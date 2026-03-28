package chromahub.rhythm.app.infrastructure.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import chromahub.rhythm.app.infrastructure.audio.usb.UsbAudioManager

/**
 * BroadcastReceiver for USB audio events.
 *
 * This receiver handles:
 * 1. USB audio device attachment → requests OS permission → triggers system dialog like HiBy
 * 2. USB permission result → notifies UsbAudioManager
 * 3. USB device detachment → cleans up exclusive mode and connection
 *
 * Registered in AndroidManifest.xml with USB_DEVICE_ATTACHED action to allow Android
 * to present the user with "Open Rhythm to handle USB-C Audio?" dialog automatically.
 */
class UsbAudioReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "RhythmUsbReceiver"
        
        // Singleton reference to the USB audio manager — set by MediaPlaybackService
        // This is needed because manifest-registered BroadcastReceivers create new instances
        @Volatile
        var usbAudioManagerInstance: UsbAudioManager? = null

        // Singleton reference to the SiphonSessionManager — set by MediaPlaybackService
        // Routes USB lifecycle events through the centralized gatekeeper
        @Volatile
        var sessionManagerInstance: chromahub.rhythm.app.infrastructure.audio.siphon.SiphonSessionManager? = null
        
        // Queue events that arrive before UsbAudioManager is ready (Bug 2 fix)
        private val pendingEvents = mutableListOf<Intent>()
        
        // Process queued events when manager becomes ready
        fun processPendingEvents() {
            synchronized(pendingEvents) {
                val manager = usbAudioManagerInstance
                if (manager != null) {
                    for (intent in pendingEvents) {
                        processEvent(manager, intent)
                    }
                    pendingEvents.clear()
                }
            }
        }
        
        private fun processEvent(manager: UsbAudioManager, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (device != null && isUsbAudioDevice(device)) {
                        Log.d(TAG, "USB audio device attached: ${device.productName}")
                        // Route through SiphonSessionManager first (centralized gatekeeper)
                        // It will check exclusive mode before deciding whether to claim
                        val sessionMgr = sessionManagerInstance
                        if (sessionMgr != null) {
                            sessionMgr.onDeviceArrived(device)
                        } else {
                            // Fallback: session manager not yet wired, use direct path
                            manager.onUsbDeviceAttached(device)
                        }
                    }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (device != null) {
                        Log.d(TAG, "USB audio device detached: ${device.productName}")
                        // Notify both session manager AND audio manager
                        sessionManagerInstance?.onDeviceRemoved(device)
                        manager.onUsbDeviceDetached()
                    }
                }

                UsbAudioManager.ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Log.d(TAG, "USB permission result for ${device?.productName}: granted=$granted")
                    if (device != null) {
                        sessionManagerInstance?.onPermissionResult(device, granted)
                        manager.onPermissionResult(device, granted)
                    }
                }
            }
        }
        
        /**
         * Checks if the USB device is an audio device (DAC, sound card, headphone amp).
         * USB Audio Device Class = 0x01.
         */
        private fun isUsbAudioDevice(device: UsbDevice): Boolean {
            if (device.deviceClass == 0x01) return true
            for (i in 0 until device.interfaceCount) {
                if (device.getInterface(i).interfaceClass == 0x01) return true
            }
            return false
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val manager = usbAudioManagerInstance
        if (manager == null) {
            // Bug 2 fix: Queue event if manager not ready, don't drop it
            synchronized(pendingEvents) {
                Log.d(TAG, "UsbAudioManager not yet initialized, queueing event: ${intent.action}")
                pendingEvents.add(Intent(intent))  // Copy intent to preserve it
            }
            if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                val device: UsbDevice? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                if (device != null) {
                    UsbAudioManager.persistPendingAttach(context, device)
                }
            }
            return
        }

        processEvent(manager, intent)
    }
}

