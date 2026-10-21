package com.daftech.suaraku.engine

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.daftech.suaraku.audio.WavUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Cadangan Offline: TTS sistem Android (synthesizeToFile -> WAV).
 * Dipakai otomatis bila model Piper belum diunduh / gagal dimuat.
 * Locale id-ID bila tersedia di HP, fallback en-US / default.
 */
class SystemTtsEngine(private val context: Context) {

    private fun readWavSampleRate(file: File): Int {
        val bytes = file.readBytes()
        if (bytes.size < 28) return 22050
        return (bytes[24].toInt() and 0xFF) or ((bytes[25].toInt() and 0xFF) shl 8) or
            ((bytes[26].toInt() and 0xFF) shl 16) or ((bytes[27].toInt() and 0xFF) shl 24)
    }

    suspend fun synthesize(
        chunks: List<String>,
        rate: Float,
        pitch: Float,
        outFile: File,
        onProgress: (done: Int, total: Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        var ttsRef: TextToSpeech? = null
        try {
            val tts = suspendCancellableCoroutine<TextToSpeech> { cont ->
                lateinit var engine: TextToSpeech
                engine = TextToSpeech(context.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) cont.resume(engine)
                    else cont.resumeWithException(IllegalStateException("TTS sistem gagal diinisialisasi (status $status)"))
                }
            }
            ttsRef = tts
            val idLocale = Locale("id", "ID")
            val avail = tts.isLanguageAvailable(idLocale)
            if (avail >= TextToSpeech.LANG_AVAILABLE) tts.language = idLocale
            else if (tts.isLanguageAvailable(Locale.US) >= TextToSpeech.LANG_AVAILABLE) tts.language = Locale.US
            tts.setSpeechRate(rate)
            tts.setPitch(pitch)

            val tempFiles = mutableListOf<File>()
            var sampleRate = 22050
            chunks.forEachIndexed { idx, chunk ->
                val temp = File(context.cacheDir, "suaraku_sys_${idx}.wav")
                tempFiles.add(temp)
                suspendCancellableCoroutine<Unit> { cont ->
                    val uid = "sk_chunk_$idx"
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(id: String?) { if (id == uid && cont.isActive) cont.resume(Unit) }
                        @Deprecated("deprecated in API 21")
                        override fun onError(utteranceId: String?) {
                            if (utteranceId == uid && cont.isActive) {
                                cont.resumeWithException(IllegalStateException("TTS sistem error di potongan ${idx + 1}"))
                            }
                        }
                        override fun onError(utteranceId: String?, errorCode: Int) {
                            if (utteranceId == uid && cont.isActive) {
                                cont.resumeWithException(IllegalStateException("TTS sistem error ($errorCode) di potongan ${idx + 1}"))
                            }
                        }
                    })
                    val res = tts.synthesizeToFile(chunk, null, temp, uid)
                    if (res != TextToSpeech.SUCCESS && cont.isActive) {
                        cont.resumeWithException(IllegalStateException("synthesizeToFile gagal di potongan ${idx + 1}"))
                    }
                }
                if (idx == 0) sampleRate = readWavSampleRate(temp)
                onProgress(idx + 1, chunks.size)
            }
            if (tempFiles.size == 1) {
                tempFiles[0].copyTo(outFile, overwrite = true)
            } else {
                WavUtil.concatWavsPcm16(tempFiles, outFile, sampleRate)
            }
            tempFiles.forEach { it.delete() }
        } finally {
            try { ttsRef?.stop(); ttsRef?.shutdown() } catch (e: Exception) { /* abaikan */ }
        }
    }
}
