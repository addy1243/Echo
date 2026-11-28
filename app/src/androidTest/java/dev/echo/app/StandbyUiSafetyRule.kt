package dev.echo.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assume.assumeTrue
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Guards ActivityScenario launch and restores onboarding state even when a test skips or fails. */
internal class StandbyUiSafetyRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            assumeTrue(
                "Standby UI instrumentation tests are emulator-only",
                Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.startsWith("generic"),
            )

            val context = ApplicationProvider.getApplicationContext<Context>()
            val preferences = context.getSharedPreferences(ONBOARDING_PREFERENCES, Context.MODE_PRIVATE)
            val before = preferences.all.mapValues { (_, value) ->
                if (value is Set<*>) value.toSet() else value
            }
            try {
                base.evaluate()
            } finally {
                restoreOnboardingPreferences(preferences, before)
            }
        }
    }

    private companion object {
        const val ONBOARDING_PREFERENCES = "echo-onboarding"

        fun restoreOnboardingPreferences(preferences: SharedPreferences, values: Map<String, Any?>) {
            val editor = preferences.edit().clear()
            values.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    null -> Unit
                    else -> error("Unsupported onboarding preference type for '$key'")
                }
            }
            check(editor.commit()) { "Could not restore onboarding preferences after instrumentation" }
        }
    }
}
