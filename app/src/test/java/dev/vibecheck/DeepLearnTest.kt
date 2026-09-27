package dev.vibecheck

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** The kept history and the profile written from it. */
class ArchiveTest {

    private fun t(s: String) = "对方" to s
    private fun m(s: String) = "我" to s

    @Test fun historySurvivesSaveLoadWhateverIsInIt() {
        val lines = listOf(t("第一行\n第二行"), m("a\tb"), t("反斜杠 \\n 不是换行"), m("😂"), t(""))
        val back = Archive.decode(Archive.encode(lines))
        assertEquals("an empty message has nothing to keep", lines.dropLast(1), back)
        assertEquals(emptyList<Pair<String, String>>(), Archive.decode("garbage\nX\tnope\n"))
    }

    @Test fun aReadThatReachedTheKeptHistoryAddsOnlyWhatIsNew() {
        val kept = (1..100).map { if (it % 2 == 0) m("旧$it") else t("旧$it") }
        // Read back from the newest message until it met the kept history, a few lines in.
        val read = kept.takeLast(6) + listOf(t("新消息一"), m("新消息二"), t("新消息三"))
        assertTrue(Archive.reached(kept.takeLast(Archive.MERGE_WINDOW), read.take(8)))
        assertEquals(kept + read.takeLast(3), Archive.merge(kept, read))
        // Nothing new since: the kept history as it was.
        assertEquals(kept, Archive.merge(kept, kept.takeLast(10)))
        // A read that never met it does not line up.
        assertNull(Archive.merge(kept, (1..10).map { t("完全不同的第${it}条") }))
        assertFalse(Archive.reached(emptyList(), read))
    }

    @Test fun exchangesPairTheirMessageWithMyAnswer() {
        val lines = listOf(m("早"), t("在吗"), t("明天去吃火锅吗"), t("就那家"), m("好啊"), m("几点"), m("我请"), t("七点"), m("行"))
        val ex = Archive.exchanges(lines)
        assertEquals(2, ex.size)
        assertEquals("明天去吃火锅吗 / 就那家", ex[0].theirs)
        assertEquals("好啊 / 几点", ex[0].mine)
        assertEquals("七点", ex[1].theirs)
        assertEquals("行", ex[1].mine)
    }

    @Test fun examplesAreTheMostAlikeThenTheLatestInTimeOrder() {
        val lines = ArrayList<Pair<String, String>>()
        for (i in 1..40) { lines.add(t("今天天气第${i}天")); lines.add(m("嗯嗯$i")) }
        lines.add(t("我不想出门，好累")); lines.add(m("那我陪你一起发臭发烂"))
        for (i in 41..50) { lines.add(t("随便聊聊$i")); lines.add(m("哈哈$i")) }
        val picked = Archive.examples(lines, "今天好累不想出门", k = 4)
        assertEquals(4, picked.size)
        assertTrue("the alike one is in", picked.any { it.mine == "那我陪你一起发臭发烂" })
        assertTrue("topped up with the latest", picked.any { it.mine == "哈哈50" })
        val order = picked.map { e -> lines.indexOfFirst { it.second == e.mine } }
        assertEquals("in time order", order.sorted(), order)
        // What is on screen now is not shown back as the past.
        val shown = Archive.examples(lines, "今天好累不想出门", k = 4, exclude = setOf("那我陪你一起发臭发烂"))
        assertFalse(shown.any { it.mine == "那我陪你一起发臭发烂" })
    }

    @Test fun phrasesAreCountedNotGuessed() {
        val lines = List(5) { m("哈哈哈哈") } + List(3) { m("好滴") } + List(2) { m("晚安") } +
            List(9) { m("[图片]") } + List(4) { t("笑死我了") } + List(4) { m("。。。") } + m("这是一句很长的话不会被当成口头禅的")
        assertEquals(listOf("哈哈哈哈" to 5, "好滴" to 3), Archive.phrases(lines, "我"))
        assertEquals(listOf("笑死我了" to 4), Archive.phrases(lines, "对方"))
        assertEquals("「哈哈哈哈」、「好滴」", Archive.phraseLine(Archive.phrases(lines, "我")))
    }

    @Test fun aStretchHasTheSameHashEveryTime() {
        assertEquals(Archive.hash("对方：在吗\n"), Archive.hash("对方：在吗\n"))
        assertNotEquals(Archive.hash("对方：在吗\n"), Archive.hash("对方：在吗？\n"))
    }
}

class ProfileTest {

    @After fun chinese() { L.en = false }

    private val profile = """
        【Ta 是谁】
        • 在上海做设计，养了一只叫麻辣烫的猫
        【我们怎么相处】• 天天互怼，Ta 更主动
        【Ta 怎么说话】
        • 爱说「笑死我了」，很少用表情
        【雷区】
        • 别拿 Ta 的身高开玩笑
        • 别在 Ta 加班时催
        【Ta 难过时】
        • 先陪着一起吐槽，别讲道理
        【我们的梗】
        • 「发臭发烂」
    """.trimIndent()

    @Test fun sectionsAreReadWithHeadingsOnTheirOwnLineOrNot() {
        val s = Profile.sections(profile)
        assertEquals(listOf("who", "us", "their", "sore", "comfort", "jokes"), s.map { it.id })
        assertEquals(listOf("• 天天互怼，Ta 更主动"), s[1].lines)
        assertEquals("别拿 Ta 的身高开玩笑；别在 Ta 加班时催", s[3].text)
        L.en = true
        assertEquals("sore", Profile.sections("[Sore spots]\n• height jokes").single().id)
    }

    @Test fun theJudgeGetsTheBriefInPriorityOrder() {
        val b = Profile.brief(profile)
        assertTrue(b.startsWith("【我们怎么相处】"))
        assertTrue(b.indexOf("【雷区】") < b.indexOf("【Ta 是谁】"))
        assertFalse("jokes are not for the judge", b.contains("发臭发烂"))
        assertTrue(Profile.brief(profile, max = 60).length <= 60)
        // A profile from before sections goes as it is.
        assertEquals("• 对方是我的好友", Profile.brief("• 对方是我的好友"))
    }

    @Test fun theCardGetsTheOnePartThatFitsTheMoment() {
        fun dist(top: String) = Jev.Answer.Dist(top, mapOf(top to 0.8))
        assertEquals("Ta 难过时：先陪着一起吐槽，别讲道理", Profile.hint(profile, mapOf("need" to dist("情绪安抚"))))
        assertEquals("雷区：别拿 Ta 的身高开玩笑", Profile.hint(profile, mapOf("danger" to Jev.Answer.Scored(4.0, 6))))
        assertEquals("我们的梗：「发臭发烂」", Profile.hint(profile, mapOf("intent" to dist("在开玩笑或一起感慨"))))
        assertNull(Profile.hint(profile, mapOf("intent" to dist("单纯想知道答案"))))
        assertNull(Profile.hint("• 老档案没有分段", mapOf("need" to dist("情绪安抚"))))
    }

    @Test fun notesSurviveSaveLoad() {
        val notes = linkedMapOf("abc" to "【雷区】\n• 身高\\n不是换行", "def" to "一行")
        assertEquals(notes, Profile.loadNotes(Profile.saveNotes(notes)))
    }

    @Test fun notesAreMergedInGroupsThatFit() {
        val notes = List(10) { "笔记".repeat(50) }   // 100 characters each
        val groups = Profile.groups(notes, maxChars = 350)
        assertEquals(notes, groups.flatten())
        assertTrue(groups.all { g -> g.sumOf { it.length } <= 350 })
        assertEquals(4, groups.size)
    }
}

class CloseTest {

    @After fun chinese() { L.en = false }

    @Test fun relationshipAndClosenessAreReadSeparatelyAndTakenOut() {
        val p = Relationship.parse("关系：朋友\n亲近：很铁\n【Ta 是谁】\n• 设计师")
        assertEquals("朋友", p.rel)
        assertEquals("很铁", p.close)
        assertEquals("【Ta 是谁】\n• 设计师", p.profile)
        L.en = true
        val e = Relationship.parse("Relationship: friend\nCloseness: very close\n[Who they are]")
        assertEquals("朋友" to "无话不谈", e.rel to e.close)
    }

    @Test fun anOldProfileGetsItsClosenessFromItsFirstLine() {
        val old = "• 对方是我的好友或死党，关系亲密，彼此可以随意开玩笑、互怼。\n• 常聊日常"
        val p = Relationship.parse(old)
        assertEquals("朋友" to "很铁", p.rel to p.close)
        assertEquals(old, p.profile)
    }

    @Test fun closenessWordsFarApartAreNoAnswer() {
        assertEquals("无话不谈", Relationship.guessCloseness("最好的朋友，也是死党"))
        assertNull(Relationship.guessCloseness("刚认识，但像死党一样"))
        assertNull(Relationship.guessCloseness("常聊日常"))
        assertEquals("不熟", Relationship.guessCloseness("• 刚认识的网友"))
    }

    @Test fun closeIsToldToTheJudgeOnItsOwn() {
        val state = Jev.stateJson("", listOf("对方" to "在吗"), relationship = "朋友", closeness = "很铁")
        assertTrue(state.contains("\"我和对方的关系\":\"朋友\""))
        assertTrue(state.contains("\"我们有多亲近\":\"很铁\""))
        assertEquals(null, Relationship.closeness("亲密"))
        for (c in Relationship.CLOSENESS) assertNotEquals(c, L.english(c))
        assertEquals("closeness labels do not clash with other options", Relationship.CLOSENESS.size,
            Relationship.CLOSENESS.map { L.english(it) }.toSet().size)
    }
}

class EmojiNameTest {

    @Test fun emojiNamesAreNames() {
        for (n in listOf("🐟", "❤️", "Dory🐟", "✨小鱼✨", "👨‍👩‍👧", "🧑🏻‍💻", "☁️☁️", "小鱼🐟2号", "♡欧欧♡")) {
            assertTrue(n, Person.looksLikeName(n))
        }
    }

    @Test fun clockAndIconNoiseStillIsNot() {
        for (n in listOf("12:30", "⚡65%", "4G", "", "…", "M%。l 65")) assertFalse(n, Person.looksLikeName(n))
    }

    @Test fun theTypingIndicatorIsNotAName() {
        for (n in listOf("对方正在输入...", "对方正在讲话...", "正在输入…", "typing…")) {
            assertFalse(n, Person.looksLikeName(n))
            assertTrue(n, Person.isTyping(listOf(n to Chat.Box(0, 0, 10, 10))))
        }
        assertFalse(Person.isTyping(listOf("欧欧" to Chat.Box(0, 0, 10, 10))))
    }
}
