package chromahub.rhythm.app.engine

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer

@UnstableApi
class DirectBitEngine(
    private val context: Context,
    private val usbDevice: UsbDevice?
) : AudioSink {

    companion object {
        const val TAG = "EXOPLAYER_OUTPUT"
        init { System.loadLibrary("siphon_engine") }
    }

    enum class ActiveTier { USB_HOST_DIRECT, NONE }
    var activeTier: ActiveTier = ActiveTier.NONE
        private set

    private var _isInitialized = false
    private var _softwareVolume: Double = 1.0
    private var _hardwareVolume: Float = 1.0f
    private var useHardwareVolume: Boolean = false
    private var inputFormat: Format? = null
    
    // Position tracking helper
    private var submittedBytes: Long = 0
    private var frameSize: Int = 4
    private var channelCount: Int = 2
    private var formatEnum: Int = 0

    val currentVolume: Double
        get() = if (useHardwareVolume) _hardwareVolume.toDouble() else _softwareVolume

    private external fun nativeInitUsbDirect(fd: Int, sampleRate: Int, bitDepth: Int, channels: Int): Int
    private external fun nativeUsbByteArrayWrite(data: ByteArray, offset: Int, length: Int, timestamp: Long): Int
    private external fun nativeFlush()
    private external fun nativeReleaseUsbDirect()
    private external fun nativeSendHardwareVolume(normalizedVolume: Float): Int
    private external fun nativeQueryHardwareVolumeSupport(): Boolean
    private external fun nativeApplySoftwareGain(buffer: ByteBuffer, frames: Int, channels: Int, format: Int, gain: Double)

    private fun acquireUsbFd(): Int {
        if (usbDevice == null) return -1
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!usbManager.hasPermission(usbDevice)) return -1
        val connection = usbManager.openDevice(usbDevice) ?: return -1
        return connection.fileDescriptor
    }

    override fun setListener(listener: AudioSink.Listener) {}
    
    override fun supportsFormat(format: Format): Boolean {
        // Assume we try to bypass anything raw
        return true
    }
    
    override fun getFormatSupport(format: Format): Int = AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        this.inputFormat = inputFormat
        val bitDepth = if (inputFormat.pcmEncoding == C.ENCODING_PCM_32BIT) 32 else 16
        this.channelCount = inputFormat.channelCount
        this.frameSize = channelCount * (bitDepth / 8)
        this.formatEnum = when (bitDepth) {
            16 -> 0; 24 -> 1; 32 -> 2; else -> 0
        }
        
        val fd = acquireUsbFd()
        if (fd > 0) {
            val result = nativeInitUsbDirect(fd, inputFormat.sampleRate, bitDepth, inputFormat.channelCount)
            if (result == 0) {
                _isInitialized = true
                activeTier = ActiveTier.USB_HOST_DIRECT
                useHardwareVolume = nativeQueryHardwareVolumeSupport()
            }
        }
        
        if (!_isInitialized) {
            Log.e(TAG, "FAILED to configure native audio siphon mode.")
            activeTier = ActiveTier.NONE
        }
    }

    fun isInitialized(): Boolean = _isInitialized

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        if (!_isInitialized || activeTier != ActiveTier.USB_HOST_DIRECT) return false
        
        val size = buffer.remaining()
        
        // Software volume scaling if needed
        if (!useHardwareVolume && _softwareVolume != 1.0) {
            nativeApplySoftwareGain(buffer, size / frameSize, channelCount, formatEnum, _softwareVolume)
        }

        val data = ByteArray(size)
        buffer.get(data)
        
        val bytesWritten = nativeUsbByteArrayWrite(data, 0, size, presentationTimeUs)
        if (bytesWritten < 0) {
            Log.e(TAG, "AUDIO_PIPELINE: Failed writing to USB Endpoint.")
            return false
        }
        
        submittedBytes += bytesWritten
        return true
    }

    override fun setVolume(volume: Float) {
        if (useHardwareVolume && activeTier == ActiveTier.USB_HOST_DIRECT) {
            _hardwareVolume = volume
            nativeSendHardwareVolume(_hardwareVolume)
            _softwareVolume = 1.0
        } else {
            _softwareVolume = volume.toDouble()
        }
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (inputFormat == null || frameSize == 0) return 0L
        val frames = submittedBytes / frameSize
        return (frames * C.MICROS_PER_SECOND) / inputFormat!!.sampleRate
    }

    override fun flush() { 
        nativeFlush() 
        submittedBytes = 0
    }
    
    override fun reset() {
        nativeReleaseUsbDirect()
        _isInitialized = false
        activeTier = ActiveTier.NONE
        submittedBytes = 0
    }
    
    override fun play() {}
    override fun pause() {}
    override fun playToEndOfStream() {}
    override fun isEnded(): Boolean = false
    override fun hasPendingData(): Boolean = _isInitialized
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {}
    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {}
    override fun getSkipSilenceEnabled(): Boolean = false
    override fun setAudioAttributes(audioAttributes: AudioAttributes) {}
    override fun getAudioAttributes(): AudioAttributes? = null
    override fun setAudioSessionId(audioSessionId: Int) {}
    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {}
    override fun enableTunnelingV21() {}
    override fun disableTunneling() {}
    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) {}
    override fun handleDiscontinuity() {}
    override fun getAudioTrackBufferSizeUs(): Long = 0L
}
