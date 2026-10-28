package dev.echo.desktop

import kotlinx.coroutines.runBlocking
import java.nio.file.Files

/** Explicit live GitHub acceptance. The optional download mode never starts an installer. */
fun main(args: Array<String>): Unit = runBlocking {
    check(args.isEmpty() || args.size == 2 && args[0] == "--download-from-version") { "Unsupported update acceptance arguments" }
    val temporary = if (args.isNotEmpty()) Files.createTempDirectory("echo-windows-update-acceptance-") else null
    val updater = if (temporary == null) DesktopUpdater() else DesktopUpdater(temporary, currentVersion = args[1])
    try { updater.use {
        updater.check(manual = true)
        val result = updater.state.value
        check(result.phase != DesktopUpdatePhase.FAILED) { result.message ?: "Published Windows update check failed" }
        println("PASS live Windows update check: ${result.message ?: result.phase.name}")
        if (temporary != null) {
            check(result.phase == DesktopUpdatePhase.AVAILABLE) { "Expected a newer published Windows release" }
            updater.download()
            check(updater.state.value.phase == DesktopUpdatePhase.READY) { updater.state.value.message ?: "Published installer did not verify" }
            println("PASS published Windows MSI: exact bytes/digest and native product/version/upgrade/x64 verification; installer not launched")
        }
    } } finally {
        temporary?.let { root ->
            listOf("pending.msi", "pending.json", "preferences.json", "incoming.msi").forEach { Files.deleteIfExists(root.resolve(it)) }
            Files.deleteIfExists(root)
        }
    }
}
