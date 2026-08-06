package com.custom.astrion.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.concurrent.thread

/**
 * Capture from the HA100's built-in microphone.
 *
 * The format is pinned to 16 kHz / mono / PCM16 on purpose: that is what Home
 * Assistant's Assist pipeline expects, and it is also exactly what HAP requires
 * for Siri audio to an Apple TV (spec §12.2 — Opus, 16 kHz, 20 ms, VBR; Opus
 * encodes from precisely this PCM). Capturing once in that format keeps both
 * sinks reachable with no resampling on a 1 GB MT6580.
 *
 * Frames go to `sink` as they arrive and the caller decides what they are — a
 * WAV on disk, a HA websocket, an Opus encoder. Capture ends on an explicit
 * [stop], on `maxMs`, or — when `endOnSilence` — after roughly `endSilenceMs`
 * of quiet once speech has been heard.
 *
 * That silence rule is not a nicety. **The HA100's VOICE key emits an instant
 * press+release, not a hold**, so hold-to-talk is impossible on this hardware
 * and the end of an utterance has to be inferred. Credit to
 * vvaters/astrion-ha-dashboard, which hit the same wall and whose thresholds
 * these match.
 */
class MicCapture(
    private val maxMs: Int = 10_000,
    private val endOnSilence: Boolean = true,
    /** Quiet needed AFTER speech before the utterance counts as finished. */
    private val endSilenceMs: Int = 1_200,
    /** Give up if the user never speaks at all. */
    private val noSpeechMs: Int = 4_000,
) {
    @Volatile private var capturing = false

    val isCapturing: Boolean get() = capturing

    fun stop() {
        capturing = false
    }

    /**
     * Starts capture on a background thread.
     *
     * @param sink called with (buffer, byteCount) per ~100 ms chunk. The buffer
     *   is REUSED between calls — copy it if you need to retain it.
     * @param onDone (totalBytes, peakRms); peakRms == 0 means silence, which on
     *   this device usually means the permission was denied rather than a quiet
     *   room.
     */
    fun start(sink: (ByteArray, Int) -> Unit, onDone: (Int, Int) -> Unit = { _, _ -> }) {
        if (capturing) return
        thread(name = "astrion-mic", isDaemon = true) {
            val (total, peak) = captureInto(sink)
            onDone(total, peak)
        }
    }

    /**
     * Capture on the CALLING thread, returning (totalBytes, peakRms).
     *
     * This is what the voice path uses: called from inside an OkHttp
     * RequestBody.writeTo, each chunk is written straight to the socket as the
     * microphone produces it. No queue, no buffering — audio is reaching Siri
     * (or Assist) while the user is still speaking, which is the difference
     * between a remote that feels instant and one that feels broken.
     */
    fun captureInto(sink: (ByteArray, Int) -> Unit): Pair<Int, Int> {
        if (capturing) return 0 to 0
        capturing = true
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, CHANNEL, ENCODING,
                maxOf(minBuf, CHUNK_BYTES * 2),
            )
        } catch (e: Exception) {
            Log.e(TAG, "microphone unavailable: ${e.message}")
            capturing = false
            return 0 to 0
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "microphone failed to initialize (RECORD_AUDIO not granted?)")
            rec.release()
            capturing = false
            return 0 to 0
        }

        var total = 0
        var peak = 0
        var elapsedMs = 0
        var speechHeard = false
        var silenceMs = 0
        try {
            rec.startRecording()
            Log.i(TAG, "capture started")
            val buf = ByteArray(CHUNK_BYTES) // ~100 ms
            while (capturing) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) {
                    if (n < 0) Log.w(TAG, "AudioRecord.read error: $n")
                    break
                }
                total += n
                sink(buf, n)

                elapsedMs += n / BYTES_PER_MS
                val level = rms(buf, n)
                if (level > peak) peak = level
                if (level > SPEECH_RMS) {
                    speechHeard = true
                    silenceMs = 0
                } else if (speechHeard) {
                    silenceMs += n / BYTES_PER_MS
                }
                if (elapsedMs >= maxMs) break
                if (endOnSilence) {
                    // Ended talking -> send it.
                    if (speechHeard && silenceMs >= endSilenceMs) {
                        Log.i(TAG, "auto-stop: silence after speech (${elapsedMs}ms)")
                        break
                    }
                    // Never started talking -> give up rather than holding the
                    // mic (and, on the Siri route, the SIRI button) for the
                    // full window on an accidental press.
                    if (!speechHeard && elapsedMs >= noSpeechMs) {
                        Log.i(TAG, "auto-stop: no speech within ${noSpeechMs}ms")
                        break
                    }
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            capturing = false
            Log.i(TAG, "capture ended: $total bytes (~${total / BYTES_PER_MS} ms), peak rms=$peak")
        }
        return total to peak
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** ~100 ms at 16 kHz mono PCM16. */
        const val CHUNK_BYTES = 3_200

        /** 16000 samples/s × 2 bytes ÷ 1000 ms. */
        const val BYTES_PER_MS = 32

        private const val SPEECH_RMS = 700
        private const val TAG = "AstrionMic"

        /** Root-mean-square level of a little-endian PCM16 buffer. */
        fun rms(buf: ByteArray, len: Int): Int {
            var sum = 0L
            var i = 0
            while (i + 1 < len) {
                val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
                sum += s.toLong() * s
                i += 2
            }
            val samples = len / 2
            return if (samples == 0) 0 else Math.sqrt(sum.toDouble() / samples).toInt()
        }
    }
}
