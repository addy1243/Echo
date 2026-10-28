package dev.echo.desktop

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class DesktopArtworkTest {
    @Test fun onlyTrustedHttpsArtworkHostsAreAllowed() {
        assertTrue(trustedDesktopArtworkUrl("https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg"))
        assertTrue(trustedDesktopArtworkUrl("https://lh3.googleusercontent.com/image"))
        assertTrue(trustedDesktopArtworkUrl("https://yt3.ggpht.com/image"))
        listOf(null, "file:///photo.jpg", "http://i.ytimg.com/a", "https://ytimg.com.evil.test/a",
            "https://evilytimg.com/a", "https://user:secret@i.ytimg.com/a", "https://i.ytimg.com:8443/a").forEach {
            assertFalse(it, trustedDesktopArtworkUrl(it))
        }
    }

    @Test fun advertisedSizeCannotBypassStreamBound() {
        assertEquals(MAX_ARTWORK_BYTES, readDesktopArtworkBytes(ByteArrayInputStream(ByteArray(MAX_ARTWORK_BYTES))).size)
        try {
            readDesktopArtworkBytes(ByteArrayInputStream(ByteArray(MAX_ARTWORK_BYTES + 1)))
            fail("Expected oversized artwork rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun imageDimensionsAreBoundedBeforeDecode() {
        assertTrue(validDesktopArtworkDimensions(1200, 1200))
        assertFalse(validDesktopArtworkDimensions(0, 1200))
        assertFalse(validDesktopArtworkDimensions(100_000, 100_000))
        assertFalse(validDesktopArtworkDimensions(Int.MAX_VALUE, Int.MAX_VALUE))
    }
}
