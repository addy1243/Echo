package dev.echo.app

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import dev.echo.core.Playlist
import dev.echo.core.Track
import java.util.UUID

@Composable internal fun AddToPlaylistDialog(track: Track, vm: EchoViewModel, playlists: List<Playlist>, close: () -> Unit) {
    var create by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    fun add(playlist: Playlist) {
        close()
        vm.runAction("Added to ${playlist.name}") {
            if (vm.tracks.value.none { it.id == track.id }) vm.library.upsertTracks(listOf(track))
            vm.library.addToPlaylist(playlist.id, track.id)
        }
    }
    AlertDialog(onDismissRequest = close, title = { Text("Add to playlist") }, text = {
        LazyColumn {
            item { Text(track.title) }
            if (playlists.isEmpty()) item { Text("Create your first playlist below.") }
            items(playlists, key = { it.id }) { playlist -> TextButton(onClick = { add(playlist) }) { Text(playlist.name) } }
            item {
                if (create) OutlinedTextField(name, { name = it }, label = { Text("Playlist name") }, singleLine = true)
                else TextButton(onClick = { create = true }) { Text("New playlist") }
            }
        }
    }, confirmButton = {
        if (create) TextButton(enabled = name.isNotBlank(), onClick = {
            close()
            vm.runAction("Playlist created") {
                vm.library.importPlaylist(UUID.randomUUID().toString(), name.trim(), listOf(track))
            }
        }) { Text("Create and add") }
    }, dismissButton = { TextButton(onClick = close) { Text("Close") } })
}
