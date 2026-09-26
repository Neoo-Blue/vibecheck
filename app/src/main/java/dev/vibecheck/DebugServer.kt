package dev.vibecheck

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
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
 * token that the app generates on first run, and only callers on the local network are
 * answered. Off by default.
 *
 * [token] is read on every request, so rotating it in settings locks the old URL out at once
 * instead of at the next restart of the service.
 */
class DebugServer(private val token: () -> String, private val host: Host) {

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
    @Volatile private var stopped = false

    @Synchronized fun start(port: Int = PORT): Boolean {
        if (running || stopped || token().isBlank()) return false
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

    /** Final: a stopped server is not started again (a new one is made instead). */
    @Synchronized fun stop() {
        stopped = true
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
        // LAN only, as promised: the socket listens on every interface, and on mobile data the
        // phone may hold a public IPv6 address or share a carrier's 10.x network with strangers.
        if (!onLan(sock.inetAddress)) return
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

        if (!constantTimeEquals(supplied, token())) {
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

    /**
     * The caller is this phone, or sits inside the subnet of one of its non-cellular interfaces
     * (Wi-Fi, Ethernet, hotspot, USB tethering). A private-looking address alone is not enough:
     * carriers hand out 10.x addresses too.
     */
    private fun onLan(remote: InetAddress?): Boolean {
        val r = unmapped(remote ?: return false)
        if (r.isLoopbackAddress) return true
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList().any { nif ->
                nif.isUp && !nif.isLoopback && !cellular(nif.name) &&
                    nif.interfaceAddresses.any { ia -> sameSubnet(ia.address, r, ia.networkPrefixLength.toInt()) }
            }
        }.getOrDefault(false)
    }

    /** An IPv4 caller on a dual-stack socket arrives as ::ffff:a.b.c.d. */
    private fun unmapped(a: InetAddress): InetAddress {
        val b = a.address
        val mapped = a is Inet6Address && b.size == 16 && (0 until 10).all { b[it].toInt() == 0 } &&
            b[10].toInt() == -1 && b[11].toInt() == -1
        return if (mapped) InetAddress.getByAddress(b.copyOfRange(12, 16)) else a
    }

    private fun cellular(name: String) =
        listOf("rmnet", "ccmni", "pdp", "clat", "v4-", "seth", "ppp", "dummy", "ifb").any { name.startsWith(it) }

    private fun sameSubnet(local: InetAddress?, remote: InetAddress, prefix: Int): Boolean {
        val x = local?.address ?: return false
        val y = remote.address
        if (x.size != y.size) return false
        // Some ROMs report nonsense prefixes; fall back to the usual home-network sizes.
        var bits = if (prefix in 1..x.size * 8) prefix else if (x.size == 4) 24 else 64
        for (i in x.indices) {
            if (bits <= 0) break
            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((x[i].toInt() and mask) != (y[i].toInt() and mask)) return false
            bits -= 8
        }
        return true
    }

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
