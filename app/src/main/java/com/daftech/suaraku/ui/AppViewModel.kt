package com.daftech.suaraku.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.daftech.suaraku.Prefs
import com.daftech.suaraku.audio.AudioStore
import com.daftech.suaraku.director.DirectorPass
import com.daftech.suaraku.engine.EdgeTtsEngine
import com.daftech.suaraku.engine.GeminiTtsEngine
import com.daftech.suaraku.engine.ModelDownloader
import com.daftech.suaraku.engine.Presets
import com.daftech.suaraku.engine.SherpaTtsEngine
import com.daftech.suaraku.engine.SystemTtsEngine
import com.daftech.suaraku.project.ProjectStore
import com.daftech.suaraku.project.VoiceProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AppViewModel(application: Application) : AndroidViewModel(application) {

    val prefs = Prefs(application)
    private val ctx get() = getApplication<Application>()

    // ---- Form state ----
    var text by mutableStateOf("")
    var engine by mutableStateOf(prefs.defaultEngine)
    var voiceGemini by mutableStateOf("Kore")
    var voiceEdge by mutableStateOf("id-ID-ArdiNeural")
    var presetId by mutableStateOf("netral")
    var voPresetId by mutableStateOf("umum")
    var customStyle by mutableStateOf("")
    var speed by mutableStateOf(1.0f)          // 0.5 - 2.0
    var pitchPercent by mutableStateOf(0)      // -50 - +50 (Edge & TTS sistem)
    var directorEnabled by mutableStateOf(prefs.directorEnabled)
    var hasGeminiKey by mutableStateOf(prefs.geminiKey.isNotBlank())

    fun setDirector(v: Boolean) {
        directorEnabled = v
        prefs.directorEnabled = v
    }

    // ---- Proses ----
    var generating by mutableStateOf(false)
    var progressText by mutableStateOf("")
    var progressFraction by mutableStateOf(0f)
    var error by mutableStateOf<String?>(null)
    var info by mutableStateOf<String?>(null)
    var lastFile by mutableStateOf<File?>(null)

    // ---- Batch generate (v1.3.0) ----
    var batchItems by mutableStateOf<List<BatchItem>>(emptyList())
    var batchRunning by mutableStateOf(false)
    var batchSummary by mutableStateOf<String?>(null)

    // ---- Proyek tersimpan (v1.3.0) ----
    var projects by mutableStateOf<List<VoiceProject>>(emptyList())
    var projectName by mutableStateOf("")

    // ---- Riwayat & model offline ----
    var history by mutableStateOf<List<File>>(emptyList())
    var modelReady by mutableStateOf(false)
    var modelDownloading by mutableStateOf(false)
    var modelProgress by mutableStateOf(0)

    init {
        refreshStatus()
        refreshProjects()
    }

    fun refreshStatus() {
        history = AudioStore.listHistory(ctx)
        modelReady = SherpaTtsEngine.isReady(ctx)
    }

    fun refreshProjects() {
        projects = ProjectStore.loadAll(ctx)
    }

    fun selectEngine(e: String) {
        engine = e
        prefs.defaultEngine = e
    }

    fun downloadModel() {
        if (modelDownloading) return
        viewModelScope.launch {
            modelDownloading = true
            error = null
            try {
                withContext(Dispatchers.IO) {
                    ModelDownloader.downloadAndExtract(ctx) { pct -> modelProgress = pct }
                }
                modelReady = true
                info = "Model offline Piper Indonesia berhasil diunduh. Engine Offline sekarang memakai AI lokal."
            } catch (e: Exception) {
                error = "Gagal unduh model offline: ${e.message}"
            } finally {
                modelDownloading = false
            }
        }
    }

    /**
     * Tulis progres ke Compose state — SELALU lewat Main thread.
     * Dipanggil dari callback engine yang berjalan di thread IO, jadi
     * penulisan state tidak boleh dilakukan langsung di thread pemanggil
     * (pelajaran ClipKu v1.4.0: tulis state dari IO = crash snapshot).
     */
    private fun reportProgress(label: String, done: Int, total: Int) {
        viewModelScope.launch(Dispatchers.Main) {
            progressText = "$label: potongan $done dari $total"
            progressFraction = if (total > 0) done.toFloat() / total else 0f
        }
    }

    /** Tag engine + ekstensi file untuk nama output. */
    private fun engineTag(): String = when (engine) {
        Prefs.ENGINE_GEMINI -> "gemini"
        Prefs.ENGINE_EDGE -> "edge"
        else -> "offline"
    }

    private fun outputExt(): String = if (engine == Prefs.ENGINE_EDGE) "mp3" else "wav"

    /**
     * Inti generate: chunking per-engine + layering preset VO & emosi,
     * lalu dispatch ke engine yang dipilih. Dipakai generate biasa maupun
     * tiap item batch. Harus dipanggil dari coroutine (IO dilakukan di
     * dalam dengan withContext); progres dilaporkan lewat [reportProgress].
     */
    private suspend fun synthesizeText(text: String, out: File, progressLabel: String) {
        val chunks = DirectorPass.chunkFor(engine, text, directorEnabled)
        if (chunks.isEmpty()) throw IllegalStateException("Teks kosong setelah dibersihkan")
        val preset = Presets.byId(presetId)
        val vo = Presets.voById(voPresetId)
        when (engine) {
            Prefs.ENGINE_GEMINI -> {
                val key = prefs.geminiKey
                if (key.isBlank()) throw IllegalStateException(
                    "API key Gemini belum diisi. Buka Pengaturan (ikon gerigi) lalu tempel API key Gemini milikmu sendiri."
                )
                val styled = chunks.map { Presets.geminiStyledText(vo, preset, customStyle, it) }
                withContext(Dispatchers.IO) {
                    GeminiTtsEngine.synthesize(key, prefs.geminiModel, styled, voiceGemini, out) { d, t ->
                        reportProgress(progressLabel, d, t)
                    }
                }
            }
            Prefs.ENGINE_EDGE -> {
                val rate = Presets.edgeRate(vo, preset, speed)
                val pitch = Presets.edgePitch(vo, preset, pitchPercent)
                val volume = Presets.edgeVolume(vo, preset)
                withContext(Dispatchers.IO) {
                    EdgeTtsEngine.synthesize(chunks, voiceEdge, rate, pitch, volume, out) { d, t ->
                        reportProgress(progressLabel, d, t)
                    }
                }
            }
            else -> { // OFFLINE
                val effectiveSpeed = Presets.offlineSpeed(vo, preset, speed)
                val pitchFactor = Presets.offlinePitchFactor(vo, preset, pitchPercent)
                var usedSystemFallback = false
                if (SherpaTtsEngine.isReady(ctx)) {
                    try {
                        withContext(Dispatchers.IO) {
                            SherpaTtsEngine.synthesize(ctx, chunks, effectiveSpeed, out) { d, t ->
                                reportProgress("$progressLabel (Piper AI)", d, t)
                            }
                        }
                    } catch (e: Exception) {
                        usedSystemFallback = true
                    }
                } else {
                    usedSystemFallback = true
                }
                if (usedSystemFallback) {
                    withContext(Dispatchers.IO) {
                        SystemTtsEngine(ctx).synthesize(chunks, effectiveSpeed, pitchFactor, out) { d, t ->
                            reportProgress("$progressLabel (TTS Sistem)", d, t)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        info = "Catatan: mode offline memakai TTS sistem (model Piper belum siap/gagal dimuat)."
                    }
                }
            }
        }
    }

    fun generate() {
        if (generating || batchRunning) return
        val raw = text.trim()
        if (raw.isEmpty()) { error = "Teks masih kosong — tulis atau impor file .txt dulu."; return }
        viewModelScope.launch {
            generating = true
            error = null
            info = null
            lastFile = null
            progressFraction = 0f
            progressText = "Menyiapkan teks (director pass)..."
            try {
                val out = AudioStore.newNamedOutputFile(ctx, engineTag(), voPresetId, 1, outputExt())
                synthesizeText(raw, out, "Membuat suara")
                lastFile = out
                progressText = "Selesai"
                progressFraction = 1f
                val doneMsg = "Berhasil: ${out.name} (${out.length() / 1024} KB)"
                info = if (info == null) doneMsg else info + "\n" + doneMsg
                refreshStatus()
            } catch (e: Exception) {
                error = e.message ?: "Terjadi kesalahan tidak dikenal"
                progressText = ""
            } finally {
                generating = false
            }
        }
    }

    /** Batch: tiap bagian (pemisah = baris kosong / garis ---) jadi 1 file; gagal tidak menghentikan antrean. */
    fun generateBatch() {
        if (generating || batchRunning) return
        val parts = splitBatchItems(text)
        if (parts.isEmpty()) { error = "Teks masih kosong — tulis atau impor file .txt dulu."; return }
        batchItems = parts.mapIndexed { idx, part -> BatchItem(idx + 1, part) }
        batchSummary = null
        batchRunning = true
        error = null
        info = null
        progressFraction = 0f
        progressText = "Batch: menyiapkan ${parts.size} bagian..."
        viewModelScope.launch {
            try {
                val tag = engineTag()
                val ext = outputExt()
                val finalItems = executeBatch(
                    items = batchItems,
                    process = { item ->
                        val out = AudioStore.newNamedOutputFile(ctx, tag, voPresetId, item.id, ext)
                        synthesizeText(item.text, out, "Bagian ${item.id}")
                        out
                    },
                    onItemUpdate = { updated ->
                        batchItems = batchItems.map { if (it.id == updated.id) updated else it }
                        val doneCount = batchItems.count { it.status == BatchStatus.DONE || it.status == BatchStatus.FAILED }
                        progressFraction = doneCount.toFloat() / batchItems.size
                        progressText = "Batch: $doneCount dari ${batchItems.size} bagian"
                    }
                )
                batchItems = finalItems
                batchSummary = summarizeBatch(finalItems)
                progressFraction = 1f
                progressText = "Batch selesai"
                val okCount = finalItems.count { it.status == BatchStatus.DONE }
                if (okCount > 0) {
                    lastFile = finalItems.lastOrNull { it.status == BatchStatus.DONE }?.outputFile
                }
                refreshStatus()
            } catch (e: Exception) {
                error = e.message ?: "Batch gagal tanpa pesan"
            } finally {
                batchRunning = false
            }
        }
    }

    /** Simpan SEMUA hasil batch yang berhasil ke Download/SuaraKu. */
    fun saveAllBatchToDownloads() {
        val files = batchItems.filter { it.status == BatchStatus.DONE }.mapNotNull { it.outputFile }
        if (files.isEmpty()) { info = "Belum ada hasil batch yang berhasil disimpan."; return }
        viewModelScope.launch {
            var ok = 0
            withContext(Dispatchers.IO) {
                for (f in files) {
                    if (AudioStore.saveToDownloads(ctx, f) != null) ok++
                }
            }
            info = "Tersimpan $ok dari ${files.size} file batch ke Download/SuaraKu."
        }
    }

    // ---- Proyek tersimpan ----

    fun saveCurrentProject() {
        val raw = text.trim()
        if (raw.isEmpty()) { error = "Teks masih kosong — tidak ada yang bisa disimpan sebagai proyek."; return }
        val name = projectName.trim().ifEmpty { ProjectStore.defaultName(raw) }
        ProjectStore.save(
            ctx,
            VoiceProject(
                name = name,
                text = raw,
                engine = engine,
                voiceGemini = voiceGemini,
                voiceEdge = voiceEdge,
                presetId = presetId,
                voPresetId = voPresetId,
                speed = speed,
                pitchPercent = pitchPercent,
                customStyle = customStyle,
                savedAt = System.currentTimeMillis()
            )
        )
        projectName = ""
        refreshProjects()
        info = "Proyek \"$name\" tersimpan."
    }

    fun loadProject(p: VoiceProject) {
        text = p.text
        selectEngine(p.engine)
        voiceGemini = p.voiceGemini
        voiceEdge = p.voiceEdge
        presetId = p.presetId
        voPresetId = p.voPresetId
        speed = p.speed
        pitchPercent = p.pitchPercent
        customStyle = p.customStyle
        error = null
        info = "Proyek \"${p.name}\" dimuat — semua pengaturan kembali seperti saat disimpan."
    }

    fun deleteProject(p: VoiceProject) {
        ProjectStore.delete(ctx, p.name)
        refreshProjects()
        info = "Proyek \"${p.name}\" dihapus."
    }

    fun saveLastToDownloads() {
        val f = lastFile ?: return
        viewModelScope.launch {
            val dest = withContext(Dispatchers.IO) { AudioStore.saveToDownloads(ctx, f) }
            info = if (dest != null) "Tersimpan di $dest"
            else "Gagal menyalin ke Download — file tetap ada di Riwayat (folder app)."
        }
    }

    fun saveHistoryToDownloads(file: File) {
        viewModelScope.launch {
            val dest = withContext(Dispatchers.IO) { AudioStore.saveToDownloads(ctx, file) }
            info = if (dest != null) "Tersimpan di $dest" else "Gagal menyalin ${file.name} ke Download."
        }
    }

    fun deleteHistory(file: File) {
        if (lastFile?.absolutePath == file.absolutePath) lastFile = null
        file.delete()
        refreshStatus()
    }
}
