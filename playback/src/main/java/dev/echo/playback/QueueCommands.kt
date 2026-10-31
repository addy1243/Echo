package dev.echo.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import dev.echo.core.Track
import dev.echo.data.library.LibraryRepository
import kotlinx.coroutines.flow.first

object QueueCommands {
    const val REORDER = "dev.echo.queue.REORDER"
    const val FROM_INDEX = "fromIndex"
    const val TO_INDEX = "toIndex"
    const val CURRENT_INDEX = "currentIndex"
    const val APPEND = "dev.echo.queue.APPEND"
    const val PLAY_SELECTED = "dev.echo.queue.PLAY_SELECTED"
    const val START_SHUFFLED = "dev.echo.queue.START_SHUFFLED"
    const val TRACK_IDS = "trackIds"
    const val START_INDEX = "startIndex"
    const val CONNECTED_START = "dev.echo.connected.START"
    const val CONNECTED_STOP = "dev.echo.connected.STOP"
    const val CONNECTED_MOVE = "dev.echo.connected.MOVE"
    const val CONNECTED_TOGETHER = "dev.echo.connected.TOGETHER"
    const val CONNECTED_GROUP_ACTION = "dev.echo.connected.GROUP_ACTION"
    const val CONNECTED_RESTORE_PREVIOUS = "dev.echo.connected.RESTORE_PREVIOUS"
    const val CONNECTED_TARGET_ID = "targetDeviceId"
    const val CONNECTED_TARGET_IDS = "targetDeviceIds"
    const val CONNECTED_TARGET_SESSIONS = "targetDeviceSessions"
    const val CONNECTED_REPLACE = "replacePlaying"
    const val CONNECTED_ACTION = "groupAction"
    const val CONNECTED_POSITION = "positionMs"
    const val CONNECTED_REMOTE_COMMAND = "dev.echo.connected.REMOTE_COMMAND"
    const val CONNECTED_REMOTE_ACTION = "remoteAction"
    const val CONNECTED_TARGET_SESSION = "targetSessionId"
    const val CONNECTED_TRACK_REVISION = "trackRevision"
    const val CONNECTED_QUEUE_REVISION = "queueRevision"
}

@UnstableApi
internal suspend fun editQueue(player: ExoPlayer, library: LibraryRepository, action: String, args: Bundle,
    resolve: suspend (Track) -> MediaItem?) {
    if (action == QueueCommands.REORDER) {
        val expected = args.getStringArrayList(QueueCommands.TRACK_IDS) ?: error("Missing queue snapshot")
        val from = args.getInt(QueueCommands.FROM_INDEX, -1)
        val to = args.getInt(QueueCommands.TO_INDEX, -1)
        val current = player.currentMediaItemIndex
        require(expected.size <= 10_000 && expected.sumOf { it.length.toLong() } <= 100_000 &&
            expected == List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId } &&
            current == args.getInt(QueueCommands.CURRENT_INDEX, -1)) { "Queue changed. Try dragging again." }
        val order = if (player.shuffleModeEnabled) fullShuffleOrder(player) else expected.indices.toList()
        val upcoming = if (player.repeatMode == Player.REPEAT_MODE_ALL) order.drop(order.indexOf(current)) + order.take(order.indexOf(current))
            else order.drop(order.indexOf(current))
        require(from in upcoming.drop(1) && to in upcoming.drop(1)) { "Only upcoming songs can be moved" }
        if (player.shuffleModeEnabled) {
            player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(reorderedTraversal(order, from, to).toIntArray(), 0L))
        } else if (player.repeatMode == Player.REPEAT_MODE_ALL && current > 0) {
            // Rotate the repeat-all traversal before moving across the physical queue boundary.
            player.moveMediaItems(0, current, expected.size - current)
            player.moveMediaItem((from - current + expected.size) % expected.size, (to - current + expected.size) % expected.size)
        } else player.moveMediaItem(from, to)
        return
    }
    require(action in setOf(QueueCommands.APPEND, QueueCommands.PLAY_SELECTED, QueueCommands.START_SHUFFLED)) { "Unknown queue action" }
    val ids = args.getStringArrayList(QueueCommands.TRACK_IDS) ?: error("Missing queue tracks")
    require(ids.isNotEmpty() && ids.size <= 5000 && ids.sumOf { it.length.toLong() } <= 100_000 && ids.all { it.isNotBlank() && it.length <= 512 }) { "Queue request is too large or invalid" }
    require(action != QueueCommands.PLAY_SELECTED || ids.size == 1) { "Select one song" }
    val before = player.currentTimeline
    val initialIndex = player.currentMediaItemIndex
    val initialUids = List(before.windowCount) { before.getWindow(it, Timeline.Window()).uid }
    val all = library.tracks.first().associateBy(Track::id)
    val resolved = ids.distinct().associateWith { id ->
        val track = all[id] ?: error("Song is no longer in the library")
        resolve(track) ?: error("No playable source for ${track.title}")
    }
    if (action == QueueCommands.PLAY_SELECTED) {
        val now = player.currentTimeline
        require(initialIndex == player.currentMediaItemIndex && initialUids == List(now.windowCount) { now.getWindow(it, Timeline.Window()).uid }) { "Playback changed while loading this song. Choose it again." }
    }
    val count = player.mediaItemCount
    val items = ids.map(resolved::getValue)
    if (action == QueueCommands.START_SHUFFLED) {
        val selected = args.getInt(QueueCommands.START_INDEX, -1)
        require(selected in items.indices) { "Select a playable occurrence" }
        val shuffled = listOf(selected) + items.indices.filter { it != selected }.shuffled()
        player.setMediaItems(items, selected, 0L)
        player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(shuffled.toIntArray(), 0L))
        player.shuffleModeEnabled = true
        player.prepare(); player.play()
    } else if (action == QueueCommands.APPEND) {
        require(count + items.size <= 10_000) { "Queue is full. Clear upcoming songs before adding more." }
        val extended = if (player.shuffleModeEnabled) appendedShuffleOrder(fullShuffleOrder(player), player.currentMediaItemIndex, player.repeatMode == Player.REPEAT_MODE_ALL, items.size) else null
        if (!player.shuffleModeEnabled && player.repeatMode == Player.REPEAT_MODE_ALL && player.currentMediaItemIndex > 0) {
            // Keep the entire repeat-all Up next traversal before the newly appended block.
            val current = player.currentMediaItemIndex
            player.moveMediaItems(0, current, count - current)
        }
        player.addMediaItems(items)
        extended?.let { player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(it.toIntArray(), 0L)) }
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
    } else {
        if (count == 0) player.setMediaItem(items.single())
        else {
            val order = fullShuffleOrder(player)
            player.replaceMediaItem(initialIndex, items.single())
            player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), 0L))
            player.seekTo(initialIndex, 0L)
        }
        player.prepare()
        player.play()
    }
}

@UnstableApi internal fun fullShuffleOrder(player: ExoPlayer): List<Int> {
    val timeline = player.currentTimeline
    val result = mutableListOf<Int>()
    val visited = mutableSetOf<Int>()
    var index = timeline.getFirstWindowIndex(true)
    while (index in 0 until timeline.windowCount && visited.add(index)) {
        result += index
        index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
    }
    check(result.size == timeline.windowCount) { "Queue order could not be read" }
    return result
}

internal fun appendedShuffleOrder(order: List<Int>, current: Int, repeatAll: Boolean, additions: Int): List<Int> {
    require(additions >= 0 && order.toSet() == order.indices.toSet()) { "Invalid queue order" }
    val offset = if (repeatAll) order.indexOf(current).coerceAtLeast(0) else 0
    return order.drop(offset) + order.take(offset) + (order.size until order.size + additions)
}

internal fun reorderedTraversal(order: List<Int>, from: Int, to: Int): List<Int> {
    require(order.toSet() == order.indices.toSet() && from in order && to in order) { "Invalid queue order" }
    val result = order.toMutableList()
    val destination = result.indexOf(to)
    result.remove(from)
    result.add(destination, from)
    return result
}
