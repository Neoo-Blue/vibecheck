package dev.vibecheck

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * What the app learns about me, across every chat: how I talk, what I do day by day, what I care
 * about. Two sources feed it:
 *  - a day log: the messages seen live in any watched chat, mine and theirs, with the time and
 *    whose chat it was; each finished day is written up in a few lines by the model;
 *  - the kept histories of the people learned: notes on what they show about me, cached per
 *    stretch like a person's notes, and merged with the day write-ups into one profile of me.
 * Reply drafts and deep reads get a short brief of it and what I said elsewhere today, so a draft
 * can mention what I actually did, in my own voice. All of it stays on the phone except what is
 * sent to write it.
 */
object Me {

    // ---- the day log ----

    /** One logged message: "21:07", "我" or "对方", whose chat it was, the text. */
    data class Line(val time: String, val who: String, val person: String, val text: String)

    fun day(time: Long, tz: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }.format(Date(time))

    fun clock(time: Long, tz: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = tz }.format(Date(time))

    /** "9月27日 周六" or "Sat, Sep 27" for a "2026-09-27". */
    fun dayLabel(day: String): String {
        val date = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(day) }.getOrNull() ?: return day
        val c = Calendar.getInstance().apply { time = date }
        val zh = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")[c.get(Calendar.DAY_OF_WEEK) - 1]
        return if (L.en) SimpleDateFormat("EEE, MMM d", Locale.US).format(date)
            else "${c.get(Calendar.MONTH) + 1}月${c.get(Calendar.DAY_OF_MONTH)}日 $zh"
    }

    /** Lines for a day's file: time, M or T, whose chat, the text; tabs and newlines escaped. */
    fun encode(time: String, person: String, lines: List<Pair<String, String>>): String = buildString {
        for ((who, text) in lines) {
            append(time).append('\t').append(if (who == "我") 'M' else 'T').append('\t')
            append(esc(person)).append('\t').append(esc(text)).append('\n')
        }
    }

    fun decode(text: String): List<Line> = text.lineSequence().mapNotNull { l ->
        val p = l.split('\t')
        if (p.size != 4 || (p[1] != "M" && p[1] != "T")) null
        else Line(p[0], if (p[1] == "M") "我" else "对方", unesc(p[2]), unesc(p[3]))
    }.toList()

    private fun esc(s: String) = buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\t' -> append("\\t")
            '\r' -> {}
            else -> append(c)
        }
    }

    private fun unesc(s: String) = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                append(when (s[i + 1]) { 'n' -> '\n'; 't' -> '\t'; else -> s[i + 1] })
                i += 2
            } else { append(c); i++ }
        }
    }

    /**
     * One day's messages for the model: chat by chat, in the order they were first active, each
     * in time order. Past [maxChars] the earliest messages go first: the evening is what the day
     * ended on.
     */
    fun dayPrompt(day: String, lines: List<Line>, maxChars: Int = 12_000): String {
        var kept = lines.map { it.copy(text = it.text.replace('\n', ' ').take(200)) }
        fun size(ls: List<Line>) = ls.sumOf { it.text.length + it.person.length + 10 }
        while (kept.size > 1 && size(kept) > maxChars) kept = kept.drop(maxOf(1, kept.size / 10))
        val out = StringBuilder(dayLabel(day)).append('\n')
        for ((person, ls) in kept.groupBy { it.person }) {
            out.append(L.t("\n【和 $person 的聊天】\n", "\n[Chat with $person]\n"))
            for (l in ls) out.append(l.time).append(' ').append(if (l.who == "我") L.t("我", "Me") else person).append(L.t("：", ": ")).append(l.text).append('\n')
        }
        return out.toString()
    }

    /**
     * What I said in other chats today, for a reply: my own messages only, newest last. Null when
     * there is nothing.
     */
    fun elsewhereToday(lines: List<Line>, notPerson: String, max: Int = 8): String? =
        lines.filter { it.who == "我" && it.person != notPerson && it.text.isNotBlank() }
            .takeLast(max)
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n") { "${it.time} " + L.t("对 ${it.person}：", "to ${it.person}: ") + it.text.replace('\n', ' ').take(60) }

    // ---- prompts ----

    val DAY_SYSTEM: String get() = L.t(
        "下面是我某一天在各个聊天里的消息（按聊天分组，按时间排）。写这一天的小结，给我以后回顾，也给替我写回复时参考：" +
            "我做了什么、去了哪、和谁聊了什么、定了什么计划或约定、心情怎么样。只写记录里有的，不编；称我为「我」，别人用名字。" +
            Profile.MISREAD_ZH +
            "三到五行，每行以「•」开头，总共不超过 150 字。没什么内容就只写一行「• 没聊什么」。",
        "Below are my messages from one day across my chats (grouped by chat, in time order). Write the day up, for me " +
            "to look back on and for drafting replies in my name: what I did, where I went, who I talked with about what, " +
            "plans or promises made, how I felt. Only what the messages show; invent nothing; call me \"I\" and others by " +
            "name. " + Profile.MISREAD_EN + "Three to five lines, each starting with \"•\", at most 80 words. If there " +
            "is little, write only \"• Not much\".")

    val NOTES_SYSTEM: String get() = L.t(
        "你在读「我」和一个人的一段聊天记录（从旧到新）。只记关于「我」的事，给我的个人档案用。按下面的小标题写，" +
            "每个小标题下一到三行短句；这一段里没有的小标题不写：\n" +
            "【我是谁】工作、学校、住哪、作息、家人、宠物这类事实\n" +
            "【我怎么说话】我的语气、口头禅、常用词和表情，附一两句我的原话\n" +
            "【我喜欢】爱好、吃的、玩的、在追的东西\n【我不喜欢】\n" +
            "【我最近在忙】工作、学习、计划、约定，带上大概的时间\n" +
            "【我在意的事】让我开心、烦心、担心的事\n" +
            "只写记录里有的，不编；对方的事不写，除非和我有关。" + Profile.MISREAD_ZH + "总共不超过 500 字。",
        "You are reading a stretch of a chat between \"me\" and one person (oldest first). Note only what it shows " +
            "about ME, for my own profile. Use these headings, one to three short lines each; leave out any heading " +
            "this stretch says nothing about:\n" +
            "[Who I am] facts: work, school, where I live, routine, family, pets\n" +
            "[How I talk] my tone, catchphrases, words and emoji, with a quote or two of mine\n" +
            "[What I like] hobbies, food, activities, what I'm into\n[What I dislike]\n" +
            "[What I'm busy with] work, study, plans, promises, with rough dates\n" +
            "[What I care about] what makes me happy, annoyed or worried\n" +
            "Only what is in it; invent nothing; nothing about them unless it concerns me. " + Profile.MISREAD_EN + "At most 250 words.")

    val MERGE_SYSTEM: String get() = L.t(
        "下面是关于「我」的几份笔记。合并成一份，用同样的小标题；重复的合并，矛盾的以较新的为准，只写笔记里有的。总共不超过 900 字。",
        "Below are several sets of notes about me. Merge them into one with the same headings; merge repeats, the " +
            "newer wins where they contradict, add nothing. At most 450 words.")

    fun profileSystem(): String = L.t(
        "下面是关于「我」的材料：从我和不同的人的聊天里整理的笔记、我最近每天的小结、我身边的人。" +
            "写一份关于我的完整档案，让以后替我写回复时像我本人。只写材料里有的，不编；重复的合并；矛盾的以较新的为准。" +
            "按这些小标题分段，小标题单独占一行，下面一到五行，每行以「•」开头；没有内容的段不写：${headings()}\n" +
            "【我怎么说话】要具体到能照着模仿：语气、口头禅、标点、表情、句子多长，对不同的人有什么不同，附原话。" +
            "【我最近在忙】按时间写，最新的在前。【我身边的人】每人一行：名字、是我的什么人。总共不超过 1200 字。",
        "Below is material about me: notes from my chats with different people, write-ups of my recent days, and the " +
            "people in my life. Write a full profile of me, so that replies drafted in my name sound like me. Only what " +
            "the material shows; invent nothing; merge repeats; the newer wins where it contradicts. Use these headings, " +
            "each on its own line, with one to five lines under it starting with \"•\"; leave out headings with nothing " +
            "to say: ${headings()}\n" +
            "[How I talk] must be specific enough to imitate: tone, catchphrases, punctuation, emoji, message length, " +
            "how it differs between people, with quotes. [What I'm busy with] in time order, newest first. [People in my " +
            "life] one line each: name, what they are to me. At most 600 words.")

    // ---- not paying twice for the same notes ----

    /**
     * Notes taken on a chat's newest stretch still stand for it while it has grown by less than
     * half, and by less than [MIN_GROWTH] characters when it is short: that stretch grows with
     * every message, and all of it used to be noted again each time the profile of me was
     * written. Meanwhile what is newer is in the day write-ups. A finished stretch, which grows no
     * more, is noted once more in full.
     */
    fun stillServes(notedChars: Int, chars: Int): Boolean =
        notedChars > 0 && chars >= notedChars && chars - notedChars < maxOf(notedChars / 2, MIN_GROWTH)

    const val MIN_GROWTH = 1_500

    /** Where a stretch begins, which stays put as the stretch grows: whose chat, and its first lines. */
    fun startKey(person: String, lines: List<Pair<String, String>>, range: IntRange): String =
        Archive.hash("me:start\n$person\n" + Archive.text(lines, range.first..minOf(range.last, range.first + 2)))

    /**
     * The notes that stand for a stretch, and the key they are kept under: its own, or for the
     * newest stretch of a chat ([newest]), those on an earlier version of it that [stillServes].
     * [starts] maps a stretch's [startKey] to the key and length of the text last noted there.
     */
    fun reuse(
        notes: Map<String, String>,
        starts: Map<String, String>,
        hash: String,
        start: String,
        chars: Int,
        newest: Boolean,
    ): Pair<String, String>? {
        notes[hash]?.let { return hash to it }
        if (!newest) return null
        val (h, n) = starts[start]?.split(' ')?.takeIf { it.size == 2 } ?: return null
        if (!stillServes(n.toIntOrNull() ?: return null, chars)) return null
        return notes[h]?.let { h to it }
    }

    fun notesPrompt(peer: String, part: Int, parts: Int, text: String): String =
        L.t("和我聊天的人：$peer\n第 $part / $parts 段：\n", "Chat with: $peer\nPart $part of $parts:\n") + text

    /** [notes] per chat, [days] newest first as (day, write-up), [people] one line each, [phrases] what I send most. */
    fun profilePrompt(notes: List<String>, days: List<Pair<String, String>>, people: List<String>, phrases: String?): String = buildString {
        if (people.isNotEmpty()) append(L.t("我身边的人：\n", "People in my life:\n")).append(people.joinToString("\n")).append("\n\n")
        phrases?.let { append(L.t("我最常单独发的话：", "What I send most often on its own: ")).append(it).append("\n\n") }
        if (days.isNotEmpty()) {
            append(L.t("我最近每天的小结（新的在前）：\n", "Write-ups of my recent days (newest first):\n"))
            for ((d, s) in days) append(dayLabel(d)).append('\n').append(s.trim()).append('\n')
            append('\n')
        }
        if (notes.isNotEmpty()) append(L.t("从聊天里整理的笔记：\n", "Notes from my chats:\n")).append(notes.joinToString("\n\n"))
    }

    // ---- reading it back ----

    private val SECTIONS: List<Triple<String, String, String>> = listOf(
        Triple("who", "我是谁", "Who I am"),
        Triple("voice", "我怎么说话", "How I talk"),
        Triple("likes", "我喜欢", "What I like"),
        Triple("dislikes", "我不喜欢", "What I dislike"),
        Triple("busy", "我最近在忙", "What I'm busy with"),
        Triple("care", "我在意的事", "What I care about"),
        Triple("people", "我身边的人", "People in my life"),
    )

    private fun headings(): String = SECTIONS.joinToString(if (L.en) " " else "") { L.t("【${it.second}】", "[${it.third}]") }

    private fun key(s: String) = s.filterNot { it.isWhitespace() }.lowercase()

    /** The section id a heading stands for, in either language. */
    fun idOf(heading: String): String? =
        SECTIONS.firstOrNull { key(it.second) == key(heading) || key(it.third) == key(heading) }?.first

    /**
     * What reply drafts and deep reads are told about me: how I talk, who I am, what I have been
     * busy with, what I like, and the last days' write-ups, within [max] characters. Null when
     * nothing is known yet.
     */
    fun brief(profile: String, days: List<Pair<String, String>>, max: Int = 900): String? {
        val secs = Profile.sections(profile)
        val parts = ArrayList<String>()
        for (id in listOf("voice", "who", "busy", "likes", "care")) {
            val s = secs.firstOrNull { idOf(it.heading) == id && it.lines.isNotEmpty() } ?: continue
            parts += "【${s.heading}】${s.text}"
        }
        if (parts.isEmpty() && profile.isNotBlank() && secs.none { idOf(it.heading) != null }) parts += profile.trim()
        for ((d, s) in days.take(3)) parts += dayLabel(d) + L.t("：", ": ") + s.lines().map { it.trim().removePrefix("•").trim() }.filter { it.isNotEmpty() }.joinToString(L.t("；", "; "))
        if (parts.isEmpty()) return null
        val out = StringBuilder()
        for (p in parts) {
            if (out.isNotEmpty() && out.length + p.length + 1 > max) break
            if (out.isNotEmpty()) out.append('\n')
            out.append(p.take(max))
        }
        return out.toString()
    }

    /** One line about me for the People tab: who I am, else what I have been busy with. */
    fun oneLine(profile: String, max: Int = 40): String? {
        val secs = Profile.sections(profile)
        val line = listOf("who", "busy", "voice").firstNotNullOfOrNull { id ->
            secs.firstOrNull { idOf(it.heading) == id && it.lines.isNotEmpty() }?.lines?.first()
        } ?: return null
        val t = line.trim().removePrefix("•").removePrefix("-").trim()
        return if (t.length <= max) t else t.take(max - 1) + "…"
    }
}
