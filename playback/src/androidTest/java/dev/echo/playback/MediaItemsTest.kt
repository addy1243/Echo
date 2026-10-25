package dev.echo.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.echo.core.Track
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaItemsTest {
    @Test fun localControllerRequestsCarryIdentityAndMetadataWithoutSourceAccess() {
        val item = Track("local:42", "Track", "Artist", sourceUri = "content://media/external/audio/media/42").toMediaItem()
        assertEquals("local:42", item.mediaId)
        assertEquals(true, item.mediaMetadata.isPlayable)
        assertEquals(false, item.mediaMetadata.isBrowsable)
        assertNull(item.localConfiguration)
    }

    @Test fun unavailableMetadataAndFoldersHaveDistinctCarFlags() {
        val unavailable = Track("spotify:42", "Track", "Artist", provider = "spotify").toMediaItem()
        assertEquals(false, unavailable.mediaMetadata.isPlayable)
        assertEquals(false, unavailable.mediaMetadata.isBrowsable)
        val parent = folder("playlists", "Playlists")
        assertEquals(false, parent.mediaMetadata.isPlayable)
        assertEquals(true, parent.mediaMetadata.isBrowsable)
    }
}
