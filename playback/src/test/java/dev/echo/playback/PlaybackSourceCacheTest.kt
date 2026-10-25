package dev.echo.playback

import dev.echo.core.PlayableSource
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class PlaybackSourceCacheTest {
    @Test fun resolvesOnlyOnUseAndRefreshesBeforeExpiry() {
        var calls = 0
        val cache = PlaybackSourceCache {
            calls++
            PlayableSource("https://audio.example/$calls", expiresAt = calls * 100_000L)
        }
        assertEquals(0, calls)
        assertEquals("https://audio.example/1", cache.get(0).uri)
        assertEquals("https://audio.example/1", cache.get(69_999).uri)
        assertEquals(1, calls)
        assertEquals("https://audio.example/2", cache.get(70_000).uri)
        assertEquals(2, calls)
    }

    @Test fun aNewPreparedSourceResolvesAgain() {
        var calls = 0
        val resolve = { calls++; PlayableSource("https://audio.example/$calls") }
        val first = PlaybackSourceCache(resolve)
        assertEquals(first.get(0), first.get(500_000))
        assertNotEquals(first.get(0), PlaybackSourceCache(resolve).get(0))
        assertEquals(2, calls)
    }

    @Test(expected = IOException::class) fun missingSourceFailsExplicitly() {
        PlaybackSourceCache { null }.get(0)
    }

    @Test fun refreshesForbiddenOnlineUrlOnlyOnceAndNeverRateLimits() {
        assertTrue(shouldRefreshOnlineSource("youtube", 403, 0))
        assertFalse(shouldRefreshOnlineSource("youtube", 403, 1))
        assertFalse(shouldRefreshOnlineSource("youtube", 429, 0))
        assertFalse(shouldRefreshOnlineSource("commons", 403, 0))
        assertFalse(shouldRefreshOnlineSource(null, null, 0))
    }
}
