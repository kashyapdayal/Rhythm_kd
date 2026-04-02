package chromahub.rhythm.app.infrastructure.audio

import kotlin.math.pow
import kotlin.math.max

/**
 * Rhythm Audio Processor - Applies ReplayGain volume adjustment.
 * 
 * Supports reading track and album gain metadata to normalize volume
 * levels across different tracks, with an optional soft limiter to prevent clipping.
 */
class RhythmReplayGainProcessor : RhythmAudioProcessor() {

    private var targetGainDb: Float = 0f
    private var targetPeak: Float = 1.0f
    private var preventClipping: Boolean = true
    private var isEnabled: Boolean = false

    // Pre-calculated multiplier for the current gain
    private var multiplier: Float = 1.0f

    @Synchronized
    fun setReplayGain(gainDb: Float, peak: Float, preventClip: Boolean) {
        this.targetGainDb = gainDb
        this.targetPeak = peak
        this.preventClipping = preventClip
        
        // Calculate linear multiplier: 10 ^ (dB / 20)
        multiplier = 10f.pow(gainDb / 20f)
        
        // If prevent clipping is enabled and the resulting peak would exceed 1.0 (0 dBFS)
        if (preventClipping && targetPeak * multiplier > 1.0f) {
            // Reduce multiplier so that peak * multiplier = 1.0
            if (targetPeak > 0f) {
                multiplier = 1.0f / targetPeak
            }
        }
        
        isEnabled = gainDb != 0f
    }

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        this.isEnabled = enabled
    }

    override fun isEnabled(): Boolean {
        // Only active if explicitly enabled AND multiplier is not exactly 1.0
        return isEnabled && multiplier != 1.0f
    }

    override fun processSamples(samples: ShortArray) {
        val currentMultiplier = multiplier // Thread-safe snapshot

        for (i in samples.indices) {
            // Apply gain
            val sampleFlow = samples[i] * currentMultiplier

            // Hard clip to 16-bit range since soft limiter is handled by gain reduction
            samples[i] = when {
                sampleFlow > Short.MAX_VALUE -> Short.MAX_VALUE
                sampleFlow < Short.MIN_VALUE -> Short.MIN_VALUE
                else -> sampleFlow.toInt().toShort()
            }
        }
    }
}
