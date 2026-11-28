package dev.echo.playback

internal data class RestoredQueue(val ids: List<String>, val index: Int, val positionMs: Long, val originalIndices: List<Int>)

internal fun canApplyQueueRestore(expectedRevision: Long, currentRevision: Long, queueEmpty: Boolean) =
    expectedRevision == currentRevision && queueEmpty

internal fun canonicalYoutubeWatchUri(recordingId: String): String? =
    recordingId.takeIf { it.matches(Regex("youtube:[A-Za-z0-9_-]{11}")) }
        ?.removePrefix("youtube:")?.let { "https://www.youtube.com/watch?v=$it" }

/** Keeps duplicate entries and resets the position if the selected item was removed. */
internal fun restoreQueue(ids: List<String>, index: Int, positionMs: Long, available: Set<String>): RestoredQueue? {
    val remaining = ids.withIndex().filter { it.value in available }
    if (remaining.isEmpty()) return null
    val selected = index.coerceIn(ids.indices)
    val newIndex = remaining.indexOfFirst { it.index >= selected }.let { if (it < 0) remaining.lastIndex else it }
    return RestoredQueue(remaining.map { it.value }, newIndex,
        if (remaining[newIndex].index == selected) positionMs.coerceAtLeast(0) else 0, remaining.map { it.index })
}

internal fun restoredShuffleOrder(saved: List<Int>, originalIndices: List<Int>): List<Int> {
    if (saved.toSet() != saved.indices.toSet() || originalIndices.any { it !in saved.indices })
        return originalIndices.indices.toList()
    val newIndexByOriginal = originalIndices.withIndex().associate { it.value to it.index }
    return saved.mapNotNull(newIndexByOriginal::get)
}

internal fun <T> page(items: List<T>, page: Int, size: Int): List<T> {
    require(page >= 0 && size > 0)
    val start = page.toLong() * size
    if (start >= items.size) return emptyList()
    return items.subList(start.toInt(), (start + size).coerceAtMost(items.size.toLong()).toInt())
}
