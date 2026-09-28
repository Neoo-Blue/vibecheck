package dev.vibecheck

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** What each call used is counted per kind, and shown in words. */
class UsageTest {

    @After fun chinese() { L.en = false }

    /** In place of org.json: "text:…" is a piece of the answer, "usage:N" the count N tokens in. */
    private fun piece(data: String): OpenRouter.Piece = when {
        data.startsWith("text:") -> OpenRouter.Piece(data.removePrefix("text:"), null)
        data.startsWith("usage:") -> OpenRouter.Piece(null, null,
            usage = OpenRouter.Usage(data.removePrefix("usage:").toInt(), 200, 2048, 0, 0.0012))
        else -> OpenRouter.Piece(null, null)
    }

    @Test fun whatACallUsedComesWithItsLastChunk() {
        val used = ArrayList<OpenRouter.Usage>()
        val stream = listOf("data: text:你好", "data: text:呀", "data: usage:3000", "data: [DONE]")
        assertEquals("你好呀", OpenRouter.collect(stream.iterator(), ::piece, onUsage = { used += it }))
        assertEquals(1, used.size)
        assertEquals(3000, used[0].prompt)
        assertEquals(2048, used[0].cached)
        // A provider that sends a running count on every chunk is counted once, at the end.
        used.clear()
        OpenRouter.collect(listOf("data: usage:10", "data: text:嗯", "data: usage:20").iterator(), ::piece, onUsage = { used += it })
        assertEquals(listOf(20), used.map { it.prompt })
        // None sent, none counted.
        used.clear()
        OpenRouter.collect(listOf("data: text:嗯").iterator(), ::piece, onUsage = { used += it })
        assertTrue(used.isEmpty())
    }

    @Test fun numbersAreEasyToRead() {
        assertEquals("850", Prefs.tokens(850))
        assertEquals("18k", Prefs.tokens(18_000))
        assertEquals("25.3k", Prefs.tokens(25_340))
        assertEquals("125k", Prefs.tokens(125_400))
        assertEquals("1.25M", Prefs.tokens(1_254_000))
        assertEquals("$0.0042", Prefs.dollars(0.0042))
        assertEquals("$0.123", Prefs.dollars(0.1234))
        assertEquals("$3.40", Prefs.dollars(3.4))
        assertEquals("<$0.0001", Prefs.dollars(0.00001))
    }

    @Test fun eachKindSaysWhatItUsed() {
        val drafts = Prefs.Spent(calls = 5, prompt = 18_000, cached = 6_000, completion = 3_400, reasoning = 1_700, cost = 0.021)
        assertEquals("5 次 · 21.4k token · $0.021", Prefs.describe(drafts))
        assertEquals("发出 18k（33% 命中缓存）· 收回 3.4k（50% 是思考）", Prefs.breakdown(drafts))
        // Counted before tokens were: calls alone.
        val old = Prefs.Spent(3, 0, 0, 0, 0, 0.0)
        assertEquals("3 次", Prefs.describe(old))
        assertNull(Prefs.breakdown(old))
        // A test in settings is not a call, only what it used.
        assertEquals("12 token", Prefs.describe(Prefs.Spent(0, 10, 0, 2, 0, 0.0)))
        val sum = drafts + old
        assertEquals(8, sum.calls)
        assertEquals(21_400L, sum.tokens)
        L.en = true
        assertEquals("5 calls · 21.4k token · $0.021", Prefs.describe(drafts))
        assertEquals("Sent 18k (33% cached) · received 3.4k (50% thinking)", Prefs.breakdown(drafts))
    }
}

/** Three messages in a row are one turn, judged once. */
class SettleTest {

    private val t = 1_000_000L

    @Test fun aMessageIsJudgedOnceItHasStoodForAMoment() {
        val s = Judge.Settle(quietMs = 3_000, maxMs = 8_000)
        assertEquals(3_000, s.wait("a", t))
        assertEquals("the same message: the wait runs on", 2_000, s.wait("a", t + 1_000))
        assertEquals(0, s.wait("a", t + 3_050))
    }

    @Test fun aBurstIsJudgedOnceWhenItStops() {
        val s = Judge.Settle(quietMs = 3_000, maxMs = 8_000)
        assertEquals(3_000, s.wait("a", t))
        assertEquals("another message: wait again", 3_000, s.wait("a|b", t + 1_500))
        assertEquals(3_000, s.wait("a|b|c", t + 4_000))
        assertEquals(0, s.wait("a|b|c", t + 7_000))
        // What comes after that is a new turn, and waits its own moment.
        assertEquals(3_000, s.wait("a|b|c|d", t + 20_000))
    }

    @Test fun aScreenThatKeepsChangingIsJudgedAnyway() {
        val s = Judge.Settle(quietMs = 3_000, maxMs = 8_000)
        var last = -1L
        for (i in 0..8) last = s.wait("k$i", t + i * 1_000L)
        assertEquals("eight seconds after it began", 0, last)
        // And the next change after that judgment waits again rather than going straight out.
        assertEquals(3_000, s.wait("k9", t + 9_000))
    }
}

/** Notes, merges and the profile of me are not paid for twice. */
class NotesReuseTest {

    @Test fun theNewestStretchKeepsItsNotesUntilItHasGrown() {
        assertTrue(Me.stillServes(6_000, 6_000))
        assertTrue(Me.stillServes(6_000, 8_999))
        assertFalse("half again as long", Me.stillServes(6_000, 9_000))
        // A short stretch may grow by a few messages before it is noted again.
        assertTrue(Me.stillServes(1_000, 2_400))
        assertFalse(Me.stillServes(1_000, 2_500))
        // Shorter than what was noted: the history changed, noted again.
        assertFalse(Me.stillServes(6_000, 5_000))
        assertFalse(Me.stillServes(0, 100))
    }

    @Test fun notesAreFoundByTheirTextOrWhereTheStretchBegins() {
        val notes = mapOf("h1" to "note one")
        val starts = mapOf("s1" to "h1 6000", "bad" to "h1")
        assertEquals("h1" to "note one", Me.reuse(notes, starts, "h1", "s1", 6_000, newest = false))
        assertEquals("h1" to "note one", Me.reuse(notes, starts, "h2", "s1", 7_000, newest = true))
        assertNull("a finished stretch is noted in full", Me.reuse(notes, starts, "h2", "s1", 7_000, newest = false))
        assertNull("grown too much", Me.reuse(notes, starts, "h3", "s1", 9_500, newest = true))
        assertNull(Me.reuse(notes, starts, "h2", "s9", 6_100, newest = true))
        assertNull(Me.reuse(notes, starts, "h2", "bad", 6_100, newest = true))
        assertNull("the notes themselves are gone", Me.reuse(emptyMap(), starts, "h2", "s1", 6_100, newest = true))
    }

    @Test fun aStretchKeepsWhereItBeginsAsItGrows() {
        val history = (1..40).map { (if (it % 2 == 0) "我" else "对方") to "第 $it 条消息，说点什么" }
        val chunks = Archive.chunks(history, 200)
        val start = Me.startKey("wx|小明", history, chunks.last())
        val grown = history + listOf("对方" to "新的一条")
        val again = Archive.chunks(grown, 200)
        assertEquals(chunks.last().first, again.last().first)
        assertEquals(start, Me.startKey("wx|小明", grown, again.last()))
        assertNotEquals("another chat", start, Me.startKey("wx|小红", grown, again.last()))
        assertNotEquals(start, Me.startKey("wx|小明", history, chunks.first()))
    }

    @Test fun mergesAreKeptByWhatWasMerged() {
        val k = Profile.mergeKey(listOf("笔记一", "笔记二"))
        assertTrue(k.startsWith("m"))
        assertEquals(k, Profile.mergeKey(listOf("笔记一", "笔记二")))
        assertNotEquals(k, Profile.mergeKey(listOf("笔记一", "笔记三")))
        // Never taken for a stretch's own key.
        assertNotEquals(Archive.hash("笔记一\n\n笔记二"), k)
    }
}

/** What is asked about one person again and again starts the same way, so providers can cache it. */
class PromptCacheTest {

    @After fun chinese() { L.en = false }

    private fun prompt(said: String, elsewhere: String?, style: String) = OpenRouter.deepPrompt(
        "小明", "同事", style, "只是闲聊/危1/随便聊", "共看到 40 条", listOf("对方" to said), emptyMap(), false, false,
        profile = "【我们怎么相处】常一起吐槽工作", me = "【我最近在忙】新版本上线", theirStyle = "话很短", elsewhere = elsewhere,
    )

    @Test fun whatChangesComesAfterWhatStays() {
        val a = prompt("明天开会吗", "小红：我到了", "平均 8 字（共 30 条）")
        val b = prompt("那周五呢", "小红：我到了\n小刚：收到", "平均 8 字（共 31 条）")
        val same = a.commonPrefixWith(b)
        assertTrue(same.contains("【我们怎么相处】常一起吐槽工作"))
        assertTrue(same.contains("【我最近在忙】新版本上线"))
        // What I said elsewhere today changes by the minute: after what is known, before the messages.
        val at = a.indexOf("我今天在别的聊天里说过：\n小红：我到了")
        assertTrue(at > a.indexOf("这段关系的长期观察"))
        assertTrue(at < a.indexOf("最近的对话"))
        assertFalse(prompt("嗯", null, "x").contains("我今天在别的聊天里说过"))
    }
}

/** Read by OCR, a message a character off is the same message. */
class SameTurnTest {

    private val judged = listOf("我" to "下午三点吧", "对方" to "明天几点见面比较好呢", "我" to "三点", "对方" to "好的那就这么定了")

    @Test fun aCharacterOffIsTheSameTurn() {
        assertTrue(Chat.sameTurn(judged, listOf("对方" to "明天几点见面比较好呢", "我" to "三点", "对方" to "好的那就这么定啦")))
        assertTrue(Chat.sameTurn(judged, judged.takeLast(3)))
    }

    @Test fun aNewMessageIsNot() {
        assertFalse(Chat.sameTurn(judged, judged.drop(2) + ("对方" to "到时候见")))
        assertFalse("short texts must match exactly", Chat.sameTurn(listOf("对方" to "好的"), listOf("对方" to "好吧")))
        assertFalse("who said it matters", Chat.sameTurn(listOf("对方" to "明天几点见面"), listOf("我" to "明天几点见面")))
        assertFalse(Chat.sameTurn(judged, judged.takeLast(2)))
        assertFalse(Chat.sameTurn(emptyList(), emptyList()))
    }
}
