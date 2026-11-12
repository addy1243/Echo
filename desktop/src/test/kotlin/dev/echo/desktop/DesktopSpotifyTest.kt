package dev.echo.desktop

import dev.echo.core.Track
import dev.echo.data.provider.YouTubePlaylistPreview
import dev.echo.data.spotify.SpotifyLinkPreview
import java.nio.file.Files
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopSpotifyTest {
    @get:Rule val temporary = TemporaryFolder()
    private val imported = Track("spotify:abcdefghijklmnopqrstuv", "Song", "Artist", durationMs = 60_000, provider = "spotify")
    private val recording = Track("youtube:abcdefghijk", "Song", "Artist", durationMs = 60_000,
        provider = "youtube", sourceUri = "https://www.youtube.com/watch?v=abcdefghijk")
    private fun library() = DesktopLibrary(temporary.root.toPath().resolve("library.json"))

    @Test fun spotifyAccountZipPreviewRetainsExactArchiveAndExposesCounts() = runBlocking {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("YourLibrary.json"))
            zip.write("""{"tracks":[],"artists":[{"name":"Artist","uri":"spotify:artist:1234567890123456789012"}],"albums":[]}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("StreamingHistory0.json"))
            zip.write("""[{"endTime":"2024-01-02 03:04","trackName":"Song","artistName":"Artist","msPlayed":1200}]""".toByteArray())
            zip.closeEntry()
        }
        val bytes = output.toByteArray()
        val file = temporary.newFile("account.zip").apply { writeBytes(bytes) }
        val parsed = readDesktopPlaylistImport(file)
        assertArrayEquals(bytes, parsed.archive)
        assertEquals(1, parsed.historyCount)
        assertEquals(1, parsed.savedArtistCount)
        assertTrue(parsed.playlists.all { it.tracks.isEmpty() })
    }

    @Test fun csvImportPreservesDuplicateOccurrencesAndCreatesNewSnapshots() {
        val csv = "Track URI,Track Name,Artist Name(s),Track Duration (ms)\n" +
            "spotify:track:abcdefghijklmnopqrstuv,Song,Artist,60000\n".repeat(2)
        val parsed = parseDesktopPlaylistImport(csv, "Favorites.csv")
        val store = library()
        val first = store.importParsedPlaylists(parsed).single()
        val second = store.importParsedPlaylists(parsed).single()
        assertNotEquals(first.id, second.id)
        assertEquals(listOf(imported.id, imported.id), first.trackIds)
        assertEquals(1, store.snapshot().tracks.size)
    }

    @Test fun recordingMatchesAreAtomicPreserveLikesAndRejectStaleSnapshots() {
        val store = library()
        val list = store.importParsedPlaylists(DesktopPlaylistImport(listOf(DesktopImportPlaylist("Imported", listOf(imported, imported))))).single()
        store.toggleFavorite(imported)
        store.applyRecordingMatches(list.id, list.trackIds, mapOf(imported.id to recording))
        assertEquals(listOf(recording.id, recording.id), store.snapshot().playlists.single().trackIds)
        assertEquals(setOf(imported.id, recording.id), store.snapshot().favorites)
        val disk = Files.readString(store.file)
        assertThrows(IllegalArgumentException::class.java) { store.applyRecordingMatches(list.id, list.trackIds, emptyMap()) }
        assertEquals(disk, Files.readString(store.file))
    }

    @Test fun manualRecordingReplacesOnlyTheSelectedOccurrence() {
        val store = library()
        val list = store.importParsedPlaylists(DesktopPlaylistImport(listOf(DesktopImportPlaylist("Imported", listOf(imported, imported))))).single()
        store.replacePlaylistRecording(list.id, 1, list.trackIds, recording)
        assertEquals(listOf(imported.id, recording.id), store.snapshot().playlists.single().trackIds)
    }

    @Test fun invalidImportDoesNotPartiallySave() {
        val store = library()
        store.saveTrack(recording)
        val disk = Files.readString(store.file)
        assertThrows(IllegalArgumentException::class.java) {
            store.importParsedPlaylists(DesktopPlaylistImport(listOf(DesktopImportPlaylist("Valid", listOf(imported)), DesktopImportPlaylist("", listOf(imported)))))
        }
        assertEquals(disk, Files.readString(store.file))
    }

    @Test fun publicPreviewRetainsSpotifyIdentityAndPartialCount() {
        val preview = SpotifyLinkPreview("Partial", listOf(imported), 80)
        val result = desktopSpotifyImport(preview, "https://open.spotify.com/playlist/abcdefghijklmnopqrstuv?si=ignored")
        assertEquals("abcdefghijklmnopqrstuv", result.playlists.single().spotifyId)
        assertFalse(preview.complete)
        assertThrows(IllegalArgumentException::class.java) { desktopSpotifyImport(preview, "https://evil.example/playlist/abcdefghijklmnopqrstuv") }
    }

    @Test fun youtubePlaylistCountChangingDuringPreviewDoesNotBlockImport() {
        val preview = YouTubePlaylistPreview("Changed", listOf(recording, recording), total = 1)
        val result = desktopYouTubeImport(preview, "https://www.youtube.com/playlist?list=PL1234567890")
        assertEquals(listOf(recording, recording), result.playlists.single().tracks)
        assertFalse(preview.complete)
    }

    @Test fun automaticMatchingSearchesEachUniqueMissingSongAndKeepsAmbiguity() = runBlocking {
        var searches = 0
        val progress = mutableListOf<Pair<Int, Int>>()
        val matches = automaticDesktopMatches(listOf(imported, imported), emptyList(), {
            searches++; listOf(recording)
        }, { done, total -> progress.add(done to total) })
        assertEquals(1, searches)
        assertEquals(recording, matches[imported.id])
        assertEquals(1 to 1, progress.last())
        val ambiguous = automaticDesktopMatches(listOf(imported), emptyList(), {
            listOf(recording, recording.copy(id = "youtube:lmnopqrstuv", durationMs = 64_000))
        })
        assertTrue(ambiguous.isEmpty())
    }

    @Test fun matchingPrefersUniqueLocalRecordingAndPropagatesCancellation() = runBlocking {
        val local = DesktopLibrary.localTrack(temporary.newFile("song.wav")).copy(title = "Song", artist = "Artist", durationMs = 60_000)
        val matches = automaticDesktopMatches(listOf(imported), listOf(local), { error("Search should not run") })
        assertEquals(local, matches[imported.id])
        try {
            automaticDesktopMatches(listOf(imported), emptyList(), { throw CancellationException("cancel") })
            fail("Cancellation should escape without applying matches")
        } catch (_: CancellationException) { }
    }
}
