package chromahub.rhythm.app.infrastructure.audio.usb

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VolumeAudioProcessorTest {

    private lateinit var processor: VolumeAudioProcessor

    @Before
    fun setUp() {
        processor = VolumeAudioProcessor()
    }

    @Test
    fun test16BitGain() {
        val format = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.setVolume(0.5f)
        assertTrue(processor.isActive)

        val inputData = shortArrayOf(10000, -10000, 32767, -32768)
        val inputBuffer = ByteBuffer.allocateDirect(inputData.size * 2).order(ByteOrder.nativeOrder())
        inputData.forEach { inputBuffer.putShort(it) }
        inputBuffer.flip()

        processor.queueInput(inputBuffer)
        val outputBuffer = processor.output
        
        assertEquals(5000.toShort(), outputBuffer.short)
        assertEquals((-5000).toShort(), outputBuffer.short)
        assertEquals(16383.toShort(), outputBuffer.short)
        assertEquals((-16384).toShort(), outputBuffer.short)
    }

    @Test
    fun test24BitGain() {
        val format = AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_24BIT)
        processor.configure(format)
        processor.setVolume(0.5f)

        // 24-bit PCM: e.g. 100000 -> 50000
        val sample1 = 100000
        val sample2 = -100000
        val sample3 = 8388607  // Max 24-bit
        val sample4 = -8388608 // Min 24-bit

        val inputBuffer = ByteBuffer.allocateDirect(4 * 3).order(ByteOrder.nativeOrder())
        write24BitSample(inputBuffer, sample1)
        write24BitSample(inputBuffer, sample2)
        write24BitSample(inputBuffer, sample3)
        write24BitSample(inputBuffer, sample4)
        inputBuffer.flip()

        processor.queueInput(inputBuffer)
        val outputBuffer = processor.output

        assertEquals(50000, read24BitSample(outputBuffer))
        assertEquals(-50000, read24BitSample(outputBuffer))
        assertEquals(4194303, read24BitSample(outputBuffer))
        assertEquals(-4194304, read24BitSample(outputBuffer))
    }

    @Test
    fun test32BitGain() {
        val format = AudioProcessor.AudioFormat(96000, 2, C.ENCODING_PCM_32BIT)
        processor.configure(format)
        processor.setVolume(0.25f)

        val inputData = intArrayOf(1000000, -1000000, Int.MAX_VALUE, Int.MIN_VALUE)
        val inputBuffer = ByteBuffer.allocateDirect(inputData.size * 4).order(ByteOrder.nativeOrder())
        inputData.forEach { inputBuffer.putInt(it) }
        inputBuffer.flip()

        processor.queueInput(inputBuffer)
        val outputBuffer = processor.output

        assertEquals(250000, outputBuffer.int)
        assertEquals(-250000, outputBuffer.int)
        assertEquals(Int.MAX_VALUE / 4, outputBuffer.int)
        assertEquals(Int.MIN_VALUE / 4, outputBuffer.int)
    }

    @Test
    fun testFloatGain() {
        val format = AudioProcessor.AudioFormat(192000, 2, C.ENCODING_PCM_FLOAT)
        processor.configure(format)
        processor.setVolume(0.5f)

        val inputData = floatArrayOf(1.0f, -1.0f, 0.5f, -0.5f)
        val inputBuffer = ByteBuffer.allocateDirect(inputData.size * 4).order(ByteOrder.nativeOrder())
        inputData.forEach { inputBuffer.putFloat(it) }
        inputBuffer.flip()

        processor.queueInput(inputBuffer)
        val outputBuffer = processor.output

        assertEquals(0.5f, outputBuffer.float, 0.0001f)
        assertEquals(-0.5f, outputBuffer.float, 0.0001f)
        assertEquals(0.25f, outputBuffer.float, 0.0001f)
        assertEquals(-0.25f, outputBuffer.float, 0.0001f)
    }

    private fun write24BitSample(buffer: ByteBuffer, sample: Int) {
        buffer.put((sample and 0xFF).toByte())
        buffer.put(((sample shr 8) and 0xFF).toByte())
        buffer.put(((sample shr 16) and 0xFF).toByte())
    }

    private fun read24BitSample(buffer: ByteBuffer): Int {
        val b1 = buffer.get().toInt() and 0xFF
        val b2 = buffer.get().toInt() and 0xFF
        val b3 = buffer.get().toInt()
        return (b1) or (b2 shl 8) or (b3 shl 16) // Sign extension via b3
    }
}
