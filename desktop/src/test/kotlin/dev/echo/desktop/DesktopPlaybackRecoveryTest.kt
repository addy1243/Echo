package dev.echo.desktop

import dev.echo.core.Track
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopPlaybackRecoveryTest {
    @Test fun latestDisplacedMoveSurvivesRestartAndPopKeepsPreviousEntry() {
        val dir = Files.createTempDirectory("echo-playback-recovery-stack")
        val file = dir.resolve("recovery.json").toFile()
        val track = Track("youtube:aaaaaaaaaaa", "Public recording", "Artist", durationMs = 120_000,
            provider = "youtube", sourceUri = "https://www.youtube.com/watch?v=aaaaaaaaaaa")
        try {
            DesktopPlaybackRecovery(file) { null }.apply {
                save("move:first", DesktopPlaybackState(listOf(track), index = 0))
                save("move:second", DesktopPlaybackState(listOf(track), index = 0))
            }
            val reopened = DesktopPlaybackRecovery(file) { null }
            assertEquals("second", reopened.latestMoveOperationId())
            reopened.remove("move:second")
            assertEquals("first", reopened.latestMoveOperationId())
            reopened.remove("move:first")
            assertEquals(null, reopened.latestMoveOperationId())
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun displacedQueuePersistsCursorDuplicatesAndNoLocalSourceUri() {
        val dir = Files.createTempDirectory("echo-playback-recovery")
        val file = dir.resolve("recovery.json").toFile()
        val audio = Files.createTempFile(dir, "local", ".wav").toFile()
        val local = Track("local:${audio.absolutePath}", "Private file", "Artist", durationMs = 45_000,
            sourceUri = audio.toURI().toString(), provider = "local")
        val youtube = Track("youtube:aaaaaaaaaaa", "Public recording", "Artist", durationMs = 120_000,
            provider = "youtube", sourceUri = "https://www.youtube.com/watch?v=aaaaaaaaaaa")
        try {
            val recovery = DesktopPlaybackRecovery(file) { key -> if (key == safeEngineRecordingId(local)) local else null }
            recovery.save("move:operation", DesktopPlaybackState(listOf(youtube, local, youtube), index = 1,
                positionMs = 17_500, durationMs = 45_000, repeatMode = DesktopRepeatMode.ALL))

            val serialized = file.readText()
            assertFalse(serialized.contains(audio.absolutePath))
            assertFalse(serialized.contains(audio.toURI().toString()))
            val restored = requireNotNull(recovery.restore("move:operation"))
            assertEquals(listOf(youtube.id, local.id, youtube.id), restored.queue.map { it.id })
            assertEquals(1, restored.index)
            assertEquals(17_500L, restored.positionMs)
            assertEquals(DesktopRepeatMode.ALL, restored.repeatMode)
            assertTrue(restored.queue[0].sourceUri?.startsWith("https://www.youtube.com/watch?v=") == true)
        } finally {
            audio.delete()
            dir.toFile().deleteRecursively()
        }
    }
}
