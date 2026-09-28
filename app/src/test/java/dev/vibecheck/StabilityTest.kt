package dev.vibecheck

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The card's title keeps its name picture the right shape, and a worker that throws is not the end. */
class StabilityTest {

    private val bg = 0xFFEDEDED.toInt()
    private val tea = 0xFF4CAF50.toInt()

    /** The title-bar strip: [w]x[h] of the bar's colour. */
    private fun strip(w: Int, h: Int) = IntArray(w * h) { bg }

    private fun fill(px: IntArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        for (y in y0..y1) for (x in x0..x1) px[y * w + x] = color
    }

    /** A photo: neighbouring pixels unlike each other and the bar. */
    private fun photo(px: IntArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int) {
        for (y in y0..y1) for (x in x0..x1) px[y * w + x] = if ((x + y) % 2 == 0) 0xFF8B1A1A.toInt() else 0xFF30302E.toInt()
    }

    @Test fun thePhotoUnderTheTitleBarIsNotTheName() {
        // 5:04 in the 🍵 chat: grapes right under the title bar reach into the bottom of the strip.
        val w = 200
        val h = 40
        val px = strip(w, h)
        fill(px, w, 94, 12, 106, 25, tea)                // the emoji at the middle
        photo(px, w, 0, 34, 80, 39)                        // a photo's top edge along the bottom
        assertArrayEquals(intArrayOf(94, 12, 106, 25), Person.nameBox(px, w, h))
    }

    @Test fun aShapeCutByTheStripsEdgeIsNotAName() {
        val w = 200
        val h = 40
        // Only a photo, across the middle and off the bottom edge: no name at all.
        val px = strip(w, h).also { photo(it, w, 20, 30, 180, 39) }
        assertNull(Person.nameBox(px, w, h))
        // Something in the status bar reaching down into the top of the strip.
        val clock = strip(w, h).also { fill(it, w, 90, 0, 110, 8, 0xFF000000.toInt()) }
        assertNull(Person.nameBox(clock, w, h))
        // Off to one side, nothing at the middle: not the name either.
        val side = strip(w, h).also { fill(it, w, 10, 10, 30, 25, tea) }
        assertNull(Person.nameBox(side, w, h))
    }

    @Test fun twoEmojiSideBySideAreOneName() {
        val w = 200
        val h = 40
        val px = strip(w, h)
        fill(px, w, 86, 12, 98, 25, tea)
        fill(px, w, 100, 12, 112, 25, 0xFFE91E63.toInt())
        assertArrayEquals(intArrayOf(86, 12, 112, 25), Person.nameBox(px, w, h))
    }

    @Test fun onlyNameShapedPicturesAreKept() {
        assertTrue(Person.plausiblePicture(64, 64))
        assertTrue(Person.plausiblePicture(200, 40))         // a few emoji
        assertFalse(Person.plausiblePicture(640, 20))        // a strip of a photo
        assertFalse(Person.plausiblePicture(300, 40))        // wider than six heights
        assertFalse(Person.plausiblePicture(40, 8))          // too thin to be anything
    }

    @Test fun aWidePictureNeverTakesTheWholeTitle() {
        assertEquals(40 to 40, Person.pictureSize(64, 64, 40))
        assertEquals(100 to 40, Person.pictureSize(160, 64, 40))
        // Twelve times as wide as tall: drawn at most four heights wide, and shorter.
        val (w, h) = Person.pictureSize(768, 64, 40)
        assertEquals(160, w)
        assertTrue(h in 1..14)
    }

    @Test fun theCallRecordWithItsIconIsNotAMessage() {
        // What OCR made of WeChat's "Canceled" call record with its phone icon, learned as a running joke.
        assertTrue(Chat.isNotification("Canceled O"))
        assertTrue(Chat.isNotification("O Canceled"))
    }

    @Test fun aWorkerThatThrowsIsLoggedAndTheNextTaskRuns() {
        val pool = Executors.newSingleThreadExecutor(Crash.threads("vibecheck-test"))
        pool.execute { throw IllegalStateException("boom in a worker") }
        var ran = false
        pool.submit { ran = true }.get(5, TimeUnit.SECONDS)
        pool.shutdown()
        assertTrue(ran)
        // The dying thread reports on its way out, which can come just after the next task ran.
        val until = System.currentTimeMillis() + 5_000
        while (!Diag.lastError.contains("boom in a worker") && System.currentTimeMillis() < until) Thread.sleep(10)
        assertTrue(Diag.lastError, Diag.lastError.contains("boom in a worker"))
    }

    @Test fun guardCatchesAndCarriesOn() {
        var after = false
        Crash.guard("test") { throw IllegalArgumentException("bad input") }
        after = true
        assertTrue(after)
        assertTrue(Diag.lastError.contains("bad input"))
    }
}
