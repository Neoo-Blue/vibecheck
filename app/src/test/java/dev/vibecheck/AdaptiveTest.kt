package dev.vibecheck

import org.junit.Assert.*
import org.junit.Test

class AdaptiveTest {

    private val state = Jev.stateJson("测试", listOf("对方" to "明天能给我吗"))

    @Test fun triageAsksOnlyWhatDecidesRouting() {
        val t = Jev.triageBody(state)
        for (id in listOf("situation", "intent", "danger", "urgency")) assertTrue(id, t.contains("\"$id\":{"))
        // The situation-specific questions are NOT in triage: that is the whole point of two stages.
        for (id in listOf("need", "action", "trap", "interest", "mood")) assertFalse(id, t.contains("\"$id\":{"))
    }

    @Test fun eachSituationAsksDifferentQuestions() {
        val work = Jev.detailBody(state, "同事或上下级")
        val stranger = Jev.detailBody(state, "陌生人或刚加上")
        val friend = Jev.detailBody(state, "朋友")
        val love = Jev.detailBody(state, "恋爱或亲密关系")

        assertTrue(work.contains("\"trap\":{") && work.contains("\"pressure\":{"))
        assertTrue(stranger.contains("\"interest\":{") && stranger.contains("\"push\":{"))
        assertTrue(friend.contains("\"mood\":{"))
        assertTrue(love.contains("\"literal\":{"))

        // No cross-contamination: a work chat is not asked whether they need 情绪安抚.
        assertFalse(work.contains("\"mood\":{"))
        assertFalse(stranger.contains("\"trap\":{"))
        assertFalse(love.contains("\"interest\":{"))
    }

    @Test fun everySetEndsInAnActionSoLearningAndFooterKeepWorking() {
        for (sit in listOf("同事或上下级", "客户或生意", "陌生人或刚加上", "朋友", "家人", "恋爱或亲密关系", "暧昧试探", "客服或办事")) {
            assertTrue(sit, Jev.detailBody(state, sit).contains("\"action\":{"))
            assertEquals(sit, "action", Jev.displayFor(sit).last().first)
        }
    }

    @Test fun cardHeadersFollowTheSituation() {
        val answers = mapOf(
            "intent" to Jev.Answer.Dist("在提要求或谈条件", mapOf("在提要求或谈条件" to 0.8, "只是同步信息" to 0.2)),
            "danger" to Jev.Answer.Scored(2.5, 6),
            "ask" to Jev.Answer.Dist("要一个决定", mapOf("要一个决定" to 0.7, "要进度" to 0.3)),
            "pressure" to Jev.Answer.Scored(2.0, 4),
            "trap" to Jev.Answer.Noul(0.8),
            "action" to Jev.Answer.Dist("先确认范围再答应", mapOf("先确认范围再答应" to 0.6, "拖一下争取时间" to 0.4)),
        )
        val headers = Jev.card(answers, situation = "客户或生意").map { it.header }
        assertEquals(listOf("当前真实意图", "翻车风险", "对方在要什么", "时间压力", "顺着回会不会等于答应了？", "最佳动作"), headers)

        // The same answers rendered as an intimate chat would show none of the work headers.
        val loveHeaders = Jev.card(answers, situation = "恋爱或亲密关系").map { it.header }
        assertFalse(loveHeaders.contains("时间压力"))
    }

    @Test fun bioPromptKeepsTheNewestWhenTrimming() {
        val history = (1..200).map { (if (it % 2 == 0) "我" else "对方") to "第${it}条消息内容内容内容" }
        val p = OpenRouter.bioPrompt("小李", history, maxChars = 600)
        assertTrue(p.contains("第200条"))
        assertFalse("oldest lines are the ones dropped", p.contains("第1条消息"))
        assertTrue(p.contains("共 200 条"))
    }

    @Test fun backgroundPrefersYourNoteThenTheLearnedBio() {
        val store = FakeStore()
        assertEquals("默认", store.background("", "", "默认"))
        assertEquals("• 学到的", store.background("", "• 学到的", "默认"))
        assertEquals("我写的", store.background("我写的", "• 学到的", "默认"))
    }

    /** background() is pure logic on the record; mirror it without an Android Context. */
    private class FakeStore {
        fun background(note: String, bio: String, fallback: String) = note.ifBlank { bio }.ifBlank { fallback }
    }

    @Test fun olderPagesMergeByOverlapAndKeepRepeats() {
        val m = "对方" to "嗯"
        val kept = listOf(m, "我" to "好", m)                  // what is on screen now
        val older = listOf("对方" to "在吗", m, m, "我" to "好")  // one page up, overlapping the tail
        assertEquals(2, Chat.overlap(kept, older))              // "嗯","好" at the end match the start
        val merged = older.dropLast(2) + kept
        assertEquals(listOf("对方" to "在吗", m, m, "我" to "好", m), merged)
        assertEquals("repeats survive", 3, merged.count { it == m })

        assertEquals("nothing moved: whole page is overlap", kept.size, Chat.overlap(kept, kept))
        assertEquals("scrolled more than a page: nothing overlaps", 0, Chat.overlap(kept, listOf("我" to "早")))
        assertEquals(0, Chat.overlap(emptyList(), older))
    }

    @Test fun aMisreadAnchorDoesNotDoubleCountThePage() {
        val kept = listOf("对方" to "好呀", "我" to "走吧", "对方" to "几点", "我" to "八点")
        // Same page one frame later; OCR now reads the clipped top bubble as 好啊, so no exact overlap.
        val jitter = listOf("对方" to "在吗", "对方" to "好啊", "我" to "走吧", "对方" to "几点", "我" to "八点")
        assertEquals(listOf("对方" to "在吗", "对方" to "好啊"), Chat.freshLines(kept, jitter))
        // A genuinely new page (scrolled past a full screen) is kept whole, repeats included.
        val older = listOf("对方" to "嗯", "对方" to "嗯", "我" to "好")
        assertEquals(older, Chat.freshLines(kept, older))
        assertEquals(emptyList<Pair<String, String>>(), Chat.freshLines(kept, kept))
    }

    @Test fun bioPromptSurvivesAPastedArticleAndMultilineMessages() {
        val article = "对方" to "长".repeat(10_000)
        val p = OpenRouter.bioPrompt("小李", listOf("我" to "第一行\n第二行", article))
        assertTrue("the article is capped, not dropped with everything else", p.contains("对方：" + "长".repeat(300)))
        assertTrue("older lines still there", p.contains("我：第一行 第二行"))
        assertFalse(p.contains("第一行\n第二行"))
    }

    @Test fun deepPromptListsTheSituationsOwnAnswers() {
        val answers = mapOf(
            "intent" to Jev.Answer.Dist("在提要求或谈条件", mapOf("在提要求或谈条件" to 0.8)),
            "trap" to Jev.Answer.Noul(0.8),
            "pressure" to Jev.Answer.Scored(2.0, 4),
        )
        val p = OpenRouter.deepPrompt("老板", "", null, null, null, emptyList(), answers, false, false, "同事或上下级")
        assertTrue(p.contains("顺着回会不会等于答应了？：80%"))
        assertTrue(p.contains("时间压力：3 / 4"))
        val none = OpenRouter.deepPrompt("老板", "", null, null, null, emptyList(), answers, false, false)
        assertFalse("without a situation the default headers apply", none.contains("时间压力"))
    }

    @Test fun openRouterRouteOnlySwapsTheModelId() {
        val body = Jev.triageBody(state)
        assertTrue(body.contains("\"model\":\"jev-latest\""))
        val via = Judge.viaOpenRouter(body)
        assertTrue(via.contains("\"model\":\"~typesafe/jev-latest\""))
        assertFalse(via.contains("\"model\":\"jev-latest\""))
        assertEquals(body.length - "jev-latest".length + "~typesafe/jev-latest".length, via.length)
    }

    @Test fun bulkObserveCountsBothSidesWithoutTiming() {
        val s = Relation.Stats()
        Relation.observeBulk(s, listOf("对方" to "你好呀", "我" to "嗨", "对方" to "在干嘛", "我" to "看剧"))
        assertEquals(2, s.theirMsgs)
        assertEquals(2, s.myMsgs)
        assertEquals(6, s.theirChars)
        assertEquals(3, s.myChars)
        assertEquals("no fake sessions from a bulk read", 0, s.sessions)
        assertEquals(0, s.replySamples)
    }
}
