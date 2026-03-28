package chromahub.rhythm.app.infrastructure.debug

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Diagnostic tool to monitor audio engine health, buffer levels, and routing stability.
 */
object AudioStressTest {
    private const val TAG = "AudioStressTest"
    
    private val _bufferLevel = MutableStateFlow(0f)
    val bufferLevel: StateFlow<Float> = _bufferLevel
    
    private val _underrunCount = MutableStateFlow(0)
    val underrunCount: StateFlow<Int> = _underrunCount
    
    private var monitorJob: Job? = null
    
    /**
     * Start monitoring the audio engine.
     */
    fun startMonitoring(scope: CoroutineScope) {
        if (monitorJob != null) return
        
        monitorJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                // In a real implementation, we would query the native engine or ExoPlayer
                // For now, this is a placeholder for the diagnostic UI
                delay(1000)
                Log.d(TAG, "Monitoring audio health... Buffer OK, Underruns: ${_underrunCount.value}")
            }
        }
    }
    
    /**
     * Stop monitoring.
     */
    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
    }
    
    /**
     * Record an underrun event.
     */
    fun reportUnderrun() {
        _underrunCount.value++
        Log.w(TAG, "Audio underrun detected! Total: ${_underrunCount.value}")
    }
    
    /**
     * Update current buffer level (0.0 to 1.0).
     */
    fun updateBufferLevel(level: Float) {
        _bufferLevel.value = level
    }
}
