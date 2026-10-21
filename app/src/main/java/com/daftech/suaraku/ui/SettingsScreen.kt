package com.daftech.suaraku.ui

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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.daftech.suaraku.Prefs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val vm: AppViewModel = viewModel()
    var keyText by remember { mutableStateOf(vm.prefs.geminiKey) }
    var modelExpanded by remember { mutableStateOf(false) }
    var engineExpanded by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf(vm.prefs.geminiModel) }
    var selectedEngine by remember { mutableStateOf(vm.prefs.defaultEngine) }

    val models = listOf(Prefs.MODEL_FLASH to "Gemini 2.5 Flash TTS (cepat, murah)", Prefs.MODEL_PRO to "Gemini 2.5 Pro TTS (kualitas tertinggi)")
    val engines = listOf(
        Prefs.ENGINE_GEMINI to "Gemini Ekspresif",
        Prefs.ENGINE_EDGE to "Edge Gratis",
        Prefs.ENGINE_OFFLINE to "Offline"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pengaturan", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Kembali", tint = SkText)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SkBg, titleContentColor = SkText)
            )
        },
        containerColor = SkBg
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("API Key Gemini (milikmu sendiri)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = keyText,
                onValueChange = { keyText = it; vm.prefs.geminiKey = it; vm.hasGeminiKey = it.isNotBlank() },
                label = { Text("Gemini API Key") },
                placeholder = { Text("Tempel API key dari Google AI Studio") },
                visualTransformation = PasswordVisualTransformation(),
                colors = skFieldColors(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Text(
                "Key disimpan HANYA di HP ini (SharedPreferences privat), tidak pernah dikirim ke mana pun selain Google Gemini API. Ambil key gratis di Google AI Studio (aistudio.google.com).",
                style = MaterialTheme.typography.bodySmall, color = SkTextDim
            )

            Spacer(Modifier.height(20.dp))
            Text("Model Gemini", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            ExposedDropdownMenuBox(expanded = modelExpanded, onExpandedChange = { modelExpanded = it }) {
                OutlinedTextField(
                    value = models.firstOrNull { it.first == selectedModel }?.second ?: selectedModel,
                    onValueChange = {}, readOnly = true,
                    label = { Text("Model") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                    colors = skFieldColors(),
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
                    models.forEach { (id, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { selectedModel = id; vm.prefs.geminiModel = id; modelExpanded = false })
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("Mesin Bawaan (default)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            ExposedDropdownMenuBox(expanded = engineExpanded, onExpandedChange = { engineExpanded = it }) {
                OutlinedTextField(
                    value = engines.firstOrNull { it.first == selectedEngine }?.second ?: selectedEngine,
                    onValueChange = {}, readOnly = true,
                    label = { Text("Mesin bawaan") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = engineExpanded) },
                    colors = skFieldColors(),
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(expanded = engineExpanded, onDismissRequest = { engineExpanded = false }) {
                    engines.forEach { (id, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { selectedEngine = id; vm.selectEngine(id); engineExpanded = false })
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Card(colors = CardDefaults.cardColors(containerColor = SkSurface), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Tentang", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "SuaraKu v1.0.0 (versionCode 1)\nby DAFTECH\n\n" +
                            "Aplikasi text-to-voice pribadi: Gemini Ekspresif (online, key sendiri), " +
                            "Edge Gratis (tidak resmi, bisa berubah), dan Offline (Piper AI di HP / TTS sistem).\n\n" +
                            "Pemakaian pribadi — tidak dijual, tanpa lisensi aktivasi, tanpa iklan.",
                        style = MaterialTheme.typography.bodySmall, color = SkTextDim
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row {
                Text("Director Pass bawaan: ", style = MaterialTheme.typography.bodySmall, color = SkTextDim)
                Text(if (vm.directorEnabled) "AKTIF" else "MATI", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Text("(ubah dari tab Buat Suara)", style = MaterialTheme.typography.bodySmall, color = SkTextDim)
            }
        }
    }
}
