package dev.vibecheck

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Who the other person is to me: read from profiles, told to Jev, never contradicted by one turn. */
class RelationshipTest {

    @After fun chinese() { L.en = false }

    /** The profile from the bug report: a best friend, whose card said 恋爱或亲密关系 91%. */
    private val oldProfile = """
        • 对方是我的好友或死党，关系亲密，彼此可以随意开玩笑、互怼。
        • 我们常聊日常琐事、科技/AI、恋爱关系和情绪，也讨论共同好友“麻辣烫”。
        • 对方说话爱用“笑死我了”“哈哈哈哈”，语气活泼随意，常打错别字，几乎不用表情符号。
        • 我对对方说话直接，爱调侃，会用“真棒”“咬他”之类的简短回应，并常发送AI分析结果。
        • 对方在乎情感陪伴和亲密关系，渴望被理解和喜欢，对独处感到焦虑，也在意男朋友的态度。
    """.trimIndent()

    @Test fun anOldProfileIsReadFromItsFirstLine() {
        val (rel, kept) = Relationship.fromProfile(oldProfile)
        assertEquals("朋友", rel)
        assertEquals("a profile without the new line is kept whole", oldProfile, kept)
    }

    @Test fun theRelationshipLineIsReadAndTakenOut() {
        val (rel, kept) = Relationship.fromProfile("关系：朋友\n$oldProfile")
        assertEquals("朋友", rel)
        assertEquals(oldProfile, kept)
        assertEquals("恋人或伴侣", Relationship.fromProfile("**关系**：恋人或伴侣。\n• 在一起两年").first)
        assertEquals("客户或生意", Relationship.fromProfile("• 关系：客户或生意\n• 做外贸").first)
    }

    @Test fun anEnglishProfileLineIsRead() {
        L.en = true
        val (rel, kept) = Relationship.fromProfile("Relationship: friend\n• Close friend from college")
        assertEquals("朋友", rel)
        assertEquals("• Close friend from college", kept)
        assertEquals("客户或生意", Relationship.fromProfile("Relationship: Client / Business\n• Orders every month").first)
        assertEquals("恋人或伴侣", Relationship.fromProfile("relationship: partner").first)
    }

    @Test fun anOffListAnswerFallsBackToItsWords() {
        val (rel, kept) = Relationship.fromProfile("关系：大学同学\n• 很久没见")
        assertEquals("朋友", rel)
        assertEquals("• 很久没见", kept)
        val unclear = "关系：说不清\n• 很久没见"
        assertEquals(null to unclear, Relationship.fromProfile(unclear))
    }

    @Test fun aWordInsideALongerOneDoesNotCount() {
        assertEquals("恋人或伴侣", Relationship.guess("• 对方是我男朋友，在一起两年"))
        assertEquals("恋人或伴侣", Relationship.guess("• 对方是我的女朋友"))
        assertEquals("朋友", Relationship.guess("• 最好的朋友，无话不谈"))
    }

    @Test fun twoAnswersAreNoAnswer() {
        assertNull(Relationship.guess("• 对方是我的好友，有男朋友"))
        assertNull(Relationship.guess("• 大学同学，现在是同事"))
        assertNull(Relationship.guess("• 聊得很多"))
        assertNull(Relationship.guess(""))
    }

    @Test fun anExIsNotAPartner() {
        assertNull(Relationship.guess("• 对方是我的前女友"))
        assertNull(Relationship.guess("• My ex-girlfriend, we still talk"))
        assertEquals("朋友", Relationship.guess("• 前任，现在是朋友"))
    }

    @Test fun englishWordsMatchWholeWordsOnly() {
        assertEquals("朋友", Relationship.guess("• My best friend since school"))
        assertEquals("朋友", Relationship.guess("• Friends from the climbing gym"))
        assertEquals("同事或上下级", Relationship.guess("• A friendly coworker"))
        assertNull(Relationship.guess("• Talks about an important moment"))
        assertNull(Relationship.guess("• Her girlfriend's brother"))
        assertEquals("恋人或伴侣", Relationship.guess("• My girlfriend"))
    }

    @Test fun storedValuesAreCheckedAndRenamed() {
        assertEquals("恋人或伴侣", Relationship.pinned("恋爱或亲密关系"))
        assertEquals("朋友", Relationship.pinned(" 朋友 "))
        assertNull(Relationship.pinned(""))
        assertNull(Relationship.pinned(null))
        assertNull(Relationship.pinned("随便"))
    }

    @Test fun whatWasLearnedUnderTheOldNameIsKept() {
        val m = Learner.load("v1\t0.1\t8\n恋爱或亲密关系|在表达不满|先道歉\t4\t0.5\n*|在表达不满|先道歉\t4\t0.5\n")
        assertEquals(Learner.Arm(4, 0.5), m.arms["恋人或伴侣|在表达不满|先道歉"])
        assertFalse(m.arms.keys.any { it.startsWith("恋爱或亲密关系") })
        assertEquals(Learner.Arm(4, 0.5), m.arms["*|在表达不满|先道歉"])
    }

    @Test fun aKnownRelationshipIsToldNotAsked() {
        val transcript = listOf("对方" to "我男朋友又不回我", "我" to "那我陪你一起发臭发烂")
        val state = Jev.stateJson("", transcript, relationship = "朋友")
        assertTrue(state.contains("\"我和对方的关系\":\"朋友\""))
        val told = Jev.triageBody(state, askSituation = false)
        assertFalse(told.contains("\"situation\":{"))
        for (id in listOf("intent", "danger", "urgency")) assertTrue(id, told.contains("\"$id\":{"))
        Json.check(told)
        Json.check(Jev.triageBody(state))
    }

    @Test fun noContextAssumesNothing() {
        val state = Jev.stateJson("", listOf("对方" to "在吗"))
        assertFalse("an empty context used to claim an intimate relationship", state.contains("亲密"))
        assertFalse(state.contains("关系背景"))
        assertTrue(Jev.stateJson("大学室友", listOf("对方" to "在吗")).contains("\"关系背景\":\"大学室友\""))
    }

    @Test fun theSituationQuestionOffersExactlyTheRelationships() {
        assertEquals(Relationship.KEYS, Jev.SITUATION_CRITERIA.map { it.first })
        val body = Jev.triageBody(Jev.stateJson("", listOf("对方" to "在吗")))
        for (k in Relationship.KEYS) assertTrue(k, body.contains("\"$k\":"))
        assertFalse(body.contains("恋爱或亲密关系"))
        assertTrue("says the topic is not the relationship", body.contains("不是聊天的话题"))
    }

    @Test fun everyRelationshipHasAnEnglishName() {
        for (k in Relationship.KEYS) assertNotEquals(k, L.english(k))
    }

    @Test fun theProfilePromptAsksForBothLines() {
        for (fromNotes in listOf(true, false)) {
            val zh = Profile.profileSystem(fromNotes)
            assertTrue(zh.contains("关系：") && zh.contains("亲近："))
            for (k in Relationship.KEYS + Relationship.CLOSENESS) assertTrue(k, zh.contains(k))
            assertTrue("close is not a couple", zh.contains("不等于是恋人"))
        }
        L.en = true
        val en = Profile.profileSystem(true)
        assertTrue(en.contains("Relationship: ") && en.contains("Closeness: "))
        for (k in Relationship.KEYS + Relationship.CLOSENESS) assertTrue(k, en.contains(L.english(k)))
    }

    @Test fun theDeepReadIsToldWhoTheyAre() {
        val answers = mapOf("intent" to Jev.Answer.Dist("在分享观点或心情", mapOf("在分享观点或心情" to 0.9)))
        val p = OpenRouter.deepPrompt("欧欧", "", null, null, null, listOf("对方" to "不想出门"), answers,
            false, false, "朋友", relationship = "朋友")
        assertTrue(p.contains("我和对方的关系：朋友"))
        val guessed = OpenRouter.deepPrompt("欧欧", "", null, null, null, listOf("对方" to "不想出门"), answers, false, false, "朋友")
        assertFalse("a guess from one turn is not stated as fact", guessed.contains("我和对方的关系"))
    }

    /** Just enough JSON to prove a request body parses: the tests run without org.json. */
    private class Json(private val s: String) {
        private var i = 0

        companion object {
            fun check(text: String) {
                val p = Json(text)
                p.value()
                p.ws()
                assertEquals("trailing text at ${p.i}", text.length, p.i)
            }
        }

        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

        private fun expect(c: Char) { ws(); assertTrue("expected '$c' at $i: ${s.drop(i).take(30)}", i < s.length && s[i] == c); i++ }

        fun value() {
            ws()
            assertTrue("unexpected end", i < s.length)
            when (s[i]) {
                '{' -> {
                    i++; ws()
                    if (s[i] == '}') { i++; return }
                    while (true) {
                        string(); expect(':'); value(); ws()
                        if (s[i] == ',') { i++; continue }
                        expect('}'); return
                    }
                }
                '[' -> {
                    i++; ws()
                    if (s[i] == ']') { i++; return }
                    while (true) {
                        value(); ws()
                        if (s[i] == ',') { i++; continue }
                        expect(']'); return
                    }
                }
                '"' -> string()
                else -> {
                    val m = Regex("""-?\d+(\.\d+)?([eE][+-]?\d+)?|true|false|null""").find(s, i)?.takeIf { it.range.first == i }
                    assertNotNull("bad value at $i: ${s.drop(i).take(30)}", m)
                    i = m!!.range.last + 1
                }
            }
        }

        private fun string() {
            ws()
            assertEquals("expected a string at $i: ${s.drop(i).take(30)}", '"', s[i])
            i++
            while (s[i] != '"') {
                assertTrue("raw control character at $i", s[i] >= ' ')
                if (s[i] == '\\') i++
                i++
            }
            i++
        }
    }
}
