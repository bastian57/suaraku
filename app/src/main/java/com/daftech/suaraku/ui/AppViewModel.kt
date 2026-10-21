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

    // ---- Riwayat & model offline ----
    var history by mutableStateOf<List<File>>(emptyList())
    var modelReady by mutableStateOf(false)
    var modelDownloading by mutableStateOf(false)
    var modelProgress by mutableStateOf(0)

    init { refreshStatus() }

    fun refreshStatus() {
        history = AudioStore.listHistory(ctx)
        modelReady = SherpaTtsEngine.isReady(ctx)
    }

    fun setEngine(e: String) {
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

    fun generate() {
        if (generating) return
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
                val chunks = DirectorPass.chunk(raw, directorEnabled)
                if (chunks.isEmpty()) throw IllegalStateException("Teks kosong setelah dibersihkan")
                val preset = Presets.byId(presetId)
                val out: File
                when (engine) {
                    Prefs.ENGINE_GEMINI -> {
                        val key = prefs.geminiKey
                        if (key.isBlank()) throw IllegalStateException(
                            "API key Gemini belum diisi. Buka Pengaturan (ikon gerigi) lalu tempel API key Gemini milikmu sendiri."
                        )
                        val styled = chunks.map { Presets.geminiStyledText(preset, customStyle, it) }
                        out = AudioStore.newOutputFile(ctx, "gemini", "wav")
                        withContext(Dispatchers.IO) {
                            GeminiTtsEngine.synthesize(key, prefs.geminiModel, styled, voiceGemini, out) { d, t ->
                                progressText = "Gemini: potongan $d dari $t"
                                progressFraction = d.toFloat() / t
                            }
                        }
                    }
                    Prefs.ENGINE_EDGE -> {
                        val rate = preset.edgeRatePercent + ((speed - 1f) * 100).toInt()
                        val pitch = (preset.edgePitchPercent + pitchPercent).coerceIn(-50, 100)
                        out = AudioStore.newOutputFile(ctx, "edge", "mp3")
                        withContext(Dispatchers.IO) {
                            EdgeTtsEngine.synthesize(chunks, voiceEdge, rate, pitch, preset.edgeVolumePercent, out) { d, t ->
                                progressText = "Edge: potongan $d dari $t"
                                progressFraction = d.toFloat() / t
                            }
                        }
                    }
                    else -> { // OFFLINE
                        out = AudioStore.newOutputFile(ctx, "offline", "wav")
                        val effectiveSpeed = (speed * preset.speedFactor).coerceIn(0.3f, 3f)
                        val pitchFactor = ((100 + preset.edgePitchPercent + pitchPercent) / 100f).coerceIn(0.5f, 2f)
                        var usedSystemFallback = false
                        if (SherpaTtsEngine.isReady(ctx)) {
                            try {
                                withContext(Dispatchers.IO) {
                                    SherpaTtsEngine.synthesize(ctx, chunks, effectiveSpeed, out) { d, t ->
                                        progressText = "Offline (Piper AI): potongan $d dari $t"
                                        progressFraction = d.toFloat() / t
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
                                    progressText = "Offline (TTS Sistem): potongan $d dari $t"
                                    progressFraction = d.toFloat() / t
                                }
                            }
                            info = "Catatan: mode offline memakai TTS sistem (model Piper belum siap/gagal dimuat)."
                        }
                    }
                }
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
