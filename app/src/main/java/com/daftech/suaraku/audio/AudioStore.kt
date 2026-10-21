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

    fun newOutputFile(context: Context, engineTag: String, ext: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(outputDir(context), "suaraku_${engineTag}_$stamp.$ext")
    }

    fun listHistory(context: Context): List<File> =
        outputDir(context).listFiles { f -> f.isFile && (f.extension == "wav" || f.extension == "mp3") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    /**
     * Salin file hasil ke Download/SuaraKu (publik).
     * @return nama/path tujuan bila sukses, null bila gagal (file tetap aman di folder app).
     */
    fun saveToDownloads(context: Context, file: File): String? {
        val mime = if (file.extension == "mp3") "audio/mpeg" else "audio/wav"
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
