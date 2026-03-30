package chromahub.rhythm.app.infrastructure.tile

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import chromahub.rhythm.app.infrastructure.service.MediaPlaybackService
import chromahub.rhythm.app.activities.MainActivity
import android.content.ComponentName
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@RequiresApi(Build.VERSION_CODES.N)
class RhythmTileService : TileService() {
    private var mediaController: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
        connectToMediaController()
    }

    private fun connectToMediaController() {
        if (mediaController != null || controllerFuture != null) return
        val sessionToken = SessionToken(this, ComponentName(this, MediaPlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.addListener({
            mediaController = controllerFuture?.get()
            updateTileState()
        }, MoreExecutors.directExecutor())
    }

    override fun onClick() {
        super.onClick()
        mediaController?.let { controller ->
            if (controller.isPlaying) {
                controller.pause()
            } else {
                controller.play()
            }
            updateTileState()
        } ?: run {
            // Launch main app if controller isn't ready
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = android.app.PendingIntent.getActivity(
                    this, 
                    0, 
                    intent, 
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        if (mediaController?.isPlaying == true) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "Pause"
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Play"
        }
        tile.updateTile()
    }

    override fun onStopListening() {
        super.onStopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        mediaController = null
        controllerFuture = null
    }
}
