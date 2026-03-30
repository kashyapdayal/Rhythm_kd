package chromahub.rhythm.app.shared.presentation.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOutQuart
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.viewmodel.compose.viewModel
import chromahub.rhythm.app.activities.RhythmGuardTimeoutActivity
import chromahub.rhythm.app.R
import chromahub.rhythm.app.core.domain.model.AppMode
import chromahub.rhythm.app.features.local.presentation.navigation.LocalNavigation
import chromahub.rhythm.app.features.streaming.presentation.navigation.StreamingNavigation
import chromahub.rhythm.app.shared.data.repository.UserPreferencesRepository
import chromahub.rhythm.app.shared.data.repository.PlaybackStatsRepository
import chromahub.rhythm.app.shared.data.repository.StatsTimeRange
import chromahub.rhythm.app.shared.presentation.viewmodel.AppModeViewModel
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import chromahub.rhythm.app.shared.presentation.viewmodel.ThemeViewModel
import chromahub.rhythm.app.shared.data.model.AppSettings
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Main navigation composable that handles switching between Local and Streaming modes.
 * Acts as the root navigation for the entire app.
 */
@Composable
fun RhythmNavigation(
    musicViewModel: MusicViewModel = viewModel(),
    themeViewModel: ThemeViewModel = viewModel(),
    modifier: Modifier = Modifier,
    navigateToSettingsTrigger: Boolean = false,
    onSettingsNavigationComplete: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val appSettings = remember { AppSettings.getInstance(context) }
    val appMode by appSettings.appMode.collectAsState()
    
    // Create a NavHostController that can be passed to both local and streaming navigation
    val rootNavController = rememberNavController()
    
    // Navigate to settings when triggered
    LaunchedEffect(navigateToSettingsTrigger) {
        if (navigateToSettingsTrigger) {
            rootNavController.navigate("settings")
            onSettingsNavigationComplete?.invoke()
        }
    }
    
    // Settings navigation callback that works for both modes
    val navigateToSettings: () -> Unit = {
        rootNavController.navigate("settings")
    }
    
    // Switch between Local and Streaming navigation based on app mode with animated transitions.
    // RhythmGuardWarningHost is mounted at root so warnings appear above all screens and bottom sheets.
    Box(modifier = modifier.fillMaxSize()) {
        NavHost(
            navController = rootNavController,
            startDestination = "main",
            modifier = Modifier.fillMaxSize()
        ) {
            composable("main") {
                AnimatedContent(
                    targetState = appMode,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        fadeIn(
                            animationSpec = tween(400)
                        ) + slideInHorizontally(
                            initialOffsetX = { if (targetState == "STREAMING") it else -it },
                            animationSpec = tween(500, easing = EaseInOutQuart)
                        ) togetherWith fadeOut(
                            animationSpec = tween(400)
                        ) + slideOutHorizontally(
                            targetOffsetX = { if (targetState == "STREAMING") -it else it },
                            animationSpec = tween(500, easing = EaseInOutQuart)
                        )
                    },
                    label = "appModeTransition"
                ) { mode ->
                    when (mode) {
                        "STREAMING" -> {
                            // Show streaming navigation with proper bottom nav
                            StreamingNavigation(
                                onNavigateToSettings = navigateToSettings,
                                onNavigateToPlayer = { /* TODO */ },
                                onSwitchToLocalMode = {
                                    // Switch back to local mode
                                    appSettings.setAppMode("LOCAL")
                                }
                            )
                        }

                        else -> {
                            // Use LocalNavigation for local mode (default)
                            LocalNavigation(
                                viewModel = musicViewModel,
                                themeViewModel = themeViewModel,
                                appSettings = appSettings
                            )
                        }
                    }
                }
            }

            // Settings screen (shared between both modes)
            composable("settings") {
                chromahub.rhythm.app.features.local.presentation.screens.settings.SettingsScreenWrapper(
                    onBack = {
                        rootNavController.popBackStack()
                    },
                    appSettings = appSettings,
                    navController = rootNavController,
                    musicViewModel = musicViewModel
                )
            }
        }

        RhythmGuardWarningHost(
            appSettings = appSettings,
            musicViewModel = musicViewModel
        )
    }
}

private enum class RhythmGuardWarningType {
    VOLUME,
    EXPOSURE
}

enum class RhythmGuardRiskLevel {
    LOW,
    MODERATE,
    HIGH,
    SEVERE
}

private data class RhythmGuardVolumeWarningDialogState(
    val mode: String,
    val currentVolumePercent: Int,
    val safeThresholdPercent: Int,
    val suggestedVolume: Float,
    val riskLevel: RhythmGuardRiskLevel
)

private data class RhythmGuardBreakDialogState(
    val mode: String,
    val estimatedTodayMinutes: Int,
    val recommendedDailyMinutes: Int,
    val riskLevel: RhythmGuardRiskLevel
)

@Composable
private fun RhythmGuardWarningHost(
    appSettings: AppSettings,
    musicViewModel: MusicViewModel
) {
    val context = LocalContext.current
    val playbackStatsRepository = remember(context) { PlaybackStatsRepository.getInstance(context) }

    val auraMode by appSettings.rhythmGuardMode.collectAsState()
    val auraAge by appSettings.rhythmGuardAge.collectAsState()
    val manualWarningsEnabled by appSettings.rhythmGuardManualWarningsEnabled.collectAsState()
    val manualVolumeThreshold by appSettings.rhythmGuardManualVolumeThreshold.collectAsState()
    val configuredAlertThresholdMinutes by appSettings.rhythmGuardAlertThresholdMinutes.collectAsState()
    val warningTimeoutMinutes by appSettings.rhythmGuardWarningTimeoutMinutes.collectAsState()
    val configuredBreakResumeMinutes by appSettings.rhythmGuardBreakResumeMinutes.collectAsState()
    val timeoutUntilMs by appSettings.rhythmGuardTimeoutUntilMs.collectAsState()
    val timeoutReason by appSettings.rhythmGuardTimeoutReason.collectAsState()
    val dailyListeningStats by appSettings.dailyListeningStats.collectAsState()
    val songsPlayed by appSettings.songsPlayed.collectAsState()
    val listeningTime by appSettings.listeningTime.collectAsState()

    val currentSong by musicViewModel.currentSong.collectAsState()
    val currentSystemVolume = rememberSystemMusicVolumeFraction(context)
    var todayExposureMinutes by remember { mutableIntStateOf(0) }

    val activePolicy = remember(auraAge) { appSettings.getRhythmGuardPolicy(auraAge) }
    val effectiveExposureLimitMinutes = if (auraMode == AppSettings.RHYTHM_GUARD_MODE_AUTO) {
        activePolicy.recommendedDailyMinutes
    } else if (configuredAlertThresholdMinutes > 0) {
        configuredAlertThresholdMinutes
    } else {
        activePolicy.recommendedDailyMinutes
    }
    val activeThreshold = if (auraMode == AppSettings.RHYTHM_GUARD_MODE_AUTO) {
        activePolicy.maxVolumeThreshold
    } else {
        manualVolumeThreshold
    }
    val activeThresholdPercent = (activeThreshold * 100f).toInt().coerceIn(0, 100)
    val currentVolumePercent = (currentSystemVolume * 100f).toInt().coerceIn(0, 100)
    val isListeningTimeoutActive = timeoutUntilMs > System.currentTimeMillis()

    val rulesEnabled = auraMode != AppSettings.RHYTHM_GUARD_MODE_OFF && currentSong != null
    val volumeWarningEnabled = when (auraMode) {
        AppSettings.RHYTHM_GUARD_MODE_AUTO -> false
        AppSettings.RHYTHM_GUARD_MODE_MANUAL -> manualWarningsEnabled
        else -> false
    }
    val exposureWarningEnabled = when (auraMode) {
        AppSettings.RHYTHM_GUARD_MODE_AUTO -> true
        AppSettings.RHYTHM_GUARD_MODE_MANUAL -> manualWarningsEnabled
        else -> false
    }
    val needsVolumeWarning =
        rulesEnabled && !isListeningTimeoutActive && volumeWarningEnabled && currentSystemVolume > activeThreshold
    val needsExposureWarning =
        rulesEnabled &&
            !isListeningTimeoutActive &&
            exposureWarningEnabled &&
            todayExposureMinutes > effectiveExposureLimitMinutes

    var volumeDialogState by remember { mutableStateOf<RhythmGuardVolumeWarningDialogState?>(null) }
    var breakDialogState by remember { mutableStateOf<RhythmGuardBreakDialogState?>(null) }
    var lastWarningType by remember { mutableStateOf<RhythmGuardWarningType?>(null) }
    var lastWarningAt by remember { mutableLongStateOf(0L) }
    var lastWarningVolumePercent by remember { mutableIntStateOf(-1) }
    var lastWarningExposureMinutes by remember { mutableIntStateOf(-1) }
    var breakResumeMinutes by remember(configuredBreakResumeMinutes) {
        mutableIntStateOf(configuredBreakResumeMinutes)
    }
    var resumeCountdownSeconds by remember { mutableLongStateOf(0L) }
    var timeoutStartedAtMs by remember { mutableLongStateOf(0L) }
    var lastAutoClampAtMs by remember { mutableLongStateOf(0L) }
    var lastAutoClampThresholdPercent by remember { mutableIntStateOf(-1) }
    var isBubbleOnLeft by remember { mutableStateOf(false) }
    var rawDragXDp by remember { mutableFloatStateOf(0f) }
    var bubbleOffsetYDp by remember { mutableFloatStateOf(0f) }

    val formattedTodayExposure = remember(todayExposureMinutes) {
        rhythmGuardFormatDurationFromMinutes(todayExposureMinutes)
    }
    val formattedExposureLimit = remember(effectiveExposureLimitMinutes) {
        rhythmGuardFormatDurationFromMinutes(effectiveExposureLimitMinutes)
    }

    LaunchedEffect(dailyListeningStats, songsPlayed, listeningTime) {
        val todaySummary = runCatching {
            playbackStatsRepository.loadSummary(StatsTimeRange.TODAY)
        }.getOrNull()
        todayExposureMinutes = ((todaySummary?.totalDurationMs ?: 0L) / 60000L)
            .toInt()
            .coerceAtLeast(0)
    }

    LaunchedEffect(auraMode, manualWarningsEnabled, currentSong) {
        if (auraMode == AppSettings.RHYTHM_GUARD_MODE_OFF) {
            volumeDialogState = null
            breakDialogState = null
            lastWarningType = null
            appSettings.clearRhythmGuardListeningTimeout()
            resumeCountdownSeconds = 0L
            timeoutStartedAtMs = 0L
            lastWarningVolumePercent = -1
            lastWarningExposureMinutes = -1
            lastAutoClampAtMs = 0L
            lastAutoClampThresholdPercent = -1
        }
        if (currentSong == null) {
            volumeDialogState = null
            breakDialogState = null
        }
        if (auraMode == AppSettings.RHYTHM_GUARD_MODE_MANUAL && !manualWarningsEnabled) {
            volumeDialogState = null
            breakDialogState = null
        }
    }

    LaunchedEffect(timeoutUntilMs, configuredBreakResumeMinutes, auraMode) {
        val now = System.currentTimeMillis()
        if (timeoutUntilMs <= now) {
            if (timeoutUntilMs > 0L) {
                appSettings.clearRhythmGuardListeningTimeout()
            }
            resumeCountdownSeconds = 0L
            timeoutStartedAtMs = 0L
            return@LaunchedEffect
        }

        musicViewModel.pauseMusic()
        if (timeoutStartedAtMs <= 0L || timeoutStartedAtMs >= timeoutUntilMs) {
            timeoutStartedAtMs = timeoutUntilMs - configuredBreakResumeMinutes.coerceIn(1, 180) * 60_000L
        }

        while (true) {
            val remainingSeconds = ((timeoutUntilMs - System.currentTimeMillis()) / 1000L)
                .coerceAtLeast(0L)
            resumeCountdownSeconds = remainingSeconds
            if (remainingSeconds <= 0L) break
            delay(1000)
        }

        val timeoutExpired = timeoutUntilMs <= System.currentTimeMillis()
        if (timeoutExpired) {
            appSettings.clearRhythmGuardListeningTimeout()
            if (auraMode == AppSettings.RHYTHM_GUARD_MODE_AUTO) {
                musicViewModel.resumeMusic()
            }
        }
        resumeCountdownSeconds = 0L
        timeoutStartedAtMs = 0L
    }

    LaunchedEffect(auraMode, auraAge) {
        if (auraMode == AppSettings.RHYTHM_GUARD_MODE_AUTO) {
            appSettings.applyRhythmGuardAutoProfileForAge(auraAge)
        }
    }

    LaunchedEffect(rulesEnabled, auraMode, currentSystemVolume, activeThreshold, auraAge) {
        if (!rulesEnabled || auraMode != AppSettings.RHYTHM_GUARD_MODE_AUTO) {
            return@LaunchedEffect
        }

        val volumeOvershoot = currentSystemVolume - activeThreshold
        if (volumeOvershoot > 0.01f) {
            val now = System.currentTimeMillis()
            val thresholdChanged = lastAutoClampThresholdPercent != activeThresholdPercent
            val largeOvershoot = volumeOvershoot > 0.03f
            val cooldownElapsed = now - lastAutoClampAtMs >= 1_500L

            if (!thresholdChanged && !largeOvershoot && !cooldownElapsed) {
                return@LaunchedEffect
            }

            setSystemMusicVolumeFraction(context, activeThreshold)
            musicViewModel.setVolume(activeThreshold)
            lastAutoClampAtMs = now
            lastAutoClampThresholdPercent = activeThresholdPercent
        }
    }

    LaunchedEffect(
        rulesEnabled,
        needsVolumeWarning,
        needsExposureWarning,
        auraMode,
        currentVolumePercent,
        activeThresholdPercent,
        todayExposureMinutes,
        effectiveExposureLimitMinutes,
        warningTimeoutMinutes
    ) {
        if (!rulesEnabled) {
            volumeDialogState = null
            breakDialogState = null
            if (auraMode == AppSettings.RHYTHM_GUARD_MODE_OFF) {
                appSettings.clearRhythmGuardListeningTimeout()
            }
            return@LaunchedEffect
        }

        if (isListeningTimeoutActive) {
            volumeDialogState = null
            breakDialogState = null
            return@LaunchedEffect
        }

        val warningType = when {
            needsVolumeWarning -> RhythmGuardWarningType.VOLUME
            needsExposureWarning -> RhythmGuardWarningType.EXPOSURE
            else -> null
        }

        if (warningType == null) {
            volumeDialogState = null
            breakDialogState = null
            return@LaunchedEffect
        }

        volumeDialogState?.let { existing ->
            if (!needsVolumeWarning || existing.mode != auraMode) {
                volumeDialogState = null
            }
        }
        breakDialogState?.let { existing ->
            if (!needsExposureWarning || existing.mode != auraMode) {
                breakDialogState = null
            }
        }

        val now = System.currentTimeMillis()
        val warningCooldownMs = warningTimeoutMinutes.coerceIn(1, 60) * 60 * 1000L
        val cooldownElapsed = now - lastWarningAt > warningCooldownMs
        val warningTypeChanged = warningType != lastWarningType
        val riskStepIncreased = when (warningType) {
            RhythmGuardWarningType.VOLUME -> currentVolumePercent >= (lastWarningVolumePercent + 5)
            RhythmGuardWarningType.EXPOSURE -> todayExposureMinutes >= (lastWarningExposureMinutes + 15)
        }
        val canShow = warningTypeChanged || cooldownElapsed || riskStepIncreased

        if (canShow && volumeDialogState == null && breakDialogState == null) {
            when (warningType) {
                RhythmGuardWarningType.VOLUME -> {
                    volumeDialogState = RhythmGuardVolumeWarningDialogState(
                        mode = auraMode,
                        currentVolumePercent = currentVolumePercent,
                        safeThresholdPercent = activeThresholdPercent,
                        suggestedVolume = activeThreshold,
                        riskLevel = rhythmGuardResolveRiskLevel(
                            currentVolumePercent = currentVolumePercent,
                            safeThresholdPercent = activeThresholdPercent,
                            exposureMinutes = todayExposureMinutes,
                            exposureLimitMinutes = effectiveExposureLimitMinutes
                        )
                    )
                }
                RhythmGuardWarningType.EXPOSURE -> {
                    if (auraMode == AppSettings.RHYTHM_GUARD_MODE_AUTO) {
                        val now = System.currentTimeMillis()
                        val safeBreakMinutes = configuredBreakResumeMinutes.coerceIn(1, 180)
                        val timeoutEnd = now + safeBreakMinutes * 60_000L
                        val reason = context.getString(
                            R.string.settings_rhythm_guard_timeout_reason_auto,
                            formattedTodayExposure,
                            formattedExposureLimit
                        )
                        timeoutStartedAtMs = now
                        appSettings.setRhythmGuardBreakResumeMinutes(safeBreakMinutes)
                        appSettings.setRhythmGuardListeningTimeout(timeoutEnd, reason)
                        musicViewModel.pauseMusic()
                        RhythmGuardTimeoutActivity.start(
                            context = context,
                            reason = reason,
                            timeoutUntilMs = timeoutEnd,
                            timeoutStartedAtMs = now
                        )
                    } else {
                        breakDialogState = RhythmGuardBreakDialogState(
                            mode = auraMode,
                            estimatedTodayMinutes = todayExposureMinutes,
                            recommendedDailyMinutes = effectiveExposureLimitMinutes,
                            riskLevel = rhythmGuardResolveRiskLevel(
                                currentVolumePercent = currentVolumePercent,
                                safeThresholdPercent = activeThresholdPercent,
                                exposureMinutes = todayExposureMinutes,
                                exposureLimitMinutes = effectiveExposureLimitMinutes
                            )
                        )
                    }
                }
            }
            lastWarningType = warningType
            lastWarningAt = now
            lastWarningVolumePercent = currentVolumePercent
            lastWarningExposureMinutes = todayExposureMinutes
        }
    }

    val volumeState = volumeDialogState
    if (volumeState != null) {
        AlertDialog(
            onDismissRequest = { volumeDialogState = null },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            iconContentColor = MaterialTheme.colorScheme.primary,
            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            textContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            icon = {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    text = context.getString(R.string.settings_rhythm_guard_warning_dialog_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = context.getString(
                            R.string.settings_rhythm_guard_warning_dialog_volume_message,
                            volumeState.currentVolumePercent,
                            volumeState.safeThresholdPercent
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = context.getString(
                            R.string.settings_rhythm_guard_risk_level_label,
                            rhythmGuardRiskLabel(context, volumeState.riskLevel)
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f)
                    )
                    Text(
                        text = context.getString(
                            R.string.settings_rhythm_guard_risk_action_label,
                            rhythmGuardRiskAction(context, volumeState.riskLevel)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.88f)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        setSystemMusicVolumeFraction(context, volumeState.suggestedVolume)
                        musicViewModel.setVolume(volumeState.suggestedVolume)
                        appSettings.setAudioNormalization(true)
                        appSettings.setReplayGain(true)
                        if (auraMode == AppSettings.RHYTHM_GUARD_MODE_AUTO) {
                            appSettings.applyRhythmGuardAutoProfileForAge(auraAge)
                        }
                        volumeDialogState = null
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(context.getString(R.string.settings_rhythm_guard_warning_dialog_apply))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { volumeDialogState = null }) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(context.getString(R.string.settings_rhythm_guard_warning_dialog_dismiss))
                }
            }
        )
    }

    val breakState = breakDialogState
    if (breakState != null) {
        AlertDialog(
            onDismissRequest = { breakDialogState = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            iconContentColor = MaterialTheme.colorScheme.secondary,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface,
            icon = {
                Icon(
                    imageVector = Icons.Filled.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary
                )
            },
            title = {
                Text(
                    text = context.getString(R.string.settings_rhythm_guard_break_dialog_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = context.getString(
                            R.string.settings_rhythm_guard_break_dialog_message,
                            formattedTodayExposure,
                            formattedExposureLimit
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = context.getString(
                            R.string.settings_rhythm_guard_risk_level_label,
                            rhythmGuardRiskLabel(context, breakState.riskLevel)
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.9f)
                    )
                    Text(
                        text = context.getString(
                            R.string.settings_rhythm_guard_risk_action_label,
                            rhythmGuardRiskAction(context, breakState.riskLevel)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.88f)
                    )
                    Text(
                        text = context.getString(R.string.settings_rhythm_guard_break_dialog_schedule_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(5, 10, 15, 30, 45, 60).forEach { option ->
                            FilterChip(
                                selected = breakResumeMinutes == option,
                                onClick = { breakResumeMinutes = option },
                                label = { Text(rhythmGuardFormatDurationFromMinutes(option)) }
                            )
                        }
                    }
                    FilledTonalButton(
                        onClick = {
                            musicViewModel.pauseMusic()
                            val now = System.currentTimeMillis()
                            val resumeDelayMs = breakResumeMinutes.toLong() * 60_000L
                            timeoutStartedAtMs = now
                            appSettings.setRhythmGuardListeningTimeout(
                                untilEpochMs = now + resumeDelayMs,
                                reason = context.getString(
                                    R.string.settings_rhythm_guard_timeout_reason_manual,
                                    formattedTodayExposure,
                                    formattedExposureLimit
                                )
                            )
                            appSettings.setRhythmGuardBreakResumeMinutes(breakResumeMinutes)
                            breakDialogState = null
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            context.getString(
                                R.string.settings_rhythm_guard_break_dialog_schedule_action,
                                rhythmGuardFormatDurationFromMinutes(breakResumeMinutes)
                            )
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        musicViewModel.pauseMusic()
                        appSettings.clearRhythmGuardListeningTimeout()
                        breakDialogState = null
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Pause,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(context.getString(R.string.settings_rhythm_guard_break_dialog_pause_only))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        musicViewModel.pauseMusic()
                        appSettings.clearRhythmGuardListeningTimeout()
                        breakDialogState = null
                        closeRhythmApp(context)
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(context.getString(R.string.settings_rhythm_guard_break_dialog_pause_close))
                }
            }
        )
    }

    val timeoutTotalSeconds = ((timeoutUntilMs - timeoutStartedAtMs).coerceAtLeast(1_000L) / 1000f)
    val timeoutElapsedSeconds = (timeoutTotalSeconds - resumeCountdownSeconds.toFloat()).coerceAtLeast(0f)
    val timeoutProgress = (timeoutElapsedSeconds / timeoutTotalSeconds).coerceIn(0f, 1f)
    val density = androidx.compose.ui.platform.LocalDensity.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        AnimatedVisibility(
            visible = timeoutUntilMs > System.currentTimeMillis() && resumeCountdownSeconds > 0L,
            enter = fadeIn(animationSpec = tween(220)) + scaleIn(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            ),
            exit = fadeOut(animationSpec = tween(200)) + scaleOut(animationSpec = tween(180))
        ) {
            val bubbleLabel = if (timeoutReason.isNotBlank()) timeoutReason else context.getString(
                R.string.settings_rhythm_guard_resume_countdown_label
            )

            RhythmGuardResumeCountdownBubble(
                countdownText = rhythmGuardFormatCountdown(resumeCountdownSeconds),
                progress = timeoutProgress,
                label = bubbleLabel,
                modifier = Modifier
                    .align(if (isBubbleOnLeft) Alignment.CenterStart else Alignment.CenterEnd)
                    .offset(x = rawDragXDp.dp, y = bubbleOffsetYDp.dp)
                    .pointerInput(timeoutReason, timeoutUntilMs, timeoutStartedAtMs) {
                        detectTapGestures(onTap = {
                            RhythmGuardTimeoutActivity.start(
                                context = context,
                                reason = timeoutReason.ifBlank {
                                    context.getString(R.string.settings_rhythm_guard_timeout_activity_default_reason)
                                },
                                timeoutUntilMs = timeoutUntilMs,
                                timeoutStartedAtMs = timeoutStartedAtMs
                            )
                        })
                    }
                    .pointerInput(isBubbleOnLeft) {
                        detectDragGestures(
                            onDragEnd = {
                                if (isBubbleOnLeft && rawDragXDp > 80f) {
                                    isBubbleOnLeft = false
                                } else if (!isBubbleOnLeft && rawDragXDp < -80f) {
                                    isBubbleOnLeft = true
                                }
                                rawDragXDp = 0f
                            }
                        ) { change, dragAmount ->
                            change.consume()
                            val deltaXDp = dragAmount.x / density.density
                            val deltaYDp = dragAmount.y / density.density
                            rawDragXDp += deltaXDp
                            bubbleOffsetYDp = (bubbleOffsetYDp + deltaYDp).coerceIn(-350f, 350f)
                        }
                    }
            )
        }
    }
}

fun rhythmGuardResolveRiskLevel(
    currentVolumePercent: Int,
    safeThresholdPercent: Int,
    exposureMinutes: Int,
    exposureLimitMinutes: Int
): RhythmGuardRiskLevel {
    val safeVolume = safeThresholdPercent.coerceAtLeast(1)
    val safeExposure = exposureLimitMinutes.coerceAtLeast(1)

    val volumeRatio = currentVolumePercent.toFloat() / safeVolume.toFloat()
    val exposureRatio = exposureMinutes.toFloat() / safeExposure.toFloat()
    val combinedRatio = maxOf(volumeRatio, exposureRatio, (volumeRatio + exposureRatio) / 2f)

    return when {
        combinedRatio >= 1.50f -> RhythmGuardRiskLevel.SEVERE
        combinedRatio >= 1.25f -> RhythmGuardRiskLevel.HIGH
        combinedRatio >= 1.00f -> RhythmGuardRiskLevel.MODERATE
        else -> RhythmGuardRiskLevel.LOW
    }
}

private fun rhythmGuardRiskLabel(
    context: android.content.Context,
    level: RhythmGuardRiskLevel
): String {
    val labelResId = when (level) {
        RhythmGuardRiskLevel.LOW -> R.string.settings_rhythm_guard_risk_level_low
        RhythmGuardRiskLevel.MODERATE -> R.string.settings_rhythm_guard_risk_level_moderate
        RhythmGuardRiskLevel.HIGH -> R.string.settings_rhythm_guard_risk_level_high
        RhythmGuardRiskLevel.SEVERE -> R.string.settings_rhythm_guard_risk_level_severe
    }
    return context.getString(labelResId)
}

private fun rhythmGuardRiskAction(
    context: android.content.Context,
    level: RhythmGuardRiskLevel
): String {
    val actionResId = when (level) {
        RhythmGuardRiskLevel.LOW -> R.string.settings_rhythm_guard_risk_action_low
        RhythmGuardRiskLevel.MODERATE -> R.string.settings_rhythm_guard_risk_action_moderate
        RhythmGuardRiskLevel.HIGH -> R.string.settings_rhythm_guard_risk_action_high
        RhythmGuardRiskLevel.SEVERE -> R.string.settings_rhythm_guard_risk_action_severe
    }
    return context.getString(actionResId)
}

private fun rhythmGuardFormatDurationFromMinutes(minutes: Int): String {
    val safeMinutes = minutes.coerceAtLeast(0)
    val days = safeMinutes / (24 * 60)
    val hours = (safeMinutes % (24 * 60)) / 60
    val mins = safeMinutes % 60

    return when {
        days > 0 && hours > 0 && mins > 0 -> "${days}d ${hours}h ${mins}m"
        days > 0 && hours > 0 -> "${days}d ${hours}h"
        days > 0 && mins > 0 -> "${days}d ${mins}m"
        days > 0 -> "${days}d"
        hours > 0 && mins > 0 -> "${hours}h ${mins}m"
        hours > 0 -> "${hours}h"
        else -> "${mins}m"
    }
}

private fun rhythmGuardFormatCountdown(seconds: Long): String {
    val safeSeconds = seconds.coerceAtLeast(0L)
    val hours = safeSeconds / 3600
    val minutes = (safeSeconds % 3600) / 60
    val secs = safeSeconds % 60

    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format("%02d:%02d", minutes, secs)
    }
}

@Composable
private fun RhythmGuardResumeCountdownBubble(
    countdownText: String,
    progress: Float,
    label: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val progressValue = progress.coerceIn(0f, 1f)
    val currentProgress by androidx.compose.runtime.rememberUpdatedState(progressValue)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 4.dp,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier.size(34.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularWavyProgressIndicator(
                    progress = { currentProgress },
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
                )
                Icon(
                    imageVector = Icons.Filled.AccessTime,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(16.dp)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                )
                Text(
                    text = context.getString(R.string.settings_rhythm_guard_resume_countdown_value, countdownText),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

private fun closeRhythmApp(context: android.content.Context) {
    val activity = context as? android.app.Activity ?: return
    activity.finishAffinity()
}

@Composable
private fun rememberSystemMusicVolumeFraction(context: android.content.Context): Float {
    var systemVolume by remember { mutableFloatStateOf(0f) }

    androidx.compose.runtime.DisposableEffect(context) {
        val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager

        fun refreshVolume() {
            val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val currentVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            systemVolume = if (maxVolume > 0) currentVolume.toFloat() / maxVolume.toFloat() else 0f
        }

        refreshVolume()

        val observer = object : android.database.ContentObserver(
            android.os.Handler(android.os.Looper.getMainLooper())
        ) {
            override fun onChange(selfChange: Boolean) {
                refreshVolume()
            }
        }

        context.contentResolver.registerContentObserver(
            android.provider.Settings.System.CONTENT_URI,
            true,
            observer
        )

        onDispose {
            context.contentResolver.unregisterContentObserver(observer)
        }
    }

    return systemVolume
}

private fun setSystemMusicVolumeFraction(context: android.content.Context, fraction: Float) {
    val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
        ?: return
    val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
    if (maxVolume <= 0) return
    val targetVolume = (fraction.coerceIn(0f, 1f) * maxVolume).toInt().coerceIn(0, maxVolume)
    audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, targetVolume, 0)
}

/**
 * Mode selector screen that allows users to switch between Local and Streaming modes.
 * Can be shown as a full screen or as a bottom sheet.
 */
@Composable
fun ModeSelectorScreen(
    currentMode: AppMode,
    onModeSelected: (AppMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Choose Your Experience",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Switch between local music and streaming services",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(48.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Local Mode Card
            ModeCard(
                title = "Local",
                description = "Play music stored on your device",
                icon = if (currentMode == AppMode.LOCAL) Icons.Filled.MusicNote else Icons.Outlined.MusicNote,
                isSelected = currentMode == AppMode.LOCAL,
                onClick = { onModeSelected(AppMode.LOCAL) },
                modifier = Modifier.weight(1f)
            )
            
            // Streaming Mode Card
            ModeCard(
                title = "Streaming",
                description = "Stream from Spotify, Apple Music & more",
                icon = if (currentMode == AppMode.STREAMING) Icons.Filled.CloudQueue else Icons.Outlined.CloudQueue,
                isSelected = currentMode == AppMode.STREAMING,
                onClick = { onModeSelected(AppMode.STREAMING) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Card component for displaying a mode option in the mode selector.
 */
@Composable
private fun ModeCard(
    title: String,
    description: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isSelected) 8.dp else 2.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surface
                },
                modifier = Modifier.size(64.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        modifier = Modifier.size(32.dp),
                        tint = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                }
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Selection indicator
            AnimatedVisibility(
                visible = isSelected,
                enter = scaleIn(animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Text(
                        text = "Active",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * Compact mode switcher that can be placed in a navigation bar or header.
 */
@Composable
fun CompactModeSwitcher(
    currentMode: AppMode,
    onModeToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onModeToggle),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Local indicator
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (currentMode == AppMode.LOCAL) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier.padding(2.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MusicNote,
                        contentDescription = "Local",
                        modifier = Modifier.size(16.dp),
                        tint = if (currentMode == AppMode.LOCAL) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    if (currentMode == AppMode.LOCAL) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Local",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
            
            // Streaming indicator
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (currentMode == AppMode.STREAMING) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier.padding(2.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CloudQueue,
                        contentDescription = "Streaming",
                        modifier = Modifier.size(16.dp),
                        tint = if (currentMode == AppMode.STREAMING) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    if (currentMode == AppMode.STREAMING) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Stream",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    }
}
