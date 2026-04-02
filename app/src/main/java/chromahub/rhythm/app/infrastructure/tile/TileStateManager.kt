package chromahub.rhythm.app.infrastructure.tile

/**
 * Pure Kotlin state manager for Quick Settings Tile.
 * This class manages the logical state of the tile and produces
 * TileState objects that the TileService can apply to the actual tile.
 */
class TileStateManager {

    /**
     * Tile display state, matching Android's Tile.STATE_* constants.
     */
    enum class State {
        ACTIVE,      // Playing - Tile.STATE_ACTIVE
        INACTIVE,    // Paused - Tile.STATE_INACTIVE
        UNAVAILABLE  // No session - Tile.STATE_UNAVAILABLE
    }

    /**
     * Audio routing types for subtitle display.
     */
    enum class AudioRouting {
        BLUETOOTH,
        SPEAKER,
        WIRED_HEADPHONES,
        USB_DAC,
        HI_RES_USB,
        HDMI,
        UNKNOWN
    }

    /**
     * Icon to display on the tile.
     */
    enum class IconType {
        PLAY,
        PAUSE
    }

    /**
     * Complete tile state ready for rendering.
     */
    data class TileState(
        val state: State,
        val label: String,
        val subtitle: String?,
        val iconType: IconType
    )

    companion object {
        private const val MAX_LABEL_LENGTH = 20
        private const val DEFAULT_LABEL_UNAVAILABLE = "Rhythm"
        private const val DEFAULT_LABEL_PLAY = "Play"
        private const val DEFAULT_LABEL_PAUSE = "Pause"

        /**
         * Get human-readable name for audio routing type.
         */
        fun getRoutingName(routing: AudioRouting): String = when (routing) {
            AudioRouting.BLUETOOTH -> "Bluetooth"
            AudioRouting.SPEAKER -> "Speaker"
            AudioRouting.WIRED_HEADPHONES -> "Wired"
            AudioRouting.USB_DAC -> "USB DAC"
            AudioRouting.HI_RES_USB -> "Hi-Res USB"
            AudioRouting.HDMI -> "HDMI"
            AudioRouting.UNKNOWN -> "Audio"
        }

        /**
         * Truncate track title to max length with ellipsis.
         */
        fun truncateTitle(title: String?, maxLength: Int = MAX_LABEL_LENGTH): String {
            if (title.isNullOrBlank()) return ""
            return if (title.length <= maxLength) {
                title
            } else {
                title.take(maxLength - 1) + "…"
            }
        }
    }

    // Current state
    private var isPlaying: Boolean = false
    private var hasSession: Boolean = false
    private var trackTitle: String? = null
    private var currentRouting: AudioRouting = AudioRouting.UNKNOWN

    /**
     * Update playback state.
     */
    fun setPlaybackState(playing: Boolean, sessionActive: Boolean) {
        isPlaying = playing
        hasSession = sessionActive
    }

    /**
     * Update current track title.
     */
    fun setTrackTitle(title: String?) {
        trackTitle = title
    }

    /**
     * Get current track title.
     */
    fun getTrackTitle(): String? {
        return trackTitle
    }

    /**
     * Update audio routing.
     */
    fun setAudioRouting(routing: AudioRouting) {
        currentRouting = routing
    }

    /**
     * Get current tile state for rendering.
     */
    fun getTileState(): TileState {
        // No active session
        if (!hasSession) {
            return TileState(
                state = State.UNAVAILABLE,
                label = DEFAULT_LABEL_UNAVAILABLE,
                subtitle = null,
                iconType = IconType.PLAY
            )
        }

        // Playing state
        if (isPlaying) {
            return TileState(
                state = State.ACTIVE,
                label = truncateTitle(trackTitle).ifEmpty { DEFAULT_LABEL_PAUSE },
                subtitle = getRoutingName(currentRouting),
                iconType = IconType.PAUSE
            )
        }

        // Paused state
        return TileState(
            state = State.INACTIVE,
            label = truncateTitle(trackTitle).ifEmpty { DEFAULT_LABEL_PLAY },
            subtitle = getRoutingName(currentRouting),
            iconType = IconType.PLAY
        )
    }

    /**
     * Reset all state to defaults.
     */
    fun reset() {
        isPlaying = false
        hasSession = false
        trackTitle = null
        currentRouting = AudioRouting.UNKNOWN
    }
}
