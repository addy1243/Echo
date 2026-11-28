package dev.echo.desktop

import dev.echo.core.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StageBufferTrackerTest {
    @Test fun trackerUsesFiveQueueOffsetsAndKeepsMissingOccurrencesEmpty() {
        val current = position(0)
        val ahead = position(2)

        assertEquals(listOf(null, null, current, null, ahead), stageBufferTrackerSlots(StageBufferStatus(listOf(ahead, current))))
        assertEquals(listOf(null, null, null, null, null), stageBufferTrackerSlots(StageBufferStatus(emptyList())))
        assertEquals("2 back", stageBufferOffsetLabel(-2))
        assertEquals("Now", stageBufferOffsetLabel(0))
        assertEquals("2 ahead", stageBufferOffsetLabel(2))
    }

    @Test fun transferProgressUsesKnownDownloadBytesOnlyAndClamps() {
        assertEquals(.25f, stageBufferTransferProgress(StageBufferTransfer(StageBufferTransferPhase.DOWNLOADING, 25, 100)))
        assertEquals(1f, stageBufferTransferProgress(StageBufferTransfer(StageBufferTransferPhase.DOWNLOADING, 200, 100)))
        assertNull(stageBufferTransferProgress(StageBufferTransfer(StageBufferTransferPhase.DOWNLOADING, 25, null)))
        assertNull(stageBufferTransferProgress(StageBufferTransfer(StageBufferTransferPhase.READY, 100, 100)))
        assertEquals("Cached", stageBufferTransferShortLabel(StageBufferTransfer(StageBufferTransferPhase.READY)))
    }

    @Test fun onlyActivelyPreparingOrDownloadingRowsAnimate() {
        val settled = position(0)
        assertEquals(false, stageBufferPositionIsActive(settled))
        assertEquals(true, stageBufferPositionIsActive(settled.copy(
            video = StageBufferTransfer(StageBufferTransferPhase.DOWNLOADING, 1, 10),
        )))
        assertEquals(true, stageBufferPositionIsActive(position(2).copy(
            audio = StageBufferTransfer(StageBufferTransferPhase.PREPARING),
        )))
        assertEquals(false, stageBufferPositionIsActive(settled.copy(
            video = StageBufferTransfer(StageBufferTransferPhase.WAITING),
        )))
        assertEquals(false, stageBufferPositionIsActive(settled.copy(
            audio = StageBufferTransfer(StageBufferTransferPhase.FAILED),
            video = StageBufferTransfer(StageBufferTransferPhase.UNAVAILABLE),
        )))
    }

    @Test fun completedAccentRequiresBothAudioAndVideoCached() {
        val cached = position(1).copy(video = StageBufferTransfer(StageBufferTransferPhase.READY))
        assertEquals(true, stageBufferPositionIsComplete(cached))
        assertEquals(false, stageBufferPositionIsComplete(cached.copy(video = StageBufferTransfer(StageBufferTransferPhase.IDLE))))
        assertEquals(false, stageBufferPositionIsComplete(cached.copy(audio = StageBufferTransfer(StageBufferTransferPhase.FAILED))))
    }

    private fun position(offset: Int) = StageBufferPosition(offset, Track("id$offset", "Track $offset", "Artist"),
        StageBufferTransfer(StageBufferTransferPhase.READY), StageBufferTransfer(StageBufferTransferPhase.UNAVAILABLE))
}
