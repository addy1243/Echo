package dev.echo.desktop

import dev.echo.core.PlayableSource
import dev.echo.core.Track
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import uk.co.caprica.vlcj.factory.MediaPlayerFactory

/** Native VLC check for cache reuse after the synthetic origin becomes unavailable. */
fun main() {
    val temp = Files.createTempDirectory("echo-stage-cache-vlc-check-")
    val wav = Files.createTempFile(temp, "fixture-", ".wav")
    val track = Track("youtube:abcdefghijk", "Synthetic fixture", "Echo", provider = "youtube")
    val originAvailable = AtomicBoolean(true)
    val resolveCalls = AtomicInteger()
    val requestCalls = AtomicInteger()
    Files.write(wav, makeWav())
    val wavBytes = Files.readAllBytes(wav)
    val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
        requestCalls.incrementAndGet()
        check(originAvailable.get()) { "Synthetic origin is unavailable" }
        okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("synthetic fixture")
            .body(object : okhttp3.ResponseBody() {
                override fun contentType() = "audio/webm".toMediaType()
                override fun contentLength() = wavBytes.size.toLong()
                override fun source() = okio.Buffer().write(wavBytes)
            }).build()
    }.build()
    val cache = DesktopStageMediaCache({
        resolveCalls.incrementAndGet()
        check(originAvailable.get()) { "Synthetic origin is unavailable" }
        PlayableSource("https://rr1.googlevideo.com/videoplayback")
    }, client, temp)
    var factory: MediaPlayerFactory? = null
    var player: uk.co.caprica.vlcj.player.base.MediaPlayer? = null
    var cached: CachedStageFile? = null
    try {
        val prepared = runBlocking { cache.prepare(track) }
        check(prepared.file.readBytes().contentEquals(wavBytes)) { "Cache did not preserve the generated WAV bytes" }
        prepared.close()

        originAvailable.set(false)
        cached = runBlocking { cache.prepare(track) }
        check(cached!!.file.readBytes().contentEquals(wavBytes)) { "Cache reuse changed the generated WAV bytes" }
        check(resolveCalls.get() == 1 && requestCalls.get() == 1) {
            "Cache reuse touched the unavailable origin (resolve=${resolveCalls.get()}, request=${requestCalls.get()})"
        }

        factory = MediaPlayerFactory("--aout=dummy", "--no-video", "--quiet", "--no-media-library", "--no-metadata-network-access")
        player = factory.mediaPlayers().newMediaPlayer()
        check(player.media().play(cached!!.file.absolutePath)) { "VLC rejected the cached fixture file" }
        val deadline = System.nanoTime() + 8_000_000_000L
        while (player.status().time() < 700 && System.nanoTime() < deadline) Thread.sleep(50)
        check(player.status().time() >= 700) { "VLC did not advance through cached audio; time=${player.status().time()}ms" }
        check(resolveCalls.get() == 1 && requestCalls.get() == 1) { "VLC playback unexpectedly contacted the origin" }
        println("PASS native VLC Stage cache: generated WAV bytes were reused and played with dummy output after origin disabled; origin resolutions=1, requests=1")
    } finally {
        cached?.close()
        player?.controls()?.stop()
        player?.release()
        factory?.release()
        cache.close()
        Files.deleteIfExists(wav)
        Files.deleteIfExists(temp)
    }
}

private fun makeWav(): ByteArray {
    val sampleRate = 44_100
    val samples = ByteArray(sampleRate * 4 * 2)
    for (i in 0 until samples.size / 2) {
        val value = (kotlin.math.sin(2 * Math.PI * 440 * i / sampleRate) * 3_000).toInt()
        samples[i * 2] = value.toByte(); samples[i * 2 + 1] = (value shr 8).toByte()
    }
    val raw = java.io.ByteArrayOutputStream()
    AudioInputStream(ByteArrayInputStream(samples), AudioFormat(sampleRate.toFloat(), 16, 1, true, false), (samples.size / 2).toLong()).use {
        AudioSystem.write(it, AudioFileFormat.Type.WAVE, raw)
    }
    return raw.toByteArray()
}
