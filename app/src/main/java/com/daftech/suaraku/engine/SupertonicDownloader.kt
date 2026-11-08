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
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Model Supertonic 3 Bahasa Indonesia (sherpa-onnx, TERKONFIRMASI ada):
 * sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2 (128.774.318 byte)
 * dari release tts-models repo k2-fsa/sherpa-onnx. Isi paket: 4 file ONNX
 * int8 (text_encoder, duration_predictor, vector_estimator, vocoder) +
 * voice.bin + unicode_indexer.bin + tts.json + LICENSE + README — diekstrak
 * ke filesDir/supertonic-id saat user menekan tombol "Unduh Model
 * Supertonic" di layar utama (SETELAH menyetujui ringkasan lisensi).
 *
 * Lisensi bobot = OpenRAIL-M (BigScience). Ringkasannya: pemakaian
 * komersial BOLEH, tapi ada restriksi penggunaan (di antaranya larangan
 * impersonasi/deepfake tanpa izin dan disinformasi) yang harus diteruskan
 * ke penerima berikutnya, dan output yang disebarkan WAJIB ditandai sebagai
 * machine-generated. Karena itu model TIDAK dibundel ke APK — diunduh
 * on-demand, dan ringkasan lisensi ditampilkan SEBELUM unduh dimulai.
 *
 * SHA-256 di bawah ini DIHITUNG LOKAL dari berkas unduhan 8 Nov 2026
 * (82fa96f9…c427) — halaman rilis TIDAK mempublikasikan checksum resmi,
 * jadi JANGAN klaim nilai ini sebagai checksum resmi sherpa-onnx. Fungsinya
 * di sini: memastikan berkas yang terunduh sama persis dengan yang diuji
 * pada spike test (bukan membuktikan keaslian dari penerbit).
 */
object SupertonicDownloader {

    const val MODEL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2"
    const val MODEL_SIZE_BYTES = 128_774_318L
    const val MODEL_SHA256 = "82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427"

    /** Ringkasan lisensi OpenRAIL-M yang ditampilkan sebelum unduh (bukan teks lisensi penuh). */
    const val LICENSE_SUMMARY =
        "Model Supertonic 3 dilisensikan di bawah OpenRAIL-M:\n" +
            "• Pemakaian komersial diperbolehkan.\n" +
            "• Dilarang dipakai untuk impersonasi/deepfake tanpa izin, disinformasi, dan penggunaan terlarang lain dalam lisensi.\n" +
            "• Suara hasil generate adalah machine-generated dan harus ditandai demikian bila disebarkan.\n" +
            "• Lisensi dan restriksinya diteruskan ke siapa pun yang menerima model ini.\n" +
            "Teks lisensi lengkap ikut terunduh bersama model (file LICENSE)."

    private const val TEXT_ENCODER = "text_encoder.int8.onnx"
    private const val DURATION_PREDICTOR = "duration_predictor.int8.onnx"
    private const val VECTOR_ESTIMATOR = "vector_estimator.int8.onnx"
    private const val VOCODER = "vocoder.int8.onnx"
    private const val VOICE_STYLE = "voice.bin"
    private const val UNICODE_INDEXER = "unicode_indexer.bin"
    private const val TTS_JSON = "tts.json"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .build()

    fun modelDir(context: Context): File = File(context.filesDir, "supertonic-id")

    data class SupertonicFiles(
        val textEncoder: File,
        val durationPredictor: File,
        val vectorEstimator: File,
        val vocoder: File,
        val voiceStyle: File,
        val unicodeIndexer: File,
        val ttsJson: File
    )

    /**
     * Cari file model hasil ekstrak di folder [dir]. Murni (tanpa Context)
     * supaya bisa diuji di JVM. Semua 7 file wajib ada; satu hilang =
     * model dianggap belum siap.
     */
    fun findModelInDir(dir: File): SupertonicFiles? {
        if (!dir.isDirectory) return null
        fun need(name: String): File? {
            val f = File(dir, name)
            return if (f.isFile && f.length() > 0) f else null
        }
        return SupertonicFiles(
            textEncoder = need(TEXT_ENCODER) ?: return null,
            durationPredictor = need(DURATION_PREDICTOR) ?: return null,
            vectorEstimator = need(VECTOR_ESTIMATOR) ?: return null,
            vocoder = need(VOCODER) ?: return null,
            voiceStyle = need(VOICE_STYLE) ?: return null,
            unicodeIndexer = need(UNICODE_INDEXER) ?: return null,
            ttsJson = need(TTS_JSON) ?: return null
        )
    }

    fun findModel(context: Context): SupertonicFiles? = findModelInDir(modelDir(context))

    fun isReady(context: Context): Boolean = findModel(context) != null

    /** SHA-256 heksadesimal (huruf kecil) dari isi [file]. Murni — dipakai unit test JVM. */
    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Unduh + verifikasi SHA-256 + ekstrak. onProgress: 0..100 (persen
     * gabungan unduh+ekstrak kasar, pola sama seperti ModelDownloader Piper).
     * SHA tidak cocok = tarball dihapus dan unduh DIBATALKAN (tidak ada
     * ekstrak). Folder model lama dihapus HANYA setelah SHA terverifikasi.
     */
    fun downloadAndExtract(context: Context, onProgress: (Int) -> Unit) {
        val dir = modelDir(context)
        val tarFile = File(context.cacheDir, "supertonic-id.tar.bz2")
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
        // Verifikasi SHA-256 SEBELUM ekstrak; gagal = hapus tarball, jangan sentuh model lama.
        val actual = sha256Hex(tarFile)
        if (!actual.equals(MODEL_SHA256, ignoreCase = true)) {
            tarFile.delete()
            throw IllegalStateException(
                "SHA-256 model tidak cocok (unduhan rusak/berubah). Coba unduh lagi; model lama (bila ada) tetap aman."
            )
        }
        // Ekstrak: buang komponen folder pertama ("sherpa-onnx-supertonic-3-tts-int8-2026-05-11/...")
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
        if (!isReady(context)) throw IllegalStateException("Ekstrak selesai tapi file model Supertonic tidak lengkap")
        onProgress(100)
    }
}
