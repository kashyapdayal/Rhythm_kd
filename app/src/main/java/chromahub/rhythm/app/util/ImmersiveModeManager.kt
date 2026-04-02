package chromahub.rhythm.app.util

import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import chromahub.rhythm.app.shared.data.model.ImmersiveBehaviour
import chromahub.rhythm.app.shared.data.model.ImmersiveConfig
import chromahub.rhythm.app.shared.data.model.ImmersiveScope

class ImmersiveModeManager(private val activity: ComponentActivity) {

    private val window get() = activity.window
    private val decorView get() = window.decorView

    // Current applied state — used to restore after focus loss
    private var currentConfig: ImmersiveConfig = ImmersiveConfig()
    private var isPlayerScreenVisible = false

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun applyConfig(config: ImmersiveConfig) {
        currentConfig = config
        applyCurrentState()
    }

    fun onPlayerScreenVisibilityChanged(visible: Boolean) {
        isPlayerScreenVisible = visible
        applyCurrentState()
    }

    // Called from Activity.onWindowFocusChanged(hasFocus: Boolean)
    // CRITICAL: Android resets system bar visibility on EVERY focus loss/gain
    // (dialogs, notifications, app-switcher). Re-apply here every time.
    fun onWindowFocusChanged(hasFocus: Boolean) {
        if (hasFocus) {
            applyCurrentState()
        }
    }

    // Called from the Compose NavController's destination change listener
    // so immersive activates/deactivates as user navigates
    fun onDestinationChanged(route: String) {
        val isPlayer = route.contains("player", ignoreCase = true) ||
                       route.contains("now_playing", ignoreCase = true) ||
                       route.contains("nowplaying", ignoreCase = true)
        onPlayerScreenVisibilityChanged(isPlayer)
    }

    // -------------------------------------------------------------------------
    // Internal logic
    // -------------------------------------------------------------------------

    private fun applyCurrentState() {
        val shouldHide = when (currentConfig.scope) {
            ImmersiveScope.OFF         -> false
            ImmersiveScope.WHOLE_APP   -> true
            ImmersiveScope.PLAYER_ONLY -> isPlayerScreenVisible
        }

        if (shouldHide) hide(currentConfig.behaviour)
        else show()
    }

    private fun hide(behaviour: ImmersiveBehaviour) {
        // Don't attempt to hide in multi-window — system ignores it and
        // it can cause layout jumps when the call is silently rejected
        if (activity.isInMultiWindowMode) return

        val controller = WindowCompat.getInsetsController(window, decorView)

        // MUST set decor fits to false first — otherwise layout jumps when bars hide
        WindowCompat.setDecorFitsSystemWindows(window, false)

        controller.hide(WindowInsetsCompat.Type.systemBars())

        controller.systemBarsBehavior = when (behaviour) {
            ImmersiveBehaviour.STICKY ->
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ImmersiveBehaviour.NON_STICKY ->
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_BARS_BY_SWIPE
        }
        
        controller.isAppearanceLightStatusBars = false  // white icons on dark content
        controller.isAppearanceLightNavigationBars = false
    }

    private fun show() {
        val controller = WindowCompat.getInsetsController(window, decorView)
        controller.show(WindowInsetsCompat.Type.systemBars())
        // Don't reset setDecorFitsSystemWindows — app already targets SDK 35
        // where edge-to-edge is the enforced default. Resetting to true
        // would break the existing inset handling.
    }
}
