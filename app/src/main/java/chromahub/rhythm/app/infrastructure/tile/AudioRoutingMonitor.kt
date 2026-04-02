package chromahub.rhythm.app.infrastructure.tile

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import chromahub.rhythm.app.BuildConfig

/**
 * Optimized audio routing monitor with aggressive caching.
 * Only updates routing when BroadcastReceiver fires, avoiding expensive AudioManager queries.
 */
class AudioRoutingMonitor(private val context: Context) {
    
    companion object {
        private const val TAG = "AudioRouting"
    }
    
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    
    // Cached routing state - updated only via broadcast events
    @Volatile
    private var cachedRouting: TileStateManager.AudioRouting? = null
    
    /**
     * Get current audio routing with caching.
     * Call updateCachedRouting() when audio device changes occur.
     */
    fun getCurrentRouting(): TileStateManager.AudioRouting {
        // Return cached value if available
        cachedRouting?.let { return it }
        
        // First-time detection or cache invalidation
        return updateCachedRouting()
    }
    
    /**
     * Force refresh of routing state.
     * Call this from BroadcastReceiver events only.
     */
    fun updateCachedRouting(): TileStateManager.AudioRouting {
        val routing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            detectRouting()
        } else {
            TileStateManager.AudioRouting.UNKNOWN
        }
        
        if (cachedRouting != routing) {
            cachedRouting = routing
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Routing updated: ${TileStateManager.getRoutingName(routing)}")
            }
        }
        
        return routing
    }
    
    @RequiresApi(Build.VERSION_CODES.M)
    private fun detectRouting(): TileStateManager.AudioRouting {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        
        // Priority order: USB > Bluetooth > Wired > HDMI > Speaker
        
        // Check USB devices first (highest priority for audiophile users)
        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_ACCESSORY,
                AudioDeviceInfo.TYPE_USB_HEADSET -> {
                    return if (isHiResCapable(device)) {
                        TileStateManager.AudioRouting.HI_RES_USB
                    } else {
                        TileStateManager.AudioRouting.USB_DAC
                    }
                }
            }
        }
        
        // Check Bluetooth
        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                    return TileStateManager.AudioRouting.BLUETOOTH
                }
            }
        }
        
        // Check BLE devices (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (device in devices) {
                when (device.type) {
                    31, // TYPE_BLE_HEADSET
                    30  // TYPE_BLE_SPEAKER
                    -> {
                        return TileStateManager.AudioRouting.BLUETOOTH
                    }
                }
            }
        }
        
        // Check wired headphones
        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> {
                    return TileStateManager.AudioRouting.WIRED_HEADPHONES
                }
            }
        }
        
        // Check HDMI
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (device in devices) {
                when (device.type) {
                    AudioDeviceInfo.TYPE_HDMI -> {
                        return TileStateManager.AudioRouting.HDMI
                    }
                    // HDMI ARC/eARC (Android 10+)
                    else -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            if (device.type == 10 || device.type == 29) { // TYPE_HDMI_ARC, TYPE_HDMI_EARC
                                return TileStateManager.AudioRouting.HDMI
                            }
                        }
                    }
                }
            }
        }
        
        // Check built-in speaker (fallback)
        for (device in devices) {
            if (device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                return TileStateManager.AudioRouting.SPEAKER
            }
        }
        
        return TileStateManager.AudioRouting.UNKNOWN
    }
    
    @RequiresApi(Build.VERSION_CODES.M)
    private fun isHiResCapable(device: AudioDeviceInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        
        // Check if device supports high sample rates (>48kHz)
        val sampleRates = device.sampleRates
        if (sampleRates != null && sampleRates.isNotEmpty()) {
            return sampleRates.any { it >= 96000 }
        }
        
        return false
    }
    
    /**
     * Invalidate cache - call when audio routing might have changed.
     */
    fun invalidateCache() {
        cachedRouting = null
    }
}
