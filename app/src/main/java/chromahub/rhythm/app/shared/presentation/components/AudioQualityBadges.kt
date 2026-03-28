package chromahub.rhythm.app.shared.presentation.components

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.rounded.SurroundSound

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import chromahub.rhythm.app.shared.data.model.Song
import chromahub.rhythm.app.util.AudioQualityDetector
import chromahub.rhythm.app.util.AudioFormatDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Quality level for badge styling
 */
private enum class QualityLevel {
    EXCELLENT,  // Studio Master, Dolby Atmos/TrueHD, DTS-HD MA
    GOOD,       // Hi-Res Lossless, CD Quality Lossless
    STANDARD    // Dolby Digital, DTS, High-quality lossy
}

/**
 * Composable that displays audio quality badges (Lossless, Dolby, Hi-Res, etc.)
 * Updated to use the enhanced AudioQualityDetector for more accurate quality categorization
 * 
 * QUALITY BADGE LOGIC:
 * - Badges are based on codec, sample rate, bit depth, and bitrate analysis
 * - Lossless formats (ALAC, FLAC, WAV) preserve original audio bit-perfectly
 * - Lossy formats (MP3, AAC, OGG) are NEVER shown as lossless regardless of bitrate
 * - Standard Lossless (CD Quality): 16-bit/44.1kHz - shows "LOSSLESS" badge
 * - High-Resolution Lossless: 24-bit/96kHz+ - shows "HI-RES LOSSLESS" badge
 * - Bit depth is calculated from bitrate or explicitly provided by the codec
 */
@Composable
fun AudioQualityBadges(
    song: Song,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Get enhanced audio quality information
    var audioQuality by remember(song.id) { mutableStateOf<AudioQualityDetector.AudioQuality?>(null) }
    var detectedFormatInfo by remember(song.id) { mutableStateOf<AudioFormatDetector.AudioFormatInfo?>(null) }
    var replayGainInfo by remember(song.id) { mutableStateOf<chromahub.rhythm.app.util.ReplayGainInfo?>(null) }

    val dataStore = remember { chromahub.rhythm.app.shared.data.model.AudioQualityDataStore(context) }
    val usbExclusive by dataStore.usbExclusiveModeEnabled.collectAsState(initial = false)
    val usbDeviceConnected by dataStore.usbDeviceConnected.collectAsState(initial = false)

    LaunchedEffect(song.id) {
        withContext(Dispatchers.IO) {
            try {
                // First try to get format info for codec detection
                // Pass song object for better bit depth calculation
                val formatInfo = AudioFormatDetector.detectFormat(context, song.uri, song)
                detectedFormatInfo = formatInfo
                
                // Fetch ReplayGain metadata
                replayGainInfo = chromahub.rhythm.app.util.ReplayGainParser.parseReplayGain(context, song.uri)

                // Use the enhanced quality detector with format info
                // Prefer Song's metadata when available as it's more reliable
                val bitrateKbps = song.bitrate?.takeIf { it > 0 }?.let { it / 1000 }
                    ?: formatInfo.bitrateKbps.takeIf { it > 0 }
                    ?: 0
                
                val sampleRateHz = song.sampleRate?.takeIf { it > 0 }
                    ?: formatInfo.sampleRateHz.takeIf { it > 0 }
                    ?: 0
                
                val channelCount = song.channels?.takeIf { it > 0 }
                    ?: formatInfo.channelCount.takeIf { it > 0 }
                    ?: 2
                
                Log.d("AudioQualityBadges", "Song metadata: bitrate=${song.bitrate}, sampleRate=${song.sampleRate}, channels=${song.channels}, codec=${song.codec}")
                Log.d("AudioQualityBadges", "Format info: codec=${formatInfo.codec}, " +
                        "sampleRate=${formatInfo.sampleRateHz}Hz, bitrate=${formatInfo.bitrateKbps}kbps, " +
                        "bitDepth=${formatInfo.bitDepth}-bit, channels=${formatInfo.channelCount}")
                Log.d("AudioQualityBadges", "Using for detection: codec=${formatInfo.codec}, " +
                        "sampleRate=${sampleRateHz}Hz, bitrate=${bitrateKbps}kbps, " +
                        "bitDepth=${formatInfo.bitDepth}-bit, channels=${channelCount}")
                
                audioQuality = AudioQualityDetector.detectQuality(
                    codec = formatInfo.codec,
                    sampleRateHz = sampleRateHz,
                    bitrateKbps = bitrateKbps,
                    bitDepth = formatInfo.bitDepth,
                    channelCount = channelCount
                )
                
                Log.d("AudioQualityBadges", "Badge quality result: ${audioQuality?.qualityType} - ${audioQuality?.qualityLabel}")
            } catch (e: Exception) {
                Log.e("AudioQualityBadges", "Failed to detect audio quality", e)
            }
        }
    }

    // Determine if any badges should be shown
    val shouldShowQualityBadges = audioQuality?.let { quality ->
        quality.isLossless || quality.isDolby || quality.isDTS || quality.isHiRes ||
        quality.qualityType != AudioQualityDetector.QualityType.LOSSY_COMPRESSED
    } ?: false
    
    val showFormatBadge = detectedFormatInfo != null && detectedFormatInfo?.codec != "Unknown"
    val showUsbBadge = usbExclusive && usbDeviceConnected
    val showReplayGainBadge = replayGainInfo?.isValid == true

    if (shouldShowQualityBadges || showFormatBadge || showUsbBadge || showReplayGainBadge) {
        // Use an Accompanist FlowRow equivalent or a scrollable Row
        Row(
            modifier = modifier
                .padding(horizontal = 4.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            
            // 1. USB Exclusive Badge
            if (showUsbBadge) {
                QualityBadge(
                    text = "USB EXCLUSIVE",
                    icon = Icons.Default.Settings,
                    qualityLevel = QualityLevel.EXCELLENT
                )
                Spacer(modifier = Modifier.width(6.dp))
            }

            // 2. Primary quality badge based on type
            if (shouldShowQualityBadges) {
                val quality = audioQuality!!
                when (quality.qualityType) {
                    AudioQualityDetector.QualityType.HI_RES_STUDIO_MASTER -> {
                        QualityBadge(
                            text = "STUDIO MASTER",
                            icon = Icons.Filled.HighQuality,
                            qualityLevel = QualityLevel.EXCELLENT
                        )
                    }

                    AudioQualityDetector.QualityType.DOLBY_LOSSLESS -> {
                        val badgeText = when {
                            quality.qualityLabel.contains("Atmos") -> "DOLBY ATMOS"
                            quality.qualityLabel.contains("TrueHD") -> "DOLBY TRUEHD"
                            else -> "DOLBY"
                        }
                        QualityBadge(
                            text = badgeText,
                            icon = Icons.Rounded.SurroundSound,
                            qualityLevel = QualityLevel.EXCELLENT
                        )
                    }
                    AudioQualityDetector.QualityType.DOLBY_LOSSY_SURROUND -> {
                        val badgeText = when {
                            quality.qualityLabel.contains("Plus") -> "DOLBY DIGITAL+"
                            else -> "DOLBY D"
                        }
                        QualityBadge(
                            text = badgeText,
                            icon = Icons.Rounded.SurroundSound,
                            qualityLevel = QualityLevel.STANDARD
                        )
                    }
                    AudioQualityDetector.QualityType.DTS_SURROUND -> {
                        val badgeText = when {
                            quality.isLossless -> "DTS-HD MA"
                            else -> "DTS"
                        }
                        QualityBadge(
                            text = badgeText,
                            icon = Icons.Rounded.SurroundSound,
                            qualityLevel = if (quality.isLossless) QualityLevel.EXCELLENT else QualityLevel.STANDARD
                        )
                    }
                    AudioQualityDetector.QualityType.LOSSLESS_SURROUND -> {
                        QualityBadge(
                            text = quality.qualityLabel.uppercase(),
                            icon = Icons.Rounded.SurroundSound,
                            qualityLevel = QualityLevel.EXCELLENT
                        )
                    }
                    AudioQualityDetector.QualityType.HI_RES_LOSSLESS -> {
                        QualityBadge(
                            text = "HI-RES LOSSLESS",
                            icon = Icons.Filled.HighQuality,
                            qualityLevel = QualityLevel.GOOD
                        )
                    }
                    AudioQualityDetector.QualityType.CD_QUALITY_LOSSLESS -> {
                        QualityBadge(
                            text = "LOSSLESS",
                            icon = Icons.Filled.HighQuality,
                            qualityLevel = QualityLevel.GOOD
                        )
                    }
                    AudioQualityDetector.QualityType.LOSSY_COMPRESSED -> {
                        if (quality.qualityDescription.contains("320")) {
                            QualityBadge(
                                text = "320K",
                                icon = Icons.Filled.HighQuality,
                                qualityLevel = QualityLevel.STANDARD
                            )
                        }
                    }
                    AudioQualityDetector.QualityType.UNKNOWN -> {}
                }
                Spacer(modifier = Modifier.width(6.dp))
            }
            
            // 3. Format Information Chip (Codec, Bitrate, Sample Rate)
            if (showFormatBadge) {
                val formatInfo = detectedFormatInfo!!
                val bitDepthStr = if (formatInfo.bitDepth > 0) "${formatInfo.bitDepth}-bit" else ""
                val sampleRateStr = if (formatInfo.sampleRateHz > 0) {
                    if (formatInfo.sampleRateHz % 1000 == 0) "${formatInfo.sampleRateHz / 1000}kHz"
                    else "${String.format(java.util.Locale.US, "%.1f", formatInfo.sampleRateHz / 1000.0)}kHz"
                } else ""
                val bitrateStr = if (formatInfo.bitrateKbps > 0) "${formatInfo.bitrateKbps} kbps" else ""
                
                val parts = listOf(formatInfo.codec, bitDepthStr, sampleRateStr, bitrateStr).filter { it.isNotEmpty() }
                val formatText = parts.joinToString(" • ")

                if (formatText.isNotEmpty()) {
                    QualityBadge(
                        text = formatText,
                        icon = Icons.Default.MusicNote,
                        qualityLevel = QualityLevel.STANDARD
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
            }

            // 4. ReplayGain Pill
            if (showReplayGainBadge) {
                val trackGain = replayGainInfo?.trackGain
                val albumGain = replayGainInfo?.albumGain
                val displayGain = trackGain ?: albumGain ?: 0f
                val sign = if (displayGain > 0) "+" else ""
                
                val currentContext = LocalContext.current
                
                QualityBadge(
                    text = "RG",
                    icon = Icons.Default.GraphicEq,
                    qualityLevel = QualityLevel.GOOD,
                    modifier = Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onLongPress = {
                                val tGain = trackGain?.let { sign + String.format(java.util.Locale.US, "%.1f", it) + " dB" } ?: "N/A"
                                val aGain = albumGain?.let { sign + String.format(java.util.Locale.US, "%.1f", it) + " dB" } ?: "N/A"
                                val msg = "ReplayGain: Track $tGain, Album $aGain"
                                android.widget.Toast.makeText(currentContext, msg, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                )
            }
        }
    }
}


/**
 * Material 3 Expressive Badge with gradient, animation, and dynamic colors
 */
@Composable
private fun QualityBadge(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    qualityLevel: QualityLevel,
    modifier: Modifier = Modifier
) {
    // Entrance animation
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        visible = true
    }
    
    // Pulsing animation for excellent quality
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (qualityLevel == QualityLevel.EXCELLENT) 1.05f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    
    // Material 3 Expressive colors with gradient
    val (gradientColors, contentColor) = when (qualityLevel) {
        QualityLevel.EXCELLENT -> listOf(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.primaryContainer,
        ) to MaterialTheme.colorScheme.onPrimaryContainer
        
        QualityLevel.GOOD -> listOf(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.secondaryContainer,
        ) to MaterialTheme.colorScheme.onSecondaryContainer
        
        QualityLevel.STANDARD -> listOf(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.tertiaryContainer,
        ) to MaterialTheme.colorScheme.onTertiaryContainer
    }
    
    AnimatedVisibility(
        visible = visible,
        // enter = fadeIn(animationSpec = tween(300)) + scaleIn(animationSpec = spring(
        //     dampingRatio = Spring.DampingRatioMediumBouncy,
        //     stiffness = Spring.StiffnessLow
        // )),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .scale(scale)
                .padding(horizontal = 4.dp),
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 2.dp,
            shadowElevation = if (qualityLevel == QualityLevel.EXCELLENT) 0.dp else 0.dp
        ) {
            Box(
                modifier = Modifier
                    .background(
                        brush = Brush.horizontalGradient(gradientColors)
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = contentColor
                    )
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.8.sp
                        ),
                        color = contentColor
                    )
                }
            }
        }
    }
}

/**
 * Legacy badge composable for backwards compatibility
 */
@Composable
private fun LegacyQualityBadge(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    qualityLevel: QualityLevel,
    modifier: Modifier = Modifier
) {
    val (containerColor, contentColor) = when (qualityLevel) {
        QualityLevel.EXCELLENT -> 
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        QualityLevel.GOOD -> 
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        QualityLevel.STANDARD -> 
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    }
    
    AssistChip(
        onClick = { },
        label = {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp
                )
            )
        },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = containerColor,
            labelColor = contentColor,
            leadingIconContentColor = contentColor
        ),
        border = null,
        modifier = modifier.height(28.dp)
    )
}
