package dev.vibecheck

/**
 * What this relationship looks like over time, accumulated on the phone from messages that were
 * on screen anyway. None of it costs an API call, so it keeps accruing even when the card is
 * dismissed or judging is switched off.
 *
 * ponytail: these are observations, not a model of the person. Counts and ratios only, no
 * inference; the inference stays with Jev and the deep model, which now get this as context.
 */
object Relation {

    private const val SESSION_GAP_MS = 2 * 60 * 60 * 1000L   // a fresh conversation after 2h quiet
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    data class Stats(
        var theirMsgs: Int = 0,
        var myMsgs: Int = 0,
        var theirChars: Int = 0,
        var myChars: Int = 0,
        var sessions: Int = 0,
        var theyStarted: Int = 0,
        var friction: Int = 0,          // turns judged dangerous
        var calm: Int = 0,
        var replySamples: Int = 0,
        var replySecTotal: Long = 0,    // how long I take to answer, when observed
        var firstSeen: Long = 0,
        var lastSeen: Long = 0,
        var awaitingSince: Long = 0,    // their newest message is sitting unanswered since
        var daysSeen: Int = 0,
        var lastDay: Long = 0,
    )

    /** Fold another record's counts into this one (linking the same person across apps). */
    fun merge(into: Stats, from: Stats) {
        into.theirMsgs += from.theirMsgs; into.myMsgs += from.myMsgs
        into.theirChars += from.theirChars; into.myChars += from.myChars
        into.sessions += from.sessions; into.theyStarted += from.theyStarted
        into.friction += from.friction; into.calm += from.calm
        into.replySamples += from.replySamples; into.replySecTotal += from.replySecTotal
        into.daysSeen += from.daysSeen
        into.firstSeen = listOf(into.firstSeen, from.firstSeen).filter { it > 0 }.minOrNull() ?: 0
        into.lastSeen = maxOf(into.lastSeen, from.lastSeen)
        into.lastDay = maxOf(into.lastDay, from.lastDay)
    }

    /** Fold in messages we have not counted before. Order matters: oldest first. */
    fun observe(s: Stats, fresh: List<Chat.Bubble>, now: Long) {
        if (fresh.isEmpty()) return

        if (s.firstSeen == 0L) s.firstSeen = now
        if (now - s.lastSeen > SESSION_GAP_MS) {
            s.sessions++
            if (fresh.first().incoming) s.theyStarted++
        }
        val day = now / DAY_MS
        if (day != s.lastDay) { s.daysSeen++; s.lastDay = day }

        for (b in fresh) {
            if (b.incoming) {
                s.theirMsgs++
                s.theirChars += b.text.length
                if (s.awaitingSince == 0L) s.awaitingSince = now   // clock starts on their message
            } else {
                s.myMsgs++
                s.myChars += b.text.length
                if (s.awaitingSince != 0L) {
                    s.replySamples++
                    s.replySecTotal += (now - s.awaitingSince) / 1000
                    s.awaitingSince = 0L
                }
            }
        }
        s.lastSeen = now
    }

    /**
     * Fold a whole history read in at once. Only counts and lengths: sessions, reply latency and
     * who-opened all depend on when a message was seen, which a bulk scroll cannot tell.
     */
    fun observeBulk(s: Stats, messages: List<Pair<String, String>>) {
        for ((who, text) in messages) {
            if (who == "对方") { s.theirMsgs++; s.theirChars += text.length }
            else { s.myMsgs++; s.myChars += text.length }
        }
    }

    /** Called when a turn was actually judged, so friction is measured, not guessed. */
    fun judged(s: Stats, dangerNorm: Double) {
        if (dangerNorm >= 0.6) s.friction++ else s.calm++
    }

    /** The line that goes into the model's state. Null until there is enough to say anything. */
    fun summary(s: Stats): String? {
        val total = s.theirMsgs + s.myMsgs
        if (total < 6) return null
        val parts = ArrayList<String>()

        parts.add("共看到 $total 条（对方 ${s.theirMsgs}，我 ${s.myMsgs}）")
        val theirAvg = if (s.theirMsgs > 0) s.theirChars / s.theirMsgs else 0
        val myAvg = if (s.myMsgs > 0) s.myChars / s.myMsgs else 0
        parts.add("平均长度 对方 $theirAvg 字 / 我 $myAvg 字")

        if (s.sessions >= 2) {
            val pct = s.theyStarted * 100 / s.sessions
            parts.add(
                when {
                    pct >= 65 -> "多数时候是对方先开口（$pct%）"
                    pct <= 35 -> "多数时候是我先开口（${100 - pct}%）"
                    else -> "谁先开口大致对半"
                }
            )
        }
        if (s.replySamples >= 3) parts.add("我平均 ${fmtDuration(s.replySecTotal / s.replySamples)} 回复")
        if (s.friction + s.calm >= 4) {
            parts.add("判断过 ${s.friction + s.calm} 轮，其中 ${s.friction} 轮是高风险")
        }
        if (s.daysSeen >= 2) parts.add("有 ${s.daysSeen} 天聊过")
        return parts.joinToString("；")
    }

    fun fmtDuration(sec: Long): String = when {
        sec < 90 -> "$sec 秒"
        sec < 3600 -> "${sec / 60} 分钟"
        sec < 86400 -> "${sec / 3600} 小时"
        else -> "${sec / 86400} 天"
    }

    fun save(s: Stats): String = listOf(
        s.theirMsgs, s.myMsgs, s.theirChars, s.myChars, s.sessions, s.theyStarted,
        s.friction, s.calm, s.replySamples, s.replySecTotal, s.firstSeen, s.lastSeen,
        s.awaitingSince, s.daysSeen, s.lastDay,
    ).joinToString("\t")

    fun load(text: String): Stats {
        val p = text.split('\t').map { it.toLongOrNull() ?: 0L }
        if (p.size < 15) return Stats()
        return Stats(
            p[0].toInt(), p[1].toInt(), p[2].toInt(), p[3].toInt(), p[4].toInt(), p[5].toInt(),
            p[6].toInt(), p[7].toInt(), p[8].toInt(), p[9], p[10], p[11], p[12], p[13].toInt(), p[14],
        )
    }
}
