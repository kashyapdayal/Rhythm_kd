package chromahub.rhythm.app.infrastructure.audio.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import chromahub.rhythm.app.infrastructure.audio.native.NativeAudioEngine

/**
 * Manages direct USB DAC communication for Bit-Perfect playback.
 * Bypasses the Android audio mixer by claiming the USB interface directly.
 */
class UsbDirectEngine(
    private val context: Context,
    private val nativeEngine: NativeAudioEngine
) {
    companion object {
        private const val TAG = "UsbDirectEngine"
        private const val ACTION_USB_PERMISSION = "chromahub.rhythm.app.USB_PERMISSION"
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    
    private var currentDevice: UsbDevice? = null

    /**
     * Start direct USB mode for the given device.
     */
    fun startDirectMode(device: UsbDevice): Boolean {
        if (!usbManager.hasPermission(device)) {
            requestPermission(device)
            return false
        }
 
        val success = claimInterface(device)
        if (success) {
            val descriptors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    // Use reflection to satisfy compiler in case SDK metadata is inconsistent
                    val method = device.javaClass.getMethod("getRawDescriptors")
                    method.invoke(device) as? ByteArray ?: byteArrayOf()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get USB descriptors via reflection", e)
                    byteArrayOf()
                }
            } else {
                byteArrayOf()
            }
            if (descriptors.isNotEmpty()) {
                nativeEngine.setUsbDescriptors(descriptors)
            }
        }
        return success
    }

    /**
     * Stop direct USB mode and release the interface.
     */
    fun stopDirectMode() {
        usbInterface?.let {
            usbConnection?.releaseInterface(it)
            Log.d(TAG, "USB interface released")
        }
        usbConnection?.close()
        usbConnection = null
        usbInterface = null
        currentDevice = null
        
        nativeEngine.setUsbFileDescriptor(-1)
    }

    private fun requestPermission(device: UsbDevice) {
        val permissionIntent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun claimInterface(device: UsbDevice): Boolean {
        // Find the Audio Streaming interface
        var streamingInterface: UsbInterface? = null
        
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            // USB_CLASS_AUDIO = 1, USB_SUBCLASS_AUDIOSTREAMING = 2
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO && 
                iface.interfaceSubclass == 2) {
                streamingInterface = iface
                break
            }
        }

        if (streamingInterface == null) {
            Log.e(TAG, "No USB Audio Streaming interface found on device")
            return false
        }

        val connection = usbManager.openDevice(device)
        if (connection == null) {
            Log.e(TAG, "Failed to open USB device connection")
            return false
        }

        if (!connection.claimInterface(streamingInterface, true)) {
            Log.e(TAG, "Failed to claim USB interface")
            connection.close()
            return false
        }

        this.usbConnection = connection
        this.usbInterface = streamingInterface
        this.currentDevice = device

        // Pass the file descriptor to the native engine
        val fd = connection.fileDescriptor
        nativeEngine.setUsbFileDescriptor(fd)
        
        Log.d(TAG, "USB interface claimed successfully. FD=$fd")
        return true
    }
}
