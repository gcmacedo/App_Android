package com.traffia.player

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {

    companion object {
        const val APP_VERSION = "0.8.0-teste"
        const val SYNC_INTERVAL_MS = 30_000L
        const val CHECK_VALID_MS = 180_000L // checagem vale para exibições até 3 min depois
    }

    // ---- infraestrutura
    private lateinit var prefs: SharedPreferences
    private lateinit var api: Api
    private lateinit var deviceId: String
    private lateinit var secret: String
    private lateinit var location: LocationTracker
    private lateinit var presence: PresenceDetector
    private lateinit var rides: RideTracker
    private lateinit var playsQ: EventQueue
    private lateinit var checksQ: EventQueue
    private lateinit var ridesQ: EventQueue
    private val handler = Handler(Looper.getMainLooper())

    // ---- tela
    private lateinit var root: FrameLayout
    private lateinit var stage: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var qrView: QrOverlayView
    private lateinit var waitingText: TextView
    private lateinit var diagText: TextView
    private lateinit var player: ExoPlayer

    // ---- estado
    private var playing: List<Item> = emptyList()
    private var appliedVersion: Int? = null
    private var rotationDeg = 0
    private var shortCode = "…"
    private var deviceName = ""
    private var lastSyncOk: Long = 0
    private var lastSyncError: String? = null
    private var downloading: String? = null
    private var syncJob: Job? = null
    private val syncNow = Channel<Unit>(Channel.CONFLATED)

    // exibição atual
    private var curItem: Item? = null
    private var curPlayId: String = ""
    private var curStartedAt: Instant = Instant.now()
    private var curStartMs = 0L
    private var startChecked = false
    private var endChecked = false
    private var curHas: Boolean? = null
    private var curFaces: Int? = null

    // checagens
    private var checking = false
    private var lastCheckMs = 0L
    private var lastCheckAt: Instant? = null
    private var lastCheckHas: Boolean? = null
    private var lastCheckFaces = 0
    private var checksDone = 0

    private var tickCount = 0
    private val tick = object : Runnable {
        override fun run() {
            onTick()
            handler.postDelayed(this, 250)
        }
    }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            location.start()
            syncNow.trySend(Unit)
        }

    // =====================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goFullscreen()

        prefs = getSharedPreferences("traffia", Context.MODE_PRIVATE)
        deviceId = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
        secret = prefs.getString("device_secret", null)
            ?: (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "").also {
                prefs.edit().putString("device_secret", it).apply()
            }
        rotationDeg = prefs.getInt("rotation", 0)

        api = Api(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY)
        location = LocationTracker(this)
        presence = PresenceDetector(this, this)
        playsQ = EventQueue(File(filesDir, "fila_exibicoes.json"))
        checksQ = EventQueue(File(filesDir, "fila_checagens.json"))
        ridesQ = EventQueue(File(filesDir, "fila_corridas.json"))
        rides = RideTracker { ridesQ.add(it) }

        buildUi()
        buildPlayer()
        loadCachedPlaylist()
        askPermissions()
        startSyncLoop()
    }

    override fun onStart() {
        super.onStart()
        goFullscreen()
        location.start()
        if (playing.isNotEmpty()) {
            startPlay(player.currentMediaItemIndex)
            player.play()
        }
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onStop() {
        // o celular é de uso pessoal: ao sair do app, pausa e fecha a exibição em andamento
        finishCurrentPlay(false)
        curItem = null
        player.pause()
        handler.removeCallbacks(tick)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullscreen()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        syncJob?.cancel()
        location.stop()
        presence.close()
        player.release()
        super.onDestroy()
    }

    // =====================================================================
    // Tela
    private fun goFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        stage = FrameLayout(this)
        root.addView(stage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))

        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
            keepScreenOn = true
        }
        stage.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        qrView = QrOverlayView(this).apply { visibility = View.GONE }
        stage.addView(qrView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        waitingText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            setPadding(dp(24f), dp(24f), dp(24f), dp(24f))
        }
        stage.addView(waitingText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Tela de diagnóstico (fica fora do "stage" para não girar junto)
        diagText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(215, 0, 0, 0))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(16f), dp(40f), dp(16f), dp(16f))
            visibility = View.GONE
        }
        root.addView(diagText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Toque escondido: segurar o canto superior esquerdo por 2 segundos abre/fecha o diagnóstico
        val toggleDiag = Runnable {
            diagText.visibility = if (diagText.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            updateDiag()
        }
        val hotspot = View(this)
        hotspot.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> handler.postDelayed(toggleDiag, 2000)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(toggleDiag)
            }
            true
        }
        root.addView(hotspot, FrameLayout.LayoutParams(dp(120f), dp(120f), Gravity.TOP or Gravity.START))

        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> root.post { applyRotation() } }
        setContentView(root)
        updateWaiting()
    }

    /** Gira o conteúdo conforme a rotação cadastrada no painel (0, 90, 180, 270). */
    private fun applyRotation() {
        val w = root.width
        val h = root.height
        if (w == 0 || h == 0) return
        val swap = rotationDeg == 90 || rotationDeg == 270
        val sw = if (swap) h else w
        val sh = if (swap) w else h
        val lp = stage.layoutParams as FrameLayout.LayoutParams
        if (lp.width != sw || lp.height != sh) {
            lp.width = sw
            lp.height = sh
            lp.gravity = Gravity.CENTER
            stage.layoutParams = lp
        }
        stage.rotation = rotationDeg.toFloat()
    }

    private fun updateWaiting() {
        if (BuildConfig.SUPABASE_URL.isBlank()) {
            waitingText.text = "TraffIA Teste\n\nFalta o arquivo teste.properties na raiz do projeto."
            waitingText.visibility = View.VISIBLE
            return
        }
        if (playing.isEmpty()) {
            val extra = when {
                downloading != null -> "\n\nBaixando vídeos…"
                lastSyncError != null && lastSyncOk == 0L -> "\n\nSem conexão com o servidor ainda."
                else -> ""
            }
            waitingText.text = "TraffIA Teste\n\nCódigo do aparelho:\n$shortCode\n\n" +
                "Cadastre este código no painel de teste\ne monte a playlist.$extra"
            waitingText.visibility = View.VISIBLE
        } else {
            waitingText.visibility = View.GONE
        }
    }

    // =====================================================================
    // Permissões
    private fun askPermissions() {
        val need = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
        ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) location.start() else permLauncher.launch(need.toTypedArray())
    }

    // =====================================================================
    // Player
    private fun buildPlayer() {
        player = ExoPlayer.Builder(this).build()
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.volume = 1f
        playerView.player = player
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val completed = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                finishCurrentPlay(completed)
                if (mediaItem != null) startPlay(player.currentMediaItemIndex)
            }

            override fun onPlayerError(error: PlaybackException) {
                finishCurrentPlay(false)
                curItem = null
                handler.postDelayed({
                    if (playing.isEmpty()) return@postDelayed
                    if (player.mediaItemCount > 1) player.seekToNextMediaItem() else player.seekTo(0, 0)
                    player.prepare()
                    player.play()
                }, 3000)
            }
        })
    }

    private fun videoFile(path: String): File {
        val dir = File(filesDir, "videos").apply { mkdirs() }
        return File(dir, path.replace(Regex("[^A-Za-z0-9._-]"), "_"))
    }

    private fun isReady(item: Item): Boolean {
        val f = videoFile(item.path)
        return f.exists() && (item.size <= 0 || f.length() == item.size)
    }

    private fun applyPlaylist(items: List<Item>) {
        val ready = items.filter { isReady(it) }
        if (ready == playing) return
        playing = ready
        if (ready.isEmpty()) {
            finishCurrentPlay(false)
            curItem = null
            player.stop()
            player.clearMediaItems()
            qrView.visibility = View.GONE
            updateWaiting()
            return
        }
        player.setMediaItems(ready.map {
            MediaItem.Builder().setUri(Uri.fromFile(videoFile(it.path))).setMediaId(it.videoId).build()
        })
        player.prepare()
        player.play()
        updateWaiting()
    }

    private fun loadCachedPlaylist() {
        val raw = prefs.getString("playlist_json", null) ?: return
        try {
            val items = parseItems(JSONArray(raw))
            applyPlaylist(items)
            if (items.isNotEmpty() && items.all { isReady(it) }) {
                appliedVersion = prefs.getInt("playlist_version", -1).takeIf { it >= 0 }
            }
        } catch (e: Exception) {
            // cache corrompido: espera o próximo sync
        }
    }

    // =====================================================================
    // Exibições
    private fun startPlay(index: Int) {
        val item = playing.getOrNull(index)
        curItem = item
        curPlayId = UUID.randomUUID().toString()
        curStartedAt = Instant.now()
        curStartMs = SystemClock.elapsedRealtime()
        startChecked = false
        endChecked = false
        curHas = null
        curFaces = null
        qrView.setQr(item?.qr)
        qrView.visibility = View.GONE
    }

    private fun finishCurrentPlay(completed: Boolean) {
        val item = curItem ?: return
        curItem = null
        val secs = (SystemClock.elapsedRealtime() - curStartMs) / 1000.0
        if (secs < 0.5) return
        val o = JSONObject()
            .put("id", curPlayId)
            .put("video_id", item.videoId)
            .put("started_at", curStartedAt.toString())
            .put("duration_seconds", String.format(Locale.US, "%.1f", secs))
            .put("completed", completed)
        location.fresh()?.let {
            o.put("lat", it.latitude).put("lng", it.longitude).put("accuracy_m", it.accuracy.toDouble())
        }
        // passageiro: pela checagem desta exibição; se não houve, pela checagem mais recente
        val has = curHas ?: lastCheckHas?.takeIf { SystemClock.elapsedRealtime() - lastCheckMs <= CHECK_VALID_MS }
        val faces = if (curHas != null) curFaces else if (has != null) lastCheckFaces else null
        if (has != null) o.put("had_passenger", has)
        if (faces != null) o.put("faces", faces)
        playsQ.add(o)
    }

    // =====================================================================
    // Laço de 250 ms: QR Code e checagens de passageiro
    private fun onTick() {
        val item = curItem
        if (item != null && player.isPlaying) {
            val pos = player.currentPosition
            val dur = player.duration.takeIf { it > 0 } ?: item.durationMs

            // QR Code nos últimos 10 segundos (ou o vídeo inteiro se tiver até 10 s)
            val showQr = item.qr != null && dur > 0 && (dur <= 10_000 || pos >= dur - 10_000)
            val vis = if (showQr) View.VISIBLE else View.GONE
            if (qrView.visibility != vis) qrView.visibility = vis

            // Checagens só nos conteúdos da TraffIA: uma no início e outra perto do fim
            if (item.check && !checking) {
                if (!startChecked) {
                    startChecked = true
                    runCheck(item, "inicio")
                } else if (!endChecked && dur > 7_000 && pos >= dur - 3_200) {
                    endChecked = true
                    runCheck(item, "fim")
                }
            }
        }
        tickCount++
        if (tickCount % 4 == 0) {
            updateWaiting()
            if (diagText.visibility == View.VISIBLE) updateDiag()
        }
    }

    private fun runCheck(item: Item, phase: String) {
        checking = true
        presence.check { faces ->
            checking = false
            if (faces < 0) return@check // falhou: não registra
            val has = faces > 0
            val now = Instant.now()
            val loc = location.fresh()
            lastCheckMs = SystemClock.elapsedRealtime()
            lastCheckAt = now
            lastCheckHas = has
            lastCheckFaces = faces
            checksDone++
            if (curItem?.videoId == item.videoId) {
                curHas = (curHas ?: false) || has
                curFaces = maxOf(curFaces ?: 0, faces)
            }
            val o = JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("video_id", item.videoId)
                .put("checked_at", now.toString())
                .put("phase", phase)
                .put("has_person", has)
                .put("faces", faces)
            loc?.let { o.put("lat", it.latitude).put("lng", it.longitude) }
            checksQ.add(o)
            rides.onCheck(has, faces, now, loc)
        }
    }

    // =====================================================================
    // Sincronização com o servidor
    private fun startSyncLoop() {
        if (BuildConfig.SUPABASE_URL.isBlank()) return
        syncJob = lifecycleScope.launch {
            while (isActive) {
                try {
                    syncOnce()
                    lastSyncOk = System.currentTimeMillis()
                    lastSyncError = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastSyncError = e.message ?: e.javaClass.simpleName
                }
                try {
                    flushQueues()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastSyncError = "envio: " + (e.message ?: e.javaClass.simpleName)
                }
                withTimeoutOrNull(SYNC_INTERVAL_MS) { syncNow.receive() }
            }
        }
    }

    private suspend fun syncOnce() {
        val loc = location.fresh()
        val body = JSONObject()
            .put("p_device_id", deviceId)
            .put("p_secret", secret)
            .put("p_playing_version", appliedVersion ?: JSONObject.NULL)
            .put("p_app_version", APP_VERSION)
            .put("p_now_playing", curItem?.videoId ?: JSONObject.NULL)
            .put("p_free_space_mb", filesDir.usableSpace / (1024L * 1024L))
            .put("p_location_status", location.status())
        if (loc != null) {
            body.put("p_lat", loc.latitude)
                .put("p_lng", loc.longitude)
                .put("p_accuracy_m", loc.accuracy.toDouble())
                .put("p_location_at", Instant.ofEpochMilli(loc.time).toString())
        }
        val res = JSONObject(api.rpc("device_sync", body))

        shortCode = res.optString("short_code", shortCode)
        deviceName = if (res.isNull("name")) "" else res.optString("name")
        val rot = res.optInt("rotation", 0)
        if (rot != rotationDeg) {
            rotationDeg = rot
            prefs.edit().putInt("rotation", rot).apply()
            applyRotation()
        }
        val command = if (res.isNull("command")) null else res.optString("command")
        val version = res.optInt("playlist_version", 0)

        if (!res.isNull("items")) {
            val arr = res.getJSONArray("items")
            val items = parseItems(arr)
            for (it in items.distinctBy { i -> i.path }) {
                if (!isReady(it)) {
                    downloading = it.title.ifBlank { it.path }
                    updateWaiting()
                    try {
                        api.download(it.path, videoFile(it.path))
                    } finally {
                        downloading = null
                    }
                }
            }
            prefs.edit().putString("playlist_json", arr.toString()).putInt("playlist_version", version).apply()
            applyPlaylist(items)
            appliedVersion = version
            cleanupVideos(items)
        }

        when (command) {
            "restart" -> handler.post { recreate() }
            "sync" -> syncNow.trySend(Unit)
        }
    }

    private fun cleanupVideos(items: List<Item>) {
        val keep = items.map { videoFile(it.path).name }.toHashSet()
        File(filesDir, "videos").listFiles()?.forEach { f ->
            if (f.name !in keep) f.delete()
        }
    }

    private suspend fun flushQueues() {
        val plays = playsQ.peek(200)
        if (plays.isNotEmpty()) {
            api.rpc(
                "device_report_plays",
                JSONObject().put("p_device_id", deviceId).put("p_secret", secret).put("p_plays", JSONArray(plays))
            )
            playsQ.remove(plays)
        }
        val checks = checksQ.peek(200)
        val rideList = ridesQ.peek(50)
        if (checks.isNotEmpty() || rideList.isNotEmpty()) {
            api.rpc(
                "device_report_presence",
                JSONObject().put("p_device_id", deviceId).put("p_secret", secret)
                    .put("p_checks", JSONArray(checks)).put("p_rides", JSONArray(rideList))
            )
            checksQ.remove(checks)
            ridesQ.remove(rideList)
        }
    }

    // =====================================================================
    // Diagnóstico
    private fun updateDiag() {
        val fmt = SimpleDateFormat("HH:mm:ss", Locale("pt", "BR"))
        val sb = StringBuilder()
        sb.append("TraffIA Teste $APP_VERSION\n")
        sb.append("Aparelho: $shortCode ${if (deviceName.isNotBlank()) "($deviceName)" else ""}\n")
        sb.append("Playlist: versão ${appliedVersion ?: "-"} · ${playing.size} vídeo(s)\n")
        sb.append("Rotação: $rotationDeg°\n")
        curItem?.let { sb.append("Tocando: ${it.title}${if (it.check) " [conteúdo TraffIA]" else ""}\n") }
        if (playing.isNotEmpty() && playing.none { it.check }) {
            sb.append("Atenção: nenhum conteúdo TraffIA na playlist,\nentão não há checagem de passageiro.\n")
        }

        sb.append("\n— GPS —\n")
        sb.append("Status: ${location.status()}\n")
        val l = location.last
        if (l != null) {
            sb.append(String.format(Locale.US, "Posição: %.5f, %.5f\n", l.latitude, l.longitude))
            sb.append("Precisão: ${l.accuracy.toInt()} m · há ${location.ageSeconds()} s\n")
        } else sb.append("Posição: ainda sem sinal\n")

        sb.append("\n— Passageiro —\n")
        sb.append(if (checking) "Checando agora…\n" else "")
        val at = lastCheckAt
        if (at != null) {
            sb.append("Última checagem: ${fmt.format(Date.from(at))} · ")
            sb.append(if (lastCheckHas == true) "TEM passageiro ($lastCheckFaces rosto(s))\n" else "sem passageiro\n")
        } else sb.append("Última checagem: nenhuma ainda\n")
        sb.append("Checagens feitas: $checksDone\n")
        presence.lastError?.let { sb.append("Erro da câmera: $it\n") }
        sb.append("Corrida: ${rides.describe()}\n")

        sb.append("\n— Envio —\n")
        sb.append("Pendentes: ${playsQ.size} exibições · ${checksQ.size} checagens · ${ridesQ.size} corridas\n")
        sb.append("Último sync ok: ${if (lastSyncOk > 0) fmt.format(Date(lastSyncOk)) else "nenhum"}\n")
        downloading?.let { sb.append("Baixando: $it\n") }
        lastSyncError?.let { sb.append("Erro: $it\n") }
        sb.append("\nSegure o canto de novo por 2 s para fechar.")
        diagText.text = sb.toString()
    }
}
