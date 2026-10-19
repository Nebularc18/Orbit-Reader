package app.bookorbit.community

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.bookorbit.community.playback.PlaybackService
import app.bookorbit.community.ui.OrbitApp

class MainActivity : ComponentActivity() {
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
            OrbitApp(viewModel(), application as OrbitApplication, controller)
        }
    }
}
