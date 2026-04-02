package chromahub.rhythm.app.infrastructure.tile

import android.util.Log

/**
 * Detects double-tap gestures within a configurable time window.
 * Used by RhythmTileService for skip functionality.
 */
class DoubleTapDetector(
    private val windowMs: Long = 400L,
    private val onSingleTapExecute: () -> Unit,
    private val onDoubleTapExecute: () -> Unit,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit
) {
    companion object {
        private const val TAG = "TAP_HANDLER"
    }

    private var pendingRunnable: Runnable? = null
    private var lastTapTimeMs = 0L

    fun onTap() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastTapTimeMs

        if (elapsed <= windowMs && pendingRunnable != null) {
            // Double tap detected - cancel pending single tap
            Log.d(TAG, "Double tap detected. Canceling single tap.")
            pendingRunnable?.let { removeCallbacks(it) }
            pendingRunnable = null
            lastTapTimeMs = 0L
            onDoubleTapExecute()
        } else {
            // Potential single tap
            Log.d(TAG, "Single tap registered. Waiting for potential double tap...")
            lastTapTimeMs = now
            val runnable = Runnable {
                Log.d(TAG, "Executing single tap action after $windowMs ms.")
                pendingRunnable = null
                onSingleTapExecute()
            }
            pendingRunnable = runnable
            postDelayed(runnable, windowMs)
        }
    }

    fun cancel() {
        pendingRunnable?.let {
            Log.d(TAG, "Canceling pending tap operations.")
            removeCallbacks(it)
        }
        pendingRunnable = null
        lastTapTimeMs = 0L
    }
}
