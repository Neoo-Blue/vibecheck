package dev.vibecheck

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

/** Learning about me across chats: the day log, the write-ups, the profile, what replies are told. */
class MeTest {

    @After fun chinese() { L.en = false }

    private val shanghai = TimeZone.getTimeZone("Asia/Shanghai")

    @Test fun theDayLogKeepsTabsNewlinesAndWhoseChatItWas() {
        val text = Me.encode("21:07", "小明\t2", listOf("我" to "刚下班\n累死", "对方" to "辛苦啦 \\ 快吃饭"))
        val lines = Me.decode(text + "broken line\n")
        assertEquals(2, lines.size)
        assertEquals(Me.Line("21:07", "我", "小明\t2", "刚下班\n累死"), lines[0])
        assertEquals(Me.Line("21:07", "对方", "小明\t2", "辛苦啦 \\ 快吃饭"), lines[1])
    }

    @Test fun daysAndClocksAreLocal() {
        val t = 1_790_028_780_000L   // 2026-09-21 22:13 UTC
        assertEquals("2026-09-22", Me.day(t, shanghai))
        assertEquals("06:13", Me.clock(t, shanghai))
        assertEquals("2026-09-21", Me.day(t, TimeZone.getTimeZone("UTC")))
        assertEquals("9月27日 周日", Me.dayLabel("2026-09-27"))
        L.en = true
        assertEquals("Sun, Sep 27", Me.dayLabel("2026-09-27"))
    }

    private val day = listOf(
        Me.Line("09:10", "我", "小明", "早，今天去公司加班"),
        Me.Line("09:12", "对方", "小明", "周末还加班？"),
        Me.Line("12:30", "我", "🍵", "中午吃了拉面"),
        Me.Line("21:05", "我", "小明", "终于下班了，周六去爬山吧"),
    )

    @Test fun aDayIsWrittenUpChatByChat() {
        val p = Me.dayPrompt("2026-09-27", day)
        assertTrue(p.startsWith("9月27日 周日"))
        assertTrue(p.contains("【和 小明 的聊天】\n09:10 我：早，今天去公司加班\n09:12 小明：周末还加班？\n21:05 我："))
        assertTrue(p.contains("【和 🍵 的聊天】\n12:30 我：中午吃了拉面"))
        // Too long: the morning goes before the evening does.
        val long = (0 until 400).map { Me.Line("%02d:%02d".format(it / 60 % 24, it % 60), "我", "小明", "消息$it " + "字".repeat(80)) }
        val cut = Me.dayPrompt("2026-09-27", long, maxChars = 5000)
        assertTrue(cut.length < 6000)
        assertTrue(cut.contains("消息399"))
        assertFalse(cut.contains("消息0 "))
    }

    @Test fun whatISaidElsewhereTodayLeavesOutThisChatAndTheirLines() {
        val e = Me.elsewhereToday(day, notPerson = "🍵")!!
        assertTrue(e.contains("对 小明：早，今天去公司加班"))
        assertTrue(e.contains("终于下班了"))
        assertFalse(e.contains("拉面"))
        assertFalse("their lines are not mine", e.contains("周末还加班"))
        assertNull(Me.elsewhereToday(day.filter { it.person == "🍵" }, notPerson = "🍵"))
    }

    private val profile = """
        【我是谁】
        • 在上海做产品经理，住静安
        【我怎么说话】
        • 短句，爱用「哈哈哈」和「好滴」，很少用句号
        【我喜欢】
        • 爬山、拉面
        【我最近在忙】
        • 新版本上线，周末常加班
        【我身边的人】
        • 小明：朋友
    """.trimIndent()

    @Test fun repliesAreToldHowITalkWhoIAmAndMyLastDays() {
        val b = Me.brief(profile, listOf("2026-09-27" to "• 加班到九点\n• 约了小明周六爬山"))!!
        val lines = b.lines()
        assertTrue(lines[0].startsWith("【我怎么说话】短句"))
        assertTrue(b.contains("【我是谁】在上海做产品经理"))
        assertTrue(b.contains("【我最近在忙】新版本上线"))
        assertTrue(b.contains("9月27日 周日：加班到九点；约了小明周六爬山"))
        assertFalse("the people list is for the page, not every reply", b.contains("小明：朋友"))
        assertTrue(Me.brief(profile, emptyList(), max = 60)!!.length <= 60)
        assertNull(Me.brief("", emptyList()))
        assertEquals("9月27日 周日：没聊什么", Me.brief("", listOf("2026-09-27" to "• 没聊什么")))
    }

    @Test fun headingsAreKnownInBothLanguages() {
        assertEquals("voice", Me.idOf("我怎么说话"))
        assertEquals("voice", Me.idOf("How I talk"))
        assertEquals("busy", Me.idOf(" 我最近在忙 "))
        assertNull(Me.idOf("Ta 是谁"))
        assertEquals("在上海做产品经理，住静安", Me.oneLine(profile))
        assertNull(Me.oneLine(""))
    }

    @Test fun theProfileIsWrittenFromNotesDaysAndPeople() {
        val p = Me.profilePrompt(listOf("【和 小明 的聊天】\n【我喜欢】\n• 爬山"), listOf("2026-09-27" to "• 加班"), listOf("小明：关系：朋友"), "「哈哈哈」、「好滴」")
        assertTrue(p.contains("我身边的人：\n小明：关系：朋友"))
        assertTrue(p.contains("我最常单独发的话：「哈哈哈」、「好滴」"))
        assertTrue(p.contains("9月27日 周日\n• 加班"))
        assertTrue(p.contains("【和 小明 的聊天】"))
        for (h in listOf("【我是谁】", "【我怎么说话】", "【我最近在忙】", "【我身边的人】")) assertTrue(h, Me.profileSystem().contains(h))
        assertTrue("notes are about me, not them", Me.NOTES_SYSTEM.contains("只记关于「我」的事"))
    }

    @Test fun replyDraftsDrawOnWhatIsKnownAboutMe() {
        val p = OpenRouter.deepPrompt("小明", "", null, null, null, listOf("对方" to "今天干嘛了"), emptyMap(), false, false, me = "【我最近在忙】新版本上线")
        assertTrue(p.contains("关于我（从我所有的聊天里学的）：\n【我最近在忙】新版本上线"))
        assertFalse(OpenRouter.deepPrompt("小明", "", null, null, null, listOf("对方" to "在吗"), emptyMap(), false, false).contains("关于我"))
        assertTrue(OpenRouter.REPLY_SYSTEM.contains("「关于我」"))
    }
}
