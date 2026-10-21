# SuaraKu v1.0.0 — Text-to-Voice Pribadi

APK Android pribadi (Kotlin native + Jetpack Compose Material3) untuk mengubah teks jadi suara.
**Pemakaian pribadi** — tanpa lisensi aktivasi, tanpa iklan, tanpa Firebase.
Package: `com.daftech.suaraku` • versionCode 1 / versionName 1.0.0 • minSdk 24, targetSdk 34.

## Tiga Mesin Suara

### 1. Gemini Ekspresif (online, UTAMA)
- API: `POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent`,
  header `x-goog-api-key` — **key milik user sendiri**, diisi di Pengaturan, disimpan di
  SharedPreferences privat HP ini saja (tidak di-hardcode, tidak di-log, dikecualikan dari backup).
- Model: `gemini-2.5-flash-preview-tts` (bawaan) / `gemini-2.5-pro-preview-tts`.
- 29 suara prebuilt Gemini (Zephyr, Puck, Kore, Fenrir, dll).
- Ekspresi diarahkan lewat teks arahan natural di depan teks (preset: Netral, Ceria, Tegas,
  Sedih, Berbisik, Semangat) atau kolom gaya kustom bebas.
- Output PCM s16le 24 kHz dari API digabung per-potongan lalu dibungkus header WAV.

### 2. Edge Gratis (online)
- Port Kotlin protokol `rany2/edge-tts`: WebSocket ke endpoint Read Aloud Microsoft Edge,
  token `Sec-MS-GEC` = SHA-256(ticks filetime dibulatkan 5 menit + TrustedClientToken),
  retry sekali dengan koreksi clock-skew dari header `Date` server saat kena 403.
- Suara: Ardi & Gadis (id-ID), Guy, Jenny, Christopher (en-US), Ryan, Sonia (en-GB). Output MP3.
- **JUJUR**: ini endpoint TIDAK resmi. Microsoft sudah pernah mengubah token & memigrasikan
  endpoint (v2) yang mematahkan klien lama dengan 403. Bila suatu hari engine ini mati total,
  itu di luar kendali app — pakai Gemini / Offline.

### 3. Offline (di dalam HP)
- **sherpa-onnx v1.13.8 + Piper id_ID** (`vits-piper-id_ID-news_tts-medium`, TERKONFIRMASI ada
  di release `tts-models` repo k2-fsa/sherpa-onnx, 67.243.848 byte): diunduh sekali dari dalam
  app (tombol "Unduh Model Offline"), diekstrak (tar.bz2 via commons-compress) ke
  `filesDir/piper-id`, inferensi CPU via `OfflineTts` (API diverifikasi dari source resmi
  `sherpa-onnx/kotlin-api/Tts.kt` + bytecode AAR 1.13.8). Suara netral/datar — harga mode offline.
- **Fallback otomatis**: bila model belum diunduh / gagal dimuat → TTS sistem Android
  (`synthesizeToFile`, locale id-ID, fallback en-US). Label di UI jujur menunjukkan mode aktif.

## Director Pass (lokal, bawaan AKTIF)
Rule-based, jalan untuk semua mesin: normalisasi spasi, perluas singkatan Indonesia
(yg→yang, dgn→dengan, dll→dan lain-lain, dkk, tsb, utk, krn, sdh, blm, tdk, bpk, prof., dr.,
no., jl., tgl), eja angka & Rupiah ke kata Bahasa Indonesia (Rp50.000 → "lima puluh ribu
rupiah", 1.250.000 → "satu juta dua ratus lima puluh ribu", desimal koma → "koma"),
pecah teks jadi potongan kalimat maks 350 karakter, jamin tiap potongan berakhir tanda baca.

## Fitur Lain
- Impor file `.txt` (document picker), hitung karakter.
- Slider kecepatan (semua mesin) + pitch (Edge & Offline).
- Player bawaan (putar/jeda/seek), Simpan ke `Download/SuaraKu` (MediaStore API 29+),
  Bagikan via FileProvider, tab Riwayat (putar/simpan/bagikan/hapus).

## Build
- Build resmi via **GitHub Actions** (lihat `PANDUAN_UPLOAD.md` di folder induk paket):
  workflow menyiapkan JDK 17 + Android SDK manual, **mengunduh AAR sherpa-onnx v1.13.8
  (50.129.134 byte) ke `app/libs/`** (file ini sengaja TIDAK masuk repo/zip — lihat `.gitignore`),
  lalu `gradle assembleDebug`, upload `SuaraKu-v1.0.0.apk` + `build.log` (if: always) ke
  rolling release `latest` / `build-log`. Debug-signed, tanpa secret apa pun.
- Build lokal (opsional): unduh AAR yang sama ke `app/libs/sherpa-onnx-1.13.8.aar`, lalu
  `gradle assembleDebug` (Gradle 8.7+, JDK 17).

## Verifikasi yang sudah dilakukan (sebelum rilis source)
- Paket Piper id_ID: HTTP 302 + daftar aset GitHub API + isi tar dicek (`.onnx`, `tokens.txt`,
  `espeak-ng-data/`).
- AAR sherpa 1.13.8: ukuran byte persis, classes.jar memuat `OfflineTts` dkk, tanda tangan
  metode dicek via `javap` (ctor, `generate(String,int,float)`, `sampleRate()`,
  `GeneratedAudio.samples/sampleRate`).
- Semua kode non-UI dikompilasi bersih dengan kotlinc terhadap android.jar + dependensi asli.
- Tes JVM: DirectorPass (ejaan angka, singkatan, Rupiah, chunking), WavUtil (header WAV),
  body JSON Gemini (struktur `contents/generationConfig/speechConfig` persis), dan
  `Sec-MS-GEC` Kotlin **identik byte-per-byte** dengan algoritma referensi Python edge-tts.
- Kode UI mengikuti pola Compose WarungBoss yang sudah terbukti build hijau
  (AGP 8.5.2 + Kotlin 1.9.25 + compose compiler 1.5.15). Kompilasi UI penuh menunggu
  GitHub Actions (Gradle tidak bisa jalan di sandbox pembuat).

## TODO / Risiko Dikenal
- [ ] Edge: pantau migrasi endpoint v2 Microsoft (`api.msedgeservices.com`); bila v1 mati,
      port ulang protokol ke v2 (token GEC format baru).
- [ ] Gemini 3.8 Flash TTS (rilis Sep 2026) sebagai pilihan model tambahan.
- [ ] Slot ElevenLabs (key sendiri) untuk cloning suara — belum masuk v1.
- [ ] Uji runtime di HP: handshake Edge dari IP Indonesia, RTF Piper di Helio G95,
      kualitas suara Piper int8 vs medium.
