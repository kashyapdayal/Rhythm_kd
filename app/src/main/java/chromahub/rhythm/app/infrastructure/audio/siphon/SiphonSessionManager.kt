package chromahub.rhythm.app.infrastructure.audio.siphon

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import chromahub.rhythm.app.infrastructure.audio.usb.UsbAudioManager
import chromahub.rhythm.app.infrastructure.service.player.OutputRouter
import chromahub.rhythm.app.shared.data.model.AudioQualityDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SiphonSessionManager — Centralized USB DAC Lifecycle Gatekeeper
 *
 * Enforces the correct sequence for USB DAC acquisition:
 *   1. Check Exclusive Mode toggle in Settings
 *   2. Detect if USB DAC is connected
 *   3. Request USB permission (only after 1+2 pass)
 *   4. Evict AudioFlinger (setParameters + silent track)
 *   5. Claim USB ALSA interface via libusb
 *
 * This replaces the fragmented logic previously spread across UsbAudioReceiver,
 * SiphonManager.onUsbDeviceAttached(), and OutputRouter.
 *
 * Works like HiBy Music: on app launch, checks settings → checks DAC →
 * requests permission → claims hardware. No premature permission dialogs.
 */
class SiphonSessionManager(
    private val context: Context,
    private val audioQualityDataStore: AudioQualityDataStore,
    private val usbAudioManager: UsbAudioManager,
    private val siphonManager: SiphonManager
) {
    companion object {
        private const val TAG = "SiphonSessionManager"
    }

    // ── Session States ──────────────────────────────────────────

    enum class SessionState {
        /** No USB DAC activity. Normal audio path. */
        IDLE,
        /** Exclusive mode is ON but no DAC detected yet. Waiting for USB_DEVICE_ATTACHED. */
        WAITING_FOR_DEVICE,
        /** DAC detected, exclusive ON, requesting USB permission from user. */
        WAITING_FOR_PERMISSION,
        /** Permission granted. Running AudioFlinger eviction sequence. */
        EVICTING_AUDIOFLINGER,
        /** AudioFlinger evicted. Claiming USB interface via Siphon/libusb. */
        CLAIMING_INTERFACE,
        /** Full Siphon pipeline active. Bit-perfect USB audio. */
        ACTIVE,
        /** Releasing Siphon resources, returning to AudioFlinger. */
        RELEASING,
        /** Permission was denied by user. DAC is parked. */
        PERMISSION_DENIED
    }

    // ── Public State Flows ──────────────────────────────────────

    private val _sessionState = MutableStateFlow(SessionState.IDLE)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val _isRoutingTransitionInProgress = AtomicBoolean(false)

    /** True during AudioFlinger eviction or interface claiming. Used by RhythmPlayerEngine
     *  to suppress "No audio track selected" warnings. */
    fun isRoutingTransitionInProgress(): Boolean = _isRoutingTransitionInProgress.get()

    // ── Internal State ──────────────────────────────────────────

    /** Device that arrived before the app was ready or before exclusive mode was enabled. */
    @Volatile
    private var parkedDevice: UsbDevice? = null

    /** Device currently being managed by this session. */
    @Volatile
    private var activeDevice: UsbDevice? = null

    /** Scope for coroutine work — set by the service. */
    lateinit var scope: CoroutineScope

    /** OutputRouter — set by MediaPlaybackService after creation. */
    var outputRouter: OutputRouter? = null

    private var sessionJob: Job? = null

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // ── Public API ──────────────────────────────────────────────

    /**
     * Called by MediaPlaybackService after full player initialization.
     * This is the "app is ready" signal. If exclusive mode is ON and a DAC
     * is already connected (or was parked), we begin the claim sequence.
     */
    fun onAppReady() {
        Log.i(TAG, "onAppReady — checking for USB DAC to claim")
        sessionJob = scope.launch {
            val exclusiveEnabled = audioQualityDataStore.usbExclusiveModeEnabled.first()
            if (!exclusiveEnabled) {
                Log.d(TAG, "Exclusive mode is OFF — staying on AudioFlinger")
                _sessionState.value = SessionState.IDLE
                return@launch
            }

            // Check for parked device first (arrived before app was ready)
            val device = parkedDevice ?: findConnectedUsbDac()
            if (device != null) {
                parkedDevice = null
                Log.i(TAG, "Found USB DAC: ${device.productName} — beginning claim sequence")
                beginClaimSequence(device)
            } else {
                Log.d(TAG, "Exclusive mode ON but no DAC connected — waiting for attachment")
                _sessionState.value = SessionState.WAITING_FOR_DEVICE
            }
        }
    }

    /**
     * Called when a USB audio device is physically attached.
     * Routes through the session manager gates instead of directly requesting permission.
     */
    fun onDeviceArrived(device: UsbDevice) {
        Log.i(TAG, "onDeviceArrived: ${device.productName}")
        
        // If the session is already active with this device, ignore duplicate
        if (activeDevice?.deviceId == device.deviceId && 
            _sessionState.value == SessionState.ACTIVE) {
            Log.d(TAG, "Device already active, ignoring duplicate attach")
            return
        }

        scope.launch {
            val exclusiveEnabled = audioQualityDataStore.usbExclusiveModeEnabled.first()
            if (!exclusiveEnabled) {
                Log.d(TAG, "USB DAC attached but Exclusive Mode OFF — parking device")
                parkedDevice = device
                _sessionState.value = SessionState.IDLE
                // Still notify UsbAudioManager for standard path (DAC info display etc)
                usbAudioManager.onUsbDeviceAttached(device)
                return@launch
            }

            beginClaimSequence(device)
        }
    }

    /**
     * Called when a USB device is detached.
     */
    fun onDeviceRemoved(device: UsbDevice) {
        Log.i(TAG, "onDeviceRemoved: ${device.productName}")
        sessionJob?.cancel()
        
        if (activeDevice?.deviceId == device.deviceId || 
            _sessionState.value == SessionState.ACTIVE) {
            releaseSession()
        }
        
        parkedDevice = null
        activeDevice = null
        _sessionState.value = SessionState.IDLE
        _isRoutingTransitionInProgress.set(false)
    }

    /**
     * Called when the user toggles Exclusive Mode in settings.
     * If toggled ON and a DAC is connected, begins claim. If toggled OFF, releases.
     */
    fun onExclusiveModeChanged(enabled: Boolean) {
        Log.i(TAG, "onExclusiveModeChanged: $enabled")
        scope.launch {
            if (enabled) {
                // Check for parked/connected device
                val device = parkedDevice ?: findConnectedUsbDac()
                if (device != null) {
                    parkedDevice = null
                    beginClaimSequence(device)
                } else {
                    _sessionState.value = SessionState.WAITING_FOR_DEVICE
                }
            } else {
                // Release Siphon if active
                if (_sessionState.value == SessionState.ACTIVE) {
                    releaseSession()
                }
                _sessionState.value = SessionState.IDLE
            }
        }
    }

    /**
     * Called when USB permission result arrives from the system.
     */
    fun onPermissionResult(device: UsbDevice, granted: Boolean) {
        Log.i(TAG, "onPermissionResult: granted=$granted for ${device.productName}")
        if (_sessionState.value != SessionState.WAITING_FOR_PERMISSION) {
            Log.w(TAG, "Permission result arrived in unexpected state: ${_sessionState.value}")
        }

        if (granted) {
            scope.launch {
                proceedAfterPermission(device)
            }
        } else {
            Log.w(TAG, "USB permission denied for ${device.productName}")
            parkedDevice = device // Park for retry if user re-enables
            _sessionState.value = SessionState.PERMISSION_DENIED
        }
    }

    // ── Internal Claim Sequence ─────────────────────────────────

    /**
     * Gate 1: Exclusive mode already checked by caller.
     * Gate 2: Device is provided by caller.
     * Gate 3: Request USB permission.
     */
    private suspend fun beginClaimSequence(device: UsbDevice) {
        activeDevice = device
        _sessionState.value = SessionState.WAITING_FOR_PERMISSION

        // Check if we already have permission (e.g., previously granted)
        if (usbManager.hasPermission(device)) {
            Log.d(TAG, "USB permission already granted for ${device.productName}")
            proceedAfterPermission(device)
        } else {
            Log.d(TAG, "Requesting USB permission for ${device.productName}")
            // The UsbAudioManager/Orchestrator handles the permission PendingIntent
            // We route through UsbAudioManager so it tracks state properly
            usbAudioManager.onUsbDeviceAttached(device)
        }
    }

    /**
     * Gate 4: Evict AudioFlinger.
     * Gate 5: Claim USB interface.
     * Called after permission is confirmed granted.
     */
    private suspend fun proceedAfterPermission(device: UsbDevice) {
        _isRoutingTransitionInProgress.set(true)
        try {
            // Gate 4: AudioFlinger eviction
            _sessionState.value = SessionState.EVICTING_AUDIOFLINGER
            Log.i(TAG, "Evicting AudioFlinger for ${device.productName}")
            evictAudioFlinger()

            // Gate 5: Claim interface
            _sessionState.value = SessionState.CLAIMING_INTERFACE
            Log.i(TAG, "Waiting for enumeration to call claimInterface for ${device.productName}")
            
            // Note: _isRoutingTransitionInProgress is cleared inside claimInterface
            // or in a timeout if it never arrives.
            
        } catch (e: Exception) {
            Log.e(TAG, "Claim sequence failed: ${e.message}", e)
            _sessionState.value = SessionState.IDLE
            activeDevice = null
            _isRoutingTransitionInProgress.set(false)
        }
    }

    /**
     * Gate 5 execution. Called by OutputRouter when enumeration is complete.
     */
    fun claimInterface(device: UsbDevice, caps: chromahub.rhythm.app.infrastructure.audio.siphon.SiphonDeviceCapabilities) {
        if (_sessionState.value != SessionState.CLAIMING_INTERFACE) {
            Log.w(TAG, "claimInterface called but state is ${_sessionState.value}")
            return
        }

        scope.launch {
            try {
                outputRouter?.executeSwitchToSiphon(device, caps)
                _sessionState.value = SessionState.ACTIVE
                activeDevice = device
                Log.i(TAG, "Session ACTIVE — Siphon pipeline running for ${device.productName}")
            } catch (e: Exception) {
                Log.e(TAG, "executing switch to siphon failed: ${e.message}", e)
                _sessionState.value = SessionState.IDLE
            } finally {
                _isRoutingTransitionInProgress.set(false)
            }
        }
    }

    /**
     * Gate 4 execution. Force AudioFlinger to release its death grip on the ALSA PCM.
     */
    private suspend fun evictAudioFlinger() {
        // Step 1: Tell audio policy to disconnect USB output
        try {
            val siphonAudioManager = context.createAttributionContext("siphon")
                .getSystemService(AudioManager::class.java)
            siphonAudioManager.setParameters("usb_out_connected=false")
            Log.d(TAG, "AudioFlinger eviction step 1: setParameters done")
        } catch (e: Exception) {
            Log.w(TAG, "setParameters failed (non-fatal): ${e.message}")
        }

        // Step 2: Silent AudioTrack to force AudioFlinger to close ALSA handle
        try {
            val usbDeviceInfo = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE }
            if (usbDeviceInfo != null) {
                val silentTrack = android.media.AudioTrack.Builder()
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        android.media.AudioFormat.Builder()
                            .setSampleRate(48000)
                            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(48000 * 2 * 2 / 10) // ~100ms buffer
                    .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                    .build()
                silentTrack.setPreferredDevice(usbDeviceInfo)
                val silentBuf = ByteArray(48000 * 2 * 2 / 10)
                silentTrack.write(silentBuf, 0, silentBuf.size)
                silentTrack.play()
                delay(80)
                silentTrack.stop()
                silentTrack.release()
                Log.d(TAG, "AudioFlinger eviction step 2: silent track done")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Silent track eviction failed (non-fatal): ${e.message}")
        }

        // Step 3: Wait for ALSA kernel driver release
        delay(200)
        Log.d(TAG, "AudioFlinger eviction step 3: ALSA release delay done")
    }

    /**
     * Release Siphon session and return to AudioFlinger standard path.
     */
    private fun releaseSession() {
        _sessionState.value = SessionState.RELEASING
        _isRoutingTransitionInProgress.set(true)
        
        try {
            Log.i(TAG, "Releasing Siphon session")
            siphonManager.disconnect()
            outputRouter?.switchToStandard()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing session: ${e.message}", e)
        } finally {
            _isRoutingTransitionInProgress.set(false)
            activeDevice = null
            _sessionState.value = SessionState.IDLE
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    /**
     * Find a USB DAC already connected to the device via AudioManager.
     */
    private fun findConnectedUsbDac(): UsbDevice? {
        // Check AudioManager for USB audio output device
        val hasUsbAudio = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }

        if (!hasUsbAudio) return null

        // Match to a raw UsbDevice from UsbManager
        return usbManager.deviceList.values.firstOrNull { device ->
            isUsbAudioDevice(device)
        }
    }

    private fun isUsbAudioDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == 0x01) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == android.hardware.usb.UsbConstants.USB_CLASS_AUDIO) {
                return true
            }
        }
        return false
    }

    /**
     * Clean shutdown — call from service onDestroy.
     */
    fun release() {
        sessionJob?.cancel()
        if (_sessionState.value == SessionState.ACTIVE) {
            releaseSession()
        }
        parkedDevice = null
        activeDevice = null
        _isRoutingTransitionInProgress.set(false)
        Log.d(TAG, "SiphonSessionManager released")
    }
}



