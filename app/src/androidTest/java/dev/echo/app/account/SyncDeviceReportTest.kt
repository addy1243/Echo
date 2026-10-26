package dev.echo.app.account

import org.junit.Assert.*
import org.junit.Test

class SyncDeviceReportTest {
    private val one = "12345678-1234-1234-1234-123456789abc"
    private val two = "abcdefab-abcd-abcd-abcd-abcdefabcdef"
    @Test fun onlyRecognizedSnapshotsCountAndDuplicateRegistrationsStopSync() {
        assertEquals(setOf(one, two), snapshotDevices(listOf("echo-playlists-$one.json", "echo-playlists-$two.json", "other.json", "echo-playlists-bad.json")))
        assertTrue(runCatching { snapshotDevices(listOf("echo-playlists-$one.json", "echo-playlists-$one.json")) }.isFailure)
        assertNull(snapshotDeviceId("../echo-playlists-$one.json"))
    }
}
