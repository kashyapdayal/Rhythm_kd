package chromahub.rhythm.app.infrastructure.audio.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import chromahub.rhythm.app.shared.data.model.AudioQualityDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class UsbAudioManager(
    private val context: Context,
    private val audioQualityDataStore: AudioQualityDataStore? = null
) {
    companion object {
        private const val TAG = "UsbAudioManager"
        const val ACTION_USB_PERMISSION = UsbConnectionOrchestrator.ACTION_USB_PERMISSION
        private const val PREFS_NAME = "usb_audio_pending_events"
        private const val KEY_PENDING_VENDOR_ID = "pending_vendor_id"
        private const val KEY_PENDING_PRODUCT_ID = "pending_product_id"
        private const val KEY_PENDING_DEVICE_NAME = "pending_device_name"

        fun persistPendingAttach(context: Context, device: UsbDevice) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putInt(KEY_PENDING_VENDOR_ID, device.vendorId)
                .putInt(KEY_PENDING_PRODUCT_ID, device.productId)
                .putString(KEY_PENDING_DEVICE_NAME, device.productName ?: device.deviceName)
                .putLong("pending_attach_time", System.currentTimeMillis())
                .apply()
        }

        fun clearPendingAttach(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .remove(KEY_PENDING_VENDOR_ID)
                .remove(KEY_PENDING_PRODUCT_ID)
                .remove(KEY_PENDING_DEVICE_NAME)
                .remove("pending_attach_time")
                .apply()
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    val orchestrator = UsbConnectionOrchestrator(context, usbManager)
    private val settingsStore = audioQualityDataStore ?: AudioQualityDataStore(context)

    private val _connectedUsbDevice = MutableStateFlow<UsbDacInfo?>(null)
    val connectedUsbDevice: StateFlow<UsbDacInfo?> = _connectedUsbDevice.asStateFlow()

    private val _exclusiveModeActive = MutableStateFlow(false)
    val exclusiveModeActive: StateFlow<Boolean> = _exclusiveModeActive.asStateFlow()

    private val _usbPermissionGranted = MutableStateFlow(false)
    val usbPermissionGranted: StateFlow<Boolean> = _usbPermissionGranted.asStateFlow()

    var outputRouter: chromahub.rhythm.app.infrastructure.service.player.OutputRouter? = null

    private var currentUsbDevice: UsbDevice? = null

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            updateUsbDevices()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            updateUsbDevices()
        }
    }

    fun startMonitoring() {
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        updateUsbDevices()
        restorePendingAttachIfAny()

        scope.launch {
            orchestrator.state
                .collectLatest { state ->
                    // Bug 5 guard: publish state changes only after full enumeration is complete.
                    if (!state.isDeviceFullyEnumerated && state.connected) {
                        _exclusiveModeActive.value = false
                        return@collectLatest
                    }
                    _usbPermissionGranted.value = state.permissionGranted
                    _exclusiveModeActive.value = state.activeMode == UsbConnectionOrchestrator.EngineMode.USB_HOST_DIRECT
                }
        }

        // Bug 2 fix: Process any USB events that arrived before manager was ready
        chromahub.rhythm.app.infrastructure.audio.UsbAudioReceiver.processPendingEvents()
        
        orchestrator.onEnumerationComplete = { device, caps ->
            Log.d(TAG, "Enumeration complete — triggering OutputRouter")
            outputRouter?.onUsbDeviceAttached(device, caps)
                ?: Log.e(TAG, "outputRouter is null in UsbAudioManager!")
        }

        Log.d(TAG, "USB monitoring started")
    }

    fun stopMonitoring() {
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        deactivateExclusiveMode()
    }

    private fun updateUsbDevices() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val usb = devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }

        if (usb == null) {
            _connectedUsbDevice.value = null
            _exclusiveModeActive.value = false
            _usbPermissionGranted.value = false
            scope.launch { settingsStore.setUsbDeviceInfo(connected = false) }
            return
        }

        val rawUsb = findUsbDeviceForAudioDevice(usb)
        val info = UsbDacInfo(
            device = usb,
            name = usb.productName?.toString() ?: "USB Audio Device",
            sampleRates = usb.sampleRates,
            channelCounts = usb.channelCounts,
            encodings = usb.encodings,
            rawUsbDevice = rawUsb
        )
        _connectedUsbDevice.value = info
        scope.launch {
            settingsStore.setUsbDeviceInfo(
                connected = true,
                name = info.name,
                maxSampleRate = info.maxSampleRate,
                hiResCapable = info.isHiResCapable,
                deviceKey = "${info.device.productName}_${info.device.id}"
            )
        }
    }

    private fun findUsbDeviceForAudioDevice(audioDeviceInfo: AudioDeviceInfo): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { dev ->
            dev.productName == audioDeviceInfo.productName?.toString()
        }
    }

    fun onUsbDeviceAttached(device: UsbDevice) {
        currentUsbDevice = device
        clearPendingAttach(context)
        scope.launch {
            val enabled = settingsStore.usbExclusiveModeEnabled.first()
            orchestrator.onAttached(device, enabled)
        }
    }

    fun onPermissionResult(device: UsbDevice, granted: Boolean) {
        scope.launch {
            val exclusiveEnabled = settingsStore.usbExclusiveModeEnabled.first()
            orchestrator.onPermissionResult(device, granted, exclusiveEnabled)
        }
    }

    fun onUsbDeviceDetached() {
        currentUsbDevice = null
        orchestrator.onDetached()
        _usbPermissionGranted.value = false
        _exclusiveModeActive.value = false
        clearPendingAttach(context)
        Log.d(TAG, "USB audio device detached")
    }

    fun activateExclusiveMode(): Boolean {
        val d = currentUsbDevice ?: return false
        onUsbDeviceAttached(d)
        return true
    }

    fun deactivateExclusiveMode() {
        orchestrator.onDetached()
        _exclusiveModeActive.value = false
    }

    private fun restorePendingAttachIfAny() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val vendorId = prefs.getInt(KEY_PENDING_VENDOR_ID, -1)
        val productId = prefs.getInt(KEY_PENDING_PRODUCT_ID, -1)
        val timestamp = prefs.getLong("pending_attach_time", 0L)
        
        clearPendingAttach(context) // Immediately remove to prevent loop

        if (vendorId < 0 || productId < 0) return
        
        if (System.currentTimeMillis() - timestamp > 10000L) {
            Log.d(TAG, "Stale pending USB attach discarded")
            return
        }

        val pending = usbManager.deviceList.values.firstOrNull {
            it.vendorId == vendorId && it.productId == productId
        }
        if (pending != null) {
            Log.i(TAG, "Restoring persisted USB attach event for ${pending.productName}")
            onUsbDeviceAttached(pending)
        }
    }
    /**
     * Called when app is launched directly via manifest USB_DEVICE_ATTACHED intent.
     * This bypasses the BroadcastReceiver path and goes straight to attach handling.
     */
    fun handleDirectAttach(device: UsbDevice) {
        Log.d(TAG, "Direct attach from MainActivity: ${device.productName}")
        scope.launch {
            val enabled = settingsStore.usbExclusiveModeEnabled.first()
            if (isUsbAudioDevice(device)) {
                if (enabled) {
                    onUsbDeviceAttached(device)
                } else {
                    Log.d(TAG, "Exclusive Mode is OFF -- ignoring direct attach for ${device.productName}")
                }
            } else {
                Log.d(TAG, "Ignoring non-audio USB device: ${device.productName}")
            }
        }
    }

    private fun isUsbAudioDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == 0x01) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == android.hardware.usb.UsbConstants.USB_CLASS_AUDIO) {
                return true
            }
        }
        // Also match known DAC by VID/PID as fallback
        return device.vendorId == 0x3302 && device.productId == 0x3360
    }
}

data class UsbDacInfo(
    val device: AudioDeviceInfo,
    val name: String,
    val sampleRates: IntArray,
    val channelCounts: IntArray,
    val encodings: IntArray,
    val rawUsbDevice: UsbDevice? = null
) {
    val isHiResCapable: Boolean
        get() = sampleRates.any { it > 48000 } || sampleRates.isEmpty()

    val maxSampleRate: Int
        get() = sampleRates.maxOrNull() ?: 48000

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as UsbDacInfo
        return device.id == other.device.id
    }

    override fun hashCode(): Int = device.id
}
