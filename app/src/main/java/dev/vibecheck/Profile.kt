package dev.vibecheck

/**
 * The profile a history read writes about one person: what it asks the model for, how it is
 * read back, and which parts go where. The full profile goes to deep reads and reply drafts; the
 * judge gets a short brief; the card shows the one part that bears on the message at hand.
 *
 * A long history is written up in two steps: notes on each stretch (cached by the stretch's
 * hash, so a later read pays only for what is new), then one profile from all the notes.
 */
object Profile {

    /** Section ids with their headings, Chinese and English, in the order the profile uses. */
    private val SECTIONS: List<Triple<String, String, String>> = listOf(
        Triple("who", "Ta 是谁", "Who they are"),
        Triple("us", "我们怎么相处", "How we get along"),
        Triple("their", "Ta 怎么说话", "How they talk"),
        Triple("mine", "我怎么跟 Ta 说话", "How I talk to them"),
        Triple("likes", "Ta 喜欢", "They like"),
        Triple("dislikes", "Ta 不喜欢", "They dislike"),
        Triple("topics", "常聊的事", "What we talk about"),
        Triple("jokes", "我们的梗", "Our running jokes"),
        Triple("events", "重要的事", "Things that happened"),
        Triple("sore", "雷区", "Sore spots"),
        Triple("comfort", "Ta 难过时", "When they're down"),
    )

    fun heading(id: String): String = SECTIONS.first { it.first == id }.let { L.t(it.second, it.third) }

    private fun headings(): String = SECTIONS.joinToString(if (L.en) " " else "") { L.t("【${it.second}】", "[${it.third}]") }

    private fun key(s: String) = s.filterNot { it.isWhitespace() }.lowercase()

    private fun idOf(heading: String): String? =
        SECTIONS.firstOrNull { key(it.second) == key(heading) || key(it.third) == key(heading) }?.first

    // ---- prompts ----

    /** Step one: notes on one stretch of the history. */
    val NOTES_SYSTEM: String get() = L.t(
        "你在读「我」和「对方」的一段聊天记录（从旧到新）。把这一段里能看出来、以后跟 Ta 聊天用得上的东西记下来。" +
            "只写记录里有的，不编，不写空话。按下面的小标题写，每个小标题下一到三行短句；这一段没有相关内容的小标题就不写：\n" +
            "【Ta 是谁】工作、学校、住哪、家人、宠物、生日、作息这类事实\n" +
            "【我们怎么相处】谁更主动、怎么称呼对方、怎么开玩笑、有多亲近\n" +
            "【Ta 怎么说话】长短、语气、口头禅、常用表情，附一两句原话\n" +
            "【我怎么跟 Ta 说话】我的称呼、语气、常用词，附一两句我的原话\n" +
            "【Ta 喜欢】吃的、玩的、爱好、在追的东西\n【Ta 不喜欢】\n【常聊的事】\n" +
            "【我们的梗】只有我们懂的说法和笑点\n【重要的事】发生过的事、计划、约定，带上大概的时间\n" +
            "【雷区】会让 Ta 不高兴的话题或说法\n【Ta 难过时】什么话有用、什么没用\n" +
            "总共不超过 600 字。",
        "You are reading a stretch of a chat between \"me\" and \"them\" (oldest first). Note what it shows that will " +
            "help me talk to them later. Only what is in it; invent nothing, no filler. Use these headings, one to three " +
            "short lines under each; leave out any heading this stretch says nothing about:\n" +
            "[Who they are] facts: work, school, where they live, family, pets, birthday, routine\n" +
            "[How we get along] who reaches out, what we call each other, how we joke, how close we are\n" +
            "[How they talk] length, tone, catchphrases, emoji, with a quote or two\n" +
            "[How I talk to them] what I call them, my tone, words I use, with a quote or two of mine\n" +
            "[They like] food, activities, hobbies, what they're into\n[They dislike]\n[What we talk about]\n" +
            "[Our running jokes] things only we get\n[Things that happened] events, plans, promises, with rough dates\n" +
            "[Sore spots] topics or phrasing that upset them\n[When they're down] what helps and what doesn't\n" +
            "At most 300 words.")

    /** Step two, or the only step for a short history: the profile itself. */
    fun profileSystem(fromNotes: Boolean): String = L.t(
        (if (fromNotes) "下面是从我和「对方」的全部聊天记录里分段整理出的笔记（从旧到新）。" else "下面是我和「对方」的聊天记录（从旧到新）。") +
            "写一份关于 Ta 的完整档案，给我以后聊天用。只写材料里有的，不编；重复的合并；前后矛盾的以较新的为准。\n" +
            "第一行固定写「关系：」，后面只写下面其中一个词：${Relationship.optionList()}。看的是 Ta 和我之间的关系，不是聊天的话题：朋友之间聊各自的感情，仍然是朋友。\n" +
            "第二行固定写「亲近：」，后面只写下面其中一个词：${Relationship.closenessList()}。关系好、聊得亲密，不等于是恋人。\n" +
            "然后按这些小标题分段，小标题单独占一行，下面一到四行，每行以「•」开头；没有内容的段不写：${headings()}\n" +
            "【Ta 怎么说话】和【我怎么跟 Ta 说话】要具体到能照着模仿：称呼、口头禅、语气词、标点、表情、句子多长，各附一两句原话。\n" +
            "总共不超过 1200 字。",
        (if (fromNotes) "Below are notes taken, stretch by stretch, from my whole chat history with \"them\" (oldest first). "
            else "Below is my chat history with \"them\" (oldest first). ") +
            "Write a full profile of them for me to use in later chats. Only what the material shows; invent nothing; " +
            "merge repeats; where it contradicts itself, the newer wins.\n" +
            "The first line is \"Relationship: \" followed by exactly one of: ${Relationship.optionList()}. That is what they " +
            "are to me, not what we talk about: friends discussing their love lives are still friends.\n" +
            "The second line is \"Closeness: \" followed by exactly one of: ${Relationship.closenessList()}. Being close " +
            "is not being a couple.\n" +
            "Then these headings, each on its own line, with one to four lines under it starting with \"•\"; leave out " +
            "headings with nothing to say: ${headings()}\n" +
            "[How they talk] and [How I talk to them] must be specific enough to imitate: names we use, catchphrases, " +
            "fillers, punctuation, emoji, message length, with a quote or two each.\n" +
            "At most 600 words.")

    /** Too many notes for one pass: groups of them are merged first. */
    val MERGE_SYSTEM: String get() = L.t(
        "下面是关于同一个人的几份笔记（从旧到新）。合并成一份，用同样的小标题；重复的合并，矛盾的以较新的为准，只写笔记里有的。总共不超过 900 字。",
        "Below are several sets of notes about the same person (oldest first). Merge them into one with the same " +
            "headings; merge repeats, the newer wins where they contradict, add nothing. At most 450 words.")

    fun notesPrompt(peer: String, part: Int, parts: Int, text: String): String =
        L.t("对方：$peer\n第 $part / $parts 段：\n", "Them: $peer\nPart $part of $parts:\n") + text

    /**
     * The material for the profile: the notes or the history itself, plus what the program
     * counted, which a model reading excerpts cannot see.
     */
    fun profilePrompt(peer: String, history: List<Pair<String, String>>, body: String, fromNotes: Boolean): String = buildString {
        val theirs = history.count { it.first != "我" }
        append(L.t("对方：", "Them: ")).append(peer).append('\n')
        append(L.t("共 ${history.size} 条（Ta ${theirs} 条，我 ${history.size - theirs} 条）。\n",
            "${history.size} messages (them $theirs, me ${history.size - theirs}).\n"))
        Archive.phraseLine(Archive.phrases(history, "我"))?.let { append(L.t("我最常单独发的话：", "What I send most often on its own: ")).append(it).append('\n') }
        Archive.phraseLine(Archive.phrases(history, "对方"))?.let { append(L.t("Ta 最常单独发的话：", "What they send most often on its own: ")).append(it).append('\n') }
        append('\n').append(if (fromNotes) L.t("分段笔记：\n", "Notes:\n") else L.t("聊天记录：\n", "History:\n")).append(body)
    }

    fun mergePrompt(peer: String, notes: List<String>): String =
        L.t("对方：$peer\n", "Them: $peer\n") + notes.joinToString("\n\n") { "---\n$it" }

    /** Consecutive groups of notes, each within [maxChars], for merging a step at a time. */
    fun groups(notes: List<String>, maxChars: Int): List<List<String>> {
        val out = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        var size = 0
        for (n in notes) {
            if (size + n.length > maxChars && cur.isNotEmpty()) { out.add(cur); cur = ArrayList(); size = 0 }
            cur.add(n)
            size += n.length
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    // ---- reading it back ----

    class Section(val id: String?, val heading: String, val lines: List<String>) {
        /** The section's lines run together, bullets dropped. */
        val text: String get() = lines.joinToString(L.t("；", "; ")) { it.trim().removePrefix("•").removePrefix("-").trim() }
    }

    private val HEAD = Regex("""^[【\[]\s*([^】\]]{1,24}?)\s*[】\]]\s*(.*)$""")

    /** The profile's sections in order; text before the first heading is a section without one. */
    fun sections(profile: String): List<Section> {
        val out = ArrayList<Section>()
        var heading = ""
        var id: String? = null
        var body = ArrayList<String>()
        fun flush() { if (heading.isNotEmpty() || body.isNotEmpty()) out.add(Section(id, heading, body)) }
        for (raw in profile.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val m = HEAD.matchEntire(line)
            if (m == null) { body.add(line); continue }
            flush()
            heading = m.groupValues[1].trim()
            id = idOf(heading)
            body = ArrayList()
            m.groupValues[2].trim().takeIf { it.isNotEmpty() }?.let { body.add(it) }
        }
        flush()
        return out
    }

    /**
     * What the judge gets: the parts that change how a message should be read, within [max]
     * characters. An older free-form profile, without sections, goes as it is.
     */
    fun brief(profile: String, max: Int = 500): String {
        val secs = sections(profile)
        if (secs.none { it.id != null }) return profile.trim().take(max)
        val sb = StringBuilder()
        for (id in listOf("us", "their", "sore", "comfort", "who", "likes")) {
            val s = secs.firstOrNull { it.id == id && it.lines.isNotEmpty() } ?: continue
            val line = "【${s.heading}】${s.text}"
            if (sb.isNotEmpty() && sb.length + line.length + 1 > max) break
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line.take(max))
        }
        return sb.toString()
    }

    /**
     * What reply drafts get: the parts of the profile that shape a reply, most useful first,
     * within [max] characters. The whole profile, often a few thousand characters, used to go
     * with every draft of three short lines. A part that does not fit is left out and the next,
     * shorter one tried; an older free-form profile goes as far as it fits.
     */
    fun forDrafts(profile: String, max: Int = 1200): String {
        val secs = sections(profile)
        if (secs.none { it.id != null }) return profile.trim().take(max)
        val sb = StringBuilder()
        for (id in listOf("us", "mine", "their", "jokes", "sore", "likes", "dislikes", "who", "topics", "comfort", "events")) {
            val s = secs.firstOrNull { it.id == id && it.lines.isNotEmpty() } ?: continue
            val line = "【${s.heading}】${s.text}"
            if (sb.isNotEmpty() && sb.length + line.length + 1 > max) continue
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line.take(max))
        }
        return sb.toString()
    }

    /**
     * One line about the person for the People list: how we get along, else who they are, else
     * the start of an older free-form profile. Null for no profile.
     */
    fun oneLine(profile: String, max: Int = 40): String? {
        if (profile.isBlank()) return null
        val secs = sections(profile)
        val line = listOf("us", "who").firstNotNullOfOrNull { id -> secs.firstOrNull { it.id == id && it.lines.isNotEmpty() }?.lines?.first() }
            ?: secs.firstOrNull { it.lines.isNotEmpty() }?.lines?.first()
            ?: return null
        val t = line.trim().removePrefix("•").removePrefix("-").trim()
        return if (t.length <= max) t else t.take(max - 1) + "…"
    }

    /**
     * The one thing from the profile that bears on this message, for the card: how they take
     * comfort when that is what they need, their sore spots when it could go wrong, the running
     * jokes when they are joking. Null when nothing fits or the profile has no such section.
     */
    fun hint(profile: String, answers: Map<String, Jev.Answer>): String? {
        if (profile.isBlank()) return null
        val need = (answers["need"] as? Jev.Answer.Dist)?.top
        val intent = (answers["intent"] as? Jev.Answer.Dist)?.top
        val risk = Jev.risk(answers) ?: 0.0
        val id = when {
            need == "情绪安抚" || intent == "在表达不满" -> "comfort"
            risk >= 0.5 -> "sore"
            need == "接梗一起玩" || intent == "在开玩笑或一起感慨" -> "jokes"
            else -> return null
        }
        val s = sections(profile).firstOrNull { it.id == id && it.lines.isNotEmpty() } ?: return null
        val first = s.lines.first().trim().removePrefix("•").removePrefix("-").trim()
        return heading(id) + L.t("：", ": ") + first.take(60)
    }

    // ---- notes kept between reads ----

    /** hash -> notes, one per line, newlines escaped. */
    fun saveNotes(notes: Map<String, String>): String =
        notes.entries.joinToString("\n") { (h, n) -> h + "\t" + n.replace("\\", "\\\\").replace("\n", "\\n") }

    fun loadNotes(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (l in text.lineSequence()) {
            val tab = l.indexOf('\t')
            if (tab <= 0) continue
            val sb = StringBuilder()
            var i = tab + 1
            while (i < l.length) {
                if (l[i] == '\\' && i + 1 < l.length) { sb.append(if (l[i + 1] == 'n') '\n' else l[i + 1]); i += 2 }
                else { sb.append(l[i]); i++ }
            }
            out[l.substring(0, tab)] = sb.toString()
        }
        return out
    }
}
