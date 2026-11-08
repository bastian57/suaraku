package com.daftech.suaraku.project

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Proyek tersimpan (v1.3.0): satu setup lengkap (teks + engine + suara +
 * preset emosi + preset voice-over + speed + pitch + gaya kustom) yang
 * disimpan sebagai JSON internal di filesDir, tanpa dependensi tambahan
 * (org.json bawaan Android).
 */
data class VoiceProject(
    val name: String,
    val text: String,
    val engine: String,
    val voiceGemini: String,
    val voiceEdge: String,
    val presetId: String,
    val voPresetId: String,
    val speed: Float,
    val pitchPercent: Int,
    val customStyle: String,
    val savedAt: Long
)

object ProjectStore {
    private const val FILE_NAME = "projects.json"
    private const val MAX_NAME_WORDS = 6

    /** Nama default proyek = 6 kata pertama teks (maks ~60 karakter). */
    fun defaultName(text: String): String {
        val words = text.replace(Regex("""\s+"""), " ").trim()
            .split(" ").filter { it.isNotEmpty() }.take(MAX_NAME_WORDS)
        val name = words.joinToString(" ")
        return if (name.length > 60) name.take(60).trimEnd() else name.ifEmpty { "Proyek tanpa judul" }
    }

    fun toJson(p: VoiceProject): JSONObject = JSONObject().apply {
        put("name", p.name)
        put("text", p.text)
        put("engine", p.engine)
        put("voiceGemini", p.voiceGemini)
        put("voiceEdge", p.voiceEdge)
        put("presetId", p.presetId)
        put("voPresetId", p.voPresetId)
        put("speed", p.speed.toDouble())
        put("pitchPercent", p.pitchPercent)
        put("customStyle", p.customStyle)
        put("savedAt", p.savedAt)
    }

    fun fromJson(o: JSONObject): VoiceProject = VoiceProject(
        name = o.optString("name", ""),
        text = o.optString("text", ""),
        engine = o.optString("engine", "GEMINI"),
        voiceGemini = o.optString("voiceGemini", "Kore"),
        voiceEdge = o.optString("voiceEdge", "id-ID-ArdiNeural"),
        presetId = o.optString("presetId", "netral"),
        voPresetId = o.optString("voPresetId", "umum"),
        speed = o.optDouble("speed", 1.0).toFloat(),
        pitchPercent = o.optInt("pitchPercent", 0),
        customStyle = o.optString("customStyle", ""),
        savedAt = o.optLong("savedAt", 0L)
    )

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /** Muat semua proyek, terbaru dulu. File rusak/kosong -> daftar kosong (tidak crash). */
    fun loadAll(context: Context): List<VoiceProject> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            val list = mutableListOf<VoiceProject>()
            for (i in 0 until arr.length()) {
                val p = fromJson(arr.getJSONObject(i))
                if (p.name.isNotEmpty()) list.add(p)
            }
            list.sortedByDescending { it.savedAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Simpan proyek; nama yang sama menimpa proyek lama (upsert). */
    fun save(context: Context, project: VoiceProject) {
        val kept = loadAll(context).filter { it.name != project.name }
        val arr = JSONArray()
        (listOf(project) + kept).forEach { arr.put(toJson(it)) }
        file(context).writeText(arr.toString())
    }

    /** Hapus proyek berdasarkan nama. */
    fun delete(context: Context, name: String) {
        val kept = loadAll(context).filter { it.name != name }
        val arr = JSONArray()
        kept.forEach { arr.put(toJson(it)) }
        file(context).writeText(arr.toString())
    }
}
