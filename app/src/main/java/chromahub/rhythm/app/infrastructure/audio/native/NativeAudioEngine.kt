package chromahub.rhythm.app.infrastructure.audio.native

import android.util.Log
import java.nio.ByteBuffer

/**
 * Kotlin interface to the Native (C++/NDK) Audio Engine.
 * Handles JNI communication and native lifecycle.
 */
class NativeAudioEngine {
    
    companion object {
        private const val TAG = "NativeAudioEngine"
        
        init {
            try {
                System.loadLibrary("rhythm_audio")
                Log.d(TAG, "Native library 'rhythm_audio' loaded successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load native library 'rhythm_audio'", e)
            }
        }
    }
    
    /**
     * Get the native version string.
     */
    external fun getNativeVersion(): String
    
    /**
     * Initialize the native engine.
     * @param sampleRate The sample rate to use.
     * @param channels Number of audio channels.
     * @param bitDepth The bit depth (e.g., 16, 24, 32).
     */
    external fun initialize(sampleRate: Int, channels: Int, bitDepth: Int): Boolean
    
    /**
     * Start the native audio processing thread.
     */
    external fun start(): Boolean
    
    /**
     * Stop the native audio processing thread.
     */
    external fun stop()
    
    /**
     * Pause native audio processing.
     */
    external fun pause()
    
    /**
     * Resume native audio processing.
     */
    external fun resume()
    
    /**
     * Pass an audio file descriptor to the native engine for direct USB access.
     */
    external fun setUsbFileDescriptor(fd: Int)

    /**
     * Pass raw USB descriptors to the native engine for hardware discovery.
     */
    external fun setUsbDescriptors(descriptors: ByteArray)
    
    /**
     * Bug 3 FIX: Update audio session ID after ExoPlayer creates it.
     * This ensures the native engine has a valid session ID for audio effects.
     */
    external fun updateAudioSessionId(audioSessionId: Int)
    
    /**
     * Bug 4 FIX: Set hardware volume via UAC2 feature unit.
     * @param volumeDb Volume in dB (-60.0 to 0.0)
     */
    external fun setHardwareVolume(volumeDb: Float): Boolean
    
    /**
     * Push PCM data to the native buffer queue.
     * @param buffer Direct ByteBuffer containing PCM data.
     * @param length Number of bytes in the buffer.
     */
    external fun pushData(buffer: ByteBuffer, length: Int)
    
    /**
     * Set the parametric EQ parameters.
     * @param freq Frequency in Hz.
     * @param q Q-factor.
     * @param gain Gain in dB.
     */
    external fun setEq(freq: Float, q: Float, gain: Float)
}
