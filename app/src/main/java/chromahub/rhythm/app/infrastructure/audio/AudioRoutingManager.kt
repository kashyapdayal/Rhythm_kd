package chromahub.rhythm.app.infrastructure.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log

enum class AudioOutputMode { NORMAL, HIRES_SOFTWARE, HIRES_HARDWARE }

class AudioRoutingManager(
    private val context: Context,
    private val playerEngine: Any // Abstracted to avoid circular ref without full context
) {
    private val TAG = "AUDIO_PIPELINE"
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    var currentMode = AudioOutputMode.NORMAL
        private set(value) { 
            field = value
            activeMode = value 
        }

    companion object {
        @Volatile var activeMode = AudioOutputMode.NORMAL
    }

    val activeUsbDevice: UsbDevice?
        get() = usbManager.deviceList.values.firstOrNull()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    Log.i(TAG, "USB_INIT: Attached DAC: ${device?.productName}")
                    handleUsbDeviceAttached(device)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.w(TAG, "USB_INIT: Detached DAC. Reverting to NORMAL mode.")
                    switchMode(AudioOutputMode.NORMAL)
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        context.registerReceiver(usbReceiver, filter)
    }

    private fun handleUsbDeviceAttached(device: UsbDevice?) {
        if (device == null) return
        if (!usbManager.hasPermission(device)) {
            Log.d(TAG, "USB_INIT: Requesting permission for ${device.deviceName}")
            return
        }
        setupHighResolutionModeIfEnabled()
    }

    fun switchMode(newMode: AudioOutputMode) {
        if (currentMode == newMode) return
        Log.i(TAG, "Transitioning audio mode: $currentMode -> $newMode")
        currentMode = newMode
    }

    private fun setupHighResolutionModeIfEnabled() {
        switchMode(AudioOutputMode.HIRES_HARDWARE)
    }
}

