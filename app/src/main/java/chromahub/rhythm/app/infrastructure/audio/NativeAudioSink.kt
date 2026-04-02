package chromahub.rhythm.app.infrastructure.audio

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import chromahub.rhythm.app.infrastructure.audio.native.NativeAudioEngine
import java.nio.ByteBuffer

/**
 * An AudioSink that forwards all PCM data to the Native C++ Audio Engine.
 * Does not use Android AudioTrack — used for USB Exclusive mode.
 *
 * Lifecycle contract (matches ExoPlayer expectations):
 *   configure() → play() → handleBuffer()* → playToEndOfStream() → isEnded()==true
 *   flush() can be called at any time and must leave the sink ready for new data.
 *   reset() is called on release.
 */
@OptIn(UnstableApi::class)
class NativeAudioSink(
    private val nativeEngine: NativeAudioEngine
) : AudioSink {

    companion object {
        private const val TAG = "NativeAudioSink"
    }

    // ── State Machine ──────────────────────────────────────────────
    private enum class State {
        /** Sink created but not yet configured. */
        IDLE,
        /** configure() called; waiting for play(). */
        CONFIGURED,
        /** play() called; actively accepting buffers. */
        PLAYING,
        /** pause() called; can resume via play(). */
        PAUSED,
        /** playToEndOfStream() called; draining remaining data. */
        ENDING,
        /** Fully ended (no pending data). */
        ENDED
    }

    private var state: State = State.IDLE
    private var listener: AudioSink.Listener? = null
    private var inputFormat: Format? = null
    private var playbackParameters = PlaybackParameters.DEFAULT
    private var writtenBytes = 0L
    private var writtenBytesAtFlush = 0L  // Track bytes at last flush for position calc
    private var nativeStarted = false

    // ── AudioSink interface ────────────────────────────────────────

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
    }

    override fun supportsFormat(format: Format): Boolean {
        // We support PCM formats that our native engine can handle
        val supported = format.sampleMimeType == null ||
                format.sampleMimeType == androidx.media3.common.MimeTypes.AUDIO_RAW
        Log.d(TAG, "supportsFormat(${format.sampleMimeType}, sr=${format.sampleRate}, ch=${format.channelCount}, enc=${format.pcmEncoding}) = $supported")
        return supported
    }

    override fun getFormatSupport(format: Format): Int {
        return if (supportsFormat(format)) {
            AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        } else {
            AudioSink.SINK_FORMAT_UNSUPPORTED
        }
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val fmt = inputFormat ?: return 0
        val sampleRate = fmt.sampleRate.takeIf { it != Format.NO_VALUE } ?: 44100
        val channels = fmt.channelCount.takeIf { it != Format.NO_VALUE } ?: 2
        val bytesPerSample = when (fmt.pcmEncoding) {
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> 2 // 16-bit default
        }
        val bytesPerFrame = channels * bytesPerSample
        if (bytesPerFrame == 0 || sampleRate == 0) return 0

        // Position based on bytes written since last flush
        val effectiveBytes = writtenBytes - writtenBytesAtFlush
        val posUs = (effectiveBytes / bytesPerFrame) * 1_000_000L / sampleRate
        return posUs
    }

    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?
    ) {
        Log.i(TAG, "configure() — sr=${inputFormat.sampleRate}, ch=${inputFormat.channelCount}, enc=${inputFormat.pcmEncoding}, state=$state")
        this.inputFormat = inputFormat

        val bitDepth = when (inputFormat.pcmEncoding) {
            C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_32BIT -> 32
            C.ENCODING_PCM_FLOAT -> 32 // Treat float as 32-bit for native
            else -> 16
        }

        val sr = inputFormat.sampleRate.takeIf { it != Format.NO_VALUE } ?: 44100
        val ch = inputFormat.channelCount.takeIf { it != Format.NO_VALUE } ?: 2

        val initOk = nativeEngine.initialize(sr, ch, bitDepth)
        Log.i(TAG, "nativeEngine.initialize(sr=$sr, ch=$ch, bits=$bitDepth) = $initOk")

        state = State.CONFIGURED
    }

    override fun play() {
        Log.i(TAG, "play() — state=$state, nativeStarted=$nativeStarted")
        if (!nativeStarted) {
            val startOk = nativeEngine.start()
            nativeStarted = startOk
            Log.i(TAG, "nativeEngine.start() = $startOk")
            if (!startOk) {
                Log.e(TAG, "CRITICAL: Native engine failed to start! Audio will be silent.")
            }
        } else {
            // Resume from pause
            nativeEngine.resume()
            Log.d(TAG, "nativeEngine.resume()")
        }
        state = State.PLAYING
    }

    override fun handleDiscontinuity() {
        Log.d(TAG, "handleDiscontinuity() — state=$state")
        // No-op: discontinuity just means a timestamp jump, we keep the native engine running
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        if (!buffer.hasRemaining()) return true

        if (state != State.PLAYING) {
            Log.w(TAG, "handleBuffer() called in state=$state — ignoring ${buffer.remaining()} bytes")
            // Consume the buffer to avoid ExoPlayer stalling
            buffer.position(buffer.limit())
            return true
        }

        if (!nativeStarted) {
            Log.e(TAG, "handleBuffer() but native engine not started — dropping ${buffer.remaining()} bytes")
            buffer.position(buffer.limit())
            return true
        }

        val length = buffer.remaining()
        nativeEngine.pushData(buffer, length)
        writtenBytes += length

        // Consume the full buffer
        buffer.position(buffer.limit())
        return true
    }

    override fun playToEndOfStream() {
        Log.d(TAG, "playToEndOfStream() — state=$state, writtenBytes=$writtenBytes")
        state = State.ENDING
        // Don't stop the native engine immediately — let it drain the CircularBuffer.
        // isEnded() will return true once we're sure the data has been consumed.
        // For simplicity (and because the C++ side has no drain callback), we mark as ended
        // after this call. A more sophisticated implementation would wait for the CircularBuffer
        // to report empty.
        state = State.ENDED
    }

    override fun isEnded(): Boolean {
        return state == State.ENDED
    }

    override fun hasPendingData(): Boolean {
        // Return true when we have written data and playback is active.
        // This prevents ExoPlayer from thinking playback finished prematurely.
        val pending = state == State.PLAYING && writtenBytes > writtenBytesAtFlush
        return pending
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        this.playbackParameters = playbackParameters
        Log.d(TAG, "setPlaybackParameters(speed=${playbackParameters.speed}, pitch=${playbackParameters.pitch})")
    }

    override fun getPlaybackParameters(): PlaybackParameters = playbackParameters

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        Log.d(TAG, "setSkipSilenceEnabled($skipSilenceEnabled) — no-op for native sink")
    }

    override fun getSkipSilenceEnabled(): Boolean = false

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        Log.d(TAG, "setAudioAttributes() — no-op for native sink")
    }

    override fun setAudioSessionId(audioSessionId: Int) {
        Log.d(TAG, "setAudioSessionId($audioSessionId) — no-op for native sink")
    }

    override fun setAuxEffectInfo(auxEffectInfo: androidx.media3.common.AuxEffectInfo) {
        Log.d(TAG, "setAuxEffectInfo() — no-op for native sink")
    }

    override fun enableTunnelingV21() {
        Log.d(TAG, "enableTunnelingV21() — no-op for native sink")
    }

    override fun disableTunneling() {
        Log.d(TAG, "disableTunneling() — no-op for native sink")
    }

    override fun setVolume(volume: Float) {
        Log.d(TAG, "setVolume($volume) — no-op for native sink (volume handled by VolumeManager)")
    }

    override fun pause() {
        Log.d(TAG, "pause() — state=$state")
        if (nativeStarted) {
            nativeEngine.pause()
        }
        state = State.PAUSED
    }

    override fun flush() {
        Log.d(TAG, "flush() — state=$state, writtenBytes=$writtenBytes")
        // Flush means "discard pending data, prepare for new data at a new position".
        // We do NOT stop the native engine — just note the flush point so
        // getCurrentPositionUs() resets relative to the new seek position.
        writtenBytesAtFlush = writtenBytes

        // Reset ended state so the sink can accept new data
        if (state == State.ENDED || state == State.ENDING) {
            state = State.CONFIGURED
        }
        // If we were PLAYING, stay PLAYING — ExoPlayer will call handleBuffer() next
    }

    override fun reset() {
        Log.d(TAG, "reset() — state=$state, nativeStarted=$nativeStarted")
        // Full reset: stop the native engine and return to IDLE
        if (nativeStarted) {
            nativeEngine.stop()
            nativeStarted = false
            Log.d(TAG, "nativeEngine.stop() called from reset()")
        }
        writtenBytes = 0
        writtenBytesAtFlush = 0
        state = State.IDLE
    }

    override fun getAudioAttributes(): AudioAttributes? = null

    override fun getAudioTrackBufferSizeUs(): Long = 0L

    // Media3 1.2.0+
    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        Log.d(TAG, "setOutputStreamOffsetUs($outputStreamOffsetUs)")
    }

    // Media3 1.4.0+ preferred device
    override fun setPreferredDevice(deviceInfo: android.media.AudioDeviceInfo?) {
        Log.d(TAG, "setPreferredDevice(${deviceInfo?.productName}) — no-op for native sink")
    }

    // Support for offload
    override fun setOffloadDelayPadding(delayFrames: Int, paddingFrames: Int) {
        Log.d(TAG, "setOffloadDelayPadding(delay=$delayFrames, padding=$paddingFrames) — no-op")
    }
}
