package app.bookorbit.community

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.bookorbit.community.playback.PlaybackService
import app.bookorbit.community.ui.LibraryViewModel
import app.bookorbit.community.ui.OrbitApp

class MainActivity : ComponentActivity() {
    private val library: LibraryViewModel by viewModels()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == "app.bookorbit.community.OIDC_COMPLETE") library.acceptSavedSession()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var controller by remember { mutableStateOf<MediaController?>(null) }
            DisposableEffect(Unit) {
                val future =
                    MediaController.Builder(
                            this@MainActivity,
                            SessionToken(
                                this@MainActivity,
                                ComponentName(this@MainActivity, PlaybackService::class.java),
                            ),
                        )
                        .buildAsync()
                future.addListener(
                    {
                        if (!future.isCancelled)
                            controller = runCatching { future.get() }.getOrNull()
                    },
                    ContextCompat.getMainExecutor(this@MainActivity),
                )
                onDispose { MediaController.releaseFuture(future) }
            }
            OrbitApp(library, application as OrbitApplication, controller)
        }
    }
}
