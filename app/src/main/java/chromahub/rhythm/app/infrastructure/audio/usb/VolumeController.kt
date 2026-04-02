package chromahub.rhythm.app.infrastructure.audio.usb

import android.hardware.usb.UsbDeviceConnection
import android.util.Log
import chromahub.rhythm.app.infrastructure.audio.siphon.SiphonIsochronousEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RoutingMode {
    HARDWARE_VOLUME,
    SOFTWARE_VOLUME
}

class VolumeController {
    companion object {
        private const val TAG = "VolumeController"
    }

    private var usbConnection: UsbDeviceConnection? = null
    private var featureUnitId: Int = 0
    private var dacVolMin: Int = -32768
    private var dacVolMax: Int = 0

    private val _currentVolume = MutableStateFlow(100)
    val currentVolume: StateFlow<Int> = _currentVolume.asStateFlow()

    private val _routingMode = MutableStateFlow(RoutingMode.HARDWARE_VOLUME)
    val routingMode: StateFlow<RoutingMode> = _routingMode.asStateFlow()

    fun bindUsb(connection: UsbDeviceConnection, featureUnitId: Int, dacVolMin: Int, dacVolMax: Int) {
        this.usbConnection = connection
        this.featureUnitId = featureUnitId
        this.dacVolMin = dacVolMin
        this.dacVolMax = dacVolMax
    }

    fun setRoutingMode(mode: RoutingMode) {
        _routingMode.value = mode
    }

    fun adjust(delta: Int) {
        val next = (_currentVolume.value + delta).coerceIn(0, 100)
        setVolume(next)
    }

    fun setVolume(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        _currentVolume.value = clamped

        when (_routingMode.value) {
            RoutingMode.HARDWARE_VOLUME -> sendHardwareVolumeCommand(clamped)
            RoutingMode.SOFTWARE_VOLUME -> SiphonIsochronousEngine.nativeSetSoftwareVolume(clamped)
        }
    }

    fun setMuted(muted: Boolean) {
        val connection = usbConnection ?: return
        if (featureUnitId == 0) return

        val payload = byteArrayOf(if (muted) 0x01 else 0x00)
        // bmRequestType 0x21: Host-to-Device | Class | Interface
        // bRequest     0x01: SET_CUR
        // wValue       0x0100: Control Selector=Mute(0x01), Channel=Master(0x00)
        // wIndex       FU_ID<<8 | interface(0)
        val rc = connection.controlTransfer(
            0x21,
            0x01,
            0x0100,
            featureUnitId shl 8,
            payload,
            1,
            500
        )
        if (rc < 0) {
            Log.w(TAG, "Mute control transfer failed: rc=$rc")
        }
    }

    private fun sendHardwareVolumeCommand(volumePercent: Int) {
        val connection = usbConnection ?: return
        if (featureUnitId == 0) {
            Log.w(TAG, "No UAC2 feature unit available, bypassing hardware volume control.")
            return
        }

        val uac2Volume = dacVolMin + ((dacVolMax - dacVolMin) * volumePercent / 100)
        val volumeBytes = byteArrayOf(
            (uac2Volume and 0xFF).toByte(),
            ((uac2Volume shr 8) and 0xFF).toByte()
        )

        // bmRequestType 0x21: Host-to-Device | Class | Interface
        // bRequest     0x01: SET_CUR
        // wValue       0x0200: Control Selector=Volume(0x02), Channel=Master(0x00)
        // wIndex       FU_ID<<8 | interface(0)
        val rc = connection.controlTransfer(
            0x21,
            0x01,
            0x0200,
            featureUnitId shl 8,
            volumeBytes,
            2,
            1000
        )
        if (rc < 0) {
            Log.w(TAG, "Hardware volume transfer failed: rc=$rc. Siphon is operating at base hardware output level.")
        }
    }
}
