package dev.echo.desktop

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopUpdaterTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = byteArrayOf(1, 2, 3, 4)
    private val release = DesktopUpdateRelease("0.1.1", "EchoMusic-Windows-x64-0.1.1.msi",
        "https://github.com/addy1243/Echo/releases/download/windows-0.1.1/EchoMusic-Windows-x64-0.1.1.msi", bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }, "Notes")

    private fun metadata(candidate: DesktopUpdateRelease = release) = JSONObject().put("draft", false).put("prerelease", false)
        .put("body", candidate.notes).put("assets", JSONArray().put(JSONObject().put("name", candidate.name).put("state", "uploaded")
            .put("browser_download_url", candidate.url).put("size", candidate.size).put("digest", "sha256:${candidate.sha256}")))
    private fun listing(candidate: DesktopUpdateRelease = release) = JSONArray().put(metadata(candidate)).toString().toByteArray()
    private fun client(block: (Request) -> Response): OkHttpClient = OkHttpClient.Builder().addInterceptor { block(it.request()) }.build()
    private fun reply(request: Request, code: Int = 200, body: ByteArray = listing(), location: String? = null): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("Fixture").code(code).body(body.toResponseBody())
            .apply { location?.let { header("Location", it) } }.build()
    private fun updater(root: java.nio.file.Path, http: OkHttpClient, version: String = "0.1.0",
        verify: (java.nio.file.Path, DesktopUpdateRelease) -> Unit = { _, _ -> },
        launch: (java.nio.file.Path) -> Unit = {}) = DesktopUpdater(root, version, http, verify, launch) { 10L * 86_400_000 }

    @Test fun releaseSelectionIgnoresAndroidPrereleaseAndInstalledVersions() {
        assertEquals(release, parseDesktopRelease(metadata().toString(), "0.1.0"))
        assertNull(parseDesktopRelease(metadata().put("prerelease", true).toString(), "0.1.0"))
        assertNull(parseDesktopRelease(metadata().put("draft", true).toString(), "0.1.0"))
        assertNull(parseDesktopRelease(metadata().toString(), "0.1.1"))
        assertNull(parseDesktopRelease(metadata().put("assets", JSONArray().put(JSONObject().put("name", "EchoMusic-11.apk"))).toString(), "0.1.0"))
        assertTrue(compareDesktopVersions("0.10.0", "0.9.12") > 0)
        val missingDigestPrefix = metadata().also { it.getJSONArray("assets").getJSONObject(0).put("digest", release.sha256) }
        assertTrue(runCatching { parseDesktopRelease(missingDigestPrefix.toString(), "0.1.0") }.isFailure)
    }

    @Test fun rejectsForeignUrlsUnsafeNamesInvalidVersionsSizesAndDigests() {
        listOf(release.copy(url = release.url.replace("addy1243/Echo", "other/Echo")), release.copy(url = "http://github.com/addy1243/Echo/releases/download/v/${release.name}"),
            release.copy(url = release.url + "?download=1"), release.copy(url = release.url.replace("github.com", "github.com.evil.example")),
            release.copy(name = "../../bad.msi"), release.copy(size = 0), release.copy(size = Long.MAX_VALUE), release.copy(sha256 = "bad"),
            release.copy(version = "0.01.1"), release.copy(version = "256.1.1"), release.copy(version = "1.1.65536"))
            .forEach { assertTrue("Accepted unsafe release $it", runCatching { validateDesktopRelease(it) }.isFailure) }
    }

    @Test fun newestAndroidReleaseDoesNotHideOlderWindowsUpdateAndHighestValidWindowsVersionWins() = runBlocking {
        val android = metadata().put("assets", JSONArray().put(JSONObject().put("name", "EchoMusic-11.apk")))
        val entries = JSONArray().put(android).put(metadata()).put(metadata().put("prerelease", true))
            .put(metadata(release.copy(url = release.url.replace("addy1243/Echo", "other/Echo"))))
        updater(temporary.newFolder().toPath(), client { request ->
            assertEquals("/repos/addy1243/Echo/releases", request.url.encodedPath)
            assertEquals("100", request.url.queryParameter("per_page"))
            reply(request, body = entries.toString().toByteArray())
        }).use { updater ->
            updater.check(true)
            assertEquals(DesktopUpdatePhase.AVAILABLE, updater.state.value.phase)
            assertEquals(release, updater.state.value.release)
        }
        val newer = release.copy(version = "0.10.0", name = release.name.replace("0.1.1", "0.10.0"), url = release.url.replace("0.1.1", "0.10.0"))
        assertEquals(newer, parseDesktopReleases(entries.put(metadata(newer)).toString(), "0.1.0"))
        assertTrue(runCatching { parseDesktopReleases(JSONArray().also { array -> repeat(101) { array.put(android) } }.toString(), "0.1.0") }.isFailure)
    }

    @Test fun startupCheckIsDailyAndManualCheckCanRunAgain() = runBlocking {
        val requests = AtomicInteger()
        updater(temporary.newFolder().toPath(), client { requests.incrementAndGet(); reply(it) }).use { updater ->
            updater.check(false); assertEquals(DesktopUpdatePhase.AVAILABLE, updater.state.value.phase)
            updater.check(false); assertEquals(1, requests.get())
            updater.check(true); assertEquals(2, requests.get())
        }
    }

    @Test fun redirectsCannotLeaveTrustedGithubHosts() = runBlocking {
        val requests = AtomicInteger()
        updater(temporary.newFolder().toPath(), client { requests.incrementAndGet(); reply(it, 302, location = "https://evil.example/update.msi") }).use { updater ->
            updater.check(true)
            assertEquals(1, requests.get())
            assertEquals(DesktopUpdatePhase.FAILED, updater.state.value.phase)
            assertTrue(updater.state.value.message!!.contains("trusted GitHub"))
        }
    }

    @Test fun rateLimitCooldownPersistsAndDoesNotHammerGithub() = runBlocking {
        val root = temporary.newFolder().toPath()
        val requests = AtomicInteger()
        updater(root, client { requests.incrementAndGet(); reply(it, 429).newBuilder().header("Retry-After", "3600").build() }).use { updater ->
            updater.check(true); updater.check(true)
            assertEquals(1, requests.get())
            assertTrue(updater.state.value.message!!.contains("request limit"))
        }
        updater(root, client { fail("Cooldown did not persist"); reply(it) }).use { it.check(true) }
    }

    @Test fun downloadChecksDigestIdentityAndRevalidatesBeforeInstallerLaunch() = runBlocking {
        val root = temporary.newFolder().toPath()
        val verified = AtomicInteger(); val launches = AtomicInteger()
        updater(root, client { reply(it, body = if (it.url.host == "api.github.com") listing() else bytes) },
            verify = { file, expected -> assertArrayEquals(bytes, Files.readAllBytes(file)); assertEquals(release, expected); verified.incrementAndGet() },
            launch = { assertEquals(root.resolve("pending.msi").toAbsolutePath(), it); launches.incrementAndGet() }).use { updater ->
            updater.check(true); updater.download()
            assertEquals(DesktopUpdatePhase.READY, updater.state.value.phase)
            assertEquals(4L, updater.state.value.receivedBytes)
            assertEquals(0, launches.get())
            updater.launchInstaller()
            assertEquals(1, launches.get()); assertEquals(2, verified.get())
            Files.write(root.resolve("pending.msi"), byteArrayOf(4, 3, 2, 1))
            assertTrue(runCatching { updater.launchInstaller() }.isFailure)
            assertEquals(1, launches.get())
        }
    }

    @Test fun incompleteChangedOrForeignMsiIsNeverStaged() = runBlocking {
        for (body in listOf(byteArrayOf(1, 2), byteArrayOf(4, 3, 2, 1), byteArrayOf(1, 2, 3, 4, 5))) {
            val root = temporary.newFolder().toPath()
            updater(root, client { reply(it, body = if (it.url.host == "api.github.com") listing() else body) }).use { updater ->
                updater.check(true); updater.download()
                assertEquals(DesktopUpdatePhase.FAILED, updater.state.value.phase)
                assertFalse(Files.exists(root.resolve("pending.msi"))); assertFalse(Files.exists(root.resolve("incoming.msi")))
            }
        }
        val root = temporary.newFolder().toPath()
        updater(root, client { reply(it, body = if (it.url.host == "api.github.com") listing() else bytes) },
            verify = { _, _ -> error("Foreign MSI identity") }).use { updater ->
            updater.check(true); updater.download()
            assertEquals(DesktopUpdatePhase.FAILED, updater.state.value.phase)
            assertFalse(Files.exists(root.resolve("pending.msi")))
        }
    }

    @Test fun stagedDownloadSurvivesRestartAndInstalledOrCorruptFilesAreRemoved() = runBlocking {
        val root = temporary.newFolder().toPath()
        updater(root, client { reply(it, body = if (it.url.host == "api.github.com") listing() else bytes) }).use { it.check(true); it.download() }
        updater(root, client { fail("Ready update must restore without fetching"); reply(it) }).use { it.check(false); assertEquals(DesktopUpdatePhase.READY, it.state.value.phase) }
        updater(root, client { reply(it, 404) }, version = "0.1.1").use { it.check(true); assertEquals(DesktopUpdatePhase.IDLE, it.state.value.phase) }
        assertFalse(Files.exists(root.resolve("pending.msi")))
        assertFalse(Files.exists(root.resolve("pending.json")))
    }

    @Test fun msiIdentityRequiresProductVersionUpgradeCodeAndX64() {
        validateDesktopMsiIdentity("EchoMusic", release.version, "{${DESKTOP_UPGRADE_UUID.uppercase()}}", "x64;1033", release)
        listOf(listOf("Other", release.version, DESKTOP_UPGRADE_UUID, "x64"), listOf("EchoMusic", "0.1.0", DESKTOP_UPGRADE_UUID, "x64"),
            listOf("EchoMusic", release.version, "other-guid", "x64"), listOf("EchoMusic", release.version, DESKTOP_UPGRADE_UUID, "Intel;1033"))
            .forEach { assertTrue(runCatching { validateDesktopMsiIdentity(it[0], it[1], it[2], it[3], release) }.isFailure) }
        if (System.getProperty("os.name").startsWith("Windows")) {
            val command = desktopInstallerCommand(temporary.newFile("echo update.msi").toPath())
            assertEquals("/i", command[1]); assertEquals("/norestart", command.last())
            assertFalse(command.any { it == "/quiet" || it == "/passive" || it == "/qn" })
        }
    }

    @Test fun cancellingBlockedDownloadClosesHttpAndRemovesPartialFile() = runBlocking {
        val started = CountDownLatch(1); val releaseBody = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool { task -> Thread(task).apply { isDaemon = true } }
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                if (exchange.requestURI.path == "/metadata") {
                    val json = listing(release.copy(size = 100_000))
                    exchange.sendResponseHeaders(200, json.size.toLong()); exchange.responseBody.write(json)
                } else {
                    exchange.sendResponseHeaders(200, 100_000)
                    exchange.responseBody.write(bytes); exchange.responseBody.flush(); started.countDown()
                    releaseBody.await(10, TimeUnit.SECONDS)
                }
            } finally { exchange.close() }
        }
        server.start()
        val root = temporary.newFolder().toPath()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val path = if (chain.request().url.host == "api.github.com") "metadata" else "installer"
            chain.proceed(chain.request().newBuilder().url("http://127.0.0.1:${server.address.port}/$path").build())
        }.build()
        try {
            updater(root, http).use { updater ->
                updater.check(true)
                val download = async(Dispatchers.Default) { updater.download() }
                assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
                updater.cancel()
                withTimeout(5000) { download.await() }
                assertEquals(DesktopUpdatePhase.AVAILABLE, updater.state.value.phase)
                assertFalse(Files.exists(root.resolve("pending.msi"))); assertFalse(Files.exists(root.resolve("incoming.msi")))
            }
        } finally { releaseBody.countDown(); server.stop(0); executor.shutdownNow() }
    }
}
