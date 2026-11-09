package com.daftech.suaraku.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.daftech.suaraku.audio.AudioStore
import com.daftech.suaraku.engine.EdgeTtsEngine
import com.daftech.suaraku.engine.GeminiTtsEngine
import com.daftech.suaraku.engine.SupertonicTtsEngine
import com.daftech.suaraku.project.StudioProject
import com.daftech.suaraku.studio.StudioRunItem
import com.daftech.suaraku.studio.StudioSegment
import com.daftech.suaraku.studio.StudioSegmentStatus
import com.daftech.suaraku.studio.StudioVoice
import com.daftech.suaraku.studio.StudioVoices

/**
 * Tab Studio v1.6.0 — dialog multi-suara MULTI-ENGINE.
 *
 * v1.5.0: Supertonic-only. v1.6.0: tiap speaker bisa pakai engine sendiri
 * (Supertonic / Gemini / Edge / Offline) + suara engine itu. Semua segmen
 * diseragamkan ke WAV PCM16 mono 44,1 kHz sebelum digabung (lihat
 * WavUtil.convertToWav44100).
 *
 * Pratinjau (v1.6.0): setelah tap ▶ di baris dan file pratinjau jadi,
 * pratinjau LANGSUNG BUNYI (auto-play via LaunchedEffect ber-key path
 * file) — tidak perlu scroll + tap kedua seperti v1.5.0.
 *
 * Keputusan UI (dari v1.5.0): Studio jadi TAB KE-4 di NavigationBar,
 * bukan seksi di dalam tab Buat Suara — Studio punya alur sendiri
 * (editor naskah + pemetaan suara + generate + pratinjau + proyek).
 */
@Composable
fun StudioTab(vm: AppViewModel, controller: PlayerController) {
    val context = LocalContext.current

    // Auto-play pratinjau (v1.6.0): tiap file pratinjau BARU langsung
    // diputar sekali. Guard `lastAutoPlayedPath` supaya recomposition
    // tidak memutar ulang, dan supaya pause manual user tidak dilawan
    // (efek hanya jalan saat path berubah — file baru = path baru,
    // karena nama file pratinjau pakai stempel sampai detik).
    var lastAutoPlayedPath by remember { mutableStateOf<String?>(null) }
    val previewFile = vm.studioPreviewFile
    LaunchedEffect(previewFile?.absolutePath) {
        val f = previewFile
        if (f != null && f.absolutePath != lastAutoPlayedPath) {
            lastAutoPlayedPath = f.absolutePath
            controller.play(f)
        }
    }

    SectionTitle("Studio Dialog")
    Text(
        "Dialog multi-suara multi-engine. Satu baris = satu suara. " +
            "Format: \"Nama: kalimat...\" — baris tanpa nama = lanjutan speaker sebelumnya. " +
            "Baris kosong = jeda. Token [jeda] = 0,8 dtk, [jeda 2s] = jeda 2 dtk. " +
            "Tiap speaker bisa pakai engine sendiri (lihat di bawah).",
        style = MaterialTheme.typography.bodySmall, color = SkTextDim
    )
    Spacer(Modifier.height(10.dp))

    OutlinedTextField(
        value = vm.studioScript,
        onValueChange = { vm.updateStudioScript(it) },
        label = { Text("Naskah dialog") },
        placeholder = { Text("Narator: Banjir melanda tiga kecamatan.\nBudi: Kita berangkat jam tujuh.\n[jeda 2s]\nNarator: Mereka pun berangkat.") },
        colors = skFieldColors(),
        modifier = Modifier.fillMaxWidth(),
        minLines = 8
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "${vm.studioSegments.size} segmen • ${vm.studioSpeakers.size} speaker",
        style = MaterialTheme.typography.bodySmall, color = SkTextDim
    )

    if (vm.studioSpeakers.isNotEmpty()) {
        Spacer(Modifier.height(SectionGap))
        SectionTitle("Mesin + Suara per Speaker")
        Text(
            "Supertonic = offline (butuh model, 10 suara). Gemini = ekspresif (butuh API key, tiap segmen = request API sendiri). Edge = gratis (butuh internet). Offline = Piper / TTS sistem.",
            style = MaterialTheme.typography.bodySmall, color = SkTextDim
        )
        Spacer(Modifier.height(8.dp))
        vm.studioSpeakers.forEach { speaker ->
            StudioSpeakerCard(vm, speaker)
            Spacer(Modifier.height(8.dp))
        }
    }

    Spacer(Modifier.height(10.dp))
    Text("Kecepatan: ${"%.1f".format(vm.speed)}x", style = MaterialTheme.typography.bodyMedium)
    Slider(value = vm.speed, onValueChange = { vm.speed = it }, valueRange = 0.5f..2.0f)

    // Prasyarat sesuai engine yang DIPAKAI naskah (bukan global Supertonic).
    val usedEngines = vm.studioEnginesUsed()
    if (StudioVoices.ENGINE_GEMINI in usedEngines && !vm.hasGeminiKey) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Ada speaker Gemini tapi API key Gemini belum diisi — buka Pengaturan (ikon gerigi di atas).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
        )
    }
    if (StudioVoices.ENGINE_SUPERTONIC in usedEngines && !vm.supertonicReady) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Ada speaker Supertonic tapi model Supertonic belum diunduh — buka tab Buat Suara, pilih Supertonic (AI), lalu unduh modelnya (sekitar 123 MB, sekali saja).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
        )
    }

    Spacer(Modifier.height(10.dp))
    Button(
        onClick = { vm.generateStudio() },
        enabled = !vm.studioGenerating && vm.studioSegments.isNotEmpty(),
        modifier = Modifier.fillMaxWidth().height(52.dp)
    ) {
        Text("GENERATE STUDIO")
    }

    if (vm.studioGenerating || vm.studioProgressText.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(progress = { vm.studioProgressFraction }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Text(vm.studioProgressText, style = MaterialTheme.typography.bodySmall, color = SkTextDim)
    }
    vm.studioSummary?.let { summary ->
        Spacer(Modifier.height(6.dp))
        Text(summary, style = MaterialTheme.typography.bodySmall, color = SkTextDim)
    }
    vm.error?.let { err ->
        Spacer(Modifier.height(10.dp))
        Card(colors = CardDefaults.cardColors(containerColor = SkErrorContainer), modifier = Modifier.fillMaxWidth()) {
            Text(err, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
    vm.info?.let { msg ->
        Spacer(Modifier.height(10.dp))
        Text(msg, style = MaterialTheme.typography.bodySmall, color = SkTextDim)
    }

    if (vm.studioRunItems.isNotEmpty()) {
        Spacer(Modifier.height(SectionGap))
        SectionTitle("Segmen")
        vm.studioRunItems.forEach { item ->
            StudioSegmentRow(vm, item)
            Spacer(Modifier.height(6.dp))
        }
    }

    vm.lastStudioWav?.let { wav ->
        Spacer(Modifier.height(SectionGap))
        SectionTitle("Hasil Studio")
        AudioPlayerBar(controller, wav)
        vm.lastStudioSrt?.let { srt ->
            Spacer(Modifier.height(4.dp))
            Text(srt.name, style = MaterialTheme.typography.bodySmall, color = SkTextDim)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.saveStudioToDownloads() }) {
                Icon(Icons.Filled.Download, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Simpan WAV + SRT")
            }
            OutlinedButton(onClick = {
                val intent = AudioStore.shareIntent(context, wav)
                context.startActivity(Intent.createChooser(intent, "Bagikan audio"))
            }) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Bagikan")
            }
        }
    }

    previewFile?.let { file ->
        Spacer(Modifier.height(SectionGap))
        SectionTitle("Pratinjau Baris")
        Text(
            "Pratinjau langsung berbunyi setelah dibuat. Pakai player di bawah untuk memutar ulang / jeda.",
            style = MaterialTheme.typography.bodySmall, color = SkTextDim
        )
        Spacer(Modifier.height(8.dp))
        AudioPlayerBar(controller, file)
    }

    Spacer(Modifier.height(SectionGap))
    SectionTitle("Proyek Studio")
    Text(
        "Simpan naskah + pemetaan mesin/suara + kecepatan sebagai proyek Studio (terpisah dari proyek biasa).",
        style = MaterialTheme.typography.bodySmall, color = SkTextDim
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = vm.studioProjectName,
        onValueChange = { vm.studioProjectName = it },
        label = { Text("Nama proyek Studio (opsional)") },
        placeholder = { Text("Kosong = kata pertama naskah") },
        colors = skFieldColors(),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
    Spacer(Modifier.height(8.dp))
    Button(onClick = { vm.saveStudioProject() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Icon(Icons.Filled.Folder, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text("SIMPAN PROYEK STUDIO")
    }
    Spacer(Modifier.height(12.dp))
    if (vm.studioProjects.isEmpty()) {
        EmptyStateCard(Icons.Filled.Folder, "Belum ada proyek Studio tersimpan.")
    } else {
        vm.studioProjects.forEach { project ->
            StudioProjectCard(vm, project)
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Kartu satu speaker: dropdown mesin + dropdown suara engine itu. */
@Composable
private fun StudioSpeakerCard(vm: AppViewModel, speaker: String) {
    val voice = vm.studioVoiceMap[speaker]
        ?: StudioVoices.defaultVoiceFor(StudioVoices.ENGINE_SUPERTONIC)
    val engineLabels = StudioVoices.ENGINES.map { StudioVoices.engineLabel(it) }
    Card(colors = CardDefaults.cardColors(containerColor = SkSurface), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(speaker, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            VoiceDropdown(
                label = "Mesin",
                voices = engineLabels,
                selected = StudioVoices.engineLabel(voice.engine),
                onSelect = { label ->
                    val idx = engineLabels.indexOf(label)
                    if (idx >= 0) vm.updateStudioEngine(speaker, StudioVoices.ENGINES[idx])
                }
            )
            Spacer(Modifier.height(8.dp))
            when (voice.engine) {
                StudioVoices.ENGINE_SUPERTONIC -> {
                    val sid = StudioVoices.sidOf(voice) ?: SupertonicTtsEngine.DEFAULT_SID
                    VoiceDropdown(
                        label = "Suara Supertonic",
                        voices = SupertonicTtsEngine.VOICES,
                        selected = SupertonicTtsEngine.voiceLabel(sid),
                        onSelect = { label ->
                            val idx = SupertonicTtsEngine.VOICES.indexOf(label)
                            if (idx >= 0) vm.updateStudioVoice(
                                speaker,
                                StudioVoice(StudioVoices.ENGINE_SUPERTONIC, idx.toString())
                            )
                        }
                    )
                }
                StudioVoices.ENGINE_GEMINI -> {
                    VoiceDropdown(
                        label = "Suara Gemini",
                        voices = GeminiTtsEngine.VOICES,
                        selected = voice.voice.ifEmpty { GeminiTtsEngine.VOICES.first() },
                        onSelect = { label ->
                            vm.updateStudioVoice(speaker, StudioVoice(StudioVoices.ENGINE_GEMINI, label))
                        }
                    )
                }
                StudioVoices.ENGINE_EDGE -> {
                    VoiceDropdown(
                        label = "Suara Edge",
                        voices = EdgeTtsEngine.VOICES,
                        selected = voice.voice.ifEmpty { EdgeTtsEngine.VOICES.first() },
                        onSelect = { label ->
                            vm.updateStudioVoice(speaker, StudioVoice(StudioVoices.ENGINE_EDGE, label))
                        }
                    )
                }
                else -> {
                    Text(
                        "Otomatis: Piper (kalau model terpasang), kalau tidak TTS sistem.",
                        style = MaterialTheme.typography.bodySmall, color = SkTextDim
                    )
                }
            }
        }
    }
}

private fun studioStatusLabel(status: StudioSegmentStatus): String = when (status) {
    StudioSegmentStatus.QUEUED -> "Antre"
    StudioSegmentStatus.RUNNING -> "Berjalan…"
    StudioSegmentStatus.DONE -> "Selesai"
    StudioSegmentStatus.FAILED -> "Gagal"
}

@Composable
private fun StudioSegmentRow(vm: AppViewModel, item: StudioRunItem) {
    val label = when (val seg = item.segment) {
        is StudioSegment.Dialogue -> "[${seg.speaker}] ${seg.text}"
        is StudioSegment.Pause -> "Jeda ${seg.seconds}s"
    }
    Card(colors = CardDefaults.cardColors(containerColor = SkSurface), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${item.index + 1}. $label",
                    style = MaterialTheme.typography.bodySmall,
                    color = SkText
                )
                Text(
                    studioStatusLabel(item.status) + (item.error?.let { " — $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.status == StudioSegmentStatus.FAILED) MaterialTheme.colorScheme.error else SkTextDim
                )
            }
            if (item.segment is StudioSegment.Dialogue) {
                IconButton(onClick = { vm.previewStudioLine(item.index) }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Pratinjau baris ini")
                }
            }
        }
    }
}

@Composable
private fun StudioProjectCard(vm: AppViewModel, project: StudioProject) {
    // v1.6.0: tampilkan pemetaan engine+suara; proyek v1.5.0 (tanpa
    // engineMap) jatuh ke voiceMap lama sebagai Supertonic.
    val voicesText = if (project.engineMap.isNotEmpty()) {
        project.engineMap.entries.joinToString(", ") { (s, v) -> "$s: ${StudioVoices.voiceLabel(v)}" }
    } else {
        project.voiceMap.entries.joinToString(", ") { (s, sid) -> "$s: Suara ${sid + 1}" }
    }
    Card(colors = CardDefaults.cardColors(containerColor = SkSurface), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(project.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                voicesText,
                style = MaterialTheme.typography.bodySmall, color = SkTextDim
            )
            Text(
                project.script.replace("\n", " ").take(100) +
                    if (project.script.length > 100) "…" else "",
                style = MaterialTheme.typography.bodySmall, color = SkTextDim
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.loadStudioProject(project) }) {
                    Text("Muat")
                }
                OutlinedButton(onClick = { vm.deleteStudioProject(project) }) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Hapus")
                }
            }
        }
    }
}
