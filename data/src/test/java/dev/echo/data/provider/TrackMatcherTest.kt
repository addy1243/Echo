package dev.echo.data.provider

import dev.echo.core.Track
import org.junit.Assert.*
import org.junit.Test

class TrackMatcherTest {
    private val track = Track("1", "Déjà Vu", "Artist", durationMs = 180_000)
    @Test fun normalizesAccentsAndPunctuation() {
        assertTrue(TrackMatcher.matches(track, track.copy(id = "2", title = "deja-vu")))
    }
    @Test fun rejectsWrongRecordingAndArtist() {
        assertFalse(TrackMatcher.matches(track, track.copy(durationMs = 220_000)))
        assertFalse(TrackMatcher.matches(track, track.copy(artist = "Different")))
        assertFalse(TrackMatcher.matches(track.copy(artist = ""), track.copy(artist = "")))
    }
    @Test fun recordingIdentifierOverridesDisplayMetadata() {
        assertTrue(TrackMatcher.matches(track.copy(isrc = "AB123"), track.copy(title = "Localized title", isrc = "ab123")))
        assertFalse(TrackMatcher.matches(track.copy(isrc = "AB123"), track.copy(isrc = "AB999")))
    }
    @Test fun requiresExplicitRecognizedLicense() {
        assertTrue(CommonsProvider.authorizedLicense("https://creativecommons.org/licenses/by-sa/4.0/"))
        assertFalse(CommonsProvider.authorizedLicense("https://creativecommons.org.evil.example/licenses/by/4.0/"))
        assertFalse(CommonsProvider.authorizedLicense("https://creativecommons.org/licenses/by-nc/4.0/"))
        assertFalse(CommonsProvider.authorizedLicense(""))
    }
    @Test fun acceptsCommonsOggAudioWithoutAcceptingOggVideo() {
        assertTrue(CommonsProvider.supportedAudio("application/ogg", "AUDIO"))
        assertTrue(CommonsProvider.supportedAudio("audio/flac", "AUDIO"))
        assertFalse(CommonsProvider.supportedAudio("application/ogg", "VIDEO"))
        assertFalse(CommonsProvider.supportedAudio("audio/ogg", ""))
        assertFalse(CommonsProvider.supportedAudio("text/html", "AUDIO"))
    }
}
