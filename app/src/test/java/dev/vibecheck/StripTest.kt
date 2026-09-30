package dev.vibecheck

import org.junit.Assert.*
import org.junit.Test

/**
 * Soul's quick replies when OCR runs them together, the hint in the empty reply box, the hint
 * under the follow button, the card of the other person at the top of a new chat, and party
 * rooms. Soul's screens laid out as they are (923 x 2000), with made-up messages and names.
 */
class StripTest {

    private val win = Chat.Box(0, 0, 923, 2000)
    private fun box(l: Int, t: Int, r: Int, b: Int) = Chat.Box(l, t, r, b)

    private val title = listOf(
        "35" to box(83, 148, 115, 180),
        "张三" to box(145, 148, 215, 182),
        "关注" to box(740, 148, 800, 182),
        "关注后可邀请通话" to box(672, 202, 830, 222),
    )
    private val messages = listOf(
        "周末去爬山吗" to box(195, 370, 400, 405),
        "好呀 几点" to box(560, 530, 730, 568),
        "早上八点吧" to box(195, 852, 380, 888),
        "那我定个闹钟" to box(507, 1340, 730, 1375),
    )
    private val hint = "不知道说啥，讲个笑话也行" to box(143, 1862, 550, 1905)

    private fun read(items: List<Pair<String, Chat.Box>>) = Chat.fromOcr(items, win, emptyList()).first

    @Test fun chipsOcrRanTogetherAreNotMessages() {
        val chips = listOf("晚上好 交换答案" to box(115, 1758, 458, 1795), "桌球 礼物" to box(580, 1758, 828, 1795))
        val bubbles = read(title + messages + chips + hint)
        assertEquals(messages.map { it.first }, bubbles.map { it.text })
        assertEquals(listOf(true, false, true, false), bubbles.map { it.incoming })
        // Mine is the newest: nothing of theirs to answer.
        assertNull(Chat.triggerKey(bubbles))
    }

    @Test fun twoOfTheStripsLabelsSideBySideAreAStrip() {
        val chips = listOf("晚上好" to box(115, 1758, 210, 1795), "交换答案" to box(330, 1758, 458, 1795))
        assertEquals(messages.map { it.first }, read(title + messages + chips + hint).map { it.text })
        // Two short messages of other words side by side are left to the rule of three.
        val two = listOf("好的" to box(195, 1000, 260, 1035), "行" to box(600, 1000, 640, 1035))
        assertEquals(2, Chat.messageIndices(two, win).size)
    }

    @Test fun whatIsARunOfChipLabels() {
        // OCR's reading of the icons: marks and digits, or a letter before a label.
        for (s in listOf("晚上好 交换答案", "桌球 礼物", "桌球礼物", "8 桌球 礼物", "下午好 礼物 比心", "O晚上好 O交换答案", "比心 猜拳"))
            assertTrue(s, Chat.isChipRun(s))
        // What people send: a greeting with a heart, a label twice, a label in a sentence.
        for (s in listOf("晚安 晚安", "晚安 比心", "早安比心", "礼物", "送你个礼物", "送礼物比心", "晚上好 早安", "礼物礼物", "我们去打桌球吧"))
            assertFalse(s, Chat.isChipRun(s))
    }

    @Test fun theHintUnderAStripAlongTheBottomIsNotTheirs() {
        val chips = listOf(
            "晚上好" to box(115, 1758, 210, 1795), "交换答案" to box(330, 1758, 458, 1795),
            "桌球" to box(580, 1758, 642, 1795), "礼物" to box(765, 1758, 828, 1795),
        )
        val bubbles = read(title + messages + chips + hint)
        assertFalse(bubbles.any { it.text == hint.first })
        assertEquals("那我定个闹钟", bubbles.last().text)
        // A message under a row of reactions higher up stays.
        val reactions = listOf("👍 2" to box(195, 1420, 260, 1450), "❤️ 1" to box(270, 1420, 335, 1450), "😂 3" to box(345, 1420, 410, 1450))
        val newest = "明天见" to box(195, 1600, 320, 1635)
        assertEquals("明天见", read(messages + reactions + newest).last().text)
    }

    @Test fun theHintUnderTheFollowButtonIsNotMine() {
        assertFalse(read(title + messages).any { it.text == "关注后可邀请通话" })
        assertTrue(Chat.isNotification("关注后可邀请通话"))
        assertFalse(Chat.isNotification("关注后可以看到我的动态"))
    }

    @Test fun theCardOfTheOtherPersonIsNotTheirWords() {
        val card = listOf(
            "小猫星球" to box(300, 420, 460, 452),
            "天秤座" to box(480, 420, 580, 452),
            "礼仪分：[赞]良好" to box(300, 470, 620, 502),
        )
        val chat = listOf("你好呀" to box(195, 700, 320, 735), "你好" to box(640, 860, 730, 895))
        assertEquals(listOf("你好呀", "你好"), read(title + card + chat).map { it.text })
        // Without the score beside it a star sign is an answer, and it stays.
        val answer = listOf("你什么星座" to box(560, 700, 730, 735), "天秤座" to box(195, 860, 300, 895))
        assertEquals(listOf("你什么星座", "天秤座"), read(answer).map { it.text })
        assertFalse(Chat.isCardField("哪个星球"))
        assertTrue(Chat.isCardField("天秤座 · 22岁"))
        // Asking about the score is a message.
        assertFalse(Chat.isCardScore("礼仪分：多少？"))
        assertFalse(Chat.isNotification("礼仪分：多少？"))
    }

    @Test fun keptHistoryLosesTheStripAndTheCard() {
        val real = listOf("对方" to "你好呀", "我" to "你好", "对方" to "在干嘛")
        val kept = listOf(
            "对方" to "小猫星球", "对方" to "天秤座", "对方" to "礼仪分：[赞]良好",
        ) + real.take(2) + listOf("对方" to "晚上好 交换答案", "我" to "桌球 礼物") + real.drop(2) +
            listOf("对方" to "晚上好", "对方" to "交换答案", "我" to "桌球", "我" to "礼物")
        assertEquals(real, Archive.withoutCard(Archive.withoutStrips(kept)))
        // A star sign away from any card, and single labels, are what people send.
        val sent = listOf("我" to "你什么星座", "对方" to "天秤座", "对方" to "礼物", "我" to "晚上好")
        assertSame(sent, Archive.withoutCard(sent))
        assertEquals(sent, Archive.withoutStrips(sent))
    }

    @Test fun aPartyRoomIsNotAChat() {
        // A game of pool in a party room: the host and the room's number up top, a broadcast, the
        // reply box and the mic button along the bottom.
        val room = listOf(
            "李四" to box(125, 108, 325, 138),
            "关注" to box(368, 128, 413, 152),
            "FM12345678" to box(125, 145, 260, 170),
            "邀请" to box(850, 338, 885, 355),
            "恭喜小明在乐园抽中幸运大奖，解锁一枚勋章！" to box(58, 1640, 665, 1775),
            "说点什么..." to box(64, 1870, 195, 1900),
            "上麦" to box(790, 1868, 845, 1898),
        )
        assertTrue(Chat.looksLikeRoom(room, win))
        // Either sign is enough: the keyboard hides the button, and not every room shows a number.
        assertTrue(Chat.looksLikeRoom(room.filterNot { it.first == "上麦" }, win))
        assertTrue(Chat.looksLikeRoom(room.filterNot { it.first.startsWith("FM") }, win))
        // A chat is not a room, nor is a message that says 上麦.
        assertFalse(Chat.looksLikeRoom(title + messages + hint, win))
        assertFalse(Chat.looksLikeRoom(messages + ("上麦" to box(195, 1000, 260, 1035)), win))
    }
}
