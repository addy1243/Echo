package dev.echo.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

@Composable internal fun LicensesScreen() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf<List<String>>(emptyList()) }
    var contents by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    BackHandler(selected != null) { selected = null }
    LaunchedEffect(selected) {
        contents = emptyList(); error = null
        try {
            val asset = selected
            if (asset == null) {
                files = withContext(Dispatchers.IO) { context.assets.list("").orEmpty().filter { it.endsWith(".txt") }.sorted() +
                    context.assets.list("runtime").orEmpty().filter { it.endsWith(".txt") }.sorted().map { "runtime/$it" }
                }
            } else contents = withContext(Dispatchers.IO) { context.assets.open(asset).bufferedReader().use { it.readText() }.chunked(8000) }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { error = "License text could not be opened. Matching source and notices are included with the release." }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(selected ?: "Licenses & source", style = MaterialTheme.typography.headlineSmall) }
        if (selected == null) {
            item { Text("Echo Music is GPL-3.0-or-later. Third-party components retain their own licenses and notices.") }
            item { TextButton(onClick = {
                runCatching { uriHandler.openUri("https://github.com/addy1243/Echo/releases/tag/v${BuildConfig.VERSION_NAME}") }
                    .onFailure { error = "Open github.com/addy1243/Echo/releases to find matching source." }
            }) { Text("Matching release source") } }
            items(files, key = { it }) { file -> OutlinedButton(onClick = { selected = file }) { Text(file) } }
        } else {
            item { TextButton(onClick = { selected = null }) { Text("License list") } }
            items(contents) { chunk -> SelectionContainer { Text(chunk, style = MaterialTheme.typography.bodySmall) } }
        }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
    }
}
