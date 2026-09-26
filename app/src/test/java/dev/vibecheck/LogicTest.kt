package dev.vibecheck

import org.junit.Assert.*
import org.junit.Test

class LogicTest {

    private val W = 1080
    private val H = 2400
    private val WIN = Chat.Box(0, 0, W, H)
    private fun box(l: Int, t: Int, r: Int, b: Int) = Chat.Box(l, t, r, b)

    @Test fun chromeIsDropped() {
        // action bar
        assertTrue(Chat.isChrome(box(400, 60, 680, 130), "小雨", WIN))
        // centered date divider
        assertTrue(Chat.isChrome(box(460, 900, 620, 950), "今天 19:12", WIN))
        // input bar
        assertTrue(Chat.isChrome(box(60, 2250, 900, 2320), "发送", WIN))
        // a real incoming bubble survives
        assertFalse(Chat.isChrome(box(140, 1000, 720, 1090), "你今天是不是又忘了我跟你说过什么？", WIN))
    }

    @Test fun bubbleNearTheInputBarSurvives() {
        // The window starts below the status bar and ends above the nav bar: bounds are absolute,
        // so cutoffs have to be relative to the window, not to a display height.
        val win = Chat.Box(0, 96, 1080, 2260)
        val newest = box(140, 2020, 700, 2110)     // last bubble, just above the input row
        assertFalse(Chat.isChrome(newest, "这还差不多。", win))
        assertTrue(Chat.isChrome(box(60, 2180, 900, 2250), "发送", win))
        assertTrue(Chat.isIncoming(newest, win))
    }

    @Test fun directionUsesMargins() {
        assertTrue(Chat.isIncoming(box(140, 1000, 720, 1090), WIN))       // hugs left
        assertFalse(Chat.isIncoming(box(360, 1200, 940, 1290), WIN))      // hugs right
        // wide incoming bubble whose center crosses midscreen is still incoming
        assertTrue(Chat.isIncoming(box(60, 1400, 800, 1600), WIN))
    }

    @Test fun refinePrefersLongClickableWhenAvailable() {
        val a = Chat.Bubble("消息一", true, box(140, 100, 700, 180))
        val b = Chat.Bubble("消息二", false, box(380, 200, 940, 280))
        val nickname = Chat.Bubble("小雨", true, box(140, 60, 300, 95))
        val kept = Chat.refine(listOf(nickname, a, b), setOf("消息一", "消息二"))
        assertEquals(listOf(a, b), kept)
        // with no long-clickable signal, nothing is dropped
        assertEquals(3, Chat.refine(listOf(nickname, a, b), emptySet()).size)
    }

    @Test fun triggerOnlyOnTheirMessageAndOnlyOnce() {
        val mine = Chat.Bubble("记得", false, box(380, 200, 940, 280))
        val theirs = Chat.Bubble("那你说。", true, box(140, 300, 500, 380))
        assertNull(Chat.triggerKey(listOf(theirs, mine)))          // my message last: no card
        val k1 = Chat.triggerKey(listOf(mine, theirs))
        assertNotNull(k1)
        assertEquals(k1, Chat.triggerKey(listOf(mine, theirs)))    // same screen, same key, no re-ask
        val next = Chat.Bubble("你最好是。", true, box(140, 400, 520, 480))
        assertNotEquals(k1, Chat.triggerKey(listOf(mine, theirs, next)))
    }

    @Test fun openingTheCardJudgesEvenWhenISpokeLast() {
        val theirs = Chat.Bubble("I actually hate cupcakes", true, box(140, 300, 640, 380))
        val mine = Chat.Bubble("me neither", false, box(400, 400, 900, 480))
        // Auto-trigger stays silent because my message is last...
        assertNull(Chat.triggerKey(listOf(theirs, mine)))
        // ...but a manual open still has a key to judge the screen with.
        assertNotNull(Chat.anyKey(listOf(theirs, mine)))
        assertNull(Chat.anyKey(emptyList()))
    }

    @Test fun cardRendersAnswersInOrder() {
        val answers = mapOf(
            "literal" to Jev.Answer.Noul(0.07),
            "intent" to Jev.Answer.Dist("想确认你在不在乎", mapOf(
                "想确认你在不在乎" to 0.72, "在表达不满" to 0.20, "单纯想知道答案" to 0.08)),
            "danger" to Jev.Answer.Scored(4.2, 6),
        )
        val blocks = Jev.card(answers)
        assertEquals(3, blocks.size)
        assertEquals("- 是: 7%", blocks[0].lines[0])
        assertEquals("- 不是: 93%", blocks[0].lines[1])
        assertEquals("- 想确认你在不在乎: 72%", blocks[1].lines[0])
        assertEquals("5 / 6 · 很危险", blocks[2].lines[0])   // the level says which end is bad
    }

    @Test fun footerReflectsThisTurnNotAFixedTemplate() {
        // High risk: names the actual chosen action, not a canned line.
        val tense = mapOf(
            "danger" to Jev.Answer.Scored(5.0, 6),
            "action" to Jev.Answer.Dist("先道歉", mapOf("先道歉" to 0.8)),
            "need" to Jev.Answer.Dist("道歉", mapOf("道歉" to 0.9)),
        )
        val f = Jev.footer(tense)!!
        assertTrue(f.contains("先道歉"))
        assertTrue(f.contains("道歉"))

        // Light banter: relaxed takeaway, and it must NOT read as a crisis.
        val light = mapOf(
            "danger" to Jev.Answer.Scored(0.0, 6),
            "action" to Jev.Answer.Dist("接梗顺着聊", mapOf("接梗顺着聊" to 0.7)),
            "urgency" to Jev.Answer.Noul(0.1),
        )
        val lf = Jev.footer(light)!!
        assertTrue(lf.contains("接梗顺着聊"))
        assertFalse(lf.contains("风险"))
    }

    @Test fun notificationsAndCallStubsAreNotMessages() {
        assertTrue(Chat.isNotification("WeChat 1friend(s) sent you 1 message(s)"))
        assertTrue(Chat.isNotification("对方撤回了一条消息"))
        assertTrue(Chat.isNotification("Duration: 16:57"))
        assertFalse(Chat.isNotification("Never having to go to the farmer's market"))
        assertFalse(Chat.isNotification("How meaningless"))
        // And they are dropped as chrome, whatever their position.
        assertTrue(Chat.isChrome(box(380, 1600, 900, 1680), "WeChat 2 friend(s) sent you 3 message(s)", WIN))
    }

    @Test fun ocrBlocksBecomeBubblesAndNeverReadTheCardBack() {
        val card = box(100, 1300, 850, 1700)          // where our own overlay is sitting
        val items = listOf(
            "Mia" to box(610, 110, 830, 200),      // title bar
            "扮猪吃老虎吗？" to box(150, 900, 620, 990),   // theirs
            "我喜欢欲擒故纵" to box(800, 1050, 1300, 1140), // mine
            "Jev： 当前真实意图" to box(120, 1330, 800, 1420), // our card, must not come back in
            "让我主动" to box(150, 1900, 420, 1990),      // theirs, newest
        )
        val (bubbles, titles) = Chat.fromOcr(items, WIN, listOf(card))

        assertEquals(listOf("扮猪吃老虎吗？", "我喜欢欲擒故纵", "让我主动"), bubbles.map { it.text })
        assertEquals(listOf(true, false, true), bubbles.map { it.incoming })
        assertEquals("Mia", titles.single().first)
        assertEquals("Mia", Person.peerName(titles, emptyList(), WIN))

        // With no card on screen nothing is excluded for that reason.
        assertEquals(4, Chat.fromOcr(items, WIN, emptyList()).first.size)
    }

    @Test fun theChatListIsNotAConversation() {
        // What OCR sees on WeChat's conversation list: left-aligned names and previews,
        // right-aligned timestamps. Nothing here is a message of mine.
        val list = listOf(
            "WeChat" to box(600, 120, 840, 200),
            "Mia" to box(175, 240, 420, 300),
            "1:14 PM" to box(1180, 240, 1400, 300),
            "好" to box(175, 300, 240, 360),
            "小明" to box(175, 740, 300, 800),
            "Yesterday" to box(1120, 740, 1400, 800),
            "辛苦你了" to box(175, 800, 420, 860),
        )
        val (bubbles, titles) = Chat.fromOcr(list, WIN, emptyList())
        assertFalse("timestamps must not survive as messages", bubbles.any { Chat.isTimeOrDate(it.text) })
        assertFalse("a directory of contacts is not a conversation", Chat.inConversation(bubbles))
        assertNull("the app's own title is not a person", Person.peerName(titles, emptyList(), WIN))

        // A real conversation has at least one message of mine.
        val chat = listOf(
            Chat.Bubble("扮猪吃老虎吗？", true, box(150, 900, 620, 990)),
            Chat.Bubble("我喜欢欲擒故纵", false, box(800, 1050, 1300, 1140)),
        )
        assertTrue(Chat.inConversation(chat))
    }

    @Test fun uselessNoulIsHidden() {
        assertTrue(Jev.card(mapOf("literal" to Jev.Answer.Noul(0.5))).isEmpty())
    }

    @Test fun transcriptTextIsEscaped() {
        val body = Jev.requestBody("测试", listOf("对方" to "他说\"随便\"\n然后就不理我了"))
        assertTrue(body.contains("""他说\"随便\"\n然后就不理我了"""))
    }
}

class LearnerTest {

    private fun box(l: Int, t: Int, r: Int, b: Int) = Chat.Box(l, t, r, b)

    @Test fun rewardIsFallInDangerAndBiasFollowsError() {
        val m = Learner.Model()
        // predicted 0.8, the next turn came back at 0.3: the advice preceded a calm-down
        val r = Learner.observe(m, Learner.Episode("*", "生气想吵架", "先回应情绪", 0.8), 0.3)
        assertEquals(0.5, r, 1e-9)
        assertEquals(1, m.arms["*|生气想吵架|先回应情绪"]!!.n)
        assertEquals(0.5, m.arms["*|生气想吵架|先回应情绪"]!!.mean, 1e-9)
        assertTrue("over-called danger should bias downward", m.dangerBias < 0)
        assertTrue(Learner.danger(m, 0.8) < 0.8)
    }

    @Test fun rerankNeedsEvidenceBeforeItMoves() {
        val m = Learner.Model()
        val probs = mapOf("正面回答问题" to 0.6, "先回应情绪" to 0.4)
        assertEquals(probs, Learner.rerank(m, "*", "生气想吵架", probs))          // nothing learned yet

        repeat(2) { Learner.observe(m, Learner.Episode("*", "生气想吵架", "先回应情绪", 0.8), 0.2) }
        assertFalse(Learner.changedTop(probs, Learner.rerank(m, "*", "生气想吵架", probs)))  // n=2, below MIN_N

        repeat(2) { Learner.observe(m, Learner.Episode("*", "生气想吵架", "先回应情绪", 0.8), 0.1) }
        repeat(4) { Learner.observe(m, Learner.Episode("*", "生气想吵架", "正面回答问题", 0.4), 0.9) }
        val after = Learner.rerank(m, "*", "生气想吵架", probs)
        assertTrue("learned arm should overtake", Learner.changedTop(probs, after))
        assertEquals("先回应情绪", after.maxByOrNull { it.value }!!.key)
        assertEquals(1.0, after.values.sum(), 1e-9)
    }

    @Test fun learningIsScopedToTheIntent() {
        val m = Learner.Model()
        repeat(5) { Learner.observe(m, Learner.Episode("恋爱或亲密关系", "在表达不满", "先回应情绪", 0.9), 0.1) }
        val probs = mapOf("正面回答问题" to 0.52, "先回应情绪" to 0.48)

        // Backoff on purpose: an action with a track record generalizes to a new intent, because
        // starting from zero on every unseen intent would make the learning useless in practice.
        assertTrue(Learner.changedTop(probs, Learner.rerank(m, "恋爱或亲密关系", "只是闲聊", probs)))
        assertNotNull(Learner.armFor(m, "朋友", "只是闲聊", "先回应情绪"))

        // The same action can be right in one context and wrong in another, and the specific
        // arm is what gets used once it has its own evidence.
        repeat(4) { Learner.observe(m, Learner.Episode("朋友", "只是闲聊", "先回应情绪", 0.2), 0.7) }
        assertTrue(Learner.armFor(m, "朋友", "只是闲聊", "先回应情绪")!!.mean < 0)
        assertTrue(Learner.armFor(m, "恋爱或亲密关系", "在表达不满", "先回应情绪")!!.mean > 0)
    }

    @Test fun anUnknownSituationCreditsEachArmOnce() {
        val m = Learner.Model()
        Learner.observe(m, Learner.Episode("*", "在表达不满", "先道歉", 0.8), 0.3)
        assertEquals(1, m.arms["*|在表达不满|先道歉"]!!.n)   // not 2, despite two keys colliding
        assertEquals(1, m.arms["*|*|先道歉"]!!.n)
        assertEquals(2, m.arms.size)
    }

    @Test fun modelSurvivesSaveLoad() {
        val m = Learner.Model()
        Learner.observe(m, Learner.Episode("*", "想确认你在不在乎", "翻聊天记录找事实", 0.7), 0.2)
        val back = Learner.load(Learner.save(m))
        assertEquals(m.updates, back.updates)
        assertEquals(m.dangerBias, back.dangerBias, 1e-9)
        assertEquals(m.arms["想确认你在不在乎|翻聊天记录找事实"], back.arms["想确认你在不在乎|翻聊天记录找事实"])
        assertEquals(Learner.Model().arms, Learner.load("garbage").arms)
    }

    @Test fun onlyScoreAdviceWhenIReplied() {
        val judged = Chat.Bubble("所以呢？", true, box(140, 300, 500, 380))
        val mine = Chat.Bubble("我来安排", false, box(380, 400, 940, 480))
        val next = Chat.Bubble("这还差不多。", true, box(140, 500, 520, 580))
        assertTrue(Chat.repliedSince(listOf(judged, mine, next), "所以呢？"))
        assertFalse("they double-texted, that says nothing about the advice",
            Chat.repliedSince(listOf(judged, next), "所以呢？"))
        assertFalse(Chat.repliedSince(listOf(mine, next), "没见过的消息"))
    }
}

class HomeScreenTest {

    private val win = Chat.Box(0, 0, 1440, 3120)
    private fun box(l: Int, t: Int, r: Int, b: Int) = Chat.Box(l, t, r, b)

    @Test fun theTabBarGivesTheChatListAway() {
        // WeChat's conversation list: rows of names and previews that look just like messages,
        // and a tab bar at the bottom that a real chat page never has.
        val list = listOf(
            "WeChat" to box(600, 120, 840, 200),
            "小明" to box(175, 240, 300, 300),
            "哈哈哈哈 这个挺好玩的" to box(175, 300, 700, 360),
            "Mia" to box(175, 560, 420, 620),
            "好" to box(175, 620, 240, 680),
            "WeChat" to box(60, 2880, 260, 2960),
            "Contacts" to box(400, 2880, 620, 2960),
            "Discover" to box(760, 2880, 980, 2960),
            "Me" to box(1150, 2880, 1300, 2960),
        )
        assertTrue(Chat.looksLikeHomeScreen(list, win))

        val chat = listOf(
            "Mia" to box(610, 140, 830, 210),
            "半只脚踏在鬼门关了" to box(150, 900, 620, 990),
            "很冷" to box(1000, 1050, 1300, 1140),
        )
        assertFalse(Chat.looksLikeHomeScreen(chat, win))
    }

    @Test fun aChatThatMentionsATabWordIsStillAChat() {
        // The words only count as tabs when they sit in the tab bar at the bottom.
        val chat = listOf(
            "Mia" to box(610, 140, 830, 210),
            "发现" to box(150, 900, 400, 990),
            "我" to box(1200, 1050, 1300, 1140),
        )
        assertFalse(Chat.looksLikeHomeScreen(chat, win))
    }
}
