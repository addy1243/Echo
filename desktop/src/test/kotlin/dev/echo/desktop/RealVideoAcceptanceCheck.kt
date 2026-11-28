package dev.echo.desktop

import dev.echo.core.Track
import dev.echo.data.provider.YouTubeProvider
import java.awt.Canvas
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Explicit live acceptance only; uses one public recording and a temporary recovery file. */
fun main() = runBlocking {
    check(!GraphicsEnvironment.isHeadless()) { "A Windows desktop session is required for native VLC video output." }
    val temp = Files.createTempDirectory("echo-real-video-acceptance-")
    val frame = Frame("Echo native video acceptance").apply {
        add(Canvas())
        setSize(480, 270)
        setLocationRelativeTo(null)
        isVisible = true
    }
    val canvas = frame.components.single() as Canvas
    val track = Track("youtube:aqz-KE-bpKQ", "Big Buck Bunny", "Blender Foundation",
        provider = "youtube", sourceUri = "https://www.youtube.com/watch?v=aqz-KE-bpKQ")
    val player = DesktopPlayback(YouTubeProvider(), recoveryFile = temp.resolve("recovery.json").toFile(), stageVideoEnabled = true)
    try {
        player.setVolume(0f)
        player.play(listOf(track))
        withTimeout(120_000) { player.state.first { it.error != null || it.playing && it.positionMs >= 3_000 } }
        check(player.state.value.error == null) { "Real YouTube audio did not start." }
        val audio = player.state.value
        println("PASS native YouTube audio: playing=${audio.playing} positionMs=${audio.positionMs}")

        val audioPosition = audio.positionMs
        check(player.bindVideoCanvas(canvas, enableIfNeeded = true, expectedOccurrence = audio.occurrence)) {
            "Could not switch the production player to video: ${player.state.value.videoError ?: "unknown error"}"
        }
        withTimeout(120_000) { player.state.first { it.videoMode && !it.videoLoading && it.playing } }
        val video = player.state.value
        check(video.occurrence == audio.occurrence && video.current?.id == track.id) { "Video switch changed the playing occurrence." }
        check(video.positionMs >= audioPosition - 1_000) { "Video switch moved playback backward." }
        val deadline = System.nanoTime() + 60_000_000_000L
        while (!player.hasDecodedVideoFrame(canvas) && System.nanoTime() < deadline) delay(500)
        val decoded = player.hasDecodedVideoFrame(canvas)
        println("RESULT native video: playing=${video.playing} positionMs=${video.positionMs} decodedFrame=$decoded")

        var nonPlayingSamples = 0
        repeat(20) {
            delay(1_000)
            val sample = player.state.value
            check(sample.occurrence == video.occurrence && sample.videoMode && sample.error == null) { "Video playback changed unexpectedly." }
            if (!sample.playing) nonPlayingSamples++
        }
        println("RESULT native video continuity: samples=20 nonPlayingSamples=$nonPlayingSamples")
        check(nonPlayingSamples == 0) { "Video was not playing during one or more sampled intervals." }

        val videoPosition = player.state.value.positionMs
        check(player.returnVideoCanvas(canvas, restoreVideo = false)) {
            "Could not return the production player to audio: ${player.state.value.videoError ?: "unknown error"}"
        }
        val audioAgain = player.state.value
        check(!audioAgain.videoMode && audioAgain.occurrence == video.occurrence && audioAgain.current?.id == track.id) {
            "Audio return changed the playing occurrence or remained in video mode."
        }
        check(audioAgain.positionMs >= videoPosition - 1_000) { "Audio return moved playback backward." }
        withTimeout(30_000) { player.state.first { it.playing && !it.videoMode } }
        println("PASS native audio return: playing=${player.state.value.playing} positionMs=${player.state.value.positionMs} occurrencePreserved=true")
        check(decoded) { "VLC did not report a decoded video frame on the native Canvas." }
    } finally {
        player.close()
        frame.dispose()
        temp.toFile().deleteRecursively()
    }
}
