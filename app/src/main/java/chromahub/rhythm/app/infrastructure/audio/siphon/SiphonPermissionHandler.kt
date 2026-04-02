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
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            permissionReceiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )

        if (usbManager.hasPermission(device)) {
            callback(true)
            return
        }
        
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
