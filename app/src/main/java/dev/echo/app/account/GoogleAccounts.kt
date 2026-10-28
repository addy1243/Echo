package dev.echo.app.account

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.os.BundleCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.echo.app.update.useCancellable
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class GoogleAuthorization(val accessToken: String? = null, val resolution: Intent? = null)
internal data class GoogleProfile(val email: String, val subject: String)

/** Google authentication is delegated to Android's installed authenticator; no Google SDK is bundled. */
@Singleton
class GoogleAccounts @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("google-account", Context.MODE_PRIVATE)
    private val manager = AccountManager.get(context)
    // SDK-based sessions need an explicit account choice to grant platform account visibility.
    private val _email = MutableStateFlow(prefs.getString("email", null).takeIf { prefs.contains("account_name") })
    val email = _email.asStateFlow()
    val subject: String? get() = prefs.getString("subject", null)
    private val _automatic = MutableStateFlow(_email.value != null && prefs.getBoolean(syncKey(), false))
    val automatic = _automatic.asStateFlow()
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()

    fun chooseAccountIntent(): Intent = AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"),
        "Choose a Google account for Echo", null, null, null)

    suspend fun signIn(account: Account): GoogleAuthorization {
        require(account.type == "com.google" && account.name.isNotBlank()) { "Choose a Google account" }
        val authorization = token(account, EMAIL_SCOPE)
        authorization.accessToken?.let { accessToken ->
            val profile = profile(accessToken)
            check(prefs.edit().putString("email", profile.email).putString("subject", profile.subject)
                .putString("account_name", account.name).putBoolean("firebase-sync:${profile.email}", false).commit()) { "Could not save the Google account" }
            _email.value = profile.email
            _automatic.value = false
        }
        return authorization
    }

    fun signOut() {
        setAutomatic(false)
        check(prefs.edit().remove("email").remove("subject").remove("account_name").commit()) { "Could not sign out" }
        _email.value = null
    }
    fun invalidateToken(token: String) { manager.invalidateAuthToken("com.google", token) }

    fun setAutomatic(enabled: Boolean) {
        check(!enabled || _email.value != null) { "Sign in first" }
        check(prefs.edit().putBoolean(syncKey(), enabled).commit()) { "Could not save sync preference" }
        _automatic.value = enabled
    }

    suspend fun authorize(): GoogleAuthorization {
        check(_email.value != null) { "Sign in with Google first" }
        val account = Account(requireNotNull(prefs.getString("account_name", null)), "com.google")
        val authorization = token(account, "$EMAIL_SCOPE https://www.googleapis.com/auth/drive.appdata")
        authorization.accessToken?.let {
            val profile = profile(it)
            check(profile.subject == prefs.getString("subject", null) && profile.email == _email.value) { "Google account changed. Sign out and choose the account again." }
        }
        return authorization
    }

    suspend fun authorizeIdentity(): GoogleAuthorization {
        check(_email.value != null) { "Sign in with Google first" }
        val account = Account(requireNotNull(prefs.getString("account_name", null)), "com.google")
        val authorization = token(account, EMAIL_SCOPE)
        authorization.accessToken?.let {
            val profile = profile(it)
            check(profile.subject == prefs.getString("subject", null) && profile.email == _email.value) { "Google account changed. Sign out and choose the account again." }
        }
        return authorization
    }

    private suspend fun token(account: Account, scope: String): GoogleAuthorization = suspendCancellableCoroutine { continuation ->
        val request = manager.getAuthToken(account, "oauth2:$scope", Bundle(), false, { completed ->
            if (continuation.isActive) try {
                val result = completed.result
                val returnedAccount = result.getString(AccountManager.KEY_ACCOUNT_NAME)
                check(returnedAccount == null || returnedAccount.equals(account.name, ignoreCase = true)) { "Account changed during authorization" }
                val token = result.getString(AccountManager.KEY_AUTHTOKEN)?.takeIf { it.isNotBlank() }
                val intent = BundleCompat.getParcelable(result, AccountManager.KEY_INTENT, Intent::class.java)
                check(token != null || intent != null) { "Google returned no authorization" }
                continuation.resume(GoogleAuthorization(token, if (token == null) intent else null))
            } catch (error: Exception) {
                continuation.resumeWithException(IllegalStateException("Google authorization could not complete. Choose the account again and check Echo's Android OAuth registration.", error))
            }
        }, Handler(Looper.getMainLooper()))
        continuation.invokeOnCancellation { request.cancel(true) }
    }

    private suspend fun profile(token: String): GoogleProfile = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url("https://www.googleapis.com/oauth2/v2/userinfo")
            .header("Authorization", "Bearer $token").build()).useCancellable { response ->
            if (response.code == 401) invalidateToken(token)
            check(response.isSuccessful) { "Google account verification failed (HTTP ${response.code}). Choose the account again." }
            val source = requireNotNull(response.body).source()
            source.request(16_385)
            require(source.buffer.size <= 16_384) { "Google profile response is too large" }
            parseGoogleProfile(source.buffer.readUtf8())
        }
    }

    private fun syncKey() = "firebase-sync:${_email.value.orEmpty()}"
    private companion object { const val EMAIL_SCOPE = "https://www.googleapis.com/auth/userinfo.email" }
}

internal fun googleAccountFromSelection(data: Intent?): Account {
    require(data != null) { "Google account selection cancelled. This device needs Google's account provider." }
    val name = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME).orEmpty()
    require(name.isNotBlank() && name.length <= 320 && data.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE) == "com.google") { "Choose a Google account" }
    return Account(name, "com.google")
}

internal fun parseGoogleProfile(json: String): GoogleProfile {
    val profile = JSONObject(json)
    val email = profile.getString("email")
    val subject = profile.getString("id")
    require(profile.optBoolean("verified_email") && email.length in 3..320 && '@' in email && subject.isNotBlank() && subject.length <= 256) { "Google returned an unverified account" }
    return GoogleProfile(email, subject)
}
