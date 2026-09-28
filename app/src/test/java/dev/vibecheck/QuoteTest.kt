package dev.vibecheck

import org.junit.Assert.*
import org.junit.Test

/** A reply that quotes an earlier message: the quoted words are the quoted person's, not the replier's. */
class QuoteTest {

    private fun box(top: Int, left: Int = 60, right: Int = 700) = Chat.Box(left, top, right, top + 60)
    private fun theirs(text: String, top: Int) = Chat.Bubble(text, true, box(top))
    private fun mine(text: String, top: Int) = Chat.Bubble(text, false, box(top, 380, 1020))

    @Test fun myNameIsLearnedFromTheirQuoteOfMyWords() {
        // My message on screen, their reply, and under it their quote of my message.
        val screen = listOf(mine("周六去爬山吗", 300), theirs("好呀", 500), theirs("Ken：周六去爬山吗", 580))
        assertEquals(setOf("Ken"), Chat.myQuotedNames(screen, emptyList(), listOf("小雨")))
        // Or with my message no longer on screen, among the newest kept.
        val later = listOf(theirs("可以", 500), theirs("Ken：周末有空吗", 580))
        assertEquals(setOf("Ken"), Chat.myQuotedNames(later, listOf("我" to "周末有空吗", "对方" to "有"), listOf("小雨")))
        // Their quote of their own words names them, not me; and their name is never mine.
        assertTrue(Chat.myQuotedNames(listOf(theirs("在吗", 300), mine("在", 500), mine("小雨：在吗", 580)), emptyList(), listOf("小雨")).isEmpty())
        assertTrue(Chat.myQuotedNames(listOf(mine("在吗", 300), theirs("小雨：在吗", 580)), emptyList(), listOf("小雨")).isEmpty())
    }

    @Test fun onceMyNameIsKnownMyQuotedWordsAreNeverTheirs() {
        // My message is long off screen: only the name gives the quote away.
        val screen = listOf(theirs("几点", 500), theirs("Ken：明天带水吗", 580), mine("八点", 800))
        assertEquals(listOf(screen[0], screen[2]), Chat.withoutQuotes(screen, listOf("小雨", "Ken")))
        // Without it the quote would be their message.
        assertEquals(screen, Chat.withoutQuotes(screen, listOf("小雨")))
        // Spacing and case are not the name.
        assertTrue(Chat.isQuote("ken ：明天带水吗", listOf("Ken")))
    }

    @Test fun aQuoteOfWhatIsKeptIsKnownByItsWords() {
        val kept = listOf("我" to "周末有空吗", "对方" to "有")
        val screen = listOf(theirs("那就周六", 500), theirs("Ken：周末有空吗", 580))
        assertEquals(listOf(screen[0]), Chat.withoutEchoes(screen, kept))
        assertEquals("我", Chat.echoed("Ken：周末有空…", emptyList(), kept))
        assertNull(Chat.echoed("Ken：去哪", emptyList(), kept))
    }

    @Test fun anEmojiNameOCRCannotReadIsStillAName() {
        // Nothing read before the colon: a quote.
        assertTrue(Chat.isQuote("：明天几点见"))
        assertTrue(Chat.isQuote(" : see you at eight"))
        // The emoji read as a letter or a mark: a quote in a chat whose name is an emoji.
        assertTrue(Chat.isQuote("O：明天几点见", emojiName = true))
        assertTrue(Chat.isQuote("@:明天几点见"))
        assertFalse("elsewhere a message", Chat.isQuote("O：明天几点见"))
        // An emoji name, where the app gives text.
        assertTrue(Chat.isQuote("🍵：明天几点见"))
    }

    @Test fun aQuotedPictureIsAQuote() {
        assertTrue(Chat.isQuote("小雨：[图片]"))
        assertTrue(Chat.isQuote("Ken：[动画表情]"))
        assertFalse(Chat.isQuote("[图片]"))
    }

    @Test fun colonsInMessagesStayMessages() {
        for (text in listOf("注意：带电脑", "10:30 见", "https://x.com/a", ":DD", "Q：你吃饭了吗", "地址：人民路 8 号", "PS: bring water"))
            assertFalse(text, Chat.isQuote(text, listOf("小雨", "Ken")))
    }

    @Test fun aFewCommonWordsSaidAgainAreNotAQuote() {
        // 「好的」 comes up again and again among what is kept: 「他说：好的」 is a message.
        val kept = listOf("对方" to "好的", "我" to "嗯")
        assertNull(Chat.echoed("他说：好的", emptyList(), kept))
        assertEquals(listOf("对方" to "好的", "我" to "他说：好的"), Archive.withoutQuotes(listOf("对方" to "好的", "我" to "他说：好的")))
        // Right above it on screen, it still is one.
        assertEquals("对方", Chat.echoed("小雨：好的", listOf("对方" to "好的")))
    }

    @Test fun quotesKeptAsMessagesByOlderVersionsGo() {
        val history = listOf(
            "我" to "周六去爬山吗",
            "对方" to "好呀",
            "对方" to "Ken：周六去爬山吗",     // their quote of my words, kept as theirs
            "我" to "小雨：好呀",              // my quote of theirs, kept as mine
            "对方" to "Ken：[图片]",
            "对方" to "注意：带水",
        )
        assertEquals(listOf(history[0], history[1], history[5]), Archive.withoutQuotes(history, listOf("小雨")))
    }

    @Test fun messengerRepliesToAMessageUnderALabel() {
        val win = Chat.Box(0, 0, 1080, 2400)
        val items = listOf(
            "Sam replied to you" to Chat.Box(60, 800, 520, 830),
            "Are we still on for Saturday?" to Chat.Box(60, 840, 700, 900),   // my message, quoted
            "Yes, 10am" to Chat.Box(60, 960, 420, 1010),                        // their reply
            "Great" to Chat.Box(700, 1100, 1020, 1150),
        )
        assertEquals(listOf(2, 3), Chat.messageIndices(items, win))
        // A reply to a photo: nothing quoted in words, the reply further down stays.
        val photo = listOf("You replied to Sam" to Chat.Box(560, 800, 1020, 830), "Love it" to Chat.Box(700, 1250, 1020, 1300))
        assertEquals(listOf(1), Chat.messageIndices(photo, win))
        // Said in a message, it is a message.
        val said = listOf("she finally replied to you" to Chat.Box(60, 800, 700, 860), "wow" to Chat.Box(60, 870, 300, 920))
        assertEquals(listOf(0, 1), Chat.messageIndices(said, win))
    }
}
