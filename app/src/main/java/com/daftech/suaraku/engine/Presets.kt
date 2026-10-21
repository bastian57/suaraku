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
    fun geminiStyledText(preset: ExpressionPreset, customStyle: String, chunk: String): String {
        val custom = customStyle.trim()
        return if (custom.isNotEmpty()) {
            "Say in the following style: $custom. The text is in Indonesian: $chunk"
        } else {
            preset.geminiDirection + chunk
        }
    }

    fun percent(v: Int): String = if (v >= 0) "+$v%" else "$v%"
}
