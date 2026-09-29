package dev.vibecheck

import org.junit.Assert.*
import org.junit.Test

/** What the judge is sent: every turn it needs judged, nothing it does not, as little as it takes. */
class JudgeCostTest {

    private val tail = (1..12).map { (if (it % 2 == 0) "我" else "对方") to "第 $it 条消息" }

    @Test fun scrolledBackIntoWhatWasCountedIsNotANewTurn() {
        // The newest counted lines are on screen: the turn to answer.
        assertFalse(Chat.scrolledBack(tail, tail.takeLast(6)))
        // With something new under them, too.
        assertFalse(Chat.scrolledBack(tail, tail.takeLast(5) + ("对方" to "新的一条")))
        // Only older lines on screen: scrolled up to read what came before.
        assertTrue(Chat.scrolledBack(tail, tail.subList(2, 8)))
        // Nothing lines up, or nothing counted yet: judged as before.
        assertFalse(Chat.scrolledBack(tail, listOf("对方" to "没见过的一句", "我" to "也没见过")))
        assertFalse(Chat.scrolledBack(emptyList(), tail.take(4)))
    }

    @Test fun theRelationshipIsNotAskedOnceTurnAfterTurnSaysTheSame() {
        val n = Person.Norm()
        repeat(4) { Person.observeSituation(n, "朋友") }
        assertNull("not yet", Person.steadySituation(n))
        Person.observeSituation(n, "朋友")
        assertEquals("朋友", Person.steadySituation(n))
        // Asked again now and then, and kept when it still says the same.
        n.since = 12
        assertNull(Person.steadySituation(n))
        Person.observeSituation(n, "朋友")
        assertEquals("朋友", Person.steadySituation(n))
        // Answers that disagree settle nothing.
        val mixed = Person.Norm()
        repeat(6) { Person.observeSituation(mixed, if (it % 2 == 0) "朋友" else "暧昧试探") }
        assertNull(Person.steadySituation(mixed))
        // Kept on the phone, and read by older records without it.
        val back = Person.loadNorm(Person.saveNorm(n))
        assertEquals("朋友", Person.steadySituation(back))
        assertEquals(n.asked, back.asked)
        assertNull(Person.steadySituation(Person.loadNorm("3\t0.2\t在分享观点或心情=1.0")))
    }

    @Test fun theStateIsOneLinePerMessageWithWhatChangesLast() {
        val state = Jev.stateJson("大学室友", listOf("对方" to "在吗", "我" to "在"), history = "在分享观点或心情/危1/简单回应或认同",
            relationship = "朋友", theirStyle = "话很短")
        assertTrue(state, state.contains("\"对话\":[\"对方：在吗\",\"我：在\"]"))
        assertFalse(state.contains("\"谁\""))
        assertTrue(state.indexOf("关系背景") < state.indexOf("对方平时的说话方式"))
        assertTrue(state.indexOf("对方平时的说话方式") < state.indexOf("我们最近几轮的走向"))
        assertTrue(state.indexOf("我们最近几轮的走向") < state.indexOf("对话"))
    }

    @Test fun theJudgeGetsWhatStandsOutNotTheCounts() {
        val s = Relation.Stats(theirMsgs = 40, myMsgs = 30, theirChars = 200, myChars = 300, sessions = 5, theyStarted = 4, daysSeen = 6)
        val judge = Relation.judgeSummary(s)!!
        assertFalse(judge, judge.contains("共看到"))
        assertTrue(judge, judge.contains("对方先开口"))
        assertTrue(Relation.summary(s)!!.contains("共看到 70 条"))
        val style = Person.Style()
        repeat(8) { Person.observe(style, "嗯") }
        assertEquals("话很短，简短的回复是 Ta 的常态；几乎不用表情", Person.theirTraits(style))
        assertTrue(Person.theirStyleSummary(style)!!.contains("共 8 条"))
        val plain = Person.Style()
        repeat(6) { Person.observe(plain, "今天去看了一个展还不错") }
        repeat(2) { Person.observe(plain, "下次一起去看😊") }
        assertNull("nothing stands out", Person.theirTraits(plain))
    }
}
