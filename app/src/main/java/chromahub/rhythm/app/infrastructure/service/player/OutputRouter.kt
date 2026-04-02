package chromahub.rhythm.app.infrastructure.service.player

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import chromahub.rhythm.app.infrastructure.audio.siphon.SiphonIsochronousEngine
import chromahub.rhythm.app.infrastructure.audio.siphon.SiphonManager
import chromahub.rhythm.app.infrastructure.audio.siphon.SiphonState
import chromahub.rhythm.app.infrastructure.audio.usb.UsbAudioManager
import chromahub.rhythm.app.shared.data.model.AudioQualityDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * OutputRouter — Audio Routing State Machine
 *
 * Two modes only:
 *   Standard — DefaultAudioSink → AudioTrack → AudioFlinger (speaker / BT / wired)
 *   Siphon   — SiphonUsbAudioSink → JNI → libusb → USB isochronous → DAC (bypasses AudioFlinger)
 *
 * COMPLETE AudioFlinger eviction sequence in switchToSiphon():
 *   1. Pause ExoPlayer and confirm stopped
 *   2. Request AudioFocus (exclusive, siphon attribution context)
 *   3. Tell audio policy to disconnect USB output (setParameters)
 *   4. Create silent AudioTrack routed to USB, play 100ms, release
 *   5. Wait 500ms for ALSA kernel driver to fully release the interface
 *   6. Open USB device and claim via libusb (with retry)
 *   7. Create SiphonUsbAudioSink and swap into ExoPlayer
 *
 * FAILSAFES:
 *   • Transition timeout: 5 seconds → fall back to Standard
 *   • USB write error counting: 3 consecutive → emergency fallback
 *   • USB device detach → immediate Standard fallback
 *   • openDevice() failure → retry once after 500ms
 */
class OutputRouter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val usbAudioManager: UsbAudioManager,
    private val siphonManager: SiphonManager,
    private val audioQualityDataStore: AudioQualityDataStore
) {
    companion object {
        private const val TAG = "OutputRouter"
        private const val TRANSITION_TIMEOUT_MS = 5000L
        private const val MAX_USB_WRITE_ERRORS = 3
        private const val ALSA_RELEASE_DELAY_MS = 500L
        private const val SILENT_TRACK_PLAY_MS = 100L
        private const val OPEN_DEVICE_RETRY_DELAY_MS = 500L
        
        // Debounce times — USB enumeration + ALSA kernel driver handoff can take 1-2 seconds
        private const val SIPHON_ATTACH_DEBOUNCE_MS = 1500L
        private const val STANDARD_DETACH_DEBOUNCE_MS = 300L
        private const val RAPID_ATTACH_DEBOUNCE_MS = 500L
    }

    // ── Routing paths ──────────────────────────────────────────

    sealed class RoutingPath {
        data object Standard : RoutingPath() {
            override fun toString() = "Standard"
        }
        data class Siphon(
            val device: UsbDevice,
            val connection: android.hardware.usb.UsbDeviceConnection,
            val capabilities: chromahub.rhythm.app.infrastructure.audio.siphon.SiphonDeviceCapabilities
        ) : RoutingPath() {
            override fun toString() = "Siphon(${device.productName})"
        }
    }

    // ── Transition state (observable by UI) ─────────────────────

    enum class TransitionState {
        IDLE,
        SAVING,
        STOPPING,
        RELEASING,
        REBUILDING,
        RESTORING,
        FAILED
    }

    // ── Public state flows ─────────────────────────────────────

    private val _currentPath = MutableStateFlow<RoutingPath>(RoutingPath.Standard)
    val currentPath: StateFlow<RoutingPath> = _currentPath.asStateFlow()

    private val _transitionState = MutableStateFlow(TransitionState.IDLE)
    val transitionState: StateFlow<TransitionState> = _transitionState.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    // ── Routing change pending flag (consumed by NOISY receiver) ──

    private val _routingChangePending = AtomicBoolean(false)

    /** Returns true while AudioFlinger eviction or Standard restoration is running. */
    fun isRoutingChangePending(): Boolean = _routingChangePending.get()

    // ── USB error tracking ─────────────────────────────────────

    private val consecutiveUsbErrors = AtomicInteger(0)
    private val isTransitioning = AtomicBoolean(false)
    @Volatile
    private var pendingPath: RoutingPath? = null
    private var transitionJob: Job? = null

    // ── Debounce guard ──

    private var debounceJob: Job? = null
    private var currentSiphonDevice: UsbDevice? = null
    private var siphonTransitionInProgress = false

    // ── Attribution context AudioManager for siphon operations ──

    private val siphonAudioManager: AudioManager by lazy {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            context.createAttributionContext("siphon")
                .getSystemService(AudioManager::class.java)
        } else {
            context.getSystemService(AudioManager::class.java)
        }
    }

    private val usbManager: UsbManager by lazy {
        context.getSystemService(Context.USB_SERVICE) as UsbManager
    }

    lateinit var playerEngine: RhythmPlayerEngine

    /** Set by MediaPlaybackService — used to check routing transition state. */
    var sessionManager: chromahub.rhythm.app.infrastructure.audio.siphon.SiphonSessionManager? = null

    init {
        SiphonIsochronousEngine.deviceLostListener = {
            Log.w(TAG, "USB DAC lost mid-playback — switching to Standard")
            switchToStandard()
        }
        observeRoutingChanges()
    }

    // ── Observe SiphonState to determine routing ───────────────

    private fun observeRoutingChanges() {
        scope.launch {
            siphonManager.state.collect { siphonState ->
                val desiredPath = when (siphonState) {
                    is SiphonState.Connected -> RoutingPath.Siphon(
                        siphonState.device,
                        siphonState.connection,
                        siphonState.capabilities
                    )
                    else -> RoutingPath.Standard
                }

                if (_currentPath.value != desiredPath) {
                    val debounceMs = if (desiredPath is RoutingPath.Siphon) SIPHON_ATTACH_DEBOUNCE_MS else STANDARD_DETACH_DEBOUNCE_MS
                    Log.i(TAG, "Routing change: ${_currentPath.value} → $desiredPath (debounce ${debounceMs}ms)")
                    delay(debounceMs)
                    requestTransition(desiredPath)
                }
            }
        }
    }

    // ── Transition coordination ────────────────────────────────

    private fun requestTransition(targetPath: RoutingPath) {
        if (targetPath is RoutingPath.Siphon) {
            if (siphonTransitionInProgress) {
                Log.d(TAG, "executeSwitchToSiphon: transition already in progress, ignoring duplicate")
                return
            }
        }
        pendingPath = targetPath
        if (!isTransitioning.compareAndSet(false, true)) {
            Log.d(TAG, "Transition already running, pending path updated to $targetPath")
            return
        }
        transitionJob = scope.launch {
            try {
                if (targetPath is RoutingPath.Siphon) siphonTransitionInProgress = true
                while (true) {
                    val pathToExecute = pendingPath ?: break
                    pendingPath = null

                    val result = withTimeoutOrNull(TRANSITION_TIMEOUT_MS) {
                        executeTransition(pathToExecute)
                    }

                    if (result == null) {
                        Log.e(TAG, "Transition to $pathToExecute TIMED OUT after ${TRANSITION_TIMEOUT_MS}ms")
                        _lastError.value = "Transition timed out, falling back to Standard"
                        emergencyFallback()
                    }

                    if (pendingPath == null) break
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transition FAILED", e)
                _lastError.value = "Transition failed: ${e.message}"
                emergencyFallback()
            } finally {
                isTransitioning.set(false)
                siphonTransitionInProgress = false
            }
        }
    }

    private suspend fun executeTransition(targetPath: RoutingPath) {
        _transitionState.value = TransitionState.SAVING
        _transitionState.value = TransitionState.STOPPING
        _transitionState.value = TransitionState.RELEASING

        if (targetPath is RoutingPath.Siphon) {
            consecutiveUsbErrors.set(0)
            _transitionState.value = TransitionState.REBUILDING
            Log.d(TAG, "Executing Siphon transition with AudioFlinger eviction")
            executeSwitchToSiphon(targetPath.device, targetPath.capabilities)
        } else {
            _transitionState.value = TransitionState.REBUILDING
            Log.d(TAG, "Executing Standard transition")
            switchToStandard()
        }

        _currentPath.value = targetPath
        _transitionState.value = TransitionState.RESTORING

        delay(100)

        _transitionState.value = TransitionState.IDLE
        _lastError.value = null
        Log.i(TAG, "Transition to $targetPath COMPLETE")
    }

    fun onUsbDeviceAttached(device: UsbDevice, caps: chromahub.rhythm.app.infrastructure.audio.usb.UsbConnectionOrchestrator.EnumeratedDevice) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(RAPID_ATTACH_DEBOUNCE_MS)
            if (currentSiphonDevice?.deviceId == device.deviceId) {
                Log.d(TAG, "Device already routed, ignoring repeat attach")
                return@launch
            }
            Log.i(TAG, "onUsbDeviceAttached: routing to SiphonSessionManager for ${device.productName}")
            val mgr = sessionManager
            if (mgr != null) {
                mgr.claimInterface(device, caps.toDeviceCapabilities())
            } else {
                Log.w(TAG, "No SessionManager — calling executeSwitchToSiphon directly")
                executeSwitchToSiphon(device, caps.toDeviceCapabilities())
            }
        }
    }

    /**
     * Execute the full Siphon switch sequence:
     *   1. Pause ExoPlayer (confirmed stopped)
     *   2. Request exclusive audio focus
     *   3. Evict AudioFlinger from USB ALSA (setParameters + silent track)
     *   4. Wait for ALSA kernel driver to release (500ms)
     *   5. Open USB device (with retry)
     *   6. Create SiphonUsbAudioSink
     *   7. Swap AudioSink in ExoPlayer
     *   8. Set volume mode
     *   9. Resume playback
     */
    suspend fun executeSwitchToSiphon(
        device: UsbDevice,
        caps: chromahub.rhythm.app.infrastructure.audio.siphon.SiphonDeviceCapabilities
    ) {
        val startTime = System.currentTimeMillis()
        val exclusiveEnabled = audioQualityDataStore.usbExclusiveModeEnabled.first()
        if (!exclusiveEnabled) {
            Log.d(TAG, "executeSwitchToSiphon: Exclusive Mode OFF — aborting")
            return
        }

        _routingChangePending.set(true)
        try {
            Log.i(TAG, "═══ executeSwitchToSiphon: BEGIN for ${device.productName} ═══")

            // 1. Pause ExoPlayer and confirm stopped
            playerEngine.pauseForRouting()
            Log.d(TAG, "Step 1: ExoPlayer paused (${System.currentTimeMillis() - startTime}ms)")

            // 2. Wait for safe state (exit BUFFERING)
            playerEngine.waitForSafeSwapState()
            Log.d(TAG, "Step 2: Safe swap state confirmed (${System.currentTimeMillis() - startTime}ms)")

            // Step 2b: Listen for AudioEffect disconnects (e.g. ViPER4Android)
            // If an audio effect attached to our session, it must release it before we can claim USB.
            val v4aClosedDeferred = kotlinx.coroutines.CompletableDeferred<Unit>()
            val vipCloseReceiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: android.content.Intent) {
                    if (intent.action == android.media.audiofx.AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION) {
                        Log.d(TAG, "AudioEffect session closed — safe to proceed")
                        v4aClosedDeferred.complete(Unit)
                    }
                }
            }
            try {
                androidx.core.content.ContextCompat.registerReceiver(
                    context,
                    vipCloseReceiver,
                    android.content.IntentFilter(android.media.audiofx.AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION),
                    androidx.core.content.ContextCompat.RECEIVER_EXPORTED
                )
                // Safety timeout — if V4A doesn't respond in 500ms, ignore
                kotlinx.coroutines.withTimeoutOrNull(500L) {
                    v4aClosedDeferred.await()
                }
            } catch (e: Exception) {
            } finally {
                try { context.unregisterReceiver(vipCloseReceiver) } catch (e: Exception) {}
            }
            Log.d(TAG, "Step 2b: Wait for AudioEffect close done (${System.currentTimeMillis() - startTime}ms)")

            // 3. Request exclusive audio focus with Siphon context
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                ).build()
            siphonAudioManager.requestAudioFocus(focusRequest)
            Log.d(TAG, "Step 3: Exclusive audio focus requested (${System.currentTimeMillis() - startTime}ms)")

            // 4. Evict AudioFlinger — tell audio policy to disconnect USB
            try {
                siphonAudioManager.setParameters("usb_out_connected=false")
                Log.d(TAG, "Step 4a: setParameters(usb_out_connected=false) done")
            } catch (e: Exception) {
                Log.w(TAG, "Step 4a: setParameters failed (non-fatal): ${e.message}")
            }

            // 5. Silent AudioTrack to force AudioFlinger to flush ALSA pcm handle
            try {
                val usbDeviceInfo = siphonAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
                if (usbDeviceInfo != null) {
                    val bufferSize = 48000 * 2 * 2 / 10 // ~100ms at 48kHz stereo 16-bit
                    val silentTrack = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setSampleRate(48000)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                .build()
                        )
                        .setBufferSizeInBytes(bufferSize)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build()
                    silentTrack.setPreferredDevice(usbDeviceInfo)
                    val silentBuf = ByteArray(bufferSize)
                    silentTrack.write(silentBuf, 0, silentBuf.size)
                    silentTrack.play()
                    delay(SILENT_TRACK_PLAY_MS)
                    silentTrack.stop()
                    silentTrack.release()
                    Log.d(TAG, "Step 5: Silent track played and released (${System.currentTimeMillis() - startTime}ms)")
                } else {
                    Log.d(TAG, "Step 5: No USB output device found — skipping silent track")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Step 5: Silent track failed (non-fatal): ${e.message}")
            }

            // 6. Wait for ALSA kernel driver to fully release
            delay(ALSA_RELEASE_DELAY_MS)
            Log.d(TAG, "Step 6: ALSA release delay done (${System.currentTimeMillis() - startTime}ms)")

            // 7. Open USB device (with retry)
            var connection = usbManager.openDevice(device)
            if (connection == null) {
                Log.w(TAG, "Step 7: openDevice failed — retrying after ${OPEN_DEVICE_RETRY_DELAY_MS}ms")
                delay(OPEN_DEVICE_RETRY_DELAY_MS)
                connection = usbManager.openDevice(device)
                if (connection == null) {
                    Log.e(TAG, "Step 7: openDevice FAILED on retry — aborting Siphon switch")
                    return
                }
            }
            val fd = connection.fileDescriptor
            Log.i(TAG, "Step 7: USB device opened fd=$fd (${System.currentTimeMillis() - startTime}ms)")

            // 8. Create SiphonUsbAudioSink
            val siphonUsbAudioSink = chromahub.rhythm.app.infrastructure.audio.siphon.SiphonUsbAudioSink(
                context = context,
                usbConnection = connection,
                usbDevice = device,
                deviceCapabilities = caps,
                initialRoutingMode = chromahub.rhythm.app.infrastructure.audio.siphon.SiphonRoutingMode.SOFTWARE
            )
            Log.d(TAG, "Step 8: SiphonUsbAudioSink created (${System.currentTimeMillis() - startTime}ms)")

            // 9. Swap AudioSink in ExoPlayer (captureState → rebuild player → restoreState)
            playerEngine.swapAudioSink(siphonUsbAudioSink)
            Log.d(TAG, "Step 9: AudioSink swapped in ExoPlayer (${System.currentTimeMillis() - startTime}ms)")

            // 10. Set volume mode and apply saved volume
            SiphonIsochronousEngine.nativeSetVolumeMode(false)
            delay(10)
            SiphonIsochronousEngine.nativeSetSoftwareVolume(siphonManager.loadVolume())
            Log.d(TAG, "Step 10: Volume mode set (${System.currentTimeMillis() - startTime}ms)")

            // 11. Update routing state
            currentSiphonDevice = device
            _currentPath.value = RoutingPath.Siphon(device, connection, caps)

            // 12. Resume playback
            playerEngine.resumeAfterRouting()

            val totalElapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "═══ executeSwitchToSiphon: COMPLETE in ${totalElapsed}ms for ${device.productName} ═══")

        } catch (e: Exception) {
            Log.e(TAG, "executeSwitchToSiphon FAILED: ${e.message}", e)
            emergencyFallback()
        } finally {
            _routingChangePending.set(false)
        }
    }

    /**
     * Restore Standard (AudioFlinger) path and release native resources.
     */
    fun switchToStandard() {
        scope.launch {
            _routingChangePending.set(true)
            try {
                Log.i(TAG, "switchToStandard: releasing Siphon native resources")
                try {
                    SiphonIsochronousEngine.nativeRelease()
                } catch (e: Exception) {
                    Log.w(TAG, "nativeRelease failed (may be already released): ${e.message}")
                }
                currentSiphonDevice = null
                _currentPath.value = RoutingPath.Standard
                Log.i(TAG, "Routing: Standard (AudioFlinger path restored)")
            } finally {
                _routingChangePending.set(false)
            }
        }
    }

    // ── Failsafe mechanisms ──────────────────────────────────

    private fun emergencyFallback() {
        Log.w(TAG, "EMERGENCY FALLBACK to Standard mode")
        _transitionState.value = TransitionState.FAILED
        _currentPath.value = RoutingPath.Standard
        _routingChangePending.set(false)

        scope.launch {
            delay(1000)
            _transitionState.value = TransitionState.IDLE
        }
    }

    fun reportUsbWriteError() {
        val errorCount = consecutiveUsbErrors.incrementAndGet()
        Log.w(TAG, "USB write error #$errorCount / $MAX_USB_WRITE_ERRORS")

        if (errorCount >= MAX_USB_WRITE_ERRORS) {
            Log.e(TAG, "Max USB write errors — triggering emergency fallback")
            _lastError.value = "USB audio stream failed ($errorCount errors), falling back to Standard"
            emergencyFallback()
        }
    }

    fun resetUsbWriteErrors() {
        if (consecutiveUsbErrors.getAndSet(0) > 0) {
            Log.d(TAG, "USB write error counter reset")
        }
    }

    fun onUsbDeviceDetached() {
        Log.w(TAG, "USB device detached — immediate Standard fallback")
        _lastError.value = "USB DAC disconnected"
        consecutiveUsbErrors.set(0)
        currentSiphonDevice = null
        transitionJob?.cancel()
        _currentPath.value = RoutingPath.Standard
        _transitionState.value = TransitionState.IDLE
        _routingChangePending.set(false)
    }

    fun release() {
        transitionJob?.cancel()
        transitionJob = null
        debounceJob?.cancel()
        _routingChangePending.set(false)
        Log.d(TAG, "OutputRouter released")
    }
}

internal fun chromahub.rhythm.app.infrastructure.audio.usb.UsbConnectionOrchestrator.EnumeratedDevice.toDeviceCapabilities(): chromahub.rhythm.app.infrastructure.audio.siphon.SiphonDeviceCapabilities {
    val formats = mutableListOf<chromahub.rhythm.app.infrastructure.audio.siphon.SiphonAudioFormat>()
    for (rate in nativeSampleRates) {
        for (depth in nativeBitDepths) {
            formats.add(
                chromahub.rhythm.app.infrastructure.audio.siphon.SiphonAudioFormat(
                    sampleRate = rate,
                    bitDepth = depth,
                    channelCount = maxChannels,
                    encoding = if (depth == 32) androidx.media3.common.C.ENCODING_PCM_32BIT else if (depth == 24) androidx.media3.common.C.ENCODING_PCM_24BIT else androidx.media3.common.C.ENCODING_PCM_16BIT
                )
            )
        }
    }
    return chromahub.rhythm.app.infrastructure.audio.siphon.SiphonDeviceCapabilities(
        supportsHardwareVolume = supportsHardwareVolume,
        featureUnitId = featureUnitId,
        volumeMinDb256 = dacVolMin,
        volumeMaxDb256 = dacVolMax,
        volumeSteps = 100,
        endpointAddress = isochronousEndpointAddr,
        maxPacketSize = wMaxPacketSize,
        supportedFormats = formats
    )
}
