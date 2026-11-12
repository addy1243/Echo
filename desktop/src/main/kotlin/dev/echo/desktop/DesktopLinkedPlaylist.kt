package dev.echo.desktop

import dev.echo.data.provider.YouTubePlaylistPreview
import dev.echo.data.provider.YouTubeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun refreshDesktopLinkedPlaylist(store: DesktopLibrary, provider: YouTubeProvider, id: String) {
    val source = withContext(Dispatchers.IO) { store.snapshot().linkedPlaylists[id] } ?: return
    val preview = provider.playlist(source.link)
    persistDesktopLinkedPlaylistRefresh(store, id, source, preview)
}

internal suspend fun persistDesktopLinkedPlaylistRefresh(
    store: DesktopLibrary,
    id: String,
    source: DesktopLinkedPlaylist,
    preview: YouTubePlaylistPreview,
) {
    check(preview.complete) { "The complete playlist could not be verified; the saved copy was kept" }
    withContext(Dispatchers.IO) { store.refreshLinkedYouTubePlaylist(id, source, preview.tracks) }
}
