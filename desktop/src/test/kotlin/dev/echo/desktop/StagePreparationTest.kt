package dev.echo.desktop

import dev.echo.core.Track
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class StagePreparationTest {
    @Test fun stagePreparationWindowKeepsActualDuplicateOrderAndRepeatAllWrap() {
        val a = song("aaaaaaaaaaa"); val b = song("bbbbbbbbbbb"); val c = song("ccccccccccc")
        val d = song("ddddddddddd"); val e = song("eeeeeeeeeee")
        val five = DesktopPlaybackState(queue = listOf(a, b, c, d, e), index = 2, repeatMode = DesktopRepeatMode.ALL)
        assertEquals(listOf(b, a, c, d, e), stagePreparationWindow(five))
        assertEquals(listOf(d, e, c, b, a), stagePreparationPriority(five))
        assertEquals(listOf(d, e, c, b, a), stagePreparationPriority(five.copy(repeatMode = DesktopRepeatMode.OFF)))
        val duplicates = DesktopPlaybackState(queue = listOf(a, a, b), index = 1)
        assertEquals(listOf(a, a, b), stagePreparationWindow(duplicates))
        val wrapped = DesktopPlaybackState(queue = listOf(a, b, c), index = 2, repeatMode = DesktopRepeatMode.ALL)
        assertEquals(listOf(b, a, c, a, b), stagePreparationWindow(wrapped))
        val noWrap = wrapped.copy(repeatMode = DesktopRepeatMode.OFF)
        assertEquals(listOf(b, a, c), stagePreparationWindow(noWrap))
        val repeatOne = DesktopPlaybackState(queue = listOf(a, b, c), index = 1, repeatMode = DesktopRepeatMode.ONE)
        assertEquals(listOf(a, b, c), stagePreparationWindow(repeatOne))
    }

    @Test fun stagePreparationFenceTracksWindowAndEligibilityButIgnoresRoutineTransportChanges() {
        val a = song("aaaaaaaaaaa"); val b = song("bbbbbbbbbbb")
        val original = DesktopPlaybackState(queue = listOf(a, b, a), index = 0, occurrence = 7,
            queueRevision = 4, transportRevision = 9)
        val fence = stagePreparationFence(original, targetEnabled = true, wantsPlaying = true)
        assertTrue(stagePreparationFenceIsCurrent(fence, original.copy(positionMs = 500)))
        assertFalse(stagePreparationFenceIsCurrent(fence, original, targetEnabled = false, wantsPlaying = true))
        assertFalse(stagePreparationFenceIsCurrent(fence, original, targetEnabled = true, wantsPlaying = true, offlineOnly = true))
        assertFalse(stagePreparationFenceIsCurrent(fence, original, targetEnabled = true, wantsPlaying = true, connectedGroup = true))
        assertTrue(stagePreparationFenceIsCurrent(fence, original.copy(queueRevision = 5)))
        assertTrue(stagePreparationFenceIsCurrent(fence, original.copy(index = 1, occurrence = 8)))
        assertTrue(stagePreparationFenceIsCurrent(fence, original.copy(transportRevision = 10)))
        assertTrue(stagePreparationFenceIsCurrent(fence, original.copy(loading = true)))
        assertFalse(stagePreparationFenceIsCurrent(fence, original.copy(queue = listOf(b, a, a))))
    }

    @Test fun stagePreparationFlowKeepsWorkAcrossPauseSeekAndVideoChangesButRestartsForWindowChanges() = runBlocking {
        val initial = DesktopPlaybackState(queue = listOf(song("aaaaaaaaaaa"), song("bbbbbbbbbbb"),
            song("ccccccccccc"), song("ddddddddddd"), song("eeeeeeeeeee")), index = 0, loading = true,
            occurrence = 1, queueRevision = 1)
        val playback = MutableStateFlow(initial)
        val starts = mutableListOf<StagePreparationFlowKey>()
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val firstCanceled = CompletableDeferred<Unit>()
        var canceled = 0
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            playback.distinctUntilChangedBy { stagePreparationFlowKey(it, 0, true, true, false, false) }
                .collectLatest { state ->
                    if (shouldPrepareStageMedia(state, targetEnabled = true, wantsPlaying = true,
                            offlineOnly = false, connectedGroup = false)) {
                        starts += stagePreparationFlowKey(state, 0, true, true, false, false)
                        if (starts.size == 1) firstStarted.complete(Unit)
                        if (starts.size == 2) secondStarted.complete(Unit)
                        try { awaitCancellation() } finally { canceled++; firstCanceled.complete(Unit) }
                    }
                }
        }
        playback.value = initial.copy(loading = false, playing = true)
        withTimeout(1_000) { firstStarted.await() }
        assertEquals("loading alone must not start speculation", 1, starts.size)
        playback.value = initial.copy(loading = false, playing = false, positionMs = 500, transportRevision = 2,
            videoMode = true)
        yield()
        assertEquals("pause, seek and video mode must keep the same preparation plan", 1, starts.size)
        assertEquals("routine actions must not cancel in-flight preparation", 0, canceled)
        assertTrue("paused, resolved tracks remain eligible", shouldPrepareStageMedia(playback.value,
            targetEnabled = true, offlineOnly = false, connectedGroup = false))
        playback.value = playback.value.copy(index = 1, occurrence = 2, queueRevision = 2)
        withTimeout(1_000) { firstCanceled.await() }
        withTimeout(1_000) { secondStarted.await() }
        assertEquals("a changed five-position window starts an updated plan", 2, starts.size)
        collector.cancelAndJoin()
    }

    @Test fun stagePreparationEligibilityWaitsForCurrentResolutionButAllowsPause() {
        val current = DesktopPlaybackState(queue = listOf(song("aaaaaaaaaaa")), index = 0, playing = false)
        assertTrue(shouldPrepareStageMedia(current, targetEnabled = true, offlineOnly = false, connectedGroup = false))
        assertFalse(shouldPrepareStageMedia(current.copy(loading = true), targetEnabled = true,
            offlineOnly = false, connectedGroup = false))
        assertFalse(shouldPrepareStageMedia(current.copy(ended = true), targetEnabled = true,
            offlineOnly = false, connectedGroup = false))
        assertFalse(shouldPrepareStageMedia(current, targetEnabled = false, offlineOnly = false, connectedGroup = false))
        assertFalse(shouldPrepareStageMedia(current, targetEnabled = true, offlineOnly = true, connectedGroup = false))
        assertFalse(shouldPrepareStageMedia(current, targetEnabled = true, offlineOnly = false, connectedGroup = true))
    }

    @Test fun productionPreparationWorkerRetainsOverlapAndCancelsWhenOffline() = runBlocking {
        val tracks = listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc", "ddddddddddd", "eeeeeeeeeee", "ffffffffff1")
            .map(::song)
        fun plan(state: DesktopPlaybackState, offline: Boolean = false) = StagePreparationPlan(
            fence = stagePreparationFence(state, offlineOnly = offline),
            eligible = shouldPrepareStageMedia(state, targetEnabled = true, offlineOnly = offline),
            reconcile = !offline && !state.ended,
            window = if (offline) emptyList() else stagePreparationWindow(state).filter(::stageCacheableTrack),
            order = if (offline || state.loading || state.ended) emptyList() else stagePreparationPriority(state)
                .filter(::stageCacheableTrack).distinctBy { it.id },
            slots = stageBufferPositions(state),
        )

        val initial = DesktopPlaybackState(queue = tracks, index = 2, playing = false)
        val desired = MutableStateFlow<StagePreparationPlan?>(plan(initial))
        val firstStarted = CompletableDeferred<Unit>()
        val allowFirstFinish = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val secondCanceled = CompletableDeferred<Unit>()
        val ready = mutableSetOf<String>()
        val starts = mutableListOf<String>()
        var canceled = 0
        var token = 0L
        val worker = launch {
            runStagePreparationWorker(
                desired = desired,
                videoEnabled = false,
                isReady = { track, _ -> track.id in ready },
                beginTransfer = { _, _ -> ++token },
                reportProgress = { _, _, _, _ -> },
                finishTransfer = { track, _, _, phase -> if (phase == StageBufferTransferPhase.READY) ready += track.id },
                prepare = { track, _, _ ->
                    starts += track.id
                    if (starts.size == 1) {
                        firstStarted.complete(Unit)
                        try {
                            allowFirstFinish.await()
                            ready += track.id
                            true
                        } finally {
                            if (!allowFirstFinish.isCompleted) { canceled++; secondCanceled.complete(Unit) }
                        }
                    } else {
                        secondStarted.complete(Unit)
                        try { awaitCancellation() } finally { canceled++; secondCanceled.complete(Unit) }
                    }
                },
                afterTransfer = { _, _ -> },
            )
        }
        try {
            withTimeout(1_000) { firstStarted.await() }
            val pausedAndSought = initial.copy(positionMs = 8_000, transportRevision = 4, playing = false, volume = 0.2f)
            desired.value = plan(pausedAndSought)
            yield()
            assertEquals("routine playback changes keep the active transfer", 0, canceled)

            val rolled = pausedAndSought.copy(index = 3, occurrence = 2, queueRevision = 2)
            assertTrue("the active recording remains inside the rolled window", stagePreparationWindow(rolled).any { it.id == tracks[3].id })
            desired.value = plan(rolled)
            yield()
            assertEquals("overlapping window roll keeps the transfer", 0, canceled)
            allowFirstFinish.complete(Unit)
            withTimeout(1_000) { secondStarted.await() }
            assertEquals("worker proceeds using the latest plan", tracks[4].id, starts.last())

            desired.value = plan(rolled, offline = true)
            withTimeout(1_000) { secondCanceled.await() }
            assertEquals("offline eligibility cancels active speculative work", 1, canceled)
        } finally {
            worker.cancelAndJoin()
        }
    }

    @Test fun productionWorkerUsesPerTrackAudioThenVideoPriorityOnlyInVideoModes() = runBlocking {
        val tracks = listOf(song("aaaaaaaaaaa"), song("bbbbbbbbbbb"))
        val state = DesktopPlaybackState(queue = tracks, index = 0)

        suspend fun run(videoFirst: Boolean): List<Pair<String, Boolean>> {
            val plan = StagePreparationPlan(stagePreparationFence(state), eligible = true, reconcile = true,
                window = tracks, order = tracks, slots = stageBufferPositions(state), videoFirst = videoFirst)
            val desired = MutableStateFlow<StagePreparationPlan?>(plan)
            val ready = mutableSetOf<Pair<String, Boolean>>()
            val starts = mutableListOf<Pair<String, Boolean>>()
            val allStarted = CompletableDeferred<Unit>()
            var token = 0L
            val worker = launch {
                runStagePreparationWorker(
                    desired, videoEnabled = true,
                    isReady = { track, isVideo -> (track.id to isVideo) in ready },
                    beginTransfer = { _, _ -> ++token },
                    reportProgress = { _, _, _, _ -> },
                    finishTransfer = { track, isVideo, _, phase ->
                        if (phase == StageBufferTransferPhase.READY) ready += track.id to isVideo
                    },
                    prepare = { track, isVideo, _ ->
                        starts += track.id to isVideo
                        if (starts.size == tracks.size * 2) allStarted.complete(Unit)
                        true
                    },
                    afterTransfer = { _, _ -> },
                )
            }
            try {
                withTimeout(1_000) { allStarted.await() }
                return starts.toList()
            } finally { worker.cancelAndJoin() }
        }

        assertEquals(listOf(tracks[0].id to false, tracks[1].id to false,
            tracks[0].id to true, tracks[1].id to true), run(videoFirst = false))
        assertEquals(listOf(tracks[0].id to false, tracks[0].id to true,
            tracks[1].id to false, tracks[1].id to true), run(videoFirst = true))
    }

    @Test fun changingVideoPriorityKeepsTheInFlightTransferAndChangesNextSelection() = runBlocking {
        val tracks = listOf(song("aaaaaaaaaaa"), song("bbbbbbbbbbb"))
        val state = DesktopPlaybackState(queue = tracks, index = 0)
        fun plan(videoFirst: Boolean) = StagePreparationPlan(stagePreparationFence(state), eligible = true,
            reconcile = true, window = tracks, order = tracks, slots = stageBufferPositions(state), videoFirst = videoFirst)
        val desired = MutableStateFlow<StagePreparationPlan?>(plan(videoFirst = false))
        val starts = mutableListOf<Pair<String, Boolean>>()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val ready = mutableSetOf<Pair<String, Boolean>>()
        var canceled = 0
        var token = 0L
        val worker = launch {
            runStagePreparationWorker(
                desired, videoEnabled = true,
                isReady = { track, isVideo -> (track.id to isVideo) in ready },
                beginTransfer = { _, _ -> ++token },
                reportProgress = { _, _, _, _ -> },
                finishTransfer = { track, isVideo, _, phase ->
                    if (phase == StageBufferTransferPhase.READY) ready += track.id to isVideo
                },
                prepare = { track, isVideo, _ ->
                    starts += track.id to isVideo
                    if (starts.size == 1) {
                        firstStarted.complete(Unit)
                        try { releaseFirst.await() } finally { if (!releaseFirst.isCompleted) canceled++ }
                    } else if (starts.size == 2) secondStarted.complete(Unit)
                    true
                },
                afterTransfer = { _, _ -> },
            )
        }
        try {
            withTimeout(1_000) { firstStarted.await() }
            desired.value = plan(videoFirst = true)
            yield()
            assertEquals("priority changes must not cancel active in-window work", 0, canceled)
            releaseFirst.complete(Unit)
            withTimeout(1_000) { secondStarted.await() }
            assertEquals(listOf(tracks[0].id to false, tracks[0].id to true), starts)
        } finally { worker.cancelAndJoin() }
    }

    @Test fun disablingVideoCachingCancelsOnlyTheInFlightVideoPreparation() = runBlocking {
        val tracks = listOf(song("aaaaaaaaaaa"))
        val state = DesktopPlaybackState(queue = tracks, index = 0)
        fun plan(videoCachingEnabled: Boolean) = StagePreparationPlan(stagePreparationFence(state), eligible = true,
            reconcile = true, window = tracks, order = tracks, slots = stageBufferPositions(state),
            videoCachingEnabled = videoCachingEnabled)
        val desired = MutableStateFlow<StagePreparationPlan?>(plan(videoCachingEnabled = true))
        val videoStarted = CompletableDeferred<Unit>()
        val videoCanceled = CompletableDeferred<Unit>()
        val ready = mutableSetOf<Pair<String, Boolean>>(tracks.single().id to false)
        var videoRequests = 0
        var token = 0L
        val worker = launch {
            runStagePreparationWorker(
                desired, videoEnabled = true,
                isReady = { track, isVideo -> (track.id to isVideo) in ready },
                beginTransfer = { _, _ -> ++token },
                reportProgress = { _, _, _, _ -> },
                finishTransfer = { _, _, _, _ -> },
                prepare = { _, isVideo, _ ->
                    if (isVideo) {
                        videoRequests++
                        videoStarted.complete(Unit)
                        try { awaitCancellation() } finally { videoCanceled.complete(Unit) }
                    } else true
                },
                afterTransfer = { _, _ -> },
            )
        }
        try {
            withTimeout(1_000) { videoStarted.await() }
            desired.value = plan(videoCachingEnabled = false)
            withTimeout(1_000) { videoCanceled.await() }
            delay(50)
            assertEquals("audio-only mode must not restart video preparation", 1, videoRequests)
        } finally { worker.cancelAndJoin() }
    }

    @Test fun productionPreparationWorkerMarksUnavailableVideoWithoutRetrySpin() = runBlocking {
        val track = song("aaaaaaaaaaa")
        val state = DesktopPlaybackState(queue = listOf(track), index = 0)
        val plan = StagePreparationPlan(stagePreparationFence(state), eligible = true, reconcile = true,
            window = listOf(track), order = listOf(track), slots = stageBufferPositions(state))
        val desired = MutableStateFlow<StagePreparationPlan?>(plan)
        val unavailable = CompletableDeferred<Unit>()
        var requests = 0
        var finalPhase: StageBufferTransferPhase? = null
        var token = 0L
        val worker = launch {
            runStagePreparationWorker(
                desired, videoEnabled = true,
                isReady = { _, isVideo -> !isVideo },
                beginTransfer = { _, _ -> ++token },
                reportProgress = { _, _, _, _ -> },
                finishTransfer = { _, isVideo, _, phase ->
                    if (isVideo && phase == StageBufferTransferPhase.UNAVAILABLE) {
                        finalPhase = phase
                        unavailable.complete(Unit)
                    }
                },
                prepare = { _, isVideo, report ->
                    if (!isVideo) true else {
                        requests++
                        report(StageCacheProgress(StageBufferTransferPhase.UNAVAILABLE))
                        false
                    }
                },
                afterTransfer = { _, _ -> },
            )
        }
        try {
            withTimeout(1_000) { unavailable.await() }
            delay(100)
            assertEquals("unavailable video should not be requested repeatedly for one plan", 1, requests)
            assertEquals(StageBufferTransferPhase.UNAVAILABLE, finalPhase)
        } finally { worker.cancelAndJoin() }
    }

    @Test fun productionPreparationWorkerRetriesPreemptedExtractionWithBackoff() = runBlocking {
        val track = song("aaaaaaaaaaa")
        val state = DesktopPlaybackState(queue = listOf(track), index = 0)
        val plan = StagePreparationPlan(stagePreparationFence(state), eligible = true, reconcile = true,
            window = listOf(track), order = listOf(track), slots = stageBufferPositions(state))
        val desired = MutableStateFlow<StagePreparationPlan?>(plan)
        val succeeded = CompletableDeferred<Unit>()
        var ready = false
        var requests = 0
        var token = 0L
        var secondAttemptAtMs = 0L
        val startedAtMs = System.nanoTime() / 1_000_000L
        val worker = launch {
            runStagePreparationWorker(
                desired, videoEnabled = false,
                isReady = { _, _ -> ready },
                beginTransfer = { _, _ -> ++token },
                reportProgress = { _, _, _, _ -> },
                finishTransfer = { _, _, _, phase -> if (phase == StageBufferTransferPhase.READY) succeeded.complete(Unit) },
                prepare = { _, _, _ ->
                    requests++
                    if (requests == 1) throw CancellationException("foreground resolver preempted speculation")
                    secondAttemptAtMs = System.nanoTime() / 1_000_000L
                    ready = true
                    true
                },
                afterTransfer = { _, _ -> },
            )
        }
        try {
            delay(100)
            assertEquals("preemption must not cause a tight retry loop", 1, requests)
            withTimeout(2_000) { succeeded.await() }
            assertEquals(2, requests)
            assertTrue("retry is delayed after speculative preemption", secondAttemptAtMs - startedAtMs >= 250)
        } finally { worker.cancelAndJoin() }
    }
    @Test fun liveCaptureDoesNotBlockNextSongAndDiscardCanRetry() = runBlocking {
        val current = song("aaaaaaaaaaa"); val next = song("bbbbbbbbbbb")
        val state = DesktopPlaybackState(queue = listOf(current, next), index = 0)
        val base = StagePreparationPlan(stagePreparationFence(state), eligible = true, reconcile = true,
            window = listOf(current, next), order = listOf(current, next), slots = stageBufferPositions(state))
        val desired = MutableStateFlow<StagePreparationPlan?>(base)
        val nextPrepared = CompletableDeferred<Unit>()
        val currentPrepared = CompletableDeferred<Unit>()
        val ready = mutableSetOf<String>()
        var capturing = true
        var currentAttempts = 0
        val worker = launch {
            runStagePreparationWorker(desired, videoEnabled = false,
                isReady = { track, _ -> track.id in ready || (track.id == current.id && capturing) },
                beginTransfer = { _, _ -> 1L }, reportProgress = { _, _, _, _ -> },
                finishTransfer = { track, _, _, phase -> if (phase == StageBufferTransferPhase.READY) {
                    ready += track.id
                    if (track.id == next.id) nextPrepared.complete(Unit) else currentPrepared.complete(Unit)
                } },
                prepare = { track, _, _ ->
                    if (track.id == current.id && currentAttempts++ == 0) throw StageStreamCaptureDiscarded(track.id)
                    true
                }, afterTransfer = { _, _ -> })
        }
        try {
            withTimeout(1_000) { nextPrepared.await() }
            assertEquals(0, currentAttempts)
            capturing = false
            desired.value = base.copy(refreshVersion = 1)
            withTimeout(2_000) { currentPrepared.await() }
            assertEquals(2, currentAttempts)
        } finally { worker.cancelAndJoin() }
    }

    @Test fun offlineModeStopsTransientCachedAudioAsWellAsStreams() {
        assertTrue(stopDesktopPlaybackForOffline(true, false, true))
        assertTrue(stopDesktopPlaybackForOffline(true, true, false))
        assertFalse(stopDesktopPlaybackForOffline(true, false, false))
        assertFalse(stopDesktopPlaybackForOffline(false, true, true))
    }
    @Test fun activeNetworkVideoSuspendsSpeculationButPauseAndLocalMediaResumeIt() {
        val networkVideo = DesktopPlaybackState(queue = listOf(song("aaaaaaaaaaa")), index = 0, videoMode = true)
        assertTrue("video switching pauses speculative transfers", shouldSuspendStageSpeculation(networkVideo.copy(videoLoading = true),
            wantsPlaying = true, localAudio = true, localVideo = true, videoNeedsAudio = false))
        assertTrue(shouldSuspendStageSpeculation(networkVideo, wantsPlaying = true,
            localAudio = false, localVideo = false, videoNeedsAudio = false))
        assertTrue("video with a network audio slave also yields", shouldSuspendStageSpeculation(networkVideo,
            wantsPlaying = true, localAudio = false, localVideo = false, videoNeedsAudio = true))
        assertTrue("a local video with a network audio slave still yields", shouldSuspendStageSpeculation(networkVideo,
            wantsPlaying = true, localAudio = false, localVideo = true, videoNeedsAudio = true))
        assertFalse("pause resumes speculation", shouldSuspendStageSpeculation(networkVideo.copy(playing = false),
            wantsPlaying = false, localAudio = false, localVideo = false, videoNeedsAudio = false))
        assertFalse("local video resumes speculation", shouldSuspendStageSpeculation(networkVideo,
            wantsPlaying = true, localAudio = false, localVideo = true, videoNeedsAudio = false))
        assertFalse("fully local video and slave resume speculation", shouldSuspendStageSpeculation(networkVideo,
            wantsPlaying = true, localAudio = true, localVideo = true, videoNeedsAudio = true))
        assertFalse("video mode is required", shouldSuspendStageSpeculation(networkVideo.copy(videoMode = false),
            wantsPlaying = true, localAudio = false, localVideo = false, videoNeedsAudio = true))
    }

    @Test fun networkVideoPlanCancelsEitherSpeculativeTransferAndResumesAfterPause() = runBlocking {
        val track = song("aaaaaaaaaaa")
        val state = DesktopPlaybackState(queue = listOf(track), index = 0, videoMode = true)
        suspend fun verify(isVideo: Boolean) {
            fun plan(speculationEnabled: Boolean) = StagePreparationPlan(stagePreparationFence(state), eligible = true,
                reconcile = true, window = listOf(track), order = listOf(track), slots = stageBufferPositions(state),
                speculationEnabled = speculationEnabled)
            val desired = MutableStateFlow<StagePreparationPlan?>(plan(speculationEnabled = true))
            val started = CompletableDeferred<Unit>()
            val canceled = CompletableDeferred<Unit>()
            val resumed = CompletableDeferred<Unit>()
            var requests = 0
            var token = 0L
            val ready = mutableSetOf(track.id to !isVideo)
            val worker = launch {
                runStagePreparationWorker(
                    desired, videoEnabled = true,
                    isReady = { candidate, video -> (candidate.id to video) in ready },
                    beginTransfer = { _, _ -> ++token },
                    reportProgress = { _, _, _, _ -> },
                    finishTransfer = { _, _, _, _ -> },
                    prepare = { _, video, _ ->
                        requests++
                        if (requests == 1) started.complete(Unit) else resumed.complete(Unit)
                        if (video == isVideo) try { awaitCancellation() } finally { canceled.complete(Unit) }
                        true
                    },
                    afterTransfer = { _, _ -> },
                )
            }
            try {
                withTimeout(1_000) { started.await() }
                val suspended = plan(speculationEnabled = false)
                assertTrue("suspension keeps the existing window reconciled", suspended.reconcile)
                assertEquals(listOf(track), suspended.window)
                desired.value = suspended
                withTimeout(1_000) { canceled.await() }
                delay(50)
                assertEquals("no new speculative request starts during network video", 1, requests)
                desired.value = plan(speculationEnabled = true)
                withTimeout(1_000) { resumed.await() }
                assertEquals(2, requests)
            } finally { worker.cancelAndJoin() }
        }
        verify(isVideo = false)
        verify(isVideo = true)
    }

    @Test fun trackerShowsFixedOffsetsAndKeepsDuplicateOccurrences() {
        val a = song("aaaaaaaaaaa"); val b = song("bbbbbbbbbbb")
        val state = DesktopPlaybackState(queue = listOf(a, a, b), index = 1)
        assertEquals(listOf(-2, -1, 0, 1, 2), stageBufferPositions(state).map { it.first })
        assertEquals(listOf(null, a, a, b, null), stageBufferPositions(state).map { it.second })
        val wrapped = state.copy(index = 0, repeatMode = DesktopRepeatMode.ALL)
        assertEquals(listOf(a, b, a, a, b), stageBufferPositions(wrapped).map { it.second })
    }
    @Test fun onlyBufferingNetworkPlaybackYieldsBandwidth() {
        assertTrue(stageShouldYieldToPlayback(true, true, false, true, false, false))
        assertTrue(stageShouldYieldToPlayback(true, true, true, false, true, false))
        assertTrue(stageShouldYieldToPlayback(true, true, true, true, false, true))
        assertFalse(stageShouldYieldToPlayback(true, false, false, true, false, false))
        assertFalse(stageShouldYieldToPlayback(false, true, false, true, false, false))
        assertFalse(stageShouldYieldToPlayback(true, true, true, true, false, false))
        assertFalse(stageShouldYieldToPlayback(true, true, false, false, false, false))
    }
    private fun song(id: String) = Track("youtube:$id", id, "Artist", provider = "youtube", sourceUri = "https://www.youtube.com/watch?v=$id")
}
