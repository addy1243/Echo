package dev.echo.desktop

import dev.echo.core.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class StageTransferPacingTest {
    private val playback = DesktopPlaybackState(
        queue = listOf(Track("youtube:abcdefghijk", "Song", "Artist", provider = "youtube")), index = 0,
    )

    @Test fun fastPacingRequiresPausedIntentOrEveryActiveSourceToBeLocal() {
        fun fast(state: DesktopPlaybackState = playback, playing: Boolean = true, audio: Boolean = false,
            video: Boolean = false, slave: Boolean = false) = stageCanPrepareFast(state, playing, audio, video, slave)
        assertTrue(fast(playing = false))
        assertFalse(fast()) // Buffering/paused native state still has play intent.
        assertTrue(fast(audio = true))
        assertFalse(fast(playback.copy(loading = true), audio = true))
        assertFalse(fast(playback.copy(videoLoading = true), audio = true, video = true))
        assertFalse(fast(DesktopPlaybackState(), audio = true))
        assertFalse(fast(playback.copy(error = "failed"), audio = true))
        val videoPlayback = playback.copy(videoMode = true)
        assertFalse(fast(videoPlayback, audio = true))
        assertTrue(fast(videoPlayback, video = true)) // Muxed local video does not use the network audio relay.
        assertFalse(fast(videoPlayback, video = true, slave = true))
        assertTrue(fast(videoPlayback, audio = true, video = true, slave = true))
        assertFalse(fast(videoPlayback, audio = true, video = false, slave = true))
    }

    @Test fun idleTimeAndOversleptWaitsCannotBankTransferCredit() = runBlocking {
        val gate = StageMediaTransferGate()
        var now = 0L
        var waited = 0L
        suspend fun chunk() = gate.paceTransfer(64 * 1024, 128L * 1024, { now }, { nanos ->
            waited += nanos
            now += nanos + 1_000_000_000L
        }) { true }
        chunk()
        assertEquals(500_000_000L, waited)
        now += 60_000_000_000L
        chunk()
        assertEquals(1_000_000_000L, waited)
    }

    @Test fun inFlightPacingWaitAdjustsBothDirectionsWithoutRepricingEarlierBytes() = runBlocking {
        for (initiallyFast in listOf(false, true)) {
            val gate = StageMediaTransferGate().apply { setFastTransfersAllowed(initiallyFast) }
            var now = 0L
            var firstWait = true
            gate.paceTransfer(64 * 1024, 128L * 1024, { now }, { nanos ->
                now += nanos
                if (firstWait) { firstWait = false; gate.setFastTransfersAllowed(!initiallyFast) }
            }) { true }
            assertEquals(if (initiallyFast) 125_000_000L else 54_687_500L, now)
        }
    }

    @Test fun bufferingOverridesFastRateWithoutBankingWaitCredit() = runBlocking {
        val gate = StageMediaTransferGate().apply { setFastTransfersAllowed(true); setForegroundBuffering(true) }
        var now = 60_000_000_000L
        var waited = 0L
        val started = CompletableDeferred<Unit>()
        val transfer = async {
            started.complete(Unit)
            gate.paceTransfer(64 * 1024, 128L * 1024, { now }, { nanos -> waited += nanos; now += nanos }) { true }
        }
        started.await()
        assertFalse(transfer.isCompleted)
        assertEquals(0L, waited)
        gate.setForegroundBuffering(false)
        withTimeout(1_000) { transfer.await() }
        assertEquals(31_250_000L, waited)
    }

    @Test fun upstreamBlockersOverrideFreshFastEligibilityUntilEveryRelayDrains() = runBlocking {
        val gate = StageMediaTransferGate()
        val first = Any()
        val second = Any()
        suspend fun elapsed(): Long {
            var now = 0L
            gate.paceTransfer(64 * 1024, 128L * 1024, { now }, { now += it }) { true }
            return now
        }
        gate.setUpstreamRelayActive(first, true)
        gate.setUpstreamRelayActive(second, true)
        gate.setFastTransfersAllowed(true)
        assertEquals(500_000_000L, elapsed())
        gate.setUpstreamRelayActive(first, false)
        gate.setUpstreamRelayActive(first, false) // A stale close cannot release another source's blocker.
        assertEquals(500_000_000L, elapsed())
        gate.setUpstreamRelayActive(second, false)
        assertEquals(31_250_000L, elapsed())
    }
}
