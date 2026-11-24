package com.ineedmypills.vibemis.ui.wizard

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ineedmypills.vibemis.R
import com.ineedmypills.vibemis.autotune.AutoTuneHelper
import com.ineedmypills.vibemis.autotune.AutoTuneResult
import com.ineedmypills.vibemis.preferences.PreferenceConfiguration
import com.ineedmypills.vibemis.ui.components.LocalReducedMotion
import com.ineedmypills.vibemis.ui.components.VibemisConnectedSegmentedSelector
import com.ineedmypills.vibemis.ui.components.VibemisShapeBadge
import com.ineedmypills.vibemis.ui.components.expressiveFocusAndPress
import com.ineedmypills.vibemis.ui.components.rememberVibemisAdaptiveLayout
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SetupWizardScreen(
    onFinish: () -> Unit,
    onAddHost: () -> Unit
) {
    val context = LocalContext.current
    val autoTuneData = remember { AutoTuneHelper.analyze(context) }

    var currentStep by remember { mutableIntStateOf(1) }
    val totalSteps = 5

    var selectedResolution by remember { mutableStateOf(autoTuneData.recommendedResolution) }
    var selectedFps by remember { mutableStateOf(autoTuneData.recommendedFps) }
    var bitrateKbps by remember { mutableIntStateOf(autoTuneData.recommendedBitrateKbps) }
    var videoCodec by remember { mutableStateOf(autoTuneData.recommendedVideoFormat) }
    var snappyInput by remember { mutableStateOf(true) }
    var ultraLowLatency by remember { mutableStateOf(true) }
    var onScreenControls by remember { mutableStateOf(false) }

    fun applySettings() {
        val prefs = PreferenceConfiguration.getDefaultSharedPreferences(context)
        prefs.edit()
            .putString("list_resolution", selectedResolution)
            .putString("list_fps", selectedFps)
            .putInt("seekbar_bitrate_kbps", bitrateKbps)
            .putString("video_format", videoCodec)
            .putBoolean("checkbox_snappy_input", snappyInput)
            .putBoolean("checkbox_ultra_low_latency", ultraLowLatency)
            .putBoolean("checkbox_show_onscreen_controls", onScreenControls)
            .putBoolean("checkbox_async_decode", true)
            .apply()

        val result = AutoTuneResult(
            deviceType = autoTuneData.deviceType,
            displayWidth = autoTuneData.displayWidth,
            displayHeight = autoTuneData.displayHeight,
            maxRefreshRate = autoTuneData.maxRefreshRate,
            recommendedResolution = selectedResolution,
            recommendedFps = selectedFps,
            recommendedBitrateKbps = bitrateKbps,
            recommendedVideoFormat = videoCodec,
            hdrSupported = autoTuneData.hdrSupported,
            ultraLowLatency = ultraLowLatency,
            snappyInput = snappyInput
        )
        AutoTuneHelper.apply(context, result)
    }

    val adaptive = rememberVibemisAdaptiveLayout()
    val reducedMotion = LocalReducedMotion.current
    val animatedProgress by animateFloatAsState(
        targetValue = currentStep.toFloat() / totalSteps.toFloat(),
        animationSpec = if (reducedMotion) snap() else MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "wizardProgress"
    )
    val spatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
    val effectsSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.wizard_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.wizard_step_indicator, currentStep, totalSteps),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    if (currentStep > 1) {
                        IconButton(
                            onClick = { currentStep -= 1 },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.wizard_btn_back))
                        }
                    }
                },
                actions = {
                    Text(
                        text = "${(currentStep * 100) / totalSteps}%",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 16.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (currentStep > 1) {
                        OutlinedButton(
                            onClick = { currentStep -= 1 },
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .weight(1f)
                        ) {
                            Text(stringResource(R.string.wizard_btn_back), fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                    }

                    Button(
                        onClick = {
                            if (currentStep < totalSteps) {
                                currentStep += 1
                            } else {
                                applySettings()
                                onFinish()
                            }
                        },
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .weight(if (currentStep > 1) 1.5f else 1f)
                    ) {
                        if (currentStep < totalSteps) {
                            Text(stringResource(R.string.wizard_btn_next), fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                        } else {
                            Text(stringResource(R.string.wizard_btn_finish), fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LinearWavyProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                amplitude = { p -> if (reducedMotion || p <= 0f || p >= 1f) 0f else 1f },
            )

            val stepContent: @Composable (Int) -> Unit = { step ->
                when (step) {
                    1 -> StepDeviceAnalysis(
                        data = autoTuneData,
                        onApplyAutoTune = {
                            selectedResolution = autoTuneData.recommendedResolution
                            selectedFps = autoTuneData.recommendedFps
                            bitrateKbps = autoTuneData.recommendedBitrateKbps
                            videoCodec = autoTuneData.recommendedVideoFormat
                            snappyInput = true
                            ultraLowLatency = true
                            currentStep = 5
                        }
                    )
                    2 -> StepResolutionAndFps(
                        currentRes = selectedResolution,
                        currentFps = selectedFps,
                        maxRefresh = autoTuneData.maxRefreshRate,
                        onSelectRes = { selectedResolution = it },
                        onSelectFps = { selectedFps = it }
                    )
                    3 -> StepBitrateAndCodec(
                        bitrateKbps = bitrateKbps,
                        onBitrateChange = { bitrateKbps = it },
                        codec = videoCodec,
                        onCodecChange = { videoCodec = it }
                    )
                    4 -> StepControlsAndLatency(
                        snappyInput = snappyInput,
                        onSnappyChange = { snappyInput = it },
                        ultraLowLatency = ultraLowLatency,
                        onUltraLowChange = { ultraLowLatency = it },
                        onScreenControls = onScreenControls,
                        onScreenControlsChange = { onScreenControls = it }
                    )
                    5 -> StepSummary(
                        resolution = selectedResolution,
                        fps = selectedFps,
                        bitrateKbps = bitrateKbps,
                        codec = videoCodec,
                        snappyInput = snappyInput,
                        ultraLowLatency = ultraLowLatency,
                        onScreenControls = onScreenControls,
                        onAddHost = {
                            applySettings()
                            onAddHost()
                        }
                    )
                }
            }

            if (adaptive.useTwoPane) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 1040.dp)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    WizardSideOverviewCard(
                        currentStep = currentStep,
                        totalSteps = totalSteps,
                        resolution = selectedResolution,
                        fps = selectedFps,
                        bitrateKbps = bitrateKbps,
                        codec = videoCodec,
                        modifier = Modifier
                            .weight(0.38f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                    )
                    AnimatedContent(
                        targetState = currentStep,
                        transitionSpec = {
                            if (reducedMotion) {
                                fadeIn(snap()) togetherWith fadeOut(snap())
                            } else {
                                val dir = if (targetState >= initialState) 1 else -1
                                (slideInHorizontally(spatialSpec) { it * dir / 3 } + fadeIn(effectsSpec)) togetherWith
                                    (slideOutHorizontally(spatialSpec) { -it * dir / 4 } + fadeOut(effectsSpec))
                            }
                        },
                        label = "WizardStepTransition",
                        modifier = Modifier
                            .weight(0.62f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                    ) { step ->
                        stepContent(step)
                    }
                }
            } else {
                AnimatedContent(
                    targetState = currentStep,
                    transitionSpec = {
                        if (reducedMotion) {
                            fadeIn(snap()) togetherWith fadeOut(snap())
                        } else {
                            val dir = if (targetState >= initialState) 1 else -1
                            (slideInHorizontally(spatialSpec) { it * dir / 3 } + fadeIn(effectsSpec)) togetherWith
                                (slideOutHorizontally(spatialSpec) { -it * dir / 4 } + fadeOut(effectsSpec))
                        }
                    },
                    label = "WizardStepTransition",
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 640.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) { step ->
                    stepContent(step)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WizardSideOverviewCard(
    currentStep: Int,
    totalSteps: Int,
    resolution: String,
    fps: String,
    bitrateKbps: Int,
    codec: String,
    modifier: Modifier = Modifier,
) {
    val polygon = when (currentStep) {
        1 -> MaterialShapes.SoftBurst
        2 -> MaterialShapes.Gem
        3 -> MaterialShapes.Cookie9Sided
        4 -> MaterialShapes.Clover4Leaf
        else -> MaterialShapes.Flower
    }
    val stepIcon = when (currentStep) {
        1 -> Icons.Default.Tune
        2 -> Icons.Default.StayCurrentPortrait
        3 -> Icons.Default.HighQuality
        4 -> Icons.Default.Gamepad
        else -> Icons.Default.Check
    }
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            VibemisShapeBadge(
                polygon = polygon,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                size = 56.dp,
            ) {
                Icon(stepIcon, contentDescription = null, modifier = Modifier.size(28.dp))
            }
            Text(
                text = stringResource(R.string.wizard_step_counter, currentStep, totalSteps),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            SummaryParamRow(stringResource(R.string.wizard_step2_title), "$resolution · ${fps} FPS")
            SummaryParamRow(stringResource(R.string.wizard_bitrate_label), "${bitrateKbps / 1000} Mbps")
            SummaryParamRow(stringResource(R.string.wizard_codec_label), codec)
        }
    }
}

@Composable
private fun StepDeviceAnalysis(
    data: AutoTuneResult,
    onApplyAutoTune: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = stringResource(R.string.wizard_step1_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(R.string.wizard_step1_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.StayCurrentPortrait,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Column {
                        Text(
                            text = stringResource(R.string.wizard_detected_device),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val deviceTypeLabel = when (data.deviceType) {
                            "Android TV", "TV" -> stringResource(R.string.wizard_device_tv)
                            "Tablet", "Планшет" -> stringResource(R.string.wizard_device_tablet)
                            else -> stringResource(R.string.wizard_device_phone)
                        }
                        Text(
                            text = deviceTypeLabel,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StatPill(
                        label = stringResource(R.string.wizard_native_display),
                        value = "${data.displayWidth}x${data.displayHeight}",
                        modifier = Modifier.weight(1f)
                    )
                    StatPill(
                        label = stringResource(R.string.wizard_refresh_rate),
                        value = "${data.maxRefreshRate} Hz",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Quick Auto-Tune Promo Card
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Default.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = stringResource(R.string.wizard_autotune_banner_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                val codecName = if (data.recommendedVideoFormat == "FORCE_HEVC") "HEVC" else "H.264"
                Text(
                    text = stringResource(
                        R.string.wizard_autotune_banner_desc,
                        data.recommendedResolution,
                        data.recommendedFps,
                        data.recommendedBitrateKbps / 1000,
                        codecName
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )

                Button(
                    onClick = onApplyAutoTune,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.wizard_apply_autotune_btn), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun StepResolutionAndFps(
    currentRes: String,
    currentFps: String,
    maxRefresh: Int,
    onSelectRes: (String) -> Unit,
    onSelectFps: (String) -> Unit
) {
    val resolutions = listOf(
        "1920x1080" to ("1080p Full HD" to stringResource(R.string.wizard_res_1080p_desc)),
        "2560x1440" to ("1440p QHD" to stringResource(R.string.wizard_res_1440p_desc)),
        "3840x2160" to ("4K Ultra HD" to stringResource(R.string.wizard_res_4k_desc)),
        "1280x720" to ("720p HD" to stringResource(R.string.wizard_res_720p_desc))
    )

    val fpsOptions = listOf(
        "60" to stringResource(R.string.wizard_fps_60_label),
        "90" to stringResource(R.string.wizard_fps_90_label),
        "120" to stringResource(R.string.wizard_fps_120_label)
    )

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = stringResource(R.string.wizard_step2_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(R.string.wizard_step2_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = stringResource(R.string.wizard_resolution_label),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            resolutions.forEach { (resValue, textPair) ->
                val isSelected = currentRes == resValue
                SelectableOptionCard(
                    title = textPair.first,
                    subtitle = textPair.second,
                    selected = isSelected,
                    onClick = { onSelectRes(resValue) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.wizard_fps_label),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        VibemisConnectedSegmentedSelector(
            options = fpsOptions,
            selectedValue = currentFps,
            onSelect = onSelectFps,
        )
    }
}

@Composable
private fun StepBitrateAndCodec(
    bitrateKbps: Int,
    onBitrateChange: (Int) -> Unit,
    codec: String,
    onCodecChange: (String) -> Unit
) {
    val mbpsFormat = stringResource(R.string.wizard_mbps_format)
    val presets = listOf(
        15000 to String.format(mbpsFormat, "15"),
        25000 to String.format(mbpsFormat, "25"),
        45000 to String.format(mbpsFormat, "45"),
        70000 to String.format(mbpsFormat, "70")
    )

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = stringResource(R.string.wizard_step3_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(R.string.wizard_step3_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.wizard_bitrate_label),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        val currentMbps = (bitrateKbps / 1000f)
                        val formattedMbps = if (currentMbps % 1f == 0f) {
                            currentMbps.toInt().toString()
                        } else {
                            String.format(java.util.Locale.US, "%.1f", currentMbps)
                        }
                        Text(
                            text = String.format(mbpsFormat, formattedMbps),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                @Suppress("DEPRECATION")
                Slider(
                    value = bitrateKbps.toFloat(),
                    onValueChange = { onBitrateChange(((it / 1000).roundToInt() * 1000).coerceIn(2000, 100000)) },
                    valueRange = 2000f..100000f,
                    steps = 97
                )

                val activePresetValue = presets.firstOrNull { (bitrateKbps / 5000) == (it.first / 5000) }?.first ?: -1
                VibemisConnectedSegmentedSelector(
                    options = presets,
                    selectedValue = activePresetValue,
                    onSelect = onBitrateChange,
                )
            }
        }

        Text(
            text = stringResource(R.string.wizard_codec_label),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectableOptionCard(
                title = stringResource(R.string.wizard_codec_hevc_title),
                subtitle = stringResource(R.string.wizard_codec_hevc_desc),
                selected = codec == "FORCE_HEVC",
                onClick = { onCodecChange("FORCE_HEVC") }
            )
            SelectableOptionCard(
                title = stringResource(R.string.wizard_codec_auto_title),
                subtitle = stringResource(R.string.wizard_codec_auto_desc),
                selected = codec == "AUTO",
                onClick = { onCodecChange("AUTO") }
            )
            SelectableOptionCard(
                title = stringResource(R.string.wizard_codec_avc_title),
                subtitle = stringResource(R.string.wizard_codec_avc_desc),
                selected = codec == "FORCE_H264",
                onClick = { onCodecChange("FORCE_H264") }
            )
        }
    }
}

@Composable
private fun StepControlsAndLatency(
    snappyInput: Boolean,
    onSnappyChange: (Boolean) -> Unit,
    ultraLowLatency: Boolean,
    onUltraLowChange: (Boolean) -> Unit,
    onScreenControls: Boolean,
    onScreenControlsChange: (Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = stringResource(R.string.wizard_step4_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(R.string.wizard_step4_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                PreferenceToggleRow(
                    title = stringResource(R.string.wizard_snappy_input_title),
                    subtitle = stringResource(R.string.wizard_snappy_input_desc),
                    checked = snappyInput,
                    onCheckedChange = onSnappyChange
                )

                PreferenceToggleRow(
                    title = stringResource(R.string.wizard_ultra_low_latency_title),
                    subtitle = stringResource(R.string.wizard_ultra_low_latency_desc),
                    checked = ultraLowLatency,
                    onCheckedChange = onUltraLowChange
                )

                PreferenceToggleRow(
                    title = stringResource(R.string.wizard_onscreen_controls_title),
                    subtitle = stringResource(R.string.wizard_onscreen_controls_desc),
                    checked = onScreenControls,
                    onCheckedChange = onScreenControlsChange
                )
            }
        }
    }
}

@Composable
private fun StepSummary(
    resolution: String,
    fps: String,
    bitrateKbps: Int,
    codec: String,
    snappyInput: Boolean,
    ultraLowLatency: Boolean,
    onScreenControls: Boolean,
    onAddHost: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = stringResource(R.string.wizard_step5_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(R.string.wizard_step5_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val mbpsFormat = stringResource(R.string.wizard_mbps_format)
                val enabledLabel = stringResource(R.string.wizard_state_enabled)
                val disabledLabel = stringResource(R.string.wizard_state_disabled)
                SummaryParamRow(label = stringResource(R.string.title_resolution_list), value = resolution)
                SummaryParamRow(label = stringResource(R.string.title_fps_list), value = "$fps FPS")
                SummaryParamRow(label = stringResource(R.string.title_seekbar_bitrate), value = String.format(mbpsFormat, (bitrateKbps / 1000).toString()))
                SummaryParamRow(label = stringResource(R.string.title_video_format), value = codec)
                SummaryParamRow(label = stringResource(R.string.wizard_snappy_input_title), value = if (snappyInput) enabledLabel else disabledLabel)
                SummaryParamRow(label = stringResource(R.string.wizard_ultra_low_latency_title), value = if (ultraLowLatency) enabledLabel else disabledLabel)
                SummaryParamRow(label = stringResource(R.string.wizard_onscreen_controls_title), value = if (onScreenControls) enabledLabel else disabledLabel)
            }
        }

        OutlinedButton(
            onClick = onAddHost,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        ) {
            Icon(Icons.Default.Computer, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.wizard_add_host_btn), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun StatPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun SelectableOptionCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(18.dp)
    Surface(
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .expressiveFocusAndPress(
                interactionSource = interactionSource,
                shape = shape,
            )
            .clip(shape)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun PreferenceToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SummaryParamRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
