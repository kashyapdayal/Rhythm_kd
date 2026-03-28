package chromahub.rhythm.app.infrastructure.service.player

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.DeviceInfo
import chromahub.rhythm.app.infrastructure.audio.RhythmBassBoostProcessor
import chromahub.rhythm.app.infrastructure.audio.RhythmSpatializationProcessor
import chromahub.rhythm.app.shared.data.model.TransitionSettings
import chromahub.rhythm.app.util.envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import chromahub.rhythm.app.engine.DirectBitEngine
import java.util.concurrent.atomic.AtomicBoolean
import androidx.media3.exoplayer.analytics.AnalyticsListener

/**
 * Manages two ExoPlayer instances (A and B) to enable seamless crossfade transitions.
 *
 * Player A is the designated "master" player, which is exposed to the MediaSession.
 * Player B is the auxiliary player used to pre-buffer and fade in the next track.
 * After a transition, the players swap roles — Player A adopts the state of Player B,
 * ensuring continuity for the MediaSession.
 */
@OptIn(UnstableApi::class)
class RhythmPlayerEngine(
    private val context: Context,
    private val nativeAudioEngine: chromahub.rhythm.app.infrastructure.audio.native.NativeAudioEngine? = null
) {
    private var siphonPath: OutputRouter.RoutingPath.Siphon? = null

    private var _masterPlayer: Player? = null
    val masterPlayer: Player
        get() = _masterPlayer ?: throw IllegalStateException("RhythmPlayerEngine not initialized")

    @Suppress("DEPRECATION")
    private fun wrapPlayer(player: Player): Player {
        return object : ForwardingPlayer(player) {
            override fun getDeviceInfo(): DeviceInfo {
                return if (siphonPath != null) {
                    DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
                        .setMinVolume(0)
                        .setMaxVolume(100)
                        .build()
                } else {
                    super.getDeviceInfo()
                }
            }

            override fun getDeviceVolume(): Int {
                return if (siphonPath != null) {
                    (chromahub.rhythm.app.infrastructure.audio.siphon.SiphonIsochronousEngine.nativeGetCurrentVolume() * 100f).toInt()
                } else {
                    super.getDeviceVolume()
                }
            }

            @Deprecated("Deprecated in upstream Player API")
            override fun setDeviceVolume(volume: Int) {
                if (siphonPath != null) {
                    chromahub.rhythm.app.infrastructure.audio.siphon.SiphonIsochronousEngine.nativeSetHardwareVolume(volume)
                } else {
                    super.setDeviceVolume(volume)
                }
            }

            @Deprecated("Deprecated in upstream Player API")
            override fun increaseDeviceVolume() {
                if (siphonPath != null) {
                    val current = getDeviceVolume()
                    setDeviceVolume((current + 5).coerceAtMost(100))
                } else {
                    super.increaseDeviceVolume()
                }
            }

            @Deprecated("Deprecated in upstream Player API")
            override fun decreaseDeviceVolume() {
                if (siphonPath != null) {
                    val current = getDeviceVolume()
                    setDeviceVolume((current - 5).coerceAtMost(0))
                } else {
                    super.decreaseDeviceVolume()
                }
            }
        }
    }

    // Track current routing path to prevent double initialization
    private var currentRoutingPath: OutputRouter.RoutingPath? = null

    // FIX 2: OutputRouter reference for isRoutingChangePending() check in NOISY receiver
    var outputRouter: OutputRouter? = null

    // FIX 7: Callback invoked when ExoPlayer assigns a non-zero audioSessionId
    var onAudioSessionIdAvailable: ((Int) -> Unit)? = null

    // FIX 3: Player state capture/restore
    data class PlayerState(
        val mediaItems: List<MediaItem>,
        val currentIndex: Int,
        val positionMs: Long,
        val playWhenReady: Boolean
    )

    fun captureState(): PlayerState {
        val items = buildList {
            for (i in 0 until playerA.mediaItemCount) add(playerA.getMediaItemAt(i))
        }
        return PlayerState(
            mediaItems = items,
            currentIndex = playerA.currentMediaItemIndex,
            positionMs = playerA.currentPosition,
            playWhenReady = playerA.playWhenReady
        )
    }

    fun restoreState(state: PlayerState) {
        if (state.mediaItems.isEmpty()) return
        playerA.setMediaItems(state.mediaItems, state.currentIndex, state.positionMs)
        playerA.prepare()
        playerA.playWhenReady = state.playWhenReady
        Log.d(TAG, "restoreState: ${state.mediaItems.size} items, index=${state.currentIndex}, pos=${state.positionMs}")
    }

    // FIX 9: Pause with confirmed isPlaying=false polling
    suspend fun pauseForRouting() {
        if (!::playerA.isInitialized) return
        if (!playerA.isPlaying && !playerA.playWhenReady) {
            Log.i(TAG, "pauseForRouting: already paused, skipping")
            return
        }
        playerA.pause()
        withTimeoutOrNull(500L) {
            while (playerA.isPlaying) {
                delay(16L)
            }
        } ?: Log.w(TAG, "pauseForRouting: timeout waiting for player to stop")
        Log.i(TAG, "pauseForRouting: confirmed paused")
    }

    suspend fun resumeAfterRouting() {
        Log.i(TAG, "resumeAfterRouting: playback resumed on new audio path")
    }

    fun setRoutingPath(path: OutputRouter.RoutingPath) {
        if (currentRoutingPath == path && ::playerA.isInitialized) {
            Log.d(TAG, "Routing path unchanged ($path), skipping re-initialization")
            return
        }
        currentRoutingPath = path
        when (path) {
            is OutputRouter.RoutingPath.Siphon -> siphonPath = path
            else -> siphonPath = null
        }
        Log.i(TAG, "Routing path updated in engine: $path")
    }
    
    // Bug 3 FIX: Update audio session ID for native engine after ExoPlayer assigns it
    fun updateAudioSessionId(audioSessionId: Int) {
        if (audioSessionId > 0 && audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
            _activeAudioSessionId.value = audioSessionId
            nativeAudioEngine?.updateAudioSessionId(audioSessionId)
            Log.d(TAG, "Updated native audio engine with valid session ID: $audioSessionId")
        } else {
            Log.w(TAG, "Attempted to update with invalid session ID: $audioSessionId")
        }
    }
    // Current processor instances for both players to avoid sharing state
    private var playerABassBoost: RhythmBassBoostProcessor? = null
    private var playerBBassBoost: RhythmBassBoostProcessor? = null
    private var playerASpatialization: RhythmSpatializationProcessor? = null
    private var playerBSpatialization: RhythmSpatializationProcessor? = null
    private var playerAReplayGain: chromahub.rhythm.app.infrastructure.audio.RhythmReplayGainProcessor? = null
    private var playerBReplayGain: chromahub.rhythm.app.infrastructure.audio.RhythmReplayGainProcessor? = null
    
    // Shared settings but independent processor instances
    private var bassBoostEnabled = false
    private var bassBoostStrength: Short = 0
    private var spatializationEnabled = false
    private var spatializationStrength: Short = 0
    private var replayGainEnabled = false
    companion object {
        private const val TAG = "RhythmPlayerEngine"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var transitionJob: Job? = null
    private var transitionRunning = false

    private lateinit var playerA: ExoPlayer
    private lateinit var playerB: ExoPlayer

    private val onPlayerSwappedListeners = mutableListOf<(Player) -> Unit>()

    // Active Audio Session ID Flow — used for equalizer re-attachment
    private val _activeAudioSessionId = MutableStateFlow(0)
    val activeAudioSessionId: StateFlow<Int> = _activeAudioSessionId.asStateFlow()

    // Audio Focus Management — managed manually so both players share a single focus request
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var isFocusLossPause = false

    private var isDucked = false
    private val DUCK_VOLUME_MULTIPLIER = 0.0316f // Roughly -30dB decrease

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        val exclusiveMode = currentRoutingPath is OutputRouter.RoutingPath.Siphon
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.d(TAG, "AudioFocus LOSS. Pausing both players.")
                isFocusLossPause = false
                playerA.playWhenReady = false
                playerB.playWhenReady = false
                abandonAudioFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d(TAG, "AudioFocus LOSS_TRANSIENT. Pausing.")
                isFocusLossPause = true
                playerA.playWhenReady = false
                playerB.playWhenReady = false
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (exclusiveMode) {
                    Log.d(TAG, "AudioFocus LOSS_TRANSIENT_CAN_DUCK in exclusive mode. Pausing immediately.")
                    playerA.playWhenReady = false
                    playerB.playWhenReady = false
                } else {
                    Log.d(TAG, "AudioFocus LOSS_TRANSIENT_CAN_DUCK. Ducking volume (-30dB).")
                    isDucked = true
                    playerA.volume *= DUCK_VOLUME_MULTIPLIER
                    if (::playerB.isInitialized) playerB.volume *= DUCK_VOLUME_MULTIPLIER
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "AudioFocus GAIN. Resuming if paused by loss.")
                if (isDucked) {
                    isDucked = false
                    playerA.volume /= DUCK_VOLUME_MULTIPLIER
                    if (::playerB.isInitialized) playerB.volume /= DUCK_VOLUME_MULTIPLIER
                }
                if (isFocusLossPause) {
                    isFocusLossPause = false
                    playerA.playWhenReady = true
                    if (transitionRunning && ::playerB.isInitialized) playerB.playWhenReady = true
                }
            }
        }
    }

    // FIX 2: Manual AUDIO_BECOMING_NOISY receiver that suppresses during USB routing changes
    private var lastNoisyEventMs = 0L

    private val noisyReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
            if (intent.action != android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            
            val now = System.currentTimeMillis()
            if (now - lastNoisyEventMs < 500) return // deduplicate
            lastNoisyEventMs = now

            if (outputRouter?.isRoutingChangePending() == true) {
                Log.i(TAG, "AUDIO_BECOMING_NOISY suppressed — USB DAC routing in progress")
                return
            }
            Log.i(TAG, "AUDIO_BECOMING_NOISY — pausing for real headphone unplug")
            if (::playerA.isInitialized) playerA.pause()
        }
    }
    private var isNoisyReceiverRegistered = false

    fun registerNoisyReceiver(appContext: android.content.Context) {
        if (!isNoisyReceiverRegistered) {
            appContext.registerReceiver(
                noisyReceiver,
                android.content.IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            )
            isNoisyReceiverRegistered = true
        }
    }

    fun unregisterNoisyReceiver(appContext: android.content.Context) {
        if (isNoisyReceiverRegistered) {
            try { appContext.unregisterReceiver(noisyReceiver) } catch (_: Exception) {}
            isNoisyReceiverRegistered = false
        }
    }

    // Listener attached to the active master player (playerA) for audio focus management
    private val masterPlayerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val reasonStr = when (reason) {
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "USER_REQUEST"
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "AUDIO_FOCUS_LOSS"
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "AUDIO_BECOMING_NOISY"
                Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "REMOTE"
                Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "END_OF_MEDIA_ITEM"
                else -> "UNKNOWN($reason)"
            }
            Log.i(TAG, "⚡ onPlayWhenReadyChanged: $playWhenReady, reason=$reasonStr")
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) {
                // NOISY is handled by noisyReceiver — skip default ExoPlayer behaviour
                return
            }
            if (playWhenReady) {
                requestAudioFocus()
            } else {
                if (!isFocusLossPause) {
                    abandonAudioFocus()
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val stateStr = when (playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN($playbackState)"
            }
            Log.i(TAG, "⚡ onPlaybackStateChanged: $stateStr")
            if (playbackState == Player.STATE_READY) {
                Log.i(TAG, "  → isPlaying=${playerA.isPlaying}, playWhenReady=${playerA.playWhenReady}, " +
                        "audioSessionId=${playerA.audioSessionId}")
            }
            if (playbackState == Player.STATE_ENDED) {
                Log.w(TAG, "  → Player reached ENDED state. Check if audio was actually output.")
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Log.e(TAG, "❌ onPlayerError: ${error.errorCodeName} — ${error.message}", error)
        }

        override fun onPlayerErrorChanged(error: androidx.media3.common.PlaybackException?) {
            if (error != null) {
                Log.e(TAG, "❌ onPlayerErrorChanged: ${error.errorCodeName} — ${error.message}")
            } else {
                Log.d(TAG, "⚡ onPlayerErrorChanged: error cleared")
            }
        }
        
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            _activeAudioSessionId.value = audioSessionId
            Log.d(TAG, "Audio session ID changed: $audioSessionId")
        }
        
        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            // Log selected audio track info for debugging
            val audioTrack = tracks.groups
                .firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            if (audioTrack != null && audioTrack.length > 0) {
                val format = audioTrack.getTrackFormat(0)
                Log.i(TAG, "⚡ Audio track selected: ${format.sampleMimeType}, " +
                        "sr=${format.sampleRate}, ch=${format.channelCount}, " +
                        "enc=${format.pcmEncoding}, bitrate=${format.bitrate}")
                // format log removed
            } else {
                Log.w(TAG, "⚡ onTracksChanged: No audio track selected!")
            }
        }

        override fun onRenderedFirstFrame() {
            Log.i(TAG, "⚡ onRenderedFirstFrame — audio pipeline is active")
        }
    }

    fun addPlayerSwapListener(listener: (Player) -> Unit) {
        onPlayerSwappedListeners.add(listener)
    }

    fun removePlayerSwapListener(listener: (Player) -> Unit) {
        onPlayerSwappedListeners.remove(listener)
    }



    fun isTransitionRunning(): Boolean = transitionRunning

    /**
     * Fix 8: Wait for the player to exit BUFFERING state before allowing AudioSink swap.
     * Swapping during BUFFERING causes the enc=2 → enc=21 format flip.
     */
    suspend fun waitForSafeSwapState() {
        if (!::playerA.isInitialized) return
        if (playerA.playbackState == Player.STATE_BUFFERING) {
            Log.i(TAG, "waitForSafeSwapState: BUFFERING — waiting up to 3s")
            withTimeoutOrNull(3000L) {
                while (playerA.playbackState == Player.STATE_BUFFERING) {
                    delay(32L)
                }
            } ?: Log.w(TAG, "waitForSafeSwapState: timeout expired")
            Log.i(TAG, "waitForSafeSwapState: state=${playerA.playbackState}, safe to swap")
        }
    }

    fun swapAudioSink(newSink: androidx.media3.exoplayer.audio.AudioSink) {
        if (!::playerA.isInitialized) return
        if (playerA.playbackState == Player.STATE_BUFFERING) {
            Log.w(TAG, "swapAudioSink: called during BUFFERING... ignoring request to prevent format flip")
            return
        }
        Log.d(TAG, "swapAudioSink → triggering re-initialization to swap sink safely")
        val state = captureState()
        // Override debounce temporarily to ensure rebuild
        lastInitTime = 0L 
        initialize()
        restoreState(state)
    }

    fun getAudioSessionId(): Int = if (::playerA.isInitialized) playerA.audioSessionId else 0

    private var isReleased = false

    private var currentUsbDevice: android.media.AudioDeviceInfo? = null

    private var lastInitTime = 0L
    private val isInitializing = AtomicBoolean(false)

    
    private var initJob: Job? = null
    private val initMutex = Mutex()
    private val directBitEngine by lazy { DirectBitEngine(context) }
    private var currentSampleRate = 44100
    private var currentBitDepth = 16
    private var currentChannels = 2

    fun onDeviceChanged(device: android.hardware.usb.UsbDevice?) {
        initJob?.cancel()
        initJob = scope.launch {
            delay(500)
            initMutex.withLock {
                directBitEngine.release()
                directBitEngine.initialize(device, currentSampleRate, currentBitDepth, currentChannels)
            }
        }
    }

    fun initialize() {
        if (!isInitializing.compareAndSet(false, true)) {
            Log.d(TAG, "initialize() ignored - already running")
            return
        }
        try {
        // Fix Bug 6: Prevent redundant 4x initialization
        val now = System.currentTimeMillis()
        if (now - lastInitTime < 1000) {
            Log.d(TAG, "Ignoring redundant initialize() call (de-bounced)")
            return
        }
        lastInitTime = now

        if (!isReleased && ::playerA.isInitialized && playerA.applicationLooper.thread.isAlive) return

        if (::playerA.isInitialized) {
            try { playerA.release() } catch (_: Exception) {}
        }
        if (::playerB.isInitialized) {
            try { playerB.release() } catch (_: Exception) {}
        }

        playerA = buildPlayer(handleAudioFocus = false)
        playerB = buildPlayer(false)
        
        _masterPlayer = wrapPlayer(playerA)
        
        playerA.addListener(masterPlayerListener)

        // FIX 7: Wire AnalyticsListener to fire onAudioSessionIdAvailable callback
        playerA.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioSessionIdChanged(
                eventTime: AnalyticsListener.EventTime,
                audioSessionId: Int
            ) {
                if (audioSessionId != 0) {
                    Log.i(TAG, "audioSessionId available: $audioSessionId")
                    _activeAudioSessionId.value = audioSessionId
                    onAudioSessionIdAvailable?.invoke(audioSessionId)
                }
            }
        })

        _activeAudioSessionId.value = playerA.audioSessionId

        isReleased = false
        Log.d(TAG, "RhythmPlayerEngine initialized. SessionA=${playerA.audioSessionId}")
        } finally {
            isInitializing.set(false)
        }
    }

    private fun requestAudioFocus() {
        if (audioFocusRequest != null) return

        val attributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val focusMode = if (currentRoutingPath is OutputRouter.RoutingPath.Siphon) {
            Log.d(TAG, "Requesting AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE for true USB bypass")
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
        } else {
            AudioManager.AUDIOFOCUS_GAIN
        }

        val request = AudioFocusRequest.Builder(focusMode)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusChangeListener)
            .build()

        val result = audioManager.requestAudioFocus(request)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioFocusRequest = request
        } else {
            Log.w(TAG, "AudioFocus Request Failed: $result")
            playerA.playWhenReady = false
        }
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
            audioFocusRequest = null
        }
    }

    private fun buildPlayer(handleAudioFocus: Boolean): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 30_000, 1_500, 2_500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
            
        // Create NEW processor instances for this player
        val bassBoost = RhythmBassBoostProcessor().apply {
            setEnabled(bassBoostEnabled)
            setStrength(bassBoostStrength)
        }
        val spatialization = RhythmSpatializationProcessor().apply {
            setEnabled(spatializationEnabled)
            setStrength(spatializationStrength)
        }
        val replayGain = chromahub.rhythm.app.infrastructure.audio.RhythmReplayGainProcessor()
        
        // Track which player we are building (simple version)
        val isPlayerA = !::playerA.isInitialized
        if (isPlayerA) {
            playerABassBoost = bassBoost
            playerASpatialization = spatialization
            playerAReplayGain = replayGain
        } else {
            playerBBassBoost = bassBoost
            playerBSpatialization = spatialization
            playerBReplayGain = replayGain
        }

        val renderersFactory = if (siphonPath != null) {
            Log.i(TAG, "Using SiphonUsbAudioSink for True USB Bypass")
            val siphonSink = chromahub.rhythm.app.infrastructure.audio.siphon.SiphonUsbAudioSink(
                context = context,
                usbConnection = siphonPath!!.connection,
                usbDevice = siphonPath!!.device,
                deviceCapabilities = siphonPath!!.capabilities,
                initialRoutingMode = chromahub.rhythm.app.infrastructure.audio.siphon.SiphonRoutingMode.SOFTWARE
            )
            
            object : DefaultRenderersFactory(context) {
                override fun buildAudioRenderers(
                    context: Context,
                    extensionRendererMode: Int,
                    mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                    enableDecoderFallback: Boolean,
                    audioSink: androidx.media3.exoplayer.audio.AudioSink,
                    eventHandler: android.os.Handler,
                    eventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener,
                    out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>
                ) {
                    super.buildAudioRenderers(
                        context,
                        extensionRendererMode,
                        mediaCodecSelector,
                        enableDecoderFallback,
                        siphonSink,
                        eventHandler,
                        eventListener,
                        out
                    )
                }
            }
        } else {
            // FIX 11: Float output keeps 24/32-bit files as float32 (lossless)
            Log.d(TAG, "Using DefaultRenderersFactory with float output")
            DefaultRenderersFactory(context).apply {
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                setEnableAudioFloatOutput(true)
            }
        }

        val audioFlags = if (siphonPath != null) C.FLAG_AUDIBILITY_ENFORCED else 0
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .setFlags(audioFlags)
            .build()

        return ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .build().apply {
                setAudioAttributes(audioAttributes, handleAudioFocus)
                // FIX 2: NOISY is handled manually below — suppress ExoPlayer's default handler
                // so we can check isRoutingChangePending() before pausing.
                setHandleAudioBecomingNoisy(false)
                setWakeMode(C.WAKE_MODE_LOCAL)
                setSkipSilenceEnabled(false)
                playWhenReady = false
            }
    }

    fun setPauseAtEndOfMediaItems(shouldPause: Boolean) {
        // Must be called on ExoPlayer's application thread (main thread)
        if (::playerA.isInitialized) {
            val handler = android.os.Handler(playerA.applicationLooper)
            handler.post {
                playerA.pauseAtEndOfMediaItems = shouldPause
            }
        }
    }

    fun setBassBoost(enabled: Boolean, strength: Short) {
        this.bassBoostEnabled = enabled
        this.bassBoostStrength = strength
        playerABassBoost?.setEnabled(enabled)
        playerABassBoost?.setStrength(strength)
        playerBBassBoost?.setEnabled(enabled)
        playerBBassBoost?.setStrength(strength)
    }

    fun setSpatialization(enabled: Boolean, strength: Short) {
        this.spatializationEnabled = enabled
        this.spatializationStrength = strength
        playerASpatialization?.setEnabled(enabled)
        playerASpatialization?.setStrength(strength)
        playerBSpatialization?.setEnabled(enabled)
        playerBSpatialization?.setStrength(strength)
    }

    fun setReplayGain(gain: Float, peak: Float, preventClipping: Boolean) {
        this.replayGainEnabled = true
        playerAReplayGain?.setReplayGain(gain, peak, preventClipping)
        playerBReplayGain?.setReplayGain(gain, peak, preventClipping)
    }

    fun setReplayGainEnabled(enabled: Boolean) {
        this.replayGainEnabled = enabled
        playerAReplayGain?.setEnabled(enabled)
        playerBReplayGain?.setEnabled(enabled)
    }

    /* Getters for diagnostics and state tracking */
    fun getBassBoostStrength(): Short = bassBoostStrength
    fun isBassBoostEnabled(): Boolean = bassBoostEnabled
    fun getBassBoostProcessor(): RhythmBassBoostProcessor? = playerABassBoost
    
    fun getSpatializationStrength(): Short = spatializationStrength
    fun isSpatializationEnabled(): Boolean = spatializationEnabled
    fun getSpatializationProcessor(): RhythmSpatializationProcessor? = playerASpatialization
    
    fun isReplayGainEnabled(): Boolean = replayGainEnabled

    /**
     * Enables or disables gapless playback.
     * When enabled, uses ExoPlayer's native gapless mechanism.
     */
    fun setGaplessPlayback(enabled: Boolean) {
        if (::playerA.isInitialized) {
            playerA.pauseAtEndOfMediaItems = !enabled
        }
        if (::playerB.isInitialized) {
            playerB.pauseAtEndOfMediaItems = !enabled
        }
        Log.d(TAG, "Gapless playback ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Routes the audio output to a specific USB device, or restores default routing if null.
     */
    fun setUsbAudioDevice(deviceInfo: android.media.AudioDeviceInfo?) {
        val changed = currentUsbDevice != deviceInfo
        currentUsbDevice = deviceInfo
        
        if (changed) {
            // Re-request audio focus to apply exclusive mode if transitioned while playing
            if (::playerA.isInitialized && playerA.playbackState == ExoPlayer.STATE_READY && playerA.playWhenReady) {
                abandonAudioFocus()
                requestAudioFocus()
            }
        }
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (::playerA.isInitialized) playerA.setPreferredAudioDevice(deviceInfo)
            if (::playerB.isInitialized) playerB.setPreferredAudioDevice(deviceInfo)
            Log.d(TAG, "USB Audio Device set to: ${deviceInfo?.productName ?: "None"}")
        }
    }

    /**
     * Pre-buffers the next track on Player B.
     * Sets volume to 0 and pauses, ready for the crossfade transition.
     */
    fun prepareNext(mediaItem: MediaItem, startPositionMs: Long = 0L) {
        try {
            Log.d(TAG, "prepareNext called for ${mediaItem.mediaId}")
            playerB.stop()
            playerB.clearMediaItems()
            playerB.playWhenReady = false
            playerB.setMediaItem(mediaItem)
            playerB.prepare()
            playerB.volume = 0f
            if (startPositionMs > 0) {
                playerB.seekTo(startPositionMs)
            } else {
                playerB.seekTo(0)
            }
            playerB.pause()
            Log.d(TAG, "Player B prepared, paused, volume=0")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare next player", e)
        }
    }

    /**
     * Cancels any pending transition and resets Player B.
     */
    fun cancelNext() {
        transitionJob?.cancel()
        transitionRunning = false
        if (::playerB.isInitialized && playerB.mediaItemCount > 0) {
            Log.d(TAG, "Cancelling next player")
            playerB.stop()
            playerB.clearMediaItems()
        }
        if (::playerA.isInitialized) {
            playerA.volume = 1f
            setPauseAtEndOfMediaItems(false)
        }
    }

    /**
     * Performs the crossfade transition using the given settings.
     */
    fun performTransition(settings: TransitionSettings) {
        transitionJob?.cancel()
        transitionRunning = true
        transitionJob = scope.launch {
            try {
                performOverlapTransition(settings)
            } catch (e: Exception) {
                Log.e(TAG, "Error performing transition", e)
                playerA.volume = 1f
                setPauseAtEndOfMediaItems(false)
                playerB.stop()
            } finally {
                transitionRunning = false
            }
        }
    }

    /**
     * Core crossfade logic:
     * 1. Waits for Player B to be ready
     * 2. Starts Player B at volume 0
     * 3. Swaps players EARLY so UI immediately shows the new song
     * 4. Transfers queue history/future and playback settings
     * 5. Runs a fade loop with shaped curves
     * 6. Releases old player and recreates it fresh
     */
    private suspend fun performOverlapTransition(settings: TransitionSettings) {
        Log.d(TAG, "Starting crossfade. Duration: ${settings.durationMs}ms")

        if (playerB.mediaItemCount == 0) {
            Log.w(TAG, "Skipping overlap — next player not prepared (count=0)")
            playerA.volume = 1f
            setPauseAtEndOfMediaItems(false)
            return
        }

        // Ensure Player B is ready
        if (playerB.playbackState == Player.STATE_IDLE) {
            Log.d(TAG, "Player B idle. Preparing now.")
            playerB.prepare()
        }

        var readinessChecks = 0
        while (playerB.playbackState == Player.STATE_BUFFERING && readinessChecks < 120) {
            delay(25)
            readinessChecks++
        }

        if (playerB.playbackState != Player.STATE_READY) {
            Log.w(TAG, "Player B not ready for overlap. State=${playerB.playbackState}")
            playerA.volume = 1f
            setPauseAtEndOfMediaItems(false)
            return
        }

        // Start Player B playing at volume 0
        playerB.volume = 0f
        playerA.volume = 1f
        if (!playerA.isPlaying && playerA.playbackState == Player.STATE_READY) {
            playerA.play()
        }

        playerB.playWhenReady = true
        playerB.play()

        Log.d(TAG, "Player B started. Playing=${playerB.isPlaying}, state=${playerB.playbackState}")

        // Wait for Player B to actually start rendering audio
        var playChecks = 0
        while (!playerB.isPlaying && playChecks < 80) {
            delay(25)
            playChecks++
        }

        if (!playerB.isPlaying) {
            Log.e(TAG, "Player B failed to start in time. Aborting crossfade.")
            playerA.volume = 1f
            setPauseAtEndOfMediaItems(false)
            return
        }

        delay(75) // Small stabilization delay

        // --- SWAP PLAYERS EARLY (Before Fade) ---
        // This makes the UI immediately show the new song
        val outgoingPlayer = playerA
        val incomingPlayer = playerB

        val isSelfTransition = outgoingPlayer.currentMediaItem?.mediaId == incomingPlayer.currentMediaItem?.mediaId
        val currentOutgoingIndex = outgoingPlayer.currentMediaItemIndex

        // Transfer queue history (items before current)
        val historyToTransfer = mutableListOf<MediaItem>()
        val historyEndIndex = if (isSelfTransition) currentOutgoingIndex else currentOutgoingIndex + 1
        for (i in 0 until historyEndIndex) {
            historyToTransfer.add(outgoingPlayer.getMediaItemAt(i))
        }

        // Transfer queue future (items after next)
        val futureToTransfer = mutableListOf<MediaItem>()
        val futureStartIndex = if (isSelfTransition) currentOutgoingIndex + 1 else currentOutgoingIndex + 2
        for (i in futureStartIndex until outgoingPlayer.mediaItemCount) {
            futureToTransfer.add(outgoingPlayer.getMediaItemAt(i))
        }

        // Transfer playback settings
        val repeatModeToTransfer = outgoingPlayer.repeatMode
        val shuffleModeToTransfer = outgoingPlayer.shuffleModeEnabled
        val playbackParamsToTransfer = outgoingPlayer.playbackParameters
        incomingPlayer.repeatMode = repeatModeToTransfer
        incomingPlayer.shuffleModeEnabled = shuffleModeToTransfer
        incomingPlayer.playbackParameters = playbackParamsToTransfer
        Log.d(TAG, "Transferred playback settings: repeat=$repeatModeToTransfer, shuffle=$shuffleModeToTransfer, speed=${playbackParamsToTransfer.speed}, pitch=${playbackParamsToTransfer.pitch}")

        // Swap the player references
        outgoingPlayer.removeListener(masterPlayerListener)

        playerA = incomingPlayer
        playerB = outgoingPlayer

        playerB.pauseAtEndOfMediaItems = false
        playerA.pauseAtEndOfMediaItems = false

        playerA.addListener(masterPlayerListener)
        _masterPlayer = wrapPlayer(playerA)
        
        if (playerA.playWhenReady) {
            requestAudioFocus()
        }

        // Add history and future items to the new master player
        if (historyToTransfer.isNotEmpty()) {
            playerA.addMediaItems(0, historyToTransfer)
            Log.d(TAG, "Transferred ${historyToTransfer.size} history items.")
        }

        if (futureToTransfer.isNotEmpty()) {
            playerA.addMediaItems(futureToTransfer)
            Log.d(TAG, "Transferred ${futureToTransfer.size} future items.")
        }

        // Notify listeners about the player swap
        onPlayerSwappedListeners.forEach { it(_masterPlayer!!) }

        _activeAudioSessionId.value = playerA.audioSessionId

        Log.d(TAG, "Players swapped EARLY. UI should now show next song.")

        // *** FADE LOOP — shaped volume curves ***
        val duration = settings.durationMs.toLong().coerceAtLeast(500L)
        val stepMs = 16L
        var elapsed = 0L

        while (elapsed <= duration) {
            val progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
            val volIn = envelope(progress, settings.curveIn)
            val volOut = 1f - envelope(progress, settings.curveOut)

            playerA.volume = volIn
            playerB.volume = volOut.coerceIn(0f, 1f)

            if (playerA.playbackState == Player.STATE_ENDED || playerB.playbackState == Player.STATE_ENDED) {
                Log.w(TAG, "A player ended during crossfade (A=${playerA.playbackState}, B=${playerB.playbackState})")
                break
            }

            delay(stepMs)
            elapsed += stepMs
        }

        Log.d(TAG, "Crossfade loop finished.")
        playerB.volume = 0f
        playerA.volume = 1f

        // Clean up outgoing player
        playerB.pause()
        playerB.stop()
        playerB.clearMediaItems()

        // Release and recreate Player B fresh to avoid OEM stale session bugs
        playerB.release()
        playerB = buildPlayer(handleAudioFocus = false)
        Log.d(TAG, "Old player released and recreated fresh.")

        setPauseAtEndOfMediaItems(false)
    }

    /**
     * Releases both players and cleans up resources.
     */
    fun release() {
        transitionJob?.cancel()
        abandonAudioFocus()
        if (::playerA.isInitialized) {
            playerA.removeListener(masterPlayerListener)
            playerA.release()
        }
        if (::playerB.isInitialized) playerB.release()
        isReleased = true
        Log.d(TAG, "RhythmPlayerEngine released.")
    }
}
