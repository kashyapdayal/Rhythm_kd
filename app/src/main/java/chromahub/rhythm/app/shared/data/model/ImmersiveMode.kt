package chromahub.rhythm.app.shared.data.model

enum class ImmersiveScope {
    OFF,           // bars always visible
    PLAYER_ONLY,   // immersive only on Now Playing screen
    WHOLE_APP      // immersive everywhere
}

enum class ImmersiveBehaviour {
    STICKY,        // transient bars on swipe — auto-hide
    NON_STICKY     // bars stay after swipe until user acts
}

data class ImmersiveConfig(
    val scope: ImmersiveScope = ImmersiveScope.OFF,
    val behaviour: ImmersiveBehaviour = ImmersiveBehaviour.STICKY
)
