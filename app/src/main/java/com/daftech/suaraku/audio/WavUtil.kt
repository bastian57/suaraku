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
}
