package dev.vibecheck

import org.junit.Assert.*
import org.junit.Test

class PersonTest {

    private val win = Chat.Box(0, 0, 1440, 3120)
    private fun box(l: Int, t: Int, r: Int, b: Int) = Chat.Box(l, t, r, b)

    @Test fun peerNameIsTheCenteredTitle() {
        val titles = listOf(
            "返回" to box(30, 120, 110, 200),        // back label, far left
            "Mia" to box(610, 110, 830, 200),    // the title, centered
        )
        assertEquals("Mia", Person.peerName(titles, emptyList(), win))
    }

    @Test fun ocrJunkNeverBecomesAPerson() {
        // Real capture from the status bar area: this was being stored as a contact.
        assertFalse(Person.looksLikeName("M%。l 65"))
        assertFalse(Person.looksLikeName("1:16"))
        assertFalse(Person.looksLikeName("68"))
        assertFalse(Person.looksLikeName("WeChat"))
        assertTrue(Person.looksLikeName("Mia"))
        assertTrue(Person.looksLikeName("小明"))
        assertTrue(Person.looksLikeName("Dory Arden"))
        assertNull(Person.peerName(listOf("M%。l 65" to box(600, 110, 900, 190)), emptyList(), win))
    }

    @Test fun statusBarTextIsNotATitle() {
        // 1440x3120: the clock sits at y≈50, the chat title at y≈140.
        val items = listOf(
            "1:16" to Chat.Box(60, 40, 200, 100),          // status bar
            "Mia" to Chat.Box(610, 140, 830, 210),     // title bar
            "周末有空吗？" to Chat.Box(150, 900, 620, 990),
            "好的" to Chat.Box(1000, 1050, 1300, 1140),
        )
        val (_, titles) = Chat.fromOcr(items, win, emptyList())
        assertEquals(listOf("Mia"), titles.map { it.first })
        assertEquals("Mia", Person.peerName(titles, emptyList(), win))
    }

    @Test fun aNameInSmallLettersIsStillTheTitle() {
        // 1440x3120 as WeChat draws a chat: the clock, a name with no tall letters in the middle of
        // the title bar (its box starts 7.1% down, below where a 「小雨」 starts), the first message
        // under the bar.
        val items = listOf(
            "8:31" to Chat.Box(40, 30, 190, 100),
            "anna" to Chat.Box(634, 222, 793, 256),
            "在吗" to Chat.Box(1060, 375, 1200, 440),
        )
        val (bubbles, titles) = Chat.fromOcr(items, win, emptyList())
        assertEquals(listOf("anna"), titles.map { it.first })
        assertEquals("anna", Person.peerName(titles, emptyList(), win))
        assertEquals(listOf("在吗"), bubbles.map { it.text })
        // A message scrolled under the bar, a sliver of it showing below the bar's edge: not a title.
        val sliver = listOf("anna" to Chat.Box(634, 222, 793, 256), "好的呀" to Chat.Box(200, 332, 500, 348))
        assertEquals(listOf("anna"), Chat.fromOcr(sliver, win, emptyList()).second.map { it.first })
        // A shorter screen, where the bar is a larger share of it.
        val short = Chat.Box(0, 0, 1080, 1920)
        assertEquals("anna", Person.peerName(Chat.fromOcr(listOf("anna" to Chat.Box(470, 142, 610, 170)), short, emptyList()).second, emptyList(), short))
    }

    @Test fun theLineUnderANameIsNotTheName() {
        // Telegram read by OCR: the name, and under it what they are doing, nearer the middle.
        val items = listOf(
            "Ken" to Chat.Box(250, 165, 380, 225),
            "recording audio..." to Chat.Box(250, 240, 620, 290),
        )
        val (_, titles) = Chat.fromOcr(items, win, emptyList())
        assertEquals(2, titles.size)
        assertEquals("Ken", Person.peerName(titles, emptyList(), win))
        // Side by side is not one under the other.
        assertEquals("小雨", Person.peerName(listOf("小雨" to box(600, 170, 840, 240), "Mute" to box(900, 170, 1040, 240)), emptyList(), win))
    }

    @Test fun peerNameFallsBackToAvatarDescription() {
        assertEquals("小雨", Person.peerName(emptyList(), listOf("小雨头像"), win))
        // junk-only titles must not win over a usable avatar description
        assertEquals("小雨", Person.peerName(listOf("返回" to box(30, 120, 110, 200)), listOf("小雨头像"), win))
        assertNull(Person.peerName(emptyList(), listOf("头像"), win))
    }

    @Test fun styleNeedsSamplesThenDescribesMe() {
        val s = Person.Style()
        Person.observe(s, "在吗？")
        Person.observe(s, "抱歉我忘了")
        assertNull("two messages is not a personality", Person.styleSummary(s))

        Person.observe(s, "晚上一起吃饭吧 🙂")
        val summary = Person.styleSummary(s)!!
        assertTrue(summary.contains("共 3 条"))
        assertEquals(3, s.msgs)
        assertEquals(1, s.questions)
        assertEquals(1, s.apologies)
        assertEquals(1, s.emoji)
    }

    @Test fun styleAndHistorySurviveSaveLoad() {
        val s = Person.Style()
        listOf("好的", "我来安排", "对不起，是我不对 😅").forEach { Person.observe(s, it) }
        assertEquals(s, Person.loadStyle(Person.saveStyle(s)))
        assertEquals(Person.Style(), Person.loadStyle("nonsense"))

        var h = listOf<Person.Turn>()
        repeat(10) { i -> h = Person.push(h, Person.Turn("生气想吵架", i, "先回应情绪")) }
        assertEquals(Person.HISTORY, h.size)                 // bounded
        assertEquals(9, h.last().danger)                     // newest kept
        assertEquals(h, Person.loadHistory(Person.saveHistory(h)))
        assertTrue(Person.historySummary(h)!!.contains("危9"))
        assertNull(Person.historySummary(emptyList()))
    }

    @Test fun idsSeparatePeopleAndApps() {
        assertNotEquals(Person.id("com.tencent.mm", "Mia"), Person.id("com.tencent.mm", "小雨"))
        assertNotEquals(Person.id("com.tencent.mm", "Mia"), Person.id("cn.soulapp.android", "Mia"))
        assertEquals(Person.id("com.tencent.mm", "Mia"), Person.id("com.tencent.mm", " Mia "))
    }

    @Test fun eachPersonKeepsTheirOwnLearning() {
        val a = Learner.Model()
        val b = Learner.Model()
        val probs = mapOf("正面回答问题" to 0.6, "先道歉" to 0.4)

        // Apologising calms this person down; answering plainly makes it worse.
        repeat(4) { Learner.observe(a, Learner.Episode("*", "生气想吵架", "先道歉", 0.9), 0.1) }
        repeat(4) { Learner.observe(a, Learner.Episode("*", "生气想吵架", "正面回答问题", 0.3), 0.9) }
        assertTrue(Learner.changedTop(probs, Learner.rerank(a, "*", "生气想吵架", probs)))

        assertEquals("what works on one person says nothing about another",
            probs, Learner.rerank(b, "*", "生气想吵架", probs))
    }

    @Test fun oneGoodArmDoesNotOverturnAConfidentModel() {
        // Evidence bends the ranking, it does not replace the judgment: the boost caps at 1.5x,
        // so a 0.4 option needs the model to be near-tied, not merely a good track record.
        val m = Learner.Model()
        repeat(4) { Learner.observe(m, Learner.Episode("*", "生气想吵架", "先道歉", 0.9), 0.1) }
        assertFalse(Learner.changedTop(
            mapOf("正面回答问题" to 0.6, "先道歉" to 0.4),
            Learner.rerank(m, "*", "生气想吵架", mapOf("正面回答问题" to 0.6, "先道歉" to 0.4))))
        assertTrue(Learner.changedTop(
            mapOf("正面回答问题" to 0.52, "先道歉" to 0.48),
            Learner.rerank(m, "*", "生气想吵架", mapOf("正面回答问题" to 0.52, "先道歉" to 0.48))))
    }
}

class IdentityTest {

    private val win = Chat.Box(0, 0, 1440, 3120)
    private fun box(l: Int, t: Int, r: Int, b: Int) = Chat.Box(l, t, r, b)

    @Test fun emojiNamesAreRealNames() {
        assertTrue("an emoji-only contact name is still a name", Person.looksLikeName("🌙"))
        assertTrue(Person.looksLikeName("Dory Arden🐟"))
        assertTrue(Person.looksLikeName("MAOTT🎵"))
        // but status-bar noise still must not qualify
        assertFalse(Person.looksLikeName("M%。l 65"))
        assertFalse(Person.looksLikeName("1:53"))
    }

    @Test fun titleFingerprintIdentifiesAChatOcrCannotName() {
        val chatA = intArrayOf(10, 200, 15, 190, 12, 210, 8, 180, 11, 205, 14, 195, 9, 188, 13, 202,
            10, 200, 15, 190, 12, 210, 8, 180, 11, 205, 14, 195, 9, 188, 13, 202)
        val chatB = intArrayOf(200, 10, 190, 15, 210, 12, 180, 8, 205, 11, 195, 14, 188, 9, 202, 13,
            200, 10, 190, 15, 210, 12, 180, 8, 205, 11, 195, 14, 188, 9, 202, 13)
        assertEquals("same title bar, same id", Person.hashOf(chatA), Person.hashOf(chatA))
        assertNotEquals("different chats must not share memory", Person.hashOf(chatA), Person.hashOf(chatB))
        assertTrue(Person.hashOf(chatA).startsWith("#"))
        assertEquals("", Person.hashOf(intArrayOf()))
    }

    @Test fun theKeyboardIsNotPartOfTheConversation() {
        // Gboard, read straight off the screenshot: every key came back as a message.
        val ime = box(0, 2026, 1440, 3162)
        val items = listOf(
            "Mia" to box(610, 140, 830, 210),
            "今天累得不想动了" to box(150, 900, 620, 990),
            "很冷" to box(1000, 1050, 1300, 1140),
            "o'wE' R TY Ư' I'o RT YU" to box(20, 2100, 1400, 2200),
            "A S D" to box(60, 2300, 900, 2400),
            "?123" to box(40, 2900, 300, 3000),
        )
        val (bubbles, _) = Chat.fromOcr(items, win, listOf(ime))
        assertEquals(listOf("今天累得不想动了", "很冷"), bubbles.map { it.text })
        assertTrue(Chat.inConversation(bubbles))
    }
}

class FingerprintTest {

    @Test fun aBlankRegionIsNotAnIdentity() {
        // The old average hash turned a near-uniform title bar into "#0" for every chat, so 22
        // different conversations shared one memory.
        val flat = IntArray(48) { 236 }
        assertEquals("", Person.hashOf(flat))
        assertEquals("", Person.hashOf(IntArray(48) { 236 + (it % 2) }))   // noise below tolerance
        assertEquals("", Person.hashOf(intArrayOf()))
    }

    @Test fun differentAvatarsGetDifferentIds() {
        val avatarA = IntArray(48) { (it * 37) % 255 }
        val avatarB = IntArray(48) { (255 - (it * 53) % 255) }
        assertNotEquals(Person.hashOf(avatarA), Person.hashOf(avatarB))
        assertEquals("same avatar, same id", Person.hashOf(avatarA), Person.hashOf(avatarA))
        assertTrue(Person.hashOf(avatarA).startsWith("#"))
    }
}
