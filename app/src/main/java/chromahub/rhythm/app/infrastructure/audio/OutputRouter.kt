package chromahub.rhythm.app.infrastructure.audio

import android.content.Context
import android.hardware.usb.UsbDevice
import android.media.AudioDeviceInfo
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import chromahub.rhythm.app.infrastructure.audio.usb.UsbAudioManager
import chromahub.rhythm.app.infrastructure.audio.usb.UsbDirectEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * OutputRouter — manages transitions between audio output modes.
 *
 * States:
 *   STANDARD      → DefaultAudioSink via AudioTrack (Android mixer)
 *   BIT_PERFECT   → BitPerfectAudioSink via AudioTrack (MIXER_BEHAVIOR_BIT_PERFECT on API 34+)
 *   USB_EXCLUSIVE → NativeAudioSink → C++ NativePlayer → UsbIsochronousStream (bypasses AudioFlinger)
 *
 * Transitions:
 *   1. Stop ExoPlayer
 *   2. Release current sink / USB resources
 *   3. Rebuild player with new RenderersFactory
 *   4. Restore media + position
 *   5. Prepare + play
 */
@OptIn(UnstableApi::class)
class OutputRouter(
    private val context: Context,
    private val usbAudioManager: UsbAudioManager,
    private val usbDirectEngine: UsbDirectEngine?,
    private val nativeEngine: chromahub.rhythm.app.infrastructure.audio.native.NativeAudioEngine?,
    private val onPlayerRebuilt: (ExoPlayer) -> Unit
) {
    companion object {
        private const val TAG = "OutputRouter"
        private const val MAX_USB_WRITE_ERRORS = 3
    }

    // ── State ──────────────────────────────────────────────────────

    sealed class OutputMode {
        data object Standard : OutputMode() {
            override fun toString() = "STANDARD"
        }
        data object BitPerfect : OutputMode() {
            override fun toString() = "BIT_PERFECT"
        }
        data class UsbExclusive(val device: UsbDevice) : OutputMode() {
            override fun toString() = "USB_EXCLUSIVE(${device.productName})"
        }
    }

    private val _currentMode = MutableStateFlow<OutputMode>(OutputMode.Standard)
    val currentMode: StateFlow<OutputMode> = _currentMode.asStateFlow()

    private val _isTransitioning = MutableStateFlow(false)
    val isTransitioning: StateFlow<Boolean> = _isTransitioning.asStateFlow()

    // Error reporting to UI
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    // USB write error tracking for failsafe fallback
    private var consecutiveUsbWriteErrors = 0

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── Public API ─────────────────────────────────────────────────

    /**
     * Transition to a new output mode.
     * This is a suspend function that stops the current player, rebuilds it, and resumes playback.
     *
     * @param targetMode The desired output mode
     * @param currentPlayer The currently active ExoPlayer instance
     * @return The new ExoPlayer instance to use
     */
    suspend fun transitionTo(
        targetMode: OutputMode,
        currentPlayer: ExoPlayer
    ): ExoPlayer = withContext(Dispatchers.Main) {
        val fromMode = _currentMode.value
        if (fromMode == targetMode) {
            Log.d(TAG, "Already in $targetMode — no transition needed")
            return@withContext currentPlayer
        }

        Log.i(TAG, "═══ TRANSITION: $fromMode → $targetMode ═══")
        _isTransitioning.value = true
        _lastError.value = null

        try {
            // 1. Capture current playback state
            val mediaItems = mutableListOf<MediaItem>()
            for (i in 0 until currentPlayer.mediaItemCount) {
                mediaItems.add(currentPlayer.getMediaItemAt(i))
            }
            val currentIndex = currentPlayer.currentMediaItemIndex
            val currentPositionMs = currentPlayer.currentPosition
            val wasPlaying = currentPlayer.isPlaying || currentPlayer.playWhenReady
            val repeatMode = currentPlayer.repeatMode
            val shuffleMode = currentPlayer.shuffleModeEnabled
            val playbackParams = currentPlayer.playbackParameters

            Log.d(TAG, "Captured: ${mediaItems.size} items, index=$currentIndex, " +
                    "pos=${currentPositionMs}ms, playing=$wasPlaying")

            // 2. Stop current player (NOT pause — full stop)
            currentPlayer.stop()
            currentPlayer.clearMediaItems()

            // 3. Release mode-specific resources
            releaseCurrentModeResources(fromMode)

            // 4. Acquire new mode resources
            val acquired = acquireTargetModeResources(targetMode)
            if (!acquired) {
                Log.e(TAG, "Failed to acquire resources for $targetMode — falling back to STANDARD")
                _lastError.value = "Failed to switch to $targetMode — using standard output"
                _currentMode.value = OutputMode.Standard
                // Rebuild with standard mode below
            } else {
                _currentMode.value = targetMode
            }

            // 5. Release old player
            currentPlayer.release()

            // 6. Build new player with the correct RenderersFactory
            val newPlayer = buildPlayerForMode(_currentMode.value)

            // 7. Restore playback state
            newPlayer.repeatMode = repeatMode
            newPlayer.shuffleModeEnabled = shuffleMode
            newPlayer.playbackParameters = playbackParams

            if (mediaItems.isNotEmpty()) {
                newPlayer.setMediaItems(mediaItems, currentIndex, currentPositionMs)
                newPlayer.prepare()
                if (wasPlaying) {
                    newPlayer.playWhenReady = true
                }
            }

            Log.i(TAG, "═══ TRANSITION COMPLETE: now in ${_currentMode.value} ═══")
            
            // Notify the engine
            onPlayerRebuilt(newPlayer)

            return@withContext newPlayer
        } catch (e: Exception) {
            Log.e(TAG, "TRANSITION FAILED: ${e.message}", e)
            _lastError.value = "Audio mode switch failed: ${e.message}"
            throw e
        } finally {
            _isTransitioning.value = false
        }
    }

    /**
     * Emergency fallback to STANDARD mode.
     * Called when USB errors exceed threshold or device is unplugged.
     */
    fun emergencyFallbackToStandard(currentPlayer: ExoPlayer, reason: String) {
        Log.w(TAG, "⚠ EMERGENCY FALLBACK: $reason")
        scope.launch {
            try {
                transitionTo(OutputMode.Standard, currentPlayer)
            } catch (e: Exception) {
                Log.e(TAG, "Emergency fallback failed!", e)
            }
        }
        _lastError.value = reason
    }

    /**
     * Report a USB write error. After MAX_USB_WRITE_ERRORS consecutive errors,
     * triggers automatic fallback to STANDARD.
     */
    fun reportUsbWriteError(currentPlayer: ExoPlayer) {
        consecutiveUsbWriteErrors++
        if (consecutiveUsbWriteErrors >= MAX_USB_WRITE_ERRORS) {
            emergencyFallbackToStandard(
                currentPlayer,
                "USB DAC error — switched to standard output"
            )
            consecutiveUsbWriteErrors = 0
        }
    }

    fun clearUsbWriteErrors() {
        consecutiveUsbWriteErrors = 0
    }

    // ── USB Event Handlers (called from BroadcastReceiver) ─────────

    /**
     * Called when a USB audio device is attached and permission is granted.
     * Triggers transition to BIT_PERFECT or USB_EXCLUSIVE based on user setting.
     */
    fun onUsbDeviceReady(device: UsbDevice, useExclusiveMode: Boolean, currentPlayer: ExoPlayer) {
        Log.i(TAG, "USB device ready: ${device.productName}, exclusive=$useExclusiveMode")
        scope.launch {
            val targetMode = if (useExclusiveMode) {
                OutputMode.UsbExclusive(device)
            } else {
                OutputMode.BitPerfect
            }
            try {
                transitionTo(targetMode, currentPlayer)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to transition on USB attach", e)
            }
        }
    }

    /**
     * Called when USB device is detached.
     * Immediately falls back to STANDARD without user action.
     */
    fun onUsbDeviceDetached(currentPlayer: ExoPlayer) {
        Log.i(TAG, "USB device detached — immediate fallback to STANDARD")
        emergencyFallbackToStandard(currentPlayer, "USB DAC disconnected — using speakers")
    }

    // ── Private Helpers ────────────────────────────────────────────

    private fun releaseCurrentModeResources(mode: OutputMode) {
        when (mode) {
            is OutputMode.UsbExclusive -> {
                Log.d(TAG, "Releasing USB exclusive resources...")
                try {
                    usbDirectEngine?.stopDirectMode()
                    nativeEngine?.stop()
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing USB resources", e)
                } finally {
                    usbAudioManager.deactivateExclusiveMode()
                }
            }
            is OutputMode.BitPerfect -> {
                Log.d(TAG, "Releasing bit-perfect resources...")
                // No special cleanup needed — AudioTrack is released by ExoPlayer
            }
            is OutputMode.Standard -> {
                Log.d(TAG, "Releasing standard resources...")
                // Nothing to do
            }
        }
    }

    private fun acquireTargetModeResources(mode: OutputMode): Boolean {
        return when (mode) {
            is OutputMode.UsbExclusive -> {
                if (nativeEngine == null || usbDirectEngine == null) {
                    Log.e(TAG, "Native engine or USB direct engine not available")
                    return false
                }
                val activated = usbAudioManager.activateExclusiveMode()
                if (!activated) {
                    Log.e(TAG, "Failed to activate USB exclusive mode")
                    return false
                }
                val started = usbDirectEngine.startDirectMode(mode.device)
                if (!started) {
                    Log.e(TAG, "Failed to start direct USB mode")
                    usbAudioManager.deactivateExclusiveMode()
                    return false
                }
                true
            }
            is OutputMode.BitPerfect -> true  // Will be configured via BitPerfectRenderersFactory
            is OutputMode.Standard -> true
        }
    }

    private fun buildPlayerForMode(mode: OutputMode): ExoPlayer {
        val renderersFactory = when (mode) {
            is OutputMode.UsbExclusive -> {
                Log.d(TAG, "Building player with NativeAudioSink for USB Exclusive")
                val nativeSink = NativeAudioSink(nativeEngine!!)
                BitPerfectRenderersFactory(context, customAudioSinkOverride = nativeSink)
            }
            is OutputMode.BitPerfect -> {
                Log.d(TAG, "Building player with BitPerfectAudioSink")
                BitPerfectRenderersFactory(
                    context,
                    enableBitPerfect = true,
                    usbDeviceProvider = { findCurrentUsbAudioDevice() },
                    isExclusiveModeProvider = { false }
                )
            }
            is OutputMode.Standard -> {
                Log.d(TAG, "Building player with DefaultRenderersFactory")
                androidx.media3.exoplayer.DefaultRenderersFactory(context).apply {
                    setExtensionRendererMode(
                        androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                    )
                }
            }
        }

        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 30_000, 1_500, 2_500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        return ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .build().apply {
                setAudioAttributes(audioAttributes, false)
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_LOCAL)
                setSkipSilenceEnabled(false)
                playWhenReady = false
            }
    }

    private fun findCurrentUsbAudioDevice(): AudioDeviceInfo? {
        return usbAudioManager.connectedUsbDevice.value?.device
    }

    fun release() {
        scope.cancel()
    }
}
