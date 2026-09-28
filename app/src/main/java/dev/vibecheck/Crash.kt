package dev.vibecheck

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Message
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ThreadFactory

/**
 * A net under the service, and a note of what went wrong for Tools → Diagnostics.
 *
 * An exception on any thread used to end the process: the bubble vanished, then Android started
 * the service again and the card came back as if new. Now a worker thread that throws is logged
 * and replaced, and so is a task posted to the main thread through [mainHandler]; the service
 * carries on. What still gets through (a crash while drawing) is written down before the process
 * goes, so it can be read afterwards.
 */
object Crash {
    @Volatile private var file: File? = null
    @Volatile private var version = ""

    /** Once per process: where crashes are written, and the handler that writes them. */
    @Synchronized fun install(ctx: Context) {
        if (file != null) return
        file = File(ctx.filesDir, "crash.txt")
        version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { write("crash", t.name, e) }
            before?.uncaughtException(t, e)
        }
    }

    /** Written down, and the caller carries on. */
    fun caught(where: String, e: Throwable) {
        Diag.lastError = "$where: ${e.javaClass.simpleName}: ${e.message}"
        Diag.log(Diag.lastError)
        runCatching { write("caught", where, e) }
    }

    /** What went wrong last, with where and when, or null. */
    fun last(): String? = runCatching { file?.takeIf { it.exists() }?.readText() }.getOrNull()?.takeIf { it.isNotBlank() }

    fun clear() {
        runCatching { file?.delete() }
    }

    private fun write(kind: String, where: String, e: Throwable) {
        val f = file ?: return
        val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
        f.writeText("$time $kind in $where ($version)\n" + trace.lines().take(40).joinToString("\n"))
    }

    /** Threads for an executor: a task that throws is logged, and the process goes on. */
    fun threads(name: String): ThreadFactory = ThreadFactory { r ->
        Thread(r, name).apply { setUncaughtExceptionHandler { t, e -> caught(t.name, e) } }
    }

    /** The main thread's handler, logging what a posted task throws instead of ending the process. */
    fun mainHandler(): Handler = object : Handler(Looper.getMainLooper()) {
        override fun dispatchMessage(msg: Message) {
            try {
                super.dispatchMessage(msg)
            } catch (e: Exception) {
                caught("main", e)
            }
        }
    }

    /** [block], with what it throws logged rather than ending the process: for callbacks from the system. */
    inline fun guard(where: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            caught(where, e)
        }
    }
}
