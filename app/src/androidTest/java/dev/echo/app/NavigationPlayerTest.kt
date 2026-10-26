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
class NavigationPlayerTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun switchingTabsAlwaysReturnsToTheChosenScreen() {
        ActivityScenario.launch(MainActivity::class.java).use {
            repeat(6) {
                compose.onNodeWithTag("tab-library").performClick()
                compose.onNodeWithText("Your collection").assertIsDisplayed()
                compose.onNodeWithTag("tab-home").performClick()
                compose.onNodeWithTag("home-feed").assertIsDisplayed()
                compose.onNodeWithTag("tab-home").assertIsSelected()
                compose.onNodeWithTag("tab-search").performClick()
                compose.onNodeWithText("Find your next favorite").assertIsDisplayed()
            }
        }
    }

    @Test fun queueUsesPlaybackShuffleOrderAndFollowsNextWithDuplicateOccurrences() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = "queue-ui-${UUID.randomUUID()}"
        val audio = File(context.filesDir, "$fixture.wav")
        val samples = 8_000 * 60
        val wav = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(samples * 2)
        audio.writeBytes(wav.array())
        val songs = (1..3).map { Track("$fixture-$it", "Queue song $it", fixture, durationMs = 60_000, sourceUri = Uri.fromFile(audio).toString()) }
        val library = LibraryRepository(context)
        val playlistName = "$fixture playlist"
        val playlistId = runBlocking { library.upsertTracks(songs); library.createPlaylist(playlistName) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: EchoViewModel
                scenario.onActivity { model = ViewModelProvider(it)[EchoViewModel::class.java] }
                compose.waitUntil(15_000) { model.controller != null }
                scenario.onActivity { model.controller!!.repeatMode = Player.REPEAT_MODE_ALL; model.play(listOf(songs[0], songs[1], songs[0], songs[2]), 0, true) }
                compose.waitUntil(20_000) { model.queueItems.size == 4 && model.playing }
                scenario.onActivity {
                    val player = model.controller!!
                    var index = player.currentMediaItemIndex
                    val expected = buildList { repeat(4) { add(index); index = player.currentTimeline.getNextWindowIndex(index, Player.REPEAT_MODE_ALL, true) } }
                    assertEquals(expected, model.queueItems.map { it.index })
                    assertEquals(4, model.queueItems.map { it.uid }.distinct().size)
                }
                compose.onNodeWithTag("mini-player").performClick()
                compose.onNodeWithTag("player-screen").assertIsDisplayed()
                compose.onNodeWithTag("tab-home").assertDoesNotExist()
                val savedCurrent = model.current!!.mediaId
                repeat(2) {
                    compose.onNodeWithContentDescription("Add current song to playlist").performClick()
                    compose.onNodeWithText(playlistName).performClick()
                }
                compose.waitUntil(10_000) { runBlocking { library.playlistTracks(playlistId).size == 2 } }
                assertEquals(listOf(savedCurrent, savedCurrent), runBlocking { library.playlistTracks(playlistId).map { it.id } })
                capture("echo-player-preview.png")
                compose.onNodeWithContentDescription("Open queue").performClick()
                compose.onNodeWithText("Your queue").assertIsDisplayed()
                val upcoming = model.queueItems[1].item
                compose.onAllNodesWithContentDescription("Add ${upcoming.mediaMetadata.title} to playlist").onFirst().performClick()
                compose.onNodeWithText(playlistName).performClick()
                compose.waitUntil(10_000) { runBlocking { library.playlistTracks(playlistId).size == 3 } }
                assertEquals(upcoming.mediaId, runBlocking { library.playlistTracks(playlistId).last().id })
                capture("echo-queue-preview.png")
                val previous = model.queueItems.first().uid
                scenario.onActivity { model.controller!!.seekToNextMediaItem() }
                compose.waitUntil(10_000) { model.queueItems.firstOrNull()?.uid != previous }
                compose.onNodeWithTag("queue-current").assert(hasText(model.current!!.mediaMetadata.title.toString()))
                val now = model.current!!.mediaId
                compose.onNodeWithText("Clear up next").performClick()
                compose.waitUntil(10_000) { model.queueItems.size == 1 }
                assertEquals(now, model.current!!.mediaId)
                scenario.onActivity { model.controller!!.stop(); model.controller!!.clearMediaItems(); model.controller!!.repeatMode = Player.REPEAT_MODE_OFF }
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

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
