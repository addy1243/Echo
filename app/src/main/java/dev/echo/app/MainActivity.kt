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
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        model = ViewModelProvider(this)[EchoViewModel::class.java]
        if (savedInstanceState == null) acceptCallback(intent)
        setContent { EchoApp(model) }
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); acceptCallback(intent) }
    private fun acceptCallback(intent: Intent?) {
        if (intent?.action == "android.media.action.MEDIA_PLAY_FROM_SEARCH") {
            model.playFromSearch(intent.getStringExtra(android.app.SearchManager.QUERY).orEmpty())
        }
        intent?.data?.let { if (it.scheme == "echomusic" && it.host == "oauth" && it.path == "/spotify") model.spotifyCallback(it) }
    }
}
