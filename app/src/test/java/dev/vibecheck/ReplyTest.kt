package dev.vibecheck

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * The Soul chat from the bug report 「回复推荐很傻」, as laid out on screen (923 x 2000): the strip
 * of quick replies above the reply box was read as messages, so "they" had just said 「下午好」
 * and the drafts were 「下午好」「歇会」「嗯」.
 */
class ChipRowTest {

    private val win = Chat.Box(0, 0, 923, 2000)
    private fun box(l: Int, t: Int, r: Int, b: Int) = Chat.Box(l, t, r, b)

    private val title = listOf(
        "1分钟前" to box(180, 118, 260, 142),
        "38" to box(83, 140, 115, 175),          // unread count on the back button
        "加速" to box(287, 168, 335, 202),        // a button under the name
    )
    private val messages = listOf(
        "可以看看你的照片吗" to box(443, 265, 730, 300),
        "what" to box(193, 590, 268, 625),
        "no" to box(193, 755, 230, 785),
        "14:16" to box(432, 870, 492, 895),
        "这么害羞" to box(602, 965, 730, 1000),
        "不是美女，有什么好看的" to box(193, 1292, 548, 1328),
        "14:25" to box(432, 1410, 492, 1435),
        "自信才是美女" to box(540, 1505, 730, 1540),
    )
    private val chips = listOf(
        "下午好" to box(115, 1758, 210, 1792),
        "礼物" to box(330, 1758, 395, 1792),
        "桌球" to box(515, 1758, 580, 1792),
        "比心" to box(700, 1758, 765, 1792),
        "猜拳" to box(885, 1758, 923, 1792),
    )
    private val input = box(113, 1840, 795, 1925)

    private fun read(items: List<Pair<String, Chat.Box>>, input: Chat.Box? = this.input) =
        Chat.messageIndices(items, win, input).map { items[it] }.map { Chat.Bubble(it.first, Chat.isIncoming(it.second, win), it.second) }

    @Test fun theQuickReplyStripIsNotMessages() {
        val bubbles = read(title + messages + chips)
        assertEquals(listOf("可以看看你的照片吗", "what", "no", "这么害羞", "不是美女，有什么好看的", "自信才是美女"), bubbles.map { it.text })
        assertEquals(listOf(false, true, true, false, true, false), bubbles.map { it.incoming })
        // My message is the newest: nothing to answer, and no card pops up by itself.
        assertNull(Chat.triggerKey(Chat.order(bubbles)))
    }

    @Test fun theStripGoesInWhateverOrderTheTreeListsIt() {
        // The node tree is walked last child first, and repeats nodes.
        val bubbles = read(chips.reversed() + messages + chips.take(2) + title)
        assertEquals("自信才是美女", Chat.order(bubbles).last().text)
        assertFalse(bubbles.any { it.text in setOf("下午好", "礼物", "桌球", "比心", "猜拳") })
    }

    @Test fun ocrDropsTheStripToo() {
        val hint = "不知道说啥，讲个笑话也行" to box(145, 1865, 550, 1900)
        val (bubbles, _) = Chat.fromOcr(title + messages + chips + hint, win, emptyList())
        assertFalse(bubbles.any { it.text in setOf("下午好", "礼物", "比心", "猜拳", "38", "加速") })
        assertTrue(bubbles.map { it.text }.containsAll(listOf("what", "这么害羞", "自信才是美女")))
    }

    @Test fun whatSitsInTheTitleBarIsNotAMessage() {
        val bubbles = read(title + messages)
        assertFalse(bubbles.any { it.text == "38" || it.text == "加速" || it.text == "1分钟前" })
        // The first message under the bar stays, even clipped by it.
        assertTrue(Chat.messageIndices(listOf("好呀" to box(193, 222, 300, 262)), win).isNotEmpty())
    }

    @Test fun messagesOneAboveAnotherAreNeverARow() {
        // A short exchange, each message on its own row, with a time beside one of them.
        val items = listOf(
            "嗯" to box(193, 900, 230, 940),
            "好" to box(700, 1000, 730, 1040),
            "行" to box(193, 1100, 230, 1140),
            "ok" to box(193, 1200, 240, 1240),
            "10:32" to box(250, 1210, 320, 1240),
        )
        assertEquals(listOf("嗯", "好", "行", "ok"), read(items, null).map { it.text })
        // Two side by side are not a strip either: a message and a floating badge.
        assertEquals(2, Chat.messageIndices(listOf("这么害羞" to box(602, 965, 730, 1000), "3" to box(836, 950, 856, 985)), win).size)
    }

    @Test fun theComposeAreaGoesWhereverTheKeyboardPushesIt() {
        // Keyboard up: the reply box sits mid-screen, and a send button drawn as text is below its top.
        val up = box(113, 1080, 795, 1160)
        val items = listOf(
            "你是？" to box(193, 900, 290, 940),
            "发送" to box(800, 1095, 900, 1145),
        )
        assertEquals(listOf("你是？"), read(items, up).map { it.text })
    }

    @Test fun ocrKnowsTheReplyBoxFromTheKeyboard() {
        val ime = box(0, 1200, 923, 2000)
        val row = Chat.replyRowAbove(ime, win)!!
        val items = listOf(
            "你是？" to box(193, 950, 290, 990),
            "对我有点" to box(145, 1140, 300, 1180),    // a draft being typed, right above the keys
        )
        val (bubbles, _) = Chat.fromOcr(items, win, listOf(ime), row)
        assertEquals(listOf("你是？"), bubbles.map { it.text })
        // A window the keyboard has shrunk: the reply box is along its bottom.
        val shrunk = Chat.Box(0, 0, 923, 1200)
        assertEquals(1200, Chat.replyRowAbove(ime, shrunk)!!.bottom)
        assertEquals(listOf("你是？"), Chat.fromOcr(items, shrunk, emptyList(), Chat.replyRowAbove(ime, shrunk)).first.map { it.text })
        // A floating keyboard says nothing about where the reply box is.
        assertNull(Chat.replyRowAbove(box(200, 1200, 700, 1600), win))
        assertNull(Chat.replyRowAbove(null, win))
    }

    @Test fun theJumpToNewestPillIsNotAMessage() {
        assertTrue(Chat.isNotification("3条新消息"))
        assertTrue(Chat.isNotification("12 new messages"))
        assertFalse(Chat.isNotification("I got 3 new messages from her"))
    }
}

class ReplyPromptTest {

    @After fun chinese() { L.en = false }

    @Test fun theReadLineIsShownApartFromTheDrafts() {
        val r = OpenRouter.replies("判断：你刚夸完 Ta 还没回，先别追着照片问\n1. 哈哈不看也行，那你平时都玩啥\n2. 开玩笑的啦，不勉强你～\n3. 好啦不闹你了，今天过得咋样")
        assertEquals("你刚夸完 Ta 还没回，先别追着照片问", r.read)
        assertEquals(listOf("哈哈不看也行，那你平时都玩啥", "开玩笑的啦，不勉强你～", "好啦不闹你了，今天过得咋样"), r.drafts)
        val md = OpenRouter.replies("**判断**：轮到你回\n1. 好\n2. 行")
        assertEquals("轮到你回", md.read)
        assertEquals(listOf("好", "行"), md.drafts)
        val en = OpenRouter.replies("Read: they're teasing; tease back\n1. says who\n2. bold of you\n3. ok ok you win")
        assertEquals("they're teasing; tease back", en.read)
        assertEquals(3, en.drafts.size)
    }

    @Test fun anAnswerWithoutAReadLineStillGivesDrafts() {
        val r = OpenRouter.replies("1. 好的呀\n2. 明天见")
        assertNull(r.read)
        assertEquals(listOf("好的呀", "明天见"), r.drafts)
        // Unnumbered drafts after a read line: the read is not a draft.
        val plain = OpenRouter.replies("判断：接着聊\n好的呀\n明天见")
        assertEquals("接着聊", plain.read)
        assertEquals(listOf("好的呀", "明天见"), plain.drafts)
    }

    @Test fun thePromptSaysWhoseTurnItIs() {
        assertEquals("最后一句是我说的（「自信才是美女」），Ta 还没回。", OpenRouter.turn(listOf("对方" to "不是美女，有什么好看的", "我" to "自信才是美女")))
        assertEquals("最后一句是 Ta 说的，轮到我了。", OpenRouter.turn(listOf("对方" to "在吗")))
        assertNull(OpenRouter.turn(emptyList()))
        val p = OpenRouter.deepPrompt("…", "", null, null, null, listOf("对方" to "no", "我" to "这么害羞"), emptyMap(), false, false)
        assertTrue(p.contains("最后一句是我说的（「这么害羞」），Ta 还没回。"))
        assertTrue("Jev's read is a hint, not an order", p.contains("以对话为准"))
        L.en = true
        assertTrue(OpenRouter.turn(listOf("我" to "you up?"))!!.startsWith("The last message is mine"))
    }

    @Test fun draftsAreNoLongerThreeFixedStrategies() {
        val zh = OpenRouter.REPLY_SYSTEM
        assertFalse(zh.contains("第一条先接住情绪"))
        assertTrue("says what to do when I spoke last", zh.contains("最后一句是我说的"))
        assertTrue("no greeting out of nowhere", zh.contains("下午好"))
        assertTrue("asks for the read line", zh.contains("判断："))
        L.en = true
        val en = OpenRouter.REPLY_SYSTEM
        assertFalse(en.contains("catches the feeling first"))
        assertTrue(en.contains("Read: "))
    }

    @Test fun theDeepReadTalksToMeAboutThem() {
        assertTrue(OpenRouter.DEEP_SYSTEM.contains("称我为「你」"))
        assertTrue(OpenRouter.DEEP_SYSTEM.contains("说的是 Ta，不是我"))
    }
}

class ArchiveContextTest {

    private fun lines(vararg s: String) = s.map { (if (it.startsWith("我")) "我" else "对方") to it }

    @Test fun theScreenGetsTheHistoryBeforeIt() {
        val kept = lines("旧1", "我旧2", "旧3", "我旧4", "旧5", "我新6", "新7", "我新8")
        val screen = lines("旧5", "我新6", "新7", "我新8")
        assertEquals(lines("我旧2", "旧3", "我旧4", "旧5", "我新6", "新7", "我新8"), Archive.before(kept, screen, 7))
        assertEquals(kept, Archive.before(kept, screen, 30))
    }

    @Test fun aScreenScrolledUpGetsWhatCameBeforeItNotAfter() {
        val kept = lines("旧1", "我旧2", "旧3", "我旧4", "旧5", "我新6", "新7", "我新8")
        val screen = lines("旧3", "我旧4", "旧5")
        assertEquals(lines("旧1", "我旧2", "旧3", "我旧4", "旧5"), Archive.before(kept, screen, 30))
    }

    @Test fun soulsStripKeptByOlderVersionsIsDropped() {
        val strip = listOf("我" to "猜拳", "我" to "比心", "对方" to "礼物", "对方" to "下午好")
        val real = listOf("对方" to "不是美女，有什么好看的", "我" to "自信才是美女")
        assertEquals(real + real, Archive.withoutStrips(real + strip + real))
        assertEquals(real, Archive.withoutStrips(real + strip))
        // What people do send stays: a greeting, goodnights, one sticker word, two in a row.
        val kept = listOf("对方" to "下午好", "我" to "晚安", "对方" to "晚安", "我" to "晚安", "我" to "比心", "对方" to "猜拳")
        assertSame(kept, Archive.withoutStrips(kept))
        val three = listOf("我" to "礼物", "我" to "礼物", "我" to "礼物")
        assertSame(three, Archive.withoutStrips(three))
    }

    @Test fun whatDoesNotLineUpIsLeftAlone() {
        val screen = lines("甲", "我乙", "丙")
        assertEquals(screen, Archive.before(lines("旧1", "我旧2", "旧3"), screen, 30))
        assertEquals(screen, Archive.before(emptyList(), screen, 30))
        assertEquals(screen, Archive.before(lines("甲", "我乙", "丙"), screen, 3))
    }
}

class ProfileRetryTest {

    @After fun chinese() { L.en = false }

    @Test fun aDroppedConnectionIsTriedAgainAndSaidPlainly() {
        val abort = java.net.SocketException("Software caused connection abort")
        assertTrue(Judge.isTransient(abort))
        assertTrue(Judge.describe(abort).contains("网络中途断了"))
        val tls = javax.net.ssl.SSLException("Read error: ssl=0x7b: I/O error during system call, Software caused connection abort")
        assertTrue(Judge.isTransient(tls))
        assertTrue(Judge.describe(tls).contains("网络中途断了"))
        assertTrue(Judge.isTransient(java.net.SocketTimeoutException()))
        assertTrue(Judge.isTransient(OpenRouter.Failure(503, "")))
        assertTrue(Judge.isTransient(OpenRouter.Failure(0, "模型没写完")))
        L.en = true
        assertTrue(Judge.describe(abort).startsWith("The connection dropped"))
    }

    @Test fun aRejectedKeyOrABadCertificateIsNot() {
        assertFalse(Judge.isTransient(OpenRouter.Failure(401, "")))
        assertFalse(Judge.isTransient(OpenRouter.Failure(402, "")))
        assertFalse(Judge.isTransient(OpenRouter.Failure(403, "")))
        assertFalse(Judge.isTransient(javax.net.ssl.SSLHandshakeException("bad cert")))
        assertFalse(Judge.isTransient(IllegalStateException("no notes")))
        assertFalse(Judge.describe(javax.net.ssl.SSLHandshakeException("bad cert")).contains("断了"))
    }
}
