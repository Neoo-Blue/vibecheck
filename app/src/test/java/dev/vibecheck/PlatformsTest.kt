package dev.vibecheck

import org.junit.Assert.*
import org.junit.Test

class PlatformsTest {

    @Test fun deliveryStateAndPresenceAreNotMessages() {
        for (t in listOf(
            "Seen", "Seen 10:32", "Delivered", "Delivered · 10:32 PM", "Sent", "Read", "Now", "Just now",
            "SMS · Now", "RCS message", "Text message", "Typing…", "Active now", "Active 5m ago", "Online",
            "Today 3:45 PM", "Yesterday", "Mon 10:30", "Wednesday", "Sep 21", "Sep 21, 10:30 AM", "21 Sept 2026",
            "9/21/26", "Edited", "Forwarded", "Not delivered", "已送达", "已读", "正在输入…",
            "Today at 10:30 AM", "Yesterday at 3:45 PM", "(edited)", "Played", "Opened · 10:15", "오후 3:45", "上午10:20",
            "2026년 9월 21일", "SIM 1", "既読 10:45",
        )) assertTrue(t, Chat.isTimeOrDate(t))
    }

    @Test fun realEnglishMessagesSurvive() {
        for (t in listOf(
            "I've seen it, thanks", "Can you send it now?", "Read the doc yet?", "Just now leaving the office",
            "See you Monday at 10:30", "ok", "Sure", "Delivered the package to your door",
        )) assertFalse(t, Chat.isTimeOrDate(t))
    }

    @Test fun callStubsAndReactionsAreSystemRows() {
        for (t in listOf(
            "Missed voice call", "Video call · 12 min", "You reacted ❤️ to their message", "Sam reacted 😂 to your message",
            "You unsent a message", "This message was deleted", "You replied to Sam", "Messages and calls are end-to-end encrypted.",
            "Seen by Sam", "Liked by you", "Liked “see you”", "Sam pinned a message", "Security code changed", "Outgoing Call · 3 min",
            "Sam님이 나갔습니다", "语音通话时长 03:12",
        )) assertTrue(t, Chat.isNotification(t))
        for (t in listOf("I liked the movie", "new phone who dis", "call me later", "see you at 8")) assertFalse(t, Chat.isNotification(t))
        assertFalse(Chat.isNotification("call me when you're free"))
    }

    @Test fun presenceSubtitlesAreNotNames() {
        for (t in listOf("Active now", "Active 5m ago", "online", "last seen today at 10:30", "typing…", "3 members", "Messenger", "Messages"))
            assertFalse(t, Person.looksLikeName(t))
        for (t in listOf("Sam Lee", "小李", "Dory🐟", "José")) assertTrue(t, Person.looksLikeName(t))
    }

    @Test fun otherAppsBottomBarsReadAsHomeScreen() {
        val win = Chat.Box(0, 0, 1000, 2000)
        val bar = { labels: List<String> -> labels.mapIndexed { i, l -> l to Chat.Box(i * 250, 1900, i * 250 + 200, 1960) } }
        assertTrue(Chat.looksLikeHomeScreen(bar(listOf("Chats", "Calls", "Stories", "Menu")), win))   // Messenger
        assertTrue(Chat.looksLikeHomeScreen(bar(listOf("Chats", "Updates", "Communities", "Calls")), win))   // WhatsApp
        assertFalse(Chat.looksLikeHomeScreen(bar(listOf("ok", "see you")), win))
    }

    @Test fun mergesPoolCountsAndWeightArms() {
        val a = Relation.Stats(theirMsgs = 10, myMsgs = 5, firstSeen = 500, lastSeen = 900)
        val b = Relation.Stats(theirMsgs = 1, myMsgs = 2, firstSeen = 700, lastSeen = 1200)
        Relation.merge(a, b)
        assertEquals(11, a.theirMsgs); assertEquals(7, a.myMsgs)
        assertEquals(500L, a.firstSeen); assertEquals(1200L, a.lastSeen)

        val s = Person.Style(msgs = 3, chars = 30); Person.mergeStyle(s, Person.Style(msgs = 1, chars = 5))
        assertEquals(4, s.msgs); assertEquals(35, s.chars)

        val m = Learner.Model(dangerBias = 1.0, updates = 3).apply { arms["*|*|x"] = Learner.Arm(2, 1.0) }
        val n = Learner.Model(dangerBias = -1.0, updates = 1).apply { arms["*|*|x"] = Learner.Arm(2, 0.0); arms["*|*|y"] = Learner.Arm(1, 0.5) }
        Learner.merge(m, n)
        assertEquals(4, m.updates)
        assertEquals(0.5, m.dangerBias, 1e-9)
        assertEquals(Learner.Arm(4, 0.5), m.arms["*|*|x"])
        assertEquals(Learner.Arm(1, 0.5), m.arms["*|*|y"])
    }

    @Test fun knownAppsHaveLabels() {
        assertEquals("Messenger", Apps.label("com.facebook.orca"))
        assertEquals("unknownapp", Apps.label("com.example.unknownapp"))
        assertTrue(Prefs.DEFAULT_PACKAGES.split(",").all { p -> Apps.KNOWN.any { it.first == p } })
    }
}
