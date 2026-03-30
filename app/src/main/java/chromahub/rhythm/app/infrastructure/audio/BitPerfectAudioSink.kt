package chromahub.rhythm.app.infrastructure.audio

import android.content.Context
import android.media.AudioAttributes as AndroidAudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.AudioDeviceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.common.AudioAttributes
import androidx.media3.common.audio.AudioProcessor
import chromahub.rhythm.app.infrastructure.audio.usb.VolumeAudioProcessor

/**
 * Factory for creating AudioSink instances configured for bit-perfect playback
 * and true USB exclusive mode (direct hardware offload).
 */
@OptIn(UnstableApi::class)
object BitPerfectAudioSink {
    
    private const val TAG = "BitPerfectAudioSink"
    
    // Rhythm audio processors are passed in create() and should not be stored as singletons
    // to avoid interference between crossfading players.

    /**
     * Create AudioSink with bit-perfect configuration and Rhythm audio effects
     */
    @Suppress("DEPRECATION")
    @android.annotation.SuppressLint("WrongConstant")
    fun create(
        context: Context, 
        enableBitPerfect: Boolean,
        bassBoostProcessor: RhythmBassBoostProcessor? = null,
        spatializationProcessor: RhythmSpatializationProcessor? = null,
        replayGainProcessor: RhythmReplayGainProcessor? = null,
        usbDeviceProvider: (() -> AudioDeviceInfo?)? = null,
        isExclusiveModeProvider: (() -> Boolean)? = null
    ): AudioSink {
        Log.d(TAG, "Creating AudioSink (bit-perfect: $enableBitPerfect, Rhythm effects: ${bassBoostProcessor != null || spatializationProcessor != null || replayGainProcessor != null})")
        
        // Initialize the software volume fallback
        val volProcessor = VolumeAudioProcessor()
        
        val builder = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(true)
        
        // Custom AudioTrackProvider for True USB Exclusive Mode
        // Uses reflection to avoid compile errors if AudioTrackProvider is not available
        // in the current Media3 version. The routing (setPreferredDevice) is also handled
        // by RhythmPlayerEngine at the ExoPlayer level, so this is an enhanced path.
        try {
            val providerClass = Class.forName("androidx.media3.exoplayer.audio.DefaultAudioSink\$AudioTrackProvider")
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                providerClass.classLoader,
                arrayOf(providerClass)
            ) { _, method, args ->
                if (method.name == "getAudioTrack") {
                    var audioFormat = args?.firstOrNull { it is AudioFormat } as? AudioFormat
                    val bufferSize = args?.firstOrNull { it is Int } as? Int ?: 0
                    val device = usbDeviceProvider?.invoke()
                    val exclusiveActive = isExclusiveModeProvider?.invoke() ?: false

                    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val nativeSampleRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000

                    if (audioFormat == null) {
                        Log.e(TAG, "AudioFormat missing from getAudioTrack arguments! Use fallback.")
                        audioFormat = AudioFormat.Builder()
                            .setSampleRate(nativeSampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                    } else {
                        // Fix sample rate for gapless transitions
                        if (audioFormat.sampleRate != nativeSampleRate) {
                            audioFormat = AudioFormat.Builder()
                                .setSampleRate(audioFormat.sampleRate) // Explicitly match source content
                                .setEncoding(audioFormat.encoding)
                                .setChannelMask(audioFormat.channelMask)
                                .build()
                        }
                    }

                    val track: AudioTrack = try {
                        val minBufferSize = AudioTrack.getMinBufferSize(
                            audioFormat.sampleRate,
                            audioFormat.channelMask,
                            audioFormat.encoding
                        )
                        
                        val trackBufferSize = if (exclusiveActive && minBufferSize > 0) {
                            minBufferSize * 4
                        } else if (bufferSize > 0) {
                            bufferSize
                        } else {
                            minBufferSize * 2
                        }

                        val trackBuilder = AudioTrack.Builder()
                            .setAudioFormat(audioFormat)
                            .setBufferSizeInBytes(trackBufferSize)
                            .setTransferMode(AudioTrack.MODE_STREAM)

                        val sessionId = args?.firstOrNull { it is Int && it != bufferSize } as? Int
                        if (sessionId != null && sessionId != AudioManager.AUDIO_SESSION_ID_GENERATE) {
                            trackBuilder.setSessionId(sessionId)
                        }

                        val attrsBuilder = AndroidAudioAttributes.Builder()
                            .setUsage(AndroidAudioAttributes.USAGE_MEDIA)
                            .setContentType(AndroidAudioAttributes.CONTENT_TYPE_MUSIC)
                            // Bug 5 FIX: Aurisys bypass requested automatically via USAGE_MEDIA + CONTENT_TYPE_MUSIC

                        if (exclusiveActive && device != null) {
                            attrsBuilder.setFlags(AndroidAudioAttributes.FLAG_LOW_LATENCY)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                            }
                        }

                        trackBuilder.setAudioAttributes(attrsBuilder.build())
                        trackBuilder.build()
                    } catch (e: Exception) {
                        Log.e(TAG, "AudioTrack.Builder threw exception! Falling back to minimum viable AudioTrack.", e)
                        val minBufSize = AudioTrack.getMinBufferSize(nativeSampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                        try {
                            @Suppress("DEPRECATION")
    @android.annotation.SuppressLint("WrongConstant")
                            AudioTrack(
                                AudioManager.STREAM_MUSIC,
                                nativeSampleRate,
                                AudioFormat.CHANNEL_OUT_STEREO,
                                AudioFormat.ENCODING_PCM_16BIT,
                                if (minBufSize > 0) minBufSize else 8192,
                                AudioTrack.MODE_STREAM
                            )
                        } catch (legacyError: Exception) {
                            throw IllegalStateException("AudioTrack build failed: format=$audioFormat", legacyError)
                        }
                    }

                    // If FLAG_FAST is denied, retry without FLAG_LOW_LATENCY
                    val finalTrack = if (track.state == AudioTrack.STATE_UNINITIALIZED) {
                        Log.e(TAG, "AudioTrack state == STATE_UNINITIALIZED! Retrying without exclusive flags.")
                        track.release()
                        try {
                            val attrsBuilder = AndroidAudioAttributes.Builder()
                                .setUsage(AndroidAudioAttributes.USAGE_MEDIA)
                                .setContentType(AndroidAudioAttributes.CONTENT_TYPE_MUSIC)
                                // Bug 5 FIX: Aurisys bypass requested automatically via USAGE_MEDIA + CONTENT_TYPE_MUSIC
                            
                            AudioTrack.Builder()
                                .setAudioFormat(audioFormat)
                                .setAudioAttributes(attrsBuilder.build())
                                .setBufferSizeInBytes(if (bufferSize > 0) bufferSize else 1024 * 16)
                                .setTransferMode(AudioTrack.MODE_STREAM)
                                .build()
                        } catch (e: Exception) {
                            Log.e(TAG, "Absolute fallback failed", e)
                            val minBufSize = AudioTrack.getMinBufferSize(nativeSampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                            try {
                                @Suppress("DEPRECATION")
    @android.annotation.SuppressLint("WrongConstant")
                                AudioTrack(
                                    AudioManager.STREAM_MUSIC,
                                    nativeSampleRate,
                                    AudioFormat.CHANNEL_OUT_STEREO,
                                    AudioFormat.ENCODING_PCM_16BIT,
                                    if (minBufSize > 0) minBufSize else 8192,
                                    AudioTrack.MODE_STREAM
                                )
                            } catch (legacyError: Exception) {
                                throw IllegalStateException("AudioTrack build failed: format=$audioFormat", legacyError)
                            }
                        }
                    } else {
                        track
                    }

                    if (finalTrack.state == AudioTrack.STATE_INITIALIZED && device != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            finalTrack.setPreferredDevice(device)
                        }
                    }

                    volProcessor.setBypass(enableBitPerfect && exclusiveActive)
                    return@newProxyInstance finalTrack
                } else if (method.name == "getAudioTrackChannelConfig") {
                    // Media3 calls this to map channel count → AudioFormat channel mask.
                    // The return type is int (primitive), so returning null causes an
                    // NPE during auto-unboxing. We must return a valid channel config.
                    val channelCount = args?.firstOrNull() as? Int ?: 2
                    val config = when (channelCount) {
                        1 -> AudioFormat.CHANNEL_OUT_MONO
                        2 -> AudioFormat.CHANNEL_OUT_STEREO
                        3 -> AudioFormat.CHANNEL_OUT_STEREO or AudioFormat.CHANNEL_OUT_FRONT_CENTER
                        4 -> AudioFormat.CHANNEL_OUT_QUAD
                        6 -> AudioFormat.CHANNEL_OUT_5POINT1
                        8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
                        else -> AudioFormat.CHANNEL_OUT_STEREO
                    }
                    Log.d(TAG, "getAudioTrackChannelConfig($channelCount) → $config")
                    config
                } else if (method.name == "toString") {
                    "BitPerfectAudioTrackProviderProxy"
                } else if (method.name == "hashCode") {
                    System.identityHashCode(this)
                } else if (method.name == "equals") {
                    args?.get(0) === this
                } else {
                    // Safety net: for any other method with a primitive return type,
                    // return a type-safe default instead of null to avoid NPE.
                    val returnType = method.returnType
                    when {
                        returnType == Int::class.javaPrimitiveType -> 0
                        returnType == Long::class.javaPrimitiveType -> 0L
                        returnType == Boolean::class.javaPrimitiveType -> false
                        returnType == Float::class.javaPrimitiveType -> 0f
                        returnType == Double::class.javaPrimitiveType -> 0.0
                        else -> {
                            Log.w(TAG, "Unhandled proxy method: ${method.name}, returning null")
                            null
                        }
                    }
                }
            }

            val setMethod = DefaultAudioSink.Builder::class.java.getMethod("setAudioTrackProvider", providerClass)
            setMethod.invoke(builder, proxy)
            Log.i(TAG, "Successfully injected custom AudioTrackProvider via reflection")
        } catch (e: ClassNotFoundException) {
            Log.d(TAG, "AudioTrackProvider not available in this Media3 version — using default audio routing")
        } catch (e: NoSuchMethodException) {
            Log.d(TAG, "setAudioTrackProvider method not available — using default audio routing")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize AudioTrackProvider", e)
        }

        // Configure audio processor chain
        val processors = mutableListOf<AudioProcessor>()
        
        // Always include the software volume processor in the chain first
        // It stays inactive (activeVolume = 1.0) when hardware volume works or mode is normal
        processors.add(volProcessor)

        if (replayGainProcessor != null) {
            processors.add(replayGainProcessor)
            Log.d(TAG, "Added Rhythm ReplayGain processor")
        }
        
        if (bassBoostProcessor != null) {
            processors.add(bassBoostProcessor)
            Log.d(TAG, "Added Rhythm bass boost processor")
        }
        
        if (spatializationProcessor != null) {
            processors.add(spatializationProcessor)
            Log.d(TAG, "Added Rhythm spatialization processor")
        }
        
        if (processors.isNotEmpty() || enableBitPerfect) {
            builder.setAudioProcessorChain(
                DefaultAudioSink.DefaultAudioProcessorChain(
                    *processors.toTypedArray()
                )
            )
        }
        
        return builder.build()
    }
    
    // Helper methods for accessing processors from external components if needed
    // Note: These now require a specific AudioSink instance to be effective in a multi-player setup
    // For now, we keep them as stubs or remove them if not used.
    fun isSampleRateSupported(sampleRate: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return sampleRate in listOf(44100, 48000)
        }
        val supported = sampleRate in listOf(44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000)
        return supported
    }

    fun logPlaybackFormat(format: Format) {
        val sampleRate = if (format.sampleRate != Format.NO_VALUE) format.sampleRate else "unknown"
        val channels = if (format.channelCount != Format.NO_VALUE) format.channelCount else "unknown"
        val bitDepth = when (format.pcmEncoding) {
            C.ENCODING_PCM_8BIT -> "8-bit"
            C.ENCODING_PCM_16BIT -> "16-bit"
            C.ENCODING_PCM_24BIT -> "24-bit"
            C.ENCODING_PCM_32BIT -> "32-bit"
            C.ENCODING_PCM_FLOAT -> "32-bit float"
            else -> "unknown"
        }
        
        Log.i(TAG, "=== Bit-Perfect Playback ===")
        Log.i(TAG, "Sample Rate: ${sampleRate}Hz")
        Log.i(TAG, "Channels: $channels")
        Log.i(TAG, "Bit Depth: $bitDepth")
        Log.i(TAG, "Codec: ${format.sampleMimeType ?: "unknown"}")
        Log.i(TAG, "==========================")
    }

    fun getChannelMask(channelCount: Int): Int {
        return when (channelCount) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            6 -> AudioFormat.CHANNEL_OUT_5POINT1
            8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
            else -> AudioFormat.CHANNEL_OUT_STEREO
        }
    }
}

