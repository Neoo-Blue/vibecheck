package dev.vibecheck

import android.content.Context
import java.io.File

/**
 * Where what is learned about me is kept, all in app-private storage: one log file per day,
 * the day write-ups, the notes about me per stretch of a kept history, and the profile of me.
 */
class MeStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("me", Context.MODE_PRIVATE)
    private val dir = File(ctx.filesDir, "days")

    private fun file(day: String) = File(dir, "$day.log")

    /** Messages seen live in [person]'s chat at [time], added to that day's log. */
    @Synchronized
    fun log(time: Long, person: String, lines: List<Pair<String, String>>) {
        if (lines.isEmpty()) return
        runCatching {
            dir.mkdirs()
            file(Me.day(time)).appendText(Me.encode(Me.clock(time), person, lines))
        }
    }

    /** Days with a log, newest first ("2026-09-27"). */
    fun days(): List<String> =
        dir.list()?.filter { it.endsWith(".log") }?.map { it.removeSuffix(".log") }?.sortedDescending() ?: emptyList()

    @Synchronized
    fun lines(day: String): List<Me.Line> =
        runCatching { file(day).takeIf { it.exists() }?.readText()?.let { Me.decode(it) } }.getOrNull() ?: emptyList()

    fun summary(day: String): String? = sp.getString("day:$day", null)?.takeIf { it.isNotBlank() }

    /** How many logged lines the write-up covered: a day that grew since is written up again. */
    fun summarizedLines(day: String): Int = sp.getInt("daylines:$day", 0)

    fun saveSummary(day: String, text: String, lines: Int) =
        sp.edit().putString("day:$day", text.trim()).putInt("daylines:$day", lines).apply()

    /** The newest [n] day write-ups, newest first. */
    fun summaries(n: Int): List<Pair<String, String>> = days().mapNotNull { d -> summary(d)?.let { d to it } }.take(n)

    var profile: String
        get() = sp.getString("profile", "") ?: ""
        set(v) = sp.edit().putString("profile", v).apply()

    /** When the profile of me was last written, or 0. */
    var learnedAt: Long
        get() = sp.getLong("learnedat", 0L)
        set(v) = sp.edit().putLong("learnedat", v).apply()

    /** Notes about me by the hash of the stretch they came from, so a stretch is paid for once. */
    fun notes(): Map<String, String> = Profile.loadNotes(sp.getString("notes", "") ?: "")

    fun saveNotes(notes: Map<String, String>) = sp.edit().putString("notes", Profile.saveNotes(notes)).apply()

    /** Only the newest [keep] days are kept, log and write-up. */
    @Synchronized
    fun prune(keep: Int = 90) {
        val old = days().drop(keep)
        if (old.isEmpty()) return
        val e = sp.edit()
        for (d in old) {
            runCatching { file(d).delete() }
            e.remove("day:$d").remove("daylines:$d")
        }
        e.apply()
    }

    /** Everything learned about me goes. */
    @Synchronized
    fun clear() {
        runCatching { dir.deleteRecursively() }
        sp.edit().clear().apply()
    }
}
