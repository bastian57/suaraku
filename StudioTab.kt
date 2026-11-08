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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.daftech.suaraku.audio.AudioStore
import com.daftech.suaraku.engine.SupertonicTtsEngine
import com.daftech.suaraku.project.StudioProject
import com.daftech.suaraku.studio.StudioRunItem
import com.daftech.suaraku.studio.StudioSegment
import com.daftech.suaraku.studio.StudioSegmentStatus

private val StudioErrorContainer = Color(0xFF3A1D1D)

/**
 * Tab Studio v1.5.0 — dialog multi-suara (Supertonic-only).
 *
 * Keputusan UI (didokumentasikan di laporan): Studio jadi TAB KE-4 di
 * NavigationBar, bukan seksi di dalam tab Buat Suara — Studio punya
 * alur sendiri (editor naskah + pemetaan suara + generate + pratinjau +
 * proyek) yang akan membuat GeneratorTab yang sudah 300+ baris makin
 * sulit dibaca bila dicampur. Tab baru hanya menambah 1 NavigationBarItem
 * + 1 cabang when di MainScreen; selebihnya file ini berdiri sendiri.
 */
@Composable
fun StudioTab(vm: AppViewModel, controller: PlayerController) {
    val context = LocalContext.current

    Text("Studio Dialog", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Text(
        "Dialog multi-suara (Supertonic AI, offline). Satu baris = satu suara. " +
            "Format: \"Nama: kalimat...\" — baris tanpa nama = lanjutan speaker sebelumnya. " +
            "Baris kosong = jeda. Token [jeda] = 0,8 dtk, [jeda 2s] = jeda 2 dtk.",
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
        Spacer(Modifier.height(14.dp))
        Text("Suara per Speaker", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        vm.studioSpeakers.forEach { speaker ->
            val sid = vm.studioVoiceMap[speaker] ?: SupertonicTtsEngine.DEFAULT_SID
            VoiceDropdown(
                label = speaker,
                voices = SupertonicTtsEngine.VOICES,
                selected = SupertonicTtsEngine.voiceLabel(sid),
                onSelect = { label -> vm.updateStudioVoice(speaker, SupertonicTtsEngine.VOICES.indexOf(label)) }
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    Spacer(Modifier.height(10.dp))
    Text("Kecepatan: ${"%.1f".format(vm.speed)}x", style = MaterialTheme.typography.bodyMedium)
    Slider(value = vm.speed, onValueChange = { vm.speed = it }, valueRange = 0.5f..2.0f)

    if (!vm.supertonicReady) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Model Supertonic belum diunduh — Studio butuh model itu. Buka tab Buat Suara, pilih Supertonic (AI), lalu unduh modelnya (sekitar 123 MB, sekali saja).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
        )
    }

    Spacer(Modifier.height(10.dp))
    Button(
        onClick = { vm.generateStudio() },
        enabled = !vm.studioGenerating && vm.studioSegments.isNotEmpty() && vm.supertonicReady,
        modifier = Modifier.fillMaxWidth()
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
        Card(colors = CardDefaults.cardColors(containerColor = StudioErrorContainer), modifier = Modifier.fillMaxWidth()) {
            Text(err, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
    vm.info?.let { msg ->
        Spacer(Modifier.height(10.dp))
        Text(msg, style = MaterialTheme.typography.bodySmall, color = SkTextDim)
    }

    if (vm.studioRunItems.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        Text("Segmen", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        vm.studioRunItems.forEach { item ->
            StudioSegmentRow(vm, item)
            Spacer(Modifier.height(6.dp))
        }
    }

    vm.lastStudioWav?.let { wav ->
        Spacer(Modifier.height(14.dp))
        Text("Hasil Studio", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
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

    vm.studioPreviewFile?.let { file ->
        Spacer(Modifier.height(14.dp))
        Text("Pratinjau Baris", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        AudioPlayerBar(controller, file)
    }

    Spacer(Modifier.height(16.dp))
    Text("Proyek Studio", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Text(
        "Simpan naskah + pemetaan suara + kecepatan sebagai proyek Studio (terpisah dari proyek biasa).",
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
    Button(onClick = { vm.saveStudioProject() }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.Folder, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text("SIMPAN PROYEK STUDIO")
    }
    Spacer(Modifier.height(12.dp))
    if (vm.studioProjects.isEmpty()) {
        Text("Belum ada proyek Studio tersimpan.", color = SkTextDim)
    } else {
        vm.studioProjects.forEach { project ->
            StudioProjectCard(vm, project)
            Spacer(Modifier.height(8.dp))
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
    Card(colors = CardDefaults.cardColors(containerColor = SkSurface), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(project.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                project.voiceMap.entries.joinToString(", ") { (s, sid) -> "$s: Suara ${sid + 1}" },
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
