package com.custom.astrion.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * adb-triggered microphone check.
 *
 * Deliberately NOT a network endpoint. An always-listening "record the room"
 * HTTP route on a device that lives in the living room is a bad trade for a
 * diagnostic, so this needs adb — i.e. physical/developer access:
 *
 *     adb shell am broadcast -a com.custom.astrion.MIC_TEST --ei secs 5
 *     adb pull /sdcard/astrion/mic-test.wav
 *
 * Writes a 16 kHz mono PCM16 WAV so the capture can be listened to and measured
 * off-device. It runs the same [MicCapture] the real voice path will use, so a
 * good file here means the capture core itself is sound.
 */
class MicProbe : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (running) {
            Log.w(TAG, "probe already running")
            return
        }
        val secs = intent.getIntExtra("secs", 5).coerceIn(1, 30)
        val out = File(intent.getStringExtra("out") ?: DEFAULT_OUT)
        out.parentFile?.mkdirs()

        running = true
        Log.i(TAG, "recording ${secs}s -> ${out.absolutePath}")

        val raf = try {
            RandomAccessFile(out, "rw").apply {
                setLength(0)
                write(ByteArray(HEADER_BYTES)) // placeholder, patched in onDone
            }
        } catch (e: Exception) {
            Log.e(TAG, "cannot open ${out.absolutePath}: ${e.message}")
            running = false
            return
        }

        // endOnSilence=false: a probe should record the full window even in a
        // quiet room, otherwise "it stopped early" and "the mic is dead" look
        // identical.
        MicCapture(maxMs = secs * 1000, endOnSilence = false).start(
            sink = { buf, n -> runCatching { raf.write(buf, 0, n) } },
            onDone = { total, peak ->
                runCatching {
                    writeWavHeader(raf, total)
                    raf.close()
                }
                running = false
                Log.i(
                    TAG,
                    "probe done: $total bytes (~${total / MicCapture.BYTES_PER_MS} ms), " +
                        "peak rms=$peak -> ${out.absolutePath}" +
                        if (peak == 0) "  [SILENT — check RECORD_AUDIO]" else "",
                )
            },
        )
    }

    /** Canonical 44-byte PCM WAV header, patched once the data size is known. */
    private fun writeWavHeader(raf: RandomAccessFile, dataBytes: Int) {
        val byteRate = MicCapture.SAMPLE_RATE * CHANNELS * BITS / 8
        raf.seek(0)
        raf.writeBytes("RIFF")
        raf.writeInt(Integer.reverseBytes(36 + dataBytes))
        raf.writeBytes("WAVE")
        raf.writeBytes("fmt ")
        raf.writeInt(Integer.reverseBytes(16))                    // PCM chunk size
        raf.writeShort(java.lang.Short.reverseBytes(1).toInt())   // format = PCM
        raf.writeShort(java.lang.Short.reverseBytes(CHANNELS.toShort()).toInt())
        raf.writeInt(Integer.reverseBytes(MicCapture.SAMPLE_RATE))
        raf.writeInt(Integer.reverseBytes(byteRate))
        raf.writeShort(java.lang.Short.reverseBytes((CHANNELS * BITS / 8).toShort()).toInt())
        raf.writeShort(java.lang.Short.reverseBytes(BITS.toShort()).toInt())
        raf.writeBytes("data")
        raf.writeInt(Integer.reverseBytes(dataBytes))
    }

    companion object {
        const val ACTION = "com.custom.astrion.MIC_TEST"
        private const val DEFAULT_OUT = "/sdcard/astrion/mic-test.wav"
        private const val HEADER_BYTES = 44
        private const val CHANNELS = 1
        private const val BITS = 16
        private const val TAG = "AstrionMicProbe"

        @Volatile private var running = false
    }
}
