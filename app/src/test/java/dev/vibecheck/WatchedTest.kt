package dev.vibecheck

import org.junit.Assert.*
import org.junit.Test

/** Nothing is read in an app that is not watched, nor when a watched one is not what is on screen. */
class WatchedTest {

    // AccessibilityWindowInfo's window types, by value.
    private val TYPE_APPLICATION = 1
    private val TYPE_INPUT_METHOD = 2
    private val TYPE_SYSTEM = 3
    private val TYPE_ACCESSIBILITY_OVERLAY = 4

    private val screen = 1080L * 2400
    private val watched = setOf("com.tencent.mm")

    private fun w(type: Int, layer: Int, area: Long, app: String?) = Apps.Window(type, layer, area) { app }

    private val chat = w(TYPE_APPLICATION, 1, screen, "com.tencent.mm")

    @Test fun theWatchedChatInFrontIsRead() {
        assertTrue(Apps.inFront(listOf(chat), screen, watched))
        // The keyboard, our own card and a heads-up notification over it change nothing.
        val over = listOf(
            chat,
            w(TYPE_INPUT_METHOD, 5, screen / 3, "com.example.keyboard"),
            w(TYPE_ACCESSIBILITY_OVERLAY, 6, screen / 8, "dev.vibecheck"),
            w(TYPE_SYSTEM, 7, screen / 12, "com.android.systemui"),
        )
        assertTrue(Apps.inFront(over, screen, watched))
    }

    @Test fun anythingElseInFrontIsNot() {
        // An app that is not checked, opened over the chat.
        assertFalse(Apps.inFront(listOf(chat, w(TYPE_APPLICATION, 2, screen, "cn.soulapp.android")), screen, watched))
        // The notification shade pulled down.
        assertFalse(Apps.inFront(listOf(chat, w(TYPE_SYSTEM, 9, screen, "com.android.systemui")), screen, watched))
        // Nothing on screen at all, or only an app that is not checked.
        assertFalse(Apps.inFront(emptyList(), screen, watched))
        assertFalse(Apps.inFront(listOf(w(TYPE_APPLICATION, 1, screen, "cn.soulapp.android")), screen, watched))
        // Checked once, unchecked now.
        assertFalse(Apps.inFront(listOf(chat), screen, emptySet()))
    }

    @Test fun onlyTheWindowThatDecidesIsAskedWhoseItIs() {
        var asked = 0
        val chat = Apps.Window(TYPE_APPLICATION, 1, screen) { asked++; "com.tencent.mm" }
        val keyboard = Apps.Window(TYPE_INPUT_METHOD, 3, screen / 3) { asked++; "com.example.keyboard" }
        assertTrue(Apps.inFront(listOf(keyboard, chat), screen, watched))
        assertEquals("the keyboard is passed over without asking", 1, asked)
    }
}
