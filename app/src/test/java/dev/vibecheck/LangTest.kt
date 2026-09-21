package dev.vibecheck

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class LangTest {
    @After fun back() { L.en = false }

    private val answers = mapOf(
        "intent" to Jev.Answer.Dist("在表达不满", mapOf("在表达不满" to 0.7, "想确认你在不在乎" to 0.3)),
        "danger" to Jev.Answer.Scored(3.2, 6),
        "trap" to Jev.Answer.Noul(0.8),
        "action" to Jev.Answer.Dist("先回应情绪", mapOf("先回应情绪" to 0.6, "先道歉" to 0.4)),
    )

    @Test fun englishCardTranslatesHeadersAndOptionsButNotKeys() {
        L.en = true
        val card = Jev.card(answers, situation = "同事或上下级")
        assertEquals("What they really mean", card[0].header)
        assertEquals("- expressing displeasure: 70%", card[0].lines[0])
        assertTrue(card.any { it.header == "Does going along commit you?" && it.lines[0] == "- yes: 80%" })
        assertTrue(card.last().lines[0].startsWith("- acknowledge the feeling first"))
        // The answer keys the learner records are untouched by the display language.
        assertEquals("在表达不满", (answers["intent"] as Jev.Answer.Dist).top)
        assertEquals("同事或上下级|在表达不满|先回应情绪", Learner.keys("同事或上下级", "在表达不满", "先回应情绪").first())
    }

    @Test fun chineseIsTheDefaultAndUnchanged() {
        val card = Jev.card(answers, situation = "同事或上下级")
        assertEquals("当前真实意图", card[0].header)
        assertEquals("- 在表达不满: 70%", card[0].lines[0])
    }

    @Test fun promptsAndSummariesFollowTheLanguage() {
        L.en = true
        assertTrue(OpenRouter.DEEP_SYSTEM.startsWith("You read the subtext"))
        val p = OpenRouter.deepPrompt("Sam", "", null, null, null, listOf("对方" to "hey", "我" to "hi"), answers, false, false, "朋友")
        assertTrue(p.contains("Them: Sam"))
        assertTrue(p.contains("them: hey") && p.contains("me: hi"))
        assertTrue(p.contains("• What they really mean: expressing displeasure (70%)"))
        val s = Relation.Stats(theirMsgs = 10, myMsgs = 4, theirChars = 100, myChars = 20)
        assertTrue(Relation.summary(s)!!.startsWith("14 messages seen (them 10, me 4)"))
        assertEquals("Learn this person", L.t("学习此人", "Learn this person"))
        assertEquals("未知", L.label("未知"))   // unknown keys pass through
    }
}
