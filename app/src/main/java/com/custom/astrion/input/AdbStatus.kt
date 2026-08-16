package com.custom.astrion.input

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Is network adb (adb over TCP) currently listening?
 *
 * Worth surfacing because it is the one piece of this device's state that
 * silently reverts: `adb tcpip 5555` sets only the RUNTIME property, and this
 * build's adbd ignores `persist.adb.tcp.port`, so every reboot drops it and the
 * next deploy needs a USB cable. Showing it in the panel turns "why is adb
 * refusing" into something you can see before you go looking for the cable.
 *
 * Two signals, because either alone lies:
 *  - `service.adb.tcp.port` says what adbd was ASKED to do. It survives adbd
 *    dying, so a set port does not prove anything is listening.
 *  - a loopback connect says something IS listening right now, but not on which
 *    port unless we already know it.
 *
 * Read on a background thread: both a process exec and a socket connect are
 * blocking calls with no place on the main thread.
 */
object AdbStatus {

    /** Human-readable one-liner for the info panel. */
    fun describe(): String {
        val port = prop("service.adb.tcp.port").trim()
        if (port.isEmpty() || port == "-1") return "off"
        val listening = listening(port.toIntOrNull() ?: return "off")
        return if (listening) "on (port $port)" else "set to $port, not listening"
    }

    private fun prop(name: String): String = runCatching {
        val p = ProcessBuilder("getprop", name).redirectErrorStream(true).start()
        BufferedReader(InputStreamReader(p.inputStream)).use { it.readLine().orEmpty() }
            .also { p.waitFor() }
    }.getOrDefault("")

    private fun listening(port: Int): Boolean = runCatching {
        Socket().use { s ->
            // Loopback: we are asking whether adbd is up on THIS device, not
            // whether it is reachable across the network (a firewall or a
            // sleeping Wi-Fi radio would make that a different question).
            s.connect(InetSocketAddress("127.0.0.1", port), 400)
            true
        }
    }.getOrDefault(false)
}
