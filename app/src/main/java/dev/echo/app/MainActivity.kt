package dev.echo.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private lateinit var model: EchoViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this)[EchoViewModel::class.java]
        if (savedInstanceState == null) acceptCallback(intent)
        setContent { EchoApp(model) }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); acceptCallback(intent) }
    private fun acceptCallback(intent: Intent?) {
        if (intent?.action == "android.media.action.MEDIA_PLAY_FROM_SEARCH") {
            model.playFromSearch(intent.getStringExtra(android.app.SearchManager.QUERY).orEmpty())
        }
        intent?.data?.let { if (it.scheme == "echomusic" && it.host == "oauth" && it.path == "/spotify") model.spotifyCallback(it) }
    }
}
