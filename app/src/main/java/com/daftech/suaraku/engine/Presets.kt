package com.daftech.suaraku.engine

/** Preset ekspresi: satu nama, diterjemahkan per-engine (Gemini=arahan natural, Edge=prosody, Offline=kecepatan). */
data class ExpressionPreset(
    val id: String,
    val label: String,
    val geminiDirection: String,
    val edgeRatePercent: Int,
    val edgePitchPercent: Int,
    val edgeVolumePercent: Int = 0
) {
    val speedFactor: Float get() = (100 + edgeRatePercent) / 100f
}

/**
 * Preset voice-over (gaya dasar, v1.3.0). BERLAPIS dengan [ExpressionPreset]:
 * VO = gaya dasar (berita/storytelling/iklan/...), emosi = pewarna di atasnya.
 * - Gemini: arahan VO digabung dengan arahan emosi (VO dulu, lalu emosi);
 *   gaya kustom tetap menang bila diisi (perilaku lama).
 * - Edge: angka VO + angka emosi + setelan pengguna, dijumlah lalu di-clamp
 *   (rumus di [edgeRate]/[edgePitch]/[edgeVolume]).
 * - Offline: kecepatan = speed pengguna x faktor VO x faktor emosi
 *   (rumus di [offlineSpeed]); nada memakai rumus yang sama seperti Edge.
 */
data class VoiceOverPreset(
    val id: String,
    val label: String,
    /** Arahan gaya untuk Gemini. Kosong = tidak menambah arahan (perilaku v1). */
    val geminiDirection: String,
    val edgeRate: Int,
    val edgePitch: Int,
    val edgeVolume: Int = 0,
    val speedFactor: Float = 1.0f
)

object Presets {
    val ALL = listOf(
        ExpressionPreset("netral", "Netral",
            "Say in a natural, neutral tone, in Indonesian: ", 0, 0),
        ExpressionPreset("ceria", "Ceria",
            "Say in a cheerful, bright and friendly tone, in Indonesian: ", 5, 10),
        ExpressionPreset("tegas", "Tegas",
            "Say in a firm, confident and authoritative tone, in Indonesian: ", -5, -8),
        ExpressionPreset("sedih", "Sedih",
            "Say in a sad, soft and emotional tone, slowly, in Indonesian: ", -15, -5),
        ExpressionPreset("berbisik", "Berbisik",
            "Say in a soft whisper, in Indonesian: ", -10, -10, -30),
        ExpressionPreset("semangat", "Semangat",
            "Say in a very energetic, enthusiastic and motivational tone, in Indonesian: ", 10, 12)
    )

    fun byId(id: String): ExpressionPreset = ALL.firstOrNull { it.id == id } ?: ALL[0]

    /** Arahan gaya untuk Gemini; gaya kustom (teks bebas) menang bila diisi. */
    fun geminiStyledText(preset: ExpressionPreset, customStyle: String, chunk: String): String =
        geminiStyledText(VO_PRESETS[0], preset, customStyle, chunk)

    /**
     * Arahan gaya untuk Gemini dengan preset voice-over berlapis.
     * Urutan: arahan VO (gaya dasar) + arahan emosi (pewarna) + teks.
     * Preset VO "Umum" punya arahan kosong sehingga hasilnya identik dengan
     * perilaku v1 (kompatibel penuh).
     */
    fun geminiStyledText(vo: VoiceOverPreset, preset: ExpressionPreset, customStyle: String, chunk: String): String {
        val custom = customStyle.trim()
        return if (custom.isNotEmpty()) {
            "Say in the following style: $custom. The text is in Indonesian: $chunk"
        } else {
            vo.geminiDirection + preset.geminiDirection + chunk
        }
    }

    fun percent(v: Int): String = if (v >= 0) "+$v%" else "$v%"

    val VO_PRESETS = listOf(
        VoiceOverPreset("umum", "Umum", "", 0, 0, 0, 1.0f),
        VoiceOverPreset("berita", "Berita",
            "Read it like a professional news anchor — firm, clear, and steady-paced. ",
            -5, -5, 0, 0.95f),
        VoiceOverPreset("storytelling", "Storytelling",
            "Read it like a warm storyteller — expressive, engaging, slightly slow. ",
            -10, 5, 0, 0.9f),
        VoiceOverPreset("iklan", "Iklan",
            "Read it like an energetic advertisement announcer — cheerful, lively, fast-paced. ",
            10, 10, 5, 1.1f),
        VoiceOverPreset("edukasi", "Edukasi",
            "Read it like a calm teacher explaining clearly, with emphasis on key terms. ",
            -5, 0, 0, 0.95f),
        VoiceOverPreset("santai", "Santai",
            "Read it in a relaxed, friendly, casual tone. ",
            -5, 5, 0, 0.95f)
    )

    fun voById(id: String): VoiceOverPreset = VO_PRESETS.firstOrNull { it.id == id } ?: VO_PRESETS[0]

    /**
     * Rumus gabungan untuk Edge (semua dalam persen, dijumlah lalu di-clamp
     * ke rentang aman Edge): rate = VO + emosi + (speed-1)*100, clamp -50..+50;
     * pitch = VO + emosi + pitch pengguna, clamp -50..+100 (batas lama v1);
     * volume = VO + emosi, clamp -100..+100.
     */
    fun edgeRate(vo: VoiceOverPreset, preset: ExpressionPreset, speed: Float): Int =
        (vo.edgeRate + preset.edgeRatePercent + ((speed - 1f) * 100).toInt()).coerceIn(-50, 50)

    fun edgePitch(vo: VoiceOverPreset, preset: ExpressionPreset, pitchPercent: Int): Int =
        (vo.edgePitch + preset.edgePitchPercent + pitchPercent).coerceIn(-50, 100)

    fun edgeVolume(vo: VoiceOverPreset, preset: ExpressionPreset): Int =
        (vo.edgeVolume + preset.edgeVolumePercent).coerceIn(-100, 100)

    /**
     * Rumus gabungan untuk Offline: kecepatan = speed pengguna x faktor VO x
     * faktor emosi, clamp 0.3..3.0 (batas lama v1). Nada (pitch factor untuk
     * TTS sistem): (100 + VO + emosi + pitch pengguna) / 100, clamp 0.5..2.0.
     */
    fun offlineSpeed(vo: VoiceOverPreset, preset: ExpressionPreset, speed: Float): Float =
        (speed * vo.speedFactor * preset.speedFactor).coerceIn(0.3f, 3.0f)

    fun offlinePitchFactor(vo: VoiceOverPreset, preset: ExpressionPreset, pitchPercent: Int): Float =
        ((100 + vo.edgePitch + preset.edgePitchPercent + pitchPercent) / 100f).coerceIn(0.5f, 2.0f)
}
