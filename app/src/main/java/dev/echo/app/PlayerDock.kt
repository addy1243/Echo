package dev.echo.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import androidx.compose.runtime.snapshotFlow
import coil.compose.AsyncImage

private val PlayerSurface = Color(0xFF211F29)
private val PlayerOutline = Color(0xFF403B4B)
private val PlayerAccent = Color(0xFFBFA4FF)
private val GroupSurface = Color(0xFF203B38)
private val GroupAccent = Color(0xFF83E0D2)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PlayerDock(vm: EchoViewModel, tracks: List<dev.echo.core.Track>, open: () -> Unit) {
    val deck = rememberPlayerDeck(vm, tracks)
    val latestDeck by rememberUpdatedState(deck)
    val keys = deck.entries.map { it.key }
    val multiple = deck.entries.size > 1
    val scope = rememberCoroutineScope()
    val connected by vm.connectedPlaybackState.collectAsStateWithLifecycle()
    val connectedV2 by vm.connectedPlaybackV2State.collectAsStateWithLifecycle()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val statusHeight = with(density) { (16f * fontScale).dp }
    val contentRowHeight = with(density) { maxOf(56f, 52f * fontScale).dp } +
        if (deck.entries.any { it.presentation.hasExceptionalCompactStatus() }) statusHeight else 0.dp
    val cardHeight = contentRowHeight + 56.dp
    var scrubbing by remember { mutableStateOf(false) }
    val previewWidth = if (multiple) 48.dp else 0.dp
    val pageSpacing = if (multiple) 8.dp else 0.dp

    BoxWithConstraints(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp).height(cardHeight),
    ) {
        val width = minOf(maxWidth, 560.dp)
        val cardWidth = if (multiple) width - previewWidth - pageSpacing else width
        val initialPage = playerDeckPageForKey(deck.entries, deck.focusedKey)
        val pagerState = rememberPagerState(initialPage = initialPage) {
            playerDeckPageCount(deck.entries.size)
        }
        var pendingFocusKey by rememberSaveable { mutableStateOf<String?>(null) }
        var reconciledKeys by remember { mutableStateOf<List<String>?>(null) }
        var reconciledFocusKey by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(keys, deck.focusedKey) {
            val expected = playerDeckPageForKey(latestDeck.entries, latestDeck.focusedKey)
            if (pagerState.currentPage != expected) pagerState.scrollToPage(expected)
            pendingFocusKey = null
            reconciledKeys = latestDeck.entries.map { it.key }
            reconciledFocusKey = latestDeck.focusedKey
        }
        LaunchedEffect(keys) {
            snapshotFlow {
                val current = latestDeck
                Triple(
                    pagerState.isScrollInProgress,
                    pagerState.currentPage,
                    playerDeckRevisionMatches(
                        current.entries.map { it.key }, current.focusedKey, reconciledKeys, reconciledFocusKey,
                    ),
                )
            }
                .distinctUntilChanged()
                .collect { (scrolling, page, reconciled) ->
                    if (!scrolling && reconciled) {
                        val current = latestDeck
                        val entry = playerDeckEntryAtPage(current.entries, page) ?: return@collect
                        if (entry.key != current.focusedKey && entry.key != pendingFocusKey) {
                            pendingFocusKey = entry.key
                            vm.selectConnectedPlaybackTarget(entry.target)
                        }
                        val canonical = playerDeckCanonicalPage(current.entries, page)
                        if (page != canonical) pagerState.scrollToPage(canonical)
                    }
                }
        }

        val focusedDescription = playerCardDescription(
            deck.focused, deck.focusedIndex + 1, deck.entries.size,
        )
        val navigationActions = if (multiple) listOf(
            CustomAccessibilityAction("Previous player") {
                scope.launch { pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0)) }
                true
            },
            CustomAccessibilityAction("Next player") {
                scope.launch { pagerState.animateScrollToPage((pagerState.currentPage + 1).coerceAtMost(pagerState.pageCount - 1)) }
                true
            },
        ) else emptyList()

        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !scrubbing,
            pageSize = PageSize.Fixed(cardWidth),
            pageSpacing = pageSpacing,
            contentPadding = PaddingValues(end = previewWidth),
            beyondViewportPageCount = if (multiple) 1 else 0,
            key = { page ->
                val entry = requireNotNull(playerDeckEntryAtPage(deck.entries, page))
                when {
                    multiple && page == 0 -> "previous:${entry.key}"
                    multiple && page == deck.entries.size + 1 -> "next:${entry.key}"
                    else -> entry.key
                }
            },
            modifier = Modifier.align(Alignment.Center).width(width).height(cardHeight).clipToBounds(),
        ) { page ->
            val entry = playerDeckEntryAtPage(deck.entries, page) ?: return@HorizontalPager
            val isFocused = entry.key == deck.focusedKey
            val entryIndex = deck.entries.indexOfFirst { it.key == entry.key }.coerceAtLeast(0)
            val description = playerCardDescription(entry, entryIndex + 1, deck.entries.size, next = true)
            val deckReconciled = playerDeckRevisionMatches(keys, deck.focusedKey, reconciledKeys, reconciledFocusKey)
            val focusedAndSettled = isFocused && !pagerState.isScrollInProgress && deckReconciled &&
                pendingFocusKey == null &&
                pagerState.currentPage == playerDeckPageForKey(deck.entries, deck.focusedKey)
            val transportEnabled = when {
                !focusedAndSettled -> false
                entry.target != null -> entry.presentation.remoteEnabled && !connected.pending
                entry.isGroup -> !connectedV2.pending
                else -> vm.controller != null
            }
            val localPlayer = vm.controller
            CompactPlayerCard(
                vm = vm,
                entry = entry,
                position = entryIndex + 1,
                total = deck.entries.size,
                focused = isFocused,
                contentRowHeight = contentRowHeight,
                transportEnabled = transportEnabled,
                onScrubbingChanged = { scrubbing = it },
                onCardClick = {
                    if (isFocused) open()
                    else if (!pagerState.isScrollInProgress) scope.launch { pagerState.animateScrollToPage(page) }
                },
                onPlay = vm::toggleFocusedPlayback,
                onPrevious = vm::previousFocusedPlayback,
                onNext = vm::nextFocusedPlayback,
                canPlay = transportEnabled && (entry.target != null || entry.isGroup || vm.current != null),
                canNext = transportEnabled && (entry.target != null || entry.isGroup ||
                    (localPlayer?.hasNextMediaItem() == true && vm.revision >= 0)),
                accessibilityDescription = description,
                focusedDescription = focusedDescription,
                navigationActions = if (isFocused) navigationActions else emptyList(),
                testTag = when {
                    isFocused -> "mini-player"
                    page == pagerState.currentPage + 1 -> "mini-player-next-player"
                    else -> null
                },
            )
        }
    }
}

@Composable
private fun CompactPlayerCard(
    vm: EchoViewModel,
    entry: PlayerDeckEntry,
    position: Int,
    total: Int,
    focused: Boolean,
    contentRowHeight: androidx.compose.ui.unit.Dp,
    transportEnabled: Boolean,
    onScrubbingChanged: (Boolean) -> Unit,
    onCardClick: () -> Unit,
    onPlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    canPlay: Boolean,
    canNext: Boolean,
    accessibilityDescription: String,
    focusedDescription: String,
    navigationActions: List<CustomAccessibilityAction>,
    testTag: String?,
) {
    val presentation = entry.presentation
    val status = compactPlayerStatus(entry)
    val identity = compactPlayerIdentity(entry)
    val artist = presentation.artist
    val shape = RoundedCornerShape(20.dp)
    val cardClick = Modifier.clickable(onClick = onCardClick)
    val accessibility = if (focused) Modifier.semantics(mergeDescendants = true) {
        contentDescription = focusedDescription
        customActions = navigationActions
    } else Modifier.clearAndSetSemantics {
        contentDescription = accessibilityDescription
        role = Role.Button
        onClick(label = "Switch player") { onCardClick(); true }
    }
    Surface(
        modifier = Modifier.fillMaxSize().then(cardClick).then(accessibility).then(testTag?.let(Modifier::testTag) ?: Modifier),
        shape = shape,
        color = if (entry.isGroup) GroupSurface else PlayerSurface,
        border = BorderStroke(1.dp, if (focused) (if (entry.isGroup) GroupAccent else PlayerAccent).copy(alpha = 0.56f) else PlayerOutline),
        shadowElevation = if (focused) 4.dp else 0.dp,
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 4.dp)) {
            Row(
                Modifier.fillMaxWidth().height(contentRowHeight),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(40.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
                        if (entry.isGroup) GroupArtwork(presentation.artworkUri, 40) else Artwork(presentation.artworkUri, 40)
                    }
                    if (focused && total > 1) Text(
                        "$position/$total",
                        modifier = Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp)
                            .background(Color(0xCC171720), CircleShape).padding(horizontal = 4.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    else if (!focused && total > 1) Text(
                        compactDeviceMarker(entry),
                        modifier = Modifier.align(Alignment.TopStart).offset(2.dp, 2.dp)
                            .background(Color(0xCC171720), CircleShape).padding(horizontal = 5.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(
                        identity,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                        fontWeight = FontWeight.SemiBold,
                        color = if (entry.isGroup) GroupAccent else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        presentation.title.ifBlank { "Choose a song" },
                        style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (artist.isNotBlank()) Text(
                        artist,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (presentation.hasExceptionalCompactStatus()) Text(
                        status,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (focused) FilledIconButton(
                    enabled = canPlay,
                    modifier = Modifier.size(48.dp),
                    onClick = onPlay,
                    colors = if (entry.isGroup) IconButtonDefaults.filledIconButtonColors(
                        containerColor = GroupAccent,
                        contentColor = Color(0xFF163331),
                    ) else IconButtonDefaults.filledIconButtonColors(),
                ) {
                    if (presentation.buffering) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(if (presentation.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        "${if (presentation.playing) "Pause" else "Play"} on $identity")
                } else Spacer(Modifier.size(48.dp))
            }
            if (focused) {
                Row(
                    Modifier.fillMaxWidth().height(48.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(enabled = transportEnabled, modifier = Modifier.size(48.dp), onClick = onPrevious) {
                        Icon(Icons.Default.SkipPrevious, "Previous track on $identity")
                    }
                    PlayerSeekBar(
                        vm = vm,
                        presentation = presentation,
                        modifier = Modifier.weight(1f),
                        enabled = transportEnabled,
                        onScrubbingChanged = onScrubbingChanged,
                        colors = if (entry.isGroup) SliderDefaults.colors(
                            thumbColor = GroupAccent,
                            activeTrackColor = GroupAccent,
                            inactiveTrackColor = Color(0xFF4A4652),
                        ) else SliderDefaults.colors(),
                    )
                    IconButton(enabled = canNext, modifier = Modifier.size(48.dp), onClick = onNext) {
                        Icon(Icons.Default.SkipNext, "Next track on $identity")
                    }
                }
            } else Spacer(Modifier.fillMaxWidth().height(48.dp))
        }
    }
}

private fun compactPlayerStatus(entry: PlayerDeckEntry): String = when {
    entry.presentation.reconnecting -> "Reconnecting"
    entry.isGroup && entry.presentation.group?.status == dev.echo.data.firebase.GroupV2Status.PREPARING -> "Preparing"
    entry.isGroup && entry.presentation.group?.members?.any { it.status == dev.echo.data.firebase.GroupMemberV2Status.BUFFERING } == true ->
        "Catching up"
    entry.isGroup && entry.presentation.buffering -> "Buffering"
    entry.isGroup && entry.presentation.playing -> "Playing"
    entry.isGroup -> "Ready"
    entry.presentation.buffering -> "Buffering"
    entry.presentation.playing -> "Playing"
    entry.presentation.current != null || entry.presentation.remote?.player?.trackId != null -> "Paused"
    else -> "Ready"
}

private fun PlayerPresentationState.hasExceptionalCompactStatus(): Boolean =
    reconnecting || buffering || group?.status == dev.echo.data.firebase.GroupV2Status.PREPARING ||
        group?.members?.any { it.status == dev.echo.data.firebase.GroupMemberV2Status.BUFFERING } == true

private fun compactPlayerIdentity(entry: PlayerDeckEntry): String = if (entry.isGroup) {
    "Together · ${entry.presentation.group?.members?.count {
        it.status != dev.echo.data.firebase.GroupMemberV2Status.LEFT
    } ?: 0} devices"
} else entry.deviceLabel

private fun compactDeviceMarker(entry: PlayerDeckEntry): String = if (entry.isGroup) "T"
    else entry.deviceLabel.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"

@Composable
private fun GroupArtwork(uri: String?, size: Int) {
    var fallback by remember(uri) { mutableStateOf(uri.isNullOrBlank()) }
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Color(0xFF294943)),
        contentAlignment = Alignment.Center,
    ) {
        if (fallback) Icon(Icons.Default.MusicNote, null, tint = GroupAccent, modifier = Modifier.size(20.dp))
        else AsyncImage(
            model = uri,
            contentDescription = null,
            modifier = Modifier.fillMaxSize().clip(CircleShape),
            contentScale = ContentScale.Crop,
            onError = { fallback = true },
        )
    }
}

private fun playerCardDescription(
    entry: PlayerDeckEntry,
    position: Int,
    total: Int,
    next: Boolean = false,
): String = buildString {
    if (next) append("Next player: ")
    append(compactPlayerIdentity(entry)).append(", ").append(entry.presentation.title)
    entry.presentation.artist.takeIf(String::isNotBlank)?.let { append(", ").append(it) }
    append(", ").append(playerStatus(entry)).append(", player ").append(position).append(" of ").append(total)
}

private fun playerStatus(entry: PlayerDeckEntry): String = when {
    entry.presentation.reconnecting -> "Reconnecting"
    entry.isGroup && entry.presentation.group?.status == dev.echo.data.firebase.GroupV2Status.PREPARING -> "Preparing"
    entry.isGroup && entry.presentation.group?.members?.any { it.status == dev.echo.data.firebase.GroupMemberV2Status.BUFFERING } == true -> "Catching up"
    entry.isGroup -> if (entry.presentation.playing) "Playing together" else "Shared player ready"
    entry.presentation.playing -> "Playing"
    entry.presentation.current != null || entry.presentation.remote?.player?.trackId != null -> "Paused"
    else -> "Ready to play"
}
