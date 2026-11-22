package com.traffia.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Conversa com o Supabase (banco traffia-teste) do mesmo jeito que o app 0.7.0. */
class Api(private val baseUrl: String, private val key: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = "application/json".toMediaType()

    suspend fun rpc(name: String, body: JSONObject): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/rest/v1/rpc/$name")
            .header("apikey", key)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(json))
            .build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("$name: HTTP ${r.code} ${text.take(200)}")
            text
        }
    }

    /** Baixa um vídeo do bucket público "videos" para [dest]. */
    suspend fun download(storagePath: String, dest: File) = withContext(Dispatchers.IO) {
        val encoded = storagePath.split("/").joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        val req = Request.Builder()
            .url("$baseUrl/storage/v1/object/public/videos/$encoded")
            .header("apikey", key)
            .build()
        val tmp = File(dest.parentFile, dest.name + ".part")
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("download ${r.code}: $storagePath")
            val body = r.body ?: throw IOException("download vazio: $storagePath")
            tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) throw IOException("não consegui salvar $storagePath")
    }
}
