package dev.echo.desktop

import dev.echo.core.Track
import dev.echo.core.PlayableSource
import dev.echo.data.offline.audioDigest
import dev.echo.data.offline.offlineFileName
import dev.echo.data.provider.YouTubeProvider
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopOfflineTest {
    @get:Rule val temporary = TemporaryFolder()
    private val song = Track("youtube:abcdefghijk", "Song", "Artist", provider = "youtube")
    private fun seed(status: String = "COMPLETE"): java.nio.file.Path {
        val root = temporary.newFolder().toPath()
        val audio = root.resolve(offlineFileName(song.id)).toFile().apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val entry = JSONObject().put("id", song.id).put("title", song.title).put("artist", song.artist)
            .put("status", status).put("bytes", audio.length()).put("sha256", audioDigest(audio))
        Files.writeString(root.resolve("manifest.json"), JSONObject().put("version", 1).put("offline", true)
            .put("entries", JSONArray().put(entry)).toString())
        return root
    }
    @Test fun completeFileIsVerifiedBeforePlaybackAndCorruptionRequiresRetry() = runBlocking {
        val root = seed()
        DesktopOfflineRepository(YouTubeProvider(), root).use { repository ->
            assertTrue(repository.state.value.offlineOnly)
            assertNotNull(repository.savedFile(song))
            root.resolve(offlineFileName(song.id)).toFile().writeBytes(byteArrayOf(4, 3, 2, 1))
            assertNull(repository.savedFile(song))
            assertEquals(DesktopDownloadStatus.RETRY, repository.state.value.entries.single().status)
        }
    }
    @Test fun restartDiscardsPartialFilesAndNeverOffersInterruptedAudio() = runBlocking {
        val root = seed("DOWNLOADING")
        val partial = root.resolve(offlineFileName(song.id) + ".part").toFile().apply { writeText("partial") }
        DesktopOfflineRepository(YouTubeProvider(), root).use { repository ->
            assertEquals(DesktopDownloadStatus.RETRY, repository.state.value.entries.single().status)
            assertFalse(partial.exists())
            assertNull(repository.savedFile(song))
            try { repository.download(song); fail("Offline mode must reject downloads") } catch (_: IllegalStateException) { }
            repository.remove(song.id)
            assertTrue(repository.state.value.entries.isEmpty())
        }
    }
    @Test fun unreadableManifestPreservesExistingAudioAndRefusesOverwrite() {
        val root = seed()
        val manifest = root.resolve("manifest.json")
        Files.writeString(manifest, "corrupt")
        DesktopOfflineRepository(YouTubeProvider(), root).use { repository ->
            assertNotNull(repository.state.value.error)
            assertTrue(root.resolve(offlineFileName(song.id)).toFile().exists())
            try { repository.download(song); fail("Must not overwrite corrupt storage") } catch (_: IllegalStateException) { }
            assertEquals("corrupt", Files.readString(manifest))
        }
    }
    @Test fun modeChangeAndRemovalPersistWithoutSavingDeliveryUrls() = runBlocking {
        val root = seed()
        DesktopOfflineRepository(YouTubeProvider(), root).use { repository -> repository.setOfflineOnly(false) }
        DesktopOfflineRepository(YouTubeProvider(), root).use { repository ->
            assertFalse(repository.state.value.offlineOnly)
            assertNotNull(repository.savedFile(song))
            repository.remove(song.id)
        }
        assertFalse(root.resolve(offlineFileName(song.id)).toFile().exists())
        val saved = Files.readString(root.resolve("manifest.json"))
        assertFalse(saved.contains("sourceUri"))
        assertFalse(saved.contains("googlevideo"))
    }

    private fun transport(root: java.nio.file.Path, code: Int = 200, mime: String = "audio/webm", declared: Long = 4,
        delivery: String = "https://rr1.googlevideo.com/videoplayback?signature=never-persist",
        resolver: (suspend (Track) -> PlayableSource?)? = null): DesktopOfflineRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Synthetic fixture")
                .body(object : ResponseBody() {
                    override fun contentType() = mime.toMediaType()
                    override fun contentLength() = declared
                    override fun source() = Buffer().write(byteArrayOf(1, 2, 3, 4))
                }).build()
        }.build()
        return DesktopOfflineRepository(root, client, resolver ?: { PlayableSource(delivery) })
    }
    @Test fun successfulTransferCommitsVerifiedBytesWithoutDeliveryUrl() = runBlocking {
        val root = temporary.newFolder().toPath()
        transport(root).use { repository ->
            repository.download(song)
            val entry = withTimeout(5000) { repository.state.first { it.entries.singleOrNull()?.status == DesktopDownloadStatus.COMPLETE } }.entries.single()
            assertEquals(4L, entry.receivedBytes)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), repository.savedFile(song)!!.readBytes())
            assertFalse(Files.readString(root.resolve("manifest.json")).contains("signature"))
            assertFalse(root.resolve(offlineFileName(song.id) + ".part").toFile().exists())
        }
    }
    @Test fun partialErrorNonAudioAndUntrustedSourcesNeverBecomePlayable() = runBlocking {
        val fixtures = listOf(
            Triple(200, "audio/webm", 8L), Triple(403, "audio/webm", 4L), Triple(200, "text/html", 4L))
        for ((code, mime, length) in fixtures) {
            val root = temporary.newFolder().toPath()
            transport(root, code, mime, length).use { repository ->
                repository.download(song)
                withTimeout(5000) { repository.state.first { it.entries.singleOrNull()?.status == DesktopDownloadStatus.RETRY } }
                assertNull(repository.savedFile(song))
                assertFalse(root.resolve(offlineFileName(song.id)).toFile().exists())
            }
        }
        val root = temporary.newFolder().toPath()
        transport(root, delivery = "https://evil.example/audio").use { repository ->
            repository.download(song)
            withTimeout(5000) { repository.state.first { it.entries.singleOrNull()?.status == DesktopDownloadStatus.RETRY } }
            assertNull(repository.savedFile(song))
        }
    }
    @Test fun cancelledTransferReleasesSerialQueueAndCannotCommitPartialAudio() = runBlocking {
        val root = temporary.newFolder().toPath()
        val started = CompletableDeferred<Unit>()
        val second = song.copy(id = "youtube:abcdefghij2")
        transport(root, resolver = { track ->
            if (track.id == song.id) { started.complete(Unit); awaitCancellation() }
            else PlayableSource("https://rr1.googlevideo.com/videoplayback")
        }).use { repository ->
            repository.download(song)
            withTimeout(5000) { started.await() }
            repository.download(second)
            assertEquals(DesktopDownloadStatus.QUEUED, repository.state.value.entries.find { it.track.id == second.id }!!.status)
            repository.cancel(song.id)
            withTimeout(5000) { repository.state.first { state ->
                state.entries.find { it.track.id == song.id }?.status == DesktopDownloadStatus.RETRY &&
                    state.entries.find { it.track.id == second.id }?.status == DesktopDownloadStatus.COMPLETE
            } }
            assertNull(repository.savedFile(song))
            assertNotNull(repository.savedFile(second))
        }
    }
}
