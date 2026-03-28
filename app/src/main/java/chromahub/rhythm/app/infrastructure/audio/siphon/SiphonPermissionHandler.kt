package chromahub.rhythm.app.infrastructure.audio.siphon

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

class SiphonPermissionHandler(
    private val context: Context,
    private val usbManager: UsbManager
) {
    companion object {
        const val ACTION_USB_PERMISSION = 
            "chromahub.rhythm.app.USB_PERMISSION"
    }
    
    // CRITICAL FIX: USB permission is currently never requested.
    // This class MUST request permission before any connection attempt.
    
    private var callback: ((Boolean) -> Unit)? = null
    
    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val device: UsbDevice? = 
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                val granted = intent.getBooleanExtra(
                    UsbManager.EXTRA_PERMISSION_GRANTED, false)
                callback?.invoke(granted)
                callback = null
            }
        }
    }
    
    fun requestPermission(device: UsbDevice, callback: (Boolean) -> Unit) {
        this.callback = callback
        // Register receiver
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(permissionReceiver, filter, 
                Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(permissionReceiver, filter)
        }
        
        // Check if already have permission
        if (usbManager.hasPermission(device)) {
            callback(true)
            return
        }
        
        // Request permission — THIS IS WHAT WAS MISSING
        val permissionIntent = PendingIntent.getBroadcast(
            context, 0,
            Intent(ACTION_USB_PERMISSION).apply {
                setPackage(context.packageName)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        usbManager.requestPermission(device, permissionIntent)
    }
}
