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
}
