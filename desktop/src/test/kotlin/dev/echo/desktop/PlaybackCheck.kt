package dev.echo.desktop

import dev.echo.core.Track
import dev.echo.data.provider.YouTubeProvider
import java.nio.file.Files
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.math.sin

fun main(args: Array<String>) = runBlocking {
    val file = Files.createTempFile("echo-desktop-check", ".wav").toFile()
    val sampleRate = 44_100
    val data = ByteArray(sampleRate * 12 * 2)
    for (index in 0 until data.size / 2) {
        val value = (sin(2 * Math.PI * 440 * index / sampleRate) * 500).toInt()
        data[index * 2] = value.toByte(); data[index * 2 + 1] = (value shr 8).toByte()
    }
    AudioInputStream(data.inputStream(), AudioFormat(sampleRate.toFloat(), 16, 1, true, false), (data.size / 2).toLong()).use {
        AudioSystem.write(it, AudioFileFormat.Type.WAVE, file)
    }
    val histories = mutableListOf<String>()
    val provider = YouTubeProvider()
    val playback = DesktopPlayback(provider) { _, event, listened -> histories += "$event:$listened" }
    fun tracePlaybackState(phase: String) {
        val state = playback.state.value
        val errorType = when {
            state.error == null -> "none"
            state.error.contains("VLC", ignoreCase = true) -> "vlc"
            state.error.contains("offline", ignoreCase = true) -> "offline"
            state.error.contains("audio", ignoreCase = true) -> "audio"
            else -> "other"
        }
        println("DIAG playbackCheck phase=$phase currentIndex=${state.index} playing=${state.playing} loading=${state.loading} " +
            "ended=${state.ended} positionMs=${state.positionMs} durationMs=${state.durationMs} errorType=$errorType")
    }
    try {
        playback.setVolume(0.05f)
        val track = Track("local:check", "Desktop playback check", "Echo", durationMs = 12_000, sourceUri = file.toURI().toString())
        tracePlaybackState("before-first-play")
        playback.play(listOf(track, track))
        tracePlaybackState("first-play-submitted")
        try {
            withTimeout(20_000) { playback.state.first { it.error != null || it.playing && it.positionMs > 1000 } }
        } catch (timeout: TimeoutCancellationException) {
            tracePlaybackState("first-play-timeout")
            throw timeout
        }
        tracePlaybackState("first-play-state-observed")
        check(playback.state.value.error == null) { playback.state.value.error.orEmpty() }
        playback.toggle(); delay(600)
        check(!playback.state.value.playing) { "Pause did not apply" }
        val pausedAt = playback.state.value.positionMs
        delay(600)
        check(playback.state.value.positionMs <= pausedAt + 150) { "Paused playback advanced" }
        val paused = playback.state.value
        check(playback.applySmartQueue(listOf(track, track, track), paused.occurrence, track.id, paused.queueRevision))
        check(playback.state.value.positionMs == paused.positionMs && !playback.state.value.playing && playback.state.value.occurrence == paused.occurrence) { "Smart queue restarted paused playback" }
        check(!playback.applySmartQueue(listOf(track), paused.occurrence, track.id, paused.queueRevision)) { "Stale Smart queue was applied" }
        val reordered = playback.state.value
        check(playback.moveUpcoming(1, 2, reordered.occurrence, reordered.queueRevision))
        check(!playback.removeUpcoming(0, playback.state.value.occurrence, playback.state.value.queueRevision)) { "Current row was removed" }
        val removable = playback.state.value
        check(playback.removeUpcoming(2, removable.occurrence, removable.queueRevision))
        playback.seek(5000); delay(500)
        playback.toggle()
        withTimeout(10_000) { playback.state.first { it.playing && it.positionMs >= 5000 } }
        playback.next()
        withTimeout(10_000) { playback.state.first { it.index == 1 && it.playing && it.positionMs > 500 } }
        check(histories.any { it.startsWith("SKIPPED:") }) { "Actual listening was not recorded" }
        playback.setSleepTimerMillis(400)
        withTimeout(5000) { playback.state.first { it.sleepRemainingMs == null && !it.playing } }
        check(playback.state.value.queue.size == 2 && playback.state.value.index == 1) { "Sleep timer cleared the queue" }
        playback.toggle()
        withTimeout(5000) { playback.state.first { it.playing } }
        playback.setSleepTimerMillis(1000)
        playback.setSleepTimer(null)
        delay(1200)
        check(playback.state.value.playing && playback.state.value.sleepRemainingMs == null) { "Cancelled timer paused playback" }
        val beforeSearch = playback.state.value
        playback.searchSelected(track)
        withTimeout(10_000) { playback.state.first { it.playing && it.occurrence != beforeSearch.occurrence } }
        check(playback.state.value.queue.size == 1) { "Search submitted unrelated results" }
        playback.append(listOf(track, track))
        withTimeout(5000) { playback.state.first { it.queue.size == 3 } }
        playback.setRepeatMode(DesktopRepeatMode.ALL)
        withTimeout(5000) { playback.state.first { it.repeatMode == DesktopRepeatMode.ALL } }
        check(playback.jumpTo(2, playback.state.value.queueRevision))
        withTimeout(10_000) { playback.state.first { it.index == 2 && it.playing } }
        playback.next()
        withTimeout(10_000) { playback.state.first { it.index == 0 && it.playing } }
        playback.setRepeatMode(DesktopRepeatMode.ONE)
        withTimeout(5000) { playback.state.first { it.repeatMode == DesktopRepeatMode.ONE } }
        val repeatingOccurrence = playback.state.value.occurrence
        playback.seek(11_000)
        withTimeout(10_000) { playback.state.first { it.occurrence != repeatingOccurrence && it.index == 0 && it.playing } }
        check(histories.count { it.startsWith("PLAYED:") } == histories.count { it.startsWith("SKIPPED:") || it.startsWith("COMPLETED:") || it.startsWith("INTERRUPTED:") } + 1) { "Actual starts or terminal history events were duplicated" }
        println("PASS native local audio: advancing time, pause, seek, resume, duplicate next, actual listening event")
        println("PASS native queue parity: paused Smart queue retention, stale rejection, reorder/remove guards, search replacement, append, repeat-all wrap, repeat-one replay, sleep expiry/cancel")
        playback.toggle()
        checkNativeOfflineAudio(file, provider)
        if ("--youtube" in args) {
            val recording = Track("youtube:aqz-KE-bpKQ", "Big Buck Bunny", "Blender Foundation", provider = "youtube", sourceUri = "https://www.youtube.com/watch?v=aqz-KE-bpKQ")
            val results = provider.search("Blender Foundation Big Buck Bunny")
            check(results.isNotEmpty()) { "Live search returned no recordings" }
            println("PASS live YouTube search: ${results.size} recordings")
            playback.play(listOf(recording))
            withTimeout(120_000) { playback.state.first { it.error != null || it.playing && it.positionMs > 2000 } }
            check(playback.state.value.error == null) { playback.state.value.error.orEmpty() }
            playback.seek(10_000)
            withTimeout(15_000) { playback.state.first { it.positionMs >= 10_000 && it.playing } }
            println("PASS real YouTube native audio: advancing time and seek through validated streaming proxy")
            val smartSnapshot = playback.state.value
            var relatedIds = emptySet<String>()
            val smart = prepareDesktopSmartShuffle(listOf(recording, recording, recording), recording, DesktopLibraryState(),
                related = { seed -> provider.related(seed).also { relatedIds = it.map(Track::id).toSet() } }, search = provider::search)
            check(smart.queue.first() == recording && smart.queue.count { it.id == recording.id } == 3)
            check(smart.queue.size == 4 && smart.queue[3].id in relatedIds) { "Smart shuffle did not insert a genuine related recording" }
            check(playback.applySmartQueue(smart.queue, smartSnapshot.occurrence, recording.id, smartSnapshot.queueRevision))
            check(playback.state.value.occurrence == smartSnapshot.occurrence && playback.state.value.positionMs >= smartSnapshot.positionMs && playback.state.value.playing) { "Live Smart shuffle restarted current audio" }
            println("PASS live Smart shuffle: recording-related insertion after three preserved original occurrences; actual playback source/position retained")
        }
    } finally { playback.close(); file.delete() }
}

private suspend fun checkNativeOfflineAudio(wave: java.io.File, provider: YouTubeProvider) {
    val root = Files.createTempDirectory("echo-offline-native-check")
    val track = Track("youtube:abcdefghijk", "Synthetic saved recording", "Echo", durationMs = 12_000,
        provider = "youtube", sourceUri = "https://www.youtube.com/watch?v=abcdefghijk")
    val audio = root.resolve(dev.echo.data.offline.offlineFileName(track.id)).toFile()
    val manifest = root.resolve("manifest.json")
    wave.copyTo(audio)
    val entry = org.json.JSONObject().put("id", track.id).put("title", track.title).put("artist", track.artist)
        .put("duration", track.durationMs).put("status", "COMPLETE").put("bytes", audio.length())
        .put("sha256", dev.echo.data.offline.audioDigest(audio))
    Files.writeString(manifest, org.json.JSONObject().put("version", 1).put("offline", true)
        .put("entries", org.json.JSONArray().put(entry)).toString())
    try {
        DesktopOfflineRepository(provider, root).use { offline ->
            DesktopPlayback(provider, offline).use { engine ->
                engine.setVolume(0.05f)
                engine.play(listOf(track))
                withTimeout(20_000) { engine.state.first { it.error != null || it.playing && it.positionMs > 1000 } }
                check(engine.state.value.error == null) { "Verified offline audio did not play" }
                engine.seek(5000)
                withTimeout(5000) { engine.state.first { it.playing && it.positionMs >= 5000 } }
                engine.play(listOf(track.copy(id = "youtube:abcdefghij2", sourceUri = "https://www.youtube.com/watch?v=abcdefghij2")))
                withTimeout(5000) { engine.state.first { it.error != null } }
                check(!engine.state.value.playing && engine.state.value.error!!.contains("Offline"))
            }
        }
        println("PASS native offline audio: verified saved bytes advance and seek; unsaved recording blocked in offline-only mode")
    } finally {
        Files.deleteIfExists(audio.toPath()); Files.deleteIfExists(manifest); Files.deleteIfExists(root)
    }
}
