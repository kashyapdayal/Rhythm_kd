package chromahub.rhythm.app.infrastructure.tile

import android.util.Log
import chromahub.rhythm.app.BuildConfig

/**
 * Optimized double-tap detector with INSTANT single-tap response.
 * 
 * Strategy:
 * - First tap: Execute immediately (play/pause) with <50ms latency
 * - Second tap within window: Cancel first action if possible, execute skip
 * 
 * This provides instant feedback instead of the traditional 300ms delay.
 */
class DoubleTapDetector(
    private val windowMs: Long = 300L, // Reduced from 400ms for snappier UX
    private val onSingleTapImmediate: () -> Unit,
    private val onDoubleTapDetected: () -> Unit,
    private val cancelPendingAction: () -> Boolean // Returns true if successfully cancelled
) {
    companion object {
        private const val TAG = "TAP_HANDLER"
    }

    @Volatile
    private var lastTapTimeMs = 0L

    /**
     * Handle tap event with instant execution.
     * Returns true if this was processed as a double tap.
     */
    fun onTap(): Boolean {
        val now = System.currentTimeMillis()
        val elapsed = now - lastTapTimeMs

        return if (elapsed in 1..windowMs && lastTapTimeMs > 0) {
            // Double tap detected within window
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "⚡ Double tap (${elapsed}ms) - attempting to cancel first action")
            }
            
            // Try to cancel the first action (may already be executing)
            val cancelled = cancelPendingAction()
            
            if (BuildConfig.DEBUG && !cancelled) {
                Log.d(TAG, "First action already committed - skip will follow play/pause")
            }
            
            lastTapTimeMs = 0L // Reset to prevent triple-tap
            onDoubleTapDetected()
            true
        } else {
            // Single tap - execute immediately for instant response
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "⚡ Single tap - instant execution")
            }
            
            lastTapTimeMs = now
            onSingleTapImmediate()
            false
        }
    }

    fun cancel() {
        lastTapTimeMs = 0L
    }
}
