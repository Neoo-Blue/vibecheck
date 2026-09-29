package dev.vibecheck

/**
 * Who you are talking to, how you talk to them, and what happened last time.
 * Pure and Android-free so it unit tests on the JVM; persistence lives in PersonStore.
 */
object Person {

    fun id(pkg: String, name: String): String = "$pkg|${name.trim()}"

    // ---- identity ----

    /**
     * Screens inside the apps with a title where a chat has its name: WeChat's own pages and the
     * photo picker. Such a screen is not a chat, and its title was once kept as a person
     * ("Moments", 「相机胶卷」). Only titles no chat could have: a nickname can be almost anything.
     */
    private val SCREENS = setOf(
        "Moments", "朋友圈", "Official Account", "Official Accounts", "公众号", "服务号", "订阅号消息",
        "Service Accounts", "Channels", "视频号", "Top Stories", "看一看", "Mini Programs", "小程序",
        "相机胶卷", "Camera Roll", "所有照片", "最近项目", "图片和视频", "All photos",
    )

    // App-level titles, tab labels and buttons in a chat's title bar: these are not people.
    private val TITLE_JUNK = SCREENS + setOf(
        "返回", "取消", "更多", "聊天信息", "微信", "通讯录", "发现", "我",
        "WeChat", "Chats", "Contacts", "Discover", "Me", "Soul", "消息", "搜索", "Search",
        "Messenger", "Messages", "WhatsApp", "Telegram", "Instagram", "LINE", "Signal", "Discord",
        "Snapchat", "QQ", "KakaoTalk", "Viber", "X", "Teams", "Slack", "Calls", "Stories", "People",
        "Back", "Home", "New message", "New chat", "Select contact", "Chat",
        // What apps call anyone: Soul labels every avatar "Souler", and taking that for a name
        // filed every Soul chat under one person.
        "Souler", "Soulmate", "匿名", "匿名用户", "神秘人", "用户", "对方", "好友", "联系人", "未知",
        "Unknown", "User", "TA", "Ta", "ta",
        // Soul's follow button beside a name made of marks, which it outranked as a name.
        "关注", "已关注", "+关注", "互相关注", "Follow", "Following",
        // A clock's timer, read as a chat by versions up to 6.8.9 (see Apps.inFront).
        "Timer", "计时器", "Stopwatch", "秒表",
    )

    /**
     * The presence line under a name in a chat header (WhatsApp, Telegram, Messenger, Instagram),
     * and when someone was last there: 「1分钟前」 beside a Soul name was once kept as a person.
     */
    private val SUBTITLE = Regex(
        """(Active .*|Online|last seen.*|typing.*|\d+ (members|participants|subscribers|online).*|""" +
            """tap here for contact info|just now|yesterday|\d+\s*(s|sec|secs|seconds?|m|min|mins|minutes?|h|hr|hrs|hours?|d|days?|w|weeks?)\s+ago|""" +
            """在线|(对方)?正在(输入|讲话|说话).*|最后上线.*|\d+ ?位?成员|刚刚(在线|来过|活跃)|当前在线|近期互动|""" +
            """\d+\s*(秒|分钟|小时|天|周|个月)前(在线|来过|活跃)?|(今天|昨天|前天)(在线|来过|活跃)|最近在线)""",
        RegexOption.IGNORE_CASE,
    )

    /** A title as it is compared with the lists above: without a count or an arrow after it ("Timer (5 m)", 「相机胶卷 ▾」). */
    private fun bare(s: String): String =
        s.trim().replace(TRAILING_COUNT, "").trimEnd { it.isWhitespace() || it in DECORATIONS }

    private val TRAILING_COUNT = Regex("""\s*[(（][^()（）]{0,12}[)）]$""")
    private const val DECORATIONS = "….·⌄∨▼▾˅›>〉»↓"

    /** A title that is a screen of the app rather than someone's chat (see [SCREENS]). */
    fun notAChat(titles: List<Pair<String, Chat.Box>>): Boolean = titles.any { bare(it.first) in SCREENS }

    /**
     * A name kept by an older version that was never anyone's: a screen's title, a button, a time
     * or a presence line from the title bar.
     */
    fun isLabel(name: String): Boolean {
        val s = name.trim()
        return bare(s) in TITLE_JUNK || SUBTITLE.matches(s) || TYPING.matches(s)
    }

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
        val names = titles.filter { looksLikeName(it.first) }
        // A line under the name, over the same place (Telegram's "recording audio…", Instagram's
        // username), is about them: the name is the one on top.
        val title = names
            .filterNot { (_, b) -> names.any { (_, o) -> o !== b && o.bottom <= b.top + 4 && o.left < b.right && b.left < o.right } }
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
        if (s.isEmpty() || isLabel(s)) return false
        val cps = s.filterNot { it.isWhitespace() }.codePoints().toArray()
        return cps.size in 1..12 && cps.none { Character.isDigit(it) }
    }

    /**
     * OCR of a title bar picks up clock digits and icon noise too, and anything accepted here
     * becomes a person in storage. A name is mostly letters; "M%。l 65" is not.
     */
    fun looksLikeName(raw: String): Boolean {
        val s = raw.trim()
        if (s.isEmpty() || s.length > 64 || bare(s) in TITLE_JUNK || SUBTITLE.matches(s)) return false
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

    /**
     * A name of one letter ("J", 「雪」), which [looksLikeName] leaves out: OCR reads a stray mark
     * or an emoji as a letter just as easily. The one letter centred in the title bar, which the
     * caller takes for the name only when the picture of the title has no colour, as words have
     * none. Such a chat went as one whose name is an emoji, and showed as 「未命名联系人」.
     */
    fun oneLetterName(titles: List<Pair<String, Chat.Box>>, win: Chat.Box): String? {
        val center = (win.left + win.right) / 2
        return titles.map { it.first.trim() to it.second }
            .filter { (t, b) ->
                t.codePointCount(0, t.length) == 1 && Character.isLetter(t.codePointAt(0)) && !isLabel(t) &&
                    kotlin.math.abs(b.centerX - center) <= win.width * ONE_LETTER_CENTRED
            }
            .singleOrNull()?.first
    }

    /** How far from the middle of the window a one-letter name can sit, as a share of its width. */
    private const val ONE_LETTER_CENTRED = 0.05

    /** An emoji or an emoji-like symbol (❤, ☆, ✨), not counting the parts that travel with one. */
    private fun isPictograph(cp: Int): Boolean =
        !isEmojiPart(cp) && (isEmoji(cp) || Character.getType(cp) == Character.OTHER_SYMBOL.toInt())

    /** A name with emoji in it: the part OCR cannot read. */
    fun hasPictograph(s: String): Boolean = s.codePoints().anyMatch { isPictograph(it) }

    /** The text without its emoji: what OCR reads of a name like "李四🌸". */
    fun stripEmoji(s: String): String = buildString {
        s.codePoints().forEach { if (!isPictograph(it) && !isEmojiPart(it)) appendCodePoint(it) }
    }.trim()

    // ---- names from notifications ----

    /** "[3条]", "(3)" and the like in front of a message notification. */
    private val UNREAD = Regex("""^\s*(\[\d+条]|\(\d+\)|（\d+）|\d+ new messages?:?)\s*""", RegexOption.IGNORE_CASE)

    /** QQ's "李四 (3条新消息)" after the name. */
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
     * read ([readName]) is that name without its emoji ("李四" for "李四🌸"), or, for a name that is
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

    /**
     * A picture of a name by its shape alone, whatever the colours: dark letters on a light bar
     * and light ones on a dark bar give the same. Over the part drawn on the picture's commonest
     * colour (its background), [INK_COLS] x [INK_ROWS] shares of drawn pixels (0..255), then that
     * part's width per 100 of its height. Null when nothing is drawn.
     */
    fun inkSignature(px: IntArray, w: Int, h: Int): IntArray? {
        if (w <= 0 || h <= 0 || px.size < w * h) return null
        val counts = HashMap<Int, Int>()
        for (i in 0 until w * h) counts.merge(px[i] and 0xF0F0F0, 1, Int::plus)
        val bucket = counts.maxByOrNull { it.value }!!.key
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (i in 0 until w * h) {
            val p = px[i]
            if ((p and 0xF0F0F0) != bucket) continue
            r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
        }
        val br = (r / n).toInt(); val bg = (g / n).toInt(); val bb = (b / n).toInt()
        val ink = BooleanArray(w * h) { i ->
            val p = px[i]
            kotlin.math.abs(((p shr 16) and 0xFF) - br) + kotlin.math.abs(((p shr 8) and 0xFF) - bg) + kotlin.math.abs((p and 0xFF) - bb) > 90
        }
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (y in 0 until h) for (x in 0 until w) if (ink[y * w + x]) {
            x0 = minOf(x0, x); y0 = minOf(y0, y); x1 = maxOf(x1, x); y1 = maxOf(y1, y)
        }
        if (x1 < 0) return null
        val bw = x1 - x0 + 1
        val bh = y1 - y0 + 1
        val drawn = IntArray(INK_COLS * INK_ROWS)
        val all = IntArray(INK_COLS * INK_ROWS)
        for (y in y0..y1) for (x in x0..x1) {
            val i = ((y - y0) * INK_ROWS / bh) * INK_COLS + (x - x0) * INK_COLS / bw
            all[i]++
            if (ink[y * w + x]) drawn[i]++
        }
        return IntArray(INK_COLS * INK_ROWS + 1) { i ->
            if (i == INK_COLS * INK_ROWS) bw * 100 / bh else if (all[i] == 0) 0 else drawn[i] * 255 / all[i]
        }
    }

    /** The same shape ([inkSignature]): as wide for its height within a fifth, and drawn alike cell by cell. */
    fun sameInk(a: IntArray, b: IntArray): Boolean {
        val cells = INK_COLS * INK_ROWS
        if (a.size != cells + 1 || b.size != cells + 1) return false
        val ra = a[cells].coerceAtLeast(1)
        val rb = b[cells].coerceAtLeast(1)
        if (maxOf(ra, rb) * 5 > minOf(ra, rb) * 6) return false
        return (0 until cells).sumOf { kotlin.math.abs(a[it] - b[it]) } / cells <= INK_SAME
    }

    private const val INK_COLS = 12
    private const val INK_ROWS = 4
    private const val INK_SAME = 28

    /**
     * A picture of a name (an emoji) by what light and dark mode leave alone: where its coloured
     * pixels are and what colour they are, on a [EMOJI_GRID] x [EMOJI_GRID] grid laid over the
     * coloured part. The title bar is grey in both modes, and so are the white and black parts of
     * an emoji, which is why those are left out; and the grid is laid over the coloured part
     * rather than the whole picture, because where the picture is cut depends on the mode (white
     * next to a light bar does not stand out). Per cell: the share of coloured pixels, then their
     * mean red, green and blue. Null with too little colour to go on (a grey emoji, text).
     */
    fun emojiPrint(px: IntArray, w: Int, h: Int): IntArray? {
        if (w <= 0 || h <= 0 || px.size < w * h) return null
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        var count = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (!coloured(px[y * w + x])) continue
            count++
            x0 = minOf(x0, x); y0 = minOf(y0, y); x1 = maxOf(x1, x); y1 = maxOf(y1, y)
        }
        if (count * 33 < w * h || x1 - x0 < 4 || y1 - y0 < 4) return null
        val g = EMOJI_GRID
        val bw = x1 - x0 + 1
        val bh = y1 - y0 + 1
        val all = IntArray(g * g)
        val n = IntArray(g * g)
        val sum = LongArray(g * g * 3)
        for (y in y0..y1) for (x in x0..x1) {
            val i = ((y - y0) * g / bh) * g + (x - x0) * g / bw
            all[i]++
            val p = px[y * w + x]
            if (!coloured(p)) continue
            n[i]++
            sum[i * 3] += (p shr 16) and 0xFF
            sum[i * 3 + 1] += (p shr 8) and 0xFF
            sum[i * 3 + 2] += p and 0xFF
        }
        return IntArray(g * g * 4) { k ->
            val i = k / 4
            when (k % 4) {
                0 -> if (all[i] == 0) 0 else n[i] * 255 / all[i]
                else -> if (n[i] == 0) 0 else (sum[i * 3 + k % 4 - 1] / n[i]).toInt()
            }
        }
    }

    private fun coloured(p: Int): Boolean {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return maxOf(r, g, b) - minOf(r, g, b) > 48
    }

    private const val EMOJI_GRID = 6

    /**
     * How far apart two [emojiPrint]s are, 0 to 255: per cell, how differently much of it is
     * coloured, plus, where both are, how different the colours are.
     */
    fun emojiDistance(a: IntArray, b: IntArray): Int {
        if (a.size != b.size || a.isEmpty() || a.size % 4 != 0) return Int.MAX_VALUE
        var sum = 0L
        val cells = a.size / 4
        for (i in 0 until cells) {
            val ca = a[i * 4]
            val cb = b[i * 4]
            var d = kotlin.math.abs(ca - cb)
            if (ca >= 32 && cb >= 32) d += (1..3).sumOf { kotlin.math.abs(a[i * 4 + it] - b[i * 4 + it]) } / 3
            sum += d
        }
        return (sum / cells).toInt()
    }

    /** The same emoji: [emojiDistance] within [EMOJI_SAME]. */
    fun sameEmoji(a: IntArray, b: IntArray): Boolean = emojiDistance(a, b) <= EMOJI_SAME

    const val EMOJI_SAME = 20

    /**
     * Whose name this picture is, among [kept] (each person's kept picture, as an [emojiPrint]):
     * the one person it matches, or [current] if theirs is one of the matches (scrolling in their
     * chat). Two others alike, or none: nobody. The title bar is all there is to go on when none
     * of their messages is on screen, and a wrong guess files the chat under someone else.
     */
    fun pictureOwner(seen: IntArray, kept: List<Pair<String, IntArray>>, current: String? = null): String? {
        val alike = kept.filter { sameEmoji(seen, it.second) }.map { it.first }.distinct()
        return when {
            current != null && current in alike -> current
            alike.size == 1 -> alike[0]
            else -> null
        }
    }

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
     * every scan minting a fresh person (the phone had 277 of them). Fingerprints of different
     * kinds ([avatarHash], [insideHash], the older [hashOf]) are never alike: they are of
     * different regions, or grids.
     */
    fun hamming(a: String, b: String): Int {
        if (isAvatarHash(a) || isAvatarHash(b)) return Int.MAX_VALUE
        val va = a.startsWith(INSIDE)
        if (va != b.startsWith(INSIDE)) return Int.MAX_VALUE
        val x = a.removePrefix(if (va) INSIDE else "#").toLongOrNull(16) ?: return Int.MAX_VALUE
        val y = b.removePrefix(if (va) INSIDE else "#").toLongOrNull(16) ?: return Int.MAX_VALUE
        return java.lang.Long.bitCount(x xor y)
    }

    /**
     * The fingerprint of the inside of an avatar: the mean colour of each block of a 4 x 4 grid
     * ([rgb]: the 16 reds, then the greens, then the blues). The fingerprints before it kept 15
     * bits of which block was brighter than the next, in grey: read a few pixels off, the same
     * avatar lost up to 4 of them, one more than allowed, while one pair in eight of different
     * photo-like avatars came within the 3 allowed. The colours of the blocks moved by at most 8
     * (of 255) for the same avatar read 4 pixels off, and no two different ones came within 15.
     * "" for a region with nothing in it: a wall of one colour is nobody's face.
     */
    fun avatarHash(rgb: IntArray): String {
        if (rgb.size != AVATAR_VALUES || rgb.any { it !in 0..255 }) return ""
        val grey = IntArray(16) { (rgb[it] * 30 + rgb[16 + it] * 59 + rgb[32 + it] * 11) / 100 }
        if (grey.max() - grey.min() <= 6) return ""
        return AVATAR + rgb.joinToString("") { "%02x".format(it) }
    }

    /** How far apart two [avatarHash]es are: the mean difference of their colours, 0 to 255. */
    fun avatarDistance(a: String, b: String): Int {
        val x = avatarValues(a) ?: return Int.MAX_VALUE
        val y = avatarValues(b) ?: return Int.MAX_VALUE
        return x.indices.sumOf { kotlin.math.abs(x[it] - y[it]) } / x.size
    }

    private fun avatarValues(s: String): IntArray? {
        if (!isAvatarHash(s) || s.length != AVATAR.length + AVATAR_VALUES * 2) return null
        val out = IntArray(AVATAR_VALUES)
        for (i in out.indices) out[i] = s.substring(AVATAR.length + i * 2, AVATAR.length + i * 2 + 2).toIntOrNull(16) ?: return null
        return out
    }

    fun isAvatarHash(name: String) = name.startsWith(AVATAR)

    /** How far one look at an avatar may land from another and still be the same avatar ([avatarDistance]). */
    const val AVATAR_SAME = 12

    /**
     * How far apart two fingerprints are, in their own measure: [avatarDistance] for the newest
     * kind, [hamming] bits for the older ones. Different kinds are never near.
     */
    fun distance(a: String, b: String): Int =
        if (isAvatarHash(a) || isAvatarHash(b)) avatarDistance(a, b) else hamming(a, b)

    private const val AVATAR = "#v3"
    private const val AVATAR_VALUES = 48

    /**
     * The fingerprint of the inside of an avatar, a picture light and dark mode leave as it is.
     * The older one ([hashOf] over a strip from the screen's edge) took in the chat's background
     * beside the avatar, which is what dark mode changes: in the other mode the same person was
     * someone new, with nothing learned. Marked apart from those, which name people seen before.
     */
    fun insideHash(samples: IntArray): String = hashOf(samples).takeIf { it.isNotEmpty() }?.let { INSIDE + it.removePrefix("#") } ?: ""

    fun isInsideHash(name: String) = name.startsWith(INSIDE)

    private const val INSIDE = "#v2"

    /** How much is known about someone, to decide which of two records of one person to keep. */
    class Known(val name: String, val learned: Int, val seen: Int)

    /**
     * Two records found for one chat (the same person seen in light and in dark mode, before
     * fingerprints left the background out): the one with a history read, else the one with
     * more seen, else the first.
     */
    fun better(a: Known, b: Known): Known = when {
        (a.learned > 0) != (b.learned > 0) -> if (a.learned > 0) a else b
        a.learned != b.learned -> if (a.learned > b.learned) a else b
        b.seen > a.seen -> b
        else -> a
    }

    fun isFingerprint(name: String) = name.startsWith("#")

    // ---- how I talk, and how they do ----

    data class Style(
        var msgs: Int = 0,
        var chars: Int = 0,
        var emoji: Int = 0,
        var questions: Int = 0,
        var apologies: Int = 0,
        /** Messages that laugh or joke: 哈哈, hhh, 笑死, lol, 😂. */
        var laughs: Int = 0,
    )

    private val LAUGH = Regex("""哈{2,}|嘿嘿|嘻嘻|笑死|hh{2,}|haha|lol\b|lmao|😂|🤣|😆|😹|\[(?:偷笑|破涕为笑|捂脸|憨笑|坏笑|奸笑|Chuckle|Laugh|Facepalm|Smirk)]""", RegexOption.IGNORE_CASE)

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

    /** One message of one side: mine into my style, theirs into theirs. */
    fun observe(s: Style, text: String) {
        s.msgs++
        s.chars += text.length
        // Messages that carry an emoji, not emoji characters: "带表情 x%" is a share of messages.
        if (hasEmoji(text)) s.emoji++
        if (text.contains('？') || text.contains('?')) s.questions++
        if (APOLOGY.any { text.contains(it, ignoreCase = true) }) s.apologies++
        if (LAUGH.containsMatchIn(text)) s.laughs++
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

    /**
     * How they write, for the judge and the drafts: what is normal for this person, so a short
     * 「嗯」 from someone who always writes short is not read as cold, nor a joke as an attack.
     * The traits first, then the numbers they rest on. Null until there is enough to go on.
     */
    fun theirStyleSummary(s: Style): String? {
        if (s.msgs < 5) return null
        val pct = { n: Int -> n.coerceAtMost(s.msgs) * 100 / s.msgs }
        val avg = s.chars / s.msgs
        val numbers = L.t(
            "平均 $avg 字，带表情 ${pct(s.emoji)}%，问句 ${pct(s.questions)}%，笑 ${pct(s.laughs)}%（共 ${s.msgs} 条）",
            "avg $avg chars, emoji ${pct(s.emoji)}%, questions ${pct(s.questions)}%, laughing ${pct(s.laughs)}% (${s.msgs} messages)")
        return (traits(s) + numbers).joinToString(L.t("；", "; "))
    }

    /**
     * How they write, for the judge: what stands out, without the numbers under it, which move
     * with every message and so change every request while telling a single turn nothing more.
     * Null when nothing stands out.
     */
    fun theirTraits(s: Style): String? =
        if (s.msgs < 5) null else traits(s).joinToString(L.t("；", "; ")).ifEmpty { null }

    private fun traits(s: Style): List<String> {
        val pct = { n: Int -> n.coerceAtMost(s.msgs) * 100 / s.msgs }
        val avg = s.chars / s.msgs
        return listOfNotNull(
            when {
                avg <= 6 -> L.t("话很短，简短的回复是 Ta 的常态", "writes very short; brief replies are normal for them")
                avg >= 25 -> L.t("话多，常发长消息", "writes a lot, often long messages")
                else -> null
            },
            if (pct(s.laughs) >= 25) L.t("常笑、常开玩笑", "laughs and jokes a lot") else null,
            when {
                pct(s.emoji) >= 40 -> L.t("爱用表情", "uses a lot of emoji")
                pct(s.emoji) <= 5 -> L.t("几乎不用表情", "hardly uses emoji")
                else -> null
            },
            if (pct(s.questions) >= 30) L.t("常问问题", "asks a lot of questions") else null,
        )
    }

    /** Fold another record's style into this one (linking the same person across apps). */
    fun mergeStyle(into: Style, from: Style) {
        into.msgs += from.msgs; into.chars += from.chars; into.emoji += from.emoji
        into.questions += from.questions; into.apologies += from.apologies; into.laughs += from.laughs
    }

    fun saveStyle(s: Style) = "${s.msgs}\t${s.chars}\t${s.emoji}\t${s.questions}\t${s.apologies}\t${s.laughs}"

    fun loadStyle(text: String): Style {
        val p = text.split('\t').map { it.toIntOrNull() ?: 0 }
        return if (p.size < 5) Style() else Style(p[0], p[1], p[2], p[3], p[4], p.getOrElse(5) { 0 })
    }

    // ---- how a turn with them usually reads ----

    /**
     * How this person usually comes across, from every turn judged: what they are mostly doing,
     * and how tense a turn with them usually reads. A running average that settles as the chat
     * goes on and still follows a change: the first turns count fully, and past [NORM_WINDOW]
     * each new turn moves it by 1/[NORM_WINDOW].
     */
    /**
     * How a turn with them usually reads: [intents] and [danger] as Jev read them, and the
     * relationship ([situations], Relationship.KEYS) as each turn that asked it was answered,
     * [asked] times so far, the last time [since] turns ago (see [steadySituation]).
     */
    class Norm(
        var turns: Int = 0,
        var danger: Double = 0.0,
        val intents: LinkedHashMap<String, Double> = LinkedHashMap(),
        val situations: LinkedHashMap<String, Double> = LinkedHashMap(),
        var asked: Int = 0,
        var since: Int = 0,
    )

    const val NORM_WINDOW = 40
    private const val NORM_MIN = 5

    /** One more judged turn: its intent (Jev's top answer) and its danger (0..1, as Jev read it). */
    fun observeNorm(n: Norm, intent: String, danger: Double) {
        n.turns++
        val w = 1.0 / minOf(n.turns, NORM_WINDOW)
        n.danger += (danger.coerceIn(0.0, 1.0) - n.danger) * w
        for (k in n.intents.keys.toList()) n.intents[k] = n.intents.getValue(k) * (1 - w)
        if (intent.isNotBlank() && intent != "unknown") n.intents[intent] = (n.intents[intent] ?: 0.0) + w
        // What no longer carries any weight goes.
        n.intents.keys.filter { n.intents.getValue(it) < 0.02 }.forEach { n.intents.remove(it) }
    }

    /** What this turn's situation question answered: the relationship, as Jev read it. */
    fun observeSituation(n: Norm, situation: String) {
        n.asked++
        n.since = 0
        val w = 1.0 / minOf(n.asked, SITUATION_WINDOW)
        for (k in n.situations.keys.toList()) n.situations[k] = n.situations.getValue(k) * (1 - w)
        if (situation.isNotBlank()) n.situations[situation] = (n.situations[situation] ?: 0.0) + w
        n.situations.keys.filter { n.situations.getValue(it) < 0.02 }.forEach { n.situations.remove(it) }
    }

    /**
     * The relationship, once the turns that asked it keep answering the same: [SITUATION_ASKED]
     * answers, [SITUATION_SHARE] of them the same one. The question then goes unasked (it is a
     * fifth of each first request, and its eight options a good part of the answer), and is
     * asked again every [SITUATION_RECHECK] turns, so a friendship that turns into more is seen.
     * Null while it is not settled, or when a recheck is due.
     */
    fun steadySituation(n: Norm): String? {
        if (n.asked < SITUATION_ASKED || n.since >= SITUATION_RECHECK) return null
        val (top, share) = n.situations.maxByOrNull { it.value } ?: return null
        return top.takeIf { share >= SITUATION_SHARE }
    }

    private const val SITUATION_WINDOW = 20
    private const val SITUATION_ASKED = 5
    private const val SITUATION_SHARE = 0.75
    private const val SITUATION_RECHECK = 12

    /** Null until a few turns are in. */
    fun normSummary(n: Norm): String? {
        if (n.turns < NORM_MIN) return null
        val common = n.intents.entries.sortedByDescending { it.value }.filter { it.value >= 0.15 }.take(3)
            .joinToString(L.t("、", ", ")) { "${L.label(it.key)} ${Math.round(it.value * 100)}%" }
        val mood = when {
            n.danger < 0.2 -> L.t("平时聊得很轻松", "usually relaxed")
            n.danger < 0.4 -> L.t("平时偶尔有点紧张", "now and then a little tense")
            n.danger < 0.6 -> L.t("平时常有点紧张", "often a little tense")
            else -> L.t("平时经常很紧张", "often very tense")
        }
        return L.t(
            "看过 ${n.turns} 轮：" + (if (common.isNotEmpty()) "常见的是$common；" else "") + mood,
            "Over ${n.turns} turns: " + (if (common.isNotEmpty()) "mostly $common; " else "") + mood)
    }

    /** Two records of one person (linked across apps): weighted by how many turns each saw. */
    fun mergeNorm(into: Norm, from: Norm) {
        val total = into.turns + from.turns
        if (from.turns == 0) return
        if (into.asked + from.asked > 0) {
            val b = into.asked.toDouble() / (into.asked + from.asked)
            for (k in (into.situations.keys + from.situations.keys).toSet())
                into.situations[k] = (into.situations[k] ?: 0.0) * b + (from.situations[k] ?: 0.0) * (1 - b)
            into.asked += from.asked
            into.since = minOf(into.since, from.since)
        }
        if (into.turns == 0) { into.turns = from.turns; into.danger = from.danger; into.intents.putAll(from.intents); return }
        val a = into.turns.toDouble() / total
        into.danger = into.danger * a + from.danger * (1 - a)
        for (k in (into.intents.keys + from.intents.keys).toSet()) into.intents[k] = (into.intents[k] ?: 0.0) * a + (from.intents[k] ?: 0.0) * (1 - a)
        into.turns = total
    }

    fun saveNorm(n: Norm): String =
        "${n.turns}\t${n.danger}\t" + shares(n.intents) + "\t" + shares(n.situations) + "\t${n.asked}\t${n.since}"

    private fun shares(m: Map<String, Double>): String = m.entries.joinToString("|") { "${it.key}=${it.value}" }

    fun loadNorm(text: String): Norm {
        val p = text.split('\t')
        if (p.size < 2) return Norm()
        fun shares(s: String?): LinkedHashMap<String, Double> {
            val out = LinkedHashMap<String, Double>()
            s?.split('|')?.forEach { kv ->
                val k = kv.substringBeforeLast('=', "")
                val v = kv.substringAfterLast('=', "").toDoubleOrNull()
                if (k.isNotBlank() && v != null) out[k] = v
            }
            return out
        }
        return Norm(p[0].toIntOrNull() ?: 0, p[1].toDoubleOrNull() ?: 0.0, shares(p.getOrNull(2)),
            shares(p.getOrNull(3)), p.getOrNull(4)?.toIntOrNull() ?: 0, p.getOrNull(5)?.toIntOrNull() ?: 0)
    }

    // ---- what happened before ----

    data class Turn(val intent: String, val danger: Int, val action: String)

    const val HISTORY = 8

    fun push(history: List<Turn>, t: Turn): List<Turn> = (history + t).takeLast(HISTORY)

    fun historySummary(history: List<Turn>, n: Int = 5): String? {
        if (history.isEmpty()) return null
        return history.takeLast(n).joinToString(L.t("；", "; ")) {
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
