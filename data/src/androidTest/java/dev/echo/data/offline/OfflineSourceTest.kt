package dev.echo.data.offline

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.echo.core.Track
import dev.echo.data.provider.CommonsProvider
import dev.echo.data.provider.SourceResolver
import dev.echo.data.provider.YouTubeProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OfflineSourceTest {
    @Test fun offlineResolverUsesVerifiedFileWithoutOnlineExtractionAndRejectsMissingAudio() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().context
        val name = "offline-test-${UUID.randomUUID()}"
        val root = File(base.cacheDir, name).apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = root
            override fun getSharedPreferences(value: String, mode: Int) = base.getSharedPreferences("$name-$value", mode)
        }
        val prefs = context.getSharedPreferences("offline-audio", Context.MODE_PRIVATE)
        try {
            val track = Track("youtube:offline-fixture", "Offline fixture", "Echo test", provider = "youtube",
                sourceUri = "https://invalid.example/no-online-extraction")
            val audio = File(File(root, "offline-audio").apply { mkdirs() }, offlineFileName(track.id))
            audio.writeBytes(byteArrayOf(1, 2, 3, 4))
            val manifest = JSONArray().put(JSONObject().put("id", track.id).put("title", track.title)
                .put("pinned", true).put("status", "saved").put("bytes", 4).put("sha256", audioDigest(audio)))
            prefs.edit().putBoolean("offline", true).putString("manifest", manifest.toString()).commit()
            val youtube = YouTubeProvider()
            val offline = OfflineRepository(context, youtube, CommonsProvider())
            val resolver = SourceResolver(context, youtube, offline)
            assertEquals(audio.toURI().path, android.net.Uri.parse(resolver.resolve(track)!!.uri).path)
            audio.delete()
            assertNull(resolver.resolve(track))
            assertEquals("failed", offline.songs.value.single().status)
            offline.setOfflineOnly(false)
            offline.download(track)
            offline.cancel(track.id)
            assertEquals("failed", offline.songs.value.single().status)
            offline.download(track)
            withTimeout(5_000) {
                while (offline.songs.value.single().status != "failed") delay(20)
            }
            // Invalid stable locator fails before extraction; a cancelled job must not block Retry.
            assertNull(offline.localSource(track))
            offline.remove(track.id)
            assertTrue(offline.songs.value.isEmpty())
        } finally {
            prefs.edit().clear().commit()
            context.getSharedPreferences("sources", Context.MODE_PRIVATE).edit().clear().commit()
            root.walkBottomUp().forEach(File::delete)
        }
    }
}
