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

    @Test fun aNameLooksTheSameInLightAndDarkMode() {
        val day = Person.colourSignature(cup(light), 60, 40)!!
        val night = Person.colourSignature(cup(dark), 60, 40)!!
        assertTrue(Person.sameColours(day, night))
        // Brightness, which the older comparison used, is far apart.
        assertFalse(Person.samePicture(Person.signature(cup(light), 60, 40), Person.signature(cup(dark), 60, 40)))
    }

    @Test fun anotherNameDoesNot() {
        val tea = Person.colourSignature(cup(dark), 60, 40)!!
        assertFalse("another emoji", Person.sameColours(tea, Person.colourSignature(flower(dark), 60, 40)!!))
        assertFalse("the same emoji elsewhere", Person.sameColours(tea, Person.colourSignature(cup(dark, dx = 14), 60, 40)!!))
        // Grey text, a grey emoji, a notification: too little colour to compare at all.
        assertNull(Person.colourSignature(IntArray(60 * 40) { if (it % 7 == 0) 0xFF202020.toInt() else light }, 60, 40))
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
