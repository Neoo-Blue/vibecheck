package dev.vibecheck

import org.junit.Assert.*
import org.junit.Test

class RelationTest {

    private fun theirs(t: String) = Chat.Bubble(t, true, Chat.Box(140, 100, 700, 180))
    private fun mine(t: String) = Chat.Bubble(t, false, Chat.Box(380, 200, 940, 280))
    private val t0 = 1_700_000_000_000L
    private val hour = 3_600_000L

    @Test fun countsBothSidesAndMyReplyDelay() {
        val s = Relation.Stats()
        Relation.observe(s, listOf(theirs("在吗"), theirs("睡了没")), t0)
        Relation.observe(s, listOf(mine("在")), t0 + 180_000)      // answered three minutes later

        assertEquals(2, s.theirMsgs)
        assertEquals(1, s.myMsgs)
        assertEquals(1, s.sessions)
        assertEquals(1, s.theyStarted)
        assertEquals(1, s.replySamples)
        assertEquals(180, s.replySecTotal)
        assertEquals(0L, s.awaitingSince)                          // cleared once I replied
    }

    @Test fun aNewSessionStartsAfterAQuietGap() {
        val s = Relation.Stats()
        Relation.observe(s, listOf(mine("早")), t0)
        Relation.observe(s, listOf(theirs("嗯")), t0 + 60_000)      // same session
        assertEquals(1, s.sessions)
        assertEquals(0, s.theyStarted)

        Relation.observe(s, listOf(theirs("在忙吗")), t0 + 5 * hour) // next day-ish, they opened
        assertEquals(2, s.sessions)
        assertEquals(1, s.theyStarted)
    }

    @Test fun summaryStaysQuietUntilItHasSomethingToSay() {
        val s = Relation.Stats()
        Relation.observe(s, listOf(theirs("嗨")), t0)
        assertNull(Relation.summary(s))

        repeat(4) { i -> Relation.observe(s, listOf(theirs("消息$i"), mine("好")), t0 + i * hour * 3) }
        val summary = Relation.summary(s)!!
        assertTrue(summary.contains("共看到"))
        assertTrue(summary.contains("先开口"))
    }

    @Test fun frictionIsCountedOnlyFromRealJudgments() {
        val s = Relation.Stats()
        Relation.judged(s, 0.8)
        Relation.judged(s, 0.2)
        Relation.judged(s, 0.1)
        assertEquals(1, s.friction)
        assertEquals(2, s.calm)
    }

    @Test fun statsSurviveSaveLoad() {
        val s = Relation.Stats()
        Relation.observe(s, listOf(theirs("在吗")), t0)
        Relation.observe(s, listOf(mine("在")), t0 + 120_000)
        Relation.judged(s, 0.9)
        assertEquals(s, Relation.load(Relation.save(s)))
        assertEquals(Relation.Stats(), Relation.load("garbage"))
    }

    @Test fun durationsReadLikeAHuman() {
        assertEquals("45 秒", Relation.fmtDuration(45))
        assertEquals("5 分钟", Relation.fmtDuration(300))
        assertEquals("2 小时", Relation.fmtDuration(7200))
        assertEquals("1 天", Relation.fmtDuration(90000))
    }

    @Test fun backoffUsesTheMostSpecificArmWithEvidence() {
        val m = Learner.Model()
        // Four apologies in a romantic context, which went well.
        repeat(4) { Learner.observe(m, Learner.Episode("恋爱或亲密关系", "在表达不满", "先道歉", 0.9), 0.2) }

        // Same situation and intent: the specific arm is used.
        assertEquals(4, Learner.armFor(m, "恋爱或亲密关系", "在表达不满", "先道歉")!!.n)
        // Different situation: falls back to what is known about the intent.
        assertEquals("*|在表达不满|先道歉", Learner.keys("客户或生意", "在表达不满", "先道歉")[1])
        assertNotNull(Learner.armFor(m, "客户或生意", "在表达不满", "先道歉"))
        // Unseen intent as well: falls back to the action's general record.
        assertNotNull(Learner.armFor(m, "客户或生意", "在提要求或谈条件", "先道歉"))
        // An action with no history anywhere stays unknown.
        assertNull(Learner.armFor(m, "恋爱或亲密关系", "在表达不满", "守住边界不让步"))
    }

    @Test fun learningIsStillScopedWhereItShouldBe() {
        val m = Learner.Model()
        repeat(4) { Learner.observe(m, Learner.Episode("客户或生意", "在提要求或谈条件", "守住边界不让步", 0.8), 0.3) }
        val probs = mapOf("直接给具体方案" to 0.55, "守住边界不让步" to 0.45)
        // The learned arm pulls ahead in a near-tie.
        assertTrue(Learner.changedTop(probs, Learner.rerank(m, "客户或生意", "在提要求或谈条件", probs)))
        // A different person's model knows nothing.
        assertEquals(probs, Learner.rerank(Learner.Model(), "客户或生意", "在提要求或谈条件", probs))
    }

    @Test fun modelWithBackoffKeysSurvivesSaveLoad() {
        val m = Learner.Model()
        Learner.observe(m, Learner.Episode("朋友", "只是闲聊", "正面回答问题", 0.3), 0.1)
        val back = Learner.load(Learner.save(m))
        assertEquals(m.arms, back.arms)
        assertEquals(3, back.arms.size)      // specific, intent-level, action-level
        assertTrue(back.arms.containsKey("朋友|只是闲聊|正面回答问题"))
        assertTrue(back.arms.containsKey("*|*|正面回答问题"))
    }
}
