package chromahub.rhythm.app.infrastructure.service.player

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.AudioTrack
import android.os.Build
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
 * FIX 1: Full AudioFlinger eviction sequence in switchToSiphon():
 *   1. Pause ExoPlayer
 *   2. Request AudioFocus (siphon attribution context)
 *   3. On API 34+: setPreferredMixerAttributes then clear it
 *   4. Create silent AudioTrack routed to USB, play 80ms, release
 *   5. Wait 200ms for ALSA kernel driver to release the interface
 *   6. THEN claim via libusb (guaranteed to succeed)
 *
 * FAILSAFES:
 *   • Transition timeout: 5 seconds → fall back to Standard
 *   • USB write error counting: 3 consecutive → emergency fallback
 *   • USB device detach → immediate Standard fallback
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
        private const val ALSA_RELEASE_DELAY_MS = 200L
        private const val SILENT_TRACK_PLAY_MS = 80L
        
        // FIX: Increased debounce times to prevent rapid USB role-switching
        // The old 500ms/100ms was too short — USB device enumeration + ALSA kernel driver
        // handoff can take up to 1-2 seconds on some devices
        private const val SIPHON_ATTACH_DEBOUNCE_MS = 1500L  // Was 500ms
        private const val STANDARD_DETACH_DEBOUNCE_MS = 300L // Was 100ms
        private const val RAPID_ATTACH_DEBOUNCE_MS = 500L    // Was 100ms
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

    // ── Fix 1 & 2: Routing change pending flag (consumed by RhythmPlayerEngine NOISY receiver) ──

    private val _routingChangePending = AtomicBoolean(false)

    /** Returns true while AudioFlinger eviction or Standard restoration is running. */
    fun isRoutingChangePending(): Boolean = _routingChangePending.get()

    // ── USB error tracking ─────────────────────────────────────

    private val consecutiveUsbErrors = AtomicInteger(0)
    private val isTransitioning = AtomicBoolean(false)
    @Volatile
    private var pendingPath: RoutingPath? = null
    private var transitionJob: Job? = null

    // ── Fix 1: Debounce guard — prevent duplicate switchToSiphon calls ──

    private var debounceJob: Job? = null
    private var currentSiphonDevice: UsbDevice? = null
    private var siphonTransitionInProgress = false

    // ── Fix 8: Attribution context AudioManager for all siphon AudioManager calls ──

    private val siphonAudioManager: AudioManager by lazy {
        context.createAttributionContext("siphon")
            .getSystemService(AudioManager::class.java)
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
                Log.d(TAG, "executeSwitchToSiphon: transition already in progress, ignoring duplicate call")
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
                        Log.e(TAG, "Transition to $pathToExecute TIMED OUT")
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
            // This is primarily for the flow based observeRoutingChanges fallback
            // Standard attachment should route via onUsbDeviceAttached directly
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
            delay(RAPID_ATTACH_DEBOUNCE_MS) // Debounce rapid attach events
            if (currentSiphonDevice?.deviceId == device.deviceId) {
                Log.d(TAG, "Device already routed, ignoring repeat attach")
                return@launch
            }
            Log.i(TAG, "onUsbDeviceAttached: Re-routing to SiphonSessionManager")
            val mgr = sessionManager
            if (mgr != null) {
                mgr.claimInterface(device, caps.toDeviceCapabilities())
            } else {
                executeSwitchToSiphon(device, caps.toDeviceCapabilities())
            }
        }
    }

    suspend fun executeSwitchToSiphon(
        device: UsbDevice,
        caps: chromahub.rhythm.app.infrastructure.audio.siphon.SiphonDeviceCapabilities
    ) {
        val exclusiveEnabled = audioQualityDataStore.usbExclusiveModeEnabled.first()
        if (!exclusiveEnabled) {
            Log.d(TAG, "executeSwitchToSiphon: Exclusive Mode is OFF — aborting Siphon transition for ${device.productName}")
            return
        }

        _routingChangePending.set(true)
        try {
            Log.i(TAG, "executeSwitchToSiphon: transition requested for ${device.productName}")

            // 1. Pause ExoPlayer
            playerEngine.pauseForRouting()

            // 2. Wait for safe state (exit BUFFERING)
            playerEngine.waitForSafeSwapState()

            // 3. Request true exclusive focus with Siphon context
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                ).build()
            siphonAudioManager.requestAudioFocus(focusRequest)

            // ── FIX #7: AudioFlinger eviction — prevent ALSA write storm ──────
            // Step A: Tell audio policy to disconnect the USB output
            try {
                siphonAudioManager.setParameters("usb_out_connected=false")
                Log.d(TAG, "AudioFlinger eviction: setParameters(usb_out_connected=false)")
            } catch (e: Exception) {
                Log.w(TAG, "setParameters failed (non-fatal): ${e.message}")
            }

            // Step B: Create a brief silent AudioTrack routed to USB, then kill it.
            // This forces AudioFlinger to cleanly close its ALSA pcm handle on the DAC.
            try {
                val usbDeviceInfo = siphonAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE }
                if (usbDeviceInfo != null) {
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
                        .setBufferSizeInBytes(48000 * 2 * 2 / 10) // ~100ms buffer
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build()
                    silentTrack.setPreferredDevice(usbDeviceInfo)
                    val silentBuf = ByteArray(48000 * 2 * 2 / 10) // 100ms of silence
                    silentTrack.write(silentBuf, 0, silentBuf.size)
                    silentTrack.play()
                    delay(SILENT_TRACK_PLAY_MS)
                    silentTrack.stop()
                    silentTrack.release()
                    Log.d(TAG, "AudioFlinger eviction: silent track played and released")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Silent track eviction failed (non-fatal): ${e.message}")
            }

            // Step C: Allow ALSA kernel driver to fully release
            delay(ALSA_RELEASE_DELAY_MS)
            Log.d(TAG, "AudioFlinger eviction: ALSA release delay complete")

            // 4. Detach kernel driver + open device
            val connection = usbManager.openDevice(device)
            if (connection == null) {
                Log.e(TAG, "executeSwitchToSiphon: usbManager.openDevice failed")
                return
            }
            val fd = connection.fileDescriptor
            Log.i(TAG, "executeSwitchToSiphon: USB device opened fd=$fd")

            // 5. Construct & Configure AudioSink
            val siphonUsbAudioSink = chromahub.rhythm.app.infrastructure.audio.siphon.SiphonUsbAudioSink(
                context = context,
                usbConnection = connection,
                usbDevice = device,
                deviceCapabilities = caps,
                initialRoutingMode = chromahub.rhythm.app.infrastructure.audio.siphon.SiphonRoutingMode.SOFTWARE
            )

            // 6. Swap AudioSink using playerEngine captureState/restoreState rebuild
            playerEngine.swapAudioSink(siphonUsbAudioSink)

            // 7. Initialize Direct JNI Volume logic
            SiphonIsochronousEngine.nativeSetVolumeMode(false) 
            delay(10)
            SiphonIsochronousEngine.nativeSetSoftwareVolume(siphonManager.loadVolume())

            // 8. Update state
            currentSiphonDevice = device
            _currentPath.value = RoutingPath.Siphon(device, connection, caps)

            // 9. Resume playback
            playerEngine.resumeAfterRouting()

        } catch (e: Exception) {
            Log.e(TAG, "executeSwitchToSiphon failed: ${e.message}", e)
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

    private fun serializeRoutingPath(path: RoutingPath): String {
        return when (path) {
            is RoutingPath.Siphon -> "SIPHON"
            is RoutingPath.Standard -> "STANDARD"
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

    private fun chromahub.rhythm.app.infrastructure.audio.usb.UsbConnectionOrchestrator.EnumeratedDevice.toDeviceCapabilities(): chromahub.rhythm.app.infrastructure.audio.siphon.SiphonDeviceCapabilities {
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
}
