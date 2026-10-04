package dev.vibecheck

import java.util.concurrent.ConcurrentHashMap

/**
 * A profile being written, and how far it has got: for the card and for the person's page.
 *
 * A write runs for minutes (a note on each stretch of the history, three at a time, then the
 * profile from the notes), and the card used to say only 「还在整理上次读到的记录」: nobody could
 * tell a slow write from a stuck one, or whether to start again. Kept for the process: the service
 * writes it, the app reads it.
 */
object Learning {

    enum class Stage { NOTES, MERGE, FINAL }

    class Progress(val startedAt: Long, val read: Int?) {
        /** Messages kept, and their characters, once the history has been read back. */
        @Volatile var kept = 0
        @Volatile var chars = 0
        @Volatile var stage = Stage.NOTES
        @Volatile var done = 0
        @Volatile var total = 0
        /** Stretches already noted when this write began: they took no time now. */
        @Volatile var doneAtStart = -1
        /** The last sign of life: a stretch done, a step begun. */
        @Volatile var lastAt = startedAt
        @Volatile var stageAt = startedAt

        @Synchronized fun update(stage: Stage, done: Int, total: Int, now: Long = System.currentTimeMillis()) {
            if (stage != this.stage) stageAt = now
            this.stage = stage
            if (stage == Stage.NOTES) {
                if (doneAtStart < 0) doneAtStart = done
                this.done = maxOf(this.done, done)
                this.total = total
            }
            lastAt = now
        }
    }

    /** Writes under way, by person id. */
    val writing = ConcurrentHashMap<String, Progress>()

    /**
     * No sign of life for this long and the write is taken as stuck: Learn then starts it again.
     * One call can take three minutes and is tried three times, so less would call slow stuck.
     */
    const val STALL_MS = 10 * 60_000L

    /** The last step, one long answer: roughly how long it takes, for the estimate. */
    private const val FINAL_MS = 120_000L

    fun stalled(p: Progress, now: Long): Boolean = now - p.lastAt > STALL_MS

    /**
     * Is it time to write a learned person's profile again by itself (Prefs.keepUpDays)? [days]
     * since it was last written ([writtenAt]), at the least, and never by itself when 0; and only
     * once their kept history ([kept] messages) holds [KEEP_UP_MIN] more than that write was made
     * from ([writtenFrom]): no new messages, no call. A day is taken a little short, so a daily
     * update keeps to the time of day you chat. [triedAt] is when one was last started by itself:
     * a write that did not get through is not started again for [KEEP_UP_RETRY_MS].
     */
    fun keepUpDue(days: Int, writtenAt: Long, kept: Int, writtenFrom: Int, triedAt: Long, now: Long): Boolean =
        days > 0 && kept - writtenFrom >= KEEP_UP_MIN &&
            now - writtenAt >= days * DAY_MS - DAY_SLACK_MS && now - triedAt >= KEEP_UP_RETRY_MS

    /** New messages it takes before a profile is written again by itself. */
    const val KEEP_UP_MIN = 30

    private const val DAY_MS = 86_400_000L
    private const val DAY_SLACK_MS = 3 * 3_600_000L
    private const val KEEP_UP_RETRY_MS = 6 * 3_600_000L

    /** "████░░░░░░" for [done] of [total]. */
    fun bar(done: Int, total: Int, cells: Int = 10): String {
        val full = if (total <= 0) 0 else (done.coerceIn(0, total) * cells / total)
        return "█".repeat(full) + "░".repeat(cells - full)
    }

    /** "40 秒", "3 分钟", "1 小时 5 分钟". */
    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> L.t("$s 秒", "${s}s")
            s < 3600 -> L.t("${s / 60} 分钟", "${s / 60} min")
            else -> L.t("${s / 3600} 小时 ${s % 3600 / 60} 分钟", "${s / 3600} h ${s % 3600 / 60} min")
        }
    }

    /** "3521 字", "约 4.1 万字", "~41k characters". */
    fun amount(chars: Int): String =
        if (L.en) (if (chars < 1000) "$chars characters" else "~${(chars + 500) / 1000}k characters")
        else if (chars < 10_000) "$chars 字" else "约 ${"%.1f".format(chars / 10_000.0)} 万字"

    /** What the card and the person's page say about a write: the step, how far, how long, and whether it still moves. */
    fun lines(p: Progress, now: Long): List<String> {
        val out = ArrayList<String>()
        out += when (p.stage) {
            Stage.NOTES -> L.t("第 1 步，逐段整理：", "Step 1, a note on each stretch: ") + bar(p.done, p.total) + " ${p.done} / ${p.total}"
            Stage.MERGE -> L.t("第 2 步，合并笔记…", "Step 2, merging the notes…")
            Stage.FINAL -> L.t("最后一步，写成档案…", "Last step, writing the profile…")
        }
        if (p.kept > 0) {
            val read = p.read?.let { L.t("这次读了 $it 条，", "Read $it now, ") }.orEmpty()
            out += read + L.t("共存 ${p.kept} 条（${amount(p.chars)}）· 已用 ${duration(now - p.startedAt)}",
                "${p.kept} kept (${amount(p.chars)}) · ${duration(now - p.startedAt)} so far")
        }
        if (stalled(p, now)) {
            out += L.t("好像卡住了：${duration(now - p.lastAt)}没有进展。再点「学习」重新开始，写好的段落不会重做。",
                "This looks stuck: nothing for ${duration(now - p.lastAt)}. Tap Learn to start again; the stretches already done are kept.")
            return out
        }
        estimate(p)?.let { out += L.t("预计还要约 ${duration(it)}", "About ${duration(it)} to go") }
        if (p.stage == Stage.FINAL) out += L.t("这一步通常 1～3 分钟，已等 ${duration(now - p.stageAt)}", "This step usually takes 1–3 min; ${duration(now - p.stageAt)} so far")
        out += L.t("在后台写，不用重来。关掉卡片也会继续，写完之前屏幕会一直亮着。",
            "It runs in the background; no need to start again. It carries on with the card closed, and the screen stays on until it is done.")
        return out
    }

    /**
     * Time left while the stretches are being noted: how long each took so far this write, times
     * those left, and the last step. Null until two have come in, when a guess would be noise.
     */
    fun estimate(p: Progress): Long? {
        if (p.stage != Stage.NOTES) return null
        val newly = p.done - maxOf(0, p.doneAtStart)
        if (newly < 2) return null
        val each = (p.lastAt - p.startedAt) / newly
        return each * (p.total - p.done).coerceAtLeast(0) + FINAL_MS
    }
}
