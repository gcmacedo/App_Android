package com.traffia.player

import org.json.JSONObject
import java.util.Calendar

/**
 * Brilho da tela por horário (configurado por carro no servidor).
 * - Dia: brilho [day]; noite (de [nightStart] até [nightEnd]): brilho [night].
 * - Limites: 25% a 100%.
 * - Desligado: o celular usa o brilho dele mesmo.
 * Vale só enquanto o app está aberto; ao sair, o celular volta ao brilho normal.
 */
data class BrightnessCfg(
    val enabled: Boolean = true,
    val day: Int = 100,
    val night: Int = 40,
    val nightStart: Int = 18 * 60, // minutos desde 00:00
    val nightEnd: Int = 6 * 60,
) {
    companion object {
        const val MIN = 25
        const val MAX = 100

        fun clamp(p: Int) = p.coerceIn(MIN, MAX)

        fun parseTime(s: String?, fallback: Int): Int {
            if (s.isNullOrBlank()) return fallback
            val parts = s.trim().split(":")
            val h = parts.getOrNull(0)?.toIntOrNull() ?: return fallback
            val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
            return ((h.coerceIn(0, 23) * 60) + m.coerceIn(0, 59))
        }

        fun fmt(min: Int): String {
            val m = ((min % 1440) + 1440) % 1440
            return String.format("%02d:%02d", m / 60, m % 60)
        }

        fun fromJson(o: JSONObject?): BrightnessCfg? {
            if (o == null) return null
            val d = BrightnessCfg()
            return BrightnessCfg(
                enabled = o.optBoolean("enabled", d.enabled),
                day = clamp(o.optInt("day", d.day)),
                night = clamp(o.optInt("night", d.night)),
                nightStart = parseTime(if (o.isNull("night_start")) null else o.optString("night_start"), d.nightStart),
                nightEnd = parseTime(if (o.isNull("night_end")) null else o.optString("night_end"), d.nightEnd),
            )
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("day", day)
        .put("night", night)
        .put("night_start", fmt(nightStart))
        .put("night_end", fmt(nightEnd))

    fun isNight(minuteOfDay: Int): Boolean =
        if (nightStart == nightEnd) false
        else if (nightStart < nightEnd) minuteOfDay in nightStart until nightEnd
        else minuteOfDay >= nightStart || minuteOfDay < nightEnd // atravessa a meia-noite

    /** Brilho a aplicar agora (25–100), ou null = brilho do próprio celular. */
    fun targetNow(): Int? {
        if (!enabled) return null
        val c = Calendar.getInstance()
        val min = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return if (isNight(min)) night else day
    }
}
