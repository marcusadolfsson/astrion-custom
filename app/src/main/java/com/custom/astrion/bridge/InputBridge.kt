package com.custom.astrion.bridge

import java.io.FileInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Reads hardware keys straight off the kernel and serves them to the app.
 *
 * WHY THIS EXISTS. An ordinary Android app never sees the keypress that wakes
 * the screen: PhoneWindowManager consumes it to turn the display on, and only
 * the SECOND press is dispatched. That is a policy in the input stack, not a
 * hardware limit -- the keycode is there the whole time, and reading the evdev
 * device directly bypasses the policy entirely.
 *
 * WHY IT CANNOT LIVE IN THE APP. /dev/input/event1 is `root:input` mode 0660
 * with SELinux label `input_device`, so opening it needs group 1004 (`input`)
 * AND a permitted domain. An app is `untrusted_app` with none of that, and
 * platform-signing would not help: `system_app` is not in group 1004 either.
 * Nor can an app escalate -- `su` refuses callers that are not already root or
 * shell. So the reader has to be started from a shell, and this class is run as
 * a standalone process:
 *
 *   CLASSPATH=<apk> app_process /system/bin com.custom.astrion.bridge.InputBridge
 *
 * which is the same trick Shizuku and Key Mapper use. Being started over adb is
 * inherent, not a shortcut: nothing on the device can grant these privileges to
 * itself, so the bridge does not survive a reboot.
 *
 * PROTOCOL. One line per key edge, so the app side needs no parser:
 *
 *   KEY <code> <1=down|0=up|2=repeat>
 *
 * The app connects as a client to 127.0.0.1; the bridge listens. That direction
 * matters -- the bridge is the thing that may be missing, and a client that
 * fails to connect is easier to reason about than a server waiting for a peer
 * that never starts.
 */
object InputBridge {

    private const val PORT = 8098
    private const val EV_KEY = 1

    /** 32-bit ARM: timeval is 2x4 bytes, then u16 type, u16 code, s32 value. */
    private const val EVENT_SIZE = 16

    /** How often to look for packages that should not be running. */
    private const val WATCHDOG_MS = 60_000L

    private val clients = mutableListOf<Socket>()
    private val lock = Object()

    /**
     * How many times the OEM launcher has been force-stopped, and when last.
     *
     * Recorded because "does it keep coming back, and how often" is a question
     * about a RATE, and the only honest way to answer it is a running count --
     * spot checks of `ps` say nothing about what happened overnight. Written to
     * a file the app reads and republishes to Home Assistant, where it can be
     * charted next to the battery it explains.
     */
    private var stops = 0
    private var lastStopMs = 0L

    private val stopsFile = java.io.File("/data/user_de/0/com.custom.astrion/stock-stops")

    /**
     * Survive a bridge restart. The bridge is restarted by hand after every
     * install, so a counter that reset each time would only ever measure the
     * current adb session -- useless for the question being asked.
     */
    private fun loadStops() {
        runCatching {
            val parts = stopsFile.readText().trim().split(' ')
            stops = parts[0].toInt()
            lastStopMs = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        }
    }

    private fun recordStops() {
        lastStopMs = System.currentTimeMillis()
        runCatching {
            stopsFile.writeText("$stops $lastStopMs")
            // The bridge runs as root/shell and the app as its own uid, so
            // without this the app cannot read what we just wrote.
            stopsFile.setReadable(true, false)
        }.onFailure { log("could not record stop count: ${it.message}") }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val devices = args.filter { it.startsWith("/dev/input/") }
            .ifEmpty { listOf("/dev/input/event1") }
        val stopPackages = args.filter { it.startsWith("--stop=") }.map { it.removePrefix("--stop=") }

        log("starting; devices=$devices port=$PORT stop=$stopPackages")

        devices.forEach { path -> thread(isDaemon = true) { readDevice(path) } }
        if (stopPackages.isNotEmpty()) {
            loadStops()
            log("watchdog armed; $stops stop(s) recorded so far")
            thread(isDaemon = true) { watchdog(stopPackages) }
        }

        val server = ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))
        log("listening on 127.0.0.1:$PORT")
        while (true) {
            val socket = runCatching { server.accept() }.getOrNull() ?: continue
            socket.tcpNoDelay = true
            synchronized(lock) { clients += socket }
            log("client connected (${clients.size} total)")
        }
    }

    /**
     * Kill packages that should not be running, repeatedly.
     *
     * The OEM launcher holds a PARTIAL_WAKE_LOCK for as long as it lives, so the
     * device never deep-sleeps while it is up. Making our app the preferred home
     * stops it starting AT BOOT, but the firmware relaunches it later by paths we
     * do not control -- one observed instance held the lock for 23 hours before
     * anyone noticed. So it has to be killed repeatedly, not once.
     *
     * This belongs in the bridge because the bridge is the only part of this
     * system with the privilege to do it: `am force-stop` needs FORCE_STOP_PACKAGES,
     * which shell has and an app never will.
     *
     * force-stop, deliberately, NOT `pm disable`: disabling the OEM launcher
     * bricks this hardware into a bootloop that safe mode, recovery and factory
     * reset cannot reach. A force-stop is transient and the package stays enabled
     * as a working fallback launcher.
     */
    private fun watchdog(packages: List<String>) {
        // Read fresh each tick: the app toggles this file from its settings
        // sheet, and a file works whether or not the socket is connected.
        val gate = java.io.File("/data/user_de/0/com.custom.astrion/allow-stock")
        while (true) {
            for (pkg in packages) {
                if (gate.exists()) {
                    Thread.sleep(WATCHDOG_MS)
                    continue
                }
                if (isRunning(pkg)) {
                    runCatching {
                        Runtime.getRuntime().exec(arrayOf("am", "force-stop", pkg)).waitFor()
                        stops++
                        recordStops()
                        log("force-stopped $pkg (#$stops)")
                    }.onFailure { log("force-stop $pkg failed: ${it.message}") }
                }
            }
            Thread.sleep(WATCHDOG_MS)
        }
    }

    /** Scan /proc rather than shelling out to ps -- cheaper, and no parsing. */
    private fun isRunning(pkg: String): Boolean =
        java.io.File("/proc").listFiles()
            ?.any { d ->
                d.name.toIntOrNull() != null &&
                    runCatching {
                        java.io.File(d, "cmdline").readText().trim('\u0000', ' ') == pkg
                    }.getOrDefault(false)
            } ?: false

    /**
     * One thread per device, blocking on read(). Kept deliberately dumb: no
     * filtering beyond EV_KEY, no debounce, no mapping. Whatever policy the app
     * wants belongs in the app, where it can be changed without a shell.
     */
    private fun readDevice(path: String) {
        val buf = ByteArray(EVENT_SIZE)
        while (true) {
            try {
                FileInputStream(path).use { input ->
                    log("reading $path")
                    while (true) {
                        var read = 0
                        while (read < EVENT_SIZE) {
                            val n = input.read(buf, read, EVENT_SIZE - read)
                            if (n < 0) return@use  // device went away; reopen below
                            read += n
                        }
                        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
                        bb.position(8)                       // skip timeval
                        val type = bb.short.toInt() and 0xFFFF
                        val code = bb.short.toInt() and 0xFFFF
                        val value = bb.int
                        if (type == EV_KEY) broadcast("KEY $code $value")
                    }
                }
            } catch (e: Exception) {
                log("read $path failed: ${e.message}")
            }
            // A failed open is usually transient (device asleep, permissions in
            // flux). Retry rather than exit -- exiting would need another adb
            // session to recover, which is the expensive thing here.
            Thread.sleep(1000)
        }
    }

    private fun broadcast(line: String) {
        synchronized(lock) {
            val dead = mutableListOf<Socket>()
            clients.forEach { s ->
                try {
                    s.getOutputStream().apply { write((line + "\n").toByteArray()); flush() }
                } catch (e: Exception) {
                    dead += s
                }
            }
            dead.forEach { runCatching { it.close() }; clients.remove(it) }
            if (dead.isNotEmpty()) log("dropped ${dead.size} dead client(s)")
        }
    }

    private fun log(msg: String) {
        // stdout so `adb shell` shows it live while starting the bridge by hand.
        println("[astrion-bridge] $msg")
    }
}
