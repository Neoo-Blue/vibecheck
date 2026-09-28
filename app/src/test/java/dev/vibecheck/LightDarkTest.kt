package dev.vibecheck

import org.junit.Assert.*
import org.junit.Test

/** One person stays one person whether the phone is in light or in dark mode. */
class LightDarkTest {

    private val light = 0xFFEDEDED.toInt()
    private val dark = 0xFF111111.toInt()

    /** A name picture: a green cup with a white rim and a brown saucer, on a background. */
    private fun cup(bg: Int, w: Int = 60, h: Int = 40, dx: Int = 0) = IntArray(w * h) { i ->
        val x = i % w - dx
        val y = i / w
        when {
            y in 8..11 && x in 16..44 -> 0xFFFFFFFF.toInt()          // the white rim: grey, left out
            y in 12..27 && x in 18..42 -> 0xFF4CAF50.toInt()         // green tea
            y in 30..33 && x in 12..48 -> 0xFF8D5524.toInt()         // brown saucer
            else -> bg
        }
    }

    /** Another emoji: a pink flower, where the cup's tea was. */
    private fun flower(bg: Int, w: Int = 60, h: Int = 40) = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        if ((x - 30) * (x - 30) + (y - 20) * (y - 20) < 150) 0xFFF48FB1.toInt() else bg
    }

    /** A bear's face: brown, with ears, a light muzzle and black eyes and nose; [s] times the size. */
    private fun bear(bg: Int, w: Int = 60, h: Int = 40, s: Int = 1) = IntArray(w * s * h * s) { i ->
        val x = i % (w * s) / s
        val y = i / (w * s) / s
        fun inCircle(cx: Int, cy: Int, r: Int) = (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r
        when {
            inCircle(26, 19, 2) || inCircle(34, 19, 2) || inCircle(30, 25, 2) -> 0xFF101010.toInt()   // eyes, nose
            inCircle(30, 26, 5) -> 0xFFE8B98A.toInt()                                                 // muzzle
            inCircle(30, 22, 13) || inCircle(19, 10, 5) || inCircle(41, 10, 5) -> 0xFF8B5A2B.toInt() // face, ears
            else -> bg
        }
    }

    private fun print(px: IntArray, w: Int = 60, h: Int = 40) = Person.emojiPrint(px, w, h)!!

    @Test fun aNameLooksTheSameInLightAndDarkMode() {
        assertTrue(Person.sameEmoji(print(cup(light)), print(cup(dark))))
        assertTrue(Person.sameEmoji(print(bear(light)), print(bear(dark))))
        // Brightness, which the older comparison used, is far apart.
        assertFalse(Person.samePicture(Person.signature(cup(light), 60, 40), Person.signature(cup(dark), 60, 40)))
    }

    @Test fun whereThePictureIsCutAndHowBigDoNotMatter() {
        // Cut elsewhere: the white next to a light bar does not stand out, so the name's box differs.
        assertTrue(Person.sameEmoji(print(cup(light)), print(cup(dark, w = 80, h = 50, dx = 9), 80, 50)))
        // Twice the size.
        assertTrue(Person.sameEmoji(print(bear(dark)), print(bear(light, s = 2), 120, 80)))
    }

    @Test fun anotherNameDoesNot() {
        val tea = print(cup(dark))
        val bear = print(bear(dark))
        assertTrue("another emoji", Person.emojiDistance(tea, bear) > 3 * Person.EMOJI_SAME)
        assertFalse(Person.sameEmoji(tea, print(bear(light))))
        assertFalse("another emoji", Person.sameEmoji(tea, print(flower(dark))))
        assertFalse(Person.sameEmoji(bear, print(flower(light))))
        // Grey text, a grey emoji, a notification: too little colour to compare at all.
        assertNull(Person.emojiPrint(IntArray(60 * 40) { if (it % 7 == 0) 0xFF202020.toInt() else light }, 60, 40))
    }

    @Test fun withNoneOfTheirMessagesOnScreenThePictureDecides() {
        val kept = listOf("#v3tea" to print(cup(light)), "#v3bear" to print(bear(light)), "#v3rose" to print(flower(light)))
        assertEquals("#v3bear", Person.pictureOwner(print(bear(dark)), kept))
        assertEquals("#v3tea", Person.pictureOwner(print(cup(dark)), kept, current = "#v3bear"))
        // Two people with the same name picture: not guessed between, unless it is whose chat this was.
        val twice = kept + ("#v3tea2" to print(cup(dark)))
        assertNull(Person.pictureOwner(print(cup(dark)), twice))
        assertEquals("#v3tea2", Person.pictureOwner(print(cup(dark)), twice, current = "#v3tea2"))
        // Nobody's: nobody, never the nearest.
        assertNull(Person.pictureOwner(print(cup(dark)), kept.filter { it.first != "#v3tea" }))
        assertNull(Person.pictureOwner(print(cup(dark)), emptyList()))
    }

    @Test fun theAvatarIsKnownByTheColoursOfItsInside() {
        // The 16 reds, greens and blues of a 4 x 4 grid.
        val photo = IntArray(48) { 20 + (it * 37 + (it / 16) * 91) % 216 }
        val a = Person.avatarHash(photo)
        assertTrue(a.startsWith("#v3"))
        assertTrue(Person.isAvatarHash(a))
        assertFalse(Person.isInsideHash(a))
        assertTrue(Person.isFingerprint(a))
        assertEquals(0, Person.distance(a, a))
        // Read a few pixels off: every colour a little different, still the same avatar.
        val off = Person.avatarHash(IntArray(48) { (photo[it] + if (it % 2 == 0) 7 else -5).coerceIn(0, 255) })
        assertEquals(6, Person.distance(a, off))
        assertTrue(Person.distance(a, off) <= Person.AVATAR_SAME)
        // Another avatar: far apart.
        val other = Person.avatarHash(IntArray(48) { 255 - photo[it] })
        assertTrue(Person.distance(a, other) > 5 * Person.AVATAR_SAME)
        // Never compared with the older kinds, nor the older kinds with it.
        assertEquals(Int.MAX_VALUE, Person.distance(a, "#v2a5f1"))
        assertEquals(Int.MAX_VALUE, Person.distance("#a5f1", a))
        assertEquals(Int.MAX_VALUE, Person.hamming(a, a))
        assertEquals(1, Person.distance("#a5f1", "#a5f0"))
        // A wall of one colour is nobody's face; nor is something that is not a grid of colours.
        assertEquals("", Person.avatarHash(IntArray(48) { 120 }))
        assertEquals("", Person.avatarHash(IntArray(16) { it * 10 }))
        assertEquals(Int.MAX_VALUE, Person.distance(a, "#v3zz"))
    }

    @Test fun theInsideOfAnAvatarIsNeverTakenForAnOlderFingerprint() {
        val samples = intArrayOf(10, 200, 30, 180, 40, 170, 90, 20, 250, 60, 120, 110, 30, 230, 70, 140)
        val inside = Person.insideHash(samples)
        val older = Person.hashOf(samples)
        assertTrue(inside.startsWith("#v2"))
        assertTrue(Person.isFingerprint(inside))
        assertTrue(Person.isInsideHash(inside))
        assertFalse(Person.isInsideHash(older))
        assertEquals(older.removePrefix("#"), inside.removePrefix("#v2"))
        assertEquals("the same bits, but not the same kind", Int.MAX_VALUE, Person.hamming(inside, older))
        assertEquals(0, Person.hamming(inside, inside))
        val wobble = Person.insideHash(samples.copyOf().also { it[1] = 25 })
        assertTrue(Person.hamming(inside, wobble) in 1..3)
        assertEquals("flat: no fingerprint", "", Person.insideHash(IntArray(16) { 128 }))
    }

    @Test fun theRecordWithMoreKnownIsKept() {
        val learned = Person.Known("#a1", learned = 7400, seen = 900)
        val darkOnly = Person.Known("#v2b2", learned = 0, seen = 66)
        assertSame(learned, Person.better(learned, darkOnly))
        assertSame(learned, Person.better(darkOnly, learned))
        // Nothing learned on either side: the one seen more.
        val few = Person.Known("#c3", 0, 12)
        assertSame(darkOnly, Person.better(few, darkOnly))
        // Both read: the longer read.
        assertSame(learned, Person.better(Person.Known("#d4", 300, 5000), learned))
    }
}
