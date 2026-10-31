package dev.echo.desktop

import dev.echo.core.PlayableSource
import dev.echo.core.Track
import dev.echo.core.connected.RepeatModeV2
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DesktopPlaybackTest {
    @Test fun playNextPreservesCurrentPositionDuplicatesAndWrappedUpcoming() {
        val a = song("aaaaaaaaaaa"); val b = song("bbbbbbbbbbb"); val c = song("ccccccccccc")
        val state = DesktopPlaybackState(listOf(a, b, a), index = 1, repeatMode = DesktopRepeatMode.ALL,
            positionMs = 1234, occurrence = 9, queueRevision = 2)
        val changed = appendedDesktopQueue(state, listOf(c, c), next = true)!!
        assertEquals(listOf(b, c, c, a, a), changed.queue)
        assertEquals(1234L, changed.positionMs)
        assertEquals(9L, changed.occurrence)
        assertEquals(3L, changed.queueRevision)
        assertEquals(listOf(b, a, a, c, c), appendedDesktopQueue(state, listOf(c, c), next = false)!!.queue)
    }
    @Test fun validatesNativeAudioTrustBoundary() {
        assertTrue(validDesktopYouTubeSource(PlayableSource("https://r1.googlevideo.com/videoplayback", mapOf("User-Agent" to "Echo"))))
        listOf("http://r1.googlevideo.com/audio", "https://googlevideo.com.evil.test/audio", "https://user@r1.googlevideo.com/audio", "https://www.youtube.com/watch?v=x", "https://r1.googlevideo.com:444/audio").forEach {
            assertFalse(validDesktopYouTubeSource(PlayableSource(it)))
        }
        assertFalse(validDesktopYouTubeSource(PlayableSource("https://r1.googlevideo.com/audio", mapOf("Authorization" to "secret"))))
        assertFalse(validDesktopYouTubeSource(PlayableSource("https://r1.googlevideo.com/audio", mapOf("User-Agent" to "Echo\r\nX: bad"))))
    }
    @Test fun localFilesMustBeReadableAndCannotBeAndroidUrisOrNetworkShares() {
        val file = Files.createTempFile("echo-local-test", ".wav").toFile()
        try {
            assertEquals(file, localAudioFile(Track("local:test", "Local", "Test", sourceUri = file.toURI().toString())))
            listOf("content://media/external/audio/1", "https://example.com/audio.wav", "file://server/share/audio.wav", file.toURI().toString() + ".missing").forEach { uri ->
                assertTrue(runCatching { localAudioFile(Track("local:test", "Local", "Test", sourceUri = uri)) }.isFailure)
            }
        } finally { file.delete() }
    }
    @Test fun queueStatePreservesDuplicateOccurrences() {
        val track = Track("youtube:12345678901", "Song", "Artist")
        val state = DesktopPlaybackState(queue = listOf(track, track), index = 1, occurrence = 2)
        assertEquals(track, state.current)
        assertEquals(2, state.queue.size)
        assertEquals(1, state.index)
    }
    private fun song(id: String) = Track("youtube:$id", id, "Artist", provider = "youtube", sourceUri = "https://www.youtube.com/watch?v=$id")
    @Test fun smartQueueRetainsPausedCurrentPositionAndDuplicateOccurrences() {
        val current = song("aaaaaaaaaaa")
        val state = DesktopPlaybackState(listOf(current, song("bbbbbbbbbbb"), current), index = 0,
            positionMs = 12_345, durationMs = 90_000, occurrence = 4, queueRevision = 8)
        val mixed = listOf(current, current, song("ccccccccccc"))
        val changed = retainedDesktopQueue(state, mixed, 4, current.id, 8)!!
        assertEquals(mixed, changed.queue)
        assertEquals(12_345L, changed.positionMs)
        assertFalse(changed.playing)
        assertEquals(4L, changed.occurrence)
        assertEquals(9L, changed.queueRevision)
    }
    @Test fun smartQueueRejectsChangedPlaybackQueueAndEndedAnchors() {
        val current = song("aaaaaaaaaaa")
        val state = DesktopPlaybackState(listOf(current), index = 0, occurrence = 4, queueRevision = 8)
        assertNull(retainedDesktopQueue(state, listOf(current), 3, current.id, 8))
        assertNull(retainedDesktopQueue(state, listOf(current), 4, current.id, 7))
        assertNull(retainedDesktopQueue(state.copy(ended = true), listOf(current), 4, current.id, 8))
        assertNull(retainedDesktopQueue(state.copy(loading = true), listOf(current), 4, current.id, 8))
        assertNull(retainedDesktopQueue(state, listOf(song("bbbbbbbbbbb")), 4, current.id, 8))
        assertNotNull(retainedDesktopQueue(state.copy(ended = true), listOf(current), 4, null, 8))
    }
    @Test fun repeatAllUpcomingWrapIncludesEveryOtherOccurrenceInOrder() {
        val a = song("aaaaaaaaaaa"); val b = song("bbbbbbbbbbb"); val c = song("ccccccccccc")
        val state = DesktopPlaybackState(listOf(a, b, a, c), index = 2, repeatMode = DesktopRepeatMode.ALL)
        assertEquals(listOf(c, a, b), upcomingDesktopTracks(state))
        assertEquals(listOf(c), upcomingDesktopTracks(state.copy(repeatMode = DesktopRepeatMode.OFF)))
    }
    @Test fun transferCaptureUsesCurrentFirstRepeatWrapAndDistinctDuplicateOccurrences() {
        val a = song("aaaaaaaaaaa"); val b = song("bbbbbbbbbbb")
        val state = DesktopPlaybackState(listOf(a, b, a), index = 1, repeatMode = DesktopRepeatMode.ALL)
        val transfer = transferQueueForDesktop(state)
        assertEquals(listOf(b.id, a.id, a.id), transfer.entries.map { it.recording.id })
        assertEquals(RepeatModeV2.ALL, transfer.repeatMode)
        assertEquals(3, transfer.entries.map { it.id }.toSet().size)
    }
    @Test fun transferCaptureRejectsTheSpecificUnportableQueuePosition() {
        val youtube = song("aaaaaaaaaaa")
        val local = Track("local:secret", "Private recording", "Artist", durationMs = 10_000,
            sourceUri = "file:///private/song.wav", provider = "local")
        val error = runCatching { transferQueueForDesktop(DesktopPlaybackState(listOf(youtube, local), index = 0)) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("Queue position 2 (Private recording)"))
    }
    @Test fun queueEditsGuardCurrentAndStaleDuplicateRows() {
        val a = song("aaaaaaaaaaa"); val b = song("bbbbbbbbbbb"); val c = song("ccccccccccc")
        val state = DesktopPlaybackState(listOf(a, b, b, c), index = 0, occurrence = 4, queueRevision = 8)
        assertEquals(listOf(a, b, c), editDesktopUpcoming(state, 2, null, 4, 8)!!.queue)
        assertEquals(listOf(a, b, c, b), editDesktopUpcoming(state, 1, 3, 4, 8)!!.queue)
        assertNull(editDesktopUpcoming(state, 0, 1, 4, 8))
        assertNull(editDesktopUpcoming(state, 1, 0, 4, 8))
        assertNull(editDesktopUpcoming(state, 1, 2, 5, 8))
        assertNull(editDesktopUpcoming(state, 1, 2, 4, 9))
    }
    @Test fun repeatAllEditsWrappedPhysicalOccurrencesWithoutRestartingCurrent() {
        val firstA = song("aaaaaaaaaaa"); val b = song("bbbbbbbbbbb")
        val currentA = firstA.copy(); val c = song("ccccccccccc")
        val state = DesktopPlaybackState(listOf(firstA, b, currentA, c), index = 2, occurrence = 4,
            queueRevision = 8, repeatMode = DesktopRepeatMode.ALL, positionMs = 12_345, durationMs = 90_000)
        val removed = editDesktopUpcoming(state, 0, null, 4, 8)!!
        assertEquals(listOf(currentA, c, b), removed.queue)
        assertSame(currentA, removed.current)
        assertEquals(0, removed.index)
        val moved = editDesktopUpcoming(state, 0, 1, 4, 8)!!
        assertEquals(listOf(currentA, c, b, firstA), moved.queue)
        assertSame(currentA, moved.current)
        assertSame(firstA, moved.queue.last())
        assertEquals(4L, moved.occurrence)
        assertEquals(12_345L, moved.positionMs)
        assertFalse(moved.playing)
        assertEquals(9L, moved.queueRevision)
        assertNull(editDesktopUpcoming(state, 2, 0, 4, 8))
        assertNull(editDesktopUpcoming(state, 0, 2, 4, 8))
        assertNull(editDesktopUpcoming(state, 0, 1, 4, 7))
        assertNull(editDesktopUpcoming(state, 0, 1, 3, 8))
    }
}
