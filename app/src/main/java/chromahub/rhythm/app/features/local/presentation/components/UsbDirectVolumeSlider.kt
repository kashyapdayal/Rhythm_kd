package chromahub.rhythm.app.features.local.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import chromahub.rhythm.app.util.HapticUtils
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.infrastructure.audio.siphon.GlobalVolumeController
import chromahub.rhythm.app.infrastructure.audio.siphon.VolumeController

@Composable
fun UsbDirectVolumeSlider(
    isUsbDirectActive: Boolean,
    modifier: Modifier = Modifier
) {
    val volumeDb by GlobalVolumeController.volumeDb.collectAsState()

    val sliderPosition = remember(volumeDb) {
        GlobalVolumeController.dbToSteps(volumeDb).toFloat()
    }

    AnimatedVisibility(
        visible = isUsbDirectActive,
        enter   = fadeIn() + slideInVertically { it / 2 },
        exit    = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text  = "USB Volume",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text  = if (volumeDb >= 0f) "0.0 dB"
                            else "%.1f dB".format(volumeDb),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector        = Icons.AutoMirrored.Rounded.VolumeDown,
                    contentDescription = "Volume down",
                    tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier           = Modifier.size(18.dp)
                )

                Slider(
                    value        = sliderPosition,
                    onValueChange = { newSteps ->
                        GlobalVolumeController.setFromSlider(newSteps.toInt())
                    },
                    valueRange   = 0f..VolumeController.STEPS.toFloat(),
                    steps        = VolumeController.STEPS - 1,
                    modifier     = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    colors       = SliderDefaults.colors(
                        thumbColor            = MaterialTheme.colorScheme.primary,
                        activeTrackColor      = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor    = MaterialTheme.colorScheme.surfaceVariant
                    )
                )

                Icon(
                    imageVector        = Icons.AutoMirrored.Rounded.VolumeUp,
                    contentDescription = "Volume up",
                    tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier           = Modifier.size(18.dp)
                )
            }

            Text(
                text  = "Direct USB · bypassing Android mixer",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}
