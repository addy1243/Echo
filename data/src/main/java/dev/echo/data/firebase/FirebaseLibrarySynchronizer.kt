package dev.echo.data.firebase

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Local state is already sanitized; callers apply the result under their own local edit guard. */
suspend fun synchronizeFirebaseLibrary(
    cloud: FirebaseLibraryCloud,
    session: FirebaseSession,
    baseline: LibrarySyncState?,
    local: LibrarySyncState,
    importDeviceHistory: Boolean = false,
    checkAccount: () -> Unit,
): LibrarySyncState {
    val now = System.currentTimeMillis()
    repeat(3) {
        currentCoroutineContext().ensureActive()
        checkAccount()
        val document = cloud.read(session)
        currentCoroutineContext().ensureActive()
        checkAccount()
        val remote = document.json?.let(::decodeLibrarySyncState) ?: LibrarySyncState()
        val effectiveBaseline = if (document.json == null) null else baseline
        // First enable/account changes import additively; old device clears must not erase this account.
        val intended = if (effectiveBaseline == null) local.copy(deletedPlaylists = emptySet(), unlikedTracks = emptySet(),
            historyEpoch = remote.historyEpoch,
            history = if (remote.historyEpoch.counter == 0L || local.historyEpoch == remote.historyEpoch || importDeviceHistory)
                local.history else emptyList()) else local
        val merged = mergeLibrarySyncStates(effectiveBaseline, intended, remote, now)
        val encoded = encodeLibrarySyncState(merged)
        if (encoded == encodeLibrarySyncState(remote)) {
            currentCoroutineContext().ensureActive()
            checkAccount()
            return merged
        }
        currentCoroutineContext().ensureActive()
        checkAccount()
        if (cloud.write(session, document, encoded)) {
            currentCoroutineContext().ensureActive()
            checkAccount()
            return merged
        }
    }
    error("The cloud library changed during sync. Local data was kept; try again.")
}
