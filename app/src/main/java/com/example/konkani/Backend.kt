package com.example.konkani

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Where the app gets its translation/voice from.
 * BASE_URL empty  -> Stage 0 (Google translate + on-device speech).  ← current default
 * BASE_URL set    -> Stage 1/2 CloudBackend (our IndicTrans2 + IndicF5 on Modal).
 * Paste your deployed Modal base here to switch, e.g. "https://<ws>--konkani-backend-engine".
 */
object BackendConfig {
    const val BASE_URL = ""
}

/** Konkani text + optional audio (WAV bytes) returned by the cloud /speak. */
data class SpeakResult(val konkaniText: String, val audio: ByteArray?)

interface TranslationBackend {
    val name: String
    fun translate(text: String, source: String, target: String): String
}

/** Stage 0 backend: Google's free (unofficial, no-key) translate endpoint. */
class GoogleBackend : TranslationBackend {
    override val name = "google-free"

    override fun translate(text: String, source: String, target: String): String {
        val q = URLEncoder.encode(text, "UTF-8")
        val url = "https://translate.googleapis.com/translate_a/single" +
            "?client=gtx&sl=$source&tl=$target&dt=t&q=$q"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        try {
            conn.inputStream.use { input ->
                val body = BufferedReader(InputStreamReader(input, "UTF-8")).readText()
                val seg = JSONArray(body).getJSONArray(0)
                val sb = StringBuilder()
                for (i in 0 until seg.length()) sb.append(seg.getJSONArray(i).getString(0))
                return sb.toString()
            }
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Stage 1/2 backend: our own open-source models on Modal (see backend/modal_app.py).
 * [baseUrl] is the Modal endpoint prefix; each function targets `<baseUrl>-<fn>.modal.run`.
 */
class CloudBackend(private val baseUrl: String) : TranslationBackend {
    override val name = "cloud-indictrans2"

    override fun translate(text: String, source: String, target: String): String {
        val body = JSONObject().put("text", text).put("source", source).put("target", target)
        return JSONObject(post("$baseUrl-translate.modal.run", body.toString())).getString("translated")
    }

    /** English text -> Konkani text + Konkani audio (in [voiceId]'s voice if enrolled). */
    fun speak(englishText: String, voiceId: String?): SpeakResult {
        val body = JSONObject().put("english_text", englishText)
        if (!voiceId.isNullOrBlank()) body.put("voice_id", voiceId)
        val o = JSONObject(post("$baseUrl-speak.modal.run", body.toString()))
        val b64 = o.optString("konkani_audio_b64", "")
        val audio = if (b64.isNotEmpty()) Base64.decode(b64, Base64.DEFAULT) else null
        return SpeakResult(o.getString("konkani_text"), audio)
    }

    /** Upload a voice reference clip; returns a voice_id for use with [speak]. */
    fun enroll(userId: String, audio: ByteArray): String {
        val body = JSONObject()
            .put("user_id", userId)
            .put("consent", true)
            .put("audio_b64", Base64.encodeToString(audio, Base64.NO_WRAP))
        return JSONObject(post("$baseUrl-enroll.modal.run", body.toString())).getString("voice_id")
    }

    private fun post(url: String, json: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20000
            readTimeout = 120000 // generous: GPU cold start
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            return conn.inputStream.use { BufferedReader(InputStreamReader(it, "UTF-8")).readText() }
        } finally {
            conn.disconnect()
        }
    }
}
