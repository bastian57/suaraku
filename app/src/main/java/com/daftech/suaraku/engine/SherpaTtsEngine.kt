package com.daftech.suaraku.engine

import android.content.Context
import com.daftech.suaraku.audio.WavUtil
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * Engine Offline AI: sherpa-onnx + Piper id_ID (VITS) di dalam HP.
 * API diverifikasi dari source resmi k2-fsa/sherpa-onnx (kotlin-api/Tts.kt,
 * cocok dgn AAR v1.13.8): OfflineTts(config) -> generate(text, sid, speed)
 * -> GeneratedAudio.samples / .sampleRate.
 * Jalan 100% CPU (di HP kelas Helio G95 Piper medium real-time, RTF ~0.08
 * menurut benchmark sherpa di kelas flagship 2019). Suara datar/netral —
 * itu harga mode offline; ekspresi tetap urusan engine Gemini.
 */
object SherpaTtsEngine {

    @Volatile private var tts: OfflineTts? = null

    fun isReady(context: Context): Boolean = ModelDownloader.isReady(context)

    private fun getOrCreate(context: Context): OfflineTts {
        tts?.let { return it }
        synchronized(this) {
            tts?.let { return it }
            val files = ModelDownloader.findModel(context)
                ?: throw IllegalStateException("Model offline belum diunduh")
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = files.onnx.absolutePath,
                        lexicon = "",
                        tokens = files.tokens.absolutePath,
                        dataDir = files.espeakDir?.absolutePath ?: "",
                        noiseScale = 0.667f,
                        noiseScaleW = 0.8f,
                        lengthScale = 1.0f
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                ),
                ruleFsts = "",
                ruleFars = "",
                maxNumSentences = 1,
                silenceScale = 0.2f
            )
            val created = OfflineTts(assetManager = null, config = config)
            tts = created
            return created
        }
    }

    /** Generate semua chunk -> satu WAV. speed: 1.0 = normal. */
    fun synthesize(
        context: Context,
        chunks: List<String>,
        speed: Float,
        outFile: File,
        onProgress: (done: Int, total: Int) -> Unit
    ) {
        val engine = getOrCreate(context)
        val sampleRate = engine.sampleRate()
        val gap = FloatArray((sampleRate * 0.15).toInt()) // jeda 0,15 dtk antar potongan
        val all = mutableListOf<FloatArray>()
        chunks.forEachIndexed { idx, chunk ->
            val audio = engine.generate(text = chunk, sid = 0, speed = speed)
            all.add(audio.samples)
            if (idx < chunks.size - 1) all.add(gap)
            onProgress(idx + 1, chunks.size)
        }
        var total = 0
        for (part in all) total += part.size
        val merged = FloatArray(total)
        var pos = 0
        for (part in all) { part.copyInto(merged, pos); pos += part.size }
        WavUtil.writeFloatToWav(outFile, merged, sampleRate)
    }
}
