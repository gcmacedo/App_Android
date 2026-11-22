package com.traffia.player

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Fila de registros guardada no aparelho. Se a internet cair, nada se perde:
 * os registros ficam aqui até o envio dar certo. Usada só na thread principal.
 */
class EventQueue(private val file: File, private val max: Int = 5000) {

    private val items = ArrayList<JSONObject>()

    init {
        try {
            if (file.exists()) {
                val arr = JSONArray(file.readText())
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { items.add(it) }
            }
        } catch (e: Exception) {
            items.clear()
        }
    }

    val size: Int get() = items.size

    fun add(o: JSONObject) {
        items.add(o)
        while (items.size > max) items.removeAt(0)
        save()
    }

    fun peek(n: Int): List<JSONObject> = items.take(n)

    fun remove(sent: List<JSONObject>) {
        val ids = sent.map { it.optString("id") }.toHashSet()
        items.removeAll { it.optString("id") in ids }
        save()
    }

    private fun save() {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(JSONArray(items).toString())
            tmp.renameTo(file)
        } catch (e: Exception) {
            // sem espaço ou erro de disco: segue em memória
        }
    }
}
