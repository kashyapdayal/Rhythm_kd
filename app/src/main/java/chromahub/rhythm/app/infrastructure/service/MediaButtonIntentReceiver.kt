package chromahub.rhythm.app.infrastructure.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class MediaButtonIntentReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "MediaButtonReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return

        val serviceIntent = Intent(context, MediaPlaybackService::class.java).apply {
            action = MediaPlaybackService.ACTION_MEDIA_BUTTON
            putExtras(intent)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed forwarding media button intent", e)
        }
    }
}
