package dev.echo.desktop

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun DesktopUpdatesDialog(updater: DesktopUpdater, scope: CoroutineScope, onDismiss: () -> Unit) {
    val state by updater.state.collectAsState()
    var job by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var installing by remember { mutableStateOf(false) }
    fun action(block: suspend () -> Unit) {
        if (job?.isActive == true) return
        error = null
        job = scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "The update could not finish. Your library is preserved." }
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Echo for Windows updates") }, text = {
        Column(Modifier.widthIn(min = 420.dp, max = 560.dp).heightIn(max = 450.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Installed version $DESKTOP_VERSION")
            Text("Echo checks for updates automatically once a day when opened. Downloads are verified before Windows Installer opens.")
            (error ?: state.message)?.let { Text(it) }
            if (state.phase == DesktopUpdatePhase.CHECKING) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.phase == DesktopUpdatePhase.DOWNLOADING) {
                LinearProgressIndicator(progress = { if (state.totalBytes > 0) (state.receivedBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth())
                Text("${state.receivedBytes / 1024} / ${state.totalBytes / 1024} KiB")
            }
            state.release?.let { release ->
                Text("Version ${release.version}")
                if (release.notes.isNotBlank()) Text(release.notes)
            }
            if (state.phase == DesktopUpdatePhase.READY) Text("Install opens the normal Windows installer. Close Echo when it asks; your library and sign-in stay in your Windows user folder.")
            if (installing) Text("Windows Installer opened. Follow its prompts to finish updating.")
        }
    }, confirmButton = {
        when (state.phase) {
            DesktopUpdatePhase.CHECKING, DesktopUpdatePhase.DOWNLOADING -> TextButton(onClick = { updater.cancel(); job?.cancel() }) { Text("Cancel") }
            DesktopUpdatePhase.READY -> Button(enabled = job?.isActive != true, onClick = { action { updater.launchInstaller(); installing = true } }) { Text("Install update") }
            DesktopUpdatePhase.AVAILABLE, DesktopUpdatePhase.FAILED -> if (state.release != null)
                Button(onClick = { action { updater.download() } }) { Text("Download update") }
            else Button(onClick = { action { updater.check(true) } }) { Text("Check again") }
            DesktopUpdatePhase.IDLE -> Button(onClick = { action { updater.check(true) } }) { Text("Check for updates") }
        }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(if (state.phase == DesktopUpdatePhase.DOWNLOADING) "Hide" else "Close") } })
}
