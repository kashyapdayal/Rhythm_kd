package chromahub.rhythm.app.infrastructure.tile

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.bluetooth.BluetoothA2dp
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import chromahub.rhythm.app.R
import chromahub.rhythm.app.activities.MainActivity
import chromahub.rhythm.app.infrastructure.service.MediaPlaybackService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

@RequiresApi(Build.VERSION_CODES.N)
class RhythmTileService : TileService() {

    companion object {
        private const val TAG_QS = "QS_TILE"
        private const val TAG_MC = "MEDIA_CONTROLLER"
        private const val TAG_TAP = "TAP_HANDLER"
        
        private const val DOUBLE_TAP_WINDOW_MS = 400L
        private const val CONTROLLER_RETRY_DELAY_MS = 2000L
        private const val STATE_VERIFICATION_DELAY_MS = 300L
        const val EXTRA_FROM_QS_TILE = "extra_from_qs_tile"
    }

    private var mediaController: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val stateManager = TileStateManager()
    private lateinit var audioRoutingMonitor: AudioRoutingMonitor

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var doubleTapDetector: DoubleTapDetector
    private var playerListener: Player.Listener? = null

    private var isRegistered = false

    private val audioRoutingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG_QS, "Audio routing changed: ${intent?.action}")
            stateManager.setAudioRouting(audioRoutingMonitor.getCurrentRouting())
            updateTileState()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG_QS, "onCreate")

        audioRoutingMonitor = AudioRoutingMonitor(this)

        doubleTapDetector = DoubleTapDetector(
            windowMs = DOUBLE_TAP_WINDOW_MS,
            onSingleTapExecute = { handleSingleTapExecute() },
            onDoubleTapExecute = { handleDoubleTap() },
            postDelayed = { runnable, delay -> mainHandler.postDelayed(runnable, delay) },
            removeCallbacks = { mainHandler.removeCallbacks(it) }
        )
    }

    override fun onStartListening() {
        super.onStartListening()
        Log.d(TAG_QS, "━━━ onStartListening ━━━")

        // Only connect if not already connected
        if (mediaController == null) {
            Log.d(TAG_QS, "MediaController is null, initiating connection")
            connectToMediaController()
        } else {
            Log.d(TAG_QS, "MediaController already connected (state=${mediaController?.playbackState}), reusing")
            updateFromMediaController()
        }

        if (!isRegistered) {
            val filter = IntentFilter().apply {
                addAction(AudioManager.ACTION_HEADSET_PLUG)
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            try {
                registerReceiver(audioRoutingReceiver, filter)
                isRegistered = true
                Log.d(TAG_QS, "Audio routing receiver registered")
            } catch (e: Exception) {
                Log.e(TAG_QS, "Failed to register audio routing receiver", e)
            }
        }

        stateManager.setAudioRouting(audioRoutingMonitor.getCurrentRouting())
        updateTileState()
    }

    override fun onStopListening() {
        super.onStopListening()
        Log.d(TAG_QS, "━━━ onStopListening ━━━")

        if (isRegistered) {
            try {
                unregisterReceiver(audioRoutingReceiver)
                isRegistered = false
                Log.d(TAG_QS, "Audio routing receiver unregistered")
            } catch (e: Exception) {
                Log.w(TAG_QS, "Error unregistering receiver", e)
            }
        }

        doubleTapDetector.cancel()
        
        // Don't disconnect controller - keep it alive for faster reconnection
        Log.d(TAG_QS, "Keeping MediaController alive for next session")
    }

    override fun onClick() {
        super.onClick()
        Log.d(TAG_TAP, "━━━ Tile onClick event received ━━━")
        doubleTapDetector.onTap()
    }

    private fun handleSingleTapExecute() {
        Log.d(TAG_TAP, "handleSingleTapExecute")
        
        val controller = mediaController
        if (controller == null) {
            Log.w(TAG_TAP, "MediaController is null during click. Starting service and reconnecting.")
            
            // Start the service to ensure it's running
            try {
                val serviceIntent = Intent(this, MediaPlaybackService::class.java)
                ContextCompat.startForegroundService(this, serviceIntent)
                Log.d(TAG_TAP, "Started MediaPlaybackService via foreground service intent")
            } catch (e: Exception) {
                Log.e(TAG_TAP, "Failed to start foreground service", e)
            }
            
            // Attempt to reconnect to the controller
            connectToMediaController()
            
            // Launch app as user feedback
            launchApp()
            return
        }

        // Ensure controller is in a valid state
        if (controller.playbackState == Player.STATE_IDLE) {
            Log.w(TAG_TAP, "Controller is in IDLE state, launching app instead")
            launchApp()
            return
        }

        val isPlayingSnapshot = controller.isPlaying
        Log.d(TAG_TAP, "Current state isPlaying=$isPlayingSnapshot, issuing toggle command.")

        try {
            if (isPlayingSnapshot) {
                controller.pause()
                Log.d(TAG_TAP, "Issued pause command")
            } else {
                controller.play()
                Log.d(TAG_TAP, "Issued play command")
            }

            // Verify state change after a brief delay
            mainHandler.postDelayed({
                val updatedController = mediaController
                if (updatedController == null) {
                    Log.w(TAG_TAP, "Controller disconnected during verification")
                    return@postDelayed
                }
                
                val actualState = updatedController.isPlaying
                if (actualState == isPlayingSnapshot) {
                    Log.w(TAG_TAP, "Playback state unchanged after command! Expected: ${!isPlayingSnapshot}, Actual: $actualState")
                } else {
                    Log.d(TAG_TAP, "Playback toggled successfully: $isPlayingSnapshot -> $actualState")
                }
                updateFromMediaController()
            }, STATE_VERIFICATION_DELAY_MS)
        } catch (e: Exception) {
            Log.e(TAG_TAP, "Error executing playback toggle", e)
            updateFromMediaController()
        }
    }

    private fun handleDoubleTap() {
        Log.d(TAG_TAP, "handleDoubleTap -> skip to next track")
        
        val controller = mediaController
        if (controller == null) {
            Log.w(TAG_TAP, "MediaController is null during double tap. Starting service and launching app.")
            
            // Start service
            try {
                val serviceIntent = Intent(this, MediaPlaybackService::class.java)
                ContextCompat.startForegroundService(this, serviceIntent)
                Log.d(TAG_TAP, "Started MediaPlaybackService for double tap")
            } catch (e: Exception) {
                Log.e(TAG_TAP, "Failed to start service on double tap", e)
            }
            
            // Attempt reconnection
            connectToMediaController()
            
            launchApp()
            return
        }
        
        // Check if there's a next track available
        if (!controller.hasNextMediaItem()) {
            Log.w(TAG_TAP, "No next track available, ignoring skip command")
            return
        }
        
        try {
            controller.seekToNext()
            Log.d(TAG_TAP, "Issued seekToNext command")
            
            // Update UI after track transition
            mainHandler.postDelayed({
                updateFromMediaController()
            }, STATE_VERIFICATION_DELAY_MS)
        } catch (e: Exception) {
            Log.e(TAG_TAP, "Error executing skip to next", e)
            updateFromMediaController()
        }
    }

    private fun connectToMediaController() {
        // Prevent duplicate connection attempts
        if (controllerFuture != null) {
            Log.d(TAG_MC, "Connection already in progress, skipping duplicate attempt")
            return
        }
        
        Log.d(TAG_MC, "Initiating MediaController connection...")

        try {
            // Ensure service is running before creating controller
            val serviceIntent = Intent(this, MediaPlaybackService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
            Log.d(TAG_MC, "Started MediaPlaybackService as foreground service")

            // Build controller asynchronously
            val sessionToken = SessionToken(this, ComponentName(this, MediaPlaybackService::class.java))
            controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()

            controllerFuture?.addListener({
                try {
                    mediaController = controllerFuture?.get()
                    Log.d(TAG_MC, "✓ MediaController connected successfully")
                    setupPlayerListener()
                    updateFromMediaController()
                } catch (e: Exception) {
                    Log.e(TAG_MC, "✗ Failed to connect MediaController", e)
                    mediaController = null
                    controllerFuture = null
                    stateManager.setPlaybackState(playing = false, sessionActive = false)
                    updateTileState()
                    
                    // Retry connection after delay if we're still listening
                    mainHandler.postDelayed({
                        if (qsTile != null && controllerFuture == null) {
                            Log.d(TAG_MC, "Retrying MediaController connection...")
                            connectToMediaController()
                        }
                    }, CONTROLLER_RETRY_DELAY_MS)
                }
            }, MoreExecutors.directExecutor())
            
        } catch (e: Exception) {
            Log.e(TAG_MC, "Exception during MediaController initialization", e)
            controllerFuture = null
        }
    }

    private fun setupPlayerListener() {
        val controller = mediaController ?: return
        
        // Remove old listener if exists
        playerListener?.let { controller.removeListener(it) }

        playerListener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.d(TAG_MC, "Player state changed → isPlaying: $isPlaying")
                stateManager.setPlaybackState(isPlaying, true)
                updateTileState()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val stateStr = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN($playbackState)"
                }
                Log.d(TAG_MC, "Playback state changed → $stateStr")
                
                val hasSession = playbackState != Player.STATE_IDLE
                stateManager.setPlaybackState(controller.isPlaying, hasSession)
                updateTileState()
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                val newTitle = mediaMetadata.title?.toString()
                Log.d(TAG_MC, "Metadata changed → Title: \"$newTitle\"")
                stateManager.setTrackTitle(newTitle)
                updateTileState()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val reasonStr = when (reason) {
                    Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
                    Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO"
                    Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
                    Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
                    else -> "UNKNOWN($reason)"
                }
                val title = mediaItem?.mediaMetadata?.title?.toString()
                Log.d(TAG_MC, "Track transition → Reason: $reasonStr, Title: \"$title\"")
                stateManager.setTrackTitle(title)
                updateTileState()
            }
        }
        controller.addListener(playerListener!!)
        Log.d(TAG_MC, "Player listener attached")
    }

    private fun updateFromMediaController() {
        val controller = mediaController
        if (controller == null) {
            Log.d(TAG_MC, "updateFromMediaController: controller is null, setting unavailable state")
            stateManager.setPlaybackState(playing = false, sessionActive = false)
            stateManager.setTrackTitle(null)
        } else {
            val playbackState = controller.playbackState
            val isPlaying = controller.isPlaying
            val hasSession = playbackState != Player.STATE_IDLE
            val title = controller.mediaMetadata.title?.toString()
            
            Log.d(TAG_MC, "updateFromMediaController: state=$playbackState, playing=$isPlaying, hasSession=$hasSession, title=\"$title\"")
            
            stateManager.setPlaybackState(isPlaying, hasSession)
            stateManager.setTrackTitle(title)
        }
        stateManager.setAudioRouting(audioRoutingMonitor.getCurrentRouting())
        updateTileState()
    }

    private fun disconnectMediaController() {
        Log.d(TAG_MC, "Disconnecting MediaController...")
        
        playerListener?.let { listener ->
            mediaController?.removeListener(listener)
        }
        playerListener = null

        controllerFuture?.let { future ->
            try {
                MediaController.releaseFuture(future)
            } catch (e: Exception) {
                Log.w(TAG_MC, "Error releasing controller future", e)
            }
        }
        controllerFuture = null
        mediaController = null
    }

    private fun updateTileState() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { updateTileState() }
            return
        }

        val tile = qsTile ?: run {
            Log.w(TAG_QS, "Cannot update tile: qsTile is null")
            return
        }
        
        try {
            val tileState = stateManager.getTileState()

            tile.state = when (tileState.state) {
                TileStateManager.State.ACTIVE -> Tile.STATE_ACTIVE
                TileStateManager.State.INACTIVE -> Tile.STATE_INACTIVE
                TileStateManager.State.UNAVAILABLE -> Tile.STATE_UNAVAILABLE
            }

            tile.label = tileState.label

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = tileState.subtitle
            }

            val iconRes = when (tileState.iconType) {
                TileStateManager.IconType.PLAY -> R.drawable.ic_tile_play
                TileStateManager.IconType.PAUSE -> R.drawable.ic_tile_pause
            }

            tile.icon = Icon.createWithResource(this, iconRes)
            tile.updateTile()

            Log.d(TAG_QS, "Tile updated → State: ${tileState.state}, Label: \"${tileState.label}\", Subtitle: \"${tileState.subtitle}\"")

        } catch (e: Exception) {
            Log.e(TAG_QS, "Failed to update tile UI", e)
        }
    }

    private fun launchApp() {
        Log.d(TAG_QS, "launchApp fallback invoked")
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_FROM_QS_TILE, true)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG_QS, "Failed to launch app via startActivityAndCollapse", e)
            try {
                startActivity(intent)
            } catch (e2: Exception) {
                Log.e(TAG_QS, "Fallback launch also failed", e2)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG_QS, "━━━ onDestroy - cleaning up resources ━━━")

        if (isRegistered) {
            try {
                unregisterReceiver(audioRoutingReceiver)
                isRegistered = false
            } catch (e: Exception) {
                Log.w(TAG_QS, "Error unregistering receiver in onDestroy", e)
            }
        }
        
        doubleTapDetector.cancel()
        disconnectMediaController()
        mainHandler.removeCallbacksAndMessages(null)
        
        Log.d(TAG_QS, "Tile service destroyed")
    }
}
