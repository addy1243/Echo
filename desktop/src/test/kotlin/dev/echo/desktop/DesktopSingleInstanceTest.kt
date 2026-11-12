package dev.echo.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopSingleInstanceTest {
    @get:Rule val temporary = TemporaryFolder()

    private val lockDirectory: Path get() = temporary.root.toPath().resolve("instances")

    @Test fun secondLaunchOnSameLibraryActivatesExistingInstance() {
        val activated = CountDownLatch(1)
        val library = temporary.root.toPath().resolve("library.json")
        val first = DesktopSingleInstance.acquire(library, lockDirectory) { activated.countDown() }
        assertNotNull(first)
        try {
            assertNull(DesktopSingleInstance.acquire(library, lockDirectory) {})
            assertTrue(activated.await(3, TimeUnit.SECONDS))
        } finally { first?.close() }
        DesktopSingleInstance.acquire(library, lockDirectory) {}.use { assertNotNull(it) }
    }

    @Test fun defaultAndExplicitPathAliasesShareOneInstance() {
        val root = temporary.root.toPath()
        val defaultLibrary = root.resolve("EchoMusic").resolve("library.json")
        Files.createDirectories(defaultLibrary.parent)
        val explicitAlias = defaultLibrary.parent.resolve("nested").resolve("..").resolve("library.json")
        val first = DesktopSingleInstance.acquire(defaultLibrary, lockDirectory, "production") {}
        assertNotNull(first)
        try {
            assertEquals(
                DesktopSingleInstance.normalizedLibraryIdentity(defaultLibrary),
                DesktopSingleInstance.normalizedLibraryIdentity(explicitAlias),
            )
            assertNull(DesktopSingleInstance.acquire(explicitAlias, lockDirectory, "production") {})
        } finally { first?.close() }
    }

    @Test fun stageAndProductionRemainSeparateForSameLibrary() {
        val library = temporary.root.toPath().resolve("library.json")
        val production = DesktopSingleInstance.acquire(library, lockDirectory, "production") {}
        val stage = DesktopSingleInstance.acquire(library, lockDirectory, "stage") {}
        assertNotNull(production)
        assertNotNull(stage)
        try {
            assertNull(DesktopSingleInstance.acquire(library, lockDirectory, "production") {})
            assertNull(DesktopSingleInstance.acquire(library, lockDirectory, "stage") {})
        } finally {
            stage?.close()
            production?.close()
        }
    }

    @Test fun lockFilenameUsesSha256SizedOpaqueIdentity() {
        val library = temporary.root.toPath().resolve("library.json")
        DesktopSingleInstance.acquire(library, lockDirectory) {}?.use { }
        val names = Files.list(lockDirectory).use { stream -> stream.map { it.fileName.toString() }.toList() }
        assertTrue(names.any { it.matches(Regex("instance-[a-f0-9]{64}\\.lock")) })
    }
}
