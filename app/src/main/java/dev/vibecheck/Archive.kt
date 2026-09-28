package dev.vibecheck

import java.security.MessageDigest

/**
 * The whole history with one person, kept on the phone once they have been learned: what the
 * profile is written from, and where reply drafts find how I actually answer this person.
 * One message per line, oldest first, as (speaker, text) with speaker "对方" or "我".
 */
object Archive {

    // ---- storage format ----

    /** "T" (them) or "M" (me), a tab, the text with backslash, newline and tab escaped. */
    fun encode(lines: List<Pair<String, String>>): String = buildString {
        for ((who, text) in lines) {
            append(if (who == "我") 'M' else 'T').append('\t')
            for (c in text) when (c) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\t' -> append("\\t")
                '\r' -> {}
                else -> append(c)
            }
            append('\n')
        }
    }

    fun decode(text: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (l in text.lineSequence()) {
            if (l.length < 3 || l[1] != '\t' || (l[0] != 'M' && l[0] != 'T')) continue
            val sb = StringBuilder()
            var i = 2
            while (i < l.length) {
                val c = l[i]
                if (c == '\\' && i + 1 < l.length) {
                    when (l[i + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        else -> sb.append(l[i + 1])
                    }
                    i += 2
                } else { sb.append(c); i++ }
            }
            out.add((if (l[0] == 'M') "我" else "对方") to sb.toString())
        }
        return out
    }

    /**
     * Lines that were never messages: Soul's strip of quick replies above the reply box
     * (「下午好」「礼物」「桌球」「比心」「猜拳」), which versions up to 6.7.1 read as messages and
     * kept. Only a run of three or more of its labels in a row goes, at least half of them and at
     * least two different ones the non-greeting labels: nobody sends that.
     */
    fun withoutStrips(lines: List<Pair<String, String>>): List<Pair<String, String>> {
        var drop: BooleanArray? = null
        var i = 0
        while (i < lines.size) {
            var j = i
            while (j < lines.size && lines[j].second.trim() in STRIP_LABELS) j++
            if (j - i >= 3) {
                val games = (i until j).map { lines[it].second.trim() }.filter { it in STRIP_GAMES }
                if (games.distinct().size >= 2 && games.size * 2 >= j - i) {
                    val d = drop ?: BooleanArray(lines.size).also { drop = it }
                    for (k in i until j) d[k] = true
                }
            }
            i = maxOf(j, i + 1)
        }
        val d = drop ?: return lines
        return lines.filterIndexed { k, _ -> !d[k] }
    }

    /**
     * Quotes kept as if they were messages, by versions that read a quote as the replier's words:
     * my words quoted under their reply were kept as theirs, and theirs under mine as mine. A line
     * shaped like one (Chat.quoteOf) goes when it repeats one of the [LOOK_BACK] lines before it,
     * or when Chat.isQuote says so from its name ([names], [emojiName]) or what it quotes.
     */
    fun withoutQuotes(lines: List<Pair<String, String>>, names: Collection<String> = emptyList(), emojiName: Boolean = false): List<Pair<String, String>> {
        var drop: BooleanArray? = null
        for (i in lines.indices) {
            val text = lines[i].second
            if (Chat.quoteOf(text) == null) continue
            if (Chat.isQuote(text, names, emojiName) || Chat.echoed(text, emptyList(), lines.subList(maxOf(0, i - LOOK_BACK), i)) != null)
                (drop ?: BooleanArray(lines.size).also { drop = it })[i] = true
        }
        val d = drop ?: return lines
        return lines.filterIndexed { k, _ -> !d[k] }
    }

    /** How far back a quote's message is looked for. */
    private const val LOOK_BACK = 80

    private val STRIP_GAMES = setOf("礼物", "桌球", "比心", "猜拳", "骰子")
    private val STRIP_LABELS = STRIP_GAMES + setOf("早上好", "上午好", "中午好", "下午好", "晚上好", "晚安")

    // ---- joining a history read onto what is kept ----

    /**
     * A history read (oldest first, ending at the newest message) joined onto the kept history.
     * A read that stopped on reaching what was already kept starts somewhere inside it; the two
     * are lined up there. Null when they do not line up.
     */
    fun merge(kept: List<Pair<String, String>>, read: List<Pair<String, String>>): List<Pair<String, String>>? {
        if (kept.isEmpty()) return read
        if (read.isEmpty()) return kept
        // Where the newest kept line sits in the read; past the compared part when the read goes
        // further back into the kept history than that.
        val end = Chat.alignEnd(kept.takeLast(MERGE_WINDOW), read.take(MERGE_WINDOW)) ?: return null
        return kept + read.drop(end + 1)
    }

    /** Does this page show part of the newest kept history? Then a read has reached what is known. */
    fun reached(keptTail: List<Pair<String, String>>, page: List<Pair<String, String>>): Boolean =
        keptTail.isNotEmpty() && page.isNotEmpty() && Chat.alignEnd(keptTail, page) != null

    const val MERGE_WINDOW = 300

    /**
     * The lines on screen with the kept history just before them, [n] lines in all: a reply
     * written from the dozen lines a screen holds misses what the chat was about. The screen
     * alone when it does not line up with what was kept.
     */
    fun before(kept: List<Pair<String, String>>, screen: List<Pair<String, String>>, n: Int): List<Pair<String, String>> {
        if (kept.isEmpty() || screen.isEmpty() || screen.size >= n) return screen
        val tail = kept.takeLast(MERGE_WINDOW)
        val end = Chat.alignEnd(tail, screen) ?: return screen
        // The screen's first line sits at this index of the tail.
        val first = tail.size - 1 - end
        if (first <= 0) return screen
        return tail.subList(maxOf(0, first - (n - screen.size)), first) + screen
    }

    // ---- cutting it up for the model ----

    /** Consecutive runs of whole messages, each about [maxChars] of text at most, oldest first. */
    fun chunks(lines: List<Pair<String, String>>, maxChars: Int): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = 0
        var size = 0
        for ((i, l) in lines.withIndex()) {
            val n = minOf(l.second.length, MAX_LINE) + 4
            if (size + n > maxChars && i > start) {
                out.add(start until i)
                start = i
                size = 0
            }
            size += n
        }
        if (start < lines.size) out.add(start until lines.size)
        return out
    }

    /** One message per line for a prompt. A pasted article is cut short rather than eating the budget. */
    fun text(lines: List<Pair<String, String>>, range: IntRange = lines.indices): String = buildString {
        for (i in range) {
            val (who, t) = lines[i]
            append(L.who(who)).append(L.t("：", ": ")).append(t.replace('\n', ' ').take(MAX_LINE)).append('\n')
        }
    }

    private const val MAX_LINE = 400

    /** A stable id for a stretch of history, so notes already written for it are not paid for twice. */
    fun hash(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
            .take(10).joinToString("") { "%02x".format(it) }

    // ---- what I actually say to them ----

    /** Their message (the last two of a run) and my answer right after (the first two of mine). */
    class Exchange(val theirParts: List<String>, val myParts: List<String>) {
        val theirs: String get() = theirParts.joinToString(" / ")
        val mine: String get() = myParts.joinToString(" / ")
    }

    fun exchanges(lines: List<Pair<String, String>>): List<Exchange> {
        val out = ArrayList<Exchange>()
        var i = 0
        while (i < lines.size) {
            if (lines[i].first == "我") { i++; continue }
            val from = i
            while (i < lines.size && lines[i].first != "我") i++
            val theirs = lines.subList(maxOf(from, i - 2), i).map { it.second }
            val mineFrom = i
            while (i < lines.size && lines[i].first == "我") i++
            if (i > mineFrom) out.add(Exchange(theirs, lines.subList(mineFrom, minOf(i, mineFrom + 2)).map { it.second }))
        }
        return out
    }

    /**
     * Past turns that show how I really answer this person: the ones most like what they just
     * said, topped up with the latest, [k] in all, in time order. Similarity is the share of
     * character pairs in common, which works for Chinese without a tokenizer. Anything still on
     * screen ([exclude]) is left out, so the model is not shown the present as the past.
     */
    fun examples(lines: List<Pair<String, String>>, said: String, k: Int = 8, exclude: Set<String> = emptySet()): List<Exchange> {
        val all = exchanges(lines).filter { e ->
            e.mine.length <= 160 && e.theirParts.none { it in exclude } && e.myParts.none { it in exclude }
        }
        if (all.isEmpty()) return emptyList()
        val q = pairs(said)
        val picked = LinkedHashSet<Int>()
        if (q.isNotEmpty()) {
            all.indices.map { it to dice(q, pairs(all[it].theirs)) }
                .filter { it.second >= 0.25 }
                .sortedByDescending { it.second }
                .take((k * 2 + 2) / 3)
                .forEach { picked += it.first }
        }
        for (i in all.indices.reversed()) {
            if (picked.size >= k) break
            picked += i
        }
        return picked.sorted().map { all[it] }
    }

    private fun pairs(s: String): Set<String> {
        val t = s.lowercase().filter { it.isLetterOrDigit() }
        if (t.length < 2) return if (t.isEmpty()) emptySet() else setOf(t)
        return (0 until t.length - 1).mapTo(HashSet()) { t.substring(it, it + 2) }
    }

    private fun dice(a: Set<String>, b: Set<String>): Double =
        if (a.isEmpty() || b.isEmpty()) 0.0 else 2.0 * a.count { it in b } / (a.size + b.size)

    /**
     * What [who] says again and again as a message of its own: "哈哈哈哈", "好滴", "晚安". Counted
     * by the program, not guessed by a model. Placeholders like "[图片]" are not phrases.
     */
    fun phrases(lines: List<Pair<String, String>>, who: String, top: Int = 8, min: Int = 3): List<Pair<String, Int>> =
        lines.asSequence()
            .filter { it.first == who }
            .map { it.second.trim() }
            .filter { it.length in 1..12 && !(it.startsWith("[") && it.endsWith("]")) && it.any { c -> !c.isWhitespace() && c !in "。，,.!！?？~～…" } }
            .groupingBy { it }.eachCount()
            .filter { it.value >= min }
            .entries.sortedByDescending { it.value }
            .take(top)
            .map { it.key to it.value }

    /** "哈哈哈哈、好滴、晚安" for a prompt, or null. */
    fun phraseLine(p: List<Pair<String, Int>>): String? =
        p.takeIf { it.isNotEmpty() }?.joinToString(L.t("、", ", ")) { "「${it.first}」" }
}
