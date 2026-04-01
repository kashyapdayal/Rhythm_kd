// [SIPHON_CUSTOM_ENGINE]
package chromahub.rhythm.app.engine

import android.content.Context
import android.net.Uri
import androidx.annotation.Keep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class NativeUsbEngine(private val context: Context) : AudioEngine {

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs

    init {
        System.loadLibrary("siphon_engine")
    }

    // JNI External Method Stubs
    private external fun nativeInit()
    private external fun nativeLoad(fd: Int)
    private external fun nativePlay()
    private external fun nativePause()
    private external fun nativeStop()
    private external fun nativeSeek(positionMs: Long)
    private external fun nativeRelease()
    private external fun nativeGetDuration(): Long
    private external fun nativeIsPlaying(): Boolean

    override fun load(uri: Uri, context: Context) {
        try {
            val parcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
            val fd = parcelFileDescriptor?.detachFd() ?: -1
            if (fd != -1) {
                nativeLoad(fd)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun play() {
        nativePlay()
    }

    override fun pause() {
        nativePause()
    }

    override fun stop() {
        nativeStop()
    }

    override fun seek(positionMs: Long) {
        nativeSeek(positionMs)
    }

    override fun release() {
        nativeRelease()
    }

    override val currentPositionMs: Long
        get() = _positionMs.value

    override val durationMs: Long
        get() = nativeGetDuration()

    override val isPlaying: Boolean
        get() = nativeIsPlaying()

    // Called from JNI on decoder thread tick (every ~100ms)
    @Keep
    fun onPositionUpdate(positionMs: Long) {
        _positionMs.value = positionMs
    }
}
