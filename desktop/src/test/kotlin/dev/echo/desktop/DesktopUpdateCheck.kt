package dev.echo.desktop

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.io.IOException
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient

/** Explicit live GitHub acceptance. Download and diagnostic modes validate the MSI but never install it. */
fun main(args: Array<String>): Unit = runBlocking {
    val download = args.size == 2 && args[0] == "--download-from-version"
    val diagnose = args.size == 2 && args[0] == "--diagnose-from-version"
    check(args.isEmpty() || download || diagnose) { "Unsupported update acceptance arguments" }
    val temporary = if (args.isNotEmpty()) Files.createTempDirectory("echo-windows-update-acceptance-") else null
    val updater = when {
        temporary == null -> DesktopUpdater()
        diagnose -> DesktopUpdater(temporary, args[1], diagnosticClient(), ::verifyDesktopMsi,
            { _, _ -> error("Diagnostic mode cannot launch an installer") }, System::currentTimeMillis)
        else -> DesktopUpdater(temporary, currentVersion = args[1])
    }
    try { updater.use {
        updater.check(manual = true)
        val result = updater.state.value
        check(result.phase != DesktopUpdatePhase.FAILED) { result.message ?: "Published Windows update check failed" }
        println("PASS live Windows update check: ${result.message ?: result.phase.name}")
        if (temporary != null) {
            if (download || diagnose) {
                check(result.phase == DesktopUpdatePhase.AVAILABLE) { "Expected a newer published Windows release" }
                updater.download()
                check(updater.state.value.phase == DesktopUpdatePhase.READY) { updater.state.value.message ?: "Published installer did not verify" }
                println("PASS published Windows MSI downloaded and verified: exact bytes/digest and native product/version/upgrade/x64; installer not launched")
            }
        }
    } } finally {
        temporary?.let { root ->
            listOf("pending.msi", "pending.json", "preferences.json", "incoming.msi").forEach { Files.deleteIfExists(root.resolve(it)) }
            Files.deleteIfExists(root)
        }
    }
}

private fun diagnosticClient(): OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(10, TimeUnit.MINUTES)
    .eventListenerFactory { SafeFailureEvents() }.build()

private class SafeFailureEvents : EventListener() {
    override fun connectFailed(call: Call, inetSocketAddress: java.net.InetSocketAddress,
        proxy: java.net.Proxy, protocol: okhttp3.Protocol?, ioe: IOException) {
        report("connectFailed", ioe)
    }

    override fun callFailed(call: Call, ioe: IOException) { report("callFailed", ioe) }

    private fun report(event: String, error: IOException) {
        val chain = generateSequence<Throwable>(error) { it.cause }.take(8).joinToString(" <- ") { cause ->
            val message = cause.message.orEmpty().replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), "<url>")
                .replace(Regex("(?i)(token|sig|signature|key|authorization)=\\S+"), "${'$'}1=<redacted>")
                .replace(Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?\\b"), "<ip>")
                .replace(Regex("(?i)(?:[0-9a-f]{0,4}:){2,}[0-9a-f:.]+"), "<ip>")
                .take(180)
            "${cause.javaClass.simpleName}${if (message.isBlank()) "" else ": $message"}"
        }
        println("DIAGNOSTIC $event $chain")
    }
}
