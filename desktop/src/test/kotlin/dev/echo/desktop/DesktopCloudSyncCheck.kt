package dev.echo.desktop

import dev.echo.data.firebase.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Explicit opt-in acceptance runner. Uses the real saved account and never logs credentials or song names. */
fun main(args: Array<String>): Unit = runBlocking {
    check(args.firstOrNull() in setOf("sync", "export-android-session", "rename-owned-playlist", "compare-android-snapshot")) { "Choose an explicit acceptance action" }
    val accounts = DesktopGoogleAccount()
    check(accounts.state.value.email != null) { "Sign in to Echo Desktop first" }
    val config = EchoFirebaseProject.configuration()
    val session = accounts.firebaseSession(config)
    if (args[0] == "export-android-session") {
        val directory = Path.of(System.getenv("LOCALAPPDATA"), "EchoMusic", "verification")
        Files.createDirectories(directory)
        val json = JSONObject().put("uid", session.uid).put("email", session.email)
            .put("idToken", session.idToken).put("expiresAt", session.expiresAt)
        writeDesktopBackup(directory.resolve("android-session.json").toFile(), json.toString())
        println("Short-lived Android verification session prepared privately; no refresh token exported")
    } else {
        val library = DesktopLibrary()
        val sync = DesktopFirebaseSync(library, accounts)
        sync.synchronize()
        if (args[0] == "rename-owned-playlist") {
            val id = args.getOrNull(1) ?: error("Provide the verification playlist")
            check(id.matches(Regex("echo-android-sync-check-[a-f0-9-]{36}"))) { "Not an owned verification playlist" }
            check(library.snapshot().playlists.any { it.id == id }) { "Android verification playlist has not arrived" }
            library.renamePlaylist(id, "Echo desktop sync verification")
            sync.synchronize()
        }
        check(sync.state.value.lastSyncedAt != null && !sync.state.value.busy) {
            sync.state.value.message ?: "Desktop cloud sync did not finish"
        }
        val cloud = FirebaseLibraryCloud(config).read(session)
        val remote = cloud.json?.let(::decodeLibrarySyncState) ?: error("Cloud library was not created")
        check(encodeLibrarySyncState(library.exportFirebaseState()) == encodeLibrarySyncState(remote)) {
            "Desktop and cloud state do not match"
        }
        if (args[0] == "compare-android-snapshot") {
            val path = Path.of(args.getOrNull(1) ?: error("Provide the sanitized Android snapshot"))
            check(Files.size(path) <= 8 * 1024 * 1024) { "Android snapshot is too large" }
            val android = decodeLibrarySyncState(Files.readString(path))
            check(encodeLibrarySyncState(android) == encodeLibrarySyncState(remote)) { "Normal Android, desktop and cloud libraries do not yet match" }
            println("PASS normal Android app / desktop / Firebase canonical equality")
        } else args.getOrNull(1)?.let { ownedPlaylist ->
            check(ownedPlaylist.matches(Regex("echo-android-sync-check-[a-f0-9-]{36}"))) { "Not a verification playlist" }
            check(remote.playlists.any { it.id == ownedPlaylist } == (args.getOrNull(2) == "present")) {
                "Android playlist change did not converge"
            }
        }
        sync.setAutomatic(true)
        println("PASS desktop/cloud equality: playlists=${remote.playlists.size}, favorites=${remote.favorites.size}, history=${remote.history.size}, tracks=${remote.tracks.size}")
        println("Automatic sync enabled for this account while Echo is open")
    }
}
