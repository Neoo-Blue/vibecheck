package dev.vibecheck

/**
 * Who the other person is to me, in two separate parts: what kind of relationship (the same
 * eight options as Jev's situation question) and how close we are. Being close is not being a
 * couple: the old option "恋爱或亲密关系" mixed the two, and a best friend was read as a partner.
 *
 * A relationship that is known (learned from the history, or set by hand) takes the place of
 * the situation question, so one turn about someone's boyfriend cannot turn a friend into one.
 */
object Relationship {

    /** Chinese keys, like every option: what is learned under them survives a language switch. */
    val KEYS: List<String> = listOf(
        "恋人或伴侣", "暧昧试探", "朋友", "家人", "同事或上下级", "客户或生意", "陌生人或刚加上", "客服或办事",
    )

    /**
     * Keys older versions used. "恋爱或亲密关系" became "恋人或伴侣": "亲密" also describes close
     * friends, and it drew exactly those chats to the wrong option.
     */
    val RENAMED: Map<String, String> = mapOf("恋爱或亲密关系" to "恋人或伴侣")

    fun normalize(key: String): String = RENAMED[key] ?: key

    /** A stored value that names one of the options, or null for "work it out from each chat". */
    fun pinned(value: String?): String? = value?.trim()?.let { normalize(it) }?.takeIf { it in KEYS }

    /** How close we are, from distant to very close. Chinese keys like every option. */
    val CLOSENESS: List<String> = listOf("不熟", "普通", "熟", "很铁", "无话不谈")

    /** A stored closeness, or null when it is not known. */
    fun closeness(value: String?): String? = value?.trim()?.takeIf { it in CLOSENESS }

    /** The options as the profile prompt lists them, in the UI language. */
    fun optionList(): String = list(KEYS)

    fun closenessList(): String = list(CLOSENESS)

    private fun list(keys: List<String>): String =
        if (L.en) keys.joinToString(", ") { L.english(it) } else keys.joinToString("、")

    /** "关系：朋友" or "Relationship: friend", optionally bulleted. */
    private val LINE = Regex("""^[\s•·*\-]*(关系|relationship)[\s*]*[:：][\s*]*(.+?)[\s*]*$""", RegexOption.IGNORE_CASE)

    /** "亲近：很铁" or "Closeness: close". */
    private val CLOSE_LINE = Regex("""^[\s•·*\-]*(亲近程度|亲近|closeness)[\s*]*[:：][\s*]*(.+?)[\s*]*$""", RegexOption.IGNORE_CASE)

    /** What a profile says about who they are to me, and the profile without the lines that said it. */
    data class Parsed(val rel: String?, val close: String?, val profile: String)

    /**
     * A profile is asked to start with "关系：<option>" and "亲近：<level>". Those lines are taken
     * out, since both are kept on their own and can later be changed by hand. A profile without
     * them (written by an older version) is kept whole, and its first line, which says in words
     * who they are to me, is read for a clear answer instead.
     */
    fun parse(profile: String): Parsed {
        val lines = profile.lines()
        var rel: String? = null
        var close: String? = null
        val used = HashSet<Int>()
        for ((i, line) in lines.withIndex().filter { it.value.isNotBlank() }.take(4)) {
            LINE.matchEntire(line.trim())?.let { m ->
                (exact(clean(m.groupValues[2])) ?: guess(clean(m.groupValues[2])))?.let { rel = it; used += i }
            }
            CLOSE_LINE.matchEntire(line.trim())?.let { m ->
                exactCloseness(clean(m.groupValues[2]))?.let { close = it; used += i }
            }
        }
        if (used.isEmpty()) {
            val first = lines.firstOrNull { it.isNotBlank() }.orEmpty()
            return Parsed(guess(first), guessCloseness(first), profile)
        }
        return Parsed(rel, close, lines.filterIndexed { i, _ -> i !in used }.joinToString("\n").trim())
    }

    /** [parse] for the relationship alone. */
    fun fromProfile(profile: String): Pair<String?, String> = parse(profile).let { it.rel to it.profile }

    private fun clean(v: String) = v.trim().trimEnd('。', '.', '；', ';', '，', ',')

    fun exactCloseness(value: String): String? {
        val v = value.trim().trim('「', '」', '"', '“', '”', '*')
        return CLOSENESS.firstOrNull { it == v } ?: CLOSENESS.firstOrNull { L.english(it).equals(v, ignoreCase = true) }
    }

    /** Words for how close, per level; see [guessCloseness]. */
    private val CLOSE_WORDS: Map<String, List<String>> = mapOf(
        "无话不谈" to listOf("无话不谈", "最好的朋友", "最好的闺蜜", "最亲近", "最铁", "best friend", "closest"),
        "很铁" to listOf("死党", "铁哥们", "很铁", "闺蜜", "发小", "关系亲密", "很亲近", "很要好", "关系很好", "close friend"),
        "熟" to listOf("熟人", "挺熟", "比较熟", "老同学", "老朋友"),
        "普通" to listOf("普通朋友", "一般朋友", "关系一般", "不太联系", "acquaintance"),
        "不熟" to listOf("不熟", "不太熟", "刚认识", "陌生", "just met"),
    )

    /**
     * How close a line of plain words says we are. Words for neighbouring levels ("死党" and
     * "最好的朋友") settle on the closer one; words far apart are no answer.
     */
    fun guessCloseness(text: String): String? {
        if (text.isBlank()) return null
        val t = text.lowercase()
        val levels = CLOSE_WORDS.filter { (_, words) -> words.any { t.contains(it) } }.keys.map { CLOSENESS.indexOf(it) }
        if (levels.isEmpty() || levels.max() - levels.min() > 1) return null
        return CLOSENESS[levels.max()]
    }

    /** An option named as such, by its key, an older key or its English label. */
    fun exact(value: String): String? {
        val v = value.trim().trim('「', '」', '"', '“', '”', '*')
        KEYS.firstOrNull { it == v }?.let { return it }
        RENAMED[v]?.let { return it }
        return KEYS.firstOrNull { L.english(it).equals(v, ignoreCase = true) }
    }

    /**
     * Words that say who someone is. Only unambiguous ones: "对象" is as often "倾诉对象" as a
     * partner, "partner" as often a business one, "老板" a shop owner.
     */
    private val WORDS: Map<String, List<String>> = mapOf(
        "恋人或伴侣" to listOf("恋人", "伴侣", "男朋友", "女朋友", "男友", "女友", "老公", "老婆", "丈夫", "妻子", "爱人", "夫妻", "情侣", "未婚夫", "未婚妻"),
        "暧昧试探" to listOf("暧昧", "追求", "相亲"),
        "朋友" to listOf("朋友", "好友", "闺蜜", "死党", "哥们", "发小", "室友", "同学"),
        // Not "哥哥" or "姐姐": "小哥哥", "小姐姐" are what anyone calls a stranger online.
        "家人" to listOf("家人", "妈妈", "爸爸", "母亲", "父亲", "老妈", "老爸", "弟弟", "妹妹", "亲戚",
            "表哥", "表姐", "表弟", "表妹", "堂哥", "堂姐", "堂弟", "堂妹", "奶奶", "爷爷", "外婆", "外公", "儿子", "女儿"),
        "同事或上下级" to listOf("同事", "上司", "领导", "下属", "主管", "组长"),
        "客户或生意" to listOf("客户", "甲方", "乙方", "买家", "卖家", "供应商", "合作方"),
        "陌生人或刚加上" to listOf("陌生人", "刚认识", "刚加上", "刚加的"),
        "客服或办事" to listOf("客服", "售后"),
    )

    private val ENGLISH: Map<String, List<String>> = mapOf(
        "恋人或伴侣" to listOf("boyfriend", "girlfriend", "husband", "wife", "spouse", "fiance", "fiancee"),
        "暧昧试探" to listOf("crush", "flirt", "flirting"),
        "朋友" to listOf("friend", "bestie", "buddy", "roommate", "classmate"),
        "家人" to listOf("family", "mother", "father", "mom", "mum", "dad", "sister", "brother", "sibling", "cousin",
            "grandma", "grandpa", "grandmother", "grandfather", "daughter", "parent"),
        "同事或上下级" to listOf("colleague", "coworker", "co-worker", "boss", "manager", "teammate"),
        "客户或生意" to listOf("client", "supplier", "vendor"),
        "陌生人或刚加上" to listOf("stranger"),
        "客服或办事" to listOf("customer service", "support agent"),
    )

    private val EX = Regex("""前(男友|女友|男朋友|女朋友|妻|夫)|\bex[- ]?(boyfriend|girlfriend|husband|wife|fiancee?)""", RegexOption.IGNORE_CASE)

    private val ENGLISH_RX: List<Pair<String, Regex>> = ENGLISH.flatMap { (key, words) ->
        words.map { key to Regex("""\b${Regex.escape(it)}s?\b""", RegexOption.IGNORE_CASE) }
    }

    /**
     * The option a line of plain words points to, when it points to exactly one. A word inside a
     * longer one does not count ("朋友" inside "男朋友"), and two options, as in "好友，有男朋友",
     * is no answer: a wrong relationship does more harm than none.
     */
    fun guess(text: String): String? {
        if (text.isBlank()) return null
        class Hit(val key: String, val start: Int, val end: Int)
        val hits = ArrayList<Hit>()
        for ((key, words) in WORDS) for (w in words) {
            var i = text.indexOf(w)
            while (i >= 0) { hits.add(Hit(key, i, i + w.length)); i = text.indexOf(w, i + 1) }
        }
        for ((key, rx) in ENGLISH_RX) rx.findAll(text).forEach { hits.add(Hit(key, it.range.first, it.range.last + 1)) }
        // "前女友", "ex-girlfriend": someone who was a partner is not one now.
        if (hits.any { it.key == "恋人或伴侣" } && EX.containsMatchIn(text)) return null
        val kept = hits.filter { h -> hits.none { o -> o !== h && o.start <= h.start && o.end >= h.end && (o.end - o.start) > (h.end - h.start) } }
        return kept.map { it.key }.distinct().singleOrNull()
    }
}
