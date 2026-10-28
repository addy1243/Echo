package dev.echo.desktop

import dev.echo.data.firebase.*
import kotlinx.coroutines.runBlocking

/** Explicit opt-in: verifies the saved user's Firebase login and reads cloud status; never writes library data. */
fun main(args: Array<String>): Unit = runBlocking {
    try {
        val account = DesktopGoogleAccount()
        if (args.size == 2 && args[0] == "--import-and-sign-in") {
            account.importClientJson(java.io.File(args[1]))
            account.signIn()
        } else require(args.isEmpty()) { "Unsupported readiness arguments" }
        check(account.state.value.email != null) { "Sign in to Echo Desktop first" }
        val config = EchoFirebaseProject.configuration()
        val session = account.firebaseSession(config)
        println("Firebase identity verified: true")
        val document = FirebaseLibraryCloud(config).read(session)
        println("Cloud library exists: ${document.json != null}")
        document.json?.let { json ->
            val library = decodeLibrarySyncState(json)
            println("Cloud counts: playlists=${library.playlists.size}, favorites=${library.favorites.size}, history=${library.history.size}")
        }
    } catch (error: Exception) {
        println("Firebase readiness: ${error.message ?: "Check project setup"}")
        throw IllegalStateException("Firebase readiness check did not pass")
    }
}
