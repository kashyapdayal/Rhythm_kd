package chromahub.rhythm.app.infrastructure.tile

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Monitors current audio routing and maps it to TileStateManager.AudioRouting.
 * Supports Android 8+ (API 26+) with enhanced detection for USB DACs.
 */
class AudioRoutingMonitor(context: Context) {
    
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    
    /**
     * Get the current audio routing type.
     * Priority: USB > Bluetooth > Wired > HDMI > Speaker
     */
    fun getCurrentRouting(): TileStateManager.AudioRouting {
        val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        } else {
            return TileStateManager.AudioRouting.UNKNOWN
        }
        
        // Priority-based detection
        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_ACCESSORY,
                AudioDeviceInfo.TYPE_USB_HEADSET -> {
                    return if (chromahub.rhythm.app.infrastructure.audio.AudioRoutingManager.activeMode == chromahub.rhythm.app.infrastructure.audio.AudioOutputMode.HIRES_HARDWARE) {
                        TileStateManager.AudioRouting.HI_RES_USB
                    } else {
                        TileStateManager.AudioRouting.USB_DAC
                    }
                }
            }
        }
        
        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                    return TileStateManager.AudioRouting.BLUETOOTH
                }
            }
        }
        
        // API 31+ has TYPE_BLE_* types
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (device in devices) {
                @Suppress("DEPRECATION")
                when (device.type) {
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_BLE_SPEAKER -> {
                        return TileStateManager.AudioRouting.BLUETOOTH
                    }
                }
            }
        }
        
        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> {
                    return TileStateManager.AudioRouting.WIRED_HEADPHONES
                }
            }
        }
        
        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_HDMI,
                AudioDeviceInfo.TYPE_HDMI_ARC,
                AudioDeviceInfo.TYPE_HDMI_EARC -> {
                    return TileStateManager.AudioRouting.HDMI
                }
            }
        }
        
        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> {
                    return TileStateManager.AudioRouting.SPEAKER
                }
            }
        }
        
        return TileStateManager.AudioRouting.UNKNOWN
    }
    
    /**
     * Check if a USB audio device is connected.
     */
    fun isUsbDacConnected(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.any { device ->
            device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
            device.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }
    
    /**
     * Get a human-readable description of current routing.
     */
    fun getRoutingDescription(): String {
        val routing = getCurrentRouting()
        return TileStateManager.getRoutingName(routing)
    }
}

