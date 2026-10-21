package com.daftech.suaraku.engine

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Engine Edge Gratis — port Kotlin protokol edge-tts.
 *
 * CATATAN JUJUR: ini endpoint "Read Aloud" Microsoft Edge yang TIDAK resmi
 * (tanpa API key, tanpa kontrak). Microsoft bisa mengubah/memutusnya
 * sewaktu-waktu. Riwayat: Okt 2026 Microsoft mewajibkan token Sec-MS-GEC v2;
 * token v1 ditolak 403 saat handshake WebSocket (rany2/edge-tts issue #490).
 *
 * STRATEGI v1.0.1 (dua lapis, keduanya tetap tidak resmi):
 * 1. UTAMA — endpoint klasik speech.platform.bing.com dengan token
 *    Sec-MS-GEC V2 persis implementasi rany2/edge-tts 7.3.1 (drm.py):
 *    token "<16 hex CS64>-<64 hex SHA-256 termodifikasi>", header versi
 *    "2-143.0.3650.75". Inilah fix resmi upstream; FluentVoice Pro 1.6.4
 *    mengonfirmasi server klasik bekerja lagi dengan token v2 ini.
 * 2. FALLBACK STICKY — bila handshake tetap 403 (setelah retry koreksi
 *    clock-skew), pindah ke endpoint baru api.msedgeservices.com
 *    (param Ocp-Apim-Subscription-Key, versi "2-155.0.4283.45" sesuai
 *    tangkapan Edge 155 di issue #490) untuk potongan itu DAN semua
 *    potongan berikutnya dalam panggilan yang sama. Pola perilaku meniru
 *    FluentVoice Pro 1.6.3: hanya 403 asli yang memicu pindah server;
 *    timeout/DNS/tidak-ada-audio TIDAK memicu pindah.
 * Format pesan speech.config/SSML dan parsing frame biner audio sama di
 * kedua endpoint (terverifikasi dari source rany2 7.3.1 communicate.py
 * dan andresayac/edge-tts src/services/EdgeTTS.ts).
 */
object EdgeTtsEngine {

    val VOICES = listOf(
        "id-ID-ArdiNeural", "id-ID-GadisNeural",
        "en-US-GuyNeural", "en-US-JennyNeural", "en-US-ChristopherNeural",
        "en-GB-RyanNeural", "en-GB-SoniaNeural"
    )

    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    // Endpoint utama (klasik) — token v2, versi mengikuti rany2/edge-tts 7.3.1 constants.py
    private const val WSS_BASE =
        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
    private const val GEC_VERSION_BING = "2-143.0.3650.75"

    // Endpoint fallback (baru) — host+param dari issue #490 (net-export Edge 155)
    // dan andresayac/edge-tts constants.ts; versi "2-155.0.4283.45" dari issue #490.
    private const val WSS_BASE_MSE =
        "wss://api.msedgeservices.com/tts/cognitiveservices/websocket/v1"
    private const val GEC_VERSION_MSE = "2-155.0.4283.45"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"
    private const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"

    private val secureRandom = SecureRandom()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    class EdgeFailure(message: String, val httpCode: Int = -1, val serverDateMs: Long? = null) :
        Exception(message)

    private enum class Endpoint { BING, MSEDGESERVICES }

    // ------------------------------------------------------------------
    // Sec-MS-GEC v1 (lama, dipertahankan untuk referensi/rollback).
    // SHA-256 hex kapital dari (ticks filetime dibulatkan ke 5 menit) + token.
    // TIDAK lagi dipakai di request: server menolaknya dengan 403 sejak Okt 2026.
    // ------------------------------------------------------------------
    fun computeGec(skewMs: Long = 0L): String {
        var ticks = (System.currentTimeMillis() + skewMs) / 1000L
        ticks -= ticks % 300L
        ticks = (ticks + 11644473600L) * 10_000_000L
        val payload = ticks.toString() + TRUSTED_CLIENT_TOKEN
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    /** Sec-MS-GEC v2 untuk waktu sekarang (+ koreksi skew). */
    fun computeGecV2(skewMs: Long = 0L): String =
        GecV2.tokenAt((System.currentTimeMillis() + skewMs) / 1000.0, TRUSTED_CLIENT_TOKEN)

    /**
     * Port setia generate_sec_ms_gec_v2 dari rany2/edge-tts 7.3.1 (drm.py,
     * PR #492): token = "<CS64>-<SHA256*>" atas string "<ticks filetime><token>",
     * ticks dibulatkan ke bawah per 300 detik. SHA256* adalah varian SHA-256
     * yang konstanta/state-nya dimodifikasi Microsoft (bukan SHA-256 standar),
     * dan CS64 adalah digest 64-bit berkunci dari digest tersebut.
     * Terverifikasi byte-per-byte melawan kode Python asli pada vektor uji.
     */
    internal object GecV2 {

        private const val WIN_EPOCH = 11644473600.0

        private val SHA256_INITIAL = intArrayOf(
            0x6A09E667, 0xBB67AE85.toInt(), 0x3C6EF372, 0xA54FF53A.toInt(),
            0x510E527F, 0x9B05688C.toInt(), 0x1F83D9AB, 0x5BE0CD19
        )
        private val SHA256_STATE = intArrayOf(
            0xFD9FBEA2.toInt(), 0x91243CE7.toInt(), 0xBE499094.toInt(), 0xCD2E1E0A.toInt(),
            0x6C006F49, 0x04BD35AB, 0xFEB0A7E4.toInt(), 0x03EFFBD2
        )
        private val SHA256_SCHEDULE = intArrayOf(
            0xEB24BF9E.toInt(), 0x1558691F, 0xA0C09EE2.toInt(), 0x0CFA32BA.toInt(),
            0xA622EE8C.toInt(), 0xD1DD9491.toInt(), 0xC0DD25A0.toInt(), 0xF9E70AB6.toInt(),
            0xF0C31573.toInt(), 0x7058CE4E, 0x7188E606, 0x00E5AB33,
            0xD258B060.toInt(), 0x72766564
        )
        private val SHA256_ROUND_CONSTANTS = intArrayOf(
            0x428A2F98, 0x71374491, 0xB5C0FBCF.toInt(), 0xE9B5DBA5.toInt(),
            0x3956C25B, 0x59F111F1, 0x923F82A4.toInt(), 0xAB1C5ED5.toInt(),
            0xD807AA98.toInt(), 0x12835B01, 0x243185BE, 0x550C7DC3,
            0x72BE5D74, 0x80DEB1FE.toInt(), 0x9BDC06A7.toInt(), 0xC19BF174.toInt(),
            0xE49B69C1.toInt(), 0xEFBE4786.toInt(), 0x0FC19DC6, 0x240CA1CC,
            0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
            0x983E5152.toInt(), 0xA831C66D.toInt(), 0xB00327C8.toInt(), 0xBF597FC7.toInt(),
            0xC6E00BF3.toInt(), 0xD5A79147.toInt(), 0x06CA6351, 0x14292967,
            0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13,
            0x650A7354, 0x766A0ABB, 0x81C2C92E.toInt(), 0x92722C85.toInt(),
            0xA2BFE8A1.toInt(), 0xA81A664B.toInt(), 0xC24B8B70.toInt(), 0xC76C51A3.toInt(),
            0xD192E819.toInt(), 0xD6990624.toInt(), 0xF40E3585.toInt(), 0x106AA070,
            0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5,
            0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
            0x748F82EE, 0x78A5636F, 0x84C87814.toInt(), 0x8CC70208.toInt(),
            0x90BEFFFA.toInt(), 0xA4506CEB.toInt(), 0xBEF9A3F7.toInt(), 0xC67178F2.toInt()
        )
        private const val SHA256_ADJUSTMENT = 0xC9DC06A7.toInt()
        private const val SHA256_SIGMA0_ADJUSTMENT = 0x059C0B80
        private const val SHA256_SIGMA1_ADJUSTMENT = 0x000B92C0
        private val SHA256_BLOCK_CONSTANTS: IntArray =
            SHA256_ROUND_CONSTANTS.copyOf().also { it[14] = SHA256_ADJUSTMENT }

        // Pasangan (koefisien, offset) CS64 dari drm.py _CS64_COEFFICIENTS
        private val CS64_COEF_A = intArrayOf(
            0xE82E91B1.toInt(), 0x44DE24C6, 0x3BEB65BC, 0xC89C5933.toInt(),
            0x8D1EC211.toInt(), 0x314EF5D5, 0xE92491B1.toInt(), 0xF0A5B9D5.toInt(),
            0x06064CF3, 0xDA79FA60.toInt(), 0xBED20560.toInt(), 0x89F9C9F8.toInt(),
            0x35FA38C6, 0xCB49E3C6.toInt()
        )
        private val CS64_COEF_B = intArrayOf(
            0xB32700D0.toInt(), 0x88DFF51D.toInt(), 0x29514038, 0xB328CBDF.toInt(),
            0xD405B4D5.toInt(), 0x26E55F3C, 0x607801C6, 0x82B2C1FB.toInt(),
            0xFA51A3DA.toInt(), 0x207CC464, 0x3C3C74F3, 0xD2D14B61.toInt(),
            0xBB04A2C1.toInt(), 0xC5626167.toInt()
        )
        private const val CS64_FACTOR = 0x82893933.toInt()
        private const val CS64_ADJUSTMENT0 = 0xF2F90B1A.toInt()
        private const val CS64_ADJUSTMENT1 = 0x0B1A0000

        private fun rol16(v: Int): Int = (v shl 16) or (v ushr 16)
        private fun rotr(v: Int, n: Int): Int = Integer.rotateRight(v, n)

        // sigma kecil SHA-256 (bentuk gabungan rotasi persis drm.py _sigma0/_sigma1)
        private fun sigma0(x: Int): Int =
            (x ushr 7) xor (x shl 25) xor (x ushr 18) xor (x shl 14) xor (x ushr 3)

        private fun sigma1(x: Int): Int =
            (x ushr 17) xor (x shl 15) xor (x ushr 19) xor (x shl 13) xor (x ushr 10)

        private fun expandSchedule(words: MutableList<Int>, firstWord: Int) {
            for (index in firstWord until 64) {
                val s1 = sigma1(words[index - 2])
                val s0 = sigma0(words[index - 15])
                words.add(s1 + words[index - 7] + s0 + words[index - 16])
            }
        }

        /** Putaran SHA-256 standar; bila firstRound==14 memakai konstanta blok termodifikasi. */
        private fun sha256Rounds(state: IntArray, words: List<Int>, firstRound: Int): IntArray {
            var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
            var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
            val constants = if (firstRound == 14) SHA256_BLOCK_CONSTANTS else SHA256_ROUND_CONSTANTS
            for (i in firstRound until 64) {
                val s1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = h + s1 + ch + constants[i] + words[i]
                val s0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
                val maj = (a and b) or (c and (a or b))
                val t2 = s0 + maj
                h = g; g = f; f = e; e = d + t1
                d = c; c = b; b = a; a = t1 + t2
            }
            return intArrayOf(a, b, c, d, e, f, g, h)
        }

        /** Port _sha256_init: state awal dari 7 byte pertama data (salt). */
        private fun sha256Init(data: ByteArray): IntArray {
            val words = mutableListOf<Int>()
            repeat(14) { words.add(0) }
            // int.from_bytes(data[:3], "big") dan int.from_bytes(data[3:7], "big")
            words.add(
                ((data[0].toInt() and 0xFF) shl 16) or
                    ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
            )
            words.add(
                ((data[3].toInt() and 0xFF) shl 24) or
                    ((data[4].toInt() and 0xFF) shl 16) or
                    ((data[5].toInt() and 0xFF) shl 8) or (data[6].toInt() and 0xFF)
            )
            for (index in 16..29) {
                var value = SHA256_SCHEDULE[index - 16]
                if (index - 2 >= 14) {
                    value += if (index == 16) sigma1(words[index - 2]) xor SHA256_SIGMA1_ADJUSTMENT
                    else sigma1(words[index - 2])
                }
                if (index - 7 >= 14) value += words[index - 7]
                if (index - 15 >= 14) {
                    value += if (index == 29) sigma0(words[index - 15]) xor SHA256_SIGMA0_ADJUSTMENT
                    else sigma0(words[index - 15])
                }
                words.add(value)
            }
            words.add(
                sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14] +
                    SHA256_ADJUSTMENT - SHA256_ROUND_CONSTANTS[14]
            )
            expandSchedule(words, 31)
            val working = sha256Rounds(SHA256_STATE, words, 14)
            return IntArray(8) { (SHA256_INITIAL[it] + working[it]) }
        }

        /** Port _sha256_digest (varian SHA-256 termodifikasi). */
        private fun sha256Digest(data: ByteArray): ByteArray {
            require(data.size >= 7) { "Token salt must contain at least seven bytes" }
            var state = sha256Init(data.copyOfRange(0, 7))
            val length = 57 + data.size
            val padZeros = Math.floorMod(55 - length, 64)
            val padded = ByteArrayOutputStream()
            padded.write(data, 7, data.size - 7)
            padded.write(0x80)
            repeat(padZeros) { padded.write(0) }
            padded.write(ByteBuffer.allocate(8).putLong(length * 8L).array())
            val paddedBytes = padded.toByteArray()
            var offset = 0
            while (offset < paddedBytes.size) {
                val words = mutableListOf<Int>()
                for (j in 0 until 16) {
                    val p = offset + j * 4
                    words.add(
                        ((paddedBytes[p].toInt() and 0xFF) shl 24) or
                            ((paddedBytes[p + 1].toInt() and 0xFF) shl 16) or
                            ((paddedBytes[p + 2].toInt() and 0xFF) shl 8) or
                            (paddedBytes[p + 3].toInt() and 0xFF)
                    )
                }
                expandSchedule(words, 16)
                val working = sha256Rounds(state, words, 0)
                state = IntArray(8) { (state[it] + working[it]) }
                offset += 64
            }
            val out = ByteBuffer.allocate(32)
            for (v in state) out.putInt(v)
            return out.array()
        }

        private fun leInt(data: ByteArray, off: Int): Int =
            (data[off].toInt() and 0xFF) or
                ((data[off + 1].toInt() and 0xFF) shl 8) or
                ((data[off + 2].toInt() and 0xFF) shl 16) or
                ((data[off + 3].toInt() and 0xFF) shl 24)

        /** Port _cs64_digest -> dua kata 32-bit. */
        private fun cs64Digest(data: ByteArray, digest: ByteArray): Pair<Int, Int> {
            val keyA = leInt(digest, 0) or 1
            val keyB = leInt(digest, 4) or 1
            val factors = intArrayOf(keyA * CS64_FACTOR, keyB * CS64_FACTOR)
            val terms = mutableListOf<Int>()
            val offsets = mutableListOf<Int>()
            for (i in CS64_COEF_A.indices) {
                terms.add(CS64_COEF_A[i] * factors[i % 2])
                offsets.add(CS64_COEF_B[i])
            }
            // _words_le4_even(b"\x00" + data): kata LE 32-bit, pad genap.
            val raw = ByteArray(data.size + 1)
            System.arraycopy(data, 0, raw, 1, data.size)
            val paddedLen = (raw.size + 3) / 4 * 4
            val wordCount0 = paddedLen / 4
            val wordCount = if (wordCount0 % 2 == 1) wordCount0 + 1 else wordCount0
            for (index in 0 until wordCount) {
                var word = 0
                if (index < wordCount0) {
                    val padded = raw.copyOf(paddedLen)
                    word = leInt(padded, index * 4)
                }
                var term = word * (if (index % 2 == 0) keyA else keyB)
                var off = rol16(word) * (if (index % 2 == 0) 0xEF0569FB.toInt() else 0xC31713DB.toInt())
                if (index == 0) {
                    term += CS64_ADJUSTMENT0 * factors[0]
                    off += CS64_ADJUSTMENT1
                }
                terms.add(term)
                offsets.add(off)
            }
            var swapAcc = 0
            var swapLast = 0
            var state = 0
            var acc = 0
            var index = 0
            while (index < terms.size) {
                val x = terms[index]
                val y = terms[index + 1]
                var t = rol16(offsets[index] + x)
                var q = t * 0x76F50F34
                swapAcc += rol16(q) * 0xC3EFEA97.toInt() + q
                t = rol16(offsets[index + 1] + y)
                q = t * 0x1881CE3C
                swapLast = rol16(q) * 0x35BD1EC9 + q
                swapAcc += swapLast

                state = state * keyA + x
                state = rol16(state) * 0xCF98B111.toInt()
                state = rol16(state) * 0x87085B9F.toInt()
                state = rol16(state) * 0x12CEB96D
                state = rol16(state) * 0x257E1D83
                acc += state
                t = state * keyB + y
                t = rol16(t) * 0xA27416F5.toInt()
                t = rol16(t) * 0xD38396FF.toInt()
                state = rol16(rol16(t) * 0x7C932B89.toInt()) * 0xBFA49F69.toInt()
                acc += state
                index += 2
            }
            return Pair(swapLast xor state, swapAcc xor acc)
        }

        /** Token v2 untuk timestamp unix tertentu (dipakai juga oleh unit test vektor). */
        fun tokenAt(unixSeconds: Double, trustedToken: String): String {
            var ticks = unixSeconds
            ticks += WIN_EPOCH
            ticks -= ticks % 300.0
            ticks *= 1e7
            val strToHash = String.format(Locale.US, "%.0f", ticks) + trustedToken
            val data = strToHash.toByteArray(Charsets.US_ASCII)
            val digest = sha256Digest(data)
            val (csA, csB) = cs64Digest(data, digest)
            val cs64Bytes = ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putInt(csA).putInt(csB).array()
            val cs64 = cs64Bytes.joinToString("") { "%02X".format(it) }
            val sha = digest.joinToString("") { "%02X".format(it) }
            return "$cs64-$sha"
        }
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

    /** MUID acak 32 hex kapital (Cookie: muid=...; seperti DRM.headers_with_muid di 7.3.1). */
    private fun generateMuid(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02X".format(it) }
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

    private fun buildUrl(endpoint: Endpoint, requestId: String, skewMs: Long): String {
        val gec = computeGecV2(skewMs)
        return when (endpoint) {
            Endpoint.BING ->
                "$WSS_BASE?TrustedClientToken=$TRUSTED_CLIENT_TOKEN" +
                    "&Sec-MS-GEC=$gec&Sec-MS-GEC-Version=$GEC_VERSION_BING&ConnectionId=$requestId"
            Endpoint.MSEDGESERVICES ->
                "$WSS_BASE_MSE?Ocp-Apim-Subscription-Key=$TRUSTED_CLIENT_TOKEN" +
                    "&Sec-MS-GEC=$gec&Sec-MS-GEC-Version=$GEC_VERSION_MSE&ConnectionId=$requestId"
        }
    }

    /** Satu sesi WebSocket untuk satu chunk teks -> byte MP3. */
    private fun runSession(
        text: String, voice: String, ratePct: Int, pitchPct: Int, volumePct: Int,
        skewMs: Long, endpoint: Endpoint
    ): ByteArray {
        val requestId = UUID.randomUUID().toString().replace("-", "")
        val url = buildUrl(endpoint, requestId, skewMs)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Origin", ORIGIN)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Cookie", "muid=${generateMuid()};")
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

    /**
     * Generate semua chunk -> satu file MP3.
     * Urutan per potongan: endpoint BING (token v2) -> retry sekali dgn koreksi
     * clock-skew bila 403 -> bila tetap 403, pindah STICKY ke MSEDGESERVICES
     * (retry skew sekali juga di sana) untuk potongan ini dan seterusnya.
     * Kegagalan non-403 tidak memicu pindah endpoint (pola FluentVoice).
     */
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
        var endpoint = Endpoint.BING
        var skewRetriedOnEndpoint = false
        chunks.forEachIndexed { idx, chunk ->
            while (true) {
                try {
                    mp3All.write(
                        runSession(chunk, voice, ratePct, pitchPct, volumePct, skewMs, endpoint)
                    )
                    break
                } catch (e: EdgeFailure) {
                    if (e.httpCode != 403) {
                        throw EdgeFailure(
                            "Engine Edge gagal di potongan ${idx + 1}/${chunks.size}. " +
                                "Ini endpoint gratis tidak resmi Microsoft dan bisa berubah sewaktu-waktu. " +
                                "Detail: ${e.message}"
                        )
                    }
                    // 403: pertama coba koreksi clock-skew dari header Date server.
                    val serverDate = e.serverDateMs
                    if (!skewRetriedOnEndpoint && serverDate != null) {
                        skewMs = serverDate - System.currentTimeMillis()
                        skewRetriedOnEndpoint = true
                        continue
                    }
                    // 403 lagi: pindah sticky ke endpoint baru, sekali saja.
                    if (endpoint == Endpoint.BING) {
                        endpoint = Endpoint.MSEDGESERVICES
                        skewRetriedOnEndpoint = false
                        continue
                    }
                    throw EdgeFailure(
                        "Engine Edge gagal di potongan ${idx + 1}/${chunks.size}: " +
                            "kedua endpoint Microsoft sudah dicoba dan sama-sama menolak (403) — " +
                            "endpoint klasik speech.platform.bing.com dan endpoint baru " +
                            "api.msedgeservices.com. Ini endpoint gratis tidak resmi dan bisa " +
                            "berubah sewaktu-waktu. Detail terakhir: ${e.message}"
                    )
                }
            }
            onProgress(idx + 1, chunks.size)
        }
        outFile.writeBytes(mp3All.toByteArray())
    }
}
