package dev.vibecheck

import kotlin.math.abs

/**
 * Pure, Android-free chat-screen heuristics so they can be unit tested on the JVM.
 *
 * We deliberately do not depend on WeChat/Soul view ids: Tencent renames and obfuscates
 * them between releases. Geometry plus "is it a TextView with text" survives that.
 */
object Chat {

    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val centerX get() = (left + right) / 2
        val width get() = right - left
    }

    data class Bubble(val text: String, val incoming: Boolean, val box: Box)

    /**
     * Chrome = title bar, timestamps, system notices, input bar: everything that is not a message.
     *
     * Measured against the chat WINDOW rect, not the display size. A service's displayMetrics can
     * exclude system bars while node bounds are absolute screen coordinates, and that mismatch
     * silently pushed real bubbles past the bottom cutoff.
     */
    private const val TIME = """((上午|下午|오전|오후)\s?)?\d{1,2}:\d{2}(\s?[AaPp]\.?[Mm]\.?)?"""
    private const val MONTH = """(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]*\.?"""
    private const val DAY =
        """(今天|昨天|前天|Today|Yesterday|星期[一二三四五六日天]|周[一二三四五六日天]|""" +
            """(Mon|Tues?|Wed(nes)?|Thu(rs)?|Fri|Sat(ur)?|Sun)(day)?|""" +
            """$MONTH \d{1,2}(, \d{4})?|\d{1,2} $MONTH( \d{4})?|""" +
            """\d{4}[/-]\d{1,2}[/-]\d{1,2}|\d{1,2}月\d{1,2}日|\d{1,2}/\d{1,2}(/\d{2,4})?|""" +
            """\d{4}년 \d{1,2}월 \d{1,2}일|오늘|어제)"""
    /** Delivery state and presence, as Messenger / Google Messages / WhatsApp / Telegram / Instagram print them. */
    private const val STATUS =
        """(Seen|Delivered|Sent|Read|Now|Just now|Sending…?|Not delivered|Failed to send|Tap to retry|""" +
            """Edited|Forwarded|Typing…?|Typing\.\.\.|is typing…?|Active now|Active \d+ ?[mhd] ago|Online|""" +
            """SMS|RCS|RCS message|MMS|Text message|iMessage|Message|Aa|Chat messages|SIM \d|""" +
            """Played|Opened|Received|\(edited\)|NEW MESSAGES|New|BOT|APP|SYSTEM|Draft|In chat|Unread|""" +
            """已读|未读|已送达|已发送|送达|发送失败|正在输入…?|对方正在输入…?|已编辑|已转发|既読|在线)"""
    /** Any run of date, status and time parts, in any order: "Seen 10:32", "SMS · Now", "Today 3:45 PM". */
    private val TIME_OR_DATE = Regex("""^(($DAY|$STATUS|$TIME)(\s*[·•|,\-]\s*|\s+at\s+|\s+)?)+$""", RegexOption.IGNORE_CASE)

    /** Timestamps look like messages to OCR: they are short, boxed, and sit in the list. */
    fun isTimeOrDate(text: String): Boolean = TIME_OR_DATE.matches(text.trim())

    /** System rows specific enough to recognise anywhere in a text. */
    private val SYSTEM_PHRASE = Regex(
        """(sent you \d+ message|friend\(s\) sent you|\d+\s*friend\(s\)|""" +
            """向你推荐|拍了拍|撤回了一条消息|邀请你加入|加入了群聊|通过了你的|Duration:\s*\d|""" +
            """reacted .{1,4} to|unsent a message|message was deleted|deleted this message|""" +
            """now connected on Messenger|end-to-end encrypted[^?？]*$|""" +
            """security code (with .{1,40} )?changed|safety number (with .{1,40} )?(has )?changed|""" +
            """disappearing messages? (were |was |are )?turned (on|off)|turned (on|off) disappearing messages?|""" +
            """Letter Sealing|secret chat (created|started)|joined the secret chat|set to (delete|disappear) after|""" +
            """님이 (나갔|들어왔)습니다|삭제된 메시지|通话时长)""",
        RegexOption.IGNORE_CASE,
    )

    /** Who a system row is about: "You", or a capitalised name of one or two words. Never "I": apps say "You". */
    private const val ACTOR = """(you|(?-i:(?!I\b)\p{Lu}[\p{L}\p{N}'._-]{0,19}( \p{Lu}[\p{L}\p{N}'._-]{0,19})?))"""
    private const val CLOCK = """\d{1,2}:\d{2}(\s?[AaPp]\.?[Mm]\.?)?"""

    /**
     * System rows built from ordinary words ("Video call", "Sam replied to you"). These only count
     * when they are the whole text, optionally followed by metadata ("· 12 min", "at 10:32"), and
     * never when they end in a question: "video call tonight?", "Video call at 8 tonight",
     * "she finally replied to you" and "say hi to your mom for me" are messages.
     */
    private val SYSTEM_ROW = Regex(
        """(((missed|cancell?ed|declined|outgoing|incoming) (voice |video |audio )?call|(voice|video|audio) call)( ended| back)?|""" +
            """call ended|huddle ended|(语音|视频)通话(已取消|已拒绝|未接听|对方已取消|对方已拒绝|对方无应答|已挂断)?|""" +
            // The floating "jump to the newest" pill of a chat scrolled up.
            """\d+\s*条新消息|有新消息|回到底部|\d+ (new|unread) messages?|""" +
            """message not delivered|(send|type|write) a message(…|\.\.\.)?|say hi( to $ACTOR)?|""" +
            """(seen|liked|loved) by $ACTOR( and \d+ others?)?|(liked|loved|laughed at|emphasized|questioned|disliked) ["“].*|""" +
            """you replied to ($ACTOR|their story|your story)|$ACTOR replied to (you|your story)|replied to (you|yourself|$ACTOR)|""" +
            """$ACTOR sent an attachment|$ACTOR changed the (theme|group|chat|emoji|nickname)[^?？]{0,60}|""" +
            """$ACTOR (joined|left) the (chat|group|server|conversation|call)|$ACTOR waved at $ACTOR|""" +
            """$ACTOR pinned a message|$ACTOR started a (video |voice )?(call|huddle)|""" +
            """$ACTOR took a screenshot( of (the|this) (chat|conversation))?|""" +
            """$ACTOR reacted to (your|their|a|an|his|her|this) (message|story|photo|video|note|comment|post)|""" +
            """$ACTOR accepted (the|your) (message )?request)""" +
            // Trailing metadata only: "· 12 min", " at 10:32", " 03:12". Not ", then dinner" or " at 8 tonight".
            """(\s*[·•|]\s*[^?？]{0,40}|\s+(at|on)\s+$CLOCK|\s+$CLOCK)?[.!。]?""",
        RegexOption.IGNORE_CASE,
    )

    /** What OCR makes of the phone or camera icon beside a call record: a stray mark or a letter. */
    private const val ICON = """([^\p{L}\p{N}\s]{1,2}|[CcLlOoQqUuGg])"""

    /**
     * A call record, in a bubble on the caller's side: WeChat's 「已取消」「对方已取消」「对方无应答」
     * 「通话时长 03:12」, "Canceled", "Call declined". Only as the whole text, so 「我已经取消了」 and
     * "I canceled" are messages.
     */
    private val CALL_ROW = Regex(
        """($ICON\s*)?(""" +
            """(对方)?(已取消|已拒绝|已挂断|未接听|无应答|已在其他设备接听)|对方(忙线中?|正忙)|忙线未接听|无人接听|连接失败|""" +
            """(语音|视频)?通话(已)?(取消|结束|中断)|(通话|聊天)时长\s*\d{1,2}:\d{2}(:\d{2})?|""" +
            """((voice |video )?call )?(canceled|cancelled|declined|unanswered|not answered|no answer)""" +
            """( by (the )?(caller|callee|other party))?|""" +
            """(call|line) busy|(voice |video )?call (ended|failed|missed)|(call )?duration:?\s*\d{1,2}:\d{2}(:\d{2})?|""" +
            """connection failed|(answered|declined) on (another|other) device""" +
            """)(\s*$ICON)?[.!。]?""",
        RegexOption.IGNORE_CASE,
    )

    /** The length printed beside a voice message: 5", 12″, 59”. */
    private val VOICE = Regex("""\d{1,2}\s*(["″”“]|'')""")

    /** WeChat toasts, call records, voice lengths, reactions and system notices are not conversation. */
    fun isNotification(text: String): Boolean {
        val t = text.trim()
        return SYSTEM_PHRASE.containsMatchIn(t) || SYSTEM_ROW.matches(t) || CALL_ROW.matches(t) || VOICE.matches(t)
    }

    fun isChrome(box: Box, text: String, win: Box): Boolean {
        if (text.isBlank() || text.length > 400) return true
        if (isTimeOrDate(text) || isNotification(text)) return true
        if (box.width <= 0 || box.bottom <= box.top) return true
        val h = (win.bottom - win.top).coerceAtLeast(1)
        if (box.top < win.top + h * 0.06) return true       // action bar / contact name
        // Wholly inside the title bar: the unread count on the back button, a presence line, a
        // button like Soul's 「加速」. A message scrolled under the bar is clipped to its edge, so
        // only a sliver of one ends this high.
        if (box.bottom <= win.top + h * 0.11) return true
        // Only the input row itself. The newest bubble often sits right on top of it, and the
        // EditText and send Button are already excluded by class, so this can stay permissive.
        if (box.bottom > win.top + h * 0.96) return true
        // Centered short text in a chat list is a date divider or "对方撤回了一条消息". Centered
        // means as far from both edges: next to an avatar, a message of theirs that ends past
        // the middle ("不是美女，有什么好看的" on Soul) has its centre near the middle too.
        if (abs((box.left - win.left) - (win.right - box.right)) < win.width * 0.06 && box.width < win.width * 0.55) return true
        return false
    }

    /**
     * Which of the texts on screen are messages: not chrome on their own ([isChrome]), not in a
     * row of texts side by side ([sideBySide]), and above the reply box when it is known.
     * Everything from the top of the reply box down is the compose area (a send button drawn as
     * text, a hint), wherever the keyboard has pushed it.
     */
    fun messageIndices(items: List<Pair<String, Box>>, win: Box, input: Box? = null): List<Int> {
        val strip = sideBySide(items)
        return items.indices.filter { i ->
            val (text, box) = items[i]
            i !in strip && !isChrome(box, text, win) && (input == null || (box.top + box.bottom) / 2 < input.top)
        }
    }

    /**
     * The texts that sit beside two or more others on one row: a strip of quick replies,
     * stickers and games (Soul's 「下午好」「礼物」「桌球」「比心」「猜拳」 above the reply box), a
     * toolbar, a row of reactions. A chat stacks its messages one per row, so three short texts
     * side by side are never messages. Read as messages, Soul's strip was the newest thing "they"
     * said, and the replies answered 「下午好」. A time or delivery mark beside a message
     * ("ok 10:32") is not counted toward a row.
     */
    fun sideBySide(items: List<Pair<String, Box>>): Set<Int> {
        // The node tree repeats nodes: rows are made of distinct texts, and every copy goes.
        val cells = items.filter { (text, box) ->
            text.isNotBlank() && text.codePointCount(0, text.length) <= STRIP_CHARS &&
                box.width > 0 && box.bottom > box.top && !isTimeOrDate(text) && !isNotification(text)
        }.distinct()
        val rows = ArrayList<MutableList<Pair<String, Box>>>()
        for (c in cells.sortedBy { it.second.top + it.second.bottom }) {
            val row = rows.firstOrNull { r -> r.all { sameRow(it.second, c.second) } }
            if (row != null) row.add(c) else rows.add(mutableListOf(c))
        }
        val strip = HashSet<Pair<String, Box>>()
        for (r in rows) {
            if (r.size < 3) continue
            // Side by side, not one inside another.
            val byLeft = r.sortedBy { it.second.left }
            if (byLeft.zipWithNext().all { (a, b) -> b.second.left >= a.second.right - 2 }) strip += r
        }
        return items.indices.filterTo(HashSet()) { items[it] in strip }
    }

    /** Longer than this is a sentence, not a chip. */
    private const val STRIP_CHARS = 16

    /** Two boxes on one line: most of the shorter one's height is shared. */
    private fun sameRow(a: Box, b: Box): Boolean {
        val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return overlap * 10 >= minOf(a.bottom - a.top, b.bottom - b.top) * 6
    }

    /**
     * Incoming (from them) bubbles hug the left edge, outgoing (mine) hug the right.
     * Compare margins rather than the center so a wide multi-line bubble is still classified right.
     */
    fun isIncoming(box: Box, win: Box): Boolean = (box.left - win.left) <= (win.right - box.right)

    /** How far a text leans to one side: its left margin less its right one, over the window width. */
    private fun lean(box: Box, win: Box): Float =
        ((box.left - win.left) - (win.right - box.right)).toFloat() / win.width.coerceAtLeast(1)

    /**
     * Whose each message is (true: theirs), for [boxes] in order down the screen.
     *
     * Most messages sit clearly to one side, and their margins decide. A text that nearly fills
     * the width of a bubble reads the same both ways: the widest bubbles of both sides leave the
     * same margins in WeChat, and the ragged end of the text tipped my long messages to them. Then
     * [seen] decides, from the avatar or bubble drawn beside it ([Sides], -1 left, 1 right, 0 not
     * known), and failing that the margins do.
     *
     * A text right under another, overlapping it, is in the same bubble or card: the lines of a
     * link card below its title (left-aligned inside a card of mine, so their margins say
     * "theirs"), a message OCR cut in two. It takes the side of the text above when the pixels
     * settled that one, and when nothing else settles its own. [dp] is pixels per dp.
     */
    fun sides(boxes: List<Box>, win: Box, dp: Float, seen: (Box) -> Int): List<Boolean> {
        val out = ArrayList<Boolean>(boxes.size)
        // Whether each side was settled by the pixels, directly or through the text above.
        val settled = ArrayList<Boolean>(boxes.size)
        for ((i, box) in boxes.withIndex()) {
            val lean = lean(box, win)
            val prev = boxes.getOrNull(i - 1)
            val under = prev != null && box.top - prev.bottom <= SAME_BUBBLE_DP * dp &&
                prev.left < box.right && box.left < prev.right
            if (under && settled[i - 1]) { out += out[i - 1]; settled += true; continue }
            if (abs(lean) >= CLEAR_LEAN) { out += lean < 0; settled += false; continue }
            when (seen(box)) {
                Sides.LEFT -> { out += true; settled += true }
                Sides.RIGHT -> { out += false; settled += true }
                else -> { out += if (under) out[i - 1] else lean <= 0; settled += false }
            }
        }
        return out
    }

    /** Leaning this far, a text's side is plain from its margins alone. */
    private const val CLEAR_LEAN = 0.12f
    /** Texts closer than this, one under the other, are in one bubble or card: separate messages are further apart. */
    private const val SAME_BUBBLE_DP = 14f

    /**
     * Build bubbles from OCR blocks. Same geometry rules as the node-tree path, plus exclusion
     * rectangles for everything on screen that is not the conversation: our own card (or it
     * reads its own output back) and the keyboard (or every key becomes a message). [seen] is
     * what the pixels beside a text say about whose it is (see [sides]).
     */
    fun fromOcr(
        items: List<Pair<String, Box>>,
        win: Box,
        exclude: List<Box>,
        input: Box? = null,
        dp: Float = 2.75f,
        seen: (Box) -> Int = { Sides.UNKNOWN },
    ): Pair<List<Bubble>, List<Pair<String, Box>>> {
        val h = (win.bottom - win.top)
        // Between the status bar and the end of the title bar. The status bar is where the clock
        // and icons live, and OCR turns those into convincing-looking garbage.
        val titles = items.filter { it.second.top >= win.top + h * 0.035 && it.second.top < win.top + h * 0.07 }
        val visible = items.filterNot { item -> exclude.any { overlaps(item.second, it) } }
        val kept = messageIndices(visible, win, input).map { visible[it] }.sortedBy { it.second.top }
        val theirs = sides(kept.map { it.second }, win, dp, seen)
        val bubbles = kept.mapIndexed { i, (text, box) -> Bubble(text, theirs[i], box) }
        return order(bubbles) to titles
    }

    /**
     * WeChat shows a quoted message under the reply, on the replier's side: 「名字：原话」. Their
     * words quoted under my reply read as mine, so a text that starts with their name and a colon
     * is a quote, not a message. [peer] is the name in the title bar.
     */
    fun withoutQuotes(bubbles: List<Bubble>, peer: String): List<Bubble> {
        val name = peer.trim()
        if (name.isEmpty()) return bubbles
        val quote = Regex("""^\s*${Regex.escape(name)}\s*[：:]\s*\S""")
        return bubbles.filterNot { quote.containsMatchIn(it.text) }
    }

    /**
     * Where the reply box is while the keyboard is up, for a screen read by OCR, which has no text
     * field to go by: the row right above the keyboard. Read as a message, a draft being typed
     * there was "their" newest line. Null for a keyboard that does not sit along the bottom.
     */
    fun replyRowAbove(ime: Box?, win: Box): Box? {
        val h = win.bottom - win.top
        if (ime == null || ime.width < win.width * 0.8 || ime.top <= win.top + h * 0.3) return null
        // A window the keyboard has shrunk ends where the keyboard starts.
        val bottom = minOf(ime.top, win.bottom)
        val row = maxOf(h * 0.065, (ime.bottom - ime.top) * 0.16).toInt()
        return Box(win.left, bottom - row, win.right, bottom)
    }

    fun overlaps(a: Box, b: Box): Boolean =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    /** Top-to-bottom order, de-duplicated by text+position (the node tree repeats nodes). */
    fun order(bubbles: List<Bubble>): List<Bubble> =
        bubbles.distinctBy { it.text to it.box.top }.sortedBy { it.box.top }

    /**
     * Am I looking at a conversation, or at the chat list? The list is all left-aligned rows of
     * names and previews, so requiring at least one message of my own keeps the tool from
     * judging a directory of contacts as if it were a conversation.
     *
     * [hasInput] is a text field along the bottom of the window, which a chat has and a chat list
     * does not. With it, a screen holding only their messages still counts: a new contact's first
     * message, or a long run of theirs, is exactly when a read is wanted.
     */
    fun inConversation(bubbles: List<Bubble>, hasInput: Boolean = false): Boolean =
        (bubbles.any { !it.incoming } && bubbles.size >= 2) || (hasInput && bubbles.isNotEmpty())

    private val TAB_LABELS = setOf(
        "WeChat", "Contacts", "Discover", "Me", "微信", "通讯录", "发现", "我",
        "消息", "广场", "星球", "聊天",
        // Messenger, WhatsApp, Instagram, Snapchat, Discord, Telegram, Teams, Slack bottom bars.
        "Chats", "Calls", "Stories", "People", "Menu", "Updates", "Communities", "Status",
        "Home", "Reels", "Notifications", "Profile", "Map", "Camera", "Spotlight", "Servers",
        "You", "Friends", "Activity", "Chat", "Teams", "Calendar", "DMs", "Mentions", "Later",
    )

    /**
     * The app's home screen has a tab bar along the bottom. Two or more tab labels down there is
     * a far stronger signal than counting bubbles: a contact list is full of names and previews
     * that otherwise look exactly like a conversation.
     */
    fun looksLikeHomeScreen(items: List<Pair<String, Box>>, win: Box): Boolean {
        val h = (win.bottom - win.top).coerceAtLeast(1)
        val tabs = items.count { (text, box) ->
            box.top > win.top + h * 0.88 && text.trim() in TAB_LABELS
        }
        return tabs >= 2
    }

    /**
     * WeChat message bubbles are long-clickable (copy/recall menu); nicknames, timestamps and
     * system rows are not. When the tree gives us that signal, trust it over geometry alone.
     */
    fun refine(bubbles: List<Bubble>, longClickable: Set<String>): List<Bubble> {
        val strong = bubbles.filter { longClickable.contains(it.text) }
        return if (strong.size >= 2) strong else bubbles
    }

    /** Fires only when the newest visible message is theirs and we have not judged it yet. */
    fun triggerKey(bubbles: List<Bubble>): String? {
        val last = bubbles.lastOrNull() ?: return null
        if (!last.incoming) return null
        return anyKey(bubbles)
    }

    /** A key for the current screen regardless of who spoke last; used when the user opens the card. */
    fun anyKey(bubbles: List<Bubble>): String? {
        if (bubbles.isEmpty()) return null
        return bubbles.takeLast(3).joinToString("|") { (if (it.incoming) "T" else "M") + it.text }
    }

    /**
     * Did I send anything after the message we last judged? Only then is the next turn
     * evidence about the advice we gave, rather than about nothing.
     */
    fun repliedSince(bubbles: List<Bubble>, judgedText: String): Boolean {
        val at = bubbles.indexOfLast { it.incoming && it.text == judgedText }
        if (at < 0) return false
        return bubbles.drop(at + 1).any { !it.incoming }
    }

    fun transcript(bubbles: List<Bubble>, max: Int = 12): List<Pair<String, String>> =
        bubbles.takeLast(max).map { (if (it.incoming) "对方" else "我") to it.text }

    /**
     * How many lines at the end of an older page are already at the start of what was kept.
     * Pages read while scrolling up overlap, and matching the overlap (rather than a global
     * set of seen texts) keeps "嗯" sent thirty times as thirty messages.
     */
    fun overlap(kept: List<Pair<String, String>>, page: List<Pair<String, String>>): Int {
        for (k in minOf(kept.size, page.size) downTo 1) {
            if (page.subList(page.size - k, page.size) == kept.subList(0, k)) return k
        }
        return 0
    }

    /**
     * The lines of an older page that are not already kept. Exact overlap first; when a clipped
     * or misread top bubble breaks the exact match (OCR reads 好呀 as 好啊 between frames), a
     * page that is mostly already in the newest two pages is taken line by line instead of
     * being prepended whole and double counted.
     */
    fun freshLines(kept: List<Pair<String, String>>, page: List<Pair<String, String>>): List<Pair<String, String>> {
        val k = overlap(kept, page)
        if (k > 0 || page.isEmpty()) return page.dropLast(k)
        val head = kept.take(page.size * 2)
        val known = page.count { it in head }
        return if (known * 2 > page.size) page.filterNot { it in head } else page
    }

    // ---- counting each message once while watching ----

    /** How many of the newest counted messages are remembered per person to line screens up against. */
    const val TAIL = 40

    /** What one observed screen adds: the lines new at the bottom, and the tail to remember next. */
    data class Sync(val fresh: List<Pair<String, String>>, val tail: List<Pair<String, String>>, val aligned: Boolean)

    /**
     * Lines the visible page up against the newest messages already counted for this person and
     * returns what is genuinely new below them. Remembering only "the last text seen" counted every
     * screen of history again as you scrolled up (the last text was off screen, so everything
     * looked new), and the way back down counted it all a third time.
     *
     * - The tail's newest message is on screen: whatever sits below it is new.
     * - The page lines up with older parts of the tail: scrolled up, nothing new.
     * - Nothing lines up: somewhere in the history we never counted, so nothing is counted, unless
     *   the chat was just opened ([reentry]), where the app shows the newest messages and no
     *   overlap means everything on screen arrived while we were away.
     *
     * [legacyLast] is the single "last seen text" older versions stored, used once to migrate.
     */
    fun sync(
        tail: List<Pair<String, String>>,
        page: List<Pair<String, String>>,
        reentry: Boolean,
        legacyLast: String = "",
        keep: Int = TAIL,
    ): Sync {
        if (page.isEmpty()) return Sync(emptyList(), tail, false)
        if (tail.isEmpty()) {
            if (legacyLast.isEmpty()) return Sync(page, page.takeLast(keep), false)
            val at = page.indexOfLast { it.second == legacyLast }
            return Sync(if (at >= 0) page.drop(at + 1) else emptyList(), page.takeLast(keep), at >= 0)
        }
        val end = alignEnd(tail, page)
        return when {
            end == null -> if (reentry) Sync(page, page.takeLast(keep), false) else Sync(emptyList(), tail, false)
            end >= page.size -> Sync(emptyList(), tail, true)
            else -> page.drop(end + 1).let { fresh -> Sync(fresh, (tail + fresh).takeLast(keep), true) }
        }
    }

    /**
     * A history read leaves the chat scrolled far up. Is this page past that history again? Yes
     * once the newest counted message (the end of [tail]) is on screen, or once the page lines up
     * with neither what was counted nor what was [read]: then it is something new, like a burst
     * that arrived meanwhile. With nothing counted there is nothing to be behind.
     */
    fun pastHistory(tail: List<Pair<String, String>>, read: List<Pair<String, String>>, page: List<Pair<String, String>>): Boolean {
        if (tail.isEmpty()) return true
        val end = alignEnd(tail, page)
        if (end != null) return end < page.size
        return alignEnd(read, page) == null
    }

    /**
     * The page index of the tail's newest line under the best-agreeing offset between the two
     * (page.size or more when the page shows only older, already counted lines), or null when
     * nothing lines up convincingly. An offset needs at least two agreeing lines and 60% of its
     * overlap agreeing, so one clipped or misread bubble does not break it and a lone "ok" that
     * happens to match does not make it.
     *
     * Known limit: if a burst pushes every known line off a short page, nothing lines up until
     * the chat is opened again. That undercounts; guessing instead would count history as new.
     */
    fun alignEnd(tail: List<Pair<String, String>>, page: List<Pair<String, String>>): Int? {
        // Normalised once per line rather than once per comparison: this runs on every scan.
        val t = tail.map { it.first to norm(it.second) }
        val p = page.map { it.first to norm(it.second) }
        var bestEnd: Int? = null
        var bestHits = 0
        val last = tail.size - 1
        for (d in -(page.size - 1)..last) {          // page[i] lines up with tail[i + d]
            val from = maxOf(0, -d)
            val to = minOf(page.size, tail.size - d)
            val n = to - from
            if (n <= 0) continue
            var hits = 0
            for (i in from until to) if (p[i].first == t[i + d].first && close(p[i].second, t[i + d].second)) hits++
            // A single line lines up when there is nothing more to go on, or when it is the tail's
            // newest line sitting at the very top of the page and distinctive enough not to be a
            // coincidence: with the keyboard up only a few bubbles show, and a quick burst can push
            // all but one known line off the screen.
            val enough = if (n == 1) hits == 1 && (tail.size == 1 || page.size == 1 || (d == last && t[last].second.length >= 6))
                else hits >= 2 && hits * 10 >= n * 6
            if (!enough) continue
            val end = last - d
            // Ties go to the later end: fewer lines called new, which is the safe mistake.
            if (hits > bestHits || (hits == bestHits && end > (bestEnd ?: -1))) {
                bestHits = hits
                bestEnd = end
            }
        }
        return bestEnd
    }

    fun same(a: Pair<String, String>, b: Pair<String, String>): Boolean =
        a.first == b.first && similar(a.second, b.second)

    /**
     * Equal, or equal up to the odd character OCR reads differently between two frames: one
     * edit per five characters. Under five characters it must be exact, or 好的 and 好吧 would
     * be one message.
     */
    fun similar(a: String, b: String): Boolean = a == b || close(norm(a), norm(b))

    private fun norm(s: String): String = s.filterNot { it.isWhitespace() }.lowercase()

    /** [similar] for already normalised text. Long texts compare their first 80 characters. */
    private fun close(x: String, y: String): Boolean {
        if (x == y) return true
        if (abs(x.length - y.length) > minOf(x.length, y.length) / 5) return false
        val xs = x.take(80)
        val ys = y.take(80)
        val slack = minOf(xs.length, ys.length) / 5
        return slack > 0 && editDistance(xs, ys) <= slack
    }

    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
