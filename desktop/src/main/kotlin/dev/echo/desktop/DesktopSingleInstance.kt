package dev.echo.desktop

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.swing.SwingUtilities

internal class DesktopSingleInstance private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    private val server: ServerSocket,
) : AutoCloseable {
    override fun close() {
        runCatching { server.close() }
        runCatching { lock.release() }
        channel.close()
    }

    companion object {
        fun acquire(
            libraryFile: Path,
            instanceDirectory: Path,
            namespace: String = "production",
            onActivate: () -> Unit,
        ): DesktopSingleInstance? {
            require(namespace.matches(Regex("[a-zA-Z0-9._-]{1,40}")))
            val identity = "$namespace\u0000${normalizedLibraryIdentity(libraryFile)}"
            val stem = MessageDigest.getInstance("SHA-256")
                .digest(identity.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            Files.createDirectories(instanceDirectory)
            val lockPath = instanceDirectory.resolve("instance-$stem.lock")
            val endpoint = instanceDirectory.resolve("instance-$stem.endpoint")
            val channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
            val lock = try { channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
            if (lock == null) {
                repeat(20) {
                    val activated = runCatching {
                        val parts = Files.readString(endpoint).trim().split(':')
                        require(parts.size == 2 && parts[1].matches(Regex("[a-f0-9-]{36}")))
                        val port = parts[0].toInt()
                        require(port in 1..65535)
                        Socket().use { socket ->
                            socket.connect(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), port), 100)
                            socket.soTimeout = 200
                            socket.getOutputStream().write((parts[1] + "\n").toByteArray(Charsets.US_ASCII))
                            socket.getInputStream().read() == 1
                        }
                    }.getOrDefault(false)
                    if (activated) {
                        channel.close()
                        return null
                    }
                    Thread.sleep(50)
                }
                channel.close()
                return null
            }

            try {
                val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
                val token = UUID.randomUUID().toString()
                Files.writeString(endpoint, "${server.localPort}:$token")
                Thread({
                    while (!server.isClosed) runCatching {
                        server.accept().use { socket ->
                            socket.soTimeout = 1000
                            if (socket.getInputStream().readNBytes(37).toString(Charsets.US_ASCII) == "$token\n") {
                                socket.getOutputStream().write(1)
                                SwingUtilities.invokeLater(onActivate)
                            }
                        }
                    }
                }, "echo-instance-activate").apply { isDaemon = true; start() }
                return DesktopSingleInstance(channel, lock, server)
            } catch (error: Throwable) {
                runCatching { lock.release() }
                channel.close()
                throw error
            }
        }

        internal fun normalizedLibraryIdentity(path: Path): String {
            val absolute = path.toAbsolutePath().normalize()
            var existing = absolute
            val missing = ArrayDeque<String>()
            while (!Files.exists(existing)) {
                val name = existing.fileName?.toString() ?: error("Library path has no existing ancestor: $path")
                missing.addFirst(name)
                existing = existing.parent ?: error("Library path has no existing ancestor: $path")
            }
            val resolved = missing.fold(existing.toRealPath()) { parent, part -> parent.resolve(part) }.normalize()
            val value = resolved.toString()
            return if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
                value.lowercase(Locale.ROOT) else value
        }
    }
}
