package com.daftech.suaraku.ui

import java.io.File

/** Status satu item antrean batch. */
enum class BatchStatus { QUEUED, RUNNING, DONE, FAILED }

/** Satu bagian teks dalam antrean batch. */
data class BatchItem(
    val id: Int,
    val text: String,
    val status: BatchStatus = BatchStatus.QUEUED,
    val error: String? = null,
    val outputFile: File? = null
)

/**
 * Pemisah batch (v1.3.0) — SATU aturan yang konsisten:
 * bagian dipisah oleh BARIS KOSONG (2x newline atau lebih). Sebuah baris
 * yang isinya hanya "---" diperlakukan sama seperti baris kosong, supaya
 * naskah yang dipisah garis tetap terpotong di tempat yang sama.
 * Baris baru tunggal di dalam satu bagian digabung jadi spasi.
 */
fun splitBatchItems(raw: String): List<String> {
    val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
    val withSeps = normalized.lines().joinToString("\n") { line ->
        if (line.trim() == "---") "" else line
    }
    return withSeps.split(Regex("""\n{2,}"""))
        .map { it.replace("\n", " ").replace(Regex("""\s+"""), " ").trim() }
        .filter { it.isNotEmpty() }
}

/** Ringkasan akhir batch: "X berhasil, Y gagal dari Z bagian". */
fun summarizeBatch(items: List<BatchItem>): String {
    val ok = items.count { it.status == BatchStatus.DONE }
    val fail = items.count { it.status == BatchStatus.FAILED }
    return "$ok berhasil, $fail gagal dari ${items.size} bagian"
}

/**
 * Jalankan antrean batch. Murni (tanpa Android): `process` menerima satu
 * [BatchItem] dan mengembalikan file hasil; bila `process` melempar
 * exception, item itu ditandai FAILED dan antrean TETAP BERLANJUT ke item
 * berikutnya. `onItemUpdate` dipanggil setiap kali status satu item
 * berubah (pemanggil bertugas mem-posting ke Main thread bila perlu).
 */
suspend fun executeBatch(
    items: List<BatchItem>,
    process: suspend (BatchItem) -> File,
    onItemUpdate: (BatchItem) -> Unit
): List<BatchItem> {
    val result = items.toMutableList()
    for (i in result.indices) {
        val running = result[i].copy(status = BatchStatus.RUNNING)
        result[i] = running
        onItemUpdate(running)
        val done: BatchItem = try {
            val out = process(running)
            running.copy(status = BatchStatus.DONE, outputFile = out)
        } catch (e: Exception) {
            running.copy(status = BatchStatus.FAILED, error = e.message ?: "Gagal tanpa pesan")
        }
        result[i] = done
        onItemUpdate(done)
    }
    return result
}
