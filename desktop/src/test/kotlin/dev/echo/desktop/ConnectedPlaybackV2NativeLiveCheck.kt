package dev.echo.desktop

import dev.echo.core.Track
import dev.echo.core.connected.NativePlaybackBridgeV2
import dev.echo.core.connected.GroupRef
import dev.echo.data.firebase.ConnectedPlaybackV2Client
import dev.echo.data.firebase.EchoFirebaseProject
import dev.echo.data.firebase.FirebaseSession
import dev.echo.data.firebase.GroupActionV2
import dev.echo.data.provider.YouTubeProvider
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Opt-in paired acceptance using a real desktop VLC engine and Android Media3 service. */
fun main() = runBlocking {
    val production = System.getProperty("echo.connected.v2.production") == "true"
    val runId = System.getProperty("echo.connected.v2.acceptanceRun").orEmpty()
    if (production) {
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,48}"))) {
            "Production acceptance requires -PechoConnectedV2Production=true and -PechoConnectedV2AcceptanceRun=<shared-run-id>"
        }
        require(System.getProperty("echo.connected.v2.secondaryAndroidId").isNullOrBlank()) {
            "Production acceptance supports only the two-device primary role"
        }
    }
    val androidId = if (production) "acceptance-v2-$runId-android" else "v2-native-android"
    val secondaryAndroidId = System.getProperty("echo.connected.v2.secondaryAndroidId")?.takeIf { it.isNotBlank() }
    val desktopId = if (production) "acceptance-v2-$runId-windows" else "v2-native-desktop"
    require(androidId.matches(Regex("[A-Za-z0-9_-]{1,128}")) && desktopId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
    val account = if (production) DesktopGoogleAccount(java.nio.file.Path.of(
        System.getenv("LOCALAPPDATA") ?: error("Production acceptance requires LOCALAPPDATA to identify the saved Windows account"),
        "EchoMusic", "google-account.dpapi")) else null
    val config = if (production) EchoFirebaseProject.configuration() else null
    val session = if (production) account!!.firebaseSession(config!!) else demoSession("v2-native-acceptance")
    suspend fun currentSession() = (if (production) account!!.firebaseSession(config!!) else session).also {
        check(it.uid == session.uid) { "Account changed during native acceptance; preserving remote records" }
    }
    val uidFingerprint = java.security.MessageDigest.getInstance("SHA-256")
        .digest(session.uid.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(12)
    println("NATIVE_V2 [Windows] ${if (production) "PRODUCTION" else "demo"} account uid-sha256=$uidFingerprint")
    val rewrite = if (production) null else Interceptor { chain ->
        val local = chain.request().url.newBuilder().scheme("http").host("127.0.0.1").port(49154)
            .addQueryParameter("ns", "demo-echo-rules-default-rtdb").build()
        chain.proceed(chain.request().newBuilder().url(local).build())
    }
    val httpBuilder = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS)
    rewrite?.let(httpBuilder::addInterceptor)
    val http = httpBuilder
        .followRedirects(false).followSslRedirects(false).build()
    val fixtureDirectory = java.nio.file.Files.createTempDirectory("echo-v2-native-")
    val provider = YouTubeProvider()
    val player = DesktopPlayback(provider, recoveryFile = fixtureDirectory.resolve("desktop-recovery.json").toFile(),
        findLocalTrack = { null }, onHistory = { _, _, _ -> })
    val trackA = Track("youtube:aqz-KE-bpKQ", "Big Buck Bunny / Native", "Blender Foundation", durationMs = 596_000,
        provider = "youtube", sourceUri = "https://www.youtube.com/watch?v=aqz-KE-bpKQ")
    val trackB = Track("youtube:eRsGyueVLvQ", "Sintel - Durian Open Movie", "Blender Foundation", durationMs = 887_000,
        provider = "youtube", sourceUri = "https://www.youtube.com/watch?v=eRsGyueVLvQ")
    val client = ConnectedPlaybackV2Client(EchoFirebaseProject.realtimeDatabaseUrl, desktopId,
        "Windows native acceptance", { currentSession() }, player as NativePlaybackBridgeV2, http,
        testDiagnosticLogger = { println(it) })
    var run: Job? = null
    var disconnectedCoordinatorForAcceptance = false
    fun milestone(message: String) = println("NATIVE_V2 [$desktopId] $message")
    suspend fun stopClientRun() {
        run?.let { job ->
            job.cancel()
            withTimeout(60_000) { job.join() }
            check(job.isCompleted) { "Windows connected playback client did not stop within 60 seconds" }
            run = null
        }
    }
    try {
        check(System.getProperty("echo.connected.v2.live") == "true") { "Run only with -PechoConnectedV2Live=true" }
        check(player.state.value.queue.isEmpty()) { "The paired runner requires an idle temporary Windows player" }
        check(player.capabilities().containsAll(setOf("move", "group")))
        player.setVolume(0f)
        withTimeout(5_000) { player.state.first { it.volume == 0f } }
        run = launch { client.run() }

        milestone("waiting for Android v2 peer")
        withTimeout(180_000) {
            client.state.first { state -> state.devices.any { it.id == androidId && it.enabled &&
                it.capabilities.containsAll(setOf("move", "group")) && it.snapshot.ready } }
        }
        val android = client.state.value.devices.first { it.id == androidId }
        milestone("Android peer ready; preparing real VLC source queue")
        val initialAndroidFence = android.snapshot.fence
        val distinctReady = android.label.contains("B-ready") && runCatching { provider.resolve(trackB) }.getOrNull() != null
        check(distinctReady) { "A second public recording is unavailable; distinct song-change acceptance cannot pass" }
        val queue = listOf(trackA, trackB, trackA)
        player.play(queue)
        withTimeout(180_000) { player.state.first { it.playing || it.error != null } }
        check(player.state.value.error == null) { "VLC could not play the public YouTube acceptance recording" }
        withTimeout(30_000) { player.state.first { it.playing && it.positionMs > 0 } }
        milestone("real VLC source queue ready: A/B/A")
        val sourceCapture = player.capture()
        val transferredOccurrenceId = sourceCapture.queue.entries.first().id
        val nativeStageQueue = dev.echo.core.connected.TransferQueue(
            listOf(dev.echo.core.connected.QueueOccurrence(java.util.UUID.randomUUID().toString(), trackA.let {
                dev.echo.core.connected.PortableRecording(it.id, it.title, it.artist, it.durationMs)
            })), dev.echo.core.connected.RepeatModeV2.OFF, false)
        val stageFence = player.fence()
        val stageOperation = "v2-native-stage-guard-${java.util.UUID.randomUUID()}"
        val readyStage = player.prepare(stageOperation, nativeStageQueue, stageFence)
        check(readyStage.ready && player.fence() == stageFence) { "Real VLC preload changed the active source" }
        val competingStageRejected = runCatching {
            player.prepare("v2-native-competing-${java.util.UUID.randomUUID()}", nativeStageQueue, stageFence)
        }.isFailure
        check(competingStageRejected) { "A second native operation replaced a ready VLC stage" }
        player.abort(stageOperation)
        check(player.observe().playing && player.observe().currentOccurrenceId == transferredOccurrenceId) {
            "Aborting the competing native stage changed the active VLC source"
        }
        val privateBeforeGroupStage = player.observe()
        val privateQueueBeforeGroupStage = player.capture().queue
        val stagedRef = GroupRef(java.util.UUID.randomUUID().toString(), 1)
        val stagedGroupOperation = "v2-native-staged-group-${java.util.UUID.randomUUID()}"
        check(player.prepare(stagedGroupOperation, nativeStageQueue, player.fence(), stagedRef).ready)
        check(runCatching { player.leaveGroupPaused(java.util.UUID.randomUUID().toString(), 1) }.isFailure) {
            "A mismatched group leave must not discard a different staged ref"
        }
        val stageLeave = player.leaveGroupPaused(stagedRef.groupId, stagedRef.epoch)
        check(!stageLeave.playing && stageLeave.currentOccurrenceId == privateBeforeGroupStage.currentOccurrenceId &&
            player.capture().queue == privateQueueBeforeGroupStage) {
            "Cancelling before group activation changed the private VLC queue or failed to pause it"
        }
        val leaveRetry = player.leaveGroupPaused(stagedRef.groupId, stagedRef.epoch)
        check(!leaveRetry.playing && leaveRetry.currentOccurrenceId == stageLeave.currentOccurrenceId) {
            "Retrying an already restored staged-group leave changed private playback"
        }
        player.toggle()
        withTimeout(20_000) { player.state.first { it.playing } }
        check(player.observe().currentOccurrenceId == transferredOccurrenceId) {
            "Resuming after staged-group cleanup changed the active VLC occurrence"
        }
        milestone("real VLC staged-group leave rejected wrong refs, discarded the exact stage, and restored idempotently")
        milestone("real VLC preload stayed silent/ready; competing operation rejected without changing source")
        milestone("requesting Windows -> Android Move")
        client.moveTo(androidId, replacePlaying = true)
        milestone("Windows -> Android Move returned: ${client.state.value.move}")
        check(client.state.value.move?.status?.name == "COMPLETE") { "Windows-to-Android Move did not reconcile: ${client.state.value.move}" }
        val afterMove = player.observe()
        check(!afterMove.playing) { "Move must pause the source only after target observation" }
        withTimeout(90_000) {
            client.state.first { state -> state.devices.firstOrNull { it.id == androidId }?.let {
                it.snapshot.currentOccurrenceId == transferredOccurrenceId &&
                    it.snapshot.fence.transportRevision > initialAndroidFence.transportRevision
            } == true }
        }
        milestone("Android target observed the transferred occurrence and advanced transport revision; waiting for reverse Move activation")
        val afterFirstMoveFence = player.fence()
        withTimeout(120_000) {
            player.state.first { it.queueRevision > afterFirstMoveFence.queueRevision &&
                it.current?.let { current -> current.id == trackA.id || current.id == trackB.id } == true && !it.playing }
        }
        milestone("reverse Move replaced the Windows queue and activated it paused; starting Together")

        val targetIds = listOf(androidId) + listOfNotNull(secondaryAndroidId)
        withTimeout(180_000) { client.state.first { state -> targetIds.all { targetId ->
            state.devices.any { it.id == targetId && it.enabled && it.capabilities.contains("group") }
        } } }
        fun expectedSessions(): Map<String, String> = targetIds.associateWith { targetId ->
            client.state.value.devices.firstOrNull { it.id == targetId && it.enabled }?.sessionId
                ?: error("Selected native peer $targetId has no current session")
        }
        client.startTogether(targetIds, replacePlaying = true, expectedTargetSessions = expectedSessions())
        milestone("Together request returned; waiting for ACTIVE group, move=${client.state.value.move}")
        withTimeout(120_000) { client.state.first { it.group?.status?.name == "ACTIVE" &&
            it.group.members.size == targetIds.size + 1 } }
        val firstGroupRef = client.state.value.group!!.ref
        milestone("Together group ACTIVE; requesting PLAY")
        client.requestGroup(GroupActionV2.PLAY)
        withTimeout(60_000) { client.state.first { it.group?.timeline?.playing == true } }
        withTimeout(60_000) { client.state.first { state -> targetIds.all { targetId ->
            state.devices.firstOrNull { it.id == targetId }?.snapshot?.playing == true
        } } }
        milestone("PLAY observed by every Android native peer")
        client.requestGroup(GroupActionV2.NEXT)
        milestone("NEXT requested; waiting for shared cursor advance")
        val next = withTimeout(120_000) {
            client.state.first { it.group?.timeline?.let { timeline -> timeline.cursor == 1 && timeline.sequence > 0 } == true }
                .group!!.timeline!!
        }
        withTimeout(90_000) {
            client.state.first { state -> targetIds.all { targetId ->
                state.devices.firstOrNull { it.id == targetId }?.snapshot?.currentOccurrenceId == next.queue.entries[next.cursor].id
            } }
        }
        milestone("NEXT actual occurrence observed by every Android native peer")
        client.requestGroup(GroupActionV2.PAUSE)
        milestone("shared cursor advanced; PAUSE requested")
        withTimeout(60_000) { client.state.first { it.group?.timeline?.playing == false } }
        withTimeout(60_000) { client.state.first { state -> targetIds.all { targetId ->
            state.devices.firstOrNull { it.id == targetId }?.snapshot?.playing == false
        } } }
        client.requestGroup(GroupActionV2.SEEK, 15_000)
        milestone("PAUSE observed; SEEK requested")
        withTimeout(60_000) { client.state.first { it.group?.timeline?.positionMs == 15_000L } }
        val seekSnapshotTargets = targetIds.filterNot { it == secondaryAndroidId }
        withTimeout(60_000) { client.state.first { state -> seekSnapshotTargets.all { targetId ->
            state.devices.firstOrNull { it.id == targetId }?.snapshot?.let { !it.playing && kotlin.math.abs(it.positionMs - 15_000L) <= 2_000L } == true
        } } }
        milestone("PAUSE and SEEK snapshot observed by primary; secondary's native check and coordinator ACK gate its leave")
        if (secondaryAndroidId != null) {
            val left = withTimeout(120_000) {
                waitForGroupMemberStatus(http, ::currentSession, firstGroupRef.groupId, secondaryAndroidId,
                    client.state.value.devices.first { it.id == secondaryAndroidId }.sessionId, firstGroupRef.epoch, "LEFT")
            }
            check(left.getString("status") == "LEFT")
            withTimeout(15_000) {
                client.state.first { state -> state.devices.firstOrNull { it.id == secondaryAndroidId }?.snapshot?.let {
                    !it.playing && it.currentOccurrenceId == left.optString("observedOccurrenceId").takeUnless(String::isBlank)
                } == true }
            }
            withTimeout(15_000) {
                client.state.first { state -> state.group?.status?.name == "ACTIVE" &&
                    state.group.members.count { it.status.name !in setOf("LEFT", "DISCONNECTED") } == 2 }
            }
            milestone("secondary follower left; Windows and primary Android remain ACTIVE with two fresh members")
        }
        client.leaveTogether()
        milestone("SEEK observed; LEAVE requested")
        withTimeout(90_000) { client.state.first { it.group == null } }
        check(!player.state.value.playing) { "Leaving Together must restore the Windows independent queue paused" }
        check(player.state.value.queue.size == 3) { "Leaving Together lost duplicate queue occurrences" }
        val primaryLeft = awaitGroupMemberLeft(http, ::currentSession, firstGroupRef.groupId, androidId,
            client.state.value.devices.first { it.id == androidId }.sessionId, firstGroupRef.epoch)
        withTimeout(15_000) {
            client.state.first { state -> state.devices.firstOrNull { it.id == androidId }?.snapshot?.let {
                !it.playing && it.currentOccurrenceId == primaryLeft.optString("observedOccurrenceId").takeUnless(String::isBlank)
            } == true }
        }
        milestone("PASS Together shared queue: Next, Pause, Seek, Leave and paused duplicate-queue restore")

        // Rejoin exercises fresh group epoch/backup retention; leave is again explicit and paused.
        client.startTogether(targetIds, replacePlaying = true, expectedTargetSessions = expectedSessions())
        milestone("rejoin requested; waiting for new ACTIVE group")
        val secondGroup = withTimeout(120_000) { client.state.first { it.group?.status?.name == "ACTIVE" &&
            it.group.members.size == targetIds.size + 1 } }
        if (secondaryAndroidId != null) check(secondGroup.group?.members?.any { it.deviceId == secondaryAndroidId } == true)
        client.requestGroup(GroupActionV2.PLAY)
        withTimeout(60_000) { client.state.first { it.group?.timeline?.playing == true } }
        withTimeout(60_000) { client.state.first { state -> targetIds.all { targetId ->
            state.devices.firstOrNull { it.id == targetId }?.snapshot?.playing == true
        } && player.state.value.playing } }
        milestone("second Together epoch playing on Windows and every Android peer; disconnecting coordinator")
        disconnectedCoordinatorForAcceptance = true
        client.close()
        stopClientRun()
        delay(25_000) // The 15-second RTDB lease must expire for the emulator to recover its backup.
        milestone("PASS Android coordinator-disconnect window elapsed with distinct A/B/A traversal")
        player.leaveConnectedGroupPaused() // Local-only cleanup; the coordinator was intentionally not sent LEAVE.
        check(player.state.value.displacedMoveOperationId != null) { "The Windows target did not retain its displaced Move queue" }
        milestone("restoring latest displaced Windows queue through the native recovery path")
        player.restoreLatestDisplacedPaused()
        check(!player.state.value.playing && player.state.value.current?.id == trackA.id &&
            player.state.value.displacedMoveOperationId == null) {
            "The displaced Windows queue was not restored paused and cleared from recovery history"
        }
        milestone("PASS restored displaced Windows queue paused")
    } finally {
        withContext(NonCancellable) {
            if (!disconnectedCoordinatorForAcceptance && client.state.value.group != null) runCatching { client.leaveTogether() }
            client.close()
            stopClientRun()
            if (production) milestone("Windows client closed; its exact device session published offline for Android-scoped cleanup " +
                "runId=$runId groups=${client.state.value.group?.ref?.groupId?.let(::listOf).orEmpty()} " +
                "operations=${client.state.value.move?.id?.let(::listOf).orEmpty()} device=$desktopId session=${player.sessionId}")
            runCatching { player.leaveConnectedGroupPaused() }
            player.close()
            java.nio.file.Files.list(fixtureDirectory).use { files -> files.forEach { java.nio.file.Files.deleteIfExists(it) } }
            java.nio.file.Files.deleteIfExists(fixtureDirectory)
            milestone("cleanup complete")
        }
    }
}

private fun demoSession(uid: String): FirebaseSession {
    val now = System.currentTimeMillis() / 1000
    fun encode(value: Any) = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(org.json.JSONObject(value as Map<*, *>).toString().toByteArray())
    val header = encode(mapOf("alg" to "none", "typ" to "JWT"))
    val claims = encode(mapOf("aud" to "demo-echo-rules", "iss" to "https://securetoken.google.com/demo-echo-rules",
        "sub" to uid, "user_id" to uid, "iat" to now, "exp" to now + 3600, "email" to "$uid@example.test",
        "email_verified" to true, "firebase" to mapOf("sign_in_provider" to "google.com", "identities" to mapOf("google.com" to listOf(uid)))))
    return FirebaseSession(uid, "$uid@example.test", "$header.$claims.", "local-demo-refresh", System.currentTimeMillis() + 3_600_000)
}

private suspend fun readGroupMemberStatus(http: OkHttpClient, sessionProvider: suspend () -> FirebaseSession, groupId: String,
    deviceId: String): org.json.JSONObject = withContext(Dispatchers.IO) {
    val session = sessionProvider()
    require(groupId.matches(Regex("[A-Za-z0-9_-]{1,128}")) && deviceId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
    val url = EchoFirebaseProject.realtimeDatabaseUrl.toHttpUrl().newBuilder()
        .addPathSegments("users/${session.uid}/playbackV2/groups/$groupId/members/$deviceId.json")
        .addQueryParameter("auth", session.idToken).build()
    val text = http.newCall(okhttp3.Request.Builder().url(url).get().build()).execute().use { response ->
        check(response.isSuccessful)
        response.body?.string().orEmpty()
    }
    check(text.isNotBlank() && text != "null") { "The Android group membership row disappeared" }
    org.json.JSONObject(text)
}

private suspend fun waitForGroupMemberStatus(http: OkHttpClient, sessionProvider: suspend () -> FirebaseSession, groupId: String,
    deviceId: String, expectedSessionId: String, expectedEpoch: Long, expectedStatus: String): org.json.JSONObject {
    while (true) {
        val member = readGroupMemberStatus(http, sessionProvider, groupId, deviceId)
        if (member.optString("sessionId") == expectedSessionId && member.optLong("epoch") == expectedEpoch &&
            member.optString("status") == expectedStatus) return member
        delay(200)
    }
}

private suspend fun awaitGroupMemberLeft(http: OkHttpClient, sessionProvider: suspend () -> FirebaseSession, groupId: String,
    deviceId: String, expectedSessionId: String, expectedEpoch: Long) =
    waitForGroupMemberStatus(http, sessionProvider, groupId, deviceId, expectedSessionId, expectedEpoch, "LEFT")
