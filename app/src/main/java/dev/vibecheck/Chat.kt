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

    private val NOTIFICATION = Regex(
        """(sent you \d+ message|friend\(s\) sent you|\d+\s*friend\(s\)|""" +
            """向你推荐|拍了拍|撤回了一条消息|邀请你加入|加入了群聊|通过了你的|Duration:\s*\d|""" +
            // Call stubs, reactions and system rows the other apps put inside the list.
            """Missed (voice|video|audio) call|(Voice|Video|Audio) call( ended)?|Call ended|""" +
            """reacted .{1,4} to|You reacted|unsent a message|message was deleted|deleted this message|""" +
            """You replied to|replied to you|Replied to|sent an attachment|changed the (theme|group)|""" +
            """joined the (chat|group|server)|left the (chat|group|conversation)|now connected on Messenger|waved at|""" +
            """end-to-end encrypted|Message not delivered|Say hi|Send a message|""" +
            """(Seen|Liked|Loved) by |^(Liked|Loved|Laughed at|Emphasized|Questioned|Disliked) ["“]|""" +
            """pinned a message|hopped into|started a (call|huddle)|Huddle ended|took a screenshot|""" +
            """Security code changed|Safety number changed|disappearing message|Letter Sealing|Secret chat|""" +
            """set to delete|accepted the request|(Outgoing|Incoming|Missed) Call|""" +
            """님이 (나갔|들어왔)습니다|삭제된 메시지|通话时长|语音通话|视频通话)""",
        RegexOption.IGNORE_CASE,
    )

    /** WeChat toasts, call-duration stubs and system notices are not conversation. */
    fun isNotification(text: String): Boolean = NOTIFICATION.containsMatchIn(text)

    fun isChrome(box: Box, text: String, win: Box): Boolean {
        if (text.isBlank() || text.length > 400) return true
        if (isTimeOrDate(text) || isNotification(text)) return true
        if (box.width <= 0 || box.bottom <= box.top) return true
        val h = (win.bottom - win.top).coerceAtLeast(1)
        if (box.top < win.top + h * 0.06) return true       // action bar / contact name
        // Only the input row itself. The newest bubble often sits right on top of it, and the
        // EditText and send Button are already excluded by class, so this can stay permissive.
        if (box.bottom > win.top + h * 0.96) return true
        // Centered short text in a chat list is a date divider or "对方撤回了一条消息".
        if (abs(box.centerX - (win.left + win.right) / 2) < win.width * 0.10 && box.width < win.width * 0.55) return true
        return false
    }

    /**
     * Incoming (from them) bubbles hug the left edge, outgoing (mine) hug the right.
     * Compare margins rather than the center so a wide multi-line bubble is still classified right.
     */
    fun isIncoming(box: Box, win: Box): Boolean = (box.left - win.left) <= (win.right - box.right)

    /**
     * Build bubbles from OCR blocks. Same geometry rules as the node-tree path, plus exclusion
     * rectangles for everything on screen that is not the conversation: our own card (or it
     * reads its own output back) and the keyboard (or every key becomes a message).
     */
    fun fromOcr(
        items: List<Pair<String, Box>>,
        win: Box,
        exclude: List<Box>,
    ): Pair<List<Bubble>, List<Pair<String, Box>>> {
        val h = (win.bottom - win.top)
        // Between the status bar and the end of the title bar. The status bar is where the clock
        // and icons live, and OCR turns those into convincing-looking garbage.
        val titles = items.filter { it.second.top >= win.top + h * 0.035 && it.second.top < win.top + h * 0.07 }
        val bubbles = items
            .filterNot { item -> exclude.any { overlaps(item.second, it) } }
            .filterNot { isChrome(it.second, it.first, win) }
            .map { Bubble(it.first, isIncoming(it.second, win), it.second) }
        return order(bubbles) to titles
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
     */
    fun inConversation(bubbles: List<Bubble>): Boolean =
        bubbles.any { !it.incoming } && bubbles.size >= 2

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

    /** Fires only when the newest visible message is theirs and we have not judged it yet. */
    /**
     * WeChat message bubbles are long-clickable (copy/recall menu); nicknames, timestamps and
     * system rows are not. When the tree gives us that signal, trust it over geometry alone.
     */
    fun refine(bubbles: List<Bubble>, longClickable: Set<String>): List<Bubble> {
        val strong = bubbles.filter { longClickable.contains(it.text) }
        return if (strong.size >= 2) strong else bubbles
    }

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
}
