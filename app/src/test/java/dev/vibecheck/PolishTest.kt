package dev.vibecheck

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class SystemRowTest {

    @Test fun ordinaryWordsInsideRealMessagesSurvive() {
        for (t in listOf(
            "let's do a video call tonight", "Video call later?", "我们语音通话吧", "say hi to your mom for me",
            "lol you took a screenshot?", "you replied to her?", "you reacted so fast lol", "let's move to a secret chat",
            "is this end-to-end encrypted?", "call ended up being fun", "did you turn on disappearing messages?",
            // Loose metadata or a lower-case "actor" must not turn a sentence into a system row.
            "Video call at 8 tonight", "video call on Sunday then", "Voice call, then dinner",
            "I left the group, sorry about that", "I left the group", "Say hi to mom", "Say hi, I'm new here",
            "she finally replied to you", "you replied to her already", "loved by everyone",
            "I changed the theme lol",
        )) assertFalse(t, Chat.isNotification(t))
    }

    @Test fun theRowsThemselvesAreStillCaught() {
        for (t in listOf(
            "Missed voice call at 10:32", "Video call ended", "Voice call · Tap to call back", "视频通话已取消",
            "语音通话 03:12", "Say hi to Sam", "You joined the secret chat", "Disappearing messages were turned on.",
            "Your safety number with Sam has changed", "Sam changed the theme to Love.", "You waved at Sam",
            "Sam left the group", "Sam took a screenshot of the chat.", "Type a message…",
            "Sam Lee replied to you", "Replied to you", "You replied to their story", "Seen by Sam and 2 others",
            "Missed video call on 10:32 PM",
        )) assertTrue(t, Chat.isNotification(t))
    }

    @Test fun aScreenOfOnlyTheirMessagesIsAChatWhenThereIsAnInputBox() {
        val theirs = Chat.Bubble("你好，我是昨天加你的那个", true, Chat.Box(140, 900, 700, 980))
        assertFalse("no input box: could be the chat list", Chat.inConversation(listOf(theirs)))
        assertTrue(Chat.inConversation(listOf(theirs), hasInput = true))
        assertFalse(Chat.inConversation(emptyList(), hasInput = true))
    }
}

class CardTextTest {

    @After fun back() { L.en = false }

    @Test fun scoreLevelsSayWhatTheyMean() {
        val answers = mapOf(
            "danger" to Jev.Answer.Scored(0.2, 6),
            "pressure" to Jev.Answer.Scored(2.0, 4),
            "mood" to Jev.Answer.Scored(0.0, 4),
        )
        val work = Jev.card(answers, situation = "同事或上下级").associate { it.header to it.lines[0] }
        assertEquals("1 / 6 · 很轻松", work["翻车风险"])
        assertEquals("3 / 4 · 今天", work["时间压力"])
        assertEquals("1 / 4 · 低落", Jev.card(answers, situation = "朋友").first { it.header == "对方心情" }.lines[0])

        L.en = true
        assertEquals("3 / 4 · today", Jev.card(answers, situation = "同事或上下级").first { it.header == "Time pressure" }.lines[0])
    }

    @Test fun aScaleWithUnexpectedLevelsFallsBackToTheNumber() {
        val odd = mapOf("pressure" to Jev.Answer.Scored(2.0, 5))
        assertEquals("3 / 5", Jev.card(odd, situation = "同事或上下级").single().lines[0])
        assertEquals("out-of-range scores are clamped", 6, Jev.shownLevel(Jev.Answer.Scored(9.0, 6)))
    }

    @Test fun englishHighRiskFooterDoesNotRepeatItself() {
        L.en = true
        val f = Jev.footer(mapOf(
            "danger" to Jev.Answer.Scored(5.0, 6),
            "action" to Jev.Answer.Dist("先道歉", mapOf("先道歉" to 0.8)),
            "need" to Jev.Answer.Dist("道歉", mapOf("道歉" to 0.9)),
        ))!!
        assertTrue(f, f.contains("Best move: apologize first"))
        assertTrue(f, f.contains("they need an apology"))
    }

    @Test fun replyDraftsArePasteReady() {
        val answer = "Here are three options:\n1. Sure. See you at 8\n2、好的，明天见\n3) \"No worries!\""
        assertEquals(listOf("Sure. See you at 8", "好的，明天见", "No worries!"), OpenRouter.drafts(answer))
        assertEquals("1.5 hours works for me", OpenRouter.draftText("1.5 hours works for me"))
        assertEquals("-5 degrees here", OpenRouter.draftText("-5 degrees here"))
        assertEquals("行", OpenRouter.draftText("• 「行」"))
        assertEquals(listOf("ok", "sure"), OpenRouter.drafts("ok\n\nsure"))
        // Chinese numbering often has no space, or a full-width dot.
        assertEquals(listOf("好的呀", "明天见", "哈哈"), OpenRouter.drafts("以下是三条：\n1.好的呀\n2．明天见\n3、哈哈"))
    }

    @Test fun learnerArmsReadInTheDisplayLanguage() {
        L.en = true
        assertEquals("friend|*|apologize first", Learner.armLabel("朋友|*|先道歉"))
        L.en = false
        assertEquals("朋友|*|先道歉", Learner.armLabel("朋友|*|先道歉"))
    }
}

class FailureTest {

    @After fun back() { L.en = false }

    @Test fun errorsSayWhatToDo() {
        assertTrue(Judge.describe(TypeSafe.Failure(401, "HTTP 401: {}")).contains("Key 被拒绝"))
        L.en = true
        assertEquals("Key rejected (401); check it in settings", Judge.describe(TypeSafe.Failure(401, "HTTP 401: {}")))
        assertEquals("Out of credit (402)", Judge.describe(OpenRouter.Failure(402, "x")))
        assertEquals("Refused (403): input flagged", Judge.describe(OpenRouter.Failure(403, "HTTP 403: input flagged")))
        assertEquals("No connection", Judge.describe(java.net.UnknownHostException("api.typesafe.ai")))
        assertEquals("Timed out", Judge.describe(java.net.SocketTimeoutException()))
        assertEquals("something odd", Judge.describe(IllegalStateException("something odd")))
    }

    @Test fun onlyKeyAndBalanceProblemsStopRetrying() {
        assertTrue(Judge.isFatal(TypeSafe.Failure(401, "")))
        assertTrue(Judge.isFatal(OpenRouter.Failure(402, "")))
        assertFalse(Judge.isFatal(TypeSafe.Failure(429, "")))
        assertFalse("OpenRouter's moderation refusal is one input, not a dead key", Judge.isFatal(OpenRouter.Failure(403, "")))
        assertFalse(Judge.isFatal(TypeSafe.Failure(503, "")))
        assertFalse(Judge.isFatal(java.io.IOException("reset")))
    }

    @Test fun backoffDoublesAndCaps() {
        assertEquals(2_000L, Judge.backoffMs(1))
        assertEquals(8_000L, Judge.backoffMs(3))
        assertEquals(60_000L, Judge.backoffMs(12))
    }

    @Test fun onlyTransientFailuresAreRetriedInPlace() {
        assertTrue(TypeSafe.retryable(TypeSafe.Failure(503, "")))
        assertTrue(TypeSafe.retryable(TypeSafe.Failure(429, "")))
        assertTrue(TypeSafe.retryable(java.io.IOException("connection reset")))
        assertFalse(TypeSafe.retryable(TypeSafe.Failure(401, "")))
        assertFalse(TypeSafe.retryable(java.net.SocketTimeoutException()))
        assertFalse(TypeSafe.retryable(java.net.UnknownHostException()))
        assertFalse("no network is not worth two more tries", TypeSafe.retryable(java.net.ConnectException("refused")))
        assertFalse(TypeSafe.retryable(javax.net.ssl.SSLHandshakeException("bad cert")))
    }
}

class StyleAndRewardTest {

    @Test fun emojiRateIsAShareOfMessages() {
        val s = Person.Style()
        Person.observe(s, "😂😂😂")
        Person.observe(s, "ok")
        Person.observe(s, "[捂脸] 我的锅")
        assertEquals(3, s.msgs)
        assertEquals("three emoji in one message is one message with emoji", 2, s.emoji)
        assertFalse(Person.hasEmoji("see [1] and [2]"))
        // Placeholders and ordinary bracketed words are not faces.
        assertFalse(Person.hasEmoji("[图片]"))
        assertFalse(Person.hasEmoji("[Photo] from yesterday"))
        assertFalse(Person.hasEmoji("[sigh] fine"))
        assertTrue(Person.hasEmoji("[Facepalm] fine"))
    }

    @Test fun englishApologiesCount() {
        val s = Person.Style()
        Person.observe(s, "my bad, forgot")
        Person.observe(s, "I apologise")
        assertEquals(2, s.apologies)
    }

    @Test fun rewardComparesRawWithRaw() {
        // The card showed 0.3 because this person runs a -0.2 bias; the model read 0.5 both turns.
        val m = Learner.Model(dangerBias = -0.2)
        val reward = Learner.observe(m, Learner.Episode("*", "在表达不满", "先回应情绪", danger = 0.3, raw = 0.5), 0.5)
        assertEquals("a conversation that stayed put is neither credit nor blame", 0.0, reward, 1e-9)
    }
}
