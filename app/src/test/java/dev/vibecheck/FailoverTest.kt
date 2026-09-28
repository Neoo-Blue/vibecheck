package dev.vibecheck

import org.junit.Assert.*
import org.junit.Test

/** Which model stands in when the first fails, and for which failures. */
class FailoverTest {

    private val kimi = Prefs.DEFAULT_FAST
    private val pro = Prefs.DEFAULT_DEEP

    @Test fun theTwoModelsStandInForEachOther() {
        assertEquals(pro, Models.backup(kimi, fast = kimi, deep = pro))
        assertEquals(kimi, Models.backup(pro, fast = kimi, deep = pro))
        // A deep read with a screenshot goes to the reply model: its backup is the Think one.
        assertEquals(pro, Models.backup(" $kimi ", fast = kimi, deep = pro))
    }

    @Test fun oneModelForBothStillHasABackup() {
        assertEquals(Models.FALLBACK, Models.backup("qwen/qwen3.8-max-0902", "qwen/qwen3.8-max-0902", "qwen/qwen3.8-max-0902"))
        assertEquals(kimi, Models.backup(Models.FALLBACK, Models.FALLBACK, Models.FALLBACK))
        assertEquals(Models.FALLBACK, Models.backup(kimi, kimi, kimi))
        assertNotEquals(kimi, Models.backup(kimi, kimi, ""))
    }

    @Test fun onlyFailuresAnotherModelMightNotShareFailOver() {
        assertFalse("a rejected key is the same for every model", OpenRouter.worthAnotherModel(OpenRouter.Failure(401, "HTTP 401")))
        assertFalse("so is an empty balance", OpenRouter.worthAnotherModel(OpenRouter.Failure(402, "HTTP 402")))
        assertFalse("and no network at all", OpenRouter.worthAnotherModel(java.net.UnknownHostException("openrouter.ai")))
        assertFalse(OpenRouter.worthAnotherModel(java.net.ConnectException("refused")))
        for (e in listOf(
            OpenRouter.Failure(429, "HTTP 429"), OpenRouter.Failure(502, "HTTP 502"), OpenRouter.Failure(503, "no endpoints"),
            OpenRouter.Failure(403, "moderation"), OpenRouter.Failure(404, "No endpoints found"),
            OpenRouter.Failure(0, "The model stopped before answering"),
            java.net.SocketTimeoutException("timeout"), java.io.IOException("stream cut"),
        )) assertTrue(e.message, OpenRouter.worthAnotherModel(e))
    }

    @Test fun modelsAreNamedOnTheCard() {
        assertEquals("Kimi K2.6", Models.nameOf(kimi))
        assertEquals("DeepSeek V4 Pro", Models.nameOf(pro))
        assertEquals("someone/some-model", Models.nameOf(" someone/some-model "))
    }

    @Test fun aNoteRidesUnderTheAnswer() {
        val s = OverlayCard.Section("可以这样回", listOf("好呀"), pickable = true, read = "轻松")
        assertSame(s, s.withNote(null))
        val n = s.withNote("Kimi K2.6 出错，这次由 DeepSeek V4 Pro 回答")
        assertEquals(listOf("好呀"), n.lines)
        assertTrue(n.pickable)
        assertEquals("轻松", n.read)
        assertEquals("Kimi K2.6 出错，这次由 DeepSeek V4 Pro 回答", n.note)
    }
}
