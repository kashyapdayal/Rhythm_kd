package chromahub.rhythm.app.shared.data.model

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.audioQualityDataStore by preferencesDataStore(name = "audio_quality_settings")

class AudioQualityDataStore(private val context: Context) {
    
    companion object {
        val USB_EXCLUSIVE_MODE = booleanPreferencesKey("usb_exclusive_mode")
        val REPLAY_GAIN_MODE = stringPreferencesKey("replay_gain_mode") // "OFF", "TRACK", "ALBUM"
        val PREVENT_CLIPPING = booleanPreferencesKey("prevent_clipping")
        val BITSTREAM_PASSTHROUGH = booleanPreferencesKey("bitstream_passthrough")
        val SMART_VOLUME_NORMALIZATION = booleanPreferencesKey("smart_volume_normalization")
        val TRIM_SILENCE = booleanPreferencesKey("trim_silence")
        val TRUE_WAVEFORM_ENABLED = booleanPreferencesKey("true_waveform_enabled")
        
        // Advanced USB Settings
        val USB_PERFORMANCE_MODE = booleanPreferencesKey("usb_performance_mode")
        val USB_VOLUME_LOCK_MODE = stringPreferencesKey("usb_volume_lock_mode") // "Lock all", "Lock only DSD", "Not locked"
        val USB_BUS_SPEED = stringPreferencesKey("usb_bus_speed") // "Default", "High Speed", "Full Speed"
        val USB_BIT_DEPTH = stringPreferencesKey("usb_bit_depth") // "Default", "16-bit", "24-bit", "32-bit"
        val USB_RELEASE_BANDWIDTH = booleanPreferencesKey("usb_release_bandwidth")
        val USB_KEEP_BACKEND_ACTIVE = booleanPreferencesKey("usb_keep_backend_active")
        
        val BIT_PERFECT_MODE = booleanPreferencesKey("bit_perfect_mode")
        val AUDIO_ROUTING_MODE = stringPreferencesKey("audio_routing_mode") // "default", "app", "system", "hardware"
        
        // High-Resolution Audio Mode (master switch)
        val HI_RES_AUDIO_MODE = booleanPreferencesKey("hi_res_audio_mode")
        // Volume control mode: "hardware" (UAC2 control transfer) or "software" (float multiply + dither)
        val VOLUME_CONTROL_MODE = stringPreferencesKey("volume_control_mode")

        // USB device state (written by service-owned UsbAudioManager, read by UI)
        val USB_DEVICE_CONNECTED = booleanPreferencesKey("usb_device_connected")
        val USB_DEVICE_NAME = stringPreferencesKey("usb_device_name")
        val USB_DEVICE_MAX_SAMPLE_RATE = intPreferencesKey("usb_device_max_sample_rate")
        val USB_DEVICE_HI_RES_CAPABLE = booleanPreferencesKey("usb_device_hi_res_capable")
        val USB_DEVICE_KEY = stringPreferencesKey("usb_device_key") // "productName_deviceId"
    }

    val trueWaveformEnabled: Flow<Boolean> = context.audioQualityDataStore.data
        .map { preferences -> preferences[TRUE_WAVEFORM_ENABLED] ?: false }

    val usbExclusiveModeEnabled: Flow<Boolean> = context.audioQualityDataStore.data
        .map { preferences -> preferences[USB_EXCLUSIVE_MODE] ?: false }

    val replayGainMode: Flow<String> = context.audioQualityDataStore.data
        .map { preferences -> preferences[REPLAY_GAIN_MODE] ?: "OFF" }

    val preventClippingEnabled: Flow<Boolean> = context.audioQualityDataStore.data
        .map { preferences -> preferences[PREVENT_CLIPPING] ?: true }

    val bitstreamPassthroughEnabled: Flow<Boolean> = context.audioQualityDataStore.data
        .map { preferences -> preferences[BITSTREAM_PASSTHROUGH] ?: false }

    val smartVolumeNormalizationEnabled: Flow<Boolean> = context.audioQualityDataStore.data
        .map { preferences -> preferences[SMART_VOLUME_NORMALIZATION] ?: false }

    val trimSilenceEnabled: Flow<Boolean> = context.audioQualityDataStore.data
        .map { preferences -> preferences[TRIM_SILENCE] ?: false }

    val usbPerformanceModeEnabled: Flow<Boolean> = context.audioQualityDataStore.data
        .map { preferences -> preferences[USB_PERFORMANCE_MODE] ?: false }

    val usbVolumeLockMode: Flow<String> = context.audioQualityDataStore.data
        .map { preferences -> preferences[USB_VOLUME_LOCK_MODE] ?: "Not locked" }

    val usbBusSpeed: Flow<String> = context.audioQualityDataStore.data
        .map { preferences -> preferences[USB_BUS_SPEED] ?: "Default" }

    val usbBitDepth: Flow<String> = context.audioQualityDataStore.data
        .map { preferences -> preferences[USB_BIT_DEPTH] ?: "Default" }

    val usbReleaseBandwidthEnabled: Flow<Boolean> = context.audioQualityDataStore.data
        .map { preferences -> preferences[USB_RELEASE_BANDWIDTH] ?: false }

    val usbKeepBackendActiveEnabled: Flow<Boolean> = context.audioQualityDataStore.data
        .map { preferences -> preferences[USB_KEEP_BACKEND_ACTIVE] ?: false }

    val bitPerfectMode: Flow<Boolean> = context.audioQualityDataStore.data
        .map { preferences -> preferences[BIT_PERFECT_MODE] ?: false }

    val audioRoutingMode: Flow<String> = context.audioQualityDataStore.data
        .map { preferences -> preferences[AUDIO_ROUTING_MODE] ?: "software" }
    
    // High-Resolution Audio Mode (master switch) - when OFF, USB routing is disabled
    val hiResAudioMode: Flow<Boolean> = context.audioQualityDataStore.data
        .map { preferences -> preferences[HI_RES_AUDIO_MODE] ?: false }
    
    // Volume control mode: "hardware" or "software"
    val volumeControlMode: Flow<String> = context.audioQualityDataStore.data
        .map { preferences -> preferences[VOLUME_CONTROL_MODE] ?: "hardware" }

    // USB device state (read-only for UI; written by service)
    val usbDeviceConnected: Flow<Boolean> = context.audioQualityDataStore.data
        .map { preferences -> preferences[USB_DEVICE_CONNECTED] ?: false }

    val usbDeviceName: Flow<String> = context.audioQualityDataStore.data
        .map { preferences -> preferences[USB_DEVICE_NAME] ?: "" }

    val usbDeviceMaxSampleRate: Flow<Int> = context.audioQualityDataStore.data
        .map { preferences -> preferences[USB_DEVICE_MAX_SAMPLE_RATE] ?: 48000 }

    val usbDeviceHiResCapable: Flow<Boolean> = context.audioQualityDataStore.data
        .map { preferences -> preferences[USB_DEVICE_HI_RES_CAPABLE] ?: false }

    val usbDeviceKey: Flow<String> = context.audioQualityDataStore.data
        .map { preferences -> preferences[USB_DEVICE_KEY] ?: "" }

    suspend fun setTrueWaveformEnabled(enabled: Boolean) {
        context.audioQualityDataStore.edit { preferences ->
            preferences[TRUE_WAVEFORM_ENABLED] = enabled
        }
    }

    suspend fun setUsbExclusiveMode(enabled: Boolean) {
        context.audioQualityDataStore.edit { preferences ->
            preferences[USB_EXCLUSIVE_MODE] = enabled
        }
    }

    suspend fun setReplayGainMode(mode: String) {
        context.audioQualityDataStore.edit { preferences ->
            preferences[REPLAY_GAIN_MODE] = mode
        }
    }

    suspend fun setPreventClipping(enabled: Boolean) {
        context.audioQualityDataStore.edit { preferences ->
            preferences[PREVENT_CLIPPING] = enabled
        }
    }

    suspend fun setBitstreamPassthrough(enabled: Boolean) {
        context.audioQualityDataStore.edit { preferences ->
            preferences[BITSTREAM_PASSTHROUGH] = enabled
        }
    }

    suspend fun setSmartVolumeNormalization(enabled: Boolean) {
        context.audioQualityDataStore.edit { preferences ->
            preferences[SMART_VOLUME_NORMALIZATION] = enabled
        }
    }

    suspend fun setTrimSilence(enabled: Boolean) {
        context.audioQualityDataStore.edit { preferences ->
            preferences[TRIM_SILENCE] = enabled
        }
    }

    suspend fun setUsbPerformanceMode(enabled: Boolean) {
        context.audioQualityDataStore.edit { preferences ->
            preferences[USB_PERFORMANCE_MODE] = enabled
        }
    }

    suspend fun setUsbVolumeLockMode(mode: String) {
        context.audioQualityDataStore.edit { preferences ->
            preferences[USB_VOLUME_LOCK_MODE] = mode
        }
    }

    suspend fun setUsbBusSpeed(speed: String) {
        context.audioQualityDataStore.edit { preferences ->
            preferences[USB_BUS_SPEED] = speed
        }
    }

    suspend fun setUsbBitDepth(depth: String) {
        context.audioQualityDataStore.edit { preferences ->
            preferences[USB_BIT_DEPTH] = depth
        }
    }

    suspend fun setUsbReleaseBandwidth(enabled: Boolean) {
        context.audioQualityDataStore.edit { preferences ->
            preferences[USB_RELEASE_BANDWIDTH] = enabled
        }
    }

    suspend fun setUsbKeepBackendActive(enabled: Boolean) {
        context.audioQualityDataStore.edit { preferences ->
            preferences[USB_KEEP_BACKEND_ACTIVE] = enabled
        }
    }

    suspend fun setBitPerfectMode(enabled: Boolean) {
        context.audioQualityDataStore.edit { preferences ->
            preferences[BIT_PERFECT_MODE] = enabled
        }
    }

    suspend fun setAudioRoutingMode(mode: String) {
        context.audioQualityDataStore.edit { preferences ->
            preferences[AUDIO_ROUTING_MODE] = mode
        }
    }
    
    suspend fun setHiResAudioMode(enabled: Boolean) {
        context.audioQualityDataStore.edit { preferences ->
            preferences[HI_RES_AUDIO_MODE] = enabled
        }
    }
    
    suspend fun setVolumeControlMode(mode: String) {
        context.audioQualityDataStore.edit { preferences ->
            preferences[VOLUME_CONTROL_MODE] = mode
        }
    }

    /**
     * Called by UsbAudioManager (service-owned) when a USB DAC is connected.
     * Persists device info so UI composables can read it without their own UsbAudioManager.
     */
    suspend fun setUsbDeviceInfo(
        connected: Boolean,
        name: String = "",
        maxSampleRate: Int = 48000,
        hiResCapable: Boolean = false,
        deviceKey: String = ""
    ) {
        context.audioQualityDataStore.edit { preferences ->
            preferences[USB_DEVICE_CONNECTED] = connected
            preferences[USB_DEVICE_NAME] = name
            preferences[USB_DEVICE_MAX_SAMPLE_RATE] = maxSampleRate
            preferences[USB_DEVICE_HI_RES_CAPABLE] = hiResCapable
            preferences[USB_DEVICE_KEY] = deviceKey
        }
    }
}
