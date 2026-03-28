package chromahub.rhythm.app.infrastructure.audio

import android.content.Context
import android.hardware.usb.UsbDeviceConnection
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import chromahub.rhythm.app.infrastructure.audio.usb.VolumeAudioProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.pow

/**
 * VolumeManager — HiBy-style 4-mode volume control for Rhythm.
 *
 * MODE 1 — SYSTEM:     Android AudioManager.STREAM_MUSIC (default, no USB)
 * MODE 2 — AUTO:       Probes DAC for hardware volume → falls back to software
 * MODE 3 — HARDWARE:   USB Audio Class SET_CUR control transfers to DAC
 * MODE 4 — SOFTWARE:   VolumeAudioProcessor gain applied before NativeAudioSink
 */
class VolumeManager(
    private val context: Context,
    private val volumeProcessor: VolumeAudioProcessor? = null
) {
    companion object {
        private const val TAG = "VolumeManager"

        // USB Audio Class constants
        private const val SET_CUR: Int = 0x01
        private const val GET_CUR: Int = 0x81
        private const val GET_MIN: Int = 0x82
        private const val GET_MAX: Int = 0x83
        private const val GET_RES: Int = 0x84
        private const val VOLUME_CONTROL: Int = 0x02
        
        // bmRequestType for class-specific interface request
        private const val USB_DIR_OUT_CLASS_INTERFACE: Int = 0x21  // SET, CLASS, INTERFACE
        private const val USB_DIR_IN_CLASS_INTERFACE: Int = 0xA1   // GET, CLASS, INTERFACE
    }

    // ── Volume Mode ────────────────────────────────────────────────

    enum class VolumeMode {
        /** Android system stream volume (STREAM_MUSIC) */
        SYSTEM,
        /** Auto-detect: probe DAC hardware → fallback to software */
        AUTO,
        /** Force USB hardware volume (SET_CUR control transfers) */
        HARDWARE,
        /** Force software volume (VolumeAudioProcessor gain) */
        SOFTWARE
    }

    /** Result of probing a DAC for hardware volume support */
    sealed class VolumeCapability {
        data class Supported(
            val minVolume: Short,
            val maxVolume: Short,
            val resolution: Short,
            val featureUnitId: Int,
            val interfaceNumber: Int
        ) : VolumeCapability() {
            override fun toString() = "HW Volume: min=$minVolume, max=$maxVolume, res=$resolution"
        }
        data object Unsupported : VolumeCapability()
    }

    // ── State ──────────────────────────────────────────────────────

    private val _mode = MutableStateFlow(VolumeMode.SYSTEM)
    val mode: StateFlow<VolumeMode> = _mode.asStateFlow()

    private val _volume = MutableStateFlow(1.0f) // 0.0..1.0 normalized
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _resolvedMode = MutableStateFlow("System") // Human-readable for UI
    val resolvedMode: StateFlow<String> = _resolvedMode.asStateFlow()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var usbConnection: UsbDeviceConnection? = null
    private var hwCapability: VolumeCapability.Supported? = null
    private var lastKnownVolume: Float = 1.0f

    // ── Public API ─────────────────────────────────────────────────

    /**
     * Set the volume mode and re-apply volume.
     */
    fun setMode(newMode: VolumeMode) {
        Log.i(TAG, "setMode: ${_mode.value} → $newMode")
        _mode.value = newMode
        applyResolved()
        // Re-apply volume to avoid zero-volume bug on mode switch
        setVolume(lastKnownVolume)
    }

    /**
     * Set normalized volume (0.0..1.0) and apply to the active path.
     */
    fun setVolume(normalized: Float) {
        val clamped = normalized.coerceIn(0f, 1f)
        _volume.value = clamped
        lastKnownVolume = clamped
        
        when (resolveActiveMode()) {
            VolumeMode.SYSTEM -> applySystemVolume(clamped)
            VolumeMode.HARDWARE -> applyHardwareVolume(clamped)
            VolumeMode.SOFTWARE -> applySoftwareVolume(clamped)
            VolumeMode.AUTO -> {} // Handled by resolve
        }
    }

    /**
     * Call before exoPlayer.play() to ensure volume is applied.
     * Prevents zero-volume bug on engine restarts.
     */
    fun reapplyCurrentVolume() {
        Log.d(TAG, "reapplyCurrentVolume: $lastKnownVolume in mode ${_mode.value}")
        setVolume(lastKnownVolume)
    }

    /**
     * Probe a USB DAC for hardware volume support.
     */
    suspend fun probeHardwareVolume(
        connection: UsbDeviceConnection,
        featureUnitId: Int = 0x06,  // Common default; varies by DAC
        interfaceNumber: Int = 0
    ): VolumeCapability {
        this.usbConnection = connection
        Log.d(TAG, "Probing hardware volume: FU=$featureUnitId, iface=$interfaceNumber")

        try {
            val minBuf = ByteArray(2)
            val maxBuf = ByteArray(2)
            val resBuf = ByteArray(2)

            val wIndex = (featureUnitId shl 8) or interfaceNumber
            val wValue = (VOLUME_CONTROL shl 8) or 0 // Master channel (0)

            val minResult = connection.controlTransfer(
                USB_DIR_IN_CLASS_INTERFACE, GET_MIN, wValue, wIndex, minBuf, 2, 1000
            )
            val maxResult = connection.controlTransfer(
                USB_DIR_IN_CLASS_INTERFACE, GET_MAX, wValue, wIndex, maxBuf, 2, 1000
            )
            val resResult = connection.controlTransfer(
                USB_DIR_IN_CLASS_INTERFACE, GET_RES, wValue, wIndex, resBuf, 2, 1000
            )

            if (minResult >= 2 && maxResult >= 2 && resResult >= 2) {
                val min = bytesToShort(minBuf)
                val max = bytesToShort(maxBuf)
                val res = bytesToShort(resBuf)

                val capability = VolumeCapability.Supported(min, max, res, featureUnitId, interfaceNumber)
                hwCapability = capability
                Log.i(TAG, "DAC hardware volume SUPPORTED: $capability")
                return capability
            }

            Log.d(TAG, "DAC hardware volume probe failed: min=$minResult, max=$maxResult, res=$resResult")
        } catch (e: Exception) {
            Log.e(TAG, "Hardware volume probe error", e)
        }

        return VolumeCapability.Unsupported
    }

    /**
     * Handle volume key events. Returns true if consumed (USB exclusive modes).
     */
    fun onVolumeKeyEvent(keyCode: Int): Boolean {
        val activeMode = resolveActiveMode()

        // SYSTEM mode: let Android handle it
        if (activeMode == VolumeMode.SYSTEM) {
            return false
        }

        // USB modes: intercept and handle ourselves
        val step = 1.0f / 30f  // ~30 steps like most DACs
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                setVolume((_volume.value + step).coerceAtMost(1f))
                Log.d(TAG, "Volume key UP → ${_volume.value}")
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                setVolume((_volume.value - step).coerceAtLeast(0f))
                Log.d(TAG, "Volume key DOWN → ${_volume.value}")
                return true
            }
        }
        return false
    }

    /**
     * Set the USB connection for hardware volume control.
     */
    fun setUsbConnection(connection: UsbDeviceConnection?) {
        this.usbConnection = connection
    }

    /**
     * Release resources when switching away from USB modes.
     */
    fun onUsbDisconnected() {
        usbConnection = null
        hwCapability = null
        if (_mode.value != VolumeMode.SYSTEM) {
            _mode.value = VolumeMode.SYSTEM
            applyResolved()
            setVolume(lastKnownVolume)
            Log.i(TAG, "USB disconnected — reverted to SYSTEM volume")
        }
    }

    // ── Private Helpers ────────────────────────────────────────────

    private fun resolveActiveMode(): VolumeMode {
        return when (_mode.value) {
            VolumeMode.AUTO -> {
                if (hwCapability != null) VolumeMode.HARDWARE else VolumeMode.SOFTWARE
            }
            else -> _mode.value
        }
    }

    private fun applyResolved() {
        val resolved = resolveActiveMode()
        _resolvedMode.value = when (_mode.value) {
            VolumeMode.SYSTEM -> "System"
            VolumeMode.AUTO -> if (resolved == VolumeMode.HARDWARE) "Auto (Hardware)" else "Auto (Software)"
            VolumeMode.HARDWARE -> "Hardware"
            VolumeMode.SOFTWARE -> "Software"
        }
        
        // Enable/disable software volume processor based on resolved mode
        when (resolved) {
            VolumeMode.SYSTEM, VolumeMode.HARDWARE -> {
                // Bypass software volume (pass-through, gain = 1.0)
                volumeProcessor?.setVolume(1.0f)
                volumeProcessor?.setBypass(true)
            }
            VolumeMode.SOFTWARE -> {
                volumeProcessor?.setBypass(false)
            }
            VolumeMode.AUTO -> {} // Handled by resolve
        }
    }

    private fun applySystemVolume(normalized: Float) {
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val targetVol = (normalized * maxVol).toInt().coerceIn(0, maxVol)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
        Log.d(TAG, "System volume set to $targetVol/$maxVol")
    }

    private fun applyHardwareVolume(normalized: Float) {
        val hw = hwCapability ?: run {
            Log.w(TAG, "Hardware volume requested but no capability — falling back to software")
            applySoftwareVolume(normalized)
            return
        }

        val conn = usbConnection ?: run {
            Log.w(TAG, "Hardware volume requested but no USB connection — falling back to software")
            applySoftwareVolume(normalized)
            return
        }

        // Map normalized (0..1) to DAC range (wMin..wMax)
        val range = hw.maxVolume - hw.minVolume
        val dacVolume = (hw.minVolume + (normalized * range).toInt()).toShort()

        val wIndex = (hw.featureUnitId shl 8) or hw.interfaceNumber
        val wValue = (VOLUME_CONTROL shl 8) or 0 // Master channel

        val data = shortToBytes(dacVolume)
        val result = conn.controlTransfer(
            USB_DIR_OUT_CLASS_INTERFACE, SET_CUR, wValue, wIndex, data, 2, 1000
        )

        if (result >= 0) {
            Log.d(TAG, "Hardware volume set to $dacVolume (raw), normalized=$normalized")
        } else {
            Log.e(TAG, "Failed to set hardware volume: result=$result")
        }
    }

    private fun applySoftwareVolume(normalized: Float) {
        // Perceptual linearity: squared curve
        val gain = normalized.pow(2)
        volumeProcessor?.setBypass(false)
        volumeProcessor?.setVolume(gain)
        Log.d(TAG, "Software volume set: normalized=$normalized, gain=$gain")
    }

    // ── Byte Helpers ───────────────────────────────────────────────

    private fun bytesToShort(buf: ByteArray): Short {
        return ((buf[1].toInt() shl 8) or (buf[0].toInt() and 0xFF)).toShort()
    }

    private fun shortToBytes(value: Short): ByteArray {
        return byteArrayOf(
            (value.toInt() and 0xFF).toByte(),
            ((value.toInt() shr 8) and 0xFF).toByte()
        )
    }
}
