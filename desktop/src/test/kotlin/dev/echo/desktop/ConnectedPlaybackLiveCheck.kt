package dev.echo.desktop

import dev.echo.core.Track
import dev.echo.data.firebase.ConnectedPlaybackAction
import dev.echo.data.firebase.ConnectedPlaybackCommand
import dev.echo.data.firebase.EchoFirebaseProject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Explicit real-account acceptance. Uses only temporary players/libraries; never changes the user's queue. */
fun main(args: Array<String>): Unit = runBlocking {
    require(args.isEmpty() || args.contentEquals(arrayOf("--local-only")) || args.contentEquals(arrayOf("--android-peer")))
    val localOnly = args.firstOrNull() == "--local-only"
    val androidPeer = args.firstOrNull() == "--android-peer"
    val directory = Files.createTempDirectory("echo-connected-check-")
    val account = DesktopGoogleAccount()
    if (!localOnly) check(account.state.value.email != null) { "Sign in to Echo desktop first" }
    val wav = directory.resolve("silence.wav")
    val bytes = ByteBuffer.allocate(44 + 8_000 * 300 * 2).order(ByteOrder.LITTLE_ENDIAN)
    bytes.put("RIFF".toByteArray()).putInt(bytes.capacity() - 8).put("WAVEfmt ".toByteArray())
        .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
        .putShort(2).putShort(16).put("data".toByteArray()).putInt(bytes.capacity() - 44)
    Files.write(wav, bytes.array())
    val preferences = (1..2).map { Preferences.userRoot().node("dev/echo/acceptance/${UUID.randomUUID()}") }
    val players = (1..2).map { DesktopPlayback() }
    val controllers = players.mapIndexed { index, player ->
        DesktopConnectedPlayback(DesktopLibrary(directory.resolve("library-$index.json")), account, player, preferences[index])
    }
    val jobs = mutableListOf<Job>()
    try {
        players.forEachIndexed { index, player ->
            val tracks = (1..2).map { occurrence -> Track("local:acceptance-$index-$occurrence", "Acceptance $occurrence", "Echo",
                durationMs = 300_000, sourceUri = wav.toUri().toString()) }
            player.setVolume(0f)
            player.play(tracks)
            withTimeout(20_000) { player.state.first { it.playing && it.positionMs > 200 || it.error != null } }
            check(player.state.value.error == null) { "Native playback failed" }
        }
        if (localOnly) {
            val player = players[0]
            suspend fun apply(action: ConnectedPlaybackAction, position: Long = 0) {
                val state = player.state.value
                val now = System.currentTimeMillis()
                player.executeConnectedCommand(ConnectedPlaybackCommand(UUID.randomUUID().toString(), action, "local-check",
                    state.occurrence.toString(), state.queueRevision.toString(), position, now, now + 20_000))
                println("Dispatched local $action: index=${player.state.value.index}, playing=${player.state.value.playing}, position=${player.state.value.positionMs}")
            }
            apply(ConnectedPlaybackAction.PAUSE)
            withTimeout(5_000) { player.state.first { !it.playing } }
            apply(ConnectedPlaybackAction.PAUSE)
            check(!player.state.value.playing)
            apply(ConnectedPlaybackAction.SEEK, 25_000)
            withTimeout(5_000) { player.state.first { it.positionMs in 24_000..26_000 } }
            apply(ConnectedPlaybackAction.PLAY)
            withTimeout(5_000) { player.state.first { it.playing && it.positionMs >= 25_000 } }
            apply(ConnectedPlaybackAction.PLAY)
            delay(200)
            check(player.state.value.playing)
            val beforeNext = player.state.value
            apply(ConnectedPlaybackAction.NEXT)
            withTimeout(5_000) { player.state.first { it.index == 1 && it.playing } }
            val now = System.currentTimeMillis()
            check(runCatching { player.executeConnectedCommand(ConnectedPlaybackCommand(UUID.randomUUID().toString(),
                ConnectedPlaybackAction.PAUSE, "local-check", beforeNext.occurrence.toString(), beforeNext.queueRevision.toString(),
                0, now, now + 20_000)) }.isFailure) { "An old occurrence controlled the next track" }
            apply(ConnectedPlaybackAction.PAUSE)
            apply(ConnectedPlaybackAction.SEEK, 0)
            withTimeout(5_000) { player.state.first { it.positionMs < 1000 } }
            apply(ConnectedPlaybackAction.PREVIOUS)
            withTimeout(5_000) { player.state.first { it.index == 0 && it.playing } }
            println("PASS real native remote command adapter: pause/play idempotence, seek, next/previous, stale occurrence rejection")
            return@runBlocking
        }
        (if (androidPeer) controllers.take(1) else controllers).forEach { controller ->
            controller.setEnabled(true); jobs += launch { controller.run() }
        }
        if (androidPeer) {
            println("READY native Windows player for Android emulator acceptance")
            val controller = controllers[0]
            val androidId = withTimeout(180_000) { controller.state.first { state -> state.devices.any {
                it.label.startsWith("Android") && it.player.title == "Echo mobile acceptance 1" && it.controlEnabled && it.player.durationMs > 0
            } }.devices.first { it.label.startsWith("Android") && it.player.title == "Echo mobile acceptance 1" }.id }
            suspend fun mobile(action: ConnectedPlaybackAction, position: Long = 0, condition: (dev.echo.data.firebase.ConnectedDevice) -> Boolean) {
                val target = controller.state.value.devices.first { it.id == androidId }
                controller.sendCommand(target, action, position)
                withTimeout(25_000) { controller.state.first { !it.pending && it.message == "Remote command applied." &&
                    it.devices.any { device -> device.id == androidId && condition(device) } } }
                println("PASS Windows-to-Android $action")
                delay(2_500)
            }
            mobile(ConnectedPlaybackAction.PAUSE) { !it.player.isPlaying }
            mobile(ConnectedPlaybackAction.SEEK, 25_000) { !it.player.isPlaying && it.player.positionMs in 24_000..26_000 }
            mobile(ConnectedPlaybackAction.PLAY) { it.player.isPlaying && it.player.positionMs >= 25_000 }
            mobile(ConnectedPlaybackAction.NEXT) { it.player.title == "Echo mobile acceptance 2" }
            mobile(ConnectedPlaybackAction.PAUSE) { !it.player.isPlaying }
            mobile(ConnectedPlaybackAction.SEEK, 0) { it.player.positionMs < 1000 }
            mobile(ConnectedPlaybackAction.PREVIOUS) { it.player.title == "Echo mobile acceptance 1" }
            println("PASS actual Android emulator / native Windows remote playback over RTDB")
            delay(5_000)
            return@runBlocking
        }
        suspend fun command(sender: Int, target: Int, action: ConnectedPlaybackAction, position: Long = 0,
                            condition: (DesktopPlaybackState) -> Boolean) {
            val controller = controllers[sender]
            val id = controllers[target].deviceId
            val device = withTimeout(30_000) { controller.state.first { state -> state.devices.any { it.id == id && it.controlEnabled } }
                .devices.first { it.id == id } }
            controller.sendCommand(device, action, position)
            withTimeout(25_000) { players[target].state.first(condition) }
            withTimeout(25_000) { controller.state.first { !it.pending && it.message == "Remote command applied." } }
            println("PASS native remote $action direction $sender->$target")
            // Wait for the next published track/queue revision before sending another action.
            delay(2_500)
        }
        for ((sender, target) in listOf(0 to 1, 1 to 0)) {
            command(sender, target, ConnectedPlaybackAction.PAUSE) { !it.playing }
            command(sender, target, ConnectedPlaybackAction.SEEK, 25_000) { !it.playing && it.positionMs in 24_000..26_000 }
            command(sender, target, ConnectedPlaybackAction.PLAY) { it.playing && it.positionMs >= 25_000 }
            command(sender, target, ConnectedPlaybackAction.NEXT) { it.index == 1 && it.playing }
            command(sender, target, ConnectedPlaybackAction.PREVIOUS) { it.index == 0 || it.index == 1 && it.positionMs < 3000 }
        }
        println("PASS two real native players / RTDB presence and acknowledged controls; independent queues preserved")
    } finally {
        controllers.forEach { it.setEnabled(false); it.stop() }
        jobs.forEach { it.cancelAndJoin() }
        players.forEach { it.close() }
        if (!localOnly) runCatching {
            val session = account.firebaseSession(EchoFirebaseProject.configuration())
            val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .callTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build()
            withContext(NonCancellable + Dispatchers.IO) {
                (if (androidPeer) controllers.take(1) else controllers).forEach { controller ->
                    for (part in listOf("devices", "commands")) {
                        val url = EchoFirebaseProject.realtimeDatabaseUrl.toHttpUrl().newBuilder()
                            .addPathSegments("users/${session.uid}/playback/$part/${controller.deviceId}.json")
                            .addQueryParameter("auth", session.idToken).build()
                        http.newCall(Request.Builder().url(url).delete().build()).execute().close()
                    }
                }
            }
        }
        preferences.forEach { it.removeNode() }
        Files.list(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }
        Files.deleteIfExists(directory)
    }
}
