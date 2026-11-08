package com.daftech.suaraku.director

/**
 * Director Pass lokal (rule-based, tanpa AI):
 * membersihkan teks supaya dibaca mesin TTS lebih natural —
 * singkatan diperluas, angka/Rupiah dieja Bahasa Indonesia,
 * teks dipecah per kalimat, dan tiap chunk dijamin berakhir tanda baca.
 *
 * v2 (SuaraKu 1.3.0) — "chunking pintar":
 * - Ekor kecil (< [SMALL_TAIL_CHARS] karakter) digabung ke chunk sebelumnya
 *   supaya tidak buang 1 request Gemini untuk 1 kata.
 * - Tidak pecah di dalam tanda kutip/dialog: pemisah kalimat mengenali
 *   penutup kutip (." / ?" / !") dan pemotongan kalimat panjang memilih
 *   titik potong yang menjaga keseimbangan kutip bila ada.
 * - Singkatan bertitik (dst., dsb., dll., a.n., s.d., d.a.) dilindungi dari
 *   pemisah kalimat dengan masking sementara (titik diganti sentinel),
 *   berlaku baik saat Director Pass aktif maupun mati.
 * - Kalimat super panjang dipotong di batas klausa (koma/titik koma, lalu
 *   kata sambung), bukan potong paksa di tengah kata.
 *
 * Batas chunk PER ENGINE (kenapa angkanya beda):
 * - Gemini 350 karakter (sama seperti v1): 1 chunk = 1 request API, jadi
 *   batas ini menjaga hitungan kuota tetap transparan (free ±15/hari) dan
 *   prosodi stabil karena tiap chunk pendek & utuh.
 * - Edge 600 karakter: endpoint WebSocket Microsoft menerima teks lebih
 *   panjang dengan nyaman; chunk lebih panjang = lebih sedikit sesi dan
 *   jeda antar potongan, tanpa biaya API.
 * - Offline 800 karakter: tidak ada biaya API sama sekali (lokal), Piper /
 *   TTS sistem sanggup membaca teks panjang; batas 800 tetap dijaga supaya
 *   prosodi tidak rusak dan satu utterance sistem tidak terlalu besar.
 * Fungsi lama [chunk] tetap memakai 350 (kompatibel dengan perilaku v1).
 */
object DirectorPass {

    const val MAX_CHUNK_CHARS = 350

    /** Batas chunk per engine (lihat KDoc di atas untuk alasannya). */
    const val GEMINI_CHUNK_CHARS = 350
    const val EDGE_CHUNK_CHARS = 600
    const val OFFLINE_CHUNK_CHARS = 800
    /** Supertonic 3: sama seperti Offline (800) — model on-device tanpa batas API cloud; chunk besar = lebih sedikit jeda antar potongan. */
    const val SUPERTONIC_CHUNK_CHARS = 800

    /** Ekor di bawah panjang ini digabung ke chunk sebelumnya bila muat. */
    const val SMALL_TAIL_CHARS = 60

    /** Batas chunk untuk engine tertentu ("GEMINI"/"EDGE"/"OFFLINE"/"SUPERTONIC", default 350). */
    fun chunkLimitFor(engine: String): Int = when (engine) {
        "EDGE" -> EDGE_CHUNK_CHARS
        "OFFLINE" -> OFFLINE_CHUNK_CHARS
        "SUPERTONIC" -> SUPERTONIC_CHUNK_CHARS
        else -> GEMINI_CHUNK_CHARS
    }

    private val ABBREV: List<Pair<Regex, String>> = listOf(
        Regex("""\byg\b""", RegexOption.IGNORE_CASE) to "yang",
        Regex("""\bdgn\b""", RegexOption.IGNORE_CASE) to "dengan",
        Regex("""\bdll\b\.?""", RegexOption.IGNORE_CASE) to "dan lain-lain",
        Regex("""\bdkk\b\.?""", RegexOption.IGNORE_CASE) to "dan kawan-kawan",
        Regex("""\bsbb\b\.?""", RegexOption.IGNORE_CASE) to "sebagai berikut",
        Regex("""\btsb\b\.?""", RegexOption.IGNORE_CASE) to "tersebut",
        Regex("""\butk\b""", RegexOption.IGNORE_CASE) to "untuk",
        Regex("""\bkrn\b""", RegexOption.IGNORE_CASE) to "karena",
        Regex("""\bsdh\b""", RegexOption.IGNORE_CASE) to "sudah",
        Regex("""\bblm\b""", RegexOption.IGNORE_CASE) to "belum",
        Regex("""\btdk\b""", RegexOption.IGNORE_CASE) to "tidak",
        Regex("""\bbpk\b\.?""", RegexOption.IGNORE_CASE) to "bapak",
        Regex("""\bprof\b\.""", RegexOption.IGNORE_CASE) to "profesor",
        Regex("""\bdr\b\.""", RegexOption.IGNORE_CASE) to "dokter",
        Regex("""\bno\b\.""", RegexOption.IGNORE_CASE) to "nomor",
        Regex("""\bjl\b\.""", RegexOption.IGNORE_CASE) to "jalan",
        Regex("""\btgl\b\.?""", RegexOption.IGNORE_CASE) to "tanggal"
    )

    /**
     * Singkatan bertitik yang TIDAK boleh memecah kalimat. "dll." sudah
     * diperluas oleh [normalize] saat Director Pass aktif, tapi saat
     * Director Pass mati ia tetap harus dilindungi — jadi masking di sini
     * berlaku untuk semua singkatan di daftar, apa pun mode-nya.
     */
    private val PROTECTED_ABBREV = listOf("dst.", "dsb.", "dll.", "a.n.", "s.d.", "d.a.")

    private const val DOT_SENTINEL = '\u0001'

    /** Sembunyikan titik di dalam singkatan terproteksi supaya pemisah kalimat tidak salah potong. */
    internal fun maskProtected(text: String): String {
        var t = text
        for (abbr in PROTECTED_ABBREV) {
            t = t.replace(abbr, abbr.replace('.', DOT_SENTINEL), ignoreCase = true)
        }
        return t
    }

    /** Kembalikan titik sentinel ke titik biasa. */
    internal fun unmaskProtected(text: String): String = text.replace(DOT_SENTINEL, '.')

    private val UNITS = arrayOf("", "satu", "dua", "tiga", "empat", "lima", "enam", "tujuh", "delapan", "sembilan")

    /** Eja bilangan bulat 0..999_999_999_999 ke Bahasa Indonesia. */
    fun numberToWords(n: Long): String {
        if (n == 0L) return "nol"
        if (n < 0) return "minus " + numberToWords(-n)
        val sb = StringBuilder()
        fun appendPart(part: String) {
            if (part.isNotEmpty()) { if (sb.isNotEmpty()) sb.append(' '); sb.append(part) }
        }
        var rest = n
        val miliar = rest / 1_000_000_000; rest %= 1_000_000_000
        val juta = rest / 1_000_000; rest %= 1_000_000
        val ribu = rest / 1_000; val sisa = rest % 1_000
        if (miliar > 0) appendPart(underThousand(miliar.toInt()) + " miliar")
        if (juta > 0) appendPart(underThousand(juta.toInt()) + " juta")
        if (ribu > 0) appendPart(if (ribu == 1L) "seribu" else underThousand(ribu.toInt()) + " ribu")
        if (sisa > 0) appendPart(underThousand(sisa.toInt()))
        return sb.toString()
    }

    private fun underThousand(n: Int): String {
        val sb = StringBuilder()
        fun appendPart(part: String) {
            if (part.isNotEmpty()) { if (sb.isNotEmpty()) sb.append(' '); sb.append(part) }
        }
        val ratus = n / 100; val rest = n % 100
        if (ratus > 0) appendPart(if (ratus == 1) "seratus" else UNITS[ratus] + " ratus")
        when {
            rest == 0 -> {}
            rest < 10 -> appendPart(UNITS[rest])
            rest == 10 -> appendPart("sepuluh")
            rest == 11 -> appendPart("sebelas")
            rest < 20 -> appendPart(UNITS[rest - 10] + " belas")
            else -> {
                appendPart(UNITS[rest / 10] + " puluh")
                if (rest % 10 > 0) appendPart(UNITS[rest % 10])
            }
        }
        return sb.toString()
    }

    private fun expandNumbers(text: String): String {
        var t = text
        // Rupiah: "Rp50.000" / "Rp 150rb" -> eja + "rupiah" ("rb" ditangani sebagai ribu)
        t = Regex("""Rp\.?\s*(\d{1,3}(?:\.\d{3})+|\d+)(?:\s*(rb|ribu))?""", RegexOption.IGNORE_CASE)
            .replace(t) { m ->
                val num = m.groupValues[1].replace(".", "").toLongOrNull()
                val mult = if (m.groupValues[2].isNotEmpty()) 1000L else 1L
                if (num != null) numberToWords(num * mult) + " rupiah" else m.value
            }
        // Desimal koma: 3,5 -> tiga koma lima
        t = Regex("""(\d+),(\d+)""").replace(t) { m ->
            val a = m.groupValues[1].toLongOrNull(); val b = m.groupValues[2]
            if (a != null) numberToWords(a) + " koma " + b.map { c -> UNITS[c.digitToInt()] }.joinToString(" ") else m.value
        }
        // Angka ribuan dgn pemisah titik: 1.250.000 -> eja
        t = Regex("""\d{1,3}(?:\.\d{3})+""").replace(t) { m ->
            val num = m.value.replace(".", "").toLongOrNull()
            if (num != null) numberToWords(num) else m.value
        }
        // Bilangan bulat biasa (maks 12 digit)
        t = Regex("""\d{1,12}""").replace(t) { m ->
            val num = m.value.toLongOrNull()
            if (num != null) numberToWords(num) else m.value
        }
        return t
    }

    /** Normalisasi penuh (tanpa chunking). */
    fun normalize(raw: String): String {
        var t = raw.replace(Regex("""\s+"""), " ").trim()
        for ((regex, replacement) in ABBREV) t = regex.replace(t, replacement)
        t = expandNumbers(t)
        // Kapitalisasi awal kalimat sederhana setelah . ! ?
        t = t.replaceFirstChar { c -> c.uppercase() }
        return t
    }

    /** Pecah teks jadi chunk kalimat (<= MAX_CHUNK_CHARS), tiap chunk berujung tanda baca. */
    fun chunk(raw: String, applyDirector: Boolean): List<String> =
        chunkWithLimit(raw, applyDirector, MAX_CHUNK_CHARS)

    /** Pecah teks dengan batas chunk engine tertentu. */
    fun chunkFor(engine: String, raw: String, applyDirector: Boolean): List<String> =
        chunkWithLimit(raw, applyDirector, chunkLimitFor(engine))

    /** Kata sambung yang aman dipakai sebagai batas potong kalimat panjang. */
    private val CONJUNCTIONS = listOf(" dan ", " tetapi ", " karena ", " sehingga ", " lalu ", " kemudian ")

    /** true bila tanda kutip ganda dalam teks seimbang (tidak ada kutip yang terbuka). */
    internal fun quoteBalanced(s: String): Boolean {
        val doubles = s.count { it == '"' }
        val openCurly = s.count { it == '“' }
        val closeCurly = s.count { it == '”' }
        return doubles % 2 == 0 && openCurly == closeCurly
    }

    /**
     * Cari titik potong terbaik untuk kalimat panjang (panjang > limit).
     * Urutan: (1) koma/titik koma terakhir dalam batas; (2) kata sambung
     * terakhir (potong SEBELUM kata sambung, supaya sambungannya membuka
     * potongan berikutnya); (3) spasi terakhir; (4) potong paksa di limit
     * (hanya bila tidak ada spasi sama sekali, mis. satu kata raksasa).
     * Kandidat yang membuat kutip tidak seimbang dilewati bila ada
     * kandidat seimbang; bila tidak ada, pakai kandidat terbaik apa adanya.
     * @return indeks akhir potongan (eksklusif) dan apakah potongan berhenti
     *   di kata sambung (dipakai untuk membuang spasi di awal sisa).
     */
    internal fun findClauseCut(rest: String, limit: Int): Int {
        val candidates = mutableListOf<Int>()
        // (1) koma / titik koma — potong SETELAH tanda baca.
        var i = minOf(limit, rest.length) - 1
        while (i > 40) {
            if (rest[i] == ',' || rest[i] == ';') {
                val cut = i + 1
                if (cut <= limit) candidates.add(cut)
                break
            }
            i--
        }
        // (2) kata sambung — potong SEBELUM kata sambung.
        for (conj in CONJUNCTIONS) {
            var idx = rest.lastIndexOf(conj, minOf(limit, rest.length))
            while (idx > 40) {
                candidates.add(idx)
                break
            }
        }
        // (3) spasi terakhir dalam batas.
        val space = rest.lastIndexOf(' ', minOf(limit, rest.length))
        if (space > 40) candidates.add(space)
        // Pilih kandidat terbesar yang menjaga kutip seimbang; bila tidak ada,
        // pakai kandidat terbesar apa adanya; bila tidak ada kandidat sama sekali,
        // potong paksa di limit.
        val balanced = candidates.filter { quoteBalanced(rest.substring(0, it)) }
        val best = (if (balanced.isNotEmpty()) balanced else candidates).maxOrNull()
        return best ?: minOf(limit, rest.length)
    }

    /** Gabungkan ekor kecil (< SMALL_TAIL_CHARS) ke chunk sebelumnya bila muat dan kutip tetap seimbang. */
    internal fun mergeSmallTails(chunks: List<String>, limit: Int): List<String> {
        if (chunks.size < 2) return chunks
        val out = chunks.toMutableList()
        var i = out.size - 1
        while (i > 0) {
            val tail = out[i]
            val merged = out[i - 1] + " " + tail
            if (tail.length < SMALL_TAIL_CHARS && merged.length <= limit && quoteBalanced(merged)) {
                out[i - 1] = merged
                out.removeAt(i)
            }
            i--
        }
        return out
    }

    /** Inti chunking dengan batas karakter tertentu. */
    fun chunkWithLimit(raw: String, applyDirector: Boolean, limit: Int): List<String> {
        val prepared = if (applyDirector) normalize(raw) else raw.replace(Regex("""\s+"""), " ").trim()
        if (prepared.isEmpty()) return emptyList()
        val masked = maskProtected(prepared)
        // Pemisah kalimat: akhir kalimat (termasuk yang ditutup tanda kutip) atau baris baru.
        val sentences = Regex("""(?<=[.!?…]["'”’])\s+|(?<=[.!?…])\s+|\n+""")
            .split(masked).map { it.trim() }.filter { it.isNotEmpty() }
            .map { unmaskProtected(it) }
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) { chunks.add(finishSentence(current.toString(), limit)); current.clear() }
        }
        for (s in sentences) {
            if (s.length > limit) {
                flush()
                var rest = s
                while (rest.length > limit) {
                    val cut = findClauseCut(rest, limit)
                    var piece = rest.substring(0, cut).trim()
                    var nextRest = rest.substring(cut).trim().trimStart(',', ';')
                    // Bila potongan berakhir huruf dan sudah mentok di limit,
                    // mundur ke spasi sebelumnya supaya finishSentence bisa
                    // menambah "." tanpa melanggar batas; kata yang terbuang
                    // kembali ke sisa (bukan hilang).
                    if (piece.isNotEmpty() && piece.last() !in ".!?…,;" && piece.length >= limit) {
                        val sp = piece.lastIndexOf(' ')
                        if (sp > 40) {
                            val dropped = piece.substring(sp + 1)
                            piece = piece.substring(0, sp)
                            nextRest = "$dropped $nextRest"
                        }
                    }
                    chunks.add(finishSentence(piece, limit))
                    rest = nextRest
                }
                if (rest.isNotEmpty()) chunks.add(finishSentence(rest, limit))
                continue
            }
            if (current.length + 1 + s.length > limit) flush()
            if (current.isNotEmpty()) current.append(' ')
            current.append(s)
        }
        flush()
        return mergeSmallTails(chunks, limit)
    }

    /** Hasil preview chunk sebelum generate (transparansi kuota). */
    data class ChunkPreview(
        val chunkCount: Int,
        /** 1 chunk = 1 request API Gemini. */
        val geminiRequests: Int,
        /** 1 chunk = 1 sesi WebSocket Edge. */
        val edgeRequests: Int,
        /** Offline tidak memakai request API sama sekali. */
        val offlineRequests: Int
    )

    /** Hitung jumlah chunk + estimasi request untuk teks saat ini (tanpa generate). */
    fun preview(raw: String, applyDirector: Boolean, engine: String): ChunkPreview {
        val count = chunkFor(engine, raw, applyDirector).size
        return ChunkPreview(count, count, count, 0)
    }

    /**
     * Jamin chunk berujung tanda baca. Potongan yang sudah berakhir koma /
     * titik koma DIBIARKAN (TTS jeda di koma; menambah "." jadi ",." yang
     * salah). Titik penutup hanya ditambah bila masih ada sisa 1 karakter
     * di bawah [limit], supaya chunk tidak pernah melebihi batas.
     */
    private fun finishSentence(s: String, limit: Int = Int.MAX_VALUE): String {
        val t = s.trim()
        if (t.isEmpty()) return t
        if (t.last() in ".!?…,;") return t
        return if (t.length < limit) "$t." else t
    }
}
