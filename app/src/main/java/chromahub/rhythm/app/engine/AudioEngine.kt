// [SIPHON_CUSTOM_ENGINE]
package chromahub.rhythm.app.engine

import android.content.Context
import android.net.Uri

interface AudioEngine {
    fun load(uri: Uri, context: Context)
    fun play()
    fun pause()
    fun stop()
    fun seek(positionMs: Long)
    fun release()
    
    val currentPositionMs: Long
    val durationMs: Long
    val isPlaying: Boolean
}
