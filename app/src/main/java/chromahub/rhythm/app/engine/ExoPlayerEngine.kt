// [SIPHON_CUSTOM_ENGINE]
package chromahub.rhythm.app.engine

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

@OptIn(UnstableApi::class)
class ExoPlayerEngine(private val context: Context) : AudioEngine {
    
    // NOTE: For now, this creates a new instance.
    // In actual integration, you may want to pass your existing ExoPlayer instance here
    // or link it to your existing media session service.
    private val player: ExoPlayer = ExoPlayer.Builder(context).build()

    override fun load(uri: Uri, context: Context) {
        val mediaItem = MediaItem.fromUri(uri)
        player.setMediaItem(mediaItem)
        player.prepare()
    }

    override fun play() {
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun stop() {
        player.stop()
    }

    override fun seek(positionMs: Long) {
        player.seekTo(positionMs)
    }

    override fun release() {
        player.release()
    }

    override val currentPositionMs: Long
        get() = player.currentPosition
        
    override val durationMs: Long
        get() = player.duration

    override val isPlaying: Boolean
        get() = player.isPlaying
}
