package com.daftech.suaraku.project

import android.content.Context
import com.daftech.suaraku.studio.StudioVoice
import com.daftech.suaraku.studio.StudioVoices
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Proyek Studio v1.5.0 (TERPISAH dari proyek biasa v1.3.0):
 * naskah dialog + pemetaan speaker->sid Supertonic + kecepatan.
 * Disimpan di file sendiri (`studio-projects.json`) supaya proyek biasa
 * (projects.json) tidak tersentuh — proyek lama v1.3.0/v1.4.0 tetap
 * bisa dimuat apa adanya. Pola JSON mengikuti [ProjectStore]
 * (org.json bawaan Android, tanpa dependensi tambahan).
 *
 * Catatan kompatibilitas: sid di luar 0..9 saat dimuat DIBUANG (bukan
 * di-clamp diam-diam); speaker yang kehilangan sid mendapat default
 * saat proyek dimuat di ViewModel (lihat `defaultVoiceMap`).
 *
 * v1.6.0: tambah [StudioProject.engineMap] (speaker -> engine + suara).
 * Proyek lama (hanya `voiceMap` sid) dimuat sebagai Supertonic dengan
 * sid yang sama — perilaku identik v1.5.0. Saat menyimpan, `voiceMap`
 * tetap ditulis (hanya sid Supertonic, untuk pembaca lama) di samping
 * `engineMap` yang lengkap.
 */
data class StudioProject(
    val name: String,
    val script: String,
    val voiceMap: Map<String, Int>,
    val speed: Float,
    val savedAt: Long,
    val engineMap: Map<String, StudioVoice> = emptyMap()
)

object StudioProjectStore {
    private const val FILE_NAME = "studio-projects.json"

    fun toJson(p: StudioProject): JSONObject = JSONObject().apply {
        put("name", p.name)
        put("script", p.script)
        val map = JSONObject()
        p.voiceMap.forEach { (speaker, sid) -> map.put(speaker, sid) }
        put("voiceMap", map)
        // v1.6.0: pemetaan lengkap (engine + suara per speaker).
        val engineMapJson = JSONObject()
        p.engineMap.forEach { (speaker, voice) ->
            engineMapJson.put(speaker, JSONObject().apply {
                put("engine", voice.engine)
                put("voice", voice.voice)
            })
        }
        put("engineMap", engineMapJson)
        put("speed", p.speed.toDouble())
        put("savedAt", p.savedAt)
    }

    fun fromJson(o: JSONObject): StudioProject {
        val map = mutableMapOf<String, Int>()
        val rawMap = o.optJSONObject("voiceMap")
        if (rawMap != null) {
            val keys = rawMap.keys()
            while (keys.hasNext()) {
                val speaker = keys.next()
                val sid = rawMap.optInt(speaker, -1)
                if (sid in 0..9) map[speaker] = sid
            }
        }
        // v1.6.0: baca engineMap; entri tidak valid dibuang. Bila tidak
        // ada engineMap (proyek v1.5.0), turunkan dari voiceMap lama
        // sebagai Supertonic — perilaku identik v1.5.0.
        val engineMap = mutableMapOf<String, StudioVoice>()
        val rawEngineMap = o.optJSONObject("engineMap")
        if (rawEngineMap != null) {
            val keys = rawEngineMap.keys()
            while (keys.hasNext()) {
                val speaker = keys.next()
                val entry = rawEngineMap.optJSONObject(speaker) ?: continue
                val engine = entry.optString("engine", "")
                val voice = entry.optString("voice", "")
                if (!StudioVoices.isKnownEngine(engine)) continue
                val candidate = StudioVoice(engine, voice)
                val valid = when (engine) {
                    StudioVoices.ENGINE_SUPERTONIC -> StudioVoices.sidOf(candidate) != null
                    StudioVoices.ENGINE_GEMINI, StudioVoices.ENGINE_EDGE -> voice.isNotBlank()
                    else -> true
                }
                if (valid) engineMap[speaker] = candidate
            }
        }
        if (engineMap.isEmpty()) {
            map.forEach { (speaker, sid) ->
                engineMap[speaker] = StudioVoice(StudioVoices.ENGINE_SUPERTONIC, sid.toString())
            }
        }
        return StudioProject(
            name = o.optString("name", ""),
            script = o.optString("script", ""),
            voiceMap = map,
            speed = o.optDouble("speed", 1.0).toFloat().coerceIn(0.5f, 2.0f),
            savedAt = o.optLong("savedAt", 0L),
            engineMap = engineMap
        )
    }

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /** Muat semua proyek studio, terbaru dulu. File rusak/kosong -> daftar kosong (tidak crash). */
    fun loadAll(context: Context): List<StudioProject> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            val list = mutableListOf<StudioProject>()
            for (i in 0 until arr.length()) {
                val p = fromJson(arr.getJSONObject(i))
                if (p.name.isNotEmpty()) list.add(p)
            }
            list.sortedByDescending { it.savedAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Simpan proyek studio; nama yang sama menimpa proyek lama (upsert). */
    fun save(context: Context, project: StudioProject) {
        val kept = loadAll(context).filter { it.name != project.name }
        val arr = JSONArray()
        (listOf(project) + kept).forEach { arr.put(toJson(it)) }
        file(context).writeText(arr.toString())
    }

    /** Hapus proyek studio berdasarkan nama. */
    fun delete(context: Context, name: String) {
        val kept = loadAll(context).filter { it.name != name }
        val arr = JSONArray()
        kept.forEach { arr.put(toJson(it)) }
        file(context).writeText(arr.toString())
    }
}
