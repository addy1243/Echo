package dev.echo.app

import dev.echo.core.Track
import kotlin.random.Random

enum class QueueAddMode { NORMAL, SHUFFLE, SMART }

internal fun randomPlayableIndex(items: List<Track>, offlineOnly: Boolean = false, downloaded: Set<String> = emptySet(), random: Random = Random.Default): Int =
    items.indices.filter { index -> items[index].let { it.sourceUri != null && (!offlineOnly || it.provider == "local" || it.id in downloaded) } }
        .let { if (it.isEmpty()) -1 else it.random(random) }

internal fun smartShuffleAnchor(items: List<Track>, currentId: String?, random: Random = Random.Default): Track {
    val playable = items.filter { it.sourceUri != null }
    require(playable.isNotEmpty()) { "Choose recordings for your imported songs first" }
    return playable.find { it.id == currentId } ?: playable.random(random)
}
