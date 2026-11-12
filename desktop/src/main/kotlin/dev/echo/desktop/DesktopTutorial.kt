package dev.echo.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private data class TutorialPage(val title: String, val body: String, val icon: ImageVector, val steps: List<String>)

private val tutorialPages = listOf(
    TutorialPage("Welcome to Echo", "Your music library, playlists and listening experience in one open source player.", Icons.Default.GraphicEq,
        listOf("Made for Windows and Android", "Explore the source and releases on GitHub")),
    TutorialPage("Bring your Spotify data", "Keep a local copy of your Spotify account export and use its history to find music in Echo.", Icons.Default.ImportExport,
        listOf("Request your account data at privacy.spotify.com/account", "When Spotify sends the export, open Settings → Import Spotify ZIP", "Choose the ZIP; Echo saves it locally for the Spotify archive page")),
    TutorialPage("Bring in playlists", "Import a playlist link or search public YouTube playlists, then save the results in your Echo library.", Icons.Default.PlaylistAdd,
        listOf("Choose Import playlist in the sidebar", "Paste a public Spotify or YouTube playlist link, or search playlists", "Imports create a new playlist and keep order and duplicates")),
    TutorialPage("Listen together, move your queue", "Connect Echo devices with the same account to share playback or hand a supported queue to another device.", Icons.Default.Devices,
        listOf("Open Devices & account to connect your devices", "Use Play together to start a shared session", "Use Move in the player to transfer supported portable tracks and the remaining queue")),
    TutorialPage("You're ready", "Search for music, open a playlist, or add local files to get started.", Icons.Default.MusicNote,
        listOf("Your local library stays on this device", "You can revisit features from the sidebar and Settings"))
)

@Composable
internal fun DesktopTutorialDialog(onDone: () -> Unit) {
    var pageIndex by remember { mutableIntStateOf(0) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val page = tutorialPages[pageIndex]
    val uriHandler = LocalUriHandler.current
    val finish: () -> Unit = {
        runCatching(onDone).onFailure { saveError = "Echo could not save this choice. Your tutorial will stay open. ${it.message.orEmpty()}" }
        Unit
    }
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false)) {
        Card(Modifier.fillMaxWidth().widthIn(max = 620.dp), shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).padding(32.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("GETTING STARTED", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = finish) { Text("Skip") }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(76.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(page.icon, null, Modifier.size(38.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(page.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(page.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            page.steps.forEachIndexed { index, text ->
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                                        Text("${index + 1}", Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.labelLarge)
                                    }
                                    Text(text, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            if (pageIndex == 0) TextButton(onClick = { uriHandler.openUri("https://github.com/addy1243/Echo") }) {
                                Icon(Icons.Default.Code, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("View Echo on GitHub")
                            }
                        }
                    }
                    if (pageIndex == 1) Image(painterResource("tutorial-spotify-settings.png"),
                        contentDescription = "Spotify account data controls in Echo Settings", modifier = Modifier.fillMaxWidth())
                    saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("${pageIndex + 1} of ${tutorialPages.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { if (pageIndex == tutorialPages.lastIndex) finish() else pageIndex++ }) {
                        Text(if (pageIndex == tutorialPages.lastIndex) "Get started" else "Next")
                        if (pageIndex < tutorialPages.lastIndex) { Spacer(Modifier.width(8.dp)); Icon(Icons.Default.ArrowForward, null, Modifier.size(18.dp)) }
                    }
                }
            }
        }
    }
}
