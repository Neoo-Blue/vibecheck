package dev.vibecheck

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * A Soul chat as laid out on screen (923 x 2000): the strip of quick replies above the reply box
 * was read as messages, so "they" had just said 「下午好」 and the drafts answered that.
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
        "周末一起去看展好吗" to box(443, 265, 730, 300),
        "哪个" to box(193, 590, 268, 625),
        "嗯" to box(193, 755, 230, 785),
        "14:16" to box(432, 870, 492, 895),
        "去不去嘛" to box(602, 965, 730, 1000),
        "这周有点忙，改天再说吧" to box(193, 1292, 548, 1328),
        "14:25" to box(432, 1410, 492, 1435),
        "那等你忙完呀" to box(540, 1505, 730, 1540),
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
        assertEquals(listOf("周末一起去看展好吗", "哪个", "嗯", "去不去嘛", "这周有点忙，改天再说吧", "那等你忙完呀"), bubbles.map { it.text })
        assertEquals(listOf(false, true, true, false, true, false), bubbles.map { it.incoming })
        // My message is the newest: nothing to answer, and no card pops up by itself.
        assertNull(Chat.triggerKey(Chat.order(bubbles)))
    }

    @Test fun theStripGoesInWhateverOrderTheTreeListsIt() {
        // The node tree is walked last child first, and repeats nodes.
        val bubbles = read(chips.reversed() + messages + chips.take(2) + title)
        assertEquals("那等你忙完呀", Chat.order(bubbles).last().text)
        assertFalse(bubbles.any { it.text in setOf("下午好", "礼物", "桌球", "比心", "猜拳") })
    }

    @Test fun ocrDropsTheStripToo() {
        val hint = "不知道说啥，讲个笑话也行" to box(145, 1865, 550, 1900)
        val (bubbles, _) = Chat.fromOcr(title + messages + chips + hint, win, emptyList())
        assertFalse(bubbles.any { it.text in setOf("下午好", "礼物", "比心", "猜拳", "38", "加速") })
        assertTrue(bubbles.map { it.text }.containsAll(listOf("哪个", "去不去嘛", "那等你忙完呀")))
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
        assertEquals(2, Chat.messageIndices(listOf("去不去嘛" to box(602, 965, 730, 1000), "3" to box(836, 950, 856, 985)), win).size)
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
            "等我一下" to box(145, 1140, 300, 1180),    // a draft being typed, right above the keys
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
        val r = OpenRouter.replies("判断：你刚约了 Ta 还没回，先别追问\n1. 不急，你看哪天方便\n2. 忙完记得休息呀～\n3. 好啦不催你了，今天过得咋样")
        assertEquals("你刚约了 Ta 还没回，先别追问", r.read)
        assertEquals(listOf("不急，你看哪天方便", "忙完记得休息呀～", "好啦不催你了，今天过得咋样"), r.drafts)
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
        assertEquals("最后一句是我说的（「那等你忙完呀」），Ta 还没回。", OpenRouter.turn(listOf("对方" to "这周有点忙，改天再说吧", "我" to "那等你忙完呀")))
        assertEquals("最后一句是 Ta 说的，轮到我了。", OpenRouter.turn(listOf("对方" to "在吗")))
        assertNull(OpenRouter.turn(emptyList()))
        val p = OpenRouter.deepPrompt("…", "", null, null, null, listOf("对方" to "嗯", "我" to "去不去嘛"), emptyMap(), false, false)
        assertTrue(p.contains("最后一句是我说的（「去不去嘛」），Ta 还没回。"))
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
        val real = listOf("对方" to "这周有点忙，改天再说吧", "我" to "那等你忙完呀")
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

/** Answers arrive as a stream, so the first draft shows while the model writes the rest. */
class StreamTest {

    @After fun chinese() { L.en = false }

    /**
     * What the tests use in place of org.json: "text:…" is a piece of text, "think:…" a piece of
     * the model's thinking, "error:…" an error.
     */
    private fun piece(data: String): OpenRouter.Piece = when {
        data.startsWith("error:") -> OpenRouter.Piece(null, OpenRouter.Failure(0, data.removePrefix("error:")))
        data.startsWith("text:") -> OpenRouter.Piece(data.removePrefix("text:").replace("\\n", "\n"), null)
        data.startsWith("think:") -> OpenRouter.Piece(null, null, alive = true)
        else -> OpenRouter.Piece(null, null)
    }

    private val ping = ": OPENROUTER PROCESSING"

    /** A stream read against a clock: each line arrives at its second. */
    private fun timed(deadline: OpenRouter.Deadline, vararg lines: Pair<Int, String>): String {
        var clock = 0L
        val it = lines.map { (sec, line) -> { clock = sec * 1000L; line } }.iterator()
        val stream = object : Iterator<String> {
            override fun hasNext() = it.hasNext()
            override fun next() = it.next()()
        }
        return OpenRouter.collect(stream, ::piece, deadline, now = { clock })
    }

    private val drafts = OpenRouter.Deadline(quietMs = 40_000, answerMs = 75_000, totalMs = 150_000)

    @Test fun eventLinesAreReadAndCommentsSkipped() {
        assertEquals("{\"a\":1}", OpenRouter.sseData("data: {\"a\":1}"))
        assertEquals("[DONE]", OpenRouter.sseData("data:[DONE]"))
        assertNull(OpenRouter.sseData(": OPENROUTER PROCESSING"))
        assertNull(OpenRouter.sseData(""))
        assertNull(OpenRouter.sseData("event: message"))
    }

    @Test fun eachFinishedLineIsShownAsSoonAsItIsWritten() {
        val stream = listOf(
            ": OPENROUTER PROCESSING",
            "data: text:判断：你刚",
            "data: text:约完\\n1. 不急",
            "",
            "data: reasoning-only chunk",
            "data: text:你慢慢看\\n2. 忙完记得休息",
            "data: text:呀",
            "data: [DONE]",
            "data: text:never read",
        )
        val shown = ArrayList<String>()
        val all = OpenRouter.collect(stream.iterator(), ::piece) { shown += it }
        assertEquals("判断：你刚约完\n1. 不急你慢慢看\n2. 忙完记得休息呀", all)
        assertEquals(listOf("判断：你刚约完\n", "判断：你刚约完\n1. 不急你慢慢看\n"), shown)
        // What is shown can already be parsed into the read and the finished drafts.
        val r = OpenRouter.replies(shown.last())
        assertEquals("你刚约完", r.read)
        assertEquals(listOf("不急你慢慢看"), r.drafts)
    }

    @Test fun aModelStuckInAQueueIsGivenUpOnDespiteThePings() {
        val e = assertThrows(OpenRouter.Failure::class.java) {
            timed(drafts, 0 to ping, 10 to ping, 20 to ping, 30 to ping, 41 to ping, 45 to "data: text:too late")
        }
        assertEquals("等了 41 秒还没开始写", e.message)
        assertTrue("the backup is asked", OpenRouter.worthAnotherModel(e))
    }

    @Test fun anAnswerThatIsComingIsWaitedFor() {
        assertEquals("好呀\n周末见", timed(drafts, 0 to ping, 30 to "data: text:好呀\\n", 65 to "data: text:周末见", 70 to "data: [DONE]"))
        // Thinking is work too: nothing written for a minute, but thinking all the while.
        assertEquals("嗯", timed(drafts, 0 to ping, 25 to "data: think:…", 55 to "data: think:…", 70 to "data: text:嗯", 72 to "data: [DONE]"))
        // No deadline, no clock: as long as it takes.
        assertEquals("嗯", OpenRouter.collect(listOf(ping, "data: text:嗯").iterator(), ::piece))
    }

    @Test fun thinkingThatNeverTurnsIntoAnAnswerIsGivenUpOn() {
        val e = assertThrows(OpenRouter.Failure::class.java) {
            timed(drafts, 20 to "data: think:…", 40 to "data: think:…", 60 to "data: think:…", 76 to "data: think:…")
        }
        assertEquals("想了 76 秒还没开始回答", e.message)
    }

    @Test fun anAnswerThatStopsHalfWayIsGivenUpOn() {
        val e = assertThrows(OpenRouter.Failure::class.java) {
            timed(drafts, 5 to "data: text:好", 20 to ping, 46 to ping, 50 to "data: text:的")
        }
        assertEquals("写到一半停了 41 秒", e.message)
        L.en = true
        val en = assertThrows(OpenRouter.Failure::class.java) { timed(drafts, 0 to ping, 50 to ping) }
        assertEquals("Nothing after 50s", en.message)
    }

    @Test fun anAnswerIsNotWrittenForever() {
        val steady = (0..16).map { it * 10 to "data: text:字" }.toTypedArray()
        val e = assertThrows(OpenRouter.Failure::class.java) { timed(drafts, *steady) }
        assertEquals("写了 160 秒还没写完", e.message)
    }

    @Test fun anErrorInTheStreamEndsIt() {
        val stream = listOf("data: text:好", "data: error:Provider disconnected unexpectedly", "data: text:的")
        val e = assertThrows(OpenRouter.Failure::class.java) { OpenRouter.collect(stream.iterator(), ::piece, null) }
        assertEquals("Provider disconnected unexpectedly", e.message)
        assertTrue("a cut-off stream is worth another try", Judge.isTransient(e))
    }

    @Test fun thinkingIsAskedForAgainOnlyWhenAModelRequiresIt() {
        val mandatory = OpenRouter.Failure(400, "HTTP 400: {\"error\":{\"message\":\"Reasoning is mandatory for this endpoint and cannot be disabled.\"}}")
        assertTrue(OpenRouter.thinkingRequired(OpenRouter.Think.OFF, mandatory))
        assertFalse(OpenRouter.thinkingRequired(OpenRouter.Think.LOW, mandatory))
        assertFalse(OpenRouter.thinkingRequired(OpenRouter.Think.OFF, OpenRouter.Failure(400, "HTTP 400: bad image")))
        assertFalse(OpenRouter.thinkingRequired(OpenRouter.Think.OFF, OpenRouter.Failure(401, "HTTP 401: reasoning")))
    }

    @Test fun aModelThatReadsNoImagesGetsTheTextAlone() {
        assertTrue(OpenRouter.noImages(OpenRouter.Failure(404, "HTTP 404: {\"error\":{\"message\":\"No endpoints found that support image input\"}}")))
        assertFalse(OpenRouter.noImages(OpenRouter.Failure(404, "HTTP 404: model not found")))
        assertFalse(OpenRouter.noImages(OpenRouter.Failure(401, "image")))
        val p = OpenRouter.deepPrompt("小明", "", null, null, null, listOf("对方" to "在吗"), emptyMap(), true, true)
        assertTrue(p.contains(OpenRouter.SCREENSHOT_NOTE))
        assertFalse(p.replace(OpenRouter.SCREENSHOT_NOTE, "").contains("以截图为准"))
    }

    @Test fun theFastestProviderUnlessTheSlugSaysOtherwise() {
        assertEquals("throughput", OpenRouter.providerSort("deepseek/deepseek-v4.1-flash"))
        assertEquals("throughput", OpenRouter.providerSort("~anthropic/claude-haiku-latest"))
        assertNull(OpenRouter.providerSort("deepseek/deepseek-v4-pro:nitro"))
        assertNull(OpenRouter.providerSort("deepseek/deepseek-v4.1-flash:floor"))
    }

    @Test fun theOldExperimentalVisionDefaultMovesOn() {
        assertEquals(Prefs.DEFAULT_FAST, Prefs.fastOrDefault(""))
        assertEquals(Prefs.DEFAULT_FAST, Prefs.fastOrDefault("  "))
        assertEquals(Prefs.DEFAULT_FAST, Prefs.fastOrDefault("deepseek/deepseek-v4-flash-vision-exp"))
        assertEquals("anthropic/claude-haiku-4.5", Prefs.fastOrDefault(" anthropic/claude-haiku-4.5 "))
    }

    @Test fun defaultsStoredByOlderSettingsPagesMoveToTheNewDefault() {
        val fast = setOf("deepseek/deepseek-v4-flash-vision-exp", "deepseek/deepseek-v4.1-flash")
        assertTrue(Prefs.wasDefault(" deepseek/deepseek-v4.1-flash", fast))
        assertFalse(Prefs.wasDefault("moonshotai/kimi-k3", fast))
        // From now on the default is stored as nothing, and a real choice as itself.
        assertEquals("", Prefs.stored(Prefs.DEFAULT_FAST, Prefs.DEFAULT_FAST))
        assertEquals("", Prefs.stored("  ", Prefs.DEFAULT_FAST))
        assertEquals("deepseek/deepseek-v4.1-flash", Prefs.stored("deepseek/deepseek-v4.1-flash ", Prefs.DEFAULT_FAST))
        assertEquals(Prefs.DEFAULT_FAST, Prefs.fastOrDefault(Prefs.stored(Prefs.DEFAULT_FAST, Prefs.DEFAULT_FAST)))
    }

    @Test fun theModelOptionsAreWellFormedAndStartWithTheDefaults() {
        val slug = Regex("""^[a-z0-9-]+/[a-z0-9.\-]+$""")
        for (o in Models.REPLY + Models.DEEP) {
            assertTrue(o.id, slug.matches(o.id))
            assertTrue(o.name, o.about.isNotBlank())
        }
        assertEquals(Prefs.DEFAULT_FAST, Models.REPLY.first().id)
        assertEquals(Prefs.DEFAULT_DEEP, Models.DEEP.first().id)
        assertEquals("Kimi K3", Models.option(Models.REPLY, " moonshotai/kimi-k3 ")?.name)
        assertNull("typed in by hand", Models.option(Models.REPLY, "anthropic/claude-opus-5"))
        // What cannot do adult replies says so.
        assertTrue(Models.option(Models.REPLY, "qwen/qwen3.8-max-0902")!!.about.contains("不写成人回复"))
    }

    @Test fun adultDraftsOnlyWhenSwitchedOnAndOnlyAtTheirLevel() {
        assertFalse(OpenRouter.replySystem(false).contains("性话题"))
        val on = OpenRouter.replySystem(true)
        for (rule in listOf("性话题", "尺度看 Ta 的反应", "Ta 说不", "未成年")) assertTrue(rule, on.contains(rule))
        assertTrue("the output format still comes last", on.trimEnd().endsWith("不解释。"))
        assertTrue(OpenRouter.deepSystem(true).contains("性方面的潜台词"))
        assertFalse(OpenRouter.deepSystem(false).contains("性方面"))
        L.en = true
        val en = OpenRouter.replySystem(true)
        assertTrue(en.contains("under 18") && en.contains("never further than they have shown they want"))
        assertFalse(OpenRouter.replySystem(false).contains("under 18"))
    }
}

/**
 * An emoji name in the title: a notification sliding over the title bar was cut out and kept as
 * the picture of the person's name.
 */
class NamePictureTest {

    private val win = Chat.Box(0, 0, 923, 2000)

    @Test fun aNotificationOverTheTitleBarCoversIt() {
        val statusBar = Chat.Box(0, 0, 923, 95)
        assertFalse("the status bar alone ends above the name", Person.titleCovered(listOf(statusBar), win))
        val withNotification = Chat.Box(0, 0, 923, 330)
        assertTrue(Person.titleCovered(listOf(statusBar, withNotification), win))
        val navBar = Chat.Box(0, 1950, 923, 2000)
        assertFalse(Person.titleCovered(listOf(navBar), win))
    }

    @Test fun ourOwnCardOverTheTitleBarCoversIt() {
        assertTrue(Person.titleCovered(listOf(Chat.Box(46, 110, 877, 1700)), win))
        assertFalse("a card lower down leaves the name alone", Person.titleCovered(listOf(Chat.Box(240, 770, 900, 1730)), win))
        assertFalse("the bubble at the edge is beside the name, not over it", Person.titleCovered(listOf(Chat.Box(870, 100, 915, 190)), win))
    }

    private fun picture(w: Int, h: Int, ink: (Int, Int) -> Boolean) =
        IntArray(w * h) { i -> if (ink(i % w, i / w)) 0xFF202020.toInt() else 0xFFF5F5F5.toInt() }

    @Test fun signaturesTellNamesApart() {
        val cup = picture(60, 40) { x, y -> (x - 30) * (x - 30) + (y - 20) * (y - 20) < 150 }
        val cupAgain = picture(62, 40) { x, y -> (x - 31) * (x - 31) + (y - 20) * (y - 20) < 150 }
        val banner = picture(400, 40) { x, y -> y in 8..14 && x % 9 < 6 || y in 26..31 && x < 180 && x % 7 < 5 }
        val a = Person.signature(cup, 60, 40)
        assertTrue("the same emoji a pixel wider", Person.samePicture(a, Person.signature(cupAgain, 62, 40)))
        assertFalse(Person.samePicture(a, Person.signature(banner, 400, 40)))
    }

    @Test fun aPictureIsKeptOnlyOnceSeenAlike() {
        val cup = IntArray(36) { if (it % 12 in 5..6) 40 else 240 }
        val banner = IntArray(36) { if (it / 12 == 0) 90 else 200 }
        val votes = Person.PictureVotes()
        assertFalse("one look is not enough", Person.keepPicture(null, cup, votes))
        assertTrue(Person.keepPicture(null, cup, votes))
        // A wrong picture already kept is replaced after three agreeing looks, not before.
        val fresh = Person.PictureVotes()
        assertFalse(Person.keepPicture(banner, cup, fresh))
        assertFalse(Person.keepPicture(banner, cup, fresh))
        assertTrue(Person.keepPicture(banner, cup, fresh))
        // Looks that keep changing replace nothing; a look that matches what is kept resets the count.
        val flicker = Person.PictureVotes()
        repeat(4) { assertFalse(Person.keepPicture(cup, if (it % 2 == 0) banner else IntArray(36) { 128 }, flicker)) }
        assertFalse(Person.keepPicture(cup, banner, flicker))
        assertFalse(Person.keepPicture(cup, cup, flicker))
        assertFalse(Person.keepPicture(cup, banner, flicker))
    }
}

/** What the People tab shows about a person. */
class PeoplePageTest {

    @After fun chinese() { L.en = false }

    @Test fun oneLineAboutThemComesFromHowYouGetAlong() {
        val profile = "【Ta 是谁】\n• 在一所学校当老师\n【我们怎么相处】\n• 常一起吐槽工作，有事第一个找对方\n【Ta 喜欢】\n• 爬山"
        assertEquals("常一起吐槽工作，有事第一个找对方", Profile.oneLine(profile))
        assertEquals("在一所学校当老师", Profile.oneLine("【Ta 是谁】\n• 在一所学校当老师"))
        assertEquals("对方是我的好友，关系亲密，说话很…", Profile.oneLine("• 对方是我的好友，关系亲密，说话很随便，常互相开玩笑。", max = 17))
        assertNull(Profile.oneLine(""))
    }

    @Test fun whatJudgingTaughtIsSaidInWords() {
        val m = Learner.Model(dangerBias = 0.12, updates = 9)
        m.arms["*|在表达不满|先道歉"] = Learner.Arm(4, 0.5)
        m.arms["*|在开玩笑或一起感慨|接梗顺着聊"] = Learner.Arm(3, -0.3)
        m.arms["*|*|先道歉"] = Learner.Arm(9, 0.4)            // every intent at once: not said
        m.arms["*|单纯想知道答案|正面回答问题"] = Learner.Arm(2, 0.9)  // too few to say
        val lines = Learner.plain(m)
        assertTrue(lines[0], lines[0].contains("更容易出问题"))
        assertTrue(lines.any { it.contains("在表达不满") && it.contains("先道歉") && it.contains("缓和") && it.contains("4 次") })
        assertTrue(lines.any { it.contains("接梗顺着聊") && it.contains("更僵") })
        assertEquals(3, lines.size)
        assertTrue(Learner.plain(Learner.Model()).isEmpty())
        L.en = true
        assertTrue(Learner.plain(Learner.Model(dangerBias = -0.2)).single().startsWith("They are easier going"))
    }
}
