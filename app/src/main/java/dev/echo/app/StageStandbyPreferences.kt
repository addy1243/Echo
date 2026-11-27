package dev.echo.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/** Each Echo build stores Standby preferences in its own app-private storage. */
internal class StageStandbyPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("stage-standby", Context.MODE_PRIVATE)

    fun read(): StageStandbySettings = StageStandbySettings(
        automaticEnabled = preferences.getBoolean(KEY_AUTOMATIC, false),
        chargingOnly = preferences.getBoolean(KEY_CHARGING_ONLY, false),
        cinema = preferences.getBoolean("cinema", true),
        idleTimeoutMs = preferences.getLong(KEY_IDLE_TIMEOUT, StageStandbySettings.DEFAULT_IDLE_TIMEOUT_MS)
            .coerceAtLeast(1L),
    )

    fun update(settings: StageStandbySettings) {
        preferences.edit()
            .putBoolean(KEY_AUTOMATIC, settings.automaticEnabled)
            .putBoolean(KEY_CHARGING_ONLY, settings.chargingOnly)
            .putBoolean("cinema", settings.cinema)
            .putLong(KEY_IDLE_TIMEOUT, settings.idleTimeoutMs)
            .apply()
    }

    companion object {
        private const val KEY_AUTOMATIC = "automatic_enabled"
        private const val KEY_CHARGING_ONLY = "charging_only"
        private const val KEY_IDLE_TIMEOUT = "idle_timeout_ms"
    }
}

/** Returns null if Android has no battery snapshot; charging-only policy then stays inactive. */
internal fun readStageChargingState(context: Context): Boolean? {
    val intent = context.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    return when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
        BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
        else -> null
    }
}
