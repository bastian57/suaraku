package com.daftech.suaraku.engine

import android.content.Context
import com.daftech.suaraku.audio.WavUtil
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import java.io.File

/**
 * Engine ke-4: Supertonic 3 Bahasa Indonesia (sherpa-onnx, ONNX int8) di
 * dalam HP. API diverifikasi dari source resmi k2-fsa/sherpa-onnx v1.13.8
 * (sherpa-onnx/kotlin-api/Tts.kt) DAN dari AAR 1.13.8 yang dipakai app ini:
 * OfflineTts(config) -> generateWithConfig(text, GenerationConfig) ->
 * GeneratedAudio.samples / .sampleRate. Pola kode mengikuti
 * [SherpaTtsEngine] (Piper) yang sudah ada.
 *
 * Fakta yang memengaruhi desain (semua terverifikasi, bukan tebakan):
 * - Config Supertonic TIDAK punya parameter `lang` — bahasa ditangani
 *   internal model, jadi JANGAN kirim lang ke mana pun. (Koreksi dari spike
 *   test: riset awal sempat mengira ada `{"lang": "id"}`.)
 * - `numSteps` default Kotlin = 5, sedangkan spike test memakai 8 — di sini
 *   dipakai 8 secara eksplisit supaya sama dengan yang didengar Putra.
 * - Output WAV 44.100 Hz (bukan 24 kHz seperti dugaan awal).
 * - 10 suara preset, sid 0..9. sid 3 = default (paling cepat di spike dan
 *   suara pertama yang didengar Putra). sid 0 paling lambat/bert tempo pelan.
 * - RTF di VM 0,67–0,82; RAM puncak proses ~437 MB (spike). RTF/RAM di
 *   Helio G95 tetap harus diuji di HP (vonis akhir = acceptance Putra).
 * - Supertonic TIDAK punya parameter prosody per-engine (tidak ada
 *   rate/pitch/volume seperti Edge, tidak ada arahan gaya seperti Gemini).
 *   Satu-satunya "gaya" adalah pilihan sid + teks yang masuk (DirectorPass
 *   tetap jalan: angka/singkatan dieja sebelum dikirim). JANGAN pura-pura
 *   memetakan preset VO/emosi ke angka yang tidak ada — preset tetap tampil
 *   di UI tapi tidak memengaruhi suara Supertonic.
 * - Lisensi OpenRAIL-M: output WAJIB ditandai machine-generated (dilakukan
 *   ViewModel pada info hasil generate).
 */
object SupertonicTtsEngine {

    const val SID_MIN = 0
    const val SID_MAX = 9
    const val DEFAULT_SID = 3
    const val NUM_STEPS = 8
    const val NUM_THREADS = 4

    /** Label suara di dropdown: sid 0 = "Suara 1", ..., sid 9 = "Suara 10". */
    val VOICES: List<String> = (SID_MIN..SID_MAX).map { "Suara ${it + 1}" }

    fun isValidSid(sid: Int): Boolean = sid in SID_MIN..SID_MAX

    /** Label satu sid; sid di luar 0..9 ditolak (bukan di-clamp diam-diam). */
    fun voiceLabel(sid: Int): String {
        require(isValidSid(sid)) { "sid Supertonic harus 0..9, dapat $sid" }
        return "Suara ${sid + 1}"
    }

    @Volatile private var tts: OfflineTts? = null

    fun isReady(context: Context): Boolean = SupertonicDownloader.isReady(context)

    private fun getOrCreate(context: Context): OfflineTts {
        tts?.let { return it }
        synchronized(this) {
            tts?.let { return it }
            val files = SupertonicDownloader.findModel(context)
                ?: throw IllegalStateException("Model Supertonic belum diunduh")
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    supertonic = OfflineTtsSupertonicModelConfig(
                        durationPredictor = files.durationPredictor.absolutePath,
                        textEncoder = files.textEncoder.absolutePath,
                        vectorEstimator = files.vectorEstimator.absolutePath,
                        vocoder = files.vocoder.absolutePath,
                        ttsJson = files.ttsJson.absolutePath,
                        unicodeIndexer = files.unicodeIndexer.absolutePath,
                        voiceStyle = files.voiceStyle.absolutePath
                    ),
                    numThreads = NUM_THREADS,
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

    /** Generate semua chunk -> satu WAV. speed: 1.0 = normal (slider 0.5..2.0). */
    fun synthesize(
        context: Context,
        chunks: List<String>,
        sid: Int,
        speed: Float,
        outFile: File,
        onProgress: (done: Int, total: Int) -> Unit
    ) {
        require(isValidSid(sid)) { "sid Supertonic harus 0..9, dapat $sid" }
        val engine = getOrCreate(context)
        val sampleRate = engine.sampleRate()
        val gap = FloatArray((sampleRate * 0.15).toInt()) // jeda 0,15 dtk antar potongan
        val all = mutableListOf<FloatArray>()
        chunks.forEachIndexed { idx, chunk ->
            val genConfig = GenerationConfig(
                silenceScale = 0.2f,
                speed = speed.coerceIn(0.5f, 2.0f),
                sid = sid,
                referenceAudio = null,
                referenceSampleRate = 0,
                referenceText = null,
                numSteps = NUM_STEPS,
                extra = null
            )
            val audio = engine.generateWithConfig(chunk, genConfig)
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
