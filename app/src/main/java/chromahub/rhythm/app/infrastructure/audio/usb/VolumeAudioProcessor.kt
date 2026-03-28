package chromahub.rhythm.app.infrastructure.audio.usb

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * An ExoPlayer AudioProcessor that applies a linear volume gain factor to raw PCM data.
 * Used as a fallback software volume control when USB absolute hardware volume is unavailable
 * but the direct/exclusive output path is active (bypassing Android's software mixer).
 *
 * Supports 16-bit, 24-bit, 32-bit integer, and 32-bit float PCM encodings to preserve
 * high-resolution bit-depths.
 */
@OptIn(UnstableApi::class)
class VolumeAudioProcessor : AudioProcessor {

    private var targetVolume: Double = 1.0
    private var activeVolume: Double = 1.0
    private var isBypassed: Boolean = false

    private var inputAudioFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var outputAudioFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET
    
    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded: Boolean = false

    /**
     * Sets the volume multiplier.
     * @param volume Linear volume scale from 0.0 to 1.0.
     */
    fun setVolume(volume: Float) {
        targetVolume = volume.toDouble().coerceIn(0.0, 1.0)
    }

    /**
     * Sets whether the processor should be bypassed (useful for bit-perfect output).
     */
    fun setBypass(bypass: Boolean) {
        isBypassed = bypass
    }

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT &&
            encoding != C.ENCODING_PCM_24BIT &&
            encoding != C.ENCODING_PCM_32BIT &&
            encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }

        this.inputAudioFormat = inputAudioFormat
        this.outputAudioFormat = inputAudioFormat
        return outputAudioFormat
    }

    override fun isActive(): Boolean {
        // Active if format is configured and NOT bypassed
        // We stay active even if targetVolume is 1.0 so volume can be changed dynamically
        return inputAudioFormat != AudioProcessor.AudioFormat.NOT_SET && !isBypassed
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) {
            return
        }

        activeVolume = targetVolume // snapshot

        if (activeVolume == 1.0) {
            // Pass-through while preserving the byte buffer contents properly
            val limit = inputBuffer.limit()
            val position = inputBuffer.position()
            val remaining = limit - position

            if (buffer.capacity() < remaining) {
                buffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder())
            } else {
                buffer.clear()
            }

            buffer.put(inputBuffer)
            buffer.flip()
            outputBuffer = buffer
            return
        }

        val limit = inputBuffer.limit()
        val position = inputBuffer.position()
        val remaining = limit - position

        if (buffer.capacity() < remaining) {
            buffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder())
        } else {
            buffer.clear()
        }

        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> process16Bit(inputBuffer, buffer, remaining)
            C.ENCODING_PCM_24BIT -> process24Bit(inputBuffer, buffer, remaining)
            C.ENCODING_PCM_32BIT -> process32Bit(inputBuffer, buffer, remaining)
            C.ENCODING_PCM_FLOAT -> processFloat(inputBuffer, buffer, remaining)
        }

        inputBuffer.position(limit)
        buffer.flip()
        outputBuffer = buffer
    }

    private fun process16Bit(input: ByteBuffer, output: ByteBuffer, bytes: Int) {
        for (i in 0 until bytes step 2) {
            val sample = input.short.toDouble()
            val scaled = (sample * activeVolume).toInt()
            output.putShort(scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }
    }

    private fun process24Bit(input: ByteBuffer, output: ByteBuffer, bytes: Int) {
        for (i in 0 until bytes step 3) {
            val b1 = input.get().toInt() and 0xFF
            val b2 = input.get().toInt() and 0xFF
            val b3 = input.get().toInt()
            // 24-bit is stored little-endian in ExoPlayer typically, sign extended
            val sample = (b1) or (b2 shl 8) or (b3 shl 16)
            
            val scaled = (sample.toDouble() * activeVolume).toInt().coerceIn(-8388608, 8388607)
            
            output.put((scaled and 0xFF).toByte())
            output.put(((scaled shr 8) and 0xFF).toByte())
            output.put(((scaled shr 16) and 0xFF).toByte())
        }
    }

    private fun process32Bit(input: ByteBuffer, output: ByteBuffer, bytes: Int) {
        for (i in 0 until bytes step 4) {
            val sample = input.int.toDouble()
            val scaled = (sample * activeVolume).toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
            output.putInt(scaled.toInt())
        }
    }

    private fun processFloat(input: ByteBuffer, output: ByteBuffer, bytes: Int) {
        for (i in 0 until bytes step 4) {
            val sample = input.float.toDouble()
            val scaled = sample * activeVolume
            // Float PCM is typically bounded -1.0 to +1.0, but we don't hard clip floats
            output.putFloat(scaled.toFloat())
        }
    }

    override fun queueEndOfStream() {
        inputEnded = true
        // Empty buffer marker to flush
    }

    @Deprecated("Deprecated in AudioProcessor", replaceWith = ReplaceWith("flush()"))
    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
    }

    override fun getOutput(): ByteBuffer {
        val output = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return output
    }

    override fun isEnded(): Boolean {
        return inputEnded && outputBuffer === AudioProcessor.EMPTY_BUFFER
    }

    override fun reset() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        buffer = AudioProcessor.EMPTY_BUFFER
        inputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        outputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        targetVolume = 1.0
        activeVolume = 1.0
    }
}
