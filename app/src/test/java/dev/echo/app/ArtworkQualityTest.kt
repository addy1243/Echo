package dev.echo.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkQualityTest {
    @Test fun upgradesOnlyKnownTrustedMusicResizeSuffixes() {
        assertEquals("https://lh3.googleusercontent.com/album=w1200-h1200-l90-rj",
            playerArtwork("https://lh3.googleusercontent.com/album=w120-h120-l90-rj"))
        listOf(null, "content://media/album/1", "https://i.ytimg.com/vi/id/hqdefault.jpg",
            "https://googleusercontent.com.evil.test/album=w120-h120", "http://lh3.googleusercontent.com/a=w120-h120",
            "https://user@lh3.googleusercontent.com/a=w120-h120", "https://lh3.googleusercontent.com:8443/a=w120-h120",
            "https://lh3.googleusercontent.com/a=w120-h120?token=signature", "https://lh3.googleusercontent.com/a=unknown")
            .forEach { assertEquals(it, it, playerArtwork(it)) }
    }
}
