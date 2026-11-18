package app.spool.ui

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.spool.playback.PlaybackService
import app.spool.ui.theme.SpoolTheme
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SpoolTheme {
                AppRoot(controller = controller)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        connect()
    }

    override fun onStop() {
        disconnect()
        super.onStop()
    }

    // Media3's recommended lifecycle: connect while the UI is visible, release when it isn't.
    private fun connect() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token)
            .setListener(object : MediaController.Listener {
                // The session went away (e.g. the service was killed): reconnect if still visible.
                override fun onDisconnected(controller: MediaController) {
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        disconnect()
                        connect()
                    }
                }
            })
            .buildAsync()
        controllerFuture = future
        future.addListener(
            { if (controllerFuture === future) controller = runCatching { future.get() }.getOrNull() },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun disconnect() {
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
    }
}
