package com.traffia.player

import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

data class Qr(val url: String, val cta: String, val bg: Int, val fg: Int)

data class Item(
    val videoId: String,
    val path: String,
    val size: Long,
    val durationMs: Long,
    val title: String,
    /** true = conteúdo da TraffIA (fundo branco): faz checagem de passageiro. */
    val check: Boolean,
    val qr: Qr?,
)

private fun color(s: String?, fallback: Int): Int =
    try { if (s.isNullOrBlank()) fallback else Color.parseColor(s.trim()) } catch (e: Exception) { fallback }

private fun JSONObject.str(name: String): String? = if (isNull(name)) null else optString(name)

fun parseItems(arr: JSONArray): List<Item> {
    val out = ArrayList<Item>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val id = o.str("video_id") ?: continue
        val path = o.str("storage_path") ?: continue
        val qrObj = if (o.isNull("qr")) null else o.optJSONObject("qr")
        val qr = qrObj?.let {
            val url = it.str("url")
            if (url.isNullOrBlank()) null else Qr(
                url = url,
                cta = it.str("cta") ?: "Escaneie o QR Code e fale conosco",
                bg = color(it.str("bg"), Color.parseColor("#33A3F5")),
                fg = color(it.str("fg"), Color.WHITE),
            )
        }
        val dur = o.str("duration_seconds")?.toDoubleOrNull() ?: 0.0
        out.add(
            Item(
                videoId = id,
                path = path,
                size = if (o.isNull("file_size")) 0L else o.optLong("file_size", 0L),
                durationMs = (dur * 1000).toLong(),
                title = o.str("title") ?: "",
                check = o.optBoolean("check", false),
                qr = qr,
            )
        )
    }
    return out
}
