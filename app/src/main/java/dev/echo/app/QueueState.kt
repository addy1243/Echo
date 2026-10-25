package dev.echo.app

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline

internal data class QueueEntry(val index: Int, val uid: Any, val item: MediaItem)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun queueSnapshot(player: Player): List<QueueEntry> {
    val timeline = player.currentTimeline
    if (timeline.isEmpty || player.currentMediaItemIndex !in 0 until timeline.windowCount) return emptyList()
    val entries = mutableListOf<QueueEntry>()
    val visited = mutableSetOf<Int>()
    var index = player.currentMediaItemIndex
    val repeat = if (player.repeatMode == Player.REPEAT_MODE_ALL) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
    while (index in 0 until timeline.windowCount && visited.add(index)) {
        val window = timeline.getWindow(index, Timeline.Window())
        entries += QueueEntry(index, window.uid, player.getMediaItemAt(index))
        index = timeline.getNextWindowIndex(index, repeat, player.shuffleModeEnabled)
    }
    return entries
}
