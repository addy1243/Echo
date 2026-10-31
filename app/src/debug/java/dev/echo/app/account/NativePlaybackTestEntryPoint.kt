package dev.echo.app.account

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.echo.data.firebase.ConnectedPlaybackParticipation
import dev.echo.data.library.LibraryRepository
import dev.echo.data.offline.OfflineRepository
import dev.echo.playback.SleepTimerController

/** Debug-only access for the explicit paired native playback acceptance harness. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface NativePlaybackTestEntryPoint {
    fun connectedPlaybackParticipation(): ConnectedPlaybackParticipation
    fun sleepTimerController(): SleepTimerController
    fun libraryRepository(): LibraryRepository
    fun googleAccounts(): GoogleAccounts
    fun offlineRepository(): OfflineRepository
}
