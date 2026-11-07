package dev.echo.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.echo.core.Track
import dev.echo.data.firebase.ConnectedDevice
import dev.echo.data.firebase.ConnectedGroupV2

/** A focused-player snapshot shared by the dock cards and full player. */
internal data class PlayerPresentationState(
    val selectedRemoteId: String?,
    val remote: ConnectedDevice?,
    val group: ConnectedGroupV2?,
    val current: Track?,
    val title: String,
    val artist: String,
    val artworkUri: String?,
    val playing: Boolean,
    val buffering: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val remoteEnabled: Boolean,
    val reconnecting: Boolean,
    val independentDevices: List<ConnectedDevice>,
)

internal data class PlayerDeckEntry(
    val key: String,
    val target: ConnectedDevice?,
    val deviceLabel: String,
    val presentation: PlayerPresentationState,
    val isGroup: Boolean,
)

internal data class PlayerDeckState(
    val entries: List<PlayerDeckEntry>,
    val focusedKey: String,
) {
    val focused: PlayerDeckEntry get() = entries.firstOrNull { it.key == focusedKey } ?: entries.first()
    val focusedIndex: Int get() = entries.indexOfFirst { it.key == focusedKey }.coerceAtLeast(0)
}

internal fun independentConnectedPlayers(
    devices: List<ConnectedDevice>,
    ownDeviceId: String,
    groupMemberIds: Set<String>,
): List<ConnectedDevice> = devices.filter {
    it.id != ownDeviceId && it.id !in groupMemberIds && it.controlEnabled
}.sortedBy { it.label.lowercase() }

internal fun connectedPlaybackFocusTargets(
    independentDevices: List<ConnectedDevice>,
    selectedRemote: ConnectedDevice?,
): List<ConnectedDevice?> = buildList {
    add(null) // Local playback or the active shared group.
    addAll(independentDevices)
    selectedRemote?.takeIf { selected -> none { it?.id == selected.id && it.sessionId == selected.sessionId } }?.let(::add)
}

internal fun playerDeckEntryKey(target: ConnectedDevice?, group: ConnectedGroupV2?): String = when {
    target != null -> "device:${target.id}:${target.sessionId}"
    group != null -> "group:${group.ref.groupId}:${group.ref.epoch}"
    else -> "local"
}

/** Page zero and the final page are wrap sentinels, keyed separately from real pages. */
internal fun playerDeckPageCount(entryCount: Int): Int = if (entryCount > 1) entryCount + 2 else 1

internal fun playerDeckEntryAtPage(entries: List<PlayerDeckEntry>, page: Int): PlayerDeckEntry? {
    if (entries.isEmpty()) return null
    val index = when {
        entries.size == 1 -> 0
        page == 0 -> entries.lastIndex
        page >= entries.size + 1 -> 0
        else -> page - 1
    }
    return entries.getOrNull(index)
}

internal fun playerDeckPageForKey(entries: List<PlayerDeckEntry>, key: String): Int =
    (entries.indexOfFirst { it.key == key }.takeIf { it >= 0 } ?: 0) + if (entries.size > 1) 1 else 0

internal fun playerDeckCanonicalPage(entries: List<PlayerDeckEntry>, page: Int): Int = when {
    entries.size <= 1 -> 0
    page == 0 -> entries.size
    page >= entries.size + 1 -> 1
    else -> page
}

internal fun playerDeckRevisionMatches(
    currentKeys: List<String>,
    currentFocusKey: String,
    reconciledKeys: List<String>?,
    reconciledFocusKey: String?,
): Boolean = currentKeys == reconciledKeys && currentFocusKey == reconciledFocusKey

@Composable
internal fun rememberPlayerDeck(vm: EchoViewModel, tracks: List<Track>): PlayerDeckState {
    val connected by vm.connectedPlaybackState.collectAsStateWithLifecycle()
    val connectedV2 by vm.connectedPlaybackV2State.collectAsStateWithLifecycle()
    val selectedId by vm.selectedConnectedPlaybackTargetId.collectAsStateWithLifecycle()
    val rememberedRemote by vm.selectedConnectedPlaybackDevice.collectAsStateWithLifecycle()
    val activeGroup = connectedV2.group
    val groupMemberIds = activeGroup?.members?.filter {
        it.status != dev.echo.data.firebase.GroupMemberV2Status.LEFT
    }?.map { it.deviceId }?.toSet().orEmpty()
    val selectedIsGroupMember = selectedId != null && activeGroup?.members?.any {
        it.deviceId == selectedId && it.status != dev.echo.data.firebase.GroupMemberV2Status.LEFT
    } == true
    val selectedRemote = if (selectedId == null || selectedIsGroupMember) null else connected.devices.firstOrNull {
        it.id == selectedId && it.sessionId == rememberedRemote?.sessionId
    } ?: rememberedRemote
    val independent = independentConnectedPlayers(connected.devices, vm.connectedPlaybackDeviceIdForUi, groupMemberIds)
    val targets = connectedPlaybackFocusTargets(independent, selectedRemote)
    val entries = targets.map { target ->
        val isGroup = target == null && activeGroup != null
        val presentation = if (target == null) {
            buildPlayerPresentation(vm, tracks, connected.devices, independent, activeGroup, null)
        } else {
            buildPlayerPresentation(vm, tracks, connected.devices, independent, null, target)
        }
        PlayerDeckEntry(
            key = playerDeckEntryKey(target, activeGroup.takeIf { isGroup }),
            target = target,
            deviceLabel = target?.label ?: if (isGroup) "Play together" else "This phone",
            presentation = presentation,
            isGroup = isGroup,
        )
    }
    val focusedKey = if (selectedRemote != null) playerDeckEntryKey(selectedRemote, null)
        else playerDeckEntryKey(null, activeGroup)
    return PlayerDeckState(entries, focusedKey)
}

@Composable
internal fun rememberPlayerPresentation(vm: EchoViewModel, tracks: List<Track>): PlayerPresentationState =
    rememberPlayerDeck(vm, tracks).focused.presentation

private fun buildPlayerPresentation(
    vm: EchoViewModel,
    tracks: List<Track>,
    devices: List<ConnectedDevice>,
    independentDevices: List<ConnectedDevice>,
    group: ConnectedGroupV2?,
    remote: ConnectedDevice?,
): PlayerPresentationState {
    val remoteSelected = remote != null
    val liveRemote = remote?.let { candidate -> devices.firstOrNull {
        it.id == candidate.id && it.sessionId == candidate.sessionId && it.controlEnabled
    } }
    val groupEntry = group?.timeline?.let { timeline -> timeline.queue.entries.getOrNull(timeline.cursor)?.recording }
    val current = when {
        remoteSelected -> tracks.firstOrNull { it.id == remote?.player?.trackId }
        group != null && groupEntry != null -> tracks.firstOrNull { it.id == groupEntry.id } ?: Track(
            id = groupEntry.id, title = groupEntry.title, artist = groupEntry.artist,
            durationMs = groupEntry.durationMs, provider = "youtube",
            sourceUri = "https://www.youtube.com/watch?v=${groupEntry.id.removePrefix("youtube:")}",
        )
        group != null -> null
        else -> tracks.firstOrNull { it.id == vm.current?.mediaId }
    }
    val title = when {
        remoteSelected -> remote?.takeIf { it.player.trackId != null }?.player?.title
            ?: if (liveRemote != null) "Choose a song" else "Reconnecting to player"
        group != null -> groupEntry?.title ?: "Preparing shared player"
        else -> vm.current?.mediaMetadata?.title?.toString() ?: "Choose a song"
    }
    val artist = when {
        remoteSelected -> remote?.takeIf { it.player.trackId != null }?.player?.artist.orEmpty()
        group != null -> groupEntry?.artist.orEmpty()
        else -> vm.current?.mediaMetadata?.artist?.toString().orEmpty()
    }
    val artwork = if (remoteSelected) connectedArtworkUri(remote?.player?.trackId, tracks)
        else if (group != null) current?.artworkUri else current?.artworkUri ?: vm.current?.mediaMetadata?.artworkUri?.toString()
    val playing = if (remoteSelected) remote?.player?.isPlaying == true else group?.timeline?.playing ?: (group == null && vm.playing)
    val localGroupOccurrenceId = group?.timeline?.let { timeline -> timeline.queue.entries.getOrNull(timeline.cursor)?.id }
    val localGroupOccurrenceMatches = groupEntry != null && group?.members?.firstOrNull {
        it.deviceId == vm.connectedPlaybackDeviceIdForUi
    }?.observedOccurrenceId == localGroupOccurrenceId
    val position = when {
        remoteSelected -> remote?.player?.positionMs ?: 0L
        localGroupOccurrenceMatches -> vm.position
        group != null -> group.timeline?.positionMs ?: 0L
        else -> vm.position
    }
    val duration = when {
        remoteSelected -> remote?.player?.durationMs ?: 0L
        group != null -> groupEntry?.durationMs ?: 0L
        else -> vm.duration
    }
    return PlayerPresentationState(
        selectedRemoteId = remote?.id, remote = remote, group = group, current = current,
        title = title, artist = artist, artworkUri = artwork, playing = playing,
        buffering = !remoteSelected && vm.buffering, positionMs = position.coerceAtLeast(0),
        durationMs = duration.coerceAtLeast(0), remoteEnabled = remote != null && vm.canControlConnectedDevice(remote),
        reconnecting = remoteSelected && liveRemote == null,
        independentDevices = independentDevices,
    )
}
