package dev.echo.app.update

import org.junit.Assert.*
import org.junit.Test

class UpdateReleaseTest {
    private val valid = UpdateRelease(3, "0.1.2", "https://github.com/addy1243/Echo/releases/download/v0.1.2/EchoMusic-3.apk", 100, "a".repeat(64), "Fixes")

    @Test fun versionComesFromAndroidCodeNotLexicalVersionName() {
        assertEquals(123L, updateVersion("EchoMusic-123.apk"))
        listOf("EchoMusic-0.apk", "EchoMusic-1.apk.exe", "app-debug.apk", "EchoMusic-999999999999999999999999.apk").forEach { assertNull(updateVersion(it)) }
        validateRelease(valid)
    }
    @Test fun releaseTrustBoundaryRejectsForeignHostsRepositoriesAndInvalidDigests() {
        listOf(
            valid.copy(url = valid.url.replace("https:", "http:")),
            valid.copy(url = valid.url.replace("github.com", "github.com.attacker.example")),
            valid.copy(url = valid.url.replace("addy1243/Echo", "someone/Other")),
            valid.copy(url = valid.url.replace("EchoMusic-3", "EchoMusic-4")),
            valid.copy(url = valid.url.replace("github.com/", "user@github.com/")),
            valid.copy(size = MAX_APK_BYTES + 1), valid.copy(size = 0), valid.copy(sha256 = ""),
        ).forEach { release -> assertThrows(IllegalArgumentException::class.java) { validateRelease(release) } }
    }
    @Test fun packageVersionSdkAndSigningIdentityMustAllMatch() {
        fun check(pkg: String = "dev.echo.music", version: Long = 3, sdk: Int = 26, signers: Set<String> = setOf("original")) =
            validateApkIdentity(pkg, "dev.echo.music", version, 2, 3, sdk, 36, signers, setOf("original"))
        check()
        assertThrows(IllegalArgumentException::class.java) { check(pkg = "other.app") }
        assertThrows(IllegalArgumentException::class.java) { check(version = 2) }
        assertThrows(IllegalArgumentException::class.java) { check(version = 4) }
        assertThrows(IllegalArgumentException::class.java) { check(sdk = 37) }
        assertThrows(IllegalArgumentException::class.java) { check(signers = emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { check(signers = setOf("different")) }
    }
}
