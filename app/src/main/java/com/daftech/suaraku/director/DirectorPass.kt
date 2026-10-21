package com.daftech.suaraku.director

/**
 * Director Pass lokal (rule-based, tanpa AI):
 * membersihkan teks supaya dibaca mesin TTS lebih natural —
 * singkatan diperluas, angka/Rupiah dieja Bahasa Indonesia,
 * teks dipecah per kalimat (maks ~350 char untuk panggilan API),
 * dan tiap chunk dijamin berakhir tanda baca.
 */
object DirectorPass {

    const val MAX_CHUNK_CHARS = 350

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
    fun chunk(raw: String, applyDirector: Boolean): List<String> {
        val text = if (applyDirector) normalize(raw) else raw.replace(Regex("""\s+"""), " ").trim()
        if (text.isEmpty()) return emptyList()
        val sentences = Regex("""(?<=[.!?…])\s+|\n+""")
            .split(text).map { it.trim() }.filter { it.isNotEmpty() }
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) { chunks.add(finishSentence(current.toString())); current.clear() }
        }
        for (s in sentences) {
            if (s.length > MAX_CHUNK_CHARS) {
                flush()
                // Kalimat kepanjangan: potong di koma/titik koma, terakhir potong paksa.
                var rest = s
                while (rest.length > MAX_CHUNK_CHARS) {
                    val cut = rest.lastIndexOfAny(charArrayOf(',', ';', ' '), MAX_CHUNK_CHARS)
                    val idx = if (cut > 40) cut else MAX_CHUNK_CHARS
                    chunks.add(finishSentence(rest.substring(0, idx).trim()))
                    rest = rest.substring(idx).trim().trimStart(',', ';')
                }
                if (rest.isNotEmpty()) chunks.add(finishSentence(rest))
                continue
            }
            if (current.length + 1 + s.length > MAX_CHUNK_CHARS) flush()
            if (current.isNotEmpty()) current.append(' ')
            current.append(s)
        }
        flush()
        return chunks
    }

    private fun finishSentence(s: String): String {
        val t = s.trim()
        return if (t.isNotEmpty() && t.last() in ".!?…") t else "$t."
    }
}
