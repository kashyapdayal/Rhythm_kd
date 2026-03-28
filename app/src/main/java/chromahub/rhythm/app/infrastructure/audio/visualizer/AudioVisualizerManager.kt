package chromahub.rhythm.app.infrastructure.audio.visualizer

import android.media.audiofx.Visualizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

class AudioVisualizerManager {
    private var visualizer: Visualizer? = null
    private var currentAudioSessionId: Int = 0
    
    // Hold waveform data as FloatArray (normalized -1f to 1f)
    private val _waveformFlow = MutableStateFlow(FloatArray(0))
    val waveformFlow: StateFlow<FloatArray> = _waveformFlow.asStateFlow()

    fun attachToSession(sessionId: Int) {
        if (sessionId == 0 || sessionId == currentAudioSessionId) return
        
        release()
        currentAudioSessionId = sessionId
        
        try {
            visualizer = Visualizer(sessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1] // Max capture size
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            visualizer: Visualizer?,
                            waveform: ByteArray?,
                            samplingRate: Int
                        ) {
                            waveform?.let { processWaveform(it) }
                        }

                        override fun onFftDataCapture(
                            visualizer: Visualizer?,
                            fft: ByteArray?,
                            samplingRate: Int
                        ) {}
                    },
                    Visualizer.getMaxCaptureRate() / 2, // Capture at 50% max rate for performance
                    true,
                    false
                )
                enabled = true
            }
            Log.d(TAG, "Visualizer attached to session $sessionId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Visualizer. Permission RECORD_AUDIO might be missing or session invalid.", e)
            _waveformFlow.value = FloatArray(0)
        }
    }

    fun setEnabled(enabled: Boolean) {
        try {
            visualizer?.enabled = enabled
            if (!enabled) {
                // Clear waveform when disabled
                _waveformFlow.value = FloatArray(0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error setting visualizer enabled state: ${e.message}")
        }
    }

    private fun processWaveform(waveform: ByteArray) {
        // Convert 8-bit unsigned PCM (0-255, center 128) to FloatArray (-1f to 1f)
        val floatArray = FloatArray(waveform.size)
        for (i in waveform.indices) {
            val v = (waveform[i].toInt() and 0xFF) - 128
            floatArray[i] = v / 128f
        }
        _waveformFlow.value = floatArray
    }

    fun release() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing visualizer: ${e.message}")
        } finally {
            visualizer = null
            currentAudioSessionId = 0
            _waveformFlow.value = FloatArray(0)
        }
    }

    companion object {
        private const val TAG = "AudioVisualizerManager"
    }
}
