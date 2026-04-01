// [SIPHON_CUSTOM_ENGINE]
package chromahub.rhythm.app.engine

import android.content.Context
import android.net.Uri

class OutputRouter(private val context: Context) {
    
    // We expose activeEngine so external classes or UI can read state Flow flows (e.g. position)
    var activeEngine: AudioEngine? = null
        private set

    val exoEngine = ExoPlayerEngine(context)
    val nativeEngine = NativeUsbEngine(context)

    var exclusiveMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                switchEngine(value)
            }
        }

    init {
        // Start in normal mode default
        switchEngine(exclusiveMode)
    }

    private fun switchEngine(useNative: Boolean) {
        val wasPlaying = activeEngine?.isPlaying == true
        
        activeEngine?.pause() // Safely pause before transition 
        // We aren't fully releasing yet to allow seamless toggling,
        // but production constraints might mean releasing the previous engine here.

        activeEngine = if (useNative) nativeEngine else exoEngine
        
        // If it was playing, we leave it to the calling layer if they want to auto-resume,
        // or we could automatically issue play() here. 
    }

    fun play(uri: Uri) {
        activeEngine?.load(uri, context)
        activeEngine?.play()
    }

    fun pause() = activeEngine?.pause()
    
    fun stop() = activeEngine?.stop()
    
    fun seek(ms: Long) = activeEngine?.seek(ms)

    val currentPositionMs: Long
        get() = activeEngine?.currentPositionMs ?: 0L

    val durationMs: Long
        get() = activeEngine?.durationMs ?: 0L

    val isPlaying: Boolean
        get() = activeEngine?.isPlaying ?: false
        
    fun releaseAll() {
        exoEngine.release()
        nativeEngine.release()
    }
}
