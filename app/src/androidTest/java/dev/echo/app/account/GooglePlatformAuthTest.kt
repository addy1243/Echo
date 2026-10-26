package dev.echo.app.account

import android.accounts.AccountManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class GooglePlatformAuthTest {
    @Test fun accountSelectionAcceptsOnlyGoogleAndHandlesCancellation() {
        val selected = Intent().putExtra(AccountManager.KEY_ACCOUNT_NAME, "listener@example.com")
            .putExtra(AccountManager.KEY_ACCOUNT_TYPE, "com.google")
        assertEquals("listener@example.com", googleAccountFromSelection(selected).name)
        assertTrue(runCatching { googleAccountFromSelection(null) }.isFailure)
        assertTrue(runCatching { googleAccountFromSelection(Intent(selected).putExtra(AccountManager.KEY_ACCOUNT_TYPE, "other")) }.isFailure)
        assertTrue(runCatching { googleAccountFromSelection(Intent(selected).putExtra(AccountManager.KEY_ACCOUNT_NAME, "")) }.isFailure)
    }

    @Test fun googleProfileMustHaveVerifiedEmailAndStableSubject() {
        assertEquals(GoogleProfile("listener@example.com", "123"), parseGoogleProfile("""{"email":"listener@example.com","id":"123","verified_email":true}"""))
        for (invalid in listOf("""{"email":"listener@example.com","id":"123","verified_email":false}""",
            """{"email":"listener@example.com","id":"","verified_email":true}""",
            """{"email":"invalid","id":"123","verified_email":true}""", "{}")) {
            assertTrue(runCatching { parseGoogleProfile(invalid) }.isFailure)
        }
    }

    @Test fun legacySdkAccountNeedsFreshExplicitSelectionAndChooserFiltersGoogle() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "google-auth-test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$prefix-$name", mode)
        }
        val prefs = context.getSharedPreferences("google-account", Context.MODE_PRIVATE)
        try {
            prefs.edit().putString("email", "legacy@example.com").putBoolean("sync:legacy@example.com", true).commit()
            val accounts = GoogleAccounts(context)
            assertNull(accounts.email.value)
            assertFalse(accounts.automatic.value)
            assertArrayEquals(arrayOf("com.google"), accounts.chooseAccountIntent().getStringArrayExtra("allowableAccountTypes"))
            assertTrue(runCatching { accounts.setAutomatic(true) }.isFailure)
            accounts.signOut()
            assertNull(prefs.getString("email", null))
        } finally { prefs.edit().clear().commit() }
    }
}
