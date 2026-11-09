package com.daftech.suaraku.audio

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Penyimpanan hasil generate: folder app (riwayat) + ekspor ke Download/SuaraKu + share. */
object AudioStore {

    fun outputDir(context: Context): File {
        val base = context.getExternalFilesDir("SuaraKu") ?: File(context.filesDir, "SuaraKu")
        val dir = File(base, "hasil")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Nama file output konsisten v1.3.0:
     * SuaraKu_YYYYMMDD_HHmm_<engine>-<voPreset>_<nn>.<ext>
     * (nn = nomor urut 2 digit, 01 untuk generate biasa, 01..N untuk batch).
     * Menit (bukan detik) + nomor urut membuat nama tetap unik dalam satu
     * menit yang sama tanpa terlihat seperti nama sementara.
     */
    /** Nama file saja (murni, tanpa Context) — dipakai unit test JVM. */
    fun outputFileName(engineTag: String, voPresetId: String, index: Int, ext: String, date: Date = Date()): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(date)
        val nn = "%02d".format(index)
        return "SuaraKu_${stamp}_${engineTag}-${voPresetId}_$nn.$ext"
    }

    fun newNamedOutputFile(context: Context, engineTag: String, voPresetId: String, index: Int, ext: String): File {
        return File(outputDir(context), outputFileName(engineTag, voPresetId, index, ext))
    }

    /**
     * Nama file Supertonic (v1.4.0, format KHUSUS — tanpa preset voice-over,
     * karena Supertonic tidak punya parameter prosody per-engine):
     * SuaraKu_YYYYMMDD_HHmm_supertonic-sidN_<nn>.wav
     * (sidN = sid 0..9 yang dipakai). Murni, tanpa Context — dipakai unit test JVM.
     */
    fun supertonicFileName(sid: Int, index: Int, date: Date = Date()): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(date)
        val nn = "%02d".format(index)
        return "SuaraKu_${stamp}_supertonic-sid$sid" + "_$nn.wav"
    }

    fun newSupertonicOutputFile(context: Context, sid: Int, index: Int): File {
        return File(outputDir(context), supertonicFileName(sid, index))
    }

    /**
     * Nama file Studio v1.5.0 (Supertonic-only, format KHUSUS):
     * SuaraKu_YYYYMMDD_HHmm_studio_<nn>.wav — WAV gabungan dialog + jeda.
     * File SRT memakai prefix yang sama (lihat [studioSrtFileName]).
     * Murni, tanpa Context — dipakai unit test JVM.
     */
    fun studioFileName(index: Int, date: Date = Date()): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(date)
        val nn = "%02d".format(index)
        return "SuaraKu_${stamp}_studio" + "_$nn.wav"
    }

    /** Nama file SRT pendamping hasil Studio (prefix sama dengan [studioFileName]). */
    fun studioSrtFileName(index: Int, date: Date = Date()): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(date)
        val nn = "%02d".format(index)
        return "SuaraKu_${stamp}_studio" + "_$nn.srt"
    }

    /**
     * Nama file pratinjau satu baris Studio (bukan hasil gabungan).
     * Stempel sampai DETIK (v1.6.0) supaya tiap pratinjau punya path
     * berbeda — syarat auto-play (LaunchedEffect ber-key path file):
     * pratinjau baru dalam menit yang sama tidak boleh menimpa path
     * pratinjau lama.
     */
    fun studioPreviewFileName(date: Date = Date()): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(date)
        return "SuaraKu_${stamp}_studio-preview.wav"
    }

    /**
     * Pasangan file hasil Studio (WAV + SRT) dengan stempel waktu yang SAMA.
     * Stempel dihitung sekali supaya WAV dan SRT tidak bisa terpisah oleh
     * pergantian menit di tengah generate.
     */
    fun newStudioFilePair(context: Context, index: Int): Pair<File, File> {
        val now = Date()
        return File(outputDir(context), studioFileName(index, now)) to
            File(outputDir(context), studioSrtFileName(index, now))
    }

    fun newStudioPreviewFile(context: Context): File {
        return File(outputDir(context), studioPreviewFileName())
    }

    fun listHistory(context: Context): List<File> =
        outputDir(context).listFiles { f -> f.isFile && (f.extension == "wav" || f.extension == "mp3") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    /**
     * Salin file hasil ke Download/SuaraKu (publik).
     * @return nama/path tujuan bila sukses, null bila gagal (file tetap aman di folder app).
     */
    fun saveToDownloads(context: Context, file: File): String? {
        // v1.5.0: SRT ikut bisa disimpan (mime subtitle, bukan audio).
        val mime = when (file.extension) {
            "mp3" -> "audio/mpeg"
            "srt" -> "application/x-subrip"
            else -> "audio/wav"
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SuaraKu")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(file).use { it.copyTo(out) }
                } ?: return null
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "Download/SuaraKu/${file.name}"
            } else {
                @Suppress("DEPRECATION")
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val dir = File(downloads, "SuaraKu")
                if (!dir.exists()) dir.mkdirs()
                val dest = File(dir, file.name)
                file.copyTo(dest, overwrite = true)
                dest.absolutePath
            }
        } catch (e: Exception) {
            null
        }
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val mime = if (file.extension == "mp3") "audio/mpeg" else "audio/wav"
        return Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
