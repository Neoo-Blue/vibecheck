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
        // What apps call anyone: Soul labels every avatar "Souler", and taking that for a name
        // filed every Soul chat under one person.
        "Souler", "Soulmate", "匿名", "匿名用户", "神秘人", "用户", "对方", "好友", "联系人", "未知",
        "Unknown", "User", "TA", "Ta", "ta",
    )

    /** The presence line under a name in a chat header: WhatsApp, Telegram, Messenger, Instagram. */
    private val SUBTITLE = Regex(
        """(Active .*|Online|last seen.*|typing.*|\d+ (members|participants|subscribers|online).*|""" +
            """tap here for contact info|在线|(对方)?正在(输入|讲话|说话).*|最后上线.*|\d+ ?位?成员)""",
        RegexOption.IGNORE_CASE,
    )

    /** What WeChat and others put where the name was while the other person types. */
    private val TYPING = Regex("""((对方)?正在(输入|讲话|说话)|.*typing).*""", RegexOption.IGNORE_CASE)

    /** The title band shows a typing indicator instead of the name: the chat itself has not changed. */
    fun isTyping(titles: List<Pair<String, Chat.Box>>): Boolean = titles.any { TYPING.matches(it.first.trim()) }

    /**
     * The contact name from the chat title bar. Candidates are TextViews in the top band of the
     * window; the title is the one nearest the horizontal center. Falls back to an avatar's
     * content description, which WeChat sets to "<名字>头像".
     */
    fun peerName(
        titles: List<Pair<String, Chat.Box>>,
        avatarDescs: List<String>,
        win: Chat.Box,
        /** Accept a name made only of marks ("...", "。"): true where the app gives text, never for OCR, which reads an emoji as a stray mark. */
        symbols: Boolean = false,
    ): String? {
        val center = (win.left + win.right) / 2
        val title = titles
            .filter { looksLikeName(it.first) }
            .minByOrNull { kotlin.math.abs(it.second.centerX - center) }
        if (title != null) return title.first.trim()
        avatarDescs.asSequence()
            .filter { it.endsWith("头像") }
            .map { it.removeSuffix("头像").trim().removeSuffix("的").trim() }
            .firstOrNull { it.isNotEmpty() && (looksLikeName(it) || (symbols && looksLikeSymbolName(it))) }
            ?.let { return it }
        if (!symbols) return null
        return titles
            .filter { looksLikeSymbolName(it.first) }
            .minByOrNull { kotlin.math.abs(it.second.centerX - center) }
            ?.first?.trim()
    }

    /**
     * "...", "。", "～～": a name made of marks, which some people really choose (Soul is full of
     * them). Taken only when nothing in the title bar reads as an ordinary name, and never with a
     * digit in it: unread counts, clocks and battery levels are all digits.
     */
    fun looksLikeSymbolName(raw: String): Boolean {
        val s = raw.trim()
        if (s.isEmpty() || s in TITLE_JUNK || SUBTITLE.matches(s) || TYPING.matches(s)) return false
        val cps = s.filterNot { it.isWhitespace() }.codePoints().toArray()
        return cps.size in 1..12 && cps.none { Character.isDigit(it) }
    }

    /**
     * OCR of a title bar picks up clock digits and icon noise too, and anything accepted here
     * becomes a person in storage. A name is mostly letters; "M%。l 65" is not.
     */
    fun looksLikeName(raw: String): Boolean {
        val s = raw.trim()
        if (s.isEmpty() || s.length > 64 || s in TITLE_JUNK || SUBTITLE.matches(s)) return false
        // Counted in code points: one emoji is two chars, a family emoji eleven.
        val cps = s.filterNot { it.isWhitespace() }.codePoints().toArray()
        if (cps.isEmpty() || cps.size > 24) return false
        val letters = cps.count { Character.isLetter(it) }
        if (letters >= 2 && letters * 10 >= cps.size * 6) return true   // at least 60% letters
        // Names like "🍵", "❤️" or "Dory🐟" are legitimate; clock and icon noise is not, so every
        // character has to be a letter or part of an emoji: the pictograph itself, or the
        // variation selector, joiner or skin tone that goes with it.
        val pictographs = cps.count { isPictograph(it) }
        val joiners = cps.count { isEmojiPart(it) }
        return pictographs >= 1 && letters + pictographs + joiners >= cps.size
    }

    /** An emoji or an emoji-like symbol (❤, ☆, ✨), not counting the parts that travel with one. */
    private fun isPictograph(cp: Int): Boolean =
        !isEmojiPart(cp) && (isEmoji(cp) || Character.getType(cp) == Character.OTHER_SYMBOL.toInt())

    /** A name with emoji in it: the part OCR cannot read. */
    fun hasPictograph(s: String): Boolean = s.codePoints().anyMatch { isPictograph(it) }

    /** The text without its emoji: what OCR reads of a name like "欧欧🌸". */
    fun stripEmoji(s: String): String = buildString {
        s.codePoints().forEach { if (!isPictograph(it) && !isEmojiPart(it)) appendCodePoint(it) }
    }.trim()

    // ---- names from notifications ----

    /** "[3条]", "(3)" and the like in front of a message notification. */
    private val UNREAD = Regex("""^\s*(\[\d+条]|\(\d+\)|（\d+）|\d+ new messages?:?)\s*""", RegexOption.IGNORE_CASE)

    /** QQ's "欧欧 (3条新消息)" after the name. */
    private val TITLE_COUNT = Regex("""\s*[(（]\d+\s*条(新消息)?[)）]\s*$""")

    /** The app speaking for itself: "你收到了 3 条消息", "5 new messages". */
    private val SUMMARY = Regex("""收到了?\s*\d+\s*条|\d+\s*条新消息|\d+ new messages|new messages from""", RegexOption.IGNORE_CASE)

    /**
     * Who a message notification is from, as the app writes the name (emoji and all), and what it
     * says. WeChat puts "[2条]" in front when several are waiting, and sometimes the sender's name
     * too. Null for the app's own summaries and anything without both parts.
     */
    fun fromNotification(title: String, text: String): Pair<String, String>? {
        val name = title.replace(TITLE_COUNT, "").trim()
        var msg = text.replace(UNREAD, "").trim()
        if (name.isEmpty() || msg.isEmpty() || name.length > 64 || name in TITLE_JUNK) return null
        if (SUMMARY.containsMatchIn(name) || SUMMARY.containsMatchIn(msg)) return null
        for (sep in listOf(": ", "：", ":")) if (msg.startsWith(name + sep)) { msg = msg.removePrefix(name + sep).trim(); break }
        return if (msg.isEmpty()) null else name to msg
    }

    /**
     * The full name of a chat whose name OCR could not read in full, from a notification of theirs.
     * Only names with emoji qualify: OCR reads everything else. One qualifies when what OCR did
     * read ([readName]) is that name without its emoji ("欧欧" for "欧欧🌸"), or, for a name that is
     * nothing but emoji ([readName] null), when the notification's message is on screen now.
     */
    fun nameFromNotifications(readName: String?, onScreen: List<String>, heard: List<Pair<String, String>>): String? {
        fun key(x: String) = x.filterNot { it.isWhitespace() }.lowercase()
        for ((name, msg) in heard.asReversed()) {
            if (!hasPictograph(name)) continue
            val bare = stripEmoji(name)
            if (readName != null) {
                if (bare.isNotEmpty() && key(bare) == key(readName)) return name
            } else if (bare.isEmpty() && msg.length >= 2 && onScreen.any { Chat.similar(it, msg) }) return name
        }
        return null
    }

    /**
     * Where the name sits in a strip of the title bar (pixels, row by row), as left, top, right,
     * bottom within the strip: the densest band of rows that stand out from the bar's own colour,
     * and the columns they cover. For a name OCR cannot read, this picture is how it is shown.
     * Null when nothing stands out.
     */
    fun nameBox(px: IntArray, w: Int, h: Int): IntArray? {
        if (w < 8 || h < 8 || px.size < w * h) return null
        fun far(a: Int, b: Int) = kotlin.math.abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) +
            kotlin.math.abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) + kotlin.math.abs((a and 0xFF) - (b and 0xFF)) > 90
        val hits = IntArray(h) { y -> val bg = px[y * w]; (0 until w).count { far(px[y * w + it], bg) } }
        var best: IntRange? = null
        var bestSum = 0
        var start = -1
        var sum = 0
        var gap = 0
        for (y in 0..h) {
            if (y < h && hits[y] >= 2) {
                if (start < 0) start = y
                sum += hits[y]
                gap = 0
            } else if (start >= 0) {
                gap++
                if (gap > 3 || y == h) {
                    if (sum > bestSum) { bestSum = sum; best = start..(y - gap) }
                    start = -1; sum = 0; gap = 0
                }
            }
        }
        val rows = best ?: return null
        if (rows.last - rows.first + 1 < 6) return null
        var left = w
        var right = -1
        for (y in rows) {
            val bg = px[y * w]
            for (x in 0 until w) if (far(px[y * w + x], bg)) { left = minOf(left, x); right = maxOf(right, x) }
        }
        if (right - left + 1 < 6) return null
        return intArrayOf(left, rows.first, right, rows.last)
    }

    /** Code points that only ever travel inside an emoji: ZWJ, variation selectors, keycap, skin tones, tags. */
    private fun isEmojiPart(cp: Int): Boolean =
        cp == 0x200D || cp in 0xFE00..0xFE0F || cp == 0x20E3 || cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F

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

    private val APOLOGY = listOf(
        "对不起", "抱歉", "不好意思", "我错了", "对不住", "原谅我",
        "sorry", "sry", "my bad", "apologi", "forgive me",
    )

    /**
     * WeChat and QQ send their built-in faces as text codes: [微笑], [捂脸], [Smile]. Only the real
     * codes count: "[图片]", "[链接]" and "[Photo]" are placeholders, not a way of writing.
     */
    private val TEXT_FACE_CODES: Set<String> = (
        "微笑 撇嘴 色 发呆 得意 流泪 害羞 闭嘴 睡 大哭 尴尬 发怒 调皮 呲牙 惊讶 难过 囧 抓狂 吐 偷笑 愉快 白眼 " +
            "傲慢 困 惊恐 憨笑 悠闲 咒骂 疑问 嘘 晕 衰 骷髅 敲打 再见 擦汗 抠鼻 鼓掌 坏笑 左哼哼 右哼哼 哈欠 鄙视 " +
            "委屈 快哭了 阴险 亲亲 可怜 笑脸 生病 脸红 破涕为笑 恐惧 失望 无语 嘿哈 捂脸 奸笑 机智 皱眉 耶 吃瓜 加油 " +
            "汗 天啊 社会社会 旺柴 好的 打脸 哇 翻白眼 666 让我看看 叹气 苦涩 裂开 嘴唇 爱心 心碎 拥抱 强 弱 握手 " +
            "胜利 抱拳 勾引 拳头 合十 啤酒 咖啡 蛋糕 玫瑰 凋谢 菜刀 炸弹 便便 月亮 太阳 庆祝 礼物 红包 發 福 烟花 " +
            "爆竹 猪头 跳跳 发抖 转圈 流汗 奋斗 饥饿 酷 冷汗 疯了 糗大了 吓 爱你 飞吻 差劲 " +
            "Smile Grimace Drool Scowl CoolGuy Sob Shy Silent Sleep Cry Awkward Angry Tongue Grin Surprise Frown " +
            "Ruthless Blush Scream Puke Chuckle Joyful Slight Smug Hungry Drowsy Panic Sweat Laugh Commando Determined " +
            "Scold Shocked Shhh Dizzy Tormented Toasted Skull Hammer Wave Speechless NosePick Clap Shame Trick Yawn " +
            "Pooh-pooh Shrunken TearingUp Sly Kiss Wrath Whimper Cleaver Beer Coffee Pig Rose Wilt Lips Heart " +
            "BrokenHeart Cake Bomb Poop Moon Sun Gift Hug ThumbsUp ThumbsDown Shake Peace Fight Beckon Fist OK " +
            "InLove Blowkiss Tremble Twirl Hey Facepalm Smirk Smart Concerned Yeah! Onlooker GoForIt Sweats OMG Emm " +
            "Respect Doge NoProb MyBad Wow Boring Awesome LetMeSee Sigh Hurt Broken Party Firecracker Fireworks"
        ).split(' ').filter { it.isNotEmpty() }.toSet()

    private val BRACKETED = Regex("""\[([^\[\]\s]{1,10})]""")

    /** Feed it only my own outgoing messages. */
    fun observe(s: Style, text: String) {
        s.msgs++
        s.chars += text.length
        // Messages that carry an emoji, not emoji characters: "带表情 x%" is a share of messages.
        if (hasEmoji(text)) s.emoji++
        if (text.contains('？') || text.contains('?')) s.questions++
        if (APOLOGY.any { text.contains(it, ignoreCase = true) }) s.apologies++
    }

    fun hasEmoji(text: String): Boolean =
        text.codePoints().anyMatch { isEmoji(it) } ||
            BRACKETED.findAll(text).any { it.groupValues[1] in TEXT_FACE_CODES }

    private fun isEmoji(cp: Int): Boolean =
        cp in 0x1F300..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x1F000..0x1F2FF ||
            cp in 0x2B50..0x2B55 || cp in 0x23E9..0x23FA || cp in 0x231A..0x231B

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

    // ---- the newest lines already counted (see Chat.sync) ----

    fun saveTail(tail: List<Pair<String, String>>): String =
        tail.joinToString("\n") { (who, text) -> "$who\t${text.replace('\n', ' ').replace('\t', ' ')}" }

    fun loadTail(text: String): List<Pair<String, String>> = text.lineSequence()
        .filter { it.isNotBlank() }
        .mapNotNull { l -> l.split('\t', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
        .toList()
        .takeLast(Chat.TAIL)
}
