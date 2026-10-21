package com.daftech.suaraku.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val SkGold = Color(0xFFF59E0B)
val SkBg = Color(0xFF0B1220)
val SkSurface = Color(0xFF131C2E)
val SkText = Color(0xFFF2F6FC)
val SkTextDim = Color(0xFF9DB0CC)
val SkFieldBg = Color(0xFF0F1930)

private val SkDarkColors = darkColorScheme(
    primary = SkGold,
    onPrimary = Color(0xFF1A1200),
    secondary = Color(0xFFFDE68A),
    background = SkBg,
    onBackground = SkText,
    surface = SkSurface,
    onSurface = SkText,
    surfaceVariant = Color(0xFF1C2942),
    onSurfaceVariant = SkTextDim,
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2B0000)
)

@Composable
fun SuaraKuTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SkDarkColors, content = content)
}

/**
 * Warna TextField di background gelap: teks + placeholder WAJIB eksplisit
 * terang (pelajaran LoopLive: default tema gelap bikin ketikan tak terlihat).
 */
@Composable
fun skFieldColors(): TextFieldColors = TextFieldDefaults.colors(
    focusedTextColor = SkText,
    unfocusedTextColor = SkText,
    disabledTextColor = SkTextDim,
    focusedContainerColor = SkFieldBg,
    unfocusedContainerColor = SkFieldBg,
    disabledContainerColor = SkFieldBg,
    cursorColor = SkGold,
    focusedPlaceholderColor = SkTextDim,
    unfocusedPlaceholderColor = SkTextDim,
    disabledPlaceholderColor = SkTextDim,
    focusedIndicatorColor = SkGold,
    unfocusedIndicatorColor = Color(0xFF2A3B5F),
    disabledIndicatorColor = Color(0xFF2A3B5F),
    focusedLabelColor = SkGold,
    unfocusedLabelColor = SkTextDim
)
