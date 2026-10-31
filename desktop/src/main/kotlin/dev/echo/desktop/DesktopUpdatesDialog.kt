package dev.echo.desktop

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import java.nio.file.Path
import java.nio.file.Files
import javax.swing.JOptionPane
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

@Composable
fun DesktopUpdatesDialog(updater: DesktopUpdater, scope: CoroutineScope, onDismiss: () -> Unit) {
    val state by updater.state.collectAsState()
    var job by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
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
            if (state.phase in setOf(DesktopUpdatePhase.READY, DesktopUpdatePhase.INSTALLATION_CANCELLED,
                    DesktopUpdatePhase.INSTALLATION_FAILED)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = job?.isActive != true, onClick = { action {
                    val choice = chooseVerifiedInstallerPath(state.release?.version ?: return@action) ?: return@action
                    updater.saveVerifiedInstaller(choice.first, overwriteConfirmed = choice.second)
                } }) { Text("Save verified installer…") }
                state.verifiedInstallerCopy?.let {
                    Button(enabled = job?.isActive != true, onClick = { action { updater.launchSavedInstaller() } }) {
                        Text("Install saved copy…")
                    }
                }
            }
            if (state.phase == DesktopUpdatePhase.INSTALLING) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = {
        when (state.phase) {
            DesktopUpdatePhase.CHECKING, DesktopUpdatePhase.DOWNLOADING -> TextButton(onClick = { updater.cancel(); job?.cancel() }) { Text("Cancel") }
            DesktopUpdatePhase.READY -> Button(enabled = job?.isActive != true, onClick = { action { updater.launchInstaller() } }) { Text("Install update") }
            DesktopUpdatePhase.INSTALLING -> Button(enabled = false, onClick = {}) { Text("Installer running") }
            DesktopUpdatePhase.INSTALLATION_CANCELLED, DesktopUpdatePhase.INSTALLATION_FAILED -> Button(
                enabled = job?.isActive != true, onClick = { action { updater.launchInstaller() } }) { Text("Try again") }
            DesktopUpdatePhase.INSTALLATION_COMPLETE, DesktopUpdatePhase.INSTALLATION_REBOOT_REQUIRED -> Unit
            DesktopUpdatePhase.AVAILABLE, DesktopUpdatePhase.FAILED -> if (state.release != null)
                Button(onClick = { action { updater.download() } }) { Text("Download update") }
            else Button(onClick = { action { updater.check(true) } }) { Text("Check again") }
            DesktopUpdatePhase.IDLE -> Button(onClick = { action { updater.check(true) } }) { Text("Check for updates") }
        }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(if (state.phase == DesktopUpdatePhase.DOWNLOADING) "Hide" else "Close") } })
}

private suspend fun chooseVerifiedInstallerPath(version: String): Pair<Path, Boolean>? = suspendCancellableCoroutine { continuation ->
    SwingUtilities.invokeLater {
        val chooser = JFileChooser().apply {
            dialogTitle = "Save verified Echo installer"
            dialogType = JFileChooser.SAVE_DIALOG
            isAcceptAllFileFilterUsed = false
            fileFilter = FileNameExtensionFilter("Echo Windows installer (*.msi)", "msi")
            selectedFile = File("EchoMusic-Windows-x64-$version.msi")
        }
        val selected = if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.toPath() else null
        val exists = selected?.let { Files.exists(it) } == true
        val overwrite = !exists || JOptionPane.showConfirmDialog(null,
            "Replace the existing file at $selected? It will be replaced only after the copied installer verifies.",
            "Confirm installer overwrite", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION
        if (continuation.isActive) continuation.resume(selected?.takeIf { overwrite }?.let { it to exists })
    }
}
