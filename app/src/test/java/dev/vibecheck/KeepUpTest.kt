package dev.vibecheck

import org.junit.Assert.*
import org.junit.Test

/** When a learned person's profile is written again by itself (Learning.keepUpDue). */
class KeepUpTest {

    private val day = 86_400_000L
    private val now = 100 * day

    private fun due(days: Int, writtenAt: Long, kept: Int, from: Int, triedAt: Long = 0L) =
        Learning.keepUpDue(days, writtenAt, kept, from, triedAt, now)

    @Test fun onceTheTimeHasPassedAndThereIsSomethingNew() {
        assertTrue(due(3, now - 3 * day, 530, 500))
        // Not yet three days.
        assertFalse(due(3, now - 2 * day, 530, 500))
        // Three days, but too little new to say anything.
        assertFalse(due(3, now - 10 * day, 520, 500))
        // Off: only when Learn is tapped.
        assertFalse(due(0, now - 100 * day, 5000, 500))
    }

    @Test fun aDailyUpdateKeepsToTheTimeOfDayYouChat() {
        // Yesterday's was written a little later in the evening than now.
        assertTrue(due(1, now - day + 2 * 3_600_000L, 560, 500))
        // But not twice in one evening.
        assertFalse(due(1, now - 5 * 3_600_000L, 560, 500))
    }

    @Test fun aWriteThatDidNotGetThroughWaitsBeforeTheNext() {
        assertFalse(due(1, now - 5 * day, 600, 500, triedAt = now - 3_600_000L))
        assertTrue(due(1, now - 5 * day, 600, 500, triedAt = now - 7 * 3_600_000L))
    }

    @Test fun neverWrittenIsDueOnceThereIsEnough() {
        assertTrue(due(7, 0L, 40, 0))
        assertFalse(due(7, 0L, 20, 0))
    }
}
