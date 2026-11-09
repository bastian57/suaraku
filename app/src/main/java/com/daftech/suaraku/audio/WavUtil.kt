package com.daftech.suaraku.audio

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/** Util WAV PCM s16le: bungkus PCM mentah (Gemini) & float samples (sherpa) jadi file WAV. */
object WavUtil {

    fun header(dataSize: Int, sampleRate: Int, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val h = ByteArrayOutputStream(44)
        fun str(s: String) { h.write(s.toByteArray(Charsets.US_ASCII)) }
        fun int32(v: Int) { h.write(v and 0xFF); h.write((v shr 8) and 0xFF); h.write((v shr 16) and 0xFF); h.write((v shr 24) and 0xFF) }
        fun int16(v: Int) { h.write(v and 0xFF); h.write((v shr 8) and 0xFF) }
        str("RIFF"); int32(36 + dataSize); str("WAVE")
        str("fmt "); int32(16); int16(1); int16(channels)
        int32(sampleRate); int32(byteRate); int16(blockAlign); int16(bitsPerSample)
        str("data"); int32(dataSize)
        return h.toByteArray()
    }

    /** PCM s16le mentah -> file WAV. */
    fun writePcm16ToWav(file: File, pcm: ByteArray, sampleRate: Int, channels: Int = 1) {
        FileOutputStream(file).use { out ->
            out.write(header(pcm.size, sampleRate, channels))
            out.write(pcm)
        }
    }

    /** Float samples [-1..1] (output sherpa-onnx) -> file WAV PCM16. */
    fun writeFloatToWav(file: File, samples: FloatArray, sampleRate: Int) {
        val pcm = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            pcm[i * 2] = (v and 0xFF).toByte()
            pcm[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        writePcm16ToWav(file, pcm, sampleRate)
    }

    /** Gabung beberapa WAV PCM16 dgn sampleRate sama (dipakai System TTS per-chunk). */
    fun concatWavsPcm16(files: List<File>, outFile: File, sampleRate: Int) {
        val pcm = ByteArrayOutputStream()
        for (f in files) {
            val bytes = f.readBytes()
            if (bytes.size > 44) pcm.write(bytes, 44, bytes.size - 44)
        }
        writePcm16ToWav(outFile, pcm.toByteArray(), sampleRate)
    }

    /**
     * Hening PCM16 (nol) selama [seconds] detik (v1.5.0, jeda Studio).
     * Catatan: seluruh hening dipegang di RAM — jeda studio di-clamp maks
     * 3 dtk, jadi puncaknya ~265 KB di 44.100 Hz, aman.
     */
    fun silencePcm16(seconds: Float, sampleRate: Int): ByteArray {
        if (seconds <= 0f || sampleRate <= 0) return ByteArray(0)
        val frames = (seconds * sampleRate).toInt().coerceAtLeast(0)
        return ByteArray(frames * 2) // s16le mono: 2 byte per frame, semua nol
    }

    /** Tulis file WAV berisi hening selama [seconds] detik. */
    fun writeSilenceToWav(file: File, seconds: Float, sampleRate: Int) {
        writePcm16ToWav(file, silencePcm16(seconds, sampleRate), sampleRate)
    }

    /**
     * Durasi file WAV (milidetik) dari header kanonik 44 byte
     * (RIFF/WAVE + sampleRate di offset 24 + ukuran data di offset 40).
     * File bukan WAV / header rusak / terlalu pendek -> -1.
     * Hanya membaca header, bukan seluruh isi file.
     */
    fun wavDurationMs(file: File): Long {
        return try {
            if (!file.isFile || file.length() < 44) return -1
            val h = ByteArray(44)
            file.inputStream().use { it.read(h) }
            if (h[0] != 'R'.code.toByte() || h[1] != 'I'.code.toByte() ||
                h[2] != 'F'.code.toByte() || h[3] != 'F'.code.toByte()
            ) return -1
            if (h[8] != 'W'.code.toByte() || h[9] != 'A'.code.toByte() ||
                h[10] != 'V'.code.toByte() || h[11] != 'E'.code.toByte()
            ) return -1
            fun le32(off: Int): Long =
                (h[off].toLong() and 0xFF) or
                    ((h[off + 1].toLong() and 0xFF) shl 8) or
                    ((h[off + 2].toLong() and 0xFF) shl 16) or
                    ((h[off + 3].toLong() and 0xFF) shl 24)
            fun le16(off: Int): Int =
                (h[off].toInt() and 0xFF) or ((h[off + 1].toInt() and 0xFF) shl 8)
            val sampleRate = le32(24)
            val channels = le16(22)
            val bits = le16(34)
            val dataSize = le32(40)
            if (sampleRate <= 0 || channels <= 0 || bits <= 0) return -1
            val bytesPerSec = sampleRate * channels * bits / 8
            if (bytesPerSec <= 0) return -1
            dataSize * 1000 / bytesPerSec
        } catch (e: Exception) {
            -1
        }
    }

    // ---- v1.6.0: pipeline multi-engine Studio (semua segmen -> PCM16 mono 44,1 kHz) ----

    /** Satu file WAV PCM16 yang sudah dibaca (selalu mono setelah downmix). */
    data class WavPcm(val sampleRate: Int, val samples: ShortArray)

    /**
     * Baca file WAV PCM16 jadi [WavPcm] mono. Header di-parse dengan
     * berjalan melewati chunk RIFF (bukan asumsi header 44 byte — output
     * TTS sistem boleh membawa chunk tambahan); stereo di-downmix ke mono
     * (rata-rata kedua kanal). Format bukan PCM (format != 1), bits != 16,
     * atau file rusak -> null.
     */
    fun readWavPcm16(file: File): WavPcm? {
        return try {
            if (!file.isFile || file.length() < 44) return null
            val bytes = file.readBytes()
            fun strAt(off: Int): String =
                String(bytes, off, 4, Charsets.US_ASCII)
            fun le32(off: Int): Int =
                (bytes[off].toInt() and 0xFF) or
                    ((bytes[off + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[off + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[off + 3].toInt() and 0xFF) shl 24)
            fun le16(off: Int): Int =
                (bytes[off].toInt() and 0xFF) or ((bytes[off + 1].toInt() and 0xFF) shl 8)
            if (strAt(0) != "RIFF" || strAt(8) != "WAVE") return null
            var pos = 12
            var sampleRate = -1
            var channels = -1
            var bits = -1
            var dataOff = -1
            var dataSize = -1
            while (pos + 8 <= bytes.size) {
                val id = strAt(pos)
                val size = le32(pos + 4)
                val body = pos + 8
                if (body + size > bytes.size && id != "data") return null
                when (id) {
                    "fmt " -> {
                        if (size < 16) return null
                        if (le16(body) != 1) return null // hanya PCM
                        channels = le16(body + 2)
                        sampleRate = le32(body + 4)
                        bits = le16(body + 14)
                    }
                    "data" -> {
                        dataOff = body
                        dataSize = minOf(size, bytes.size - body)
                    }
                }
                // Chunk di-pad ke genap.
                pos = body + size + (size and 1)
            }
            if (sampleRate <= 0 || channels <= 0 || bits != 16) return null
            if (dataOff < 0 || dataSize < 0) return null
            val frames = dataSize / (2 * channels)
            if (frames <= 0) return null
            val out = ShortArray(frames)
            for (i in 0 until frames) {
                val base = dataOff + i * 2 * channels
                if (channels == 1) {
                    out[i] = le16(base).toShort()
                } else {
                    var sum = 0
                    for (c in 0 until channels) sum += le16(base + c * 2).toShort().toInt()
                    out[i] = (sum / channels).coerceIn(-32768, 32767).toShort()
                }
            }
            WavPcm(sampleRate, out)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Resample PCM16 mono dari [fromRate] ke [toRate] (interpolasi kubik
     * Catmull-Rom — kualitas layak untuk suara, jauh lebih halus dari
     * linear di rasio non-bulat seperti 24.000 -> 44.100). Rate sama =
     * salinan apa adanya; input kosong = kosong; rate tidak valid =
     * IllegalArgumentException. Output selalu di-clamp ke [-32768, 32767].
     */
    fun resamplePcm16(samples: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        require(fromRate > 0) { "sample rate asal harus > 0, dapat $fromRate" }
        require(toRate > 0) { "sample rate tujuan harus > 0, dapat $toRate" }
        if (samples.isEmpty()) return ShortArray(0)
        if (fromRate == toRate) return samples.copyOf()
        val ratio = fromRate.toDouble() / toRate
        // Panjang output dihitung dengan aritmetika bulat (bukan float)
        // supaya 24.000 -> 44.100 tepat 44.100, bukan 44.099 (galat float).
        val outLen = ((samples.size.toLong() * toRate) / fromRate).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            val pos = i * ratio
            val idx = pos.toInt()
            val frac = (pos - idx).toFloat()
            // Tetangga kubik (tepi di-clamp ke sampel terluar).
            val p0 = samples[(idx - 1).coerceIn(0, samples.size - 1)].toFloat()
            val p1 = samples[idx.coerceIn(0, samples.size - 1)].toFloat()
            val p2 = samples[(idx + 1).coerceIn(0, samples.size - 1)].toFloat()
            val p3 = samples[(idx + 2).coerceIn(0, samples.size - 1)].toFloat()
            val v = 0.5f * (
                (2f * p1) +
                    (-p0 + p2) * frac +
                    (2f * p0 - 5f * p1 + 4f * p2 - p3) * frac * frac +
                    (-p0 + 3f * p1 - 3f * p2 + p3) * frac * frac * frac
                )
            out[i] = v.toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /** ShortArray PCM16 -> ByteArray s16le. */
    fun pcm16ToBytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = samples[i].toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Target sample rate gabungan Studio: 44.100 Hz mono PCM16. */
    const val STUDIO_MIX_RATE = 44100

    /**
     * Konversi satu file segmen (WAV apa pun ratenya, atau MP3 Edge) jadi
     * file WAV PCM16 mono 44.100 Hz di [dstFile]. Mengembalikan true bila
     * berhasil. MP3 didekode lewat [Mp3Decoder] (MediaExtractor/MediaCodec
     * bawaan Android — HANYA jalan di HP, tidak di JVM polos).
     * File tidak dikenal / decode gagal / resample gagal -> false.
     */
    fun convertToWav44100(srcFile: File, dstFile: File): Boolean {
        return try {
            val pcm: WavPcm = when (srcFile.extension.lowercase()) {
                "wav" -> readWavPcm16(srcFile)
                "mp3" -> Mp3Decoder.decodeToPcm16(srcFile)?.let { (rate, samples) ->
                    WavPcm(rate, samples)
                }
                else -> null
            } ?: return false
            val resampled = resamplePcm16(pcm.samples, pcm.sampleRate, STUDIO_MIX_RATE)
            writePcm16ToWav(dstFile, pcm16ToBytes(resampled), STUDIO_MIX_RATE)
            true
        } catch (e: Exception) {
            false
        }
    }
}
