package dev.echo.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

private val tutorialTitles = listOf(
    "Your music, your way",
    "Bring your Spotify data",
    "Import playlists",
    "Listen across devices",
    "Ready when you are"
)

private val tutorialCopy = listOf(
    "Echo is an open-source music player for Android and Windows. Your library and playback stay yours.",
    "1. Open Spotify Account Privacy and request Account data. 2. When Spotify notifies you, download the ZIP. 3. In Echo, open Settings → Import Spotify data and select it. You can also import YourLibrary.json or playlist JSON. Spotify metadata may need a recording match to play.",
    "Import a public YouTube or Spotify playlist link from Library, or bring in Spotify CSV/JSON exports. Preview the contents before adding them.",
    "Play together syncs playback across your signed-in Echo devices. Move sends the current song and remaining queue to another ready device.",
    "Explore your library, find music, and make Echo your own. You can revisit import options in Library and connected playback from the player."
)

@Composable
internal fun EchoTutorial(page: Int, saveFailed: Boolean = false, onNext: () -> Unit, onSkip: () -> Unit) {
    val context = LocalContext.current
    val safePage = page.coerceIn(0, tutorialTitles.lastIndex)
    Dialog(onDismissRequest = onSkip) {
        Surface(
            modifier = Modifier.fillMaxWidth().testTag("echo-tutorial"),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${safePage + 1} of ${tutorialTitles.size}", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = onSkip, modifier = Modifier.testTag("tutorial-skip")) { Text("Skip") }
                }
                TutorialIllustration(safePage)
                Text(tutorialTitles[safePage], style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center)
                Text(tutorialCopy[safePage], style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                if (saveFailed) Text("Could not save your choice. Please try again.", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                if (safePage == 0) {
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/addy1243/Echo"))) },
                        modifier = Modifier.testTag("tutorial-github")) { Text("View Echo on GitHub") }
                }
                if (safePage == 1) {
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.spotify.com/account/privacy/"))) },
                        modifier = Modifier.testTag("tutorial-spotify-link")) { Text("Open Spotify Account Privacy") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    tutorialTitles.indices.forEach { index ->
                        Box(Modifier.size(if (index == safePage) 18.dp else 7.dp, 7.dp)
                            .clip(CircleShape).background(if (index == safePage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
                    }
                }
                Button(onClick = onNext, modifier = Modifier.fillMaxWidth().height(50.dp).testTag("tutorial-next")) {
                    Text(if (safePage == tutorialTitles.lastIndex) "Get started" else "Next")
                }
            }
        }
    }
}

@Composable
private fun TutorialIllustration(page: Int) {
    if (page == 1) {
        Image(painter = painterResource(R.drawable.tutorial_spotify_settings),
            contentDescription = "Spotify data import options in Echo Settings",
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)), contentScale = ContentScale.FillWidth)
        return
    }
    val icon = when (page) {
        0 -> Icons.Default.GraphicEq
        2 -> Icons.Default.PlaylistPlay
        3 -> Icons.Default.Devices
        else -> Icons.Default.MusicNote
    }
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(116.dp)) {
        Box(Modifier.size(112.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer))
        Box(Modifier.size(78.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(42.dp))
        }
    }
}
