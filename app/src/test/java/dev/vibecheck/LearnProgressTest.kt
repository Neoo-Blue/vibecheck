package dev.vibecheck

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** What the card says while a profile is written, and quotes that are not new messages. */
class LearnProgressTest {

    @After fun chinese() { L.en = false }

    private fun box(t: Int) = Chat.Box(193, t, 700, t + 60)

    @Test fun theBarFillsWithTheStretches() {
        assertEquals("░░░░░░░░░░", Learning.bar(0, 12))
        assertEquals("█████░░░░░", Learning.bar(6, 12))
        assertEquals("██████████", Learning.bar(12, 12))
        assertEquals("░░░░░░░░░░", Learning.bar(3, 0))
    }

    @Test fun durationsReadNaturally() {
        assertEquals("40 秒", Learning.duration(40_000))
        assertEquals("3 分钟", Learning.duration(190_000))
        assertEquals("1 小时 5 分钟", Learning.duration(65 * 60_000L))
        L.en = true
        assertEquals("3 min", Learning.duration(190_000))
    }

    @Test fun whileNotingItShowsHowFarHowLongAndHowMuchLonger() {
        val p = Learning.Progress(startedAt = 0, read = 300).apply { kept = 3420; chars = 41_000 }
        p.update(Learning.Stage.NOTES, 3, 12, now = 1_000)       // three were noted by an earlier try
        p.update(Learning.Stage.NOTES, 4, 12, now = 30_000)
        p.update(Learning.Stage.NOTES, 5, 12, now = 60_000)
        // Two done this time in a minute: 30 s each, seven left, and the last step.
        assertEquals(7 * 30_000L + 120_000L, Learning.estimate(p))
        val lines = Learning.lines(p, now = 90_000)
        assertTrue(lines[0], lines[0].startsWith("第 1 步") && lines[0].contains("████░░░░░░ 5 / 12"))
        assertTrue(lines[1], lines[1].contains("这次读了 300 条") && lines[1].contains("共存 3420 条") && lines[1].contains("已用 1 分钟"))
        assertTrue(lines.any { it.contains("预计还要约 5 分钟") })
        assertTrue(lines.last().contains("不用重来"))
        // Counts only go up, whatever order the stretches come back in.
        p.update(Learning.Stage.NOTES, 4, 12, now = 70_000)
        assertEquals(5, p.done)
    }

    @Test fun noEstimateFromOneStretch() {
        val p = Learning.Progress(startedAt = 0, read = null)
        p.update(Learning.Stage.NOTES, 0, 12, now = 0)
        p.update(Learning.Stage.NOTES, 1, 12, now = 40_000)
        assertNull(Learning.estimate(p))
    }

    @Test fun theLastStepSaysHowLongItHasWaited() {
        val p = Learning.Progress(startedAt = 0, read = null).apply { kept = 800; chars = 9000 }
        p.update(Learning.Stage.FINAL, 0, 0, now = 200_000)
        val lines = Learning.lines(p, now = 260_000)
        assertTrue(lines[0], lines[0].startsWith("最后一步"))
        assertTrue(lines.any { it.contains("已等 1 分钟") })
        assertFalse(Learning.stalled(p, 260_000))
    }

    @Test fun tenMinutesWithoutNewsIsStuck() {
        val p = Learning.Progress(startedAt = 0, read = null).apply { kept = 800; chars = 9000 }
        p.update(Learning.Stage.NOTES, 2, 6, now = 60_000)
        assertFalse(Learning.stalled(p, 60_000 + 9 * 60_000L))
        val now = 60_000 + 11 * 60_000L
        assertTrue(Learning.stalled(p, now))
        val lines = Learning.lines(p, now)
        assertTrue(lines.last(), lines.last().contains("好像卡住了") && lines.last().contains("11 分钟") && lines.last().contains("再点「学习」"))
        assertFalse(lines.any { it.contains("不用重来") })
    }

    @Test fun aQuoteOfMyMessageUnderTheirsIsNotTheirs() {
        // 5:45 in the 🍵 chat: they quoted my 「女大 is no more」 under their own message.
        val mine = Chat.Bubble("女大 is no more", false, box(300))
        val theirs = Chat.Bubble("The more I want to know the more 醋", true, box(560))
        val quote = Chat.Bubble("A°：女大is no more", true, box(700))
        val next = Chat.Bubble("I don't like", true, box(820))
        assertEquals(listOf(mine, theirs, next), Chat.withoutEchoes(listOf(mine, theirs, quote, next)))
        // A long one cut short with "…".
        val cut = Chat.Bubble("Lily：The more I want to know…", false, box(900))
        assertEquals(listOf(mine, theirs), Chat.withoutEchoes(listOf(mine, theirs, cut)))
    }

    @Test fun colonsInRealMessagesStay() {
        val a = Chat.Bubble("明天开会", true, box(100))
        val keep = listOf(
            Chat.Bubble("注意：带电脑", false, box(200)),          // nothing above says 带电脑
            Chat.Bubble("10:30 见", true, box(300)),
            Chat.Bubble("https://x.com/a", false, box(400)),
            Chat.Bubble("https://x.com/a", true, box(500)),      // the same link sent back
        )
        assertEquals(listOf(a) + keep, Chat.withoutEchoes(listOf(a) + keep))
        // A quote of something no longer on screen is left alone (their name catches it instead).
        assertEquals(1, Chat.withoutEchoes(listOf(Chat.Bubble("小雨：周六去爬山吗", false, box(100)))).size)
    }
}
