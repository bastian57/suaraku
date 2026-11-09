package com.daftech.suaraku.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decode MP3 (output Edge TTS: audio-24khz-48kbitrate-mono-mp3) jadi PCM16
 * mono, pakai MediaExtractor + MediaCodec bawaan Android.
 *
 * ANDROID-ONLY: kelas ini mengompilasi di JVM (android.jar berisi stub),
 * tapi TIDAK bisa jalan di JVM polos — MediaExtractor/MediaCodec butuh
 * runtime Android. Pemanggil ([WavUtil.convertToWav44100]) harus
 * memperlakukan null sebagai "decode gagal" dan menandai segmen FAILED
 * dengan pesan jelas, bukan crash. Diuji di HP (acceptance v1.6.0).
 */
object Mp3Decoder {

    /** Batas iterasi decode — pengaman supaya file rusak tidak loop selamanya. */
    private const val MAX_ITERATIONS = 20_000

    /** Hasil decode: sample rate sumber + sampel PCM16 mono. */
    data class DecodedPcm(val sampleRate: Int, val samples: ShortArray)

    /** Decode file MP3 -> [DecodedPcm], atau null bila gagal / bukan audio. */
    fun decodeToPcm16(file: File): DecodedPcm? {
        if (!file.isFile || file.length() <= 0) return null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var trackIndex = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    trackFormat = fmt
                    break
                }
            }
            if (trackIndex < 0 || trackFormat == null) return null
            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.selectTrack(trackIndex)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(trackFormat, null, null, 0)
            codec.start()

            var sampleRate = if (trackFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                -1
            }
            var channels = if (trackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                1
            }
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            val pcmOut = ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputEos = false
            var outputEos = false
            var iterations = 0
            while (!outputEos && iterations++ < MAX_ITERATIONS) {
                if (!inputEos) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)
                        if (inBuf != null) {
                            val read = extractor.readSampleData(inBuf, 0)
                            if (read < 0) {
                                codec.queueInputBuffer(
                                    inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputEos = true
                            } else {
                                codec.queueInputBuffer(
                                    inIndex, 0, read,
                                    extractor.sampleTime, 0
                                )
                                extractor.advance()
                            }
                        } else {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEos = true
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIndex)
                        if (outBuf != null && bufferInfo.size > 0) {
                            val chunk = ByteArray(bufferInfo.size)
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            outBuf.get(chunk)
                            pcmOut.write(chunk)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputEos = true
                        }
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = codec.outputFormat
                        if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = fmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (inputEos) {
                            // Tidak ada output lagi dan input sudah habis — selesai.
                            outputEos = true
                        }
                    }
                }
            }
            if (iterations >= MAX_ITERATIONS) return null
            if (sampleRate <= 0) return null
            val raw = pcmOut.toByteArray()
            if (raw.isEmpty()) return null
            val samples = when (pcmEncoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> {
                    // Float32 LE -> PCM16 (jarang untuk MP3, tapi ditangani).
                    val count = raw.size / 4
                    val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                    ShortArray(count) { (bb.float.coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
                }
                AudioFormat.ENCODING_PCM_8BIT -> {
                    ShortArray(raw.size) { (((raw[it].toInt() and 0xFF) - 128) * 256).toShort() }
                }
                else -> {
                    // PCM16 LE (jalur normal MP3).
                    val count = raw.size / 2
                    val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                    ShortArray(count) { bb.short }
                }
            }
            val mono = if (channels > 1) {
                val frames = samples.size / channels
                ShortArray(frames) { i ->
                    var sum = 0
                    for (c in 0 until channels) sum += samples[i * channels + c].toInt()
                    (sum / channels).toShort()
                }
            } else {
                samples
            }
            if (mono.isEmpty()) return null
            return DecodedPcm(sampleRate, mono)
        } catch (e: Exception) {
            return null
        } finally {
            try { codec?.stop() } catch (e: Exception) { /* abaikan */ }
            try { codec?.release() } catch (e: Exception) { /* abaikan */ }
            try { extractor.release() } catch (e: Exception) { /* abaikan */ }
        }
    }
}
