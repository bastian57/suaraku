package com.daftech.suaraku.studio

/**
 * Pemetaan speaker -> engine + suara untuk Studio v1.6.0 (multi-engine).
 *
 * v1.5.0 hanya mengenal sid Supertonic (Map<String, Int>). v1.6.0 tiap
 * speaker punya [StudioVoice]: engine (GEMINI / EDGE / SUPERTONIC /
 * OFFLINE — string yang sama dengan konstanta Prefs.ENGINE_*) + suara
 * spesifik engine itu:
 * - GEMINI: nama suara Gemini, mis. "Kore" (harus ada di GeminiTtsEngine.VOICES)
 * - EDGE: nama suara Edge, mis. "id-ID-ArdiNeural" (harus ada di EdgeTtsEngine.VOICES)
 * - SUPERTONIC: sid sebagai string "0".."9"
 * - OFFLINE: suara diabaikan (Piper kalau model terpasang, kalau tidak TTS sistem)
 *
 * File ini MURNI (tanpa Android) supaya bisa diuji di JVM.
 * Default per speaker = SUPERTONIC + urutan [DEFAULT_SIDS] — persis
 * perilaku v1.5.0, jadi naskah lama dan dialog Supertonic-only jalan
 * tanpa perubahan apa pun.
 */

/** Satu pemetaan suara speaker Studio. */
data class StudioVoice(val engine: String, val voice: String)

object StudioVoices {
    const val ENGINE_GEMINI = "GEMINI"
    const val ENGINE_EDGE = "EDGE"
    const val ENGINE_SUPERTONIC = "SUPERTONIC"
    const val ENGINE_OFFLINE = "OFFLINE"

    /** Engine yang dikenal Studio, sesuai urutan tampil di dropdown. */
    val ENGINES: List<String> = listOf(ENGINE_SUPERTONIC, ENGINE_GEMINI, ENGINE_EDGE, ENGINE_OFFLINE)

    fun isKnownEngine(engine: String): Boolean = engine in ENGINES

    fun engineLabel(engine: String): String = when (engine) {
        ENGINE_GEMINI -> "Gemini Ekspresif"
        ENGINE_EDGE -> "Edge Gratis"
        ENGINE_SUPERTONIC -> "Supertonic (AI)"
        ENGINE_OFFLINE -> "Offline"
        else -> "Tidak dikenal ($engine)"
    }

    /** Suara default tiap engine (dipakai saat speaker pindah engine). */
    fun defaultVoiceFor(engine: String, sidFallback: Int = 3): StudioVoice = when (engine) {
        ENGINE_GEMINI -> StudioVoice(engine, "Kore")
        ENGINE_EDGE -> StudioVoice(engine, "id-ID-ArdiNeural")
        ENGINE_SUPERTONIC -> StudioVoice(engine, sidFallback.coerceIn(0, 9).toString())
        ENGINE_OFFLINE -> StudioVoice(engine, "")
        else -> StudioVoice(ENGINE_SUPERTONIC, sidFallback.coerceIn(0, 9).toString())
    }

    /**
     * Pemetaan default untuk [speakers] (urutan [DEFAULT_SIDS]).
     * @throws IllegalArgumentException bila speaker > [MAX_SPEAKERS].
     */
    fun defaultVoiceMap(speakers: List<String>): Map<String, StudioVoice> {
        require(speakers.size <= MAX_SPEAKERS) {
            "Maksimal $MAX_SPEAKERS speaker dalam satu naskah studio, dapat ${speakers.size}"
        }
        return speakers.mapIndexed { i, name ->
            name to StudioVoice(ENGINE_SUPERTONIC, DEFAULT_SIDS[i].toString())
        }.toMap()
    }

    /** sid Supertonic dari [StudioVoice], atau null bila bukan Supertonic / bukan angka. */
    fun sidOf(voice: StudioVoice): Int? {
        if (voice.engine != ENGINE_SUPERTONIC) return null
        return voice.voice.toIntOrNull()?.takeIf { it in 0..9 }
    }

    /** Label tampilan satu pemetaan suara (dipakai kartu speaker + kartu proyek). */
    fun voiceLabel(voice: StudioVoice): String = when (voice.engine) {
        ENGINE_SUPERTONIC -> {
            val sid = sidOf(voice)
            if (sid != null) "Supertonic — Suara ${sid + 1}" else "Supertonic — suara tidak valid"
        }
        ENGINE_GEMINI -> "Gemini — ${voice.voice.ifEmpty { "(tanpa suara)" }}"
        ENGINE_EDGE -> "Edge — ${voice.voice.ifEmpty { "(tanpa suara)" }}"
        ENGINE_OFFLINE -> "Offline — otomatis (Piper / TTS sistem)"
        else -> "Tidak dikenal (${voice.engine})"
    }

    /**
     * Validasi pemetaan suara multi-engine. Mengembalikan pesan error, atau
     * null bila valid. [geminiVoices] / [edgeVoices] = daftar suara yang
     * diizinkan tiap engine (dilewatkan sebagai parameter supaya file ini
     * tetap murni tanpa dependensi ke engine Android).
     */
    fun validateVoiceMap(
        speakers: List<String>,
        voiceMap: Map<String, StudioVoice>,
        geminiVoices: List<String>,
        edgeVoices: List<String>
    ): String? {
        if (speakers.size > MAX_SPEAKERS) {
            return "Maksimal $MAX_SPEAKERS speaker dalam satu naskah studio, dapat ${speakers.size}"
        }
        for (name in speakers) {
            val voice = voiceMap[name]
                ?: return "Speaker \"$name\" belum dipetakan ke mesin + suara"
            if (!isKnownEngine(voice.engine)) {
                return "Mesin speaker \"$name\" tidak dikenal (\"${voice.engine}\") — pilih Gemini / Edge / Supertonic / Offline"
            }
            when (voice.engine) {
                ENGINE_SUPERTONIC -> {
                    if (sidOf(voice) == null) {
                        return "Suara Supertonic speaker \"$name\" tidak valid (\"${voice.voice}\", harus sid 0..9)"
                    }
                }
                ENGINE_GEMINI -> {
                    if (voice.voice.isBlank()) {
                        return "Suara Gemini speaker \"$name\" masih kosong — pilih salah satu suara Gemini"
                    }
                    if (voice.voice !in geminiVoices) {
                        return "Suara Gemini speaker \"$name\" tidak dikenal (\"${voice.voice}\")"
                    }
                }
                ENGINE_EDGE -> {
                    if (voice.voice.isBlank()) {
                        return "Suara Edge speaker \"$name\" masih kosong — pilih salah satu suara Edge"
                    }
                    if (voice.voice !in edgeVoices) {
                        return "Suara Edge speaker \"$name\" tidak dikenal (\"${voice.voice}\")"
                    }
                }
                ENGINE_OFFLINE -> {
                    // Suara diabaikan — Piper (kalau model terpasang) atau TTS sistem.
                }
            }
        }
        return null
    }
}
