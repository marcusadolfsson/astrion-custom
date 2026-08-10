package com.custom.astrion.bridge

import android.util.Log
import com.custom.astrion.input.HardwareKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * App side of the input bridge: connects to [InputBridge] and turns raw kernel
 * scancodes into logical key presses.
 *
 * PURELY ADDITIVE, and that is the design constraint that matters. The bridge
 * has to be started from a shell over adb and cannot survive a reboot, so it is
 * absent more often than present. Nothing here may become load-bearing: when
 * the bridge is missing the app behaves exactly as it always has (Android
 * dispatches keys once the screen is awake), and when it is present the only
 * difference is that the press which WAKES the screen also does its job instead
 * of being swallowed.
 *
 * That is why this connects rather than listens, retries quietly forever, and
 * reports its state for display only -- a missing bridge is the normal case,
 * not an error worth interrupting anyone about.
 */
class BridgeClient(
    private val scope: CoroutineScope,
    private val host: String = "127.0.0.1",
    private val port: Int = 8098,
    /** Invoked on the main thread for each key DOWN the bridge reports. */
    private val onKey: (HardwareKey) -> Unit,
) {
    @Volatile
    var connected: Boolean = false
        private set

    fun start() {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(host, port), 1500)
                        socket.tcpNoDelay = true
                        connected = true
                        Log.i(TAG, "connected to input bridge on $host:$port")
                        socket.getInputStream().bufferedReader().forEachLine { line ->
                            parse(line)?.let { key ->
                                scope.launch(Dispatchers.Main) { onKey(key) }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Expected whenever the bridge is not running, which is most
                    // of the time. Debug level on purpose: this is not a fault.
                    Log.d(TAG, "bridge unavailable: ${e.message}")
                } finally {
                    connected = false
                }
                delay(RETRY_MS)
            }
        }
    }

    /**
     * `KEY <scancode> <1=down|0=up|2=repeat>`.
     *
     * Down edges only. Repeats are dropped deliberately: a held key on a remote
     * is far more often a thumb resting on it than a request to fire an action
     * twenty times, and the on-screen path does its own repeat handling for the
     * few keys that want it.
     */
    private fun parse(line: String): HardwareKey? {
        val parts = line.trim().split(' ')
        if (parts.size != 3 || parts[0] != "KEY") return null
        if (parts[2] != "1") return null
        val scan = parts[1].toIntOrNull() ?: return null
        val key = HardwareKey.fromScanCode(scan)
        if (key == HardwareKey.UNKNOWN) {
            Log.i(TAG, "unmapped scancode $scan (add it to HardwareKey.SCAN_MAP)")
            return null
        }
        return key
    }

    companion object {
        private const val TAG = "AstrionBridge"
        private const val RETRY_MS = 3000L
    }
}
