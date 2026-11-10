package com.daftech.suaraku.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Tema "Cyberpunk Premium" (v1.7.0).
 *
 * Emas PENSIUN di v1.7.0 — diganti neon cyan (primer) + magenta (sekunder).
 * Aturan keterbacaan: neon HANYA untuk aksen/label/indikator, TIDAK PERNAH
 * untuk teks isi — teks isi tetap putih terang di atas background hampir
 * hitam, supaya kontras tetap tinggi. Glow dipakai hemat (border tipis +
 * warna aksen), tanpa shadow berlapis yang berat.
 */

// Aksen neon.
val SkCyan = Color(0xFF00F0FF)
val SkMagenta = Color(0xFFFF2E88)
val SkBg = Color(0xFF05070D)
val SkSurface = Color(0xFF0A0F1E)
val SkText = Color(0xFFF2F6FC)
val SkTextDim = Color(0xFF9DB0CC)
val SkFieldBg = Color(0xFF0D1626)
/** Latar kartu error: merah gelap bernuansa magenta (bukan pink norak). */
val SkErrorContainer = Color(0xFF2A0E1E)
/** Garis tepi neon tipis untuk kartu (alpha rendah supaya tidak norak). */
val SkNeonBorder = BorderStroke(1.dp, SkCyan.copy(alpha = 0.20f))

private val SkDarkColors = darkColorScheme(
    primary = SkCyan,
    onPrimary = Color(0xFF002830),
    primaryContainer = Color(0xFF003A44),
    onPrimaryContainer = Color(0xFF9FF7FF),
    secondary = SkMagenta,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF3D0E26),
    onSecondaryContainer = Color(0xFFFF8FBE),
    background = SkBg,
    onBackground = SkText,
    surface = SkSurface,
    onSurface = SkText,
    surfaceVariant = Color(0xFF111A2E),
    onSurfaceVariant = SkTextDim,
    outline = Color(0xFF22304F),
    outlineVariant = Color(0xFF16223A),
    error = Color(0xFFFF5C7A),
    onError = Color(0xFF2B0000),
    errorContainer = SkErrorContainer,
    onErrorContainer = Color(0xFFFFB3C6)
)

/**
 * Tipografi HUD: judul + label monospace (FontFamily.Monospace bawaan
 * sistem — tanpa download/bundle font, APK tidak bengkak). Teks isi
 * (body) tetap font bawaan supaya enak dibaca panjang.
 */
private val SkTypography = Typography(
    titleLarge = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = 0.5.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 0.5.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 0.5.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 0.5.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.5.sp)
)

/** Bentuk tajam HUD: sudut kecil di semua komponen (kartu, chip, field). */
private val SkShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(4.dp),
    extraLarge = RoundedCornerShape(8.dp)
)

@Composable
fun SuaraKuTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SkDarkColors, typography = SkTypography, shapes = SkShapes, content = content)
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
    cursorColor = SkCyan,
    focusedPlaceholderColor = SkTextDim,
    unfocusedPlaceholderColor = SkTextDim,
    disabledPlaceholderColor = SkTextDim,
    focusedIndicatorColor = SkCyan,
    unfocusedIndicatorColor = Color(0xFF22304F),
    disabledIndicatorColor = Color(0xFF22304F),
    focusedLabelColor = SkCyan,
    unfocusedLabelColor = SkTextDim
)
