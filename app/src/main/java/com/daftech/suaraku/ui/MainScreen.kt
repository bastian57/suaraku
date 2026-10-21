package com.daftech.suaraku.ui

import android.content.Intent
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.daftech.suaraku.Prefs
import com.daftech.suaraku.audio.AudioStore
import com.daftech.suaraku.engine.EdgeTtsEngine
import com.daftech.suaraku.engine.GeminiTtsEngine
import com.daftech.suaraku.engine.Presets
import kotlinx.coroutines.delay
import java.io.File

/* ---------------- Player bersama (1 MediaPlayer untuk semua tombol putar) ---------------- */

class PlayerController {
    private var player: MediaPlayer? = null
    var currentPath by mutableStateOf<String?>(null)
    var isPlaying by mutableStateOf(false)
    var positionMs by mutableStateOf(0)
    var durationMs by mutableStateOf(0)

    fun toggle(file: File) {
        if (currentPath == file.absolutePath && player != null) {
            val p = player!!
            if (p.isPlaying) { p.pause(); isPlaying = false } else { p.start(); isPlaying = true }
            return
        }
        releasePlayer()
        val p = MediaPlayer()
        player = p
        try {
            p.setDataSource(file.absolutePath)
            p.setOnCompletionListener { isPlaying = false; positionMs = 0; try { it.seekTo(0) } catch (e: Exception) {} }
            p.prepare()
            durationMs = p.duration
            currentPath = file.absolutePath
            p.start()
            isPlaying = true
            positionMs = 0
        } catch (e: Exception) {
            releasePlayer()
        }
    }

    fun seekToFraction(frac: Float) {
        val p = player ?: return
        if (durationMs > 0) {
            val target = (frac * durationMs).toInt()
            try { p.seekTo(target); positionMs = target } catch (e: Exception) { /* abaikan */ }
        }
    }

    fun tick() {
        val p = player ?: return
        if (isPlaying) { try { positionMs = p.currentPosition } catch (e: Exception) { /* abaikan */ } }
    }

    private fun releasePlayer() {
        try { player?.stop() } catch (e: Exception) { /* abaikan */ }
        try { player?.release() } catch (e: Exception) { /* abaikan */ }
        player = null
        currentPath = null
        isPlaying = false
        positionMs = 0
        durationMs = 0
    }

    fun release() = releasePlayer()
}

@Composable
fun AudioPlayerBar(controller: PlayerController, file: File, modifier: Modifier = Modifier) {
    val active = controller.currentPath == file.absolutePath
    Card(modifier = modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SkSurface)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { controller.toggle(file) }) {
                    Icon(
                        if (active && controller.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = "Putar/Jeda",
                        tint = SkGold
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(file.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${file.length() / 1024} KB",
                        style = MaterialTheme.typography.bodySmall, color = SkTextDim
                    )
                }
            }
            if (active && controller.durationMs > 0) {
                Slider(
                    value = (controller.positionMs.toFloat() / controller.durationMs).coerceIn(0f, 1f),
                    onValueChange = { controller.seekToFraction(it) }
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(fmtTime(controller.positionMs), style = MaterialTheme.typography.bodySmall, color = SkTextDim)
                    Text(fmtTime(controller.durationMs), style = MaterialTheme.typography.bodySmall, color = SkTextDim)
                }
            }
        }
    }
}

private fun fmtTime(ms: Int): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/* ---------------- Layar utama ---------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onOpenSettings: () -> Unit) {
    val vm: AppViewModel = viewModel()
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    val controller = remember { PlayerController() }

    LaunchedEffect(controller.isPlaying) {
        while (controller.isPlaying) { controller.tick(); delay(300) }
    }
    DisposableEffect(Unit) { onDispose { controller.release() } }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText().orEmpty()
                vm.text = content.take(20000)
                vm.info = "File diimpor: ${content.length} karakter"
            } catch (e: Exception) {
                vm.error = "Gagal membaca file: ${e.message}"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SuaraKu", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Pengaturan", tint = SkText)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SkBg, titleContentColor = SkText)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = SkSurface) {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Mic, contentDescription = null) },
                    label = { Text("Buat Suara") }
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1; vm.refreshStatus() },
                    icon = { Icon(Icons.Filled.InsertDriveFile, contentDescription = null) },
                    label = { Text("Riwayat") }
                )
            }
        },
        containerColor = SkBg
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (tab == 0) {
                GeneratorTab(vm, controller, onImport = { importLauncher.launch(arrayOf("text/plain")) })
            } else {
                HistoryTab(vm, controller)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "SuaraKu v1.0.2 — by DAFTECH • pemakaian pribadi",
                style = MaterialTheme.typography.bodySmall, color = SkTextDim
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceDropdown(label: String, voices: List<String>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = skFieldColors(),
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            voices.forEach { v ->
                DropdownMenuItem(
                    text = { Text(v) },
                    onClick = { onSelect(v); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun GeneratorTab(vm: AppViewModel, controller: PlayerController, onImport: () -> Unit) {
    val context = LocalContext.current

    Text("Mesin Suara", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = vm.engine == Prefs.ENGINE_GEMINI,
            onClick = { vm.selectEngine(Prefs.ENGINE_GEMINI) },
            label = { Text("Gemini Ekspresif") }
        )
        FilterChip(
            selected = vm.engine == Prefs.ENGINE_EDGE,
            onClick = { vm.selectEngine(Prefs.ENGINE_EDGE) },
            label = { Text("Edge Gratis") }
        )
        FilterChip(
            selected = vm.engine == Prefs.ENGINE_OFFLINE,
            onClick = { vm.selectEngine(Prefs.ENGINE_OFFLINE) },
            label = { Text("Offline") }
        )
    }
    Spacer(Modifier.height(10.dp))

    when (vm.engine) {
        Prefs.ENGINE_GEMINI -> {
            VoiceDropdown("Suara Gemini", GeminiTtsEngine.VOICES, vm.voiceGemini) { vm.voiceGemini = it }
            if (!vm.hasGeminiKey) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "API key Gemini belum diisi — buka Pengaturan (ikon gerigi di atas).",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Prefs.ENGINE_EDGE -> {
            VoiceDropdown("Suara Edge", EdgeTtsEngine.VOICES, vm.voiceEdge) { vm.voiceEdge = it }
            Spacer(Modifier.height(6.dp))
            Text(
                "Engine gratis tidak resmi dari Microsoft — bisa berubah/berhenti sewaktu-waktu.",
                color = SkTextDim, style = MaterialTheme.typography.bodySmall
            )
        }
        else -> {
            Text(
                if (vm.modelReady) "Suara: Piper id_ID (AI offline di HP)" else "Suara: TTS Sistem (id-ID)",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = SkSurface), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        if (vm.modelReady) "Model Piper Indonesia: TERPASANG (offline AI aktif)"
                        else "Model Piper Indonesia belum diunduh (sekitar 67 MB, sekali saja). Tanpa model ini, mode offline memakai TTS sistem.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (!vm.modelReady) {
                        Spacer(Modifier.height(8.dp))
                        if (vm.modelDownloading) {
                            LinearProgressIndicator(progress = vm.modelProgress / 100f, modifier = Modifier.fillMaxWidth())
                            Text("Mengunduh model... ${vm.modelProgress}%", style = MaterialTheme.typography.bodySmall, color = SkTextDim)
                        } else {
                            OutlinedButton(onClick = { vm.downloadModel() }) {
                                Icon(Icons.Filled.Download, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Unduh Model Offline")
                            }
                        }
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(14.dp))
    Text("Preset Ekspresi", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Presets.ALL.take(3).forEach { p ->
            FilterChip(selected = vm.presetId == p.id, onClick = { vm.presetId = p.id }, label = { Text(p.label) })
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Presets.ALL.drop(3).forEach { p ->
            FilterChip(selected = vm.presetId == p.id, onClick = { vm.presetId = p.id }, label = { Text(p.label) })
        }
    }

    if (vm.engine == Prefs.ENGINE_GEMINI) {
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = vm.customStyle,
            onValueChange = { vm.customStyle = it },
            label = { Text("Gaya kustom Gemini (opsional)") },
            placeholder = { Text("cth: seperti penyiar radio semangat, agak cepat") },
            colors = skFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )
    }

    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Teks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onImport) {
            Icon(Icons.Filled.InsertDriveFile, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Impor .txt")
        }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = vm.text,
        onValueChange = { vm.text = it },
        placeholder = { Text("Tulis / tempel naskah di sini...") },
        colors = skFieldColors(),
        modifier = Modifier.fillMaxWidth(),
        minLines = 6,
        maxLines = 12
    )
    Text(
        "${vm.text.length} karakter",
        style = MaterialTheme.typography.bodySmall, color = SkTextDim
    )

    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = vm.directorEnabled,
            onCheckedChange = { vm.setDirector(it) }
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text("Director Pass", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Eja angka & singkatan, rapikan jeda per kalimat (lokal)",
                style = MaterialTheme.typography.bodySmall, color = SkTextDim
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Text("Kecepatan: ${"%.1f".format(vm.speed)}x", style = MaterialTheme.typography.bodyMedium)
    Slider(value = vm.speed, onValueChange = { vm.speed = it }, valueRange = 0.5f..2.0f)

    if (vm.engine != Prefs.ENGINE_GEMINI) {
        Text("Nada (pitch): ${vm.pitchPercent}%", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = vm.pitchPercent.toFloat(),
            onValueChange = { vm.pitchPercent = it.toInt() },
            valueRange = -50f..50f
        )
    }

    Spacer(Modifier.height(14.dp))
    Button(
        onClick = { vm.generate() },
        enabled = !vm.generating,
        modifier = Modifier.fillMaxWidth().height(52.dp)
    ) {
        Icon(if (vm.generating) Icons.Filled.Refresh else Icons.Filled.Mic, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(if (vm.generating) "Sedang Membuat..." else "BUAT SUARA")
    }

    if (vm.generating) {
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(progress = vm.progressFraction.coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
        Text(vm.progressText, style = MaterialTheme.typography.bodySmall, color = SkTextDim)
    }

    vm.error?.let { err ->
        Spacer(Modifier.height(10.dp))
        Card(colors = CardDefaults.cardColors(containerColor = ColorErrorContainer), modifier = Modifier.fillMaxWidth()) {
            Text(err, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
    vm.info?.let { msg ->
        Spacer(Modifier.height(10.dp))
        Text(msg, style = MaterialTheme.typography.bodySmall, color = SkTextDim)
    }

    vm.lastFile?.let { file ->
        Spacer(Modifier.height(14.dp))
        Text("Hasil Terakhir", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        AudioPlayerBar(controller, file)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.saveLastToDownloads() }) {
                Icon(Icons.Filled.Download, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Simpan ke Download")
            }
            OutlinedButton(onClick = {
                val intent = AudioStore.shareIntent(context, file)
                context.startActivity(Intent.createChooser(intent, "Bagikan audio"))
            }) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Bagikan")
            }
        }
    }
}

private val ColorErrorContainer = androidx.compose.ui.graphics.Color(0xFF3A1D1D)

@Composable
private fun HistoryTab(vm: AppViewModel, controller: PlayerController) {
    val context = LocalContext.current
    Text("Riwayat Hasil", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    if (vm.history.isEmpty()) {
        Text("Belum ada hasil. Buat suara pertamamu di tab Buat Suara.", color = SkTextDim)
        return
    }
    vm.history.forEach { file ->
        AudioPlayerBar(controller, file)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.saveHistoryToDownloads(file) }) {
                Icon(Icons.Filled.Download, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Download")
            }
            OutlinedButton(onClick = {
                val intent = AudioStore.shareIntent(context, file)
                context.startActivity(Intent.createChooser(intent, "Bagikan audio"))
            }) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Bagikan")
            }
            IconButton(onClick = { vm.deleteHistory(file) }) {
                Icon(Icons.Filled.Delete, contentDescription = "Hapus", tint = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
