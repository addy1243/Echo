package dev.echo.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Opt-in read-only inspection of the actual MSI. This never launches Windows Installer. */
fun main(args: Array<String>) {
    require(args.size == 2) { "Provide MSI path and expected desktop version" }
    val path = Path.of(args[0]).toAbsolutePath()
    val version = args[1]
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input -> val buffer = ByteArray(64 * 1024); while (true) {
        val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read)
    } }
    val name = "EchoMusic-Windows-x64-$version.msi"
    val release = DesktopUpdateRelease(version, name, "https://github.com/addy1243/Echo/releases/download/windows-$version/$name",
        Files.size(path), digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }, "Local read-only MSI inspection")
    validateDesktopRelease(release)
    verifyDesktopMsi(path, release)
    println("PASS actual Windows MSI identity: product EchoMusic, expected version, stable UpgradeCode and x64 architecture")
}
