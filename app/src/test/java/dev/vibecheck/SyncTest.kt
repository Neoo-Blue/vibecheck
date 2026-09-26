package dev.vibecheck

import org.junit.Assert.*
import org.junit.Test

/** Passive counting: every message counted once, however the chat is scrolled. */
class SyncTest {

    private fun t(s: String) = "对方" to s
    private fun m(s: String) = "我" to s

    @Test fun newMessagesBelowTheTailAreCountedOnce() {
        val tail = listOf(t("在吗"), m("在"), t("吃了吗"), m("还没"))
        val page = listOf(m("在"), t("吃了吗"), m("还没"), t("一起？"))
        val first = Chat.sync(tail, page, reentry = false)
        assertEquals(listOf(t("一起？")), first.fresh)
        assertTrue(first.aligned)
        // The same screen again adds nothing: sitting on a chat does not inflate anything.
        val again = Chat.sync(first.tail, page, reentry = false)
        assertEquals(emptyList<Pair<String, String>>(), again.fresh)
    }

    @Test fun scrollingUpAndBackDownCountsNothingTwice() {
        val tail = (1..40).map { if (it % 2 == 0) m("消息$it") else t("消息$it") }
        // Up into what was already counted: recognised, nothing new.
        val up = Chat.sync(tail, tail.subList(9, 21), reentry = false)
        assertEquals(emptyList<Pair<String, String>>(), up.fresh)
        assertEquals(tail, up.tail)
        // Further up, into history never counted: nothing lines up, and nothing is counted.
        val older = (1..12).map { t("很久以前$it") }
        val far = Chat.sync(tail, older, reentry = false)
        assertEquals(emptyList<Pair<String, String>>(), far.fresh)
        assertEquals("the tail is kept for the way back down", tail, far.tail)
        // Back at the bottom, one new message arrived meanwhile: exactly that one is new.
        val bottom = tail.takeLast(11) + t("你还在吗")
        assertEquals(listOf(t("你还在吗")), Chat.sync(far.tail, bottom, reentry = false).fresh)
    }

    @Test fun openingAChatAfterBeingAwayCountsTheNewScreen() {
        val tail = listOf(t("晚安"), m("晚安"))
        val page = listOf(t("早"), t("醒了吗"), m("刚醒"))
        assertEquals(page, Chat.sync(tail, page, reentry = true).fresh)
        assertEquals("without re-entry an unknown screen is history", emptyList<Pair<String, String>>(),
            Chat.sync(tail, page, reentry = false).fresh)
    }

    @Test fun aMisreadLineDoesNotBreakTheAlignment() {
        val tail = listOf(t("好呀"), m("走吧"), t("几点"), m("八点"))
        // OCR reads the clipped top bubble as 好啊 in this frame.
        val page = listOf(t("好啊"), m("走吧"), t("几点"), m("八点"), t("到了吗"))
        assertEquals(listOf(t("到了吗")), Chat.sync(tail, page, reentry = false).fresh)
    }

    @Test fun repeatsStayRepeats() {
        val tail = listOf(t("在吗"), m("在"), t("ok"))
        val page = listOf(t("在吗"), m("在"), t("ok"), t("ok"))
        assertEquals("a second ok is a second message", listOf(t("ok")), Chat.sync(tail, page, reentry = false).fresh)
    }

    @Test fun aBurstThatLeavesOneDistinctiveLineStillLinesUp() {
        // Keyboard up, three bubbles visible, two new messages pushed the rest of the tail away.
        val tail = listOf(m("在吗"), t("你到哪了我在门口等你"))
        val page = listOf(t("你到哪了我在门口等你"), t("人呢"), t("？？"))
        assertEquals(listOf(t("人呢"), t("？？")), Chat.sync(tail, page, reentry = false).fresh)
        // A short line alone is too common to trust.
        val shortTail = listOf(m("在吗"), t("ok"))
        assertNull(Chat.alignEnd(shortTail, listOf(t("ok"), m("好"), t("走吧"))))
    }

    @Test fun aLoneCoincidentalMatchIsNotAnAlignment() {
        val tail = listOf(t("你好"), m("在干嘛"), t("哈哈"))
        val history = listOf(m("明天见"), t("哈哈"), m("好的"), t("晚安"))
        assertNull(Chat.alignEnd(tail, history))
        assertEquals(emptyList<Pair<String, String>>(), Chat.sync(tail, history, reentry = false).fresh)
    }

    @Test fun theFirstLookCountsTheScreenAndOldStorageMigrates() {
        val page = listOf(t("在吗"), m("在"), t("吃了吗"))
        assertEquals(page, Chat.sync(emptyList(), page, reentry = false).fresh)
        // Older versions stored only the last text seen.
        assertEquals(listOf(m("在"), t("吃了吗")), Chat.sync(emptyList(), page, false, legacyLast = "在吗").fresh)
        val lost = Chat.sync(emptyList(), page, false, legacyLast = "早就滚出屏幕的一句")
        assertEquals("not found: count nothing rather than everything", emptyList<Pair<String, String>>(), lost.fresh)
        assertEquals(page, lost.tail)
    }

    @Test fun ocrJitterIsTheSameLineButShortWordsMustMatchExactly() {
        assertTrue(Chat.similar("我们明天下午三点见", "我们明天下午三点兑"))
        assertTrue(Chat.similar("hello there", "hello there!"))
        assertTrue(Chat.similar("see you  soon", "see you soon"))
        assertFalse(Chat.similar("好呀", "好啊"))
        assertFalse(Chat.similar("ok", "okay"))
        assertFalse(Chat.similar("我们明天见", "你们昨天走"))
        // Long texts: the slack is measured on what is compared, not on the full length.
        val long = "今天".repeat(60)
        assertFalse(Chat.similar(long, "明天".repeat(60)))
        assertTrue(Chat.similar(long, long.dropLast(1) + "！"))
    }

    @Test fun tailSurvivesSaveLoad() {
        val tail = listOf(t("第一行\n第二行"), m("a\tb"), t("ok"))
        val back = Person.loadTail(Person.saveTail(tail))
        assertEquals(3, back.size)
        assertEquals(listOf("对方", "我", "对方"), back.map { it.first })
        assertTrue(Chat.same(back[0], tail[0]))                      // same line, whitespace aside
        assertEquals(emptyList<Pair<String, String>>(), Person.loadTail("garbage-without-a-tab"))
    }
}

class RelationTimingTest {

    private fun theirs(t: String) = Chat.Bubble(t, true, Chat.Box(140, 100, 700, 180))
    private fun mine(t: String) = Chat.Bubble(t, false, Chat.Box(380, 200, 940, 280))
    private val t0 = 1_700_000_000_000L
    private val hour = 3_600_000L

    @Test fun aReplyFirstSeenTogetherWithTheirMessageIsNotASample() {
        val s = Relation.Stats()
        Relation.observe(s, listOf(theirs("在吗"), mine("在")), t0)
        assertEquals("no zero-second reply times", 0, s.replySamples)
        assertEquals(0L, s.awaitingSince)
    }

    @Test fun onlyLiveBatchesSayWhoOpened() {
        val s = Relation.Stats()
        Relation.observe(s, listOf(theirs("早"), mine("早")), t0, live = false)
        assertEquals(1, s.theirMsgs)
        assertEquals(1, s.myMsgs)
        assertEquals("a screen counted on opening has no known opener", 0, s.sessions)
        Relation.observe(s, listOf(theirs("在忙吗")), t0 + 5 * hour)
        assertEquals(1, s.sessions)
        assertEquals(1, s.theyStarted)
    }

    @Test fun oneReplyFromAnotherDeviceDaysLaterDoesNotSwampTheAverage() {
        val s = Relation.Stats()
        Relation.observe(s, listOf(theirs("在吗")), t0)
        Relation.observe(s, listOf(mine("sorry 才看到")), t0 + 72 * hour)
        assertEquals(1, s.replySamples)
        assertEquals(24 * 3600L, s.replySecTotal)
    }

    @Test fun daysAreLocalDays() {
        // 23:30 and 00:30 in UTC+8 are two days there, but the same UTC day.
        val tz = 8 * hour
        val lateEvening = java.time.Instant.parse("2026-01-01T15:30:00Z").toEpochMilli()
        val utc = Relation.Stats()
        Relation.observe(utc, listOf(theirs("a")), lateEvening)
        Relation.observe(utc, listOf(theirs("b")), lateEvening + hour)
        val local = Relation.Stats()
        Relation.observe(local, listOf(theirs("a")), lateEvening, tzOffsetMs = tz)
        Relation.observe(local, listOf(theirs("b")), lateEvening + hour, tzOffsetMs = tz)
        assertEquals(2, local.daysSeen)
        assertEquals(1, utc.daysSeen)
    }

    @Test fun englishSummaryUsesEnglishPunctuation() {
        L.en = true
        try {
            val s = Relation.Stats(theirMsgs = 10, myMsgs = 4, theirChars = 100, myChars = 20)
            val summary = Relation.summary(s)!!
            assertTrue(summary.contains("; "))
            assertFalse(summary.contains("；"))
        } finally {
            L.en = false
        }
    }
}
