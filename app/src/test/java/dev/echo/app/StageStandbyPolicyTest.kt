package dev.echo.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StageStandbyPolicyTest {
    @Test fun autoStandbyRequiresOptInForegroundAndFullIdleTimeout() {
        val settings = StageStandbySettings(automaticEnabled = true, idleTimeoutMs = 10_000)
        assertFalse(shouldEnterStageStandby(settings, 10_000, appForeground = false, charging = true))
        assertFalse(shouldEnterStageStandby(settings, 9_999, appForeground = true, charging = true))
        assertTrue(shouldEnterStageStandby(settings, 10_000, appForeground = true, charging = false))
        assertFalse(shouldEnterStageStandby(StageStandbySettings(), 99_000, appForeground = true, charging = true))
    }

    @Test fun chargingOnlyFailsClosedOnUnknownAndRequiresCharging() {
        val settings = StageStandbySettings(automaticEnabled = true, chargingOnly = true, idleTimeoutMs = 1)
        assertFalse(shouldEnterStageStandby(settings, 1, appForeground = true, charging = null))
        assertFalse(shouldEnterStageStandby(settings, 1, appForeground = true, charging = false))
        assertTrue(shouldEnterStageStandby(settings, 1, appForeground = true, charging = true))
    }

    @Test fun idleClockResetsOnInputAndForegroundTransitions() {
        val clock = StageStandbyIdleClock(startedAtMs = 100)
        val settings = StageStandbySettings(automaticEnabled = true, idleTimeoutMs = 50)
        clock.setForeground(true, 110)
        assertFalse(clock.shouldEnter(settings, 159, charging = true))
        assertTrue(clock.shouldEnter(settings, 160, charging = true))
        clock.recordInteraction(170)
        assertFalse(clock.shouldEnter(settings, 219, charging = true))
        clock.setForeground(false, 220)
        clock.setForeground(true, 500)
        assertFalse(clock.shouldEnter(settings, 549, charging = true))
    }
}
