package com.daftech.suaraku

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.daftech.suaraku.ui.MainScreen
import com.daftech.suaraku.ui.SettingsScreen
import com.daftech.suaraku.ui.SuaraKuTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SuaraKuTheme {
                var showSettings by remember { mutableStateOf(false) }
                if (showSettings) {
                    SettingsScreen(onBack = { showSettings = false })
                } else {
                    MainScreen(onOpenSettings = { showSettings = true })
                }
            }
        }
    }
}
