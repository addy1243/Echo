package dev.echo.playback

internal data class RestoredQueue(val ids: List<String>, val index: Int, val positionMs: Long)

/** Keeps duplicate entries and resets the position if the selected item was removed. */
internal fun restoreQueue(ids: List<String>, index: Int, positionMs: Long, available: Set<String>): RestoredQueue? {
    val remaining = ids.withIndex().filter { it.value in available }
    if (remaining.isEmpty()) return null
    val selected = index.coerceIn(ids.indices)
    val newIndex = remaining.indexOfFirst { it.index >= selected }.let { if (it < 0) remaining.lastIndex else it }
    return RestoredQueue(remaining.map { it.value }, newIndex,
        if (remaining[newIndex].index == selected) positionMs.coerceAtLeast(0) else 0)
}

internal fun <T> page(items: List<T>, page: Int, size: Int): List<T> {
    require(page >= 0 && size > 0)
    val start = page.toLong() * size
    if (start >= items.size) return emptyList()
    return items.subList(start.toInt(), (start + size).coerceAtMost(items.size.toLong()).toInt())
}
