package dev.vibecheck

import org.junit.Assert.*
import org.junit.Test

class DeepTest {

    private val answers = mapOf(
        "literal" to Jev.Answer.Noul(0.18),
        "intent" to Jev.Answer.Dist("想要你主动承担", mapOf("想要你主动承担" to 0.68, "在表达不满" to 0.32)),
        "danger" to Jev.Answer.Scored(3.2, 6),
        "situation" to Jev.Answer.Dist("暧昧试探", mapOf("暧昧试探" to 0.8, "恋爱或亲密关系" to 0.2)),
        "urgency" to Jev.Answer.Noul(0.82),
    )
    private val transcript = listOf("我" to "我喜欢欲擒故纵", "对方" to "让我主动")

    @Test fun triageCoversMoreThanRomanceAndDetailFollowsIt() {
        val body = Jev.requestBody("", transcript)
        for (id in listOf("situation", "intent", "danger", "urgency")) {
            assertTrue("missing question $id", body.contains(""""$id":{"""))
        }
        // Situations beyond a relationship, so the same card works at work or with a client.
        for (option in listOf("同事或上下级", "客户或生意", "朋友", "家人", "陌生人或刚加上")) {
            assertTrue("missing situation $option", body.contains(option))
        }
        // The negotiation-only action lives in the work detail set, not in every card.
        val work = Jev.detailBody(Jev.stateJson("", transcript), "客户或生意")
        assertTrue(work.contains("守住边界不让步"))
        assertFalse(body.contains("守住边界不让步"))
    }

    @Test fun expandedCardShowsTheExtraJudgments() {
        val compact = Jev.card(answers).map { it.header }
        val more = Jev.more(answers).map { it.header }
        assertTrue(compact.contains("当前真实意图"))
        assertFalse("situation belongs in the expanded card only", compact.contains("这是什么场合"))
        assertEquals(listOf("这是什么场合", "需要马上回吗？"), more)
        assertEquals("- 暧昧试探: 80%", Jev.more(answers)[0].lines[0])
    }

    @Test fun deepPromptCarriesStateJudgmentsAndTheOcrCaveat() {
        val p = OpenRouter.deepPrompt(
            peer = "Mia", note = "暧昧阶段", style = "平均 9 字", history = "暧昧试探/危3/先回应情绪",
            relation = "共看到 40 条", transcript = transcript, answers = answers,
            fromOcr = true, hasImage = true,
        )
        assertTrue(p.contains("对方：Mia"))
        assertTrue(p.contains("关系背景：暧昧阶段"))
        assertTrue(p.contains("我平时的说话方式：平均 9 字"))
        assertTrue(p.contains("这段关系的长期观察：共看到 40 条"))
        assertTrue(p.contains("我：我喜欢欲擒故纵"))
        assertTrue(p.contains("当前真实意图：想要你主动承担（68%）"))
        assertTrue(p.contains("翻车风险：4 / 6"))
        assertTrue(p.contains("表情包图片没有被识别"))
        assertTrue(p.contains("以截图为准"))

        // Node-tree transcripts are complete, so neither caveat belongs.
        val clean = OpenRouter.deepPrompt("小明", "", null, null, null, transcript, answers, false, false)
        assertFalse(clean.contains("识别"))
        assertFalse(clean.contains("截图"))

        // No image attached: do not tell the model to trust one.
        val noImage = OpenRouter.deepPrompt("小明", "", null, null, null, transcript, answers, true, false)
        assertTrue(noImage.contains("没有被识别"))
        assertFalse(noImage.contains("以截图为准"))
    }
}
