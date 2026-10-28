package dev.echo.data.firebase

/** Firebase client configuration is public; never place service-account credentials here. */
object EchoFirebaseProject {
    const val realtimeDatabaseUrl = "https://echo-85da4-default-rtdb.asia-southeast1.firebasedatabase.app"
    private const val PROJECT_ID = "echo-85da4"
    private const val API_KEY = "AIzaSyCVP0dbsDK6zJ5kqC1tkaP_eIPOYRMwtUk"
    val configured: Boolean get() = PROJECT_ID.isNotBlank() && API_KEY.isNotBlank()
    fun configuration(): FirebaseConfig {
        check(configured) { "Firebase setup is pending. Configure Echo's Firebase project before syncing." }
        return FirebaseConfig(PROJECT_ID, API_KEY)
    }
}
