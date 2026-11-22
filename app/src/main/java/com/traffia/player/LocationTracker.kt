package com.traffia.player

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/** GPS do próprio aparelho. Atualiza a cada ~10 segundos enquanto o app está aberto. */
class LocationTracker(private val ctx: Context) {

    private val client = LocationServices.getFusedLocationProviderClient(ctx)
    private var started = false

    var last: Location? = null
        private set

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { last = it }
        }
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (started || !hasPermission()) return
        started = true
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L)
            .setMinUpdateIntervalMillis(5_000L)
            .build()
        client.requestLocationUpdates(req, callback, Looper.getMainLooper())
        client.lastLocation.addOnSuccessListener { if (it != null && last == null) last = it }
    }

    fun stop() {
        if (!started) return
        client.removeLocationUpdates(callback)
        started = false
    }

    /** Idade da última posição, em segundos (null = nenhuma posição ainda). */
    fun ageSeconds(): Long? {
        val l = last ?: return null
        return (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000_000L
    }

    /** Posição válida para registrar (até 5 minutos de idade). */
    fun fresh(): Location? {
        val age = ageSeconds() ?: return null
        return if (age <= 300) last else null
    }

    fun status(): String {
        if (!hasPermission()) return "sem_permissao"
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm != null && !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return "gps_desligado"
        val age = ageSeconds() ?: return "aguardando_sinal"
        return if (age > 120) "sem_sinal_recente (${age}s)" else "ok"
    }
}
