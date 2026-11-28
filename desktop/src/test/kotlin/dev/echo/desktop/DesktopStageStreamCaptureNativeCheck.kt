package dev.echo.desktop

import dev.echo.core.PlayableSource
import dev.echo.core.Track
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Comparator
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import uk.co.caprica.vlcj.factory.MediaPlayerFactory

/** Opt-in native check: play a synthetic live source through VLC and require its bytes to fill Stage cache. */
fun main() {
    val temp = Files.createTempDirectory("echo-stage-stream-vlc-check-")
    val wav = makeStreamFixtureWav()
    val track = Track("youtube:abcdefghijk", "Synthetic live fixture", "Echo", provider = "youtube")
    val source = PlayableSource("https://rr1.googlevideo.com/videoplayback?itag=251", mimeType = "audio/webm")
    val liveRequests = CopyOnWriteArrayList<String>()
    val cacheRequests = AtomicInteger()
    val resolves = AtomicInteger()
    val upstreamDisabled = AtomicBoolean(false)
    val origin = OkHttpClient.Builder().addInterceptor { chain ->
        liveRequests += "${chain.request().method} ${chain.request().header("Range") ?: "full"}"
        check(!upstreamDisabled.get()) { "Synthetic origin is disabled after relay handoff" }
        fixtureResponse(chain.request(), wav)
    }.build()
    val cacheClient = OkHttpClient.Builder().addInterceptor { chain ->
        cacheRequests.incrementAndGet()
        fixtureResponse(chain.request(), wav)
    }.build()
    val cache = DesktopStageMediaCache({
        resolves.incrementAndGet()
        source
    }, cacheClient, temp)
    val capture = cache.beginStreamCapture(track, source) ?: error("Could not start Stage stream capture")
    val relay = DesktopAudioSource(source, stageCapture = capture, client = origin) { source }
    var factory: MediaPlayerFactory? = null
    var player: uk.co.caprica.vlcj.player.base.MediaPlayer? = null
    var prepared: CachedStageFile? = null
    try {
        factory = MediaPlayerFactory("--aout=dummy", "--no-video", "--quiet", "--no-media-library", "--no-metadata-network-access")
        player = factory.mediaPlayers().newMediaPlayer()
        check(player.media().play(relay.uri, ":demux=wav")) { "VLC rejected the synthetic loopback audio stream" }

        val firstRequestDeadline = System.nanoTime() + 8_000_000_000L
        while (liveRequests.isEmpty() && System.nanoTime() < firstRequestDeadline) Thread.sleep(25)
        check(liveRequests.isNotEmpty()) { "VLC did not request the loopback source" }

        // While VLC is still using the source, prepare must wait for captured bytes rather than download again.
        val earlyPrepare = runCatching { runBlocking { cache.prepare(track) } }
        earlyPrepare.getOrNull()?.close()
        earlyPrepare.exceptionOrNull()?.let { check(it is StageStreamCapturePending) { "Unexpected early prepare failure: ${it::class.simpleName}" } }
        check(resolves.get() == 0 && cacheRequests.get() == 0) { "Stage started a duplicate media request during live playback" }

        val captureDeadline = System.nanoTime() + 12_000_000_000L
        while (!cache.contains(track.id) && System.nanoTime() < captureDeadline) Thread.sleep(25)
        check(cache.contains(track.id)) {
            "VLC did not read enough of the source to complete capture; requests=${liveRequests.joinToString()}"
        }
        val playbackDeadline = System.nanoTime() + 8_000_000_000L
        while ((!player.status().isPlaying || player.status().time() < 250) && System.nanoTime() < playbackDeadline) Thread.sleep(50)
        check(player.status().isPlaying && player.status().time() >= 250) {
            "VLC did not advance through synthetic audio; state=${player.status().state()}, " +
                "playing=${player.status().isPlaying}, time=${player.status().time()}ms, requests=${liveRequests.joinToString()}"
        }

        val completed = runBlocking { capture.awaitComplete() }
        val relayPin = cache.pinStreamCapture(track.id, capture, completed) ?: error("Exact capture could not be pinned")
        check(relay.attachCapturedFile(capture, completed, relayPin)) { "Relay rejected the successful capture" }
        val upstreamDrainDeadline = System.nanoTime() + 3_000_000_000L
        while (!relay.isFullyLocal && System.nanoTime() < upstreamDrainDeadline) Thread.sleep(25)
        check(relay.isFullyLocal) { "Relay still has an upstream request after capture completion" }
        upstreamDisabled.set(true)
        val requestsAtAttachment = liveRequests.size
        val probe = OkHttpClient()
        probe.newCall(Request.Builder().url(relay.uri).header("Range", "bytes=8-31").build()).execute().use {
            check(it.code == 206 && it.body!!.bytes().contentEquals(wav.copyOfRange(8, 32))) {
                "The attached relay did not serve a local byte range"
            }
        }
        probe.connectionPool.evictAll()
        player.controls().setPause(true)
        val pauseDeadline = System.nanoTime() + 2_000_000_000L
        while (player.status().isPlaying && System.nanoTime() < pauseDeadline) Thread.sleep(25)
        check(!player.status().isPlaying) { "Native fixture did not pause before the seek" }
        val beforeSeek = player.status().time()
        check(beforeSeek < 1_250) { "Native fixture advanced too far to distinguish the paused seek; time=$beforeSeek" }
        player.controls().setTime(1_500)
        val seekDeadline = System.nanoTime() + 2_000_000_000L
        while (kotlin.math.abs(player.status().time() - 1_500) > 150 && System.nanoTime() < seekDeadline) Thread.sleep(25)
        check(!player.status().isPlaying && kotlin.math.abs(player.status().time() - 1_500) <= 150) {
            "The paused native player did not land at the seek target; before=$beforeSeek, time=${player.status().time()}"
        }
        player.controls().setPause(false)
        val resumedDeadline = System.nanoTime() + 3_000_000_000L
        while (player.status().time() < 1_800 && System.nanoTime() < resumedDeadline) Thread.sleep(25)
        check(player.status().isPlaying && player.status().time() >= 1_800) {
            "The same native player did not resume after the paused seek with the synthetic origin disabled; time=${player.status().time()}"
        }
        check(liveRequests.size == requestsAtAttachment) { "The attached relay contacted the disabled synthetic origin" }

        prepared = runBlocking { cache.prepare(track) }
        check(prepared!!.file.readBytes().contentEquals(wav)) { "The cache differs from the generated source WAV" }
        check(resolves.get() == 0 && cacheRequests.get() == 0) { "Cache reuse resolved or downloaded the recording again" }
        check(liveRequests.isNotEmpty()) { "No live source requests were observed" }
        println("PASS native Stage stream capture: same VLC player attached captured bytes, landed a paused seek and resumed with origin disabled; " +
            "separate local range probe passed; VLC may reuse native buffered bytes; dummy output; cached bytes match; " +
            "live requests=${liveRequests.joinToString()}, cache requests=${cacheRequests.get()}, resolves=${resolves.get()}")
    } finally {
        prepared?.close()
        player?.controls()?.stop()
        player?.release()
        factory?.release()
        relay.close()
        cache.close()
        origin.connectionPool.evictAll()
        cacheClient.connectionPool.evictAll()
        Files.walk(temp).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
}

private fun fixtureResponse(request: Request, wav: ByteArray): Response {
    val rangeHeader = request.header("Range")
    val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        .header("ETag", "\"echo-stage-wav-fixture-v1\"")
        .header("Accept-Ranges", "bytes")
        .header("Content-Type", "audio/webm")
    if (request.method == "HEAD") {
        return response.code(200).message("synthetic HEAD")
            .header("Content-Length", wav.size.toString())
            .body(fixtureBody(ByteArray(0))).build()
    }
    if (rangeHeader == null) return response.code(200).message("synthetic full response")
        .body(fixtureBody(wav)).build()
    val match = Regex("bytes=(\\d+)-(\\d*)").matchEntire(rangeHeader)
        ?: return response.code(416).message("invalid range").body(fixtureBody(ByteArray(0))).build()
    val start = match.groupValues[1].toIntOrNull() ?: return response.code(416).message("invalid range").body(fixtureBody(ByteArray(0))).build()
    val requestedEnd = match.groupValues[2].takeIf(String::isNotEmpty)?.toIntOrNull() ?: wav.lastIndex
    if (start !in wav.indices || requestedEnd < start) return response.code(416).message("range unsatisfied")
        .header("Content-Range", "bytes */${wav.size}").body(fixtureBody(ByteArray(0))).build()
    val end = requestedEnd.coerceAtMost(wav.lastIndex)
    val bytes = wav.copyOfRange(start, end + 1)
    return response.code(206).message("synthetic range")
        .header("Content-Range", "bytes $start-$end/${wav.size}")
        .body(fixtureBody(bytes)).build()
}

private fun fixtureBody(bytes: ByteArray) = object : ResponseBody() {
    override fun contentType() = "audio/webm".toMediaType()
    override fun contentLength() = bytes.size.toLong()
    override fun source() = Buffer().write(bytes)
}

private fun makeStreamFixtureWav(): ByteArray {
    val sampleRate = 44_100
    val seconds = 3
    val samples = ByteArray(sampleRate * seconds * 2)
    for (index in 0 until samples.size / 2) {
        val value = (kotlin.math.sin(2 * Math.PI * 440 * index / sampleRate) * 3_000).toInt()
        samples[index * 2] = value.toByte()
        samples[index * 2 + 1] = (value shr 8).toByte()
    }
    val bytes = ByteArrayOutputStream()
    AudioInputStream(ByteArrayInputStream(samples), AudioFormat(sampleRate.toFloat(), 16, 1, true, false),
        (samples.size / 2).toLong()).use { AudioSystem.write(it, AudioFileFormat.Type.WAVE, bytes) }
    return bytes.toByteArray()
}
