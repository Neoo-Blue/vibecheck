package dev.vibecheck

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Whose a message is when its text alone can't tell, and call records and quotes that are not messages. */
class SidesTest {

    private val W = 1080
    private val WIN = Chat.Box(0, 0, W, 2400)
    private val DP = 2.75f
    private val BG = 0xFFEDEDED.toInt()
    private val WHITE = 0xFFFFFFFF.toInt()
    private val GREEN = 0xFF95EC69.toInt()
    private fun box(l: Int, t: Int, r: Int, b: Int) = Chat.Box(l, t, r, b)

    /** One row of pixels across the window: [spans] of (from, to, colour) over the background. */
    private fun row(vararg spans: Triple<Int, Int, Int>, bg: Int = BG): IntArray {
        val r = IntArray(W) { bg }
        for ((from, to, color) in spans) for (x in from until to) r[x] = color
        return r
    }

    /** A photo: no two neighbours alike. */
    private fun photo(r: IntArray, from: Int, to: Int): IntArray {
        for (x in from until to) r[x] = if (x % 2 == 0) 0xFF336699.toInt() else 0xFF884422.toInt()
        return r
    }

    /** Glyphs over [from]..[to]: dark strokes on the bubble. */
    private fun text(r: IntArray, from: Int, to: Int): IntArray {
        for (x in from until to step 7) r[x] = 0xFF111111.toInt()
        return r
    }

    // WeChat at 1080 px, 2.75 px per dp: avatars 12dp in and 40dp wide, the widest bubble between
    // the avatar columns, the text inside it.
    private val theirLongRow get() = text(photo(row(Triple(160, 920, WHITE)), 33, 143), 193, 887)
    private val myLongRow get() = text(photo(row(Triple(160, 920, GREEN)), 937, 1047), 193, 887)
    /** Further down the same bubble, below the avatar. */
    private val lowerRow get() = text(row(Triple(160, 920, GREEN)), 193, 887)

    @Test fun theAvatarBesideALongMessageSaysWhoseItIs() {
        assertEquals(Sides.LEFT, Sides.rowSide(theirLongRow, 193, 887, DP))
        assertEquals(Sides.RIGHT, Sides.rowSide(myLongRow, 193, 887, DP))
        // Below the avatar both edges are as far from the bubble: nothing to go on.
        assertEquals(Sides.UNKNOWN, Sides.rowSide(lowerRow, 193, 887, DP))
    }

    @Test fun withoutAvatarsTheBubbleHugsItsSide() {
        // WhatsApp or Telegram: their bubble starts 8dp from the left edge, mine ends 8dp from the right.
        val theirs = text(row(Triple(22, 870, WHITE)), 55, 840)
        val mine = text(row(Triple(210, 1058, GREEN)), 240, 1025)
        assertEquals(Sides.LEFT, Sides.rowSide(theirs, 55, 840, DP))
        assertEquals(Sides.RIGHT, Sides.rowSide(mine, 240, 1025, DP))
    }

    @Test fun noFlatBackgroundAtTheEdgeTellsNothing() {
        // A wallpaper: the strip at the edge is not one colour.
        val wallpaper = text(photo(row(Triple(160, 920, WHITE)), 0, 160), 193, 887)
        assertEquals(Sides.UNKNOWN, Sides.rowSide(wallpaper, 193, 887, DP))
        // A scrollbar along the right edge while scrolling: that side can't be read.
        val scrolling = row(Triple(1072, 1080, 0xFF888888.toInt())).also { photo(it, 33, 143); text(it, 193, 887) }
        assertEquals(Sides.UNKNOWN, Sides.rowSide(scrolling, 193, 887, DP))
        // A text that runs to the edge leaves no edge to look at.
        assertEquals(Sides.UNKNOWN, Sides.rowSide(theirLongRow, 5, 887, DP))
        assertEquals(Sides.UNKNOWN, Sides.rowSide(theirLongRow, 193, 1075, DP))
    }

    @Test fun rowsMustAgree() {
        assertEquals(Sides.RIGHT, Sides.blockSide(listOf(myLongRow, lowerRow, lowerRow), 193, 887, DP))
        assertEquals(Sides.LEFT, Sides.blockSide(listOf(lowerRow, theirLongRow), 193, 887, DP))
        assertEquals(Sides.UNKNOWN, Sides.blockSide(listOf(theirLongRow, myLongRow), 193, 887, DP))
        assertEquals(Sides.UNKNOWN, Sides.blockSide(listOf(lowerRow), 193, 887, DP))
        assertEquals(Sides.UNKNOWN, Sides.blockSide(emptyList(), 193, 887, DP))
    }

    @Test fun rowsAreTakenNearTheFirstAndLastLines() {
        assertEquals(listOf(1008, 1027, 1032, 1051), Sides.rowsFor(1000, 1060, 2400, DP))
        // A single thin line: the rows that fall outside it are dropped.
        assertTrue(Sides.rowsFor(1000, 1020, 2400, DP).all { it in 1000 until 1020 })
        assertTrue(Sides.rowsFor(2390, 2440, 2400, DP).all { it < 2400 })
    }

    @Test fun clearMarginsDecideAndPixelsSettleTheRest() {
        val short = box(193, 500, 600, 560)                // theirs, hugging the left
        val long = box(193, 700, 887, 820)                 // fills the width: margins alike
        val shortMine = box(600, 1000, 887, 1060)          // mine, hugging the right
        val asked = ArrayList<Chat.Box>()
        val seen = { b: Chat.Box -> asked += b; if (b == long) Sides.RIGHT else Sides.LEFT }
        assertEquals(listOf(true, false, false), Chat.sides(listOf(short, long, shortMine), WIN, DP, seen))
        // Only the text whose margins leave it open is looked at.
        assertEquals(listOf(long), asked)
        // Nothing seen: the margin rule it always had (a tie is theirs).
        assertEquals(listOf(true), Chat.sides(listOf(long), WIN, DP) { Sides.UNKNOWN })
        assertEquals(listOf(true), Chat.sides(listOf(long), WIN, DP) { Sides.LEFT })
    }

    @Test fun theLinesOfACardFollowItsTitle() {
        // A link card of mine: the title fills the card, the description and source under it are
        // left-aligned inside it, so their margins alone say "theirs".
        val title = box(193, 700, 880, 800)
        val description = box(193, 815, 600, 900)          // 15 px under the title
        val source = box(193, 930, 420, 970)               // 30 px under the description
        val sides = Chat.sides(listOf(title, description, source), WIN, DP) { if (it == title) Sides.RIGHT else Sides.UNKNOWN }
        assertEquals(listOf(false, false, false), sides)
        // A separate message further down keeps its own side.
        val next = box(193, 1100, 600, 1160)
        assertEquals(listOf(false, true), Chat.sides(listOf(title, next), WIN, DP) { Sides.RIGHT })
        // Margins alone settle nothing for the text under them.
        assertEquals(listOf(true, true), Chat.sides(listOf(box(193, 500, 600, 560), description.copy(top = 570, bottom = 640)), WIN, DP) { Sides.RIGHT })
    }

    @Test fun ocrUsesWhatThePixelsSay() {
        val items = listOf(
            "你今天去哪了" to box(193, 500, 520, 560),
            "我下午去公司开了个会，开完又去买了点东西，刚到家，累死了，晚上还得改方案" to box(193, 700, 887, 820),
            "辛苦啦" to box(193, 1000, 400, 1060),
        )
        val long = items[1].second
        val (bubbles, _) = Chat.fromOcr(items, WIN, emptyList(), seen = { if (it == long) Sides.RIGHT else Sides.UNKNOWN })
        assertEquals(listOf(true, false, true), bubbles.map { it.incoming })
        // Without the pixels the long one was theirs.
        assertEquals(listOf(true, true, true), Chat.fromOcr(items, WIN, emptyList()).first.map { it.incoming })
    }

    @Test fun avatarsInTheNodeTree() {
        val theirs = box(33, 690, 143, 800)
        val mine = box(937, 990, 1047, 1100)
        assertTrue(Sides.isAvatar(theirs, WIN, DP))
        assertTrue(Sides.isAvatar(mine, WIN, DP))
        assertFalse("a photo in a message is not an avatar", Sides.isAvatar(box(193, 700, 700, 1200), WIN, DP))
        assertFalse("nor an icon in the title bar", Sides.isAvatar(box(33, 60, 143, 170), WIN, DP))
        assertFalse("nor a square in the middle", Sides.isAvatar(box(480, 700, 590, 810), WIN, DP))
        val avatars = listOf(theirs, mine)
        assertEquals(Sides.LEFT, Sides.byAvatars(box(193, 700, 887, 820), avatars))
        assertEquals(Sides.RIGHT, Sides.byAvatars(box(193, 1000, 887, 1180), avatars))
        assertEquals(Sides.UNKNOWN, Sides.byAvatars(box(193, 1300, 887, 1400), avatars))
    }

    @Test fun callRecordsAreNotMessages() {
        for (t in listOf(
            "已取消", "对方已取消", "已拒绝", "对方已拒绝", "对方无应答", "未接听", "忙线未接听", "对方忙线中",
            "已在其他设备接听", "连接失败", "通话中断", "通话已结束", "通话时长 03:12", "通话时长 1:02:33",
            "Canceled", "Cancelled", "Call canceled", "Call Cancelled", "Canceled by caller", "Declined", "Call declined",
            "No answer", "Unanswered", "Line busy", "Call failed", "Duration 00:37", "Call Duration 03:12",
            // The phone icon read as a mark or a letter.
            "C 已取消", "已取消 C", "& Canceled", "© 对方已取消", "Cancelled.",
            // A voice message's length, and with its sound-wave icon read as brackets and dots
            // (learned as "likes abstract emoticons and brackets" in a 🍵 profile).
            "5\"", "12″", "59”", "3\" ((", "4\"(。", "2\"(•", "))) 12\"", "8\" C",
        )) assertTrue(t, Chat.isNotification(t))
        for (t in listOf(
            "我已经取消了", "取消吧", "订单已取消了吗", "I canceled", "Ok canceled", "cancel it", "declined the offer",
            "No answer yet?", "call me later", "5 mins", "12 点见", "正忙", "忙线", "3\"好", "等我 5\"",
        )) assertFalse(t, Chat.isNotification(t))
    }

    @Test fun aQuoteOfTheirsUnderMyReplyIsNotMine() {
        val reply = Chat.Bubble("好啊，几点", false, box(600, 700, 887, 760))
        val quote = Chat.Bubble("小雨：周六去爬山吗", false, box(560, 790, 887, 830))
        val theirs = Chat.Bubble("小雨说她也去", true, box(193, 900, 600, 960))
        assertEquals(listOf(reply, theirs), Chat.withoutQuotes(listOf(reply, quote, theirs), "小雨"))
        assertEquals(listOf(reply), Chat.withoutQuotes(listOf(reply, Chat.Bubble("Mia: see you", false, box(600, 800, 887, 840))), "Mia"))
        assertEquals(listOf(reply), Chat.withoutQuotes(listOf(reply, Chat.Bubble("小雨 ： 在吗", false, box(600, 800, 887, 840))), "小雨"))
        // No name, nothing taken out.
        assertEquals(listOf(reply, quote), Chat.withoutQuotes(listOf(reply, quote), ""))
        // A name that needs escaping.
        assertEquals(emptyList<Chat.Bubble>(), Chat.withoutQuotes(listOf(Chat.Bubble("A.B (work)：ok", true, box(0, 0, 1, 1))), "A.B (work)"))
    }

    @After fun chinese() { L.en = false }

    @Test fun theScreenshotSaysWhichSideIsWhom() {
        assertTrue(OpenRouter.SCREENSHOT_NOTE.contains("右边的气泡是我发的"))
        assertTrue(OpenRouter.SCREENSHOT_NOTE.contains("谁说的"))
        val p = OpenRouter.deepPrompt("小雨", "", null, null, null, listOf("对方" to "在吗", "我" to "在"), emptyMap(), fromOcr = true, hasImage = true)
        assertTrue(p.contains("「我」是我发的，「对方」是 Ta 发的"))
        assertTrue(p.contains(OpenRouter.SCREENSHOT_NOTE))
        L.en = true
        assertTrue(OpenRouter.SCREENSHOT_NOTE.contains("on the right are mine"))
    }
}
