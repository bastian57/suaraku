package com.daftech.suaraku.engine

import android.util.Base64
import com.daftech.suaraku.audio.WavUtil
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Engine Gemini Ekspresif (Google Gemini TTS).
 * POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent
 * header: x-goog-api-key: <key milik user sendiri dari Settings>
 * Respons: inlineData base64 = PCM s16le mono (default 24000 Hz) -> dibungkus WAV.
 * Gaya bicara diarahkan lewat teks arahan natural di depan teks (cara resmi Gemini 2.5 TTS).
 */
object GeminiTtsEngine {

    val VOICES = listOf(
        "Zephyr", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus", "Aoede",
        "Callirrhoe", "Autonoe", "Enceladus", "Iapetus", "Umbriel", "Algieba",
        "Despina", "Erinome", "Algenib", "Rasalgethi", "Laomedeia", "Alnilam",
        "Schedar", "Gacrux", "Pulcherrima", "Achird", "Zubenelgenubi",
        "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Bangun body JSON generateContent untuk satu chunk. Terpisah supaya gampang diverifikasi. */
    fun buildRequestJson(styledText: String, voiceName: String): JSONObject {
        val part = JSONObject().put("text", styledText)
        val parts = JSONArray().put(part)
        val content = JSONObject().put("parts", parts)
        val contents = JSONArray().put(content)

        val prebuilt = JSONObject().put("voiceName", voiceName)
        val voiceConfig = JSONObject().put("prebuiltVoiceConfig", prebuilt)
        val speechConfig = JSONObject().put("voiceConfig", voiceConfig)
        val generationConfig = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("speechConfig", speechConfig)

        return JSONObject()
            .put("contents", contents)
            .put("generationConfig", generationConfig)
    }

    private fun parseSampleRate(mimeType: String?): Int {
        if (mimeType == null) return 24000
        val m = Regex("""rate=(\d+)""").find(mimeType)
        return m?.groupValues?.get(1)?.toIntOrNull() ?: 24000
    }

    /**
     * Generate semua chunk -> satu file WAV.
     * @throws IllegalStateException dengan pesan error ASLI dari API bila gagal.
     */
    fun synthesize(
        apiKey: String,
        model: String,
        styledChunks: List<String>,
        voiceName: String,
        outFile: File,
        onProgress: (done: Int, total: Int) -> Unit
    ) {
        val pcmAll = ByteArrayOutputStream()
        var sampleRate = 24000
        styledChunks.forEachIndexed { idx, chunk ->
            val body = buildRequestJson(chunk, voiceName)
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .header("x-goog-api-key", apiKey)
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Gemini HTTP ${resp.code}: ${text.take(600)}")
                }
                val json = JSONObject(text)
                val candidates = json.optJSONArray("candidates")
                    ?: throw IllegalStateException("Respons Gemini tanpa candidates: ${text.take(400)}")
                val parts = candidates.getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                var gotAudio = false
                for (i in 0 until parts.length()) {
                    val inline = parts.getJSONObject(i).optJSONObject("inlineData") ?: continue
                    sampleRate = parseSampleRate(inline.optString("mimeType", ""))
                    val b64 = inline.getString("data")
                    pcmAll.write(Base64.decode(b64, Base64.DEFAULT))
                    gotAudio = true
                }
                if (!gotAudio) throw IllegalStateException("Respons Gemini tanpa audio: ${text.take(400)}")
            }
            onProgress(idx + 1, styledChunks.size)
        }
        WavUtil.writePcm16ToWav(outFile, pcmAll.toByteArray(), sampleRate)
    }
}
