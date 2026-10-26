package dev.echo.app

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.echo.core.Track
import dev.echo.data.library.LibraryRepository
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SearchQueueBehaviorTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun searchTapPreservesUpcomingAndPlaylistAppendPreservesCurrentInBothShuffleModes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = "queue-actions-${UUID.randomUUID()}"
        val audio = File(context.filesDir, "$fixture.wav")
        val samples = 8_000 * 120
        val wav = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(samples * 2)
        audio.writeBytes(wav.array())
        val songs = (1..5).map { Track("$fixture-$it", if (it == 4) "Selected $fixture" else "Song $it $fixture", fixture,
            durationMs = 120_000, sourceUri = Uri.fromFile(audio).toString()) }
        val repository = LibraryRepository(context)
        val playlistId = "$fixture-playlist"
        runBlocking { repository.upsertTracks(songs); repository.importPlaylist(playlistId, "Append $fixture", listOf(songs[4], songs[4], songs[1])) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: EchoViewModel
                scenario.onActivity { model = ViewModelProvider(it)[EchoViewModel::class.java] }
                fun observe(): QueueObservation {
                    var observed: QueueObservation? = null
                    scenario.onActivity {
                        val player = requireNotNull(model.controller)
                        observed = QueueObservation(queueSnapshot(player), player.currentMediaItem?.mediaId, player.currentPosition, player.isPlaying)
                    }
                    return requireNotNull(observed)
                }
                try {
                    compose.waitUntil(15_000) { model.controller != null }
                    scenario.onActivity {
                        model.controller!!.repeatMode = Player.REPEAT_MODE_OFF
                        model.play(listOf(songs[0], songs[1], songs[0], songs[2]), 0, true)
                    }
                    compose.waitUntil(20_000) { !model.busy && observe().let { it.playing && it.entries.size == 4 } }
                    assertEquals(listOf(songs[0].id, songs[0].id, songs[1].id, songs[2].id).sorted(), observe().ids().sorted())
                    compose.onNodeWithTag("tab-search").performClick()
                    compose.onNodeWithTag("search-source-0").performClick()
                    compose.onNodeWithText("Songs, artists or albums").performTextInput(fixture)
                    for (shuffle in listOf(false, true)) {
                        scenario.onActivity { model.controller!!.repeatMode = Player.REPEAT_MODE_ALL; model.play(listOf(songs[0], songs[1], songs[0], songs[2]), 0, shuffle) }
                        compose.waitUntil(20_000) { !model.busy && observe().let { it.entries.size == 4 && it.playing && it.currentId != songs[3].id } }
                        val expectedNext = observe().ids().drop(1)
                        compose.onNodeWithText(songs[3].title).performScrollTo().performClick()
                        compose.waitUntil(15_000) { !model.busy && observe().let { it.currentId == songs[3].id && it.playing } }
                        val selected = observe()
                        assertEquals(4, selected.entries.size)
                        assertEquals(expectedNext, selected.ids().drop(1))
                        scenario.onActivity { model.controller!!.seekToNextMediaItem() }
                        compose.waitUntil(10_000) { observe().currentId == expectedNext.first() }

                        val before = observe()
                        val additions = listOf(songs[4], songs[4], songs[1])
                        scenario.onActivity { model.appendPlaylist(additions, QueueAddMode.NORMAL) }
                        compose.waitUntil(15_000) { !model.busy && observe().entries.size == 7 }
                        val after = observe()
                        assertEquals(before.currentId, after.currentId)
                        assertTrue(after.position >= before.position)
                        assertEquals(before.ids() + additions.map { it.id }, after.ids())
                        val beforeShuffleAppend = observe()
                        scenario.onActivity { model.appendPlaylist(additions, QueueAddMode.SHUFFLE) }
                        compose.waitUntil(15_000) { !model.busy && observe().entries.size == 10 }
                        val afterShuffleAppend = observe()
                        assertEquals(beforeShuffleAppend.ids(), afterShuffleAppend.ids().take(7))
                        assertEquals(additions.groupingBy { it.id }.eachCount(), afterShuffleAppend.ids().drop(7).groupingBy { it }.eachCount())
                        assertEquals(before.currentId, afterShuffleAppend.currentId)
                    }
                    val beforeUiAppend = observe().ids()
                    compose.onNodeWithTag("tab-library").performClick()
                    compose.onNodeWithText("Playlists").performScrollTo().performClick()
                    compose.onNodeWithText("Append $fixture").performScrollTo().performClick()
                    compose.waitUntil(10_000) { model.loadedPlaylistId == playlistId && model.playlistItems.size == 3 }
                    compose.waitUntil(10_000) {
                        model.message == null && compose.onAllNodesWithText("Added 3 songs to queue").fetchSemanticsNodes().isEmpty()
                    }
                    compose.onNodeWithTag("playlist-feed").performScrollToNode(hasText("Add playlist to queue"))
                    compose.onNodeWithText("Add playlist to queue").assertIsEnabled().performClick()
                    compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("Shuffle").assertIsDisplayed() }.isSuccess }
                    compose.onNodeWithText("Shuffle").assertIsDisplayed()
                    compose.onNodeWithText("Smart shuffle").assertIsDisplayed()
                    compose.onNodeWithText("Playlist order").performClick()
                    compose.waitUntil(15_000) { !model.busy && observe().entries.size == 13 }
                    val afterUiAppend = observe().ids()
                    assertEquals(beforeUiAppend, afterUiAppend.take(10))
                    assertEquals(listOf(songs[4].id, songs[4].id, songs[1].id), afterUiAppend.drop(10))
                } catch (failure: Throwable) {
                    runCatching {
                        val instrumentation = InstrumentationRegistry.getInstrumentation()
                        val artifacts = context.getExternalFilesDir(null) ?: context.filesDir
                        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                            File(artifacts, "queue-choice-failure.png").outputStream().use { output ->
                                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
                            }
                        }
                        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
                        val semantics = (0 until roots.fetchSemanticsNodes().size).joinToString("\n\n") { roots[it].printToString() }
                        File(artifacts, "queue-choice-failure.txt").writeText(semantics)
                    }
                    throw failure
                } finally {
                    scenario.onActivity {
                        model.controller?.let { player ->
                            player.stop(); player.clearMediaItems()
                            player.repeatMode = Player.REPEAT_MODE_OFF
                            player.shuffleModeEnabled = false
                        }
                    }
                }
            }
        } finally {
            SQLiteDatabase.openDatabase(context.getDatabasePath("echo-library.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.setForeignKeyConstraintsEnabled(true)
                db.delete("playlists", "id = ?", arrayOf(playlistId))
                songs.forEach { db.delete("tracks", "id = ?", arrayOf(it.id)) }
                db.delete("artists", "name = ?", arrayOf(fixture))
                db.delete("albums", "artist = ?", arrayOf(fixture))
            }
            audio.delete()
        }
    }

    private data class QueueObservation(val entries: List<QueueEntry>, val currentId: String?, val position: Long, val playing: Boolean) {
        fun ids() = entries.map { it.item.mediaId }
    }
}
