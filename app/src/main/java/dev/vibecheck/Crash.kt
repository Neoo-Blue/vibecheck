package dev.vibecheck

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
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
 * and replaced, and whatever throws on the main thread is logged while its loop goes on (see
 * [loopGuarded]); the service carries on. What still ends the process (a failure while Android
 * starts a component, a crash in native code, "not responding", being killed for memory) is
 * written down, with Android's own account of it, and the app says so the next time it opens.
 */
object Crash {
    /** The last thing that went wrong, caught or not. */
    @Volatile private var file: File? = null
    /** The last time the process itself ended because of it, for the app's banner. */
    @Volatile private var exitFile: File? = null
    @Volatile private var version = ""

    /** Once per process: where crashes are written, and the handler that writes them. */
    @Synchronized fun install(ctx: Context) {
        if (file != null) return
        file = File(ctx.filesDir, "crash.txt")
        exitFile = File(ctx.filesDir, "exit.txt")
        version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { write("crash", t.name, e, fatal = true) }
            before?.uncaughtException(t, e)
        }
        runCatching { readExits(ctx) }
        Handler(Looper.getMainLooper()).post { loopGuarded() }
    }

    /**
     * The main thread's loop, run inside a net. Our handler covers what we post, but a touch on
     * the card, a frame being drawn, a view's own post or a system callback goes straight to the
     * loop, and one exception there ended the process: the bubble vanished and Android started
     * the service again. Now the loop logs it and goes on. Two kinds still end the process: a
     * failure while Android starts, stops or binds a component (the component would be left half
     * made), and the same failing again and again (whatever state it is in is broken for good).
     */
    private fun loopGuarded() {
        val recent = ArrayDeque<Long>()
        while (true) {
            try {
                Looper.loop()
                return   // the main loop never quits; if it did, so do we
            } catch (e: Throwable) {
                val now = SystemClock.uptimeMillis()
                recent.addLast(now)
                while (recent.isNotEmpty() && now - recent.first() > BURST_MS) recent.removeFirst()
                if (lifecycle(e) || recent.size > BURST) throw e
                caught("main loop", e)
            }
        }
    }

    private const val BURST = 10
    private const val BURST_MS = 10_000L

    /** Thrown while Android was creating, starting, binding or stopping an activity or service? */
    fun lifecycle(e: Throwable): Boolean {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth++ < 8) {
            // Only the frames of the message that failed: below them is the loop, and below that
            // the loop that runs this guard, whose frames say nothing about the failure.
            for (f in t.stackTrace) {
                if (f.className == "android.os.Looper") break
                if (f.className.startsWith("android.app.ActivityThread") || f.className.startsWith("android.app.servertransaction.")) return true
            }
            t = t.cause
        }
        return false
    }

    /**
     * Why the process ended the last times, as Android kept it (Android 11+). Our handler sees a
     * Java crash, but not the rest: a crash in native code, "not responding" (for which the main
     * thread's stack is kept), or being killed for memory. The newest one not seen before is
     * written down, so Tools → Diagnostics says why, not just that it closed.
     */
    private fun readExits(ctx: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return
        val sp = ctx.getSharedPreferences("crash", Context.MODE_PRIVATE)
        val seen = sp.getLong("exitseen", 0L)
        val exits = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 10).filter { it.timestamp > seen }
        if (exits.isEmpty()) return
        sp.edit().putLong("exitseen", exits.maxOf { it.timestamp }).apply()
        val bad = exits.filter { it.reason in BAD }.maxByOrNull { it.timestamp } ?: return
        val f = file ?: return
        val x = exitFile ?: return
        // A Java crash our handler already wrote down, with its stack: that is the better account.
        if (bad.reason == ApplicationExitInfo.REASON_CRASH && x.exists() && kotlin.math.abs(x.lastModified() - bad.timestamp) < 60_000) return
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(bad.timestamp))
        val head = "$time ${reasonName(bad.reason)} (${bad.description.orEmpty()}) memory ${bad.pss / 1024} MB, " +
            "importance ${bad.importance} ($version)"
        // For "not responding", the main thread's stack from the trace Android kept.
        val trace = if (bad.reason == ApplicationExitInfo.REASON_ANR) runCatching {
            bad.traceInputStream?.bufferedReader()?.use { r ->
                val lines = r.readLines()
                val main = lines.indexOfFirst { it.startsWith("\"main\"") }
                if (main < 0) lines.take(30) else lines.drop(main).take(30)
            }
        }.getOrNull().orEmpty() else emptyList()
        val text = (listOf(head) + trace).joinToString("\n")
        f.writeText(text)
        x.writeText(text)
    }

    /** The ways out that mean something went wrong, not the user closing it or an update. */
    private val BAD = setOf(
        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE, ApplicationExitInfo.REASON_SIGNALED,
    )

    private fun reasonName(r: Int): String = when (r) {
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for low memory"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "killed for using too much"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
        else -> "exit $r"
    }

    /** How the process last ended when it should not have, or null: what the app's banner shows. */
    fun lastExit(): String? = runCatching { exitFile?.takeIf { it.exists() }?.readText() }.getOrNull()?.takeIf { it.isNotBlank() }

    /** When that was written down, to tell whether it has been seen. */
    fun lastExitAt(): Long = runCatching { exitFile?.takeIf { it.exists() }?.lastModified() }.getOrNull() ?: 0L

    /** [lastExit] when it is newer than the last one dismissed in the app, else null. */
    fun unseenExit(ctx: Context): String? {
        val at = lastExitAt()
        if (at == 0L) return null
        val seen = ctx.getSharedPreferences("crash", Context.MODE_PRIVATE).getLong("exitack", 0L)
        return if (at > seen) lastExit() else null
    }

    fun dismissExit(ctx: Context) {
        ctx.getSharedPreferences("crash", Context.MODE_PRIVATE).edit().putLong("exitack", lastExitAt()).apply()
    }

    /** [record] with what the phone is, for sending on. */
    fun report(record: String): String =
        "Vibecheck $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n$record"

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
        runCatching { exitFile?.delete() }
    }

    private fun write(kind: String, where: String, e: Throwable, fatal: Boolean = false) {
        val f = file ?: return
        val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
        val text = "$time $kind in $where ($version)\n" + trace.lines().take(40).joinToString("\n")
        f.writeText(text)
        if (fatal) exitFile?.writeText(text)
    }

    /** Threads for an executor: a task that throws is logged, and the process goes on. */
    fun threads(name: String): ThreadFactory = ThreadFactory { r ->
        Thread(r, name).apply { setUncaughtExceptionHandler { t, e -> caught(t.name, e) } }
    }

    /**
     * The main thread's handler, logging what a posted task throws instead of ending the process:
     * errors too (a regex that overflows the stack, a picture too big for memory), which are
     * better lost with the one screen they were about than with the whole service.
     */
    fun mainHandler(): Handler = object : Handler(Looper.getMainLooper()) {
        override fun dispatchMessage(msg: Message) {
            try {
                super.dispatchMessage(msg)
            } catch (e: Throwable) {
                caught("main", e)
            }
        }
    }

    /** [block], with what it throws logged rather than ending the process: for callbacks from the system. */
    inline fun guard(where: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            caught(where, e)
        }
    }
}
