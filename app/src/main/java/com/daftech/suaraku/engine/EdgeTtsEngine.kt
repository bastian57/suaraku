package com.daftech.suaraku.engine

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Engine Edge Gratis — port Kotlin protokol edge-tts (rany2/edge-tts),
 * pola yang sama dgn jing332 TTS Server Android.
 *
 * CATATAN JUJUR: ini endpoint "Read Aloud" Microsoft Edge yang TIDAK resmi
 * (tanpa API key, tanpa kontrak). Microsoft bisa mengubah/memutusnya
 * sewaktu-waktu (sudah pernah: perubahan token Sec-MS-GEC & migrasi endpoint
 * menyebabkan 403 massal). Bila engine ini gagal terus, pakai Gemini/Offline.
 */
object EdgeTtsEngine {

    val VOICES = listOf(
        "id-ID-ArdiNeural", "id-ID-GadisNeural",
        "en-US-GuyNeural", "en-US-JennyNeural", "en-US-ChristopherNeural",
        "en-GB-RyanNeural", "en-GB-SoniaNeural"
    )

    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val GEC_VERSION = "1-143.0.3650.75"
    private const val WSS_BASE =
        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"
    private const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    class EdgeFailure(message: String, val httpCode: Int = -1, val serverDateMs: Long? = null) :
        Exception(message)

    /** Sec-MS-GEC = SHA-256 hex kapital dari (ticks filetime dibulatkan ke 5 menit) + token. */
    fun computeGec(skewMs: Long = 0L): String {
        var ticks = (System.currentTimeMillis() + skewMs) / 1000L
        ticks -= ticks % 300L
        ticks = (ticks + 11644473600L) * 10_000_000L
        val payload = ticks.toString() + TRUSTED_CLIENT_TOKEN
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    private fun wsTimestamp(): String {
        val fmt = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private fun parseHttpDate(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val patterns = listOf("EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, dd MMM yyyy HH:mm:ss 'GMT'")
        for (p in patterns) {
            try {
                val fmt = SimpleDateFormat(p, Locale.US)
                fmt.timeZone = TimeZone.getTimeZone("UTC")
                return fmt.parse(value)?.time
            } catch (e: Exception) { /* coba pola berikutnya */ }
        }
        return null
    }

    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun buildSsml(text: String, voice: String, ratePct: Int, pitchPct: Int, volumePct: Int): String {
        val lang = if (voice.length >= 5) voice.substring(0, 5) else "id-ID"
        return "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" " +
            "xmlns:mstts=\"https://www.w3.org/2001/mstts\" xml:lang=\"$lang\">" +
            "<voice name=\"$voice\">" +
            "<prosody pitch=\"${Presets.percent(pitchPct)}\" rate=\"${Presets.percent(ratePct)}\" " +
            "volume=\"${Presets.percent(volumePct)}\">${escapeXml(text)}</prosody>" +
            "</voice></speak>"
    }

    /** Satu sesi WebSocket untuk satu chunk teks -> byte MP3. */
    private fun runSession(
        text: String, voice: String, ratePct: Int, pitchPct: Int, volumePct: Int, skewMs: Long
    ): ByteArray {
        val requestId = UUID.randomUUID().toString().replace("-", "")
        val url = "$WSS_BASE?TrustedClientToken=$TRUSTED_CLIENT_TOKEN" +
            "&Sec-MS-GEC=${computeGec(skewMs)}&Sec-MS-GEC-Version=$GEC_VERSION&ConnectionId=$requestId"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Origin", ORIGIN)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .build()

        val audio = ByteArrayOutputStream()
        val latch = CountDownLatch(1)
        var failure: EdgeFailure? = null

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val ts = wsTimestamp()
                val configJson = "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":" +
                    "{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
                    "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"
                webSocket.send(
                    "X-Timestamp:$ts\r\nContent-Type:application/json; charset=utf-8\r\n" +
                        "Path:speech.config\r\n\r\n$configJson"
                )
                val ssml = buildSsml(text, voice, ratePct, pitchPct, volumePct)
                webSocket.send(
                    "X-RequestId:$requestId\r\nContent-Type:application/ssml+xml\r\n" +
                        "X-Timestamp:${wsTimestamp()}\r\nPath:ssml\r\n\r\n$ssml"
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
                    webSocket.close(1000, null)
                    latch.countDown()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (bytes.size < 2) return
                val headerLen = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
                if (bytes.size < 2 + headerLen) return
                val header = bytes.substring(2, 2 + headerLen).utf8()
                if (header.contains("Path:audio")) {
                    val payload = bytes.substring(2 + headerLen)
                    synchronized(audio) { audio.write(payload.toByteArray()) }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: -1
                val dateMs = parseHttpDate(response?.header("Date"))
                failure = EdgeFailure(
                    "Edge gagal (HTTP $code): ${t.message ?: "koneksi WebSocket putus"}", code, dateMs
                )
                latch.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                latch.countDown()
            }
        }

        client.newWebSocket(request, listener)
        val finished = latch.await(75, TimeUnit.SECONDS)
        if (!finished) throw EdgeFailure("Edge timeout: tidak ada respons turn.end dalam 75 detik")
        failure?.let { throw it }
        val bytes = audio.toByteArray()
        if (bytes.isEmpty()) throw EdgeFailure("Edge tidak mengirim audio (kemungkinan token/endpoint berubah)")
        return bytes
    }

    /** Generate semua chunk -> satu file MP3. Retry sekali dgn koreksi clock-skew saat 403. */
    fun synthesize(
        chunks: List<String>,
        voice: String,
        ratePct: Int,
        pitchPct: Int,
        volumePct: Int,
        outFile: File,
        onProgress: (done: Int, total: Int) -> Unit
    ) {
        val mp3All = ByteArrayOutputStream()
        var skewMs = 0L
        chunks.forEachIndexed { idx, chunk ->
            var attempt = 0
            while (true) {
                try {
                    mp3All.write(runSession(chunk, voice, ratePct, pitchPct, volumePct, skewMs))
                    break
                } catch (e: EdgeFailure) {
                    val serverDate = e.serverDateMs
                    if (e.httpCode == 403 && serverDate != null && attempt == 0) {
                        skewMs = serverDate - System.currentTimeMillis()
                        attempt++
                    } else {
                        throw EdgeFailure(
                            "Engine Edge gagal di potongan ${idx + 1}/${chunks.size}. " +
                                "Ini endpoint gratis tidak resmi Microsoft dan bisa berubah sewaktu-waktu. " +
                                "Detail: ${e.message}"
                        )
                    }
                }
            }
            onProgress(idx + 1, chunks.size)
        }
        outFile.writeBytes(mp3All.toByteArray())
    }
}
