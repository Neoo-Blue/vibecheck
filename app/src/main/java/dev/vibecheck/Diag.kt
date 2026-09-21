package dev.vibecheck

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-memory ring buffer plus the last request/response, for the debug server to serve. */
object Diag {
    private const val MAX = 200
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lines = ArrayDeque<String>()

    @Volatile var lastRequest: String = ""
    @Volatile var lastResponse: String = ""
    @Volatile var lastError: String = ""
    @Volatile var lastScan: String = ""
    @Volatile var connected = false
    @Volatile var events = 0
    @Volatile var lastPackage: String = ""
    @Volatile var person: String = ""
    @Volatile var shotProbe: String = ""

    @Synchronized fun log(msg: String) {
        if (lines.size >= MAX) lines.removeFirst()
        lines.addLast("${fmt.format(Date())}  $msg")
    }

    @Synchronized fun dump(): String = lines.joinToString("\n")

    @Synchronized fun clear() = lines.clear()
}
