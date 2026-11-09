package com.daftech.suaraku.studio

/**
 * Naskah Studio v1.5.0 (Supertonic-only, dialog multi-suara).
 *
 * Format naskah — satu baris dialog per baris:
 * ```
 * Narator: Banjir melanda tiga kecamatan di wilayah utara.
 * Budi: Kita berangkat jam tujuh.
 * Kita bawa bekal secukupnya.
 * [jeda 2s]
 * Narator: Mereka pun berangkat.
 * ```
 * Aturan parser (semuanya diuji di TestV15):
 * - Baris `Nama: kalimat...` memulai dialog speaker itu. Nama harus diawali
 *   huruf, panjang 1..30 karakter, tanpa angka — supaya `Jam 07:30 ...` atau
 *   `Rp1.250.000: ...` TIDAK salah dibaca sebagai nama speaker.
 * - Baris tanpa prefix nama = lanjutan speaker sebelumnya (tetap jadi segmen
 *   sendiri = satu cue SRT sendiri). Baris sebelum ada speaker mana pun
 *   memakai speaker default [DEFAULT_SPEAKER] ("Narator").
 * - Baris kosong = satu jeda 0,8 detik DI ANTARA dua dialog; baris kosong
 *   berurutan tetap satu jeda (bukan dijumlah — untuk jeda lebih panjang
 *   pakai token [jeda Ns]); baris kosong di awal/akhir naskah diabaikan
 *   (Enter nyasar tidak menambah hening di ujung file).
 * - Token jeda eksplisit `[jeda]` (= 0,8 dtk) atau `[jeda 2s]` — boleh satu
 *   baris sendiri ATAU di tengah baris dialog (teks sebelum/sesudah token
 *   jadi segmen dialog terpisah). Angka boleh pakai koma desimal
 *   (`[jeda 0,5s]`) dan satuan `s` / `d` / `detik` (opsional, spasi opsional).
 *   Durasi di-clamp ke [MIN_PAUSE_SECONDS]..[MAX_PAUSE_SECONDS] (0,5–3 dtk).
 * - Token jeda TIDAK ikut dibacakan (dihapus dari teks dialog).
 * - Token [jeda] berurutan digabung jadi satu (jumlah durasi, cap [MAX_PAUSE_SECONDS]).
 * - Nama speaker didedup tanpa peduli huruf besar/kecil, ejaan pertama yang
 *   menang ("Budi" dan "budi" = satu speaker bernama "Budi").
 *
 * File ini MURNI (tanpa Android) supaya bisa diuji di JVM.
 */

/** Satu segmen naskah studio. */
sealed interface StudioSegment {
    /** Satu baris dialog satu speaker. */
    data class Dialogue(val speaker: String, val text: String) : StudioSegment
    /** Hening selama [seconds] detik (sudah di-clamp 0,5–3 dtk). */
    data class Pause(val seconds: Float) : StudioSegment
}

/** Status satu segmen saat generate studio berjalan. */
enum class StudioSegmentStatus { QUEUED, RUNNING, DONE, FAILED }

/** Satu segmen + status generate-nya (dipakai ViewModel + UI Studio). */
data class StudioRunItem(
    val index: Int,
    val segment: StudioSegment,
    val status: StudioSegmentStatus = StudioSegmentStatus.QUEUED,
    val error: String? = null
)

const val DEFAULT_SPEAKER = "Narator"

/** Jeda default: baris kosong dan token `[jeda]` tanpa angka. */
const val DEFAULT_PAUSE_SECONDS = 0.8f
const val MIN_PAUSE_SECONDS = 0.5f
const val MAX_PAUSE_SECONDS = 3.0f

/** Maksimal speaker dalam satu naskah (satu sid Supertonic per speaker). */
const val MAX_SPEAKERS = 10

/**
 * Urutan sid default per speaker (indeks = urutan kemunculan pertama):
 * speaker pertama sid 3 (paling cepat di spike + suara pertama yang
 * didengar Putra), kedua sid 0, lalu 1, 2, 4, 5, 6, 7, 8, 9.
 */
val DEFAULT_SIDS: List<Int> = listOf(3, 0, 1, 2, 4, 5, 6, 7, 8, 9)

/** Clamp durasi jeda ke 0,5–3 dtk. */
fun clampPause(seconds: Float): Float = seconds.coerceIn(MIN_PAUSE_SECONDS, MAX_PAUSE_SECONDS)

private val SPEAKER_PREFIX = Regex(
    """^([A-Za-zÀ-ÿ][A-Za-zÀ-ÿ .'\-]{0,29}):(.*)$"""
)

/**
 * Token jeda: `[jeda]`, `[jeda 2s]`, `[jeda 0,5s]`, `[jeda 2 detik]`,
 * `[jeda 2d]`. Grup 1 = angka (opsional, koma desimal diterima),
 * grup 2 = satuan (opsional: s/d/detik).
 */
private val PAUSE_TOKEN = Regex(
    """\[\s*jeda(?:\s+(\d+(?:[.,]\d+)?)\s*(d|detik|s)?)?\s*\]""",
    RegexOption.IGNORE_CASE
)

/** Parse durasi token jeda; tanpa angka = [DEFAULT_PAUSE_SECONDS]. */
fun parsePauseSeconds(token: String): Float {
    val m = PAUSE_TOKEN.matchEntire(token.trim()) ?: return DEFAULT_PAUSE_SECONDS
    val num = m.groupValues[1]
    if (num.isEmpty()) return DEFAULT_PAUSE_SECONDS
    val v = num.replace(',', '.').toFloatOrNull() ?: return DEFAULT_PAUSE_SECONDS
    return clampPause(v)
}

/**
 * Parse naskah mentah jadi daftar segmen. Tidak pernah melempar untuk teks
 * biasa; naskah kosong -> daftar kosong. Validasi jumlah speaker (>10)
 * dilakukan terpisah di [validateVoiceMap] / saat generate.
 */
fun parseStudioScript(raw: String): List<StudioSegment> {
    val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
    val out = mutableListOf<StudioSegment>()
    var currentSpeaker = DEFAULT_SPEAKER
    // Jeda dari baris kosong: ditunda sampai ada dialog sesudahnya (supaya
    // tidak jadi hening di awal/akhir naskah); baris kosong berurutan tetap
    // satu jeda — jeda lebih panjang pakai token [jeda Ns].
    var pendingBlankPause = false

    fun flushBlankPause() {
        if (!pendingBlankPause) return
        pendingBlankPause = false
        val last = out.lastOrNull()
        if (last is StudioSegment.Pause) {
            out[out.lastIndex] = StudioSegment.Pause(clampPause(last.seconds + DEFAULT_PAUSE_SECONDS))
        } else if (out.isNotEmpty()) {
            out.add(StudioSegment.Pause(DEFAULT_PAUSE_SECONDS))
        }
    }

    fun addDialogue(speaker: String, text: String) {
        val t = text.trim()
        if (t.isNotEmpty()) {
            flushBlankPause()
            out.add(StudioSegment.Dialogue(speaker, t))
        }
    }

    fun addPause(seconds: Float) {
        flushBlankPause()
        val last = out.lastOrNull()
        if (last is StudioSegment.Pause) {
            // Token [jeda] berurutan digabung (jumlah, cap 3 dtk).
            out[out.lastIndex] = StudioSegment.Pause(clampPause(last.seconds + seconds))
        } else {
            out.add(StudioSegment.Pause(clampPause(seconds)))
        }
    }

    /** Potong satu potongan baris (sudah tanpa prefix speaker) jadi dialog/jeda. */
    fun emitPieces(rest: String, speaker: String) {
        var pos = 0
        val text = StringBuilder()
        fun flushText() {
            if (text.isNotEmpty()) {
                addDialogue(speaker, text.toString())
                text.clear()
            }
        }
        for (m in PAUSE_TOKEN.findAll(rest)) {
            text.append(rest.substring(pos, m.range.first))
            flushText()
            addPause(parsePauseSeconds(m.value))
            pos = m.range.last + 1
        }
        text.append(rest.substring(pos))
        flushText()
    }

    for (rawLine in normalized.lines()) {
        val line = rawLine.trim()
        if (line.isEmpty()) {
            // Hanya jadi jeda kalau sudah ada dialog sebelumnya; kalau belum
            // ada dialog (awal naskah), baris kosong diabaikan.
            if (out.any { it is StudioSegment.Dialogue }) pendingBlankPause = true
            continue
        }
        val prefix = SPEAKER_PREFIX.matchEntire(line)
        if (prefix != null) {
            // Baris "Nama:" tanpa kalimat = ganti speaker saja, tanpa segmen.
            currentSpeaker = prefix.groupValues[1].trim()
            if (prefix.groupValues[2].isNotBlank()) {
                emitPieces(prefix.groupValues[2], currentSpeaker)
            }
        } else {
            emitPieces(line, currentSpeaker)
        }
    }
    return out
}

/** Daftar speaker unik sesuai urutan kemunculan pertama (dedup tanpa peduli huruf). */
fun speakersOf(segments: List<StudioSegment>): List<String> {
    val seen = LinkedHashMap<String, String>()
    for (s in segments) {
        if (s is StudioSegment.Dialogue) {
            val key = s.speaker.lowercase()
            if (!seen.containsKey(key)) seen[key] = s.speaker
        }
    }
    return seen.values.toList()
}

/**
 * Pemetaan sid default untuk [speakers] (urutan [DEFAULT_SIDS]).
 * @throws IllegalArgumentException bila speaker > [MAX_SPEAKERS].
 */
fun defaultVoiceMap(speakers: List<String>): Map<String, Int> {
    require(speakers.size <= MAX_SPEAKERS) {
        "Maksimal $MAX_SPEAKERS speaker dalam satu naskah studio, dapat ${speakers.size}"
    }
    return speakers.mapIndexed { i, name -> name to DEFAULT_SIDS[i] }.toMap()
}

/**
 * Validasi pemetaan suara: tiap speaker harus punya sid 0..9, jumlah speaker
 * <= 10. Mengembalikan pesan error, atau null bila valid.
 */
fun validateVoiceMap(speakers: List<String>, voiceMap: Map<String, Int>): String? {
    if (speakers.size > MAX_SPEAKERS) {
        return "Maksimal $MAX_SPEAKERS speaker dalam satu naskah studio, dapat ${speakers.size}"
    }
    for (name in speakers) {
        val sid = voiceMap[name]
            ?: return "Speaker \"$name\" belum dipetakan ke suara (sid 0..9)"
        if (sid !in 0..9) return "Suara speaker \"$name\" di luar rentang (sid $sid, harus 0..9)"
    }
    return null
}

/** Format timestamp SRT: HH:MM:SS,mmm (milidetik selalu 3 digit). */
fun formatSrtTimestamp(ms: Long): String {
    val total = ms.coerceAtLeast(0)
    val h = total / 3_600_000
    val m = (total % 3_600_000) / 60_000
    val s = (total % 60_000) / 1_000
    val milli = total % 1_000
    return "%02d:%02d:%02d,%03d".format(h, m, s, milli)
}

/** Satu cue SRT (teks sudah termasuk prefix "[Nama] "). */
data class SrtCue(val startMs: Long, val endMs: Long, val text: String)

/** Bangun teks .srt standar dari daftar cue (penomoran ulang 1..n berurutan). */
fun buildSrt(cues: List<SrtCue>): String {
    val sb = StringBuilder()
    cues.forEachIndexed { i, cue ->
        sb.append(i + 1).append('\n')
        sb.append(formatSrtTimestamp(cue.startMs))
        sb.append(" --> ")
        sb.append(formatSrtTimestamp(cue.endMs))
        sb.append('\n')
        sb.append(cue.text.trim()).append('\n')
        sb.append('\n')
    }
    return sb.toString()
}
