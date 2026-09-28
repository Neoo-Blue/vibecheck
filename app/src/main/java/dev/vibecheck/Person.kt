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
        // Names like "🌙", "❤️" or "Dory🐟" are legitimate; clock and icon noise is not, so every
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
     * bottom within the strip: the shape drawn across the middle of the bar, with whatever touches
     * it. For a name OCR cannot read, this picture is how it is shown. Null when nothing stands
     * out there.
     *
     * Only a shape reaching into the middle counts, and not one that runs off the strip's top or
     * bottom edge: the strip reaches a little past the bar, and a photo right under the title bar
     * once filled more of it than the emoji name did. A thin strip of that photo was cut out as
     * the name, and the card's title read "…".
     */
    fun nameBox(px: IntArray, w: Int, h: Int): IntArray? {
        if (w < 8 || h < 8 || px.size < w * h) return null
        fun far(a: Int, b: Int) = kotlin.math.abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) +
            kotlin.math.abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) + kotlin.math.abs((a and 0xFF) - (b and 0xFF)) > 90
        // Each row against its own commonest colour, the bar's, whatever else crosses the row.
        val ink = BooleanArray(w * h)
        val counts = HashMap<Int, Int>()
        for (y in 0 until h) {
            counts.clear()
            for (x in 0 until w step 2) counts.merge(px[y * w + x] and 0xF8F8F8, 1, Int::plus)
            val common = counts.maxByOrNull { it.value }!!.key
            val bg = px[y * w + (0 until w).first { (px[y * w + it] and 0xF8F8F8) == common }]
            for (x in 0 until w) if (far(px[y * w + x], bg)) ink[y * w + x] = true
        }
        // The shapes reaching into the middle fifth, each grown across gaps of a pixel or two
        // (between the strokes of a glyph, or two emoji side by side).
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var best: IntArray? = null
        var bestSize = 0
        for (y in 0 until h) for (x in w * 2 / 5 until w * 3 / 5) {
            val start = y * w + x
            if (!ink[start] || seen[start]) continue
            val box = intArrayOf(x, y, x, y)
            var size = 0
            var top = 0
            stack[top++] = start
            seen[start] = true
            while (top > 0) {
                val i = stack[--top]
                size++
                val ix = i % w
                val iy = i / w
                box[0] = minOf(box[0], ix); box[1] = minOf(box[1], iy)
                box[2] = maxOf(box[2], ix); box[3] = maxOf(box[3], iy)
                for (ny in maxOf(0, iy - REACH)..minOf(h - 1, iy + REACH)) {
                    for (nx in maxOf(0, ix - REACH)..minOf(w - 1, ix + REACH)) {
                        val j = ny * w + nx
                        if (ink[j] && !seen[j]) { seen[j] = true; stack[top++] = j }
                    }
                }
            }
            // Cut off by the strip's edge: it comes from under the bar (or the status bar above it).
            if (box[1] == 0 || box[3] == h - 1) continue
            if (size > bestSize) { bestSize = size; best = box }
        }
        val b = best ?: return null
        if (b[3] - b[1] + 1 < 6 || b[2] - b[0] + 1 < 6) return null
        return b
    }

    /** Gaps a shape is grown across, in pixels. */
    private const val REACH = 2

    /**
     * Could this be a picture of a name: at least [MIN_PICTURE_H] pixels tall and not far wider
     * than a few emoji. A kept picture that is not (a strip cut from under the title bar) is
     * thrown away, and the next clear looks take a new one.
     */
    fun plausiblePicture(w: Int, h: Int): Boolean = h >= MIN_PICTURE_H && w >= 6 && w <= h * 6

    private const val MIN_PICTURE_H = 12

    /** At most this many times as wide as it is tall when a name picture is drawn. */
    const val PICTURE_MAX_RATIO = 4

    /**
     * The size to draw a name picture of [w]x[h] in a line [height] pixels tall: that tall, or
     * shrunk so it is no wider than [PICTURE_MAX_RATIO] heights. A wide one took the whole title.
     */
    fun pictureSize(w: Int, h: Int, height: Int): Pair<Int, Int> {
        val width = height * w / h.coerceAtLeast(1)
        val max = height * PICTURE_MAX_RATIO
        return if (width <= max) width to height else max to (max * h / w.coerceAtLeast(1)).coerceAtLeast(1)
    }

    // ---- keeping the right picture of a name ----

    /**
     * The name band of the title bar (where [nameBox] looks, 4.5% to 10.5% down the window) has
     * something drawn across its middle: a notification, our card. Its picture and fingerprint
     * would be of that, not of the name.
     */
    fun titleCovered(over: List<Chat.Box>, win: Chat.Box): Boolean {
        val h = win.bottom - win.top
        val mid = win.top + (h * 0.075f).toInt()
        val band = Chat.Box(win.left + (win.width * 0.18f).toInt(), win.top + (h * 0.045f).toInt(), win.left + (win.width * 0.82f).toInt(), win.top + (h * 0.105f).toInt())
        return over.any { it.top <= mid && it.bottom > mid && Chat.overlaps(it, band) }
    }

    /** Brightness block means of a picture, [cols] x [rows]: enough to tell one name from another. */
    fun signature(px: IntArray, w: Int, h: Int, cols: Int = 12, rows: Int = 3): IntArray {
        val sum = LongArray(cols * rows)
        val n = IntArray(cols * rows)
        if (w <= 0 || h <= 0 || px.size < w * h) return IntArray(cols * rows)
        for (y in 0 until h) for (x in 0 until w) {
            val p = px[y * w + x]
            val i = (y * rows / h) * cols + (x * cols / w)
            sum[i] += (((p shr 16) and 0xFF) * 30 + ((p shr 8) and 0xFF) * 59 + (p and 0xFF) * 11) / 100
            n[i]++
        }
        return IntArray(cols * rows) { if (n[it] == 0) 0 else (sum[it] / n[it]).toInt() }
    }

    /** Two pictures of a name look alike: signatures (block means, 0..255) within a small average difference. */
    fun samePicture(a: IntArray, b: IntArray): Boolean =
        a.size == b.size && a.isNotEmpty() && a.indices.sumOf { kotlin.math.abs(a[it] - b[it]) } / a.size <= 14

    /** Pictures of one person's name seen lately that did not match the kept one. */
    class PictureVotes {
        var seen: IntArray? = null
        var count = 0
    }

    /**
     * Whether to keep a newly seen picture of someone's name. Once a notification sliding over the
     * title bar was cut out as the name and kept for good. Now a first picture waits for a second
     * look that agrees, and a kept one is replaced once three looks in a row agree with each other
     * and not with it.
     */
    fun keepPicture(kept: IntArray?, seen: IntArray, votes: PictureVotes): Boolean {
        if (kept != null && samePicture(kept, seen)) { votes.seen = null; votes.count = 0; return false }
        val before = votes.seen
        if (before != null && samePicture(before, seen)) votes.count++ else { votes.seen = seen; votes.count = 1 }
        if (votes.count < (if (kept == null) 2 else 3)) return false
        votes.seen = null
        votes.count = 0
        return true
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
