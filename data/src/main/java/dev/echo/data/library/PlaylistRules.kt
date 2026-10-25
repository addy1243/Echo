package dev.echo.data.library

internal fun checkedName(name: String): String = name.trim().also { require(it.isNotEmpty()) { "Playlist name cannot be empty" } }

internal fun movedPlaylistIds(ids: List<String>, from: Int, to: Int): List<String> {
    require(from in ids.indices && to in ids.indices) { "Playlist position is out of range" }
    return ids.toMutableList().apply { add(to, removeAt(from)) }
}
