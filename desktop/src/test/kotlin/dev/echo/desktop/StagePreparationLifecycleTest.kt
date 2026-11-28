package dev.echo.desktop

import dev.echo.core.Track
import dev.echo.core.PlayableSource
import dev.echo.data.provider.YouTubeVideoSelection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class StagePreparationLifecycleTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun song(id: String) = Track("youtube:$id", id, "Artist", provider = "youtube")

    private fun state() = DesktopPlaybackState(
        queue = listOf(song("aaaaaaaaaaa"), song("bbbbbbbbbbb"), song("ccccccccccc"),
            song("ddddddddddd"), song("eeeeeeeeeee"), song("ffffffffff1"), song("gggggggggg1")),
        index = 3, playing = true, occurrence = 1, queueRevision = 1,
    )

    @Test fun pauseSeekVolumeAndVideoChangesKeepTheSamePreparationPlan() {
        val original = state()
        val key = stagePreparationFlowKey(original, version = 0, targetEnabled = true)
        val routineChanges = listOf(
            original.copy(playing = false),
            original.copy(playing = false, positionMs = 23_000, transportRevision = 4),
            original.copy(volume = 0.15f),
            original.copy(videoMode = true),
            original.copy(playing = false, positionMs = 23_000, transportRevision = 4,
                volume = 0.15f, videoMode = true),
        )

        assertTrue(shouldPrepareStageMedia(original, targetEnabled = true))
        routineChanges.forEach { changed ->
            assertEquals(stagePreparationFence(original), stagePreparationFence(changed))
            assertEquals(key, stagePreparationFlowKey(changed, version = 0, targetEnabled = true))
            assertTrue("pause must not suspend preparation", shouldPrepareStageMedia(changed, targetEnabled = true))
        }
    }

    @Test fun planChangesOnlyWhenTheWindowOrItsEligibilityChanges() {
        val original = state()
        val fence = stagePreparationFence(original)
        val outsideWindow = original.queue.toMutableList().also {
            it[0] = song("hhhhhhhhhhh")
            it[6] = song("iiiiiiiiiii")
        }
        assertTrue(stagePreparationFenceIsCurrent(fence, original.copy(queue = outsideWindow,
            queueRevision = original.queueRevision + 1)))

        val insideWindow = original.queue.toMutableList().also { it[4] = song("jjjjjjjjjjj") }
        assertFalse(stagePreparationFenceIsCurrent(fence, original.copy(queue = insideWindow,
            queueRevision = original.queueRevision + 1)))
        assertFalse(stagePreparationFenceIsCurrent(fence, original.copy(index = 4)))
        assertFalse(stagePreparationFenceIsCurrent(fence, original, targetEnabled = false))
        assertFalse(stagePreparationFenceIsCurrent(fence, original, offlineOnly = true))
        assertFalse(stagePreparationFenceIsCurrent(fence, original, connectedGroup = true))

        assertFalse(shouldPrepareStageMedia(original, targetEnabled = false))
        assertFalse(shouldPrepareStageMedia(original, targetEnabled = true, offlineOnly = true))
        assertFalse(shouldPrepareStageMedia(original, targetEnabled = true, connectedGroup = true))
    }

    @Test fun sameWindowStateUpdatesDoNotCancelActiveWorkButScopeAndWindowChangesDo() = runBlocking {
        val playback = MutableStateFlow(state())
        val started = CompletableDeferred<Unit>()
        val canceled = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        var starts = 0
        var progress = 0
        var cancellations = 0

        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            playback.distinctUntilChangedBy { stagePreparationFlowKey(it, 0, targetEnabled = true) }
                .collectLatest { current ->
                    if (!shouldPrepareStageMedia(current, targetEnabled = true)) return@collectLatest
                    starts++
                    try {
                        progress++
                        if (starts == 1) started.complete(Unit) else secondStarted.complete(Unit)
                        kotlinx.coroutines.awaitCancellation()
                    } finally {
                        cancellations++
                        canceled.complete(Unit)
                    }
                }
        }
        withTimeout(1_000) { started.await() }

        val initial = playback.value
        playback.value = initial.copy(playing = false)
        playback.value = playback.value.copy(positionMs = 12_000, transportRevision = 2)
        playback.value = playback.value.copy(volume = 0.2f)
        playback.value = playback.value.copy(videoMode = true)
        playback.value = playback.value.copy(queue = playback.value.queue.toMutableList().also {
            it[0] = song("hhhhhhhhhhh")
        }, queueRevision = 2)

        assertEquals(1, starts)
        assertEquals(0, cancellations)
        assertEquals(1, progress)

        val outsideWindow = playback.value.queue.toMutableList().also {
            it[6] = song("iiiiiiiiiii")
        }
        playback.value = playback.value.copy(queue = outsideWindow, queueRevision = 3)
        assertEquals(1, starts)
        assertEquals(0, cancellations)

        val replacement = playback.value.queue.toMutableList().also { it[4] = song("jjjjjjjjjjj") }
        playback.value = playback.value.copy(queue = replacement, queueRevision = 4)
        withTimeout(1_000) { secondStarted.await() }
        withTimeout(1_000) { canceled.await() }
        assertEquals(1, cancellations)
        assertEquals(2, starts)
        collector.cancelAndJoin()
    }

    @Test fun pausedWorkCanFinishAndOfflineOrDisabledTargetCancelsPendingWork() = runBlocking {
        val base = state().copy(playing = false)
        val key = stagePreparationFlowKey(base, 0, targetEnabled = true)
        val completed = CompletableDeferred<Unit>()
        val firstStarted = CompletableDeferred<Unit>()
        val pendingStarted = CompletableDeferred<Unit>()
        val pendingCanceled = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var starts = 0
        var cancellations = 0
        var completedWhilePaused = false
        val playback = MutableStateFlow(base)
        val enabled = MutableStateFlow(true)
        val offline = MutableStateFlow(false)

        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            kotlinx.coroutines.flow.combine(playback, enabled, offline) { state, target, offlineOnly ->
                Triple(state, target, offlineOnly)
            }.distinctUntilChangedBy { (state, target, offlineOnly) ->
                stagePreparationFlowKey(state, 0, target, offlineOnly = offlineOnly)
            }.collectLatest { (state, target, offlineOnly) ->
                if (!shouldPrepareStageMedia(state, targetEnabled = target, offlineOnly = offlineOnly)) return@collectLatest
                starts++
                try {
                    if (starts == 1) {
                        // The first transfer completes even though playback remains paused.
                        firstStarted.complete(Unit)
                        finish.await()
                        completedWhilePaused = !playback.value.playing
                        completed.complete(Unit)
                    } else {
                        pendingStarted.complete(Unit)
                        awaitCancellationForTest()
                    }
                } finally {
                    cancellations++
                    if (starts == 2) pendingCanceled.complete(Unit)
                }
            }
        }
        withTimeout(1_000) { firstStarted.await() }
        playback.value = base.copy(positionMs = 12_000, transportRevision = 5,
            volume = 0.2f, videoMode = true)
        assertEquals(1, starts)
        assertEquals(key, stagePreparationFlowKey(playback.value, 0, targetEnabled = true))
        finish.complete(Unit)
        withTimeout(1_000) { completed.await() }
        assertEquals(1, starts)
        assertTrue(completedWhilePaused)

        playback.value = playback.value.copy(queue = playback.value.queue.toMutableList().also {
            it[4] = song("jjjjjjjjjjj")
        }, queueRevision = 2)
        withTimeout(1_000) { pendingStarted.await() }
        offline.value = true
        withTimeout(1_000) { pendingCanceled.await() }
        assertEquals(2, starts)
        assertEquals(2, cancellations)
        assertFalse(shouldPrepareStageMedia(playback.value, targetEnabled = enabled.value, offlineOnly = offline.value))
        collector.cancelAndJoin()
    }

    @Test fun actualAudioAndVideoRequestsContinueAcrossPauseSeekVolumeAndVideoToggle() = runBlocking {
        val audioStarted = CountDownLatch(1)
        val videoStarted = CountDownLatch(1)
        val releaseAudio = CountDownLatch(1)
        val releaseVideo = CountDownLatch(1)
        val audioRequests = AtomicInteger()
        val videoRequests = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val isVideo = chain.request().url.queryParameter("kind") == "video"
            if (isVideo) videoRequests.incrementAndGet() else audioRequests.incrementAndGet()
            val startedLatch = if (isVideo) videoStarted else audioStarted
            val releaseLatch = if (isVideo) releaseVideo else releaseAudio
            val mime = if (isVideo) "video/webm" else "audio/webm"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(object : ResponseBody() {
                    override fun contentType() = mime.toMediaType()
                    override fun contentLength() = -1L
                    override fun source(): BufferedSource = object : ForwardingSource(Buffer().write(byteArrayOf(1, 2, 3, 4))) {
                        private var sent = false
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            if (sent) {
                                startedLatch.countDown()
                                check(releaseLatch.await(5, TimeUnit.SECONDS))
                                return -1
                            }
                            return super.read(sink, byteCount).also { if (it > 0) sent = true }
                        }
                        override fun close() { releaseLatch.countDown(); super.close() }
                    }.buffer()
                }).build()
        }.build()
        val gate = StageMediaTransferGate()
        val audio = DesktopStageMediaCache(
            { PlayableSource("https://rr1.googlevideo.com/videoplayback?kind=audio") },
            client, temporary.newFolder().toPath(), transferGate = gate,
        )
        val video = DesktopStageVideoCache(
            { YouTubeVideoSelection(PlayableSource("https://rr1.googlevideo.com/videoplayback?kind=video", mimeType = "video/webm"), false) },
            client, gate, temporary.newFolder().toPath(),
        )
        val playback = MutableStateFlow(state())
        val finished = CompletableDeferred<Unit>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            playback.distinctUntilChangedBy { stagePreparationFlowKey(it, 0, targetEnabled = true) }
                .collectLatest { current ->
                    if (!shouldPrepareStageMedia(current, targetEnabled = true)) return@collectLatest
                    withContext(Dispatchers.IO) { audio.prepare(current.current!!).close() }
                    withContext(Dispatchers.IO) { video.prepare(current.current!!)?.close() }
                    finished.complete(Unit)
                }
        }

        try {
            assertTrue(withContext(Dispatchers.IO) { audioStarted.await(2, TimeUnit.SECONDS) })
            val original = playback.value
            playback.value = original.copy(playing = false)
            playback.value = playback.value.copy(positionMs = 17_000, transportRevision = 2)
            playback.value = playback.value.copy(volume = 0.1f)
            assertEquals(1, audioRequests.get())
            releaseAudio.countDown()

            assertTrue(withContext(Dispatchers.IO) { videoStarted.await(2, TimeUnit.SECONDS) })
            playback.value = playback.value.copy(videoMode = true)
            playback.value = playback.value.copy(videoMode = false)
            assertEquals("audio must not be restarted after pause/seek/volume", 1, audioRequests.get())
            assertEquals("video must not be restarted when the display toggle changes", 1, videoRequests.get())
            releaseVideo.countDown()
            withTimeout(2_000) { finished.await() }
            assertEquals(1, audioRequests.get())
            assertEquals(1, videoRequests.get())
            assertTrue(audio.contains(playback.value.current!!.id))
            assertTrue(video.contains(playback.value.current!!.id))
        } finally {
            releaseAudio.countDown()
            releaseVideo.countDown()
            collector.cancelAndJoin()
            audio.close()
            video.close()
        }
    }

    private suspend fun awaitCancellationForTest(): Nothing = kotlinx.coroutines.awaitCancellation()
}
