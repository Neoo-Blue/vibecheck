package dev.vibecheck

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Tiny HTTP server so the running app can be inspected from a laptop on the same Wi-Fi,
 * without adb and without stopping anything.
 *
 *   curl "http://<phone-ip>:8848/status?t=<token>"
 *
 * Everything it serves is sensitive (chat text, judgments), so every route requires the
 * token that the app generates on first run. Off by default.
 */
class DebugServer(private val token: String, private val host: Host) {

    interface Host {
        fun status(): String
        fun tree(): String
        fun windowList(): String
        fun screenshotProbe(): String
        fun lastExchange(): String
        fun learner(): String
        fun resetLearner(): String
        fun rescan(): String
    }

    private var socket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(2)
    @Volatile private var running = false

    fun start(port: Int = PORT): Boolean {
        if (running || token.isBlank()) return false
        return try {
            socket = ServerSocket(port)
            running = true
            Thread({ loop() }, "jev-debug").apply { isDaemon = true }.start()
            Diag.log("debug server listening on :$port")
            true
        } catch (e: Exception) {
            Diag.log("debug server failed: ${e.message}")
            false
        }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        pool.shutdownNow()
    }

    private fun loop() {
        while (running) {
            val client = try { socket?.accept() ?: break } catch (e: Exception) { break }
            runCatching { pool.execute { handle(client) } }.onFailure { client.close() }
        }
    }

    private fun handle(client: Socket) = client.use { sock ->
        sock.soTimeout = 5000
        val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
        val requestLine = reader.readLine() ?: return
        var auth = ""
        while (true) {                       // drain headers, keep Authorization
            val h = reader.readLine() ?: break
            if (h.isEmpty()) break
            if (h.startsWith("Authorization:", true)) auth = h.substringAfter(':').trim().removePrefix("Bearer ").trim()
        }

        val target = requestLine.split(' ').getOrNull(1) ?: "/"
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "")
        val supplied = auth.ifBlank { param(query, "t") }

        if (!constantTimeEquals(supplied, token)) {
            respond(sock.getOutputStream(), 401, "unauthorized\n")
            return
        }

        val body = when (path) {
            "/", "/status" -> host.status()
            "/log" -> Diag.dump()
            "/tree" -> host.tree()
            "/windows" -> host.windowList()
            "/shot" -> host.screenshotProbe()
            "/last" -> host.lastExchange()
            "/learn" -> host.learner()
            "/learn/reset" -> host.resetLearner()
            "/rescan" -> host.rescan()
            else -> null
        }
        if (body == null) respond(sock.getOutputStream(), 404, "routes: /status /log /tree /windows /shot /last /learn /learn/reset /rescan\n")
        else respond(sock.getOutputStream(), 200, body + "\n")
    }

    private fun param(query: String, name: String): String =
        query.split('&').firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            ?: ""

    private fun constantTimeEquals(a: String, b: String): Boolean =
        a.isNotEmpty() && MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    private fun respond(out: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code ${if (code == 200) "OK" else "ERR"}\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    companion object { const val PORT = 8848 }
}
