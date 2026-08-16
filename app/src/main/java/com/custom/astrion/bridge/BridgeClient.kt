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
    /**
     * Keys that should not light the screen. A press arriving while the display
     * is dark runs its action and the screen goes straight back down.
     *
     * Volume and transport are the cases that matter: adjusting the volume
     * mid-film should not put a 3" screen in your lap at full brightness for the
     * whole timeout. Anything NOT in this set behaves normally -- some presses
     * genuinely want the screen, and guessing wrong in that direction is worse.
     *
     * A function, not a Set, because the set now depends on the dashboard, which
     * is re-read on every onResume: this client is constructed once and would
     * otherwise hold whatever was configured at first launch, so a Sync that
     * changed a `quiet` flag would appear to do nothing until a restart.
     */
    private val quietKeys: () -> Set<HardwareKey> = { emptySet() },
    /** Called after a quiet-key action so the caller can re-sleep the display. */
    private val onQuietHandled: () -> Unit = {},
    /** True while the display is off; consulted at the moment a key arrives. */
    private val isDisplayOff: () -> Boolean = { false },
    /** Invoked on the main thread for each key DOWN the bridge reports. */
    private val onKey: (HardwareKey) -> Unit,
) {
    @Volatile
    var connected: Boolean = false
        private set

    /**
     * Held so [stop] can close it. Cancelling the scope is NOT enough: the read
     * loop is blocked in forEachLine, which no coroutine cancellation can
     * interrupt, so the socket stays open and the bridge keeps counting a client
     * that will never act again. Across an activity recreation that accumulates
     * -- observed as two connected clients for one app.
     */
    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var started = false

    fun start() {
        // Idempotent: called from onCreate, which can run more than once for a
        // single process.
        if (started) return
        started = true
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    Socket().use { sock ->
                        socket = sock
                        sock.connect(InetSocketAddress(host, port), 1500)
                        sock.tcpNoDelay = true
                        connected = true
                        Log.i(TAG, "connected to input bridge on $host:$port")
                        sock.getInputStream().bufferedReader().forEachLine { line ->
                            parse(line)?.let { key ->
                                // Sampled HERE, not on the main thread: by the
                                // time a posted block runs, Android has already
                                // woken the display for its own copy of this
                                // event, and the answer would always be "on".
                                val wasDark = isDisplayOff()
                                scope.launch(Dispatchers.Main) {
                                    // ONLY act on presses Android will not
                                    // deliver itself. With the screen awake it
                                    // dispatches to dispatchKeyEvent as normal,
                                    // and this reader sees the SAME press off
                                    // /dev/input -- so acting here too ran every
                                    // action twice. The bridge exists purely to
                                    // cover the press PhoneWindowManager eats to
                                    // wake the display; anything else is a
                                    // duplicate.
                                    if (!wasDark) return@launch
                                    // Decide quiet BEFORE running the action.
                                    // The quiet set depends on the page being
                                    // shown, and an action can CHANGE the page --
                                    // asking afterwards judges the press by the
                                    // page it led to rather than the one it
                                    // arrived on.
                                    val quiet = key in quietKeys()
                                    onKey(key)
                                    if (quiet) onQuietHandled()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Expected whenever the bridge is not running, which is most
                    // of the time. Debug level on purpose: this is not a fault.
                    Log.d(TAG, "bridge unavailable: ${e.message}")
                } finally {
                    connected = false
                    socket = null
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

    /** Close the socket so the bridge drops us; the blocked read then unwinds. */
    fun stop() {
        started = false
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        private const val TAG = "AstrionBridge"
        private const val RETRY_MS = 3000L
    }
}
