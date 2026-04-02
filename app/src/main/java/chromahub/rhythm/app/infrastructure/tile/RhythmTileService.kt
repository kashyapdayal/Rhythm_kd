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
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import chromahub.rhythm.app.BuildConfig
import chromahub.rhythm.app.R
import chromahub.rhythm.app.activities.MainActivity
import chromahub.rhythm.app.infrastructure.service.MediaPlaybackService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

/**
 * Pixel-quality Quick Settings Tile with instant response and zero lag.
 * 
 * Optimizations:
 * - Instant tap response (<50ms)
 * - Debounced tile updates (200ms minimum interval)
 * - Cached audio routing (updates only via BroadcastReceiver)
 * - Persistent MediaController (no reconnections)
 * - Lightweight onClick() (non-blocking)
 * - Production-grade lifecycle management
 */
@RequiresApi(Build.VERSION_CODES.N)
class RhythmTileService : TileService() {

    companion object {
        private const val TAG = "QSTile"
        private const val TAG_MC = "QSTile_MC"
        
        // Tile update debouncing
        private const val MIN_UPDATE_INTERVAL_MS = 200L
        
        // MediaController retry
        private const val RETRY_DELAY_MS = 3000L
        
        const val EXTRA_FROM_QS_TILE = "extra_from_qs_tile"
    }

    // MediaController - persistent across sessions
    private var mediaController: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var playerListener: Player.Listener? = null
    
    // State management
    private val stateManager = TileStateManager()
    private lateinit var audioRoutingMonitor: AudioRoutingMonitor
    
    // Tap detection
    private lateinit var doubleTapDetector: DoubleTapDetector
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Command cancellation for double-tap
    @Volatile
    private var lastCommandTimestamp = 0L
    private val COMMAND_CANCELLATION_WINDOW_MS = 100L // 100ms window to cancel
    
    // Tile update debouncing
    @Volatile
    private var lastTileUpdateTime = 0L
    private var pendingUpdateRunnable: Runnable? = null
    
    // BroadcastReceiver registration tracking
    private var isAudioReceiverRegistered = false

    // Audio routing change receiver
    private val audioRoutingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Update cached routing and refresh tile
            stateManager.setAudioRouting(audioRoutingMonitor.updateCachedRouting())
            scheduleTileUpdate()
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "onCreate")
        }

        audioRoutingMonitor = AudioRoutingMonitor(this)

        doubleTapDetector = DoubleTapDetector(
            windowMs = 300L,
            onSingleTapImmediate = { executeSingleTap() },
            onDoubleTapDetected = { executeDoubleTap() },
            cancelPendingAction = { cancelPendingCommand() }
        )
    }

    override fun onStartListening() {
        super.onStartListening()
        
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "onStartListening")
        }

        // Register audio routing receiver
        if (!isAudioReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(AudioManager.ACTION_HEADSET_PLUG)
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            try {
                registerReceiver(audioRoutingReceiver, filter)
                isAudioReceiverRegistered = true
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "Failed to register audio receiver", e)
                }
            }
        }

        // Connect MediaController if not already connected
        if (mediaController == null && controllerFuture == null) {
            connectMediaController()
        } else {
            // Reuse existing controller and update immediately
            updateFromMediaController()
        }

        // Update audio routing from cache (fast)
        stateManager.setAudioRouting(audioRoutingMonitor.getCurrentRouting())
        updateTileImmediate()
    }

    override fun onStopListening() {
        super.onStopListening()
        
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "onStopListening")
        }

        // Unregister audio receiver
        if (isAudioReceiverRegistered) {
            try {
                unregisterReceiver(audioRoutingReceiver)
                isAudioReceiverRegistered = false
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "Error unregistering audio receiver", e)
                }
            }
        }

        // Cancel pending updates
        doubleTapDetector.cancel()
        pendingUpdateRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingUpdateRunnable = null
        
        // Keep MediaController alive for fast reconnection
    }

    /**
     * CRITICAL: onClick() must be lightweight and non-blocking.
     * All heavy work is done in background.
     */
    override fun onClick() {
        super.onClick()
        
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "onClick")
        }
        
        // Instant tap detection - no delays
        doubleTapDetector.onTap()
    }

    /**
     * Execute single tap (play/pause) immediately.
     */
    private fun executeSingleTap() {
        lastCommandTimestamp = System.currentTimeMillis()
        
        val controller = mediaController
        if (controller == null) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Controller null - launching app")
            }
            launchApp()
            return
        }

        // Check if controller is in valid state
        if (controller.playbackState == Player.STATE_IDLE) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Controller IDLE - launching app")
            }
            launchApp()
            return
        }

        try {
            // Execute command immediately
            if (controller.isPlaying) {
                controller.pause()
            } else {
                controller.play()
            }
            
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Play/pause executed")
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "Error toggling playback", e)
            }
        }
    }

    /**
     * Execute double tap (skip next).
     */
    private fun executeDoubleTap() {
        val controller = mediaController
        if (controller == null) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Controller null on double tap - launching app")
            }
            launchApp()
            return
        }

        if (!controller.hasNextMediaItem()) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "No next track available")
            }
            return
        }

        try {
            controller.seekToNext()
            
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Skip executed")
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "Error skipping track", e)
            }
        }
    }

    /**
     * Try to cancel pending command (for double-tap cancellation).
     * Returns true if command was within cancellation window.
     */
    private fun cancelPendingCommand(): Boolean {
        val elapsed = System.currentTimeMillis() - lastCommandTimestamp
        return elapsed < COMMAND_CANCELLATION_WINDOW_MS
    }

    /**
     * Connect MediaController with retry logic.
     */
    private fun connectMediaController() {
        if (controllerFuture != null) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG_MC, "Connection already in progress")
            }
            return
        }

        if (BuildConfig.DEBUG) {
            Log.d(TAG_MC, "Connecting MediaController...")
        }

        try {
            val sessionToken = SessionToken(
                this,
                ComponentName(this, MediaPlaybackService::class.java)
            )
            
            controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()

            controllerFuture?.addListener({
                try {
                    mediaController = controllerFuture?.get()
                    
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG_MC, "✓ Connected")
                    }
                    
                    setupPlayerListener()
                    updateFromMediaController()
                    
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) {
                        Log.e(TAG_MC, "✗ Connection failed", e)
                    }
                    
                    mediaController = null
                    controllerFuture = null
                    
                    // Retry after delay if still listening
                    mainHandler.postDelayed({
                        if (qsTile != null && mediaController == null) {
                            connectMediaController()
                        }
                    }, RETRY_DELAY_MS)
                }
            }, MoreExecutors.directExecutor())
            
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG_MC, "Exception during connection", e)
            }
            controllerFuture = null
        }
    }

    /**
     * Setup player listener for state changes.
     * Uses Media3 callbacks instead of polling.
     */
    private fun setupPlayerListener() {
        val controller = mediaController ?: return
        
        // Remove old listener
        playerListener?.let { controller.removeListener(it) }

        playerListener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                stateManager.setPlaybackState(isPlaying, true)
                scheduleTileUpdate()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val hasSession = playbackState != Player.STATE_IDLE
                stateManager.setPlaybackState(controller.isPlaying, hasSession)
                scheduleTileUpdate()
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                stateManager.setTrackTitle(mediaMetadata.title?.toString())
                scheduleTileUpdate()
            }
        }
        
        controller.addListener(playerListener!!)
        
        if (BuildConfig.DEBUG) {
            Log.d(TAG_MC, "Player listener attached")
        }
    }

    /**
     * Update state from MediaController.
     */
    private fun updateFromMediaController() {
        val controller = mediaController
        
        if (controller == null) {
            stateManager.setPlaybackState(playing = false, sessionActive = false)
            stateManager.setTrackTitle(null)
        } else {
            val hasSession = controller.playbackState != Player.STATE_IDLE
            stateManager.setPlaybackState(controller.isPlaying, hasSession)
            stateManager.setTrackTitle(controller.mediaMetadata.title?.toString())
        }
        
        scheduleTileUpdate()
    }

    /**
     * Schedule tile update with debouncing to prevent excessive updates.
     * Coalesces rapid updates into single call.
     */
    private fun scheduleTileUpdate() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastTileUpdateTime
        
        if (elapsed >= MIN_UPDATE_INTERVAL_MS) {
            // Enough time has passed - update immediately
            updateTileImmediate()
        } else {
            // Too soon - schedule for later
            pendingUpdateRunnable?.let { mainHandler.removeCallbacks(it) }
            
            val runnable = Runnable {
                updateTileImmediate()
                pendingUpdateRunnable = null
            }
            
            pendingUpdateRunnable = runnable
            mainHandler.postDelayed(runnable, MIN_UPDATE_INTERVAL_MS - elapsed)
        }
    }

    /**
     * Update tile immediately without debouncing.
     */
    private fun updateTileImmediate() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { updateTileImmediate() }
            return
        }

        val tile = qsTile ?: return
        
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

            lastTileUpdateTime = System.currentTimeMillis()
            
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Tile: ${tileState.state} | ${tileState.label} | ${tileState.subtitle}")
            }

        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "Error updating tile", e)
            }
        }
    }

    /**
     * Launch main app.
     */
    private fun launchApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_FROM_QS_TILE, true)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this, 
                    0, 
                    intent, 
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "Error launching app", e)
            }
        }
    }

    /**
     * Disconnect MediaController.
     */
    private fun disconnectMediaController() {
        if (BuildConfig.DEBUG) {
            Log.d(TAG_MC, "Disconnecting")
        }
        
        playerListener?.let { listener ->
            mediaController?.removeListener(listener)
        }
        playerListener = null

        controllerFuture?.let { future ->
            try {
                MediaController.releaseFuture(future)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG_MC, "Error releasing controller", e)
                }
            }
        }
        
        controllerFuture = null
        mediaController = null
    }

    override fun onDestroy() {
        super.onDestroy()
        
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "onDestroy")
        }

        if (isAudioReceiverRegistered) {
            try {
                unregisterReceiver(audioRoutingReceiver)
                isAudioReceiverRegistered = false
            } catch (e: Exception) {
                // Ignore
            }
        }
        
        doubleTapDetector.cancel()
        pendingUpdateRunnable?.let { mainHandler.removeCallbacks(it) }
        mainHandler.removeCallbacksAndMessages(null)
        disconnectMediaController()
    }
}
