package dev.vibecheck

import org.junit.Assert.*
import org.junit.Test

/** What is in a title bar that is not a person: screens, buttons, times; and names that are short. */
class LabelTest {

    private val win = Chat.Box(0, 0, 1440, 3120)
    private fun at(text: String, left: Int, right: Int, top: Int = 200, bottom: Int = 260) = text to Chat.Box(left, top, right, bottom)

    @Test fun screensButtonsAndTimesAreNotNames() {
        for (s in listOf("Moments", "朋友圈", "Official Account", "公众号", "相机胶卷", "相机胶卷 ▾", "Camera Roll", "Timer (5 m)",
                "关注", "+关注", "1分钟前", "3 小时前", "刚刚在线", "当前在线", "近期互动", "5 min ago", "just now")) {
            assertFalse(s, Person.looksLikeName(s))
            assertFalse(s, Person.looksLikeSymbolName(s))
            assertTrue(s, Person.isLabel(s))
        }
        for (s in listOf("张三", "Ken", "anna", "刚刚", "Mia (work)", "Timer Chen", "小雨🌙")) {
            assertTrue(s, Person.looksLikeName(s))
            assertFalse(s, Person.isLabel(s))
        }
    }

    @Test fun aNameOfMarksIsNotOutrankedByTheFollowButton() {
        val titles = listOf(at("~_~", 560, 760), at("关注", 1180, 1320))
        assertEquals("~_~", Person.peerName(titles, emptyList(), win, symbols = true))
        // Nor by when they were last there, under it.
        assertEquals("...", Person.peerName(listOf(at("...", 640, 800), at("1分钟前", 600, 840, 270, 320)), emptyList(), win, symbols = true))
    }

    @Test fun aScreenTitledAsTheAppsOwnIsNotAChat() {
        assertTrue(Person.notAChat(listOf(at("Moments", 560, 880))))
        assertTrue(Person.notAChat(listOf(at("相机胶卷 ▾", 560, 880))))
        assertTrue(Person.notAChat(listOf(at("Official Accounts", 480, 960))))
        // A chat with a follow button beside the name is a chat.
        assertFalse(Person.notAChat(listOf(at("张三", 600, 840), at("关注", 1180, 1320))))
        assertFalse(Person.notAChat(emptyList()))
    }

    @Test fun oneLetterInTheMiddleOfTheBarMayBeTheName() {
        assertEquals("J", Person.oneLetterName(listOf(at("J", 700, 740)), win))
        assertEquals("雪", Person.oneLetterName(listOf(at("雪", 690, 750)), win))
        // Off to the side: a button or a stray mark.
        assertNull(Person.oneLetterName(listOf(at("J", 60, 100)), win))
        // A label, not a letter, or more than one of them.
        assertNull(Person.oneLetterName(listOf(at("我", 690, 750)), win))
        assertNull(Person.oneLetterName(listOf(at("?", 700, 740)), win))
        assertNull(Person.oneLetterName(listOf(at("J", 700, 740), at("K", 690, 750, 270, 320)), win))
    }

    /** A made-up glyph drawn in [ink] on [paper]: the shape of an "m" (three stems and a top), or a corner. */
    private fun glyph(ink: Int, paper: Int, m: Boolean, w: Int = 60, h: Int = 40, shift: Int = 0): IntArray {
        val px = IntArray(w * h) { paper }
        fun fill(x0: Int, y0: Int, x1: Int, y1: Int) {
            for (y in y0 until y1) for (x in x0 until x1) px[y * w + x + shift] = ink
        }
        if (m) {
            fill(8, 8, 50, 13)
            fill(8, 8, 13, 34); fill(27, 8, 32, 34); fill(45, 8, 50, 34)
        } else {
            fill(20, 4, 25, 36); fill(20, 31, 40, 36)
        }
        return px
    }

    @Test fun aNameIsKnownByItsShapeInDarkModeAndLight() {
        val light = Person.inkSignature(glyph(0x222222, 0xEDEDED, m = true), 60, 40)!!
        val dark = Person.inkSignature(glyph(0xE0E0E0, 0x191919, m = true), 60, 40)!!
        assertTrue(Person.sameInk(light, dark))
        // Where it sits in the picture does not matter.
        assertTrue(Person.sameInk(light, Person.inkSignature(glyph(0xE0E0E0, 0x191919, m = true, shift = 4), 60, 40)!!))
        // Another shape does.
        assertFalse(Person.sameInk(light, Person.inkSignature(glyph(0x222222, 0xEDEDED, m = false), 60, 40)!!))
        // Nothing drawn: nothing to go on.
        assertNull(Person.inkSignature(IntArray(60 * 40) { 0xEDEDED }, 60, 40))
    }
}
