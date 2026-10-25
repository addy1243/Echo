package dev.echo.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import dev.echo.core.Track

/** Controllers submit identity only; the service resolves all sources from the trusted library. */
fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    .setMediaMetadata(MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setArtworkUri(artworkUri?.let(Uri::parse))
        .setIsBrowsable(false)
        .setIsPlayable(!sourceUri.isNullOrBlank())
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .build())
    .build()

internal fun folder(id: String, title: String): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    .setMediaMetadata(MediaMetadata.Builder()
        .setTitle(title)
        .setIsBrowsable(true)
        .setIsPlayable(false)
        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        .build())
    .build()
