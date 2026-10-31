package dev.echo.app.account

import android.os.Build
import android.database.sqlite.SQLiteDatabase
import android.content.ContentValues
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import dagger.hilt.android.EntryPointAccessors
import dev.echo.app.MainActivity
import dev.echo.app.EchoViewModel
import dev.echo.core.connected.NativePlaybackBridgeV2
import dev.echo.core.connected.EngineCapture
import dev.echo.core.connected.GroupRef
import dev.echo.core.connected.PortableRecording
import dev.echo.core.connected.QueueOccurrence
import dev.echo.core.connected.RepeatModeV2
import dev.echo.core.connected.TransferQueue
import dev.echo.data.firebase.ConnectedPlaybackV2Client
import dev.echo.data.firebase.EchoFirebaseProject
import dev.echo.data.firebase.FirebaseSession
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in paired test using the actual Media3 service player and a local demo RTDB emulator only. */
class ConnectedPlaybackV2NativeLiveTest {
    private fun milestone(message: String) {
        val line = "NATIVE_V2 [Android] $message"
        android.util.Log.i("EchoV2NativeTest", line)
        println(line)
    }
    private data class HistoryMapping(val payload: String, val fetchedAt: Long)
    private data class HistoryBaseline(val trackIds: Map<Long, String>, val mappings: Map<String, HistoryMapping>, val oldestPlayedAt: Long?) {
        val ids get() = trackIds.keys
    }

    @Test fun restoreOnlyExplicitSavedMoveBackup(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("echoConnectedV2RecoveryOnly") == "true")
        assumeTrue("Recovery-only acceptance is emulator-only", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk", true))
        val operationIds = args.getString("echoV2RecoveryOperationIds")?.split(',')
            ?: listOf(requireNotNull(args.getString("echoV2RecoveryOperationId")))
        val expectedPositionMs = requireNotNull(args.getString("echoV2RecoveryExpectedPositionMs")?.toLongOrNull()) {
            "Recovery-only acceptance requires the explicitly observed pre-recovery position"
        }
        val allowEmptyAfterRestart = args.getString("echoV2RecoveryAllowEmptyAfterRestart") == "true"
        require(expectedPositionMs >= 0) { "Expected recovery position cannot be negative" }
        require(operationIds.size in 1..2 && operationIds.distinct().size == operationIds.size &&
            operationIds.all { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }) {
            "Name one seed backup or the exact two-entry LIFO recovery stack"
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val appEntryPoint = EntryPointAccessors.fromApplication(context.applicationContext, NativePlaybackTestEntryPoint::class.java)
        val accounts = appEntryPoint.googleAccounts()
        val automaticSyncWasEnabled = accounts.automatic.value
        val localPreferences = context.getSharedPreferences("connected-playback", 0)
        val wasEnabled = localPreferences.getBoolean("enabled", false)
        val historyBefore = readHistoryBaseline(context)
        val historyRowsBefore = playbackHistoryDigest(context)
        val queuePreferences = context.getSharedPreferences("echo_queue", 0)
        fun persistedQueue() = listOf(
            queuePreferences.getString("ids", "[]"), queuePreferences.getInt("index", 0),
            queuePreferences.getLong("position", 0), queuePreferences.getInt("repeat", 0),
            queuePreferences.getBoolean("shuffle", false)
        )
        val persistedQueueBeforeLaunch = persistedQueue()
        fun requirePersistedRestartQueue(snapshot: List<Any?>) {
            assertEquals(listOf(fixtureRecordingA().id, fixtureRecordingB().id, fixtureRecordingA().id),
                (0 until org.json.JSONArray(snapshot[0] as String).length()).map {
                    org.json.JSONArray(snapshot[0] as String).getString(it)
                })
            assertEquals(0, snapshot[1])
            assertTrue(kotlin.math.abs((snapshot[2] as Long) - expectedPositionMs) <= 2_000L)
            assertEquals(androidx.media3.common.Player.REPEAT_MODE_OFF, snapshot[3])
            assertEquals(false, snapshot[4])
        }
        val library = appEntryPoint.libraryRepository()
        val libraryBefore = library.tracks.first()
        val playlistsBefore = library.playlists.first()
        val favoritesBefore = library.favorites.first()
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            accounts.setAutomatic(false)
            check(localPreferences.edit().putBoolean("enabled", false).commit()) {
                "Could not disable connected playback before the recovery-only check"
            }
            val recoveryFile = java.io.File(context.noBackupFilesDir, "connected-playback-v2-recovery.json")
            require(recoveryFile.length() in 1..(16 * 1024 * 1024))
            val recovery = org.json.JSONObject(recoveryFile.readText(Charsets.UTF_8))
            assertEquals("Expected only the explicitly named recovery backup(s)", operationIds.size + 1, recovery.length())
            assertEquals(setOf("_moveStack") + operationIds.map { "move:$it" },
                recovery.keys().asSequence().toSet())
            assertTrue(recovery.has("_moveStack"))
            val stack = recovery.getJSONArray("_moveStack")
            assertEquals(operationIds.size, stack.length())
            assertEquals(operationIds, (0 until stack.length()).map { stack.getString(it) })
            if (allowEmptyAfterRestart) requirePersistedRestartQueue(persistedQueueBeforeLaunch)
            val backup = recovery.getJSONObject("move:${operationIds.first()}")
            assertEquals(0, backup.getJSONArray("items").length())
            assertEquals(-1, backup.getInt("index"))
            assertEquals(0L, backup.getLong("position"))
            assertEquals(androidx.media3.common.Player.REPEAT_MODE_OFF, backup.getInt("repeat"))
            assertTrue(!backup.getBoolean("shuffle"))
            assertEquals(0, backup.getJSONArray("order").length())
            if (operationIds.size == 2) {
                val intervening = recovery.getJSONObject("move:${operationIds.last()}")
                assertEquals(listOf(fixtureRecordingA().id, fixtureRecordingB().id, fixtureRecordingA().id),
                    (0 until intervening.getJSONArray("items").length()).map {
                        intervening.getJSONArray("items").getJSONObject(it).getString("key")
                    })
                assertEquals(0, intervening.getInt("index"))
                assertEquals(androidx.media3.common.Player.REPEAT_MODE_OFF, intervening.getInt("repeat"))
                assertTrue(!intervening.getBoolean("shuffle"))
                assertEquals(listOf(0, 1, 2), (0 until intervening.getJSONArray("order").length()).map {
                    intervening.getJSONArray("order").getInt(it)
                })
            }

            scenario = ActivityScenario.launch(MainActivity::class.java)
            lateinit var viewModel: EchoViewModel
            scenario!!.onActivity { viewModel = ViewModelProvider(it)[EchoViewModel::class.java] }
            awaitController(viewModel)
            val participation = appEntryPoint.connectedPlaybackParticipation()
            val native = withTimeout(20_000) {
                while (participation.nativeBridge == null) delay(50)
                requireNotNull(participation.nativeBridge)
            }
            assertEquals(operationIds.last(), participation.displacedMoveOperationId.value)
            val currentQueue = native.capture()
            val currentObservation = native.observe()
            val restartRecovery = allowEmptyAfterRestart && currentQueue.queue.entries.isEmpty() &&
                !currentQueue.playing && !currentObservation.playing && currentObservation.recordingId == null
            if (restartRecovery) {
                requirePersistedRestartQueue(persistedQueueBeforeLaunch)
                assertEquals("Persisted queue changed after the recovery Activity launched", persistedQueueBeforeLaunch, persistedQueue())
                milestone("recovery guard selected: opted-in empty-after-restart with exact persisted paused A/B/A queue")
            } else {
                assertEquals("Refusing LIFO recovery if current playback no longer matches the known paused fixture queue",
                    listOf(fixtureRecordingA().id, fixtureRecordingB().id, fixtureRecordingA().id),
                    currentQueue.queue.entries.map { it.recording.id })
                assertEquals("Refusing LIFO recovery unless the current recording is fixture A",
                    fixtureRecordingA().id, currentObservation.recordingId)
                assertTrue("Refusing LIFO recovery unless current playback is paused",
                    !currentObservation.playing && !currentQueue.playing)
                assertTrue("Refusing LIFO recovery unless the live cursor is within 2 seconds of the explicitly observed position",
                    kotlin.math.abs(currentQueue.positionMs - expectedPositionMs) <= 2_000L &&
                        kotlin.math.abs(currentObservation.positionMs - expectedPositionMs) <= 2_000L)
                assertEquals(0, currentQueue.queue.repeatMode.ordinal)
                assertTrue(!currentQueue.queue.shuffleEnabled && !currentQueue.playing)
                milestone("recovery guard selected: live paused A/B/A queue")
            }
            operationIds.asReversed().forEach { native.restoreDisplacedPaused(it) }
            mainThread(instrumentation) {
                val restored = requireNotNull(viewModel.controller)
                assertEquals(0, restored.mediaItemCount)
                assertTrue(!restored.isPlaying)
                assertEquals(androidx.media3.common.Player.REPEAT_MODE_OFF, restored.repeatMode)
                assertTrue(!restored.shuffleModeEnabled)
            }
            if (recoveryFile.exists()) {
                require(recoveryFile.length() <= 16 * 1024 * 1024)
                val remaining = org.json.JSONObject(recoveryFile.readText(Charsets.UTF_8))
                operationIds.forEach { assertTrue(!remaining.has("move:$it")) }
                assertTrue(!remaining.has("_moveStack"))
            }
            assertTrue(participation.displacedMoveOperationId.value !in operationIds)
            assertEquals(historyBefore, readHistoryBaseline(context))
            assertEquals(historyRowsBefore, playbackHistoryDigest(context))
            assertEquals(libraryBefore, library.tracks.first())
            assertEquals(playlistsBefore, library.playlists.first())
            assertEquals(favoritesBefore, library.favorites.first())
            milestone("recovery-only seed restored and its backup consumed; library and history unchanged")
        } finally {
            try { scenario?.close() }
            finally {
                try { localPreferences.edit().putBoolean("enabled", wasEnabled).commit() }
                finally { accounts.setAutomatic(automaticSyncWasEnabled) }
            }
        }
    }

    @Test fun cleanOnlyExplicitSecondaryAfterFixtureCrash(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("echoConnectedV2CleanSecondaryCleanup") == "true")
        assumeTrue("Crash cleanup is emulator-only", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk", true))
        assumeTrue("Crash cleanup is restricted to the group-only fixture role", args.getString("echoV2Role") == "group-only")
        val androidId = requireNotNull(args.getString("echoV2AndroidId"))
        require(androidId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(args.getString("echoV2CleanSecondaryId") == androidId) {
            "Explicitly confirm the secondary emulator ID before fixture cleanup"
        }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val appEntryPoint = EntryPointAccessors.fromApplication(context.applicationContext, NativePlaybackTestEntryPoint::class.java)
        val accounts = appEntryPoint.googleAccounts()
        val googlePreferences = context.getSharedPreferences("google-account", 0)
        check(accounts.email.value == null && accounts.subject == null && !googlePreferences.contains("account_name") &&
            !googlePreferences.contains("email") && !googlePreferences.contains("subject")) {
            "Refusing clean-secondary cleanup while an Echo Google account is signed in"
        }
        val localPreferences = context.getSharedPreferences("connected-playback", 0)
        check(!localPreferences.getBoolean("enabled", false) && !accounts.automatic.value) {
            "Refusing clean-secondary cleanup because the original connected-playback preferences are not disabled"
        }

        val fixtureTracks = setOf(fixtureRecordingA().id, fixtureRecordingB().id)
        val library = appEntryPoint.libraryRepository()
        check(library.tracks.first().isEmpty() && library.playlists.first().isEmpty() && library.favorites.first().isEmpty()) {
            "Refusing clean-secondary cleanup because its library is not empty"
        }
        val historyBefore = readHistoryBaseline(context)
        check(historyBefore.trackIds.values.all { it in fixtureTracks } && historyBefore.mappings.keys.all { key ->
            key.startsWith("history-id:") && key.substringAfter("history-id:").toLongOrNull()?.let { id ->
                historyBefore.trackIds[id] in fixtureTracks
            } == true
        }) { "Refusing cleanup because history contains rows outside this fixture's two recordings" }
        val recoveryFile = java.io.File(context.noBackupFilesDir, "connected-playback-v2-recovery.json")
        check(!recoveryFile.exists() || org.json.JSONObject(recoveryFile.readText(Charsets.UTF_8)).length() == 0) {
            "Refusing cleanup while native recovery state still needs explicit review"
        }

        var scenario: ActivityScenario<MainActivity>? = null
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            lateinit var viewModel: EchoViewModel
            scenario!!.onActivity { viewModel = ViewModelProvider(it)[EchoViewModel::class.java] }
            awaitController(viewModel)
            val participation = appEntryPoint.connectedPlaybackParticipation()
            val native = withTimeout(20_000) {
                while (participation.nativeBridge == null) delay(50)
                requireNotNull(participation.nativeBridge)
            }
            val observation = native.observe()
            check(!observation.playing && observation.recordingId == null && native.capture().queue.entries.isEmpty()) {
                "Refusing cleanup because the secondary's independent queue is not empty and paused"
            }
            rollbackNewHistory(context, HistoryBaseline(emptyMap(), emptyMap(), null), fixtureTracks)
            check(readHistoryBaseline(context).trackIds.isEmpty() && readHistoryBaseline(context).mappings.isEmpty()) {
                "Clean-secondary fixture history was not removed"
            }
            check(library.tracks.first().isEmpty() && library.playlists.first().isEmpty() && library.favorites.first().isEmpty())
            check(!localPreferences.getBoolean("enabled", true) && !accounts.automatic.value && accounts.email.value == null) {
                "Clean-secondary connected-playback or account-sync preferences are not in their original signed-out state"
            }
            milestone("clean-secondary crash cleanup removed only fixture A/B history; empty library, account and original paused queue preserved")
        } finally {
            scenario?.close()
        }
    }

    @Test fun realAndroidMedia3MoveAndTogetherPeer(): Unit = runBlocking {
        milestone("instrumentation entered")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("echoConnectedV2Live") == "true")
        assertTrue("This acceptance test is emulator-only", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk", true))
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val production = args.getString("echoV2Production") == "true"
        val runId = args.getString("echoV2RunId").orEmpty()
        if (production) {
            require(args.getString("echoV2Production") == "true" && runId.matches(Regex("[A-Za-z0-9_-]{1,48}"))) {
                "Production acceptance requires -e echoV2Production true and a shared echoV2RunId"
            }
            require(args.getString("echoV2Role", "full") == "full" && args.getString("echoV2ExpectedGroupMembers")?.toIntOrNull()?.let { it == 2 } != false) {
                "Production acceptance supports only the two-device primary role"
            }
        }
        val androidId = if (production) "acceptance-v2-$runId-android" else args.getString("echoV2AndroidId", "v2-native-android")
        val desktopId = if (production) "acceptance-v2-$runId-windows" else args.getString("echoV2DesktopId", "v2-native-desktop")
        require(androidId.matches(Regex("[A-Za-z0-9_-]{1,128}")) && desktopId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val role = args.getString("echoV2Role", "full")
        require(role in setOf("full", "group-only")) { "Unknown native acceptance role: $role" }
        val expectedGroupMembers = args.getString("echoV2ExpectedGroupMembers")?.toIntOrNull()
            ?: if (role == "group-only") 3 else 2
        require(expectedGroupMembers in 2..8) { "Invalid expected native group size" }
        val appEntryPoint = EntryPointAccessors.fromApplication(context.applicationContext, NativePlaybackTestEntryPoint::class.java)
        val accounts = appEntryPoint.googleAccounts()
        val participation = appEntryPoint.connectedPlaybackParticipation()
        val accountEmail = accounts.email.value
        val session = if (production) {
            require(!accountEmail.isNullOrBlank()) { "Production acceptance requires the already-saved Echo Firebase account" }
            participation.existingSession(accountEmail)
        } else demoSession(args.getString("echoV2Uid", "v2-native-acceptance"))
        suspend fun currentSession() = (if (production) participation.existingSession(requireNotNull(accountEmail)) else session).also {
            check(it.uid == session.uid) { "Account changed during native acceptance; preserving remote records" }
        }
        val uidFingerprint = java.security.MessageDigest.getInstance("SHA-256")
            .digest(session.uid.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(12)
        milestone("${if (production) "PRODUCTION" else "demo"} account uid-sha256=$uidFingerprint")
        val automaticSyncWasEnabled = accounts.automatic.value
        val localPreferences = context.getSharedPreferences("connected-playback", 0)
        val wasEnabled = localPreferences.getBoolean("enabled", false)
        assumeTrue("Do not run the paired fixture while a user sleep timer is active",
            appEntryPoint.sleepTimerController().remainingMs.value == null)
        assumeTrue("Do not run while frequent-song caching is enabled", !appEntryPoint.offlineRepository().settings.first().autoCache)
        val library = appEntryPoint.libraryRepository()
        val libraryTracks = library.tracks.first()
        val recordingA = fixtureRecordingA()
        val recordingB = fixtureRecordingB()
        assumeTrue("A public fixture recording already exists in the user library; skipping to protect its history",
            libraryTracks.none { it.id == recordingA.id || it.id == recordingB.id })
        val historyBaseline = readHistoryBaseline(context)
        milestone("data-preservation baseline captured; launching app")
        assumeTrue("Existing history is near retention bounds; skip rather than trim user rows",
            historyBaseline.ids.size < 4_900 && historyBaseline.oldestPlayedAt?.let {
                System.currentTimeMillis() - it < 89L * 24 * 60 * 60 * 1000
            } != false)
        accounts.setAutomatic(false)
        var scenario = try { ActivityScenario.launch(MainActivity::class.java) }
        catch (error: Throwable) { accounts.setAutomatic(automaticSyncWasEnabled); throw error }
        lateinit var viewModel: EchoViewModel
        scenario.onActivity { viewModel = ViewModelProvider(it)[EchoViewModel::class.java] }
        var bridge: NativePlaybackBridgeV2? = null
        var seedOperation = ""
        var client: ConnectedPlaybackV2Client? = null
        var clientJob: kotlinx.coroutines.Deferred<Unit>? = null
        var fixtureHttp: OkHttpClient? = null
        var preexistingTargetMoveIds = emptySet<String>()
        val productionGroupIds = linkedSetOf<String>()
        val productionOperationIds = linkedSetOf<String>()
        var originalItems = emptyList<androidx.media3.common.MediaItem>()
        var originalIndex = 0
        var originalPositionMs = 0L
        var originalRepeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF
        var originalShuffleEnabled = false
        var originalPlayerVolume = 1f
        var fixtureMuted = false
        var originalPortableCapture: EngineCapture? = null
        var groupPrivateCapture: EngineCapture? = null
        var originalDisplacedMoveId: String? = null
        var productionWindowsSessionId: String? = null
        var seedPrepared = false
        var seeded = false
        var nativeAcceptancePassed = false
        var productionPreflightPassed = false
        try {
            // Keep the saved account's background publisher out of the acceptance fixture. The
            // explicit production client below uses the existing account only when opted in.
            if (wasEnabled) {
                viewModel.setConnectedPlaybackEnabled(false)
                withTimeout(15_000) { while (participation.enabled) delay(50) }
            }
            val native = withTimeout(20_000) {
                while (participation.nativeBridge == null) delay(50)
                requireNotNull(participation.nativeBridge)
            }
            bridge = native
            milestone("service bridge attached; session=${native.sessionId}")
            val rewrite = if (production) null else Interceptor { chain ->
                val local = chain.request().url.newBuilder().scheme("http").host("127.0.0.1").port(49154)
                    .addQueryParameter("ns", "demo-echo-rules-default-rtdb").build()
                chain.proceed(chain.request().newBuilder().url(local).build())
            }
            val httpBuilder = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS)
            rewrite?.let(httpBuilder::addInterceptor)
            val http = httpBuilder.followRedirects(false).followSslRedirects(false).build()
            fixtureHttp = http
            if (production) {
                suspend fun registration(id: String): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val current = currentSession()
                    val url = EchoFirebaseProject.realtimeDatabaseUrl.toHttpUrl().newBuilder()
                        .addPathSegments("users/${current.uid}/playbackV2/devices/$id.json")
                        .addQueryParameter("auth", current.idToken).build()
                    http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                        check(response.isSuccessful) { "Production device-ID preflight failed for $id: HTTP ${response.code}" }
                        val bytes = response.body?.byteStream()?.readNBytes(65_537) ?: ByteArray(0)
                        check(bytes.size <= 65_536) { "Production device-ID preflight exceeded 64 KiB for $id" }
                        bytes.toString(Charsets.UTF_8)
                    }
                }
                check(registration(androidId) == "null" && registration(desktopId) == "null") {
                    "Production acceptance device IDs already exist; refusing to reuse or overwrite their registrations"
                }
                requireNoPriorRunOperations(http, ::currentSession, androidId, desktopId)
            }
            preexistingTargetMoveIds = readTargetMoveIds(http, ::currentSession, setOf(androidId)).toSet()
            if (production) productionPreflightPassed = true
            milestone("captured ${preexistingTargetMoveIds.size} pre-existing Android-target Move operations")
            originalDisplacedMoveId = participation.displacedMoveOperationId.value
            val original = native.observe()
            check(!original.playing) { "Pause Android playback before running the paired acceptance test" }
            awaitController(viewModel)
            mainThread(instrumentation) {
                val controller = requireNotNull(viewModel.controller)
                originalItems = List(controller.mediaItemCount) { controller.getMediaItemAt(it) }
                originalIndex = controller.currentMediaItemIndex
                originalPositionMs = controller.currentPosition.coerceAtLeast(0)
                originalRepeatMode = controller.repeatMode
                originalShuffleEnabled = controller.shuffleModeEnabled
                originalPlayerVolume = controller.volume
            }
            originalPortableCapture = runCatching { native.capture() }.getOrNull()
            val recordingA = fixtureRecordingA()
            val recordingB = fixtureRecordingB()
            val probeQueue = TransferQueue(listOf(QueueOccurrence(UUID.randomUUID().toString(), recordingB)), RepeatModeV2.OFF, false)
            val secondRecordingReady = runCatching {
                val probe = "v2-resolve-probe-${UUID.randomUUID()}"
                native.prepare(probe, probeQueue, native.fence())
                native.abort(probe)
                true
            }.getOrDefault(false)
            assertTrue("The distinct public B recording must resolve on Android for song-change acceptance", secondRecordingReady)
            val privateBeforeGroupStage = native.observe()
            val privateQueueBeforeGroupStage = runCatching { native.capture() }.getOrNull()?.queue
            val stagedRef = GroupRef(UUID.randomUUID().toString(), 1)
            val stagedGroupOperation = "v2-native-staged-group-${UUID.randomUUID()}"
            assertTrue(native.prepare(stagedGroupOperation, probeQueue, native.fence(), stagedRef).ready)
            assertTrue("A mismatched ref must not discard an Android group stage",
                runCatching { native.leaveGroupPaused(UUID.randomUUID().toString(), stagedRef.epoch) }.isFailure)
            val stageLeave = native.leaveGroupPaused(stagedRef.groupId, stagedRef.epoch)
            assertTrue("Cancelling before Media3 activation must leave the independent player paused", !stageLeave.playing)
            assertEquals(privateBeforeGroupStage.currentOccurrenceId, stageLeave.currentOccurrenceId)
            privateQueueBeforeGroupStage?.let { assertEquals(it, native.capture().queue) }
            val leaveRetry = native.leaveGroupPaused(stagedRef.groupId, stagedRef.epoch)
            assertTrue(!leaveRetry.playing)
            assertEquals(stageLeave.currentOccurrenceId, leaveRetry.currentOccurrenceId)
            milestone("Media3 staged-group leave rejects wrong refs, discards the exact stage, and restores idempotently")
            seedOperation = "v2-native-seed-${UUID.randomUUID()}"
            val queue = duplicateFixtureQueue(recordingA, recordingB)
            val guardOperation = "v2-native-stage-guard-${UUID.randomUUID()}"
            val guardQueue = TransferQueue(listOf(QueueOccurrence(UUID.randomUUID().toString(), recordingA)), RepeatModeV2.OFF, false)
            val guardFence = native.fence()
            val staged = native.prepare(guardOperation, guardQueue, guardFence)
            check(staged.ready && native.fence() == guardFence) { "The real Media3 preload changed the active queue" }
            val competingStageRejected = runCatching {
                native.prepare("v2-native-competing-${UUID.randomUUID()}", guardQueue, guardFence)
            }.isFailure
            assertTrue("A second operation must not replace a ready Media3 stage", competingStageRejected)
            native.abort(guardOperation)
            check(native.fence() == guardFence && !native.observe().playing) {
                "Aborting the competing Media3 stage changed the active player"
            }
            milestone("real Media3 preload stayed silent/ready; competing operation rejected without changing queue")
            mainThread(instrumentation) { requireNotNull(viewModel.controller).volume = 0f }
            fixtureMuted = true
            milestone("fixture volume muted; staging native duplicate queue")
            native.prepare(seedOperation, queue, native.fence())
            seedPrepared = true
            native.activate(seedOperation, 0, true)
            seeded = true
            withTimeout(45_000) { while (!native.observe().playing) delay(100) }
            milestone("fixture active: ${queue.entries.size} occurrences, recording=${queue.entries.first().recording.id}")

            val v2Client = ConnectedPlaybackV2Client(EchoFirebaseProject.realtimeDatabaseUrl, androidId,
                if (secondRecordingReady) "Android v2 B-ready" else "Android v2 A-only",
                { currentSession() }, native, http,
                testDiagnosticLogger = { line -> android.util.Log.i("EchoV2NativeTest", line); println(line) })
            client = v2Client
            val preGroupCapture = if (role == "group-only") {
                check(native.pauseIfUnchanged(native.fence()) != null) {
                    "Could not pause the clean secondary's private fixture queue before recording its restore baseline"
                }
                native.capture()
            } else null
            clientJob = async { v2Client.run() }
            milestone("waiting for Windows v2 peer")
            withTimeout(90_000) {
                v2Client.state.first { state -> state.devices.any { it.id == desktopId && it.enabled &&
                    "move" in it.capabilities && "group" in it.capabilities } }
            }
            productionWindowsSessionId = v2Client.state.value.devices.first { it.id == desktopId }.sessionId
            milestone("Windows peer published; waiting for first transferred queue")
            if (role != "group-only") {
                // The primary participant runs the Move directions and stage guards. The
                // secondary participant joins only the later multi-device group phases.
                val seedIds = queue.entries.map { it.id }.toSet()
                withTimeout(180_000) {
                    while (true) {
                        val capture = runCatching { native.capture() }.getOrNull()
                        if (capture != null && capture.queue.entries.firstOrNull()?.id !in seedIds && capture.queue.entries.isNotEmpty()) break
                        delay(250)
                    }
                }
                val moved = native.capture()
                milestone("first Windows -> Android queue activated: count=${moved.queue.entries.size}, current=${moved.queue.entries.first().recording.id}")
                assertNotNull("The Android target must expose its saved independent queue for restoration",
                    participation.displacedMoveOperationId.value)
                assertEquals("youtube:aqz-KE-bpKQ", moved.queue.entries.first().recording.id)
                assertEquals("Move must retain duplicate occurrence slots", 3, moved.queue.entries.size)
                withTimeout(90_000) {
                    v2Client.state.first { state -> state.devices.firstOrNull { it.id == desktopId }?.snapshot?.playing == false }
                }
                milestone("Windows source published paused; checking stale-fence and unsupported-entry rejection")
                val staleFence = native.fence()
                val paused = native.pauseIfUnchanged(staleFence)
                assertNotNull("Target should pause only after its original fence is rechecked", paused)
                groupPrivateCapture = native.capture()
                assertTrue("Android's private queue must be paused before the reverse Move and first Together epoch",
                    !groupPrivateCapture!!.playing && groupPrivateCapture!!.queue.entries.size == 3)
                milestone("captured Android's actual paused A/B/A queue as the group-entry restore baseline")
                val staleRejected = runCatching { native.prepare("v2-stale-${UUID.randomUUID()}", moved.queue, staleFence) }.isFailure
                assertTrue("A stale destination fence must reject staging", staleRejected)
                val unsupported = TransferQueue(listOf(QueueOccurrence(UUID.randomUUID().toString(),
                    PortableRecording("local:must-not-transfer", "Local only", "Acceptance", 60_000))), RepeatModeV2.OFF, false)
                assertTrue("Unsupported local entries must fail explicitly", runCatching {
                    native.prepare("v2-unsupported-${UUID.randomUUID()}", unsupported, native.fence())
                }.isFailure)

                val desktop = withTimeout(90_000) {
                    v2Client.state.first { state -> state.devices.firstOrNull { it.id == desktopId }?.snapshot?.playing == false }
                        .devices.first { it.id == desktopId }
                }
                milestone("Android source paused; requesting reverse Android -> Windows Move")
                v2Client.moveTo(desktop.id, replacePlaying = true)
                if (production) v2Client.state.value.move?.id?.let(productionOperationIds::add)
                withTimeout(120_000) { v2Client.state.first { it.move?.status?.name == "COMPLETE" || it.move?.status?.name == "PARTIAL" } }
                milestone("reverse Move returned: ${v2Client.state.value.move}")
                assertTrue("Reverse Android-to-Windows Move must complete", v2Client.state.value.move?.status?.name == "COMPLETE")
            }

            // The Windows runner starts Together with the primary and optional secondary Android peer.
            val active = withTimeout(180_000) { v2Client.state.first { state -> state.group?.let { group ->
                group.status.name == "ACTIVE" && group.members.size == expectedGroupMembers
            } == true } }
            val firstGroup = requireNotNull(active.group)
            milestone("Together active: group=${firstGroup.ref}, members=${firstGroup.members.size}")
            assertEquals(expectedGroupMembers, firstGroup.members.size)
            val firstGroupId = firstGroup.ref.groupId
            if (production) productionGroupIds += firstGroupId
            val firstGroupEpoch = firstGroup.ref.epoch
            val serviceSessionId = native.sessionId
            scenario.close()
            milestone("activity destroyed while service group remains active; relaunching")
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { viewModel = ViewModelProvider(it)[EchoViewModel::class.java] }
            awaitController(viewModel)
            check(participation.nativeBridge?.sessionId == serviceSessionId) {
                "The Android Media3 service session changed when the activity was destroyed and reopened"
            }
            withTimeout(30_000) { v2Client.state.first { state -> state.group?.let { group ->
                group.ref.groupId == firstGroupId && group.status.name == "ACTIVE" &&
                    state.devices.any { it.id == androidId && it.enabled && it.sessionId == serviceSessionId }
            } == true } }
            milestone("service-owned v2 participation survived activity destruction")
            val expectedNext = active.group!!.timeline!!.queue.entries[1].recording.id
            withTimeout(120_000) {
                v2Client.state.first { it.group?.timeline?.let { timeline -> timeline.cursor == 1 &&
                    timeline.queue.entries[timeline.cursor].recording.id == expectedNext } == true }
            }
            milestone("first shared NEXT sequence applied: cursor=1")
            withTimeout(120_000) {
                v2Client.state.first { it.group?.timeline?.let { timeline -> !timeline.playing && timeline.positionMs == 15_000L } == true }
            }
            val seekTimeline = v2Client.state.value.group!!.timeline!!
            val seekObservation = native.observe()
            assertEquals(seekTimeline.queue.entries[seekTimeline.cursor].id, seekObservation.currentOccurrenceId)
            assertTrue("The native Android player must observe the shared paused seek", !seekObservation.playing &&
                kotlin.math.abs(seekObservation.positionMs - 15_000L) <= 2_000L)
            var groupOnlyRestoredSnapshot: dev.echo.core.connected.EngineObservation? = null
            val firstMemberRow = if (role == "group-only") {
                val coordinatorSessionId = v2Client.state.value.devices.first { it.id == desktopId }.sessionId
                withTimeout(45_000) {
                    while (true) {
                        val coordinatorAck = readGroupMember(fixtureHttp!!, ::currentSession, firstGroupId, desktopId)
                        if (coordinatorAck.optString("sessionId") == coordinatorSessionId &&
                            coordinatorAck.optLong("epoch") == firstGroupEpoch &&
                            coordinatorAck.optLong("lastAppliedSeq") == seekTimeline.sequence &&
                            coordinatorAck.optString("status") == "PAUSED" &&
                            coordinatorAck.optString("observedOccurrenceId") == seekTimeline.queue.entries[seekTimeline.cursor].id &&
                            kotlin.math.abs(coordinatorAck.optLong("observedPositionMs") - 15_000L) <= 2_000L) break
                        delay(200)
                    }
                }
                milestone("Windows coordinator durably acknowledged native SEEK; secondary can leave")
                milestone("secondary observed shared NEXT/PAUSE/SEEK; leaving while the other two remain")
                v2Client.leaveTogether()
                withTimeout(15_000) { v2Client.state.first { it.group == null } }
                groupOnlyRestoredSnapshot = native.observe()
                val beforeGroup = requireNotNull(preGroupCapture)
                val restoredCapture = native.capture()
                assertEquals(beforeGroup.queue.entries.map { it.recording.id }, restoredCapture.queue.entries.map { it.recording.id })
                assertEquals(beforeGroup.queue.repeatMode, restoredCapture.queue.repeatMode)
                assertEquals(beforeGroup.queue.shuffleEnabled, restoredCapture.queue.shuffleEnabled)
                assertEquals(beforeGroup.queue.entries.firstOrNull()?.recording?.id, restoredCapture.queue.entries.firstOrNull()?.recording?.id)
                assertEquals(beforeGroup.queue.entries.firstOrNull()?.recording?.id, groupOnlyRestoredSnapshot?.recordingId)
                assertTrue("Follower leave must restore the private cursor/position paused",
                    kotlin.math.abs(beforeGroup.positionMs - restoredCapture.positionMs) <= 2_000L &&
                        kotlin.math.abs(beforeGroup.positionMs - (groupOnlyRestoredSnapshot?.positionMs ?: Long.MAX_VALUE)) <= 2_000L)
                assertTrue("Explicit follower leave restores its private queue paused", !restoredCapture.playing)
                readGroupMember(fixtureHttp!!, ::currentSession, firstGroupId, androidId)
            } else {
                milestone("shared NEXT/PAUSE/SEEK observed; waiting for coordinator to close the group")
                withTimeout(180_000) { waitForGroupMemberLeft(fixtureHttp!!, ::currentSession, firstGroupId, androidId, serviceSessionId, firstGroupEpoch) }
            }
            assertEquals(firstGroupEpoch, firstMemberRow.getLong("epoch"))
            assertEquals("LEFT", firstMemberRow.getString("status"))
            if (groupOnlyRestoredSnapshot != null) {
                assertTrue("The native group leave snapshot must be paused", !groupOnlyRestoredSnapshot!!.playing)
                assertEquals(firstMemberRow.optString("observedOccurrenceId").takeUnless { it.isBlank() },
                    groupOnlyRestoredSnapshot!!.currentOccurrenceId)
                check(kotlin.math.abs(firstMemberRow.getLong("observedPositionMs") - groupOnlyRestoredSnapshot!!.positionMs) <= 2_000L) {
                    "The first group LEFT acknowledgement did not match the paused native snapshot"
                }
            } else {
                withTimeout(15_000) {
                    v2Client.state.first { state -> state.devices.firstOrNull { it.id == androidId }?.snapshot?.let {
                        !it.playing && it.currentOccurrenceId == firstMemberRow.optString("observedOccurrenceId").takeUnless(String::isBlank)
                    } == true }
                }
            }
            milestone("first Android member LEFT acknowledged with paused native snapshot; waiting for second group rejoin")
            val rejoined = withTimeout(180_000) {
                v2Client.state.first { it.group?.ref?.groupId != firstGroupId && it.group?.status?.name == "ACTIVE" }
            }
            assertNotNull("Android must remain available through the second group epoch", rejoined.group)
            if (production) rejoined.group?.ref?.groupId?.let(productionGroupIds::add)
            milestone("second group rejoined; waiting for Windows coordinator lease expiry")
            val expired = withTimeout(180_000) { v2Client.state.first { it.group == null } }
            assertTrue("Android must leave the expired group after restoring its backup", expired.group == null)
            milestone("Android group cleared after coordinator disconnect; final message=${expired.message.orEmpty()}")
            if (role == "group-only") {
                val expected = requireNotNull(preGroupCapture)
                val restored = native.capture()
                assertEquals(expected.queue.entries.map { it.recording.id }, restored.queue.entries.map { it.recording.id })
                assertEquals(expected.queue.repeatMode, restored.queue.repeatMode)
                assertEquals(expected.queue.shuffleEnabled, restored.queue.shuffleEnabled)
                assertEquals(expected.queue.entries.firstOrNull()?.recording?.id, restored.queue.entries.firstOrNull()?.recording?.id)
                assertTrue("Coordinator disconnect must restore the secondary's private position",
                    kotlin.math.abs(expected.positionMs - restored.positionMs) <= 2_000L)
                assertTrue("Coordinator disconnect must restore the secondary's independent queue paused", !restored.playing)
            } else {
                    val expected = requireNotNull(groupPrivateCapture)
                    val restored = native.capture()
                    check(restored.queue.entries.map { it.recording.id } == expected.queue.entries.map { it.recording.id } &&
                        restored.queue.repeatMode == expected.queue.repeatMode &&
                        restored.queue.shuffleEnabled == expected.queue.shuffleEnabled && !restored.playing &&
                        kotlin.math.abs(restored.positionMs - expected.positionMs) <= 2_000) {
                        "Coordinator disconnect did not restore Android's actual pre-group private queue/cursor paused"
                    }
                }
            milestone("PASS second group coordinator disconnect restored Android independent queue paused")
            nativeAcceptancePassed = true
        } finally {
            withContext(NonCancellable) {
            var clientStopped = clientJob == null
            try {
                if (production) client?.state?.value?.group?.ref?.groupId?.let(productionGroupIds::add)
                if (production) client?.state?.value?.move?.id?.let(productionOperationIds::add)
                val activeGroup = client?.state?.value?.group?.ref
                if (activeGroup != null && runCatching { client?.leaveTogether() }.isFailure && bridge != null) {
                    runCatching { bridge!!.leaveGroupPaused(activeGroup.groupId, activeGroup.epoch) }
                }
                client?.close()
                clientJob?.cancel()
                clientJob?.let { job ->
                    runCatching { kotlinx.coroutines.withTimeout(60_000) { job.join() } }
                    clientStopped = job.isCompleted
                    if (!clientStopped && production && fixtureHttp != null) {
                        productionOperationIds += runCatching {
                            readTargetMoveIds(fixtureHttp!!, ::currentSession, setOf(androidId, desktopId))
                                .filterNot { it in preexistingTargetMoveIds }
                        }.getOrDefault(emptyList())
                    }
                    check(clientStopped) {
                        "Connected playback client did not stop within 60 seconds; preserving native backups and remote fixture rows " +
                            "runId=$runId groups=${productionGroupIds.sorted()} devices=${listOf(androidId, desktopId)} " +
                            "operations=${productionOperationIds.sorted()} nativeBackupOperation=$seedOperation " +
                            "sessions=${listOfNotNull(bridge?.sessionId, productionWindowsSessionId)}"
                    }
                }
                try {
                    if (seeded && bridge != null) {
                        val incoming = fixtureHttp?.let { readTargetMoveIds(it, ::currentSession, setOf(androidId)) }.orEmpty()
                            .filterNot { it in preexistingTargetMoveIds }
                        incoming.asReversed().forEach { bridge!!.restoreDisplacedPaused(it) }
                        bridge!!.restoreDisplacedPaused(seedOperation)
                        check(participation.displacedMoveOperationId.value == originalDisplacedMoveId) {
                            "Android's pre-existing Move recovery state was not preserved"
                        }
                        mainThread(instrumentation) {
                            val restored = requireNotNull(viewModel.controller)
                            check(List(restored.mediaItemCount) { restored.getMediaItemAt(it).mediaId } == originalItems.map { it.mediaId }) {
                                "The paired acceptance run did not restore the Android queue"
                            }
                            check(restored.currentMediaItemIndex == originalIndex && restored.repeatMode == originalRepeatMode &&
                                restored.shuffleModeEnabled == originalShuffleEnabled) {
                                "The paired acceptance run changed Android cursor, repeat or shuffle settings"
                            }
                            check(!restored.isPlaying && kotlin.math.abs(restored.currentPosition - originalPositionMs) <= 2_000) {
                                "The paired acceptance run did not restore Android paused at its prior position"
                            }
                        }
                        originalPortableCapture?.let { before ->
                            val after = bridge!!.capture()
                            check(after.queue.entries.map { it.recording.id } == before.queue.entries.map { it.recording.id } &&
                                after.queue.repeatMode == before.queue.repeatMode && after.queue.shuffleEnabled == before.queue.shuffleEnabled) {
                                "The paired acceptance run did not restore Android's actual portable shuffle traversal"
                            }
                        }
                        milestone("original Android queue/cursor/repeat/shuffle restored paused")
                    } else if (seedPrepared && bridge != null) {
                        bridge!!.abort(seedOperation)
                    }
                } finally {
                    try {
                        if (fixtureMuted) mainThread(instrumentation) {
                            val restored = requireNotNull(viewModel.controller)
                            restored.volume = originalPlayerVolume
                            check(kotlin.math.abs(restored.volume - originalPlayerVolume) < 0.001f) {
                                "The paired acceptance run did not restore Android player volume"
                            }
                        }
                    } finally {
                        try {
                            repeat(4) {
                                delay(500)
                                rollbackNewHistory(context, historyBaseline,
                                    setOf(recordingA.id, recordingB.id) + originalItems.map { it.mediaId })
                            }
                            milestone("history/mapping baseline restored and verified")
                        } finally {
                            if (production && productionPreflightPassed && fixtureHttp != null && bridge != null) {
                                if (clientStopped) {
                                    try {
                                        productionOperationIds += readTargetMoveIds(fixtureHttp!!, ::currentSession, setOf(androidId, desktopId))
                                            .filterNot { it in preexistingTargetMoveIds }
                                        cleanupAndroidProductionRows(fixtureHttp!!, ::currentSession, androidId, bridge!!.sessionId,
                                            desktopId, productionWindowsSessionId,
                                            productionGroupIds, preexistingTargetMoveIds, nativeAcceptancePassed)
                                    } catch (error: Throwable) {
                                        val operationIds = productionOperationIds + runCatching {
                                            readTargetMoveIds(fixtureHttp!!, ::currentSession, setOf(androidId, desktopId))
                                                .filterNot { it in preexistingTargetMoveIds }
                                        }.getOrDefault(emptyList())
                                        milestone("REMOTE CLEANUP FAILED; manual recovery required: runId=$runId " +
                                            "groups=${productionGroupIds.sorted()} operations=${operationIds.sorted()} " +
                                            "devices=${listOf(androidId, desktopId)} " +
                                            "sessions=${listOfNotNull(bridge?.sessionId, productionWindowsSessionId)} " +
                                            "reason=${error.message.orEmpty()}")
                                        throw error
                                    }
                                } else milestone("REMOTE CLEANUP SKIPPED because the client did not stop; preserve fixture rows for manual recovery " +
                                    "runId=$runId groups=${productionGroupIds.sorted()} operations=${productionOperationIds.sorted()} " +
                                    "nativeBackupOperation=$seedOperation devices=${listOf(androidId, desktopId)} " +
                                    "sessions=${listOfNotNull(bridge?.sessionId, productionWindowsSessionId)}")
                            }
                        }
                    }
                }
            } finally {
                try {
                    if (wasEnabled) {
                        viewModel.setConnectedPlaybackEnabled(true)
                        runCatching { viewModel.runConnectedPlayback() }
                    } else localPreferences.edit().putBoolean("enabled", false).commit()
                } finally {
                    try { scenario.close() } finally {
                        accounts.setAutomatic(automaticSyncWasEnabled)
                        milestone("connected-playback/account-sync preferences restored; cleanup complete")
                    }
                }
            }
            }
        }
    }

    private fun fixtureRecordingA() = PortableRecording("youtube:aqz-KE-bpKQ", "Big Buck Bunny / Native", "Blender Foundation", 596_000)
    private fun fixtureRecordingB() = PortableRecording("youtube:eRsGyueVLvQ", "Sintel - Durian Open Movie", "Blender Foundation", 887_000)
    private fun isCanonicalUuid(value: String) = runCatching { UUID.fromString(value).toString().equals(value, ignoreCase = true) }.getOrDefault(false)

    private fun duplicateFixtureQueue(first: PortableRecording, middle: PortableRecording): TransferQueue {
        return TransferQueue(listOf(first, middle, first).map { QueueOccurrence(UUID.randomUUID().toString(), it) },
            RepeatModeV2.OFF, false)
    }

    private suspend fun awaitController(viewModel: EchoViewModel) = withTimeout(20_000) {
        while (viewModel.controller == null) delay(50)
    }

    private fun <T> mainThread(instrumentation: android.app.Instrumentation, action: () -> T): T {
        val task = java.util.concurrent.FutureTask(action)
        instrumentation.runOnMainSync(task)
        return task.get(10, TimeUnit.SECONDS)
    }

    private fun demoSession(uid: String): FirebaseSession {
        val now = System.currentTimeMillis() / 1000
        fun encode(value: Any) = Base64.getUrlEncoder().withoutPadding().encodeToString(org.json.JSONObject(value as Map<*, *>).toString().toByteArray())
        val header = encode(mapOf("alg" to "none", "typ" to "JWT"))
        val claims = encode(mapOf("aud" to "demo-echo-rules", "iss" to "https://securetoken.google.com/demo-echo-rules",
            "sub" to uid, "user_id" to uid, "iat" to now, "exp" to now + 3600, "email" to "$uid@example.test",
            "email_verified" to true, "firebase" to mapOf("sign_in_provider" to "google.com", "identities" to mapOf("google.com" to listOf(uid)))))
        return FirebaseSession(uid, "$uid@example.test", "$header.$claims.", "local-demo-refresh", System.currentTimeMillis() + 3_600_000)
    }

    private fun readHistoryBaseline(context: android.content.Context): HistoryBaseline =
        SQLiteDatabase.openDatabase(context.getDatabasePath("echo-library.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            val trackIds = database.rawQuery("SELECT id, trackId FROM playback_history", null).use { cursor ->
                buildMap { while (cursor.moveToNext()) put(cursor.getLong(0), cursor.getString(1)) }
            }
            val oldest = database.rawQuery("SELECT MIN(playedAt) FROM playback_history", null).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
            val mappings = database.rawQuery("SELECT cacheKey, payload, fetchedAt FROM cached_metadata WHERE provider = ? AND cacheKey LIKE 'history-id:%'",
                arrayOf("firebase-sync")).use { cursor ->
                buildMap { while (cursor.moveToNext()) put(cursor.getString(0), HistoryMapping(cursor.getString(1), cursor.getLong(2))) }
            }
            HistoryBaseline(trackIds, mappings, oldest)
        }

    private fun playbackHistoryDigest(context: android.content.Context): String =
        SQLiteDatabase.openDatabase(context.getDatabasePath("echo-library.db").path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            database.rawQuery("SELECT * FROM playback_history ORDER BY id", null).use { cursor ->
                while (cursor.moveToNext()) {
                    repeat(cursor.columnCount) { index ->
                        if (cursor.isNull(index)) digest.update(java.nio.ByteBuffer.allocate(4).putInt(-1).array())
                        else {
                            val bytes = cursor.getString(index).toByteArray(Charsets.UTF_8)
                            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
                            digest.update(bytes)
                        }
                    }
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

    private fun rollbackNewHistory(context: android.content.Context, before: HistoryBaseline, testTrackIds: Set<String>) {
        val removedIds = mutableSetOf<Long>()
        SQLiteDatabase.openDatabase(context.getDatabasePath("echo-library.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            database.beginTransaction()
            try {
                val nowTrackIds = database.rawQuery("SELECT id, trackId FROM playback_history", null).use { cursor ->
                    buildMap { while (cursor.moveToNext()) put(cursor.getLong(0), cursor.getString(1)) }
                }
                nowTrackIds.filter { (id, trackId) -> id !in before.ids && trackId in testTrackIds }.keys.forEach { id ->
                    removedIds += id
                    database.delete("playback_history", "id = ?", arrayOf(id.toString()))
                    database.delete("cached_metadata", "provider = ? AND cacheKey = ?", arrayOf("firebase-sync", "history-id:$id"))
                }
                // A normal listening write prunes orphan mappings globally. Put back any preexisting
                // mapping with the exact saved payload/time; those rows are outside the fixture.
                before.mappings.forEach { (key, mapping) ->
                    val value = ContentValues().apply {
                        put("provider", "firebase-sync"); put("cacheKey", key)
                        put("payload", mapping.payload); put("fetchedAt", mapping.fetchedAt)
                    }
                    database.insertWithOnConflict("cached_metadata", null, value, SQLiteDatabase.CONFLICT_REPLACE)
                }
                database.setTransactionSuccessful()
            } finally { database.endTransaction() }
        }
        val after = readHistoryBaseline(context)
        check(before.trackIds.all { (id, trackId) -> after.trackIds[id] == trackId } && before.mappings == after.mappings &&
            after.trackIds.none { (id, trackId) -> id !in before.ids && trackId in testTrackIds } &&
            after.mappings.keys.none { key -> key !in before.mappings &&
                key.substringAfter("history-id:").toLongOrNull()?.let { it in removedIds } == true }) {
            "The paired acceptance run did not restore test-generated listening history and its sync mappings"
        }
    }

    private suspend fun readTargetMoveIds(http: OkHttpClient, sessionProvider: suspend () -> FirebaseSession, deviceIds: Set<String>): List<String> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val session = sessionProvider()
            val url = EchoFirebaseProject.realtimeDatabaseUrl.toHttpUrl().newBuilder()
                .addPathSegments("users/${session.uid}/playbackV2/ops.json")
                .addQueryParameter("auth", session.idToken)
                .addQueryParameter("orderBy", "\"\$key\"")
                .addQueryParameter("limitToLast", "256").build()
            val text = http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                check(response.isSuccessful)
                val bytes = response.body?.byteStream()?.readNBytes(1_048_577) ?: ByteArray(0)
                check(bytes.size <= 1_048_576) { "Recent playback operation response exceeded 1 MiB" }
                bytes.toString(Charsets.UTF_8)
            }
            val operations = if (text == "null") org.json.JSONObject() else org.json.JSONObject(text)
            check(operations.length() < 256) {
                "Move operation query reached its 256-row bound; refusing a non-exhaustive ownership baseline"
            }
            operations.keys().asSequence().filter { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
                .filter { id -> operations.optJSONObject(id)?.optJSONObject("request")?.let { request ->
                    request.optString("targetDeviceId") in deviceIds
                } == true }
                .toList()
        }

    private suspend fun requireNoPriorRunOperations(http: OkHttpClient, sessionProvider: suspend () -> FirebaseSession,
        androidId: String, desktopId: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val session = sessionProvider()
        val url = EchoFirebaseProject.realtimeDatabaseUrl.toHttpUrl().newBuilder()
            .addPathSegments("users/${session.uid}/playbackV2/ops.json").addQueryParameter("auth", session.idToken)
            .addQueryParameter("orderBy", "\"\$key\"").addQueryParameter("limitToLast", "256").build()
        val text = http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            check(response.isSuccessful) { "Production operation-ID preflight failed: HTTP ${response.code}" }
            val bytes = response.body?.byteStream()?.readNBytes(1_048_577) ?: ByteArray(0)
            check(bytes.size <= 1_048_576) { "Production operation-ID preflight exceeded 1 MiB" }
            bytes.toString(Charsets.UTF_8)
        }
        val operations = if (text == "null") org.json.JSONObject() else org.json.JSONObject(text)
        check(operations.length() <= 253) {
            "Production operation preflight needs room for the fixture's two Moves; found ${operations.length()} rows (bounded at 256)"
        }
        val collisions = operations.keys().asSequence().filter { id ->
            val request = operations.optJSONObject(id)?.optJSONObject("request") ?: return@filter false
            request.optString("sourceDeviceId") in setOf(androidId, desktopId) ||
                request.optString("targetDeviceId") in setOf(androidId, desktopId)
        }.toList()
        check(collisions.isEmpty()) { "Production run IDs collide with pre-existing operation IDs: $collisions" }
        for (device in listOf(androidId, desktopId)) {
            val inboxUrl = EchoFirebaseProject.realtimeDatabaseUrl.toHttpUrl().newBuilder()
                .addPathSegments("users/${session.uid}/playbackV2/inbox/$device.json").addQueryParameter("auth", session.idToken)
                .addQueryParameter("orderBy", "\"createdAt\"").addQueryParameter("limitToLast", "256").build()
            val inboxText = http.newCall(Request.Builder().url(inboxUrl).get().build()).execute().use { response ->
                check(response.isSuccessful) { "Production inbox-ID preflight failed for $device: HTTP ${response.code}" }
                val bytes = response.body?.byteStream()?.readNBytes(1_048_577) ?: ByteArray(0)
                check(bytes.size <= 1_048_576) { "Production inbox-ID preflight exceeded 1 MiB for $device" }
                bytes.toString(Charsets.UTF_8)
            }
            check(inboxText == "null" || inboxText == "{}") {
                "Production acceptance inbox $device is not empty; refusing to adopt opaque pre-existing message IDs"
            }
        }
    }

    private suspend fun readGroupMember(http: OkHttpClient, sessionProvider: suspend () -> FirebaseSession, groupId: String,
        deviceId: String): org.json.JSONObject = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val session = sessionProvider()
        require(groupId.matches(Regex("[A-Za-z0-9_-]{1,128}")) && deviceId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val url = EchoFirebaseProject.realtimeDatabaseUrl.toHttpUrl().newBuilder()
            .addPathSegments("users/${session.uid}/playbackV2/groups/$groupId/members/$deviceId.json")
            .addQueryParameter("auth", session.idToken).build()
        val text = http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            check(response.isSuccessful)
            response.body?.string().orEmpty()
        }
        check(text.isNotBlank() && text != "null") { "The Android group membership row disappeared" }
        org.json.JSONObject(text)
    }

    /** Delete only this native fixture's Android rows; IDs and both live sessions are checked first. */
    private suspend fun cleanupAndroidProductionRows(http: OkHttpClient, sessionProvider: suspend () -> FirebaseSession,
        androidId: String, androidSession: String, desktopId: String, desktopSession: String?, groupIds: Set<String>,
        baselineAndroidMoves: Set<String>, requireCompleteRun: Boolean) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val windowsSession = desktopSession?.takeIf { it.isNotBlank() }
        if (requireCompleteRun) requireNotNull(windowsSession) { "Cannot prove the Windows peer session for cleanup" }
        val initialSession = sessionProvider()
        check(initialSession.uid.isNotBlank()) { "Cleanup session has no UID; preserving remote records" }
        val base = "users/${initialSession.uid}/playbackV2"
        suspend fun call(path: String, method: String = "GET", query: Map<String, String> = emptyMap()): String {
            require(path.startsWith("$base/") && !path.contains(".."))
            val session = sessionProvider()
            check(session.uid == initialSession.uid) { "Account UID changed during production cleanup; preserving remote records" }
            val builder = EchoFirebaseProject.realtimeDatabaseUrl.toHttpUrl().newBuilder().addPathSegments("$path.json")
                .addQueryParameter("auth", session.idToken)
            query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
            val url = builder.build()
            var failure: Throwable? = null
            repeat(if (method == "DELETE") 3 else 1) { attempt ->
                try {
                    return http.newCall(Request.Builder().url(url).method(method,
                        if (method == "GET") null else okhttp3.RequestBody.create(null, ByteArray(0))).build()).execute().use { response ->
                        check(response.isSuccessful) { "Production cleanup $method $path failed: HTTP ${response.code}" }
                        val bytes = response.body?.byteStream()?.readNBytes(1_048_577) ?: ByteArray(0)
                        check(bytes.size <= 1_048_576) { "Bounded production metadata response exceeded 1 MiB at $path" }
                        bytes.toString(Charsets.UTF_8)
                    }
                } catch (error: Throwable) {
                    failure = error
                    if (method != "DELETE" || attempt == 2) throw error
                    delay(250L * (attempt + 1))
                }
            }
            throw requireNotNull(failure)
        }
        suspend fun deleteDeviceIfSame(device: String, expectedSession: String) {
            val path = "$base/devices/$device"
            val session = sessionProvider()
            check(session.uid == initialSession.uid) { "Account UID changed before deleting registration $device" }
            val url = EchoFirebaseProject.realtimeDatabaseUrl.toHttpUrl().newBuilder().addPathSegments("$path.json")
                .addQueryParameter("auth", session.idToken).build()
            val etag = http.newCall(Request.Builder().url(url).header("X-Firebase-ETag", "true").get().build()).execute().use { response ->
                check(response.isSuccessful) { "Could not recheck exact device registration $device: HTTP ${response.code}" }
                val bytes = response.body?.byteStream()?.readNBytes(65_537) ?: ByteArray(0)
                check(bytes.size <= 65_536) { "Device registration $device exceeded 64 KiB" }
                val current = bytes.toString(Charsets.UTF_8)
                check(current != "null" && org.json.JSONObject(current).optString("sessionId") == expectedSession &&
                    !org.json.JSONObject(current).optBoolean("enabled", true)) {
                    "Preserving device registration $device: current session/status no longer matches the fixture"
                }
                response.header("ETag") ?: error("Firebase omitted ETag for exact device registration $device")
            }
            val deleteSession = sessionProvider()
            check(deleteSession.uid == initialSession.uid) { "Account UID changed during guarded device deletion $device" }
            val deleteUrl = url.newBuilder().setQueryParameter("auth", deleteSession.idToken).build()
            http.newCall(Request.Builder().url(deleteUrl).header("If-Match", etag).delete().build()).execute().use { response ->
                check(response.isSuccessful) { "Guarded deletion refused for device $device; preserving registration (HTTP ${response.code})" }
            }
        }
        val opsText = call("$base/ops", query = mapOf("orderBy" to "\"\$key\"", "limitToLast" to "256"))
        val ops = if (opsText == "null" || opsText.isBlank()) org.json.JSONObject() else org.json.JSONObject(opsText)
        check(ops.length() < 256) { "Move cleanup hit the 256-row bound; preserving all remote records" }
        val cohort = if (windowsSession == null) setOf(androidId to androidSession)
            else setOf(androidId to androidSession, desktopId to windowsSession)
        // The Windows finally block closes its client first; wait until that exact session is visibly offline
        // before touching shared group or operation rows.
        if (windowsSession != null) {
            val desktopPath = "$base/devices/$desktopId"
            val deadline = android.os.SystemClock.elapsedRealtime() + 45_000
            var desktopDevice: org.json.JSONObject? = null
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                val value = call(desktopPath)
                desktopDevice = value.takeUnless { it == "null" || it.isBlank() }?.let { org.json.JSONObject(it) }
                if (desktopDevice?.optString("sessionId") == windowsSession && !desktopDevice!!.optBoolean("enabled", true)) break
                delay(250)
            }
            check(desktopDevice?.optString("sessionId") == windowsSession && !desktopDevice!!.optBoolean("enabled", true)) {
                "Windows peer did not publish offline for its exact session; preserving all shared metadata (opaque device=$desktopId)"
            }
        }
        val touchingRunIds = ops.keys().asSequence().filter { id ->
            val request = ops.optJSONObject(id)?.optJSONObject("request") ?: return@filter false
            request.optString("sourceDeviceId") in setOf(androidId, desktopId) ||
                request.optString("targetDeviceId") in setOf(androidId, desktopId)
        }.toSet()
        val ownedMoves = touchingRunIds.filter { it !in baselineAndroidMoves }.filter { id ->
            val request = ops.optJSONObject(id)?.optJSONObject("request") ?: return@filter false
            setOf(request.optString("sourceDeviceId") to request.optString("sourceSessionId"),
                request.optString("targetDeviceId") to request.optString("targetSessionId")) == cohort &&
                androidId in setOf(request.optString("sourceDeviceId"), request.optString("targetDeviceId"))
        }.toSet()
        check(touchingRunIds == ownedMoves) {
            "Production Move plans do not all match the exact fixture sessions; preserving residual IDs=${(touchingRunIds - ownedMoves).sorted()}"
        }
        check(ownedMoves.size <= 2) { "More than two exact paired Move IDs found; preserving opaque IDs=${ownedMoves.sorted()}" }
        val inboxSnapshots = listOf(androidId, desktopId).associateWith { device ->
            val inboxText = call("$base/inbox/$device", query = mapOf("orderBy" to "\"createdAt\"", "limitToLast" to "256"))
            val inbox = if (inboxText == "null" || inboxText.isBlank()) org.json.JSONObject() else org.json.JSONObject(inboxText)
            check(inbox.length() < 256) { "Inbox cleanup for $device hit the 256-row cap; preserving all remote rows" }
            inbox
        }
        val inboxGroupIds = linkedSetOf<String>()
        val inboxMoveIds = linkedSetOf<String>()
        inboxSnapshots.forEach { (device, inbox) -> inbox.keys().asSequence().forEach { messageId ->
            val row = inbox.getJSONObject(messageId)
            val ref = row.optString("refId")
            check(isCanonicalUuid(messageId) && isCanonicalUuid(ref) &&
                row.optString("type") in setOf("MOVE", "GROUP") &&
                row.keys().asSequence().toSet() == setOf("type", "refId", "createdAt", "expiresAt")) {
                "Production inbox $device contains opaque fixture candidate ID=$messageId ref=$ref; preserving it"
            }
            if (row.optString("type") == "GROUP") inboxGroupIds += ref else inboxMoveIds += ref
        } }
        val allGroupIds = groupIds + inboxGroupIds
        check(allGroupIds.size <= 2 && allGroupIds.all(::isCanonicalUuid)) {
            "Production cleanup found opaque or excess group IDs: ${allGroupIds.sorted()}"
        }
        val allMoveIds = ownedMoves + inboxMoveIds
        check(allMoveIds.size <= 2 && allMoveIds.all(::isCanonicalUuid)) {
            "Production cleanup found opaque or excess Move IDs: ${allMoveIds.sorted()}"
        }
        check(inboxMoveIds.all { it in ownedMoves }) {
            "Preserving inbox Move messages without an exact paired operation plan: ${(inboxMoveIds - ownedMoves).sorted()}"
        }
        if (requireCompleteRun) check(ownedMoves.size == 2 && allGroupIds.size == 2) {
            "Completed acceptance expected 2 exact Moves and 2 exact groups; found moves=${ownedMoves.sorted()} groups=${allGroupIds.sorted()}"
        }
        if (windowsSession == null) check(allMoveIds.isEmpty() && allGroupIds.isEmpty()) {
            "Windows session unavailable; preserving shared metadata with opaque IDs moves=${allMoveIds.sorted()} groups=${allGroupIds.sorted()}"
        }
        val ownedRefs = allMoveIds + allGroupIds
        // Read and validate the complete deletion plan before the first remote write.
        val groupSnapshots = allGroupIds.mapNotNull { groupId ->
            val path = "$base/groups/$groupId"
            val text = call(path)
            check(text != "null" && text.isNotBlank()) { "Owned group $groupId disappeared before cleanup; preserving inbox/ref metadata" }
            val group = org.json.JSONObject(text)
            check(group.keys().asSequence().toSet().all { it in setOf("head", "timelines", "members", "requests") }) {
                "Preserving group $groupId: unexpected metadata children ${group.keys().asSequence().filter { it !in setOf("head", "timelines", "members", "requests") }.toList()}"
            }
            val members = group.optJSONObject("members") ?: error("Owned group $groupId has no member rows")
            val actualCohort = members.keys().asSequence().map { id -> id to members.getJSONObject(id).optString("sessionId") }.toSet()
            check(actualCohort == cohort) { "Preserving group $groupId: members/session cohort differs: $actualCohort" }
            groupId to group
        }.toMap()
        val moveSnapshots = ownedMoves.associateWith { id ->
            val request = ops.getJSONObject(id).optJSONObject("request") ?: error("Owned Move $id has no request plan")
            check(ops.getJSONObject(id).keys().asSequence().toSet().all { it in setOf("request", "decision", "target", "source", "result") }) {
                "Preserving Move $id: unexpected operation children"
            }
            check(isCanonicalUuid(id)) { "Preserving opaque Move operation ID: $id" }
            check(setOf(request.getString("sourceDeviceId") to request.getString("sourceSessionId"),
                request.getString("targetDeviceId") to request.getString("targetSessionId")) == cohort) {
                "Preserving Move operation $id: source/target identity changed"
            }
            request
        }
        val deviceSnapshots = cohort.associate { (device, expectedSession) ->
            val text = call("$base/devices/$device")
            val registration = text.takeUnless { it == "null" || it.isBlank() }?.let { org.json.JSONObject(it) }
            if (registration != null) check(registration.optString("sessionId") == expectedSession) {
                "Preserving device registration $device because its session changed"
            }
            if (registration != null) check(!registration.optBoolean("enabled", true)) {
                "Preserving online fixture device registration $device; it has not published offline"
            }
            device to registration
        }
        if (requireCompleteRun) check(deviceSnapshots.values.all { it != null }) {
            "Completed acceptance is missing an exact run device registration; IDs=${cohort.map { it.first }.sorted()}"
        }
        for (groupId in allGroupIds) {
            val path = "$base/groups/$groupId"
            val group = groupSnapshots[groupId] ?: continue
            val members = group.optJSONObject("members") ?: error("Owned group $groupId has no member rows")
            val timelines = group.opt("timelines")
            val timelineKeys = when (timelines) {
                null, org.json.JSONObject.NULL -> emptyList()
                is org.json.JSONObject -> {
                    val keys = timelines.keys().asSequence().toList()
                    check(keys.size < 256) { "Timeline cleanup hit the 256-row bound; preserving group $groupId" }
                    check(keys.all { it.matches(Regex("[0-9]{1,10}")) }) {
                        "Preserving group $groupId: timeline object contains a non-numeric child key"
                    }
                    keys.filter { timelines.opt(it) != org.json.JSONObject.NULL }
                }
                is org.json.JSONArray -> {
                    check(timelines.length() < 256) { "Timeline cleanup hit the 256-row bound; preserving group $groupId" }
                    (0 until timelines.length()).filter { timelines.opt(it) != null && timelines.opt(it) != org.json.JSONObject.NULL }
                        .map(Int::toString)
                }
                else -> error("Preserving group $groupId: timelines is not a JSON object or array")
            }
            timelineKeys.forEach { seq -> call("$path/timelines/$seq", "DELETE") }
            group.optJSONObject("requests")?.keys()?.asSequence()?.toList()?.forEach { id -> call("$path/requests/$id", "DELETE") }
            cohort.forEach { (device, expectedSession) ->
                check(members.getJSONObject(device).optString("sessionId") == expectedSession)
                call("$path/members/$device", "DELETE")
            }
            call("$path/head", "DELETE")
            check(call(path) == "null") { "Residual owned group metadata at $path" }
        }
        for (id in ownedMoves) {
            check(moveSnapshots.getValue(id).getString("id") == id) { "Move $id request ID changed during cleanup" }
            listOf("decision", "target", "source", "result", "request").forEach { child ->
                val childText = call("$base/ops/$id/$child")
                if (childText != "null" && childText.isNotBlank()) call("$base/ops/$id/$child", "DELETE")
            }
            check(call("$base/ops/$id") == "null") { "Residual owned Move metadata at opaque operation ID=$id" }
        }
        val removedInbox = mutableListOf<String>()
        for (device in listOf(androidId, desktopId)) {
            val inbox = inboxSnapshots.getValue(device)
            inbox.keys().asSequence().toList().forEach { id ->
                if (inbox.optJSONObject(id)?.optString("refId") in ownedRefs) {
                    call("$base/inbox/$device/$id", "DELETE")
                    removedInbox += "$device/$id"
                }
            }
        }
        for ((device, expectedSession) in cohort) {
            val path = "$base/devices/$device"
            if (deviceSnapshots[device] != null) {
                deleteDeviceIfSame(device, expectedSession)
            }
            check(call(path) == "null") { "Residual fixture device registration: $device" }
        }
        check(removedInbox.all { entry ->
            val (device, id) = entry.split('/', limit = 2)
            call("$base/inbox/$device/$id") == "null"
        }) { "Residual fixture inbox IDs: ${removedInbox.joinToString()}" }
        milestone("production metadata cleanup complete; operations=${ownedMoves.sorted()} groups=${allGroupIds.sorted()} inbox=${removedInbox.sorted()} deviceSessions=${cohort.map { it.first }.sorted()}")
    }

    private suspend fun waitForGroupMemberLeft(http: OkHttpClient, sessionProvider: suspend () -> FirebaseSession, groupId: String,
        deviceId: String, expectedSessionId: String, expectedEpoch: Long): org.json.JSONObject {
        while (true) {
            val member = readGroupMember(http, sessionProvider, groupId, deviceId)
            if (member.optString("sessionId") == expectedSessionId && member.optLong("epoch") == expectedEpoch &&
                member.optString("status") == "LEFT") return member
            delay(200)
        }
    }
}
