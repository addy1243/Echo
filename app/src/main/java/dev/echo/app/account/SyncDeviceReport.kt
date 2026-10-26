package dev.echo.app.account

data class SyncDeviceReport(val registeredDevices: Int, val checkedAt: Long)

private val deviceUuid = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"
private val snapshotName = Regex("echo-playlists-($deviceUuid)\\.json")

internal fun snapshotDeviceId(name: String): String? = snapshotName.matchEntire(name)?.groupValues?.get(1)

internal fun snapshotDevices(names: List<String>): Set<String> {
    val devices = names.mapNotNull(::snapshotDeviceId)
    require(devices.distinct().size == devices.size) { "Duplicate device snapshot in Drive; sync stopped to preserve data" }
    return devices.toSet()
}
