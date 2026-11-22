package com.traffia.player

import android.location.Location
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Junta as checagens em corridas.
 * - Checagem positiva abre (ou mantém) a corrida.
 * - A corrida só termina depois de 3 checagens negativas seguidas.
 * - Corridas com menos de 90 segundos são descartadas.
 */
class RideTracker(private val onRide: (JSONObject) -> Unit) {

    companion object {
        const val NEGATIVES_TO_CLOSE = 3
        const val MIN_SECONDS = 90L
    }

    var active = false
        private set
    private var start: Instant? = null
    private var lastPositive: Instant? = null
    private var negatives = 0
    private var maxFaces = 0
    private var startLat: Double? = null
    private var startLng: Double? = null
    private var endLat: Double? = null
    private var endLng: Double? = null

    fun onCheck(hasPerson: Boolean, faces: Int, at: Instant, loc: Location?) {
        if (hasPerson) {
            if (!active) {
                active = true
                start = at
                maxFaces = 0
                startLat = loc?.latitude
                startLng = loc?.longitude
            }
            negatives = 0
            lastPositive = at
            maxFaces = maxOf(maxFaces, faces)
            if (loc != null) {
                endLat = loc.latitude
                endLng = loc.longitude
            }
        } else if (active) {
            negatives++
            if (negatives >= NEGATIVES_TO_CLOSE) close()
        }
    }

    private fun close() {
        val s = start
        val e = lastPositive
        active = false
        negatives = 0
        if (s == null || e == null) return
        val secs = Duration.between(s, e).seconds
        if (secs < MIN_SECONDS) return
        val o = JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("started_at", s.toString())
            .put("ended_at", e.toString())
            .put("duration_seconds", secs)
            .put("max_faces", maxOf(1, maxFaces))
        startLat?.let { o.put("start_lat", it) }
        startLng?.let { o.put("start_lng", it) }
        endLat?.let { o.put("end_lat", it) }
        endLng?.let { o.put("end_lng", it) }
        onRide(o)
    }

    fun describe(): String {
        if (!active) return "nenhuma"
        val s = start ?: return "nenhuma"
        val secs = Duration.between(s, Instant.now()).seconds
        return "em andamento há ${secs / 60}min${secs % 60}s · até ${maxFaces} pessoa(s) · negativos seguidos: $negatives/$NEGATIVES_TO_CLOSE"
    }
}
