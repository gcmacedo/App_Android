package com.traffia.player

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Detecção de passageiro com a câmera frontal + ML Kit (roda no aparelho, sem internet).
 * Cada checagem: liga a câmera, espera 1,5 s para ela ajustar a luz, analisa até ~1,5 s
 * e desliga. As imagens ficam só na memória e são descartadas na hora: só sai o número de rostos.
 */
class PresenceDetector(private val ctx: Context, private val owner: LifecycleOwner) {

    companion object {
        const val WARMUP_MS = 1500L
        const val WINDOW_MS = 1500L
        const val TIMEOUT_MS = 5000L
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.08f)
            .build()
    )

    var lastError: String? = null
        private set

    /** Resultado na thread principal: número de rostos, ou -1 se a checagem falhou. */
    fun check(onResult: (Int) -> Unit) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            lastError = "sem permissão da câmera"
            onResult(-1)
            return
        }
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val provider = try {
                future.get()
            } catch (e: Exception) {
                lastError = "câmera indisponível: ${e.message}"
                onResult(-1)
                return@addListener
            }
            val selector = CameraSelector.DEFAULT_FRONT_CAMERA
            val hasFront = try { provider.hasCamera(selector) } catch (e: Exception) { false }
            if (!hasFront) {
                lastError = "aparelho sem câmera frontal"
                onResult(-1)
                return@addListener
            }

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val startedAt = SystemClock.elapsedRealtime()
            val done = AtomicBoolean(false)
            val maxFaces = AtomicInteger(0)
            val analyzed = AtomicInteger(0)

            val finish = Runnable {
                if (done.compareAndSet(false, true)) {
                    try { provider.unbind(analysis) } catch (e: Exception) { }
                    val n = analyzed.get()
                    if (n == 0 && lastError == null) lastError = "nenhum quadro da câmera"
                    if (n > 0) lastError = null
                    onResult(if (n == 0) -1 else maxFaces.get())
                }
            }

            analysis.setAnalyzer(executor) { proxy ->
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                if (done.get() || elapsed < WARMUP_MS) {
                    proxy.close()
                } else {
                    val faces = countFaces(proxy)
                    proxy.close()
                    if (faces >= 0) {
                        analyzed.incrementAndGet()
                        if (faces > maxFaces.get()) maxFaces.set(faces)
                    }
                    if (faces > 0 || elapsed > WARMUP_MS + WINDOW_MS) main.post(finish)
                }
            }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(owner, selector, analysis)
            } catch (e: Exception) {
                lastError = "não consegui abrir a câmera: ${e.message}"
                done.set(true)
                onResult(-1)
                return@addListener
            }
            main.postDelayed(finish, TIMEOUT_MS)
        }, ContextCompat.getMainExecutor(ctx))
    }

    /**
     * Conta rostos num quadro. Tenta primeiro na rotação informada pela câmera e,
     * se não achar ninguém, gira 90° para cada lado (o celular pode estar preso de lado).
     */
    @OptIn(ExperimentalGetImage::class)
    private fun countFaces(proxy: ImageProxy): Int {
        val media = proxy.image ?: return -1
        val base = proxy.imageInfo.rotationDegrees
        return try {
            var best = 0
            for (rot in listOf(base, (base + 90) % 360, (base + 270) % 360)) {
                val image = InputImage.fromMediaImage(media, rot)
                val faces = Tasks.await(detector.process(image), 2, TimeUnit.SECONDS)
                if (faces.size > best) best = faces.size
                if (best > 0) break
            }
            best
        } catch (e: Exception) {
            lastError = "erro na detecção: ${e.message}"
            -1
        }
    }

    fun close() {
        try { detector.close() } catch (e: Exception) { }
        executor.shutdown()
    }
}
