package com.custom.astrion.config

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Tiny setup web server — the app's equivalent of the stock HaRemote pairing
 * server (which listens on :8080 and takes {ip, token} at /submit).
 *
 * Browse to http://<remote-ip>:8099 from any machine on the LAN, paste the HA
 * URL + a long-lived token, and it's saved into app-private SharedPreferences
 * (see ConnectionConfig). That keeps credentials out of the APK *and* out of
 * world-readable /sdcard, and means provisioning needs no adb.
 *
 * Hand-rolled on ServerSocket rather than pulling in a HTTP library: this is one
 * form and one POST, and the APK is 1.3 MB on a 1 GB device.
 *
 * The server only runs while setup is open (started when no credentials exist,
 * or on demand from the info panel) and stops itself once creds are saved, so
 * it isn't a permanently open write endpoint on the LAN.
 */
class ConfigServer(
    private val port: Int = 8099,
    private val onSaved: (ConnectionConfig.Connection) -> Unit,
) {
    private var server: ServerSocket? = null
    @Volatile private var running = false

    val url: String get() = "http://${localIp() ?: "?"}:$port"

    fun start() {
        if (running) return
        running = true
        thread(name = "astrion-config-server", isDaemon = true) {
            try {
                ServerSocket(port).also { server = it }.use { ss ->
                    while (running) {
                        val sock = try { ss.accept() } catch (e: Exception) { break }
                        sock.use { handle(it.getInputStream().let(::InputStreamReader).let(::BufferedReader), it.getOutputStream()) }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "config server stopped: ${e.message}")
            } finally {
                running = false
            }
        }
        Log.i(TAG, "setup server listening on $url")
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
    }

    private fun handle(reader: BufferedReader, out: OutputStream) {
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(" ")
        val method = parts.getOrNull(0).orEmpty()
        val path = parts.getOrNull(1).orEmpty()

        // Read headers to find the body length.
        var contentLength = 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }

        if (method == "POST" && path.startsWith("/submit")) {
            val body = CharArray(contentLength).let { buf ->
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read.coerceAtLeast(0))
            }
            val form = parseForm(body)
            val url = form["url"]?.trim().orEmpty().removeSuffix("/")
            val token = form["token"]?.trim().orEmpty()
            if (url.isNotBlank() && token.isNotBlank()) {
                onSaved(ConnectionConfig.Connection(url, token))
                respond(out, page(saved = true, url = url))
                return
            }
            respond(out, page(error = "Both fields are required."))
            return
        }
        respond(out, page())
    }

    private fun parseForm(body: String): Map<String, String> =
        body.split('&').mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i <= 0) return@mapNotNull null
            val k = dec(pair.substring(0, i))
            val v = dec(pair.substring(i + 1))
            k to v
        }.toMap()

    private fun dec(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun respond(out: OutputStream, html: String) {
        val bytes = html.toByteArray()
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun page(saved: Boolean = false, url: String = "", error: String = ""): String = """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Astrion Custom setup</title>
        <style>
          body{font-family:system-ui,sans-serif;background:#0E2229;color:#E6F0F1;margin:0;padding:24px}
          .card{max-width:520px;margin:0 auto;background:#16303A;border-radius:16px;padding:22px}
          h1{font-size:20px;margin:0 0 4px} p{color:#93AFB6;font-size:14px;margin:0 0 18px}
          label{display:block;font-size:13px;color:#93AFB6;margin:14px 0 6px}
          input{width:100%;box-sizing:border-box;padding:12px;border-radius:10px;border:1px solid #2A4954;
                background:#0E2229;color:#F1F4FA;font-size:15px}
          button{margin-top:18px;width:100%;padding:14px;border:0;border-radius:12px;background:#2E7D95;
                 color:#fff;font-size:16px;font-weight:600}
          .ok{background:#1E4D3A;padding:12px;border-radius:10px;margin-bottom:14px}
          .err{background:#5A2A2A;padding:12px;border-radius:10px;margin-bottom:14px}
        </style></head><body><div class="card">
        <h1>Astrion Custom</h1>
        <p>Home Assistant connection for this remote.</p>
        ${if (saved) "<div class=\"ok\">Saved — connecting to $url. You can close this page.</div>" else ""}
        ${if (error.isNotBlank()) "<div class=\"err\">$error</div>" else ""}
        <form method="POST" action="/submit">
          <label>Home Assistant URL</label>
          <input name="url" placeholder="http://homeassistant.local:8123" autocapitalize="off" autocorrect="off">
          <label>Long-lived access token</label>
          <input name="token" placeholder="eyJhbGciOiJIUzI1NiIsInR5cCI6..." autocapitalize="off" autocorrect="off">
          <button type="submit">Save</button>
        </form>
        </div></body></html>
    """.trimIndent()

    private companion object {
        const val TAG = "AstrionConfigServer"

        /** First non-loopback IPv4 — what to browse to from another machine. */
        fun localIp(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull()?.hostAddress
        }.getOrNull()
    }
}
