package com.daftech.suaraku.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.daftech.suaraku.Prefs
import com.daftech.suaraku.audio.AudioStore
import com.daftech.suaraku.audio.WavUtil
import com.daftech.suaraku.director.DirectorPass
import com.daftech.suaraku.engine.EdgeTtsEngine
import com.daftech.suaraku.engine.GeminiTtsEngine
import com.daftech.suaraku.engine.ModelDownloader
import com.daftech.suaraku.engine.Presets
import com.daftech.suaraku.engine.SherpaTtsEngine
import com.daftech.suaraku.engine.SupertonicDownloader
import com.daftech.suaraku.engine.SupertonicTtsEngine
import com.daftech.suaraku.engine.SystemTtsEngine
import com.daftech.suaraku.project.ProjectStore
import com.daftech.suaraku.project.StudioProject
import com.daftech.suaraku.project.StudioProjectStore
import com.daftech.suaraku.project.VoiceProject
import com.daftech.suaraku.studio.DEFAULT_SIDS
import com.daftech.suaraku.studio.MAX_SPEAKERS
import com.daftech.suaraku.studio.SrtCue
import com.daftech.suaraku.studio.StudioRunItem
import com.daftech.suaraku.studio.StudioSegment
import com.daftech.suaraku.studio.StudioSegmentStatus
import com.daftech.suaraku.studio.buildSrt
import com.daftech.suaraku.studio.parseStudioScript
import com.daftech.suaraku.studio.speakersOf
import com.daftech.suaraku.studio.validateVoiceMap
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
    var supertonicSid by mutableStateOf(prefs.supertonicSid)
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

    // ---- Model Supertonic (v1.4.0, terpisah dari Piper) ----
    var supertonicReady by mutableStateOf(false)
    var supertonicDownloading by mutableStateOf(false)
    var supertonicProgress by mutableStateOf(0)

    fun refreshStatus() {
        history = AudioStore.listHistory(ctx)
        modelReady = SherpaTtsEngine.isReady(ctx)
        supertonicReady = SupertonicTtsEngine.isReady(ctx)
    }

    fun refreshProjects() {
        projects = ProjectStore.loadAll(ctx)
    }

    fun selectEngine(e: String) {
        engine = e
        prefs.defaultEngine = e
    }

    /** Pilih suara Supertonic (sid 0..9); di luar rentang ditolak diam-diam (state tidak berubah). */
    fun selectSupertonicSid(sid: Int) {
        if (!SupertonicTtsEngine.isValidSid(sid)) return
        supertonicSid = sid
        prefs.supertonicSid = sid
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

    /** Unduh model Supertonic (dipanggil SETELAH user menyetujui ringkasan lisensi di dialog). */
    fun downloadSupertonicModel() {
        if (supertonicDownloading) return
        viewModelScope.launch {
            supertonicDownloading = true
            error = null
            try {
                withContext(Dispatchers.IO) {
                    SupertonicDownloader.downloadAndExtract(ctx) { pct -> supertonicProgress = pct }
                }
                supertonicReady = true
                info = "Model Supertonic 3 berhasil diunduh (SHA-256 cocok). Engine Supertonic (AI) sekarang bisa dipakai."
            } catch (e: Exception) {
                error = "Gagal unduh model Supertonic: ${e.message}"
            } finally {
                supertonicDownloading = false
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
        Prefs.ENGINE_SUPERTONIC -> "supertonic-sid$supertonicSid"
        else -> "offline"
    }

    private fun outputExt(): String = if (engine == Prefs.ENGINE_EDGE) "mp3" else "wav"

    /**
     * File output untuk engine saat ini. Supertonic memakai format nama
     * KHUSUS (tanpa preset voice-over): SuaraKu_YYYYMMDD_HHmm_supertonic-sidN_<nn>.wav;
     * engine lain memakai format v1.3.0 (engine-voPreset).
     */
    private fun newOutputFile(index: Int): File =
        if (engine == Prefs.ENGINE_SUPERTONIC) AudioStore.newSupertonicOutputFile(ctx, supertonicSid, index)
        else AudioStore.newNamedOutputFile(ctx, engineTag(), voPresetId, index, outputExt())

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
            Prefs.ENGINE_SUPERTONIC -> {
                // Supertonic TIDAK punya parameter prosody per-engine: preset
                // VO/emosi dan pitch TIDAK dipetakan ke apa pun (didokumentasikan
                // di SupertonicTtsEngine). Yang dipakai: chunk (DirectorPass),
                // sid, dan speed slider. Model belum siap = error jelas, bukan crash.
                if (!SupertonicTtsEngine.isReady(ctx)) throw IllegalStateException(
                    "Model Supertonic belum diunduh. Buka bagian Supertonic (AI) di atas lalu unduh modelnya (sekitar 123 MB, sekali saja)."
                )
                withContext(Dispatchers.IO) {
                    SupertonicTtsEngine.synthesize(ctx, chunks, supertonicSid, speed, out) { d, t ->
                        reportProgress("$progressLabel (Supertonic AI)", d, t)
                    }
                }
                withContext(Dispatchers.Main) {
                    info = "Suara AI (Supertonic 3, OpenRAIL-M) — output machine-generated."
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
                val out = newOutputFile(1)
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
                val finalItems = executeBatch(
                    items = batchItems,
                    process = { item ->
                        val out = newOutputFile(item.id)
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

    // ---- Studio dialog (v1.5.0, Supertonic-only) ----
    var studioScript by mutableStateOf("")
    var studioSegments by mutableStateOf<List<StudioSegment>>(emptyList())
    var studioSpeakers by mutableStateOf<List<String>>(emptyList())
    var studioVoiceMap by mutableStateOf<Map<String, Int>>(emptyMap())
    var studioGenerating by mutableStateOf(false)
    var studioProgressText by mutableStateOf("")
    var studioProgressFraction by mutableStateOf(0f)
    var studioRunItems by mutableStateOf<List<StudioRunItem>>(emptyList())
    var studioSummary by mutableStateOf<String?>(null)
    var lastStudioWav by mutableStateOf<File?>(null)
    var lastStudioSrt by mutableStateOf<File?>(null)
    var studioPreviewFile by mutableStateOf<File?>(null)
    var studioProjects by mutableStateOf<List<StudioProject>>(emptyList())
    var studioProjectName by mutableStateOf("")

    // Blok init HARUS di sini — setelah SEMUA deklarasi state (v1.5.0 fix1).
    // Dulu init ada di atas (baris ~92) dan memanggil refreshStudioProjects(),
    // yang menulis `studioProjects` — padahal field delegate-nya baru
    // dideklarasikan di bawah init → NPE di setter sintetis saat konstruksi
    // (crash startup v1.5.0, logcat 9 Nov 2026). refreshStudioProjects()
    // sengaja TIDAK dipanggil di init: tab Studio me-refresh saat diklik.
    init {
        refreshStatus()
        refreshProjects()
    }

    /**
     * Set naskah Studio + hitung ulang segmen/speaker. Pemetaan suara lama
     * dipertahankan untuk speaker yang masih ada (cocok tanpa peduli huruf);
     * speaker baru mendapat sid default dari urutan DEFAULT_SIDS yang
     * belum dipakai. Parser tidak melempar untuk teks biasa.
     */
    fun updateStudioScript(script: String) {
        studioScript = script
        val segments = try {
            parseStudioScript(script)
        } catch (e: Exception) {
            emptyList()
        }
        studioSegments = segments
        val speakers = speakersOf(segments)
        studioSpeakers = speakers
        if (speakers.size > MAX_SPEAKERS) {
            error = "Maksimal $MAX_SPEAKERS speaker dalam satu naskah studio, dapat ${speakers.size}"
        }
        val oldByLower = studioVoiceMap.entries.associateBy { it.key.lowercase() }
        val fresh = mutableMapOf<String, Int>()
        val used = mutableSetOf<Int>()
        for (name in speakers) {
            val kept = oldByLower[name.lowercase()]?.value
            if (kept != null && kept in 0..9) {
                fresh[name] = kept
                used.add(kept)
            }
        }
        for (name in speakers) {
            if (name !in fresh) {
                val sid = DEFAULT_SIDS.firstOrNull { it !in used }
                if (sid != null) {
                    fresh[name] = sid
                    used.add(sid)
                }
            }
        }
        studioVoiceMap = fresh
    }

    /** Ubah suara satu speaker Studio (sid 0..9); nilai di luar rentang/speaker asing diabaikan. */
    fun updateStudioVoice(speaker: String, sid: Int) {
        if (!SupertonicTtsEngine.isValidSid(sid)) return
        if (speaker !in studioSpeakers) return
        studioVoiceMap = studioVoiceMap + (speaker to sid)
    }

    /**
     * Generate Studio: tiap segmen dialog di-generate Supertonic (chunk
     * DirectorPass per baris, sid per speaker), jeda = hening WAV 44.100 Hz,
     * lalu semua segmen digabung jadi SATU WAV + SRT berdampingan.
     * Segmen gagal TIDAK menghentikan antrean (dilewati — tidak ada audio
     * dan tidak ada cue SRT untuk segmen itu); ringkasan akhir menyebut
     * segmen yang gagal. Semua penulisan Compose state terjadi di Main
     * thread (loop jalan di viewModelScope/Main, IO hanya di withContext).
     */
    fun generateStudio() {
        if (generating || batchRunning || studioGenerating) return
        val segments = studioSegments
        if (segments.isEmpty()) {
            error = "Naskah Studio masih kosong — tulis dialog dengan format \"Nama: kalimat...\"."
            return
        }
        validateVoiceMap(studioSpeakers, studioVoiceMap)?.let { error = it; return }
        if (!SupertonicTtsEngine.isReady(ctx)) {
            error = "Model Supertonic belum diunduh. Buka tab Buat Suara, pilih Supertonic (AI), lalu unduh modelnya (sekitar 123 MB, sekali saja)."
            return
        }
        studioGenerating = true
        error = null
        info = null
        studioSummary = null
        studioProgressFraction = 0f
        studioRunItems = segments.mapIndexed { i, s -> StudioRunItem(i, s) }
        viewModelScope.launch {
            val segDir = File(ctx.cacheDir, "studio-segments")
            try {
                if (segDir.exists()) segDir.deleteRecursively()
                segDir.mkdirs()
                val okParts = mutableListOf<Triple<Int, File, Long>>() // (indeks segmen, file, durasi ms)
                val failed = mutableListOf<String>()
                for ((idx, item) in studioRunItems.withIndex()) {
                    val seg = item.segment
                    val label = when (seg) {
                        is StudioSegment.Dialogue -> seg.speaker
                        is StudioSegment.Pause -> "Jeda"
                    }
                    studioRunItems = studioRunItems.map {
                        if (it.index == idx) it.copy(status = StudioSegmentStatus.RUNNING) else it
                    }
                    studioProgressText = "Segmen ${idx + 1} dari ${segments.size} — $label"
                    studioProgressFraction = idx.toFloat() / segments.size
                    try {
                        val tmp = File(segDir, "seg_%02d.wav".format(idx))
                        val durationMs = withContext(Dispatchers.IO) {
                            when (seg) {
                                is StudioSegment.Dialogue -> {
                                    val sid = studioVoiceMap[seg.speaker]
                                        ?: throw IllegalStateException("Speaker \"${seg.speaker}\" belum dipetakan ke suara")
                                    val chunks = DirectorPass.chunkFor("SUPERTONIC", seg.text, directorEnabled)
                                    if (chunks.isEmpty()) throw IllegalStateException("Baris kosong setelah dibersihkan")
                                    SupertonicTtsEngine.synthesize(ctx, chunks, sid, speed, tmp) { _, _ -> }
                                    val d = WavUtil.wavDurationMs(tmp)
                                    if (d < 0) throw IllegalStateException("File audio segmen tidak terbaca")
                                    d
                                }
                                is StudioSegment.Pause -> {
                                    WavUtil.writeSilenceToWav(tmp, seg.seconds, 44100)
                                    (seg.seconds * 1000).toLong()
                                }
                            }
                        }
                        okParts.add(Triple(idx, tmp, durationMs))
                        studioRunItems = studioRunItems.map {
                            if (it.index == idx) it.copy(status = StudioSegmentStatus.DONE) else it
                        }
                    } catch (e: Exception) {
                        val msg = e.message ?: "Gagal tanpa pesan"
                        failed.add("Segmen ${idx + 1} ($label)")
                        studioRunItems = studioRunItems.map {
                            if (it.index == idx) it.copy(status = StudioSegmentStatus.FAILED, error = msg) else it
                        }
                    }
                }
                if (okParts.isEmpty()) throw IllegalStateException("Semua segmen gagal — tidak ada audio yang bisa digabung")
                val (outWav, outSrt) = AudioStore.newStudioFilePair(ctx, 1)
                val cues = withContext(Dispatchers.IO) {
                    WavUtil.concatWavsPcm16(okParts.map { it.second }, outWav, 44100)
                    var cum = 0L
                    val list = mutableListOf<SrtCue>()
                    for ((idx, _, dur) in okParts) {
                        val seg = segments[idx]
                        if (seg is StudioSegment.Dialogue) {
                            list.add(SrtCue(cum, cum + dur, "[${seg.speaker}] ${seg.text}"))
                        }
                        cum += dur
                    }
                    list
                }
                withContext(Dispatchers.IO) { outSrt.writeText(buildSrt(cues)) }
                lastStudioWav = outWav
                lastStudioSrt = outSrt
                studioProgressText = "Selesai"
                studioProgressFraction = 1f
                val okCount = okParts.size
                studioSummary = "$okCount berhasil, ${failed.size} gagal dari ${segments.size} segmen" +
                    if (failed.isNotEmpty()) " — gagal: ${failed.joinToString(", ")}" else ""
                info = "Studio selesai: ${outWav.name} (${outWav.length() / 1024} KB) + ${outSrt.name}\n" +
                    "Suara AI (Supertonic 3, OpenRAIL-M) — output machine-generated."
                refreshStatus()
            } catch (e: Exception) {
                error = e.message ?: "Studio gagal tanpa pesan"
                studioProgressText = ""
            } finally {
                studioGenerating = false
            }
        }
    }

    /** Pratinjau satu baris dialog Studio (generate HANYA baris itu). */
    fun previewStudioLine(index: Int) {
        if (generating || batchRunning || studioGenerating) return
        val seg = studioSegments.getOrNull(index) as? StudioSegment.Dialogue
            ?: run { error = "Hanya baris dialog yang bisa dipratinjau."; return }
        val sid = studioVoiceMap[seg.speaker]
            ?: run { error = "Speaker \"${seg.speaker}\" belum dipetakan ke suara."; return }
        if (!SupertonicTtsEngine.isReady(ctx)) {
            error = "Model Supertonic belum diunduh. Buka tab Buat Suara, pilih Supertonic (AI), lalu unduh modelnya."
            return
        }
        viewModelScope.launch {
            error = null
            studioProgressText = "Pratinjau: ${seg.speaker}..."
            try {
                val chunks = DirectorPass.chunkFor("SUPERTONIC", seg.text, directorEnabled)
                if (chunks.isEmpty()) throw IllegalStateException("Baris kosong setelah dibersihkan")
                val out = AudioStore.newStudioPreviewFile(ctx)
                withContext(Dispatchers.IO) {
                    SupertonicTtsEngine.synthesize(ctx, chunks, sid, speed, out) { _, _ -> }
                }
                studioPreviewFile = out
                studioProgressText = "Pratinjau siap: ${seg.speaker} (${out.length() / 1024} KB)"
                refreshStatus()
            } catch (e: Exception) {
                error = e.message ?: "Pratinjau gagal tanpa pesan"
                studioProgressText = ""
            }
        }
    }

    /** Simpan hasil Studio (WAV + SRT) ke Download/SuaraKu. */
    fun saveStudioToDownloads() {
        val wav = lastStudioWav
        val srt = lastStudioSrt
        if (wav == null || srt == null) {
            info = "Belum ada hasil Studio untuk disimpan."
            return
        }
        viewModelScope.launch {
            var ok = 0
            withContext(Dispatchers.IO) {
                if (AudioStore.saveToDownloads(ctx, wav) != null) ok++
                if (AudioStore.saveToDownloads(ctx, srt) != null) ok++
            }
            info = "Tersimpan $ok dari 2 file Studio (WAV + SRT) ke Download/SuaraKu."
        }
    }

    // ---- Proyek Studio ----

    fun refreshStudioProjects() {
        studioProjects = StudioProjectStore.loadAll(ctx)
    }

    fun saveStudioProject() {
        val raw = studioScript.trim()
        if (raw.isEmpty()) {
            error = "Naskah Studio masih kosong — tidak ada yang bisa disimpan."
            return
        }
        val name = studioProjectName.trim().ifEmpty { ProjectStore.defaultName(raw) }
        StudioProjectStore.save(
            ctx,
            StudioProject(
                name = name,
                script = raw,
                voiceMap = studioVoiceMap,
                speed = speed,
                savedAt = System.currentTimeMillis()
            )
        )
        studioProjectName = ""
        refreshStudioProjects()
        info = "Proyek Studio \"$name\" tersimpan."
    }

    /** Muat proyek Studio: naskah + kecepatan + pemetaan suara tersimpan (di atas default). */
    fun loadStudioProject(p: StudioProject) {
        speed = p.speed
        updateStudioScript(p.script)
        val applied = studioVoiceMap.toMutableMap()
        p.voiceMap.forEach { (speaker, sid) ->
            val match = studioSpeakers.firstOrNull { it.equals(speaker, ignoreCase = true) }
            if (match != null && sid in 0..9) applied[match] = sid
        }
        studioVoiceMap = applied
        error = null
        info = "Proyek Studio \"${p.name}\" dimuat."
    }

    fun deleteStudioProject(p: StudioProject) {
        StudioProjectStore.delete(ctx, p.name)
        refreshStudioProjects()
        info = "Proyek Studio \"${p.name}\" dihapus."
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
                savedAt = System.currentTimeMillis(),
                supertonicSid = supertonicSid
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
        selectSupertonicSid(p.supertonicSid)
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
