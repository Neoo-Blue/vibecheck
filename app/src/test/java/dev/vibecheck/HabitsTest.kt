package dev.vibecheck

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** What is normal for each person, learned message by message and turn by turn, and told to the judge. */
class HabitsTest {

    @After fun chinese() { L.en = false }

    private fun styleOf(vararg messages: String) = Person.Style().also { s -> messages.forEach { Person.observe(s, it) } }

    @Test fun laughingIsCounted() {
        val s = styleOf("哈哈哈哈", "笑死", "hhhh 真的假的", "lol same", "好的", "[捂脸]", "😂")
        assertEquals(7, s.msgs)
        assertEquals(6, s.laughs)
        assertEquals(0, styleOf("哈", "好的", "hello").laughs)
    }

    @Test fun whatIsNormalForThemIsSaidPlainly() {
        assertNull("too little to go on", Person.theirStyleSummary(styleOf("嗯", "好", "哦")))
        val short = Person.theirStyleSummary(styleOf("嗯", "好", "哦", "行", "嗯嗯", "好的"))!!
        assertTrue(short, short.startsWith("话很短，简短的回复是 Ta 的常态"))
        assertTrue(short, short.endsWith("（共 6 条）"))
        val playful = Person.theirStyleSummary(styleOf("哈哈哈你也太逗了吧😂", "笑死我了这个真的绝", "hhhh 你是认真的吗？", "明天几点见呀", "哈哈哈哈哈好"))!!
        assertTrue(playful, playful.contains("常笑、常开玩笑"))
        assertFalse(playful, playful.contains("话很短"))
        L.en = true
        assertTrue(Person.theirStyleSummary(styleOf("k", "ok", "y", "no", "k"))!!.startsWith("writes very short"))
    }

    @Test fun theStyleKeepsItsLaughsAndReadsOldRecords() {
        val s = styleOf("哈哈哈", "好的？", "sorry")
        assertEquals(s, Person.loadStyle(Person.saveStyle(s)))
        // Saved before laughs were counted: five numbers.
        assertEquals(Person.Style(3, 12, 1, 1, 0, 0), Person.loadStyle("3\t12\t1\t1\t0"))
        val into = Person.Style(msgs = 2, laughs = 1)
        Person.mergeStyle(into, Person.Style(msgs = 3, laughs = 2))
        assertEquals(3, into.laughs)
    }

    @Test fun theNormIsAnAverageThatSettlesAndStillFollows() {
        val n = Person.Norm()
        repeat(4) { Person.observeNorm(n, "在开玩笑或一起感慨", 0.0) }
        assertNull("not before five turns", Person.normSummary(n))
        Person.observeNorm(n, "只是闲聊", 1.0)
        // The first turns count fully: a plain average.
        assertEquals(0.2, n.danger, 1e-9)
        assertEquals(0.8, n.intents.getValue("在开玩笑或一起感慨"), 1e-9)
        assertEquals(0.2, n.intents.getValue("只是闲聊"), 1e-9)
        assertEquals("看过 5 轮：常见的是在开玩笑或一起感慨 80%、只是闲聊 20%；平时偶尔有点紧张", Person.normSummary(n))
        // Past the window each turn moves it by 1/40: a long run of one kind wins over, slowly.
        repeat(200) { Person.observeNorm(n, "在表达不满", 0.8) }
        assertEquals("在表达不满", n.intents.maxByOrNull { it.value }!!.key)
        assertTrue(n.danger > 0.75)
        assertFalse("what no longer carries weight goes", n.intents.containsKey("只是闲聊"))
        assertTrue(Person.normSummary(n)!!.endsWith("平时经常很紧张"))
    }

    @Test fun theNormIsKeptAndMerged() {
        val n = Person.Norm()
        listOf("只是闲聊", "只是闲聊", "单纯想知道答案").forEach { Person.observeNorm(n, it, 0.1) }
        val back = Person.loadNorm(Person.saveNorm(n))
        assertEquals(3, back.turns)
        assertEquals(n.danger, back.danger, 1e-9)
        assertEquals(n.intents, back.intents)
        assertEquals(0, Person.loadNorm("").turns)
        // Linked across apps: weighted by the turns each saw.
        val other = Person.Norm(1, 1.0, linkedMapOf("在表达不满" to 1.0))
        Person.mergeNorm(n, other)
        assertEquals(4, n.turns)
        assertEquals((0.1 * 3 + 1.0) / 4, n.danger, 1e-9)
        assertEquals(0.25, n.intents.getValue("在表达不满"), 1e-9)
        val empty = Person.Norm()
        Person.mergeNorm(empty, other)
        assertEquals(1, empty.turns)
    }

    @Test fun theJudgeIsToldWhatIsNormalForThem() {
        val state = Jev.stateJson("", listOf("对方" to "嗯"), theirStyle = "话很短", usual = "平时聊得很轻松")
        assertTrue(state, state.contains("\"对方平时的说话方式\":\"话很短\""))
        assertTrue(state, state.contains("\"对方平时的状态\":\"平时聊得很轻松（仅供参考，这一轮以对话本身为准）\""))
        val bare = Jev.stateJson("", listOf("对方" to "嗯"))
        assertFalse(bare.contains("对方平时"))
    }

    @Test fun draftsAreToldHowTheyWrite() {
        val p = OpenRouter.deepPrompt("小雨", "", null, null, null, listOf("对方" to "嗯"), emptyMap(), false, false, theirStyle = "话很短")
        assertTrue(p.contains("Ta 平时的说话方式：话很短"))
        assertFalse(OpenRouter.deepPrompt("小雨", "", null, null, null, listOf("对方" to "嗯"), emptyMap(), false, false).contains("Ta 平时的说话方式"))
    }
}

/** Reply drafts are asked for often: they get the parts of the profile that shape a reply, not all of it. */
class DraftProfileTest {

    private val profile = """
        【Ta 是谁】
        • 在一所学校当老师，养了一只猫
        【我们怎么相处】
        • 常一起吐槽工作，Ta 更主动
        【Ta 怎么说话】
        • 爱说「真的假的」，很少用表情
        【我怎么跟 Ta 说话】
        • 短句，爱接梗
        【重要的事】
        • 去年一起出去旅行过一次
        【我们的梗】
        • 「又来了」
        【雷区】
        • 别在 Ta 上课时催
    """.trimIndent()

    @Test fun thePartsThatShapeAReplyComeFirst() {
        val d = Profile.forDrafts(profile)
        val order = listOf("我们怎么相处", "我怎么跟 Ta 说话", "Ta 怎么说话", "我们的梗", "雷区", "Ta 是谁", "重要的事")
        assertEquals(order, d.lines().map { it.substringAfter('【').substringBefore('】') })
        assertTrue(d.contains("【我们怎么相处】常一起吐槽工作，Ta 更主动"))
    }

    @Test fun itFitsItsRoom() {
        val d = Profile.forDrafts(profile, max = 60)
        assertTrue(d.length <= 60)
        assertTrue(d, d.startsWith("【我们怎么相处】"))
        // A part too long for what is left is skipped, and a shorter one after it still goes in.
        val long = "【我们怎么相处】\n• 常一起吐槽工作\n【我怎么跟 Ta 说话】\n• " + "很长".repeat(40) + "\n【我们的梗】\n• 「又来了」"
        assertEquals(listOf("【我们怎么相处】常一起吐槽工作", "【我们的梗】「又来了」"), Profile.forDrafts(long, max = 60).lines())
        // The first part is cut to fit rather than left out.
        assertEquals(20, Profile.forDrafts("【我们怎么相处】\n• " + "很长".repeat(40), max = 20).length)
        // An older profile without sections goes as far as it fits.
        assertEquals("对方是我的好友", Profile.forDrafts("对方是我的好友，关系亲密", max = 7))
    }
}
