package chromahub.rhythm.app.engine

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.nio.ByteBuffer

/**
 * DirectBitEngine — USB-only native audio engine.
 * Delegates to siphon_engine.cpp via JNI for USB isochronous streaming.
 */
class DirectBitEngine(private val context: Context) {

    enum class ActiveTier { USB_HOST_DIRECT, NONE }
    var activeTier: ActiveTier = ActiveTier.NONE
        private set

    private var _softwareVolume: Double = 1.0
    private var _hardwareVolume: Float = 1.0f
    private var useHardwareVolume: Boolean = false
    
    val currentVolume: Double
        get() = if (useHardwareVolume) _hardwareVolume.toDouble() else _softwareVolume

    private var frameSize = 4
    private var channelCount = 2
    private var formatEnum = 0

    fun setVolume(normalizedVolume: Double) {
        if (useHardwareVolume && activeTier == ActiveTier.USB_HOST_DIRECT) {
            _hardwareVolume = normalizedVolume.toFloat()
            nativeSendHardwareVolume(_hardwareVolume)
            _softwareVolume = 1.0
        } else {
            _softwareVolume = normalizedVolume
        }
    }

    private fun acquireUsbPermissionAndGetFd(device: UsbDevice): Int {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!usbManager.hasPermission(device)) return -1
        val connection = usbManager.openDevice(device) ?: return -1
        return connection.fileDescriptor
    }

    fun initialize(usbDevice: UsbDevice?, sampleRate: Int, bitDepth: Int, channels: Int): ActiveTier {
        this.channelCount = channels
        this.frameSize = channels * if (bitDepth == 24) 3 else (bitDepth / 8)
        this.formatEnum = when (bitDepth) {
            16 -> 0
            24 -> 1
            32 -> 2
            else -> 0
        }

        if (usbDevice != null) {
            val fd = acquireUsbPermissionAndGetFd(usbDevice)
            if (fd > 0) {
                val result = nativeInitUsbDirect(fd, sampleRate, bitDepth, channels)
                if (result == 0) {
                    activeTier = ActiveTier.USB_HOST_DIRECT
                    useHardwareVolume = nativeQueryHardwareVolumeSupport()
                    return activeTier
                }
            }
        }

        activeTier = ActiveTier.NONE
        useHardwareVolume = false
        return activeTier
    }

    fun write(audioData: ByteBuffer, byteCount: Int) {
        if (!useHardwareVolume && _softwareVolume != 1.0) {
            nativeApplySoftwareGain(audioData, byteCount / frameSize, channelCount, formatEnum, _softwareVolume)
        }
        when (activeTier) {
            ActiveTier.USB_HOST_DIRECT -> nativeUsbWrite(audioData, byteCount)
            else -> {}
        }
    }

    fun release() {
        nativeReleaseUsbDirect()
        activeTier = ActiveTier.NONE
    }

    private external fun nativeInitUsbDirect(fd: Int, sampleRate: Int, bitDepth: Int, channels: Int): Int
    private external fun nativeApplySoftwareGain(buffer: ByteBuffer, frames: Int, channels: Int, format: Int, gain: Double)
    private external fun nativeSendHardwareVolume(normalizedVolume: Float): Int
    private external fun nativeQueryHardwareVolumeSupport(): Boolean
    private external fun nativeUsbWrite(buffer: ByteBuffer, byteCount: Int): Int
    private external fun nativeReleaseUsbDirect()

    companion object {
        init { System.loadLibrary("siphon_engine") }
    }
}
