package dev.echo.playback

import org.junit.Assert.*
import org.junit.Test

class SleepTimerControllerTest {
    @Test fun expiresOnceAgainstMonotonicDeadlineEvenWhenTickIsLate() {
        var elapsed = 10_000L
        val timer = SleepTimerController { elapsed }
        timer.start(15)
        elapsed += 300_000
        assertFalse(timer.tick())
        assertEquals(600_000L, timer.remainingMs.value)
        elapsed += 700_000
        assertTrue(timer.tick())
        assertNull(timer.remainingMs.value)
        assertFalse(timer.tick())
    }

    @Test fun replacingAndCancellingNeverLeaveAnOldDeadlineArmed() {
        var elapsed = 0L
        val timer = SleepTimerController { elapsed }
        timer.start(15)
        elapsed = 800_000
        timer.start(30)
        elapsed = 1_000_000
        assertFalse(timer.tick())
        assertEquals(1_600_000L, timer.remainingMs.value)
        timer.cancel()
        elapsed = 9_000_000
        assertFalse(timer.tick())
        assertNull(timer.remainingMs.value)
    }

    @Test fun invalidChoiceDoesNotCancelAnExistingTimer() {
        val timer = SleepTimerController { 100L }
        timer.start(45)
        assertTrue(runCatching { timer.start(-1) }.isFailure)
        assertTrue(runCatching { timer.start(0) }.isFailure)
        assertTrue(runCatching { timer.start(120) }.isFailure)
        assertEquals(2_700_000L, timer.remainingMs.value)
    }
}
