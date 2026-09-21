package dev.vibecheck

/**
 * Who you are talking to, how you talk to them, and what happened last time.
 * Pure and Android-free so it unit tests on the JVM; persistence lives in PersonStore.
 */
object Person {

    fun id(pkg: String, name: String): String = "$pkg|${name.trim()}"

    // ---- identity ----

    // App-level titles and tab labels: these are screens, not people.
    private val TITLE_JUNK = setOf(
        "返回", "取消", "更多", "聊天信息", "微信", "通讯录", "发现", "我",
        "WeChat", "Chats", "Contacts", "Discover", "Me", "Soul", "消息", "搜索", "Search",
        "Messenger", "Messages", "WhatsApp", "Telegram", "Instagram", "LINE", "Signal", "Discord",
        "Snapchat", "QQ", "KakaoTalk", "Viber", "X", "Teams", "Slack", "Calls", "Stories", "People",
        "Back", "Home", "New message", "New chat", "Select contact", "Chat",
    )

    /** The presence line under a name in a chat header: WhatsApp, Telegram, Messenger, Instagram. */
    private val SUBTITLE = Regex(
        """(Active .*|Online|last seen.*|typing.*|\d+ (members|participants|subscribers|online).*|""" +
            """tap here for contact info|在线|正在输入.*|最后上线.*|\d+ ?位?成员)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The contact name from the chat title bar. Candidates are TextViews in the top band of the
     * window; the title is the one nearest the horizontal center. Falls back to an avatar's
     * content description, which WeChat sets to "<名字>头像".
     */
    fun peerName(titles: List<Pair<String, Chat.Box>>, avatarDescs: List<String>, win: Chat.Box): String? {
        val center = (win.left + win.right) / 2
        val title = titles
            .filter { looksLikeName(it.first) }
            .minByOrNull { kotlin.math.abs(it.second.centerX - center) }
        if (title != null) return title.first.trim()
        return avatarDescs.firstOrNull { it.endsWith("头像") && it.length > 2 }
            ?.removeSuffix("头像")?.trim()
    }

    /**
     * OCR of a title bar picks up clock digits and icon noise too, and anything accepted here
     * becomes a person in storage. A name is mostly letters; "M%。l 65" is not.
     */
    fun looksLikeName(raw: String): Boolean {
        val s = raw.trim()
        if (s.length !in 1..24 || s in TITLE_JUNK || SUBTITLE.matches(s)) return false
        val solid = s.filterNot { it.isWhitespace() }
        if (solid.isEmpty()) return false
        val letters = solid.count { it.isLetter() }
        if (letters >= 2 && letters * 10 >= solid.length * 6) return true   // at least 60% letters
        // Names like "🍵" or "Dory🐟" are legitimate; clock and icon noise is not, so require
        // every character to be either a letter or an emoji.
        val emoji = solid.codePoints().filter { isEmoji(it) }.count().toInt()
        val emojiChars = solid.count { Character.isHighSurrogate(it) || Character.isLowSurrogate(it) }
        return emoji >= 1 && letters + emojiChars + emoji >= solid.length
    }

    /**
     * Difference hash of the sampled region: a stable id for a chat whose name cannot be read.
     * Compares neighbours rather than an average, because an average hash of a mostly-blank strip
     * collapses to zero and gives every chat the same identity.
     */
    fun hashOf(samples: IntArray): String {
        if (samples.size < 2) return ""
        var bits = 0L
        var set = 0
        for (i in 0 until minOf(samples.size - 1, 63)) {
            if (samples[i] > samples[i + 1] + 2) { bits = bits or (1L shl i); set++ }
        }
        // A region with no variation at all is not an identity, it is a blank wall.
        if (set == 0) return ""
        return "#" + java.lang.Long.toHexString(bits)
    }

    /**
     * How many bits two fingerprints disagree on. A rendering wobble flips a couple; a different
     * avatar flips most. This is what lets one contact stay one contact across scans, instead of
     * every scan minting a fresh person (the phone had 277 of them).
     */
    fun hamming(a: String, b: String): Int {
        val x = a.removePrefix("#").toLongOrNull(16) ?: return Int.MAX_VALUE
        val y = b.removePrefix("#").toLongOrNull(16) ?: return Int.MAX_VALUE
        return java.lang.Long.bitCount(x xor y)
    }

    fun isFingerprint(name: String) = name.startsWith("#")

    // ---- how I talk ----

    data class Style(
        var msgs: Int = 0,
        var chars: Int = 0,
        var emoji: Int = 0,
        var questions: Int = 0,
        var apologies: Int = 0,
    )

    private val APOLOGY = listOf("对不起", "抱歉", "不好意思", "我错了", "sorry")

    /** Feed it only my own outgoing messages. */
    fun observe(s: Style, text: String) {
        s.msgs++
        s.chars += text.length
        s.emoji += text.codePoints().filter { isEmoji(it) }.count().toInt()
        if (text.contains('？') || text.contains('?')) s.questions++
        if (APOLOGY.any { text.contains(it, ignoreCase = true) }) s.apologies++
    }

    private fun isEmoji(cp: Int): Boolean =
        cp in 0x1F300..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x1F000..0x1F2FF

    fun styleSummary(s: Style): String? {
        if (s.msgs < 3) return null
        val pct = { n: Int -> "${n * 100 / s.msgs}%" }
        return L.t(
            "平均 ${s.chars / s.msgs} 字，带表情 ${pct(s.emoji.coerceAtMost(s.msgs))}，" +
                "问句 ${pct(s.questions)}，道歉 ${pct(s.apologies)}（共 ${s.msgs} 条）",
            "avg ${s.chars / s.msgs} chars, emoji ${pct(s.emoji.coerceAtMost(s.msgs))}, " +
                "questions ${pct(s.questions)}, apologies ${pct(s.apologies)} (${s.msgs} messages)")
    }

    /** Fold another record's style into this one (linking the same person across apps). */
    fun mergeStyle(into: Style, from: Style) {
        into.msgs += from.msgs; into.chars += from.chars; into.emoji += from.emoji
        into.questions += from.questions; into.apologies += from.apologies
    }

    fun saveStyle(s: Style) = "${s.msgs}\t${s.chars}\t${s.emoji}\t${s.questions}\t${s.apologies}"

    fun loadStyle(text: String): Style {
        val p = text.split('\t').map { it.toIntOrNull() ?: 0 }
        return if (p.size < 5) Style() else Style(p[0], p[1], p[2], p[3], p[4])
    }

    // ---- what happened before ----

    data class Turn(val intent: String, val danger: Int, val action: String)

    const val HISTORY = 8

    fun push(history: List<Turn>, t: Turn): List<Turn> = (history + t).takeLast(HISTORY)

    fun historySummary(history: List<Turn>): String? {
        if (history.isEmpty()) return null
        return history.takeLast(5).joinToString(L.t("；", "; ")) {
            L.t("${it.intent}/危${it.danger}/${it.action}", "${L.label(it.intent)} / risk ${it.danger} / ${L.label(it.action)}")
        }
    }

    fun saveHistory(history: List<Turn>): String =
        history.joinToString("\n") { "${it.intent}\t${it.danger}\t${it.action}" }

    fun loadHistory(text: String): List<Turn> = text.lineSequence()
        .filter { it.isNotBlank() }
        .mapNotNull {
            val p = it.split('\t')
            if (p.size < 3) null else Turn(p[0], p[1].toIntOrNull() ?: 0, p[2])
        }
        .toList()
        .takeLast(HISTORY)
}
