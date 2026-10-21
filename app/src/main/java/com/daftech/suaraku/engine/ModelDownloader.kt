package com.daftech.suaraku.engine

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Model Offline Piper Bahasa Indonesia (sherpa-onnx, TERKONFIRMASI ada):
 * vits-piper-id_ID-news_tts-medium.tar.bz2 (67.243.848 byte) dari release
 * tts-models repo k2-fsa/sherpa-onnx. Isi paket: *.onnx + tokens.txt +
 * espeak-ng-data/ — diekstrak ke filesDir/piper-id saat user menekan
 * tombol "Unduh Model Offline" di layar utama.
 */
object ModelDownloader {

    const val MODEL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-id_ID-news_tts-medium.tar.bz2"
    const val MODEL_SIZE_BYTES = 67_243_848L

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    fun modelDir(context: Context): File = File(context.filesDir, "piper-id")

    data class ModelFiles(val onnx: File, val tokens: File, val espeakDir: File?)

    /** Cari file model hasil ekstrak (nama .onnx dicari dinamis, tidak di-hardcode). */
    fun findModel(context: Context): ModelFiles? {
        val dir = modelDir(context)
        if (!dir.exists()) return null
        val onnx = dir.walkTopDown().firstOrNull { it.isFile && it.extension == "onnx" } ?: return null
        val tokens = dir.walkTopDown().firstOrNull { it.isFile && it.name == "tokens.txt" } ?: return null
        val espeak = dir.walkTopDown().firstOrNull { it.isDirectory && it.name == "espeak-ng-data" }
        return ModelFiles(onnx, tokens, espeak)
    }

    fun isReady(context: Context): Boolean = findModel(context) != null

    /** Unduh + ekstrak. onProgress: 0..100 (persen gabungan unduh+ekstrak kasar). */
    fun downloadAndExtract(context: Context, onProgress: (Int) -> Unit) {
        val dir = modelDir(context)
        val tarFile = File(context.cacheDir, "piper-id.tar.bz2")
        val request = Request.Builder().url(MODEL_URL).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Unduh model gagal: HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("Unduh model gagal: respons kosong")
            val total = if (body.contentLength() > 0) body.contentLength() else MODEL_SIZE_BYTES
            var read = 0L
            body.byteStream().use { input ->
                FileOutputStream(tarFile).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        onProgress((read * 80 / total).toInt().coerceIn(0, 80))
                    }
                }
            }
        }
        // Ekstrak: buang komponen folder pertama ("vits-piper-id_ID-news_tts-medium/...")
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(FileInputStream(tarFile)))).use { tar ->
            var entry = tar.nextTarEntry
            var count = 0
            while (entry != null) {
                val name = entry.name
                val rel = name.substringAfter('/', "")
                if (rel.isNotEmpty() && !rel.contains("..")) {
                    val outFile = File(dir, rel)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = tar.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                            }
                        }
                    }
                }
                count++
                if (count % 200 == 0) onProgress(80 + (count % 100) / 5)
                entry = tar.nextTarEntry
            }
        }
        tarFile.delete()
        if (!isReady(context)) throw IllegalStateException("Ekstrak selesai tapi file model (.onnx/tokens.txt) tidak ditemukan")
        onProgress(100)
    }
}
