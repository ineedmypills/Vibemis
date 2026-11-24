package com.ineedmypills.vibemis.preferences

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DisplaySettings
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalToggleButton
import androidx.compose.material3.FilledTonalToggleButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import com.ineedmypills.vibemis.DebugInfoActivity
import com.ineedmypills.vibemis.R
import com.ineedmypills.vibemis.autotune.AutoTuneHelper
import com.ineedmypills.vibemis.binding.video.NvidiaLatencyRecoveryEligibility
import com.ineedmypills.vibemis.nvstream.jni.VibemisBridge
import com.ineedmypills.vibemis.ui.components.COLOR_PALETTE_DYNAMIC
import com.ineedmypills.vibemis.ui.components.COLOR_PALETTE_VIBEMIS
import com.ineedmypills.vibemis.ui.components.LocalReducedMotion
import com.ineedmypills.vibemis.ui.components.PREF_COLOR_PALETTE
import com.ineedmypills.vibemis.ui.components.PREF_THEME_MODE
import com.ineedmypills.vibemis.ui.components.THEME_MODE_DARK
import com.ineedmypills.vibemis.ui.components.THEME_MODE_LIGHT
import com.ineedmypills.vibemis.ui.components.THEME_MODE_SYSTEM
import com.ineedmypills.vibemis.ui.components.VibemisConnectedSegmentedSelector
import com.ineedmypills.vibemis.ui.components.VibemisShapeBadge
import com.ineedmypills.vibemis.ui.components.expressiveFocusAndPress
import com.ineedmypills.vibemis.ui.components.rememberVibemisAdaptiveLayout
import com.ineedmypills.vibemis.ui.wizard.SetupWizardActivity
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Stable
private sealed interface SettingEntry {
    val key: String
    val title: String
    val summary: String
    val keywords: String
    val isAdvanced: Boolean
}

private data class ChoiceSetting(
    override val key: String,
    override val title: String,
    override val summary: String,
    val defaultValue: String,
    val options: List<Pair<String, String>>,
    override val keywords: String = "",
    val storeAsInt: Boolean = false,
    override val isAdvanced: Boolean = false,
    val inlineControl: Boolean = false,
) : SettingEntry

private data class ToggleSetting(
    override val key: String,
    override val title: String,
    override val summary: String,
    val defaultValue: Boolean,
    override val keywords: String = "",
    override val isAdvanced: Boolean = false,
) : SettingEntry

private data class SliderSetting(
    override val key: String,
    override val title: String,
    override val summary: String,
    val defaultValue: Int,
    val minValue: Int,
    val maxValue: Int,
    val stepSize: Int = 1,
    val unitSuffix: String = "",
    val formatValue: ((Int) -> String)? = null,
    override val keywords: String = "",
    override val isAdvanced: Boolean = false,
) : SettingEntry

private data class ActionSetting(
    override val key: String,
    override val title: String,
    override val summary: String,
    override val keywords: String = "",
    override val isAdvanced: Boolean = false,
    val onClick: () -> Unit
) : SettingEntry

@Immutable
private data class SettingCategory(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val badgeShape: RoundedPolygon,
    val items: List<SettingEntry>
)

@Composable
private fun zipArrays(@ArrayRes valuesRes: Int, @ArrayRes namesRes: Int): List<Pair<String, String>> {
    val values = stringArrayResource(valuesRes)
    val names = stringArrayResource(namesRes)
    val count = min(values.size, names.size)
    return (0 until count).map { i -> values[i] to names[i] }
}

@Composable
private fun str(@StringRes id: Int): String = stringResource(id)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VibemisSettingsContent(
    prefs: SharedPreferences,
    context: Context,
    modifier: Modifier = Modifier
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var openedCategoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var twoPaneCategoryId by rememberSaveable { mutableStateOf("video") }
    var refreshTick by remember { mutableIntStateOf(0) }
    val expandedAdvancedMap = remember { mutableStateMapOf<String, Boolean>() }
    var infoDialogEntry by remember { mutableStateOf<SettingEntry?>(null) }

    val dm = context.resources.displayMetrics
    val nativeResValue = "${max(dm.widthPixels, dm.heightPixels)}x${min(dm.widthPixels, dm.heightPixels)}"
    val nativeResLabel = stringResource(R.string.settings_native_resolution, nativeResValue)
    val meteredAutoLabel = stringResource(R.string.settings_metered_auto)
    val baseResolutions = zipArrays(R.array.resolution_values, R.array.resolution_names)
    val resolutionOptions = remember(baseResolutions, nativeResValue, nativeResLabel) {
        if (baseResolutions.any { it.first == nativeResValue }) {
            baseResolutions
        } else {
            baseResolutions + (nativeResValue to nativeResLabel)
        }
    }

    val fpsOptions = listOf(
        "30" to "30 FPS",
        "60" to "60 FPS",
        "90" to "90 FPS",
        "120" to "120 FPS",
        "144" to "144 FPS",
        "165" to "165 FPS"
    )

    val videoCodecOptions = zipArrays(R.array.video_format_values, R.array.video_format_names)
    val videoScaleOptions = zipArrays(R.array.video_scale_mode_values, R.array.video_scale_mode_names)
    val framePacingOptions = zipArrays(R.array.video_frame_pacing_values, R.array.video_frame_pacing_names)
    val upscaleModeOptions = zipArrays(R.array.pref_video_upscale_mode_values, R.array.pref_video_upscale_mode_entries)
    val upscalePresetOptions = listOf(
        "0" to str(R.string.fsr_preset_0),
        "1" to str(R.string.fsr_preset_1),
        "2" to str(R.string.fsr_preset_2)
    )
    val cpuWarmupModeOptions = listOf(
        "auto" to str(R.string.cpu_warmup_mode_auto),
        "prime" to str(R.string.cpu_warmup_mode_prime),
        "big" to str(R.string.cpu_warmup_mode_big),
        "little" to str(R.string.cpu_warmup_mode_little),
        "all" to str(R.string.cpu_warmup_mode_all)
    )
    val themeModeOptions = listOf(
        THEME_MODE_SYSTEM to str(R.string.theme_mode_system),
        THEME_MODE_DARK to str(R.string.theme_mode_dark),
        THEME_MODE_LIGHT to str(R.string.theme_mode_light),
    )
    val colorPaletteOptions = listOf(
        COLOR_PALETTE_DYNAMIC to str(R.string.color_palette_dynamic),
        COLOR_PALETTE_VIBEMIS to str(R.string.color_palette_vibemis),
    )

    val nvidiaRecoverySummary = remember(refreshTick) {
        val requested = prefs.getBoolean(PreferenceConfiguration.NVIDIA_LATENCY_RECOVERY_PREF_STRING, true)
        val pacingStr = prefs.getString("frame_pacing", "latency")
        val pacing = PreferenceConfiguration.framePacingFromString(pacingStr)
        val asyncDecode = prefs.getBoolean("checkbox_async_decode", true)
        val syncLfr = prefs.getBoolean("checkbox_sync_lfr", false)
        val fastVsync = prefs.getBoolean("checkbox_fastVsync", false)
        val mask = NvidiaLatencyRecoveryEligibility.staticModeMask(pacing, asyncDecode, syncLfr, fastVsync)
        val reason = if (requested) NvidiaLatencyRecoveryEligibility.primaryInactiveReason(mask) else null
        if (reason != null) {
            "${context.getString(R.string.summary_checkbox_nvidia_latency_recovery)} (${context.getString(R.string.summary_nvidia_latency_recovery_inactive, reason)})"
        } else {
            context.getString(R.string.summary_checkbox_nvidia_latency_recovery)
        }
    }

    val cpuTopologySummary = remember {
        val base = context.getString(R.string.pref_cpu_warmup_enable_summary)
        val topo = try {
            context.getString(
                R.string.summary_cpu_topology_format,
                VibemisBridge.getPrimeCoreCount(),
                VibemisBridge.getBigCoreCount(),
                VibemisBridge.getLittleCoreCount()
            )
        } catch (_: Throwable) {
            null
        }
        if (topo.isNullOrBlank()) base else "$base\n$topo"
    }

    val autoScaleSummary = remember(refreshTick, nativeResValue) {
        val base = context.getString(R.string.summary_auto_resolution_scale_factor)
        val enabled = prefs.getBoolean(PreferenceConfiguration.AUTO_RESOLUTION_SCALE_FACTOR_PREF_STRING, false)
        if (!enabled) {
            base
        } else {
            val resParts = (prefs.getString("list_resolution", "1920x1080") ?: "1920x1080").split("x")
            val sw = resParts.getOrNull(0)?.toIntOrNull() ?: 1920
            val sh = resParts.getOrNull(1)?.toIntOrNull() ?: 1080
            val dw = max(dm.widthPixels, dm.heightPixels)
            val dh = min(dm.widthPixels, dm.heightPixels)
            val scale = PreferenceConfiguration.computeAutoScaleFactor(sw, sh, dw, dh)
            val tw = (sw * scale / 100.0).roundToInt()
            val th = (sh * scale / 100.0).roundToInt()
            "$base\n${context.getString(R.string.summary_auto_resolution_scale_factor_active, tw, th, scale)}"
        }
    }
    val audioConfigOptions = zipArrays(R.array.audio_config_values, R.array.audio_config_names)
    val analogScrollOptions = zipArrays(R.array.analog_scrolling_values, R.array.analog_scrolling_names)
    val mouseModeOptions = zipArrays(R.array.mouse_mode_values, R.array.mouse_mode_names)
    val keyboardAlignOptions = zipArrays(R.array.onscreen_keyboard_align_values, R.array.onscreen_keyboard_align_modes)
    val languageOptions = zipArrays(R.array.language_values, R.array.language_names)

    val quickSetupActions = listOf(
        ActionSetting(
            key = "action_setup_wizard",
            title = str(R.string.wizard_title),
            summary = str(R.string.wizard_summary),
            keywords = "wizard master setup guide мастер настройки мастер пошаговый",
            onClick = {
                context.startActivity(Intent(context, SetupWizardActivity::class.java))
            }
        ),
        ActionSetting(
            key = "action_autotune",
            title = str(R.string.autotune_action_title),
            summary = str(R.string.autotune_action_summary),
            keywords = "autotune auto tune автонастройка калибровка оптимальные",
            onClick = {
                val result = AutoTuneHelper.applyAutoTune(context)
                Toast.makeText(
                    context,
                    context.getString(R.string.autotune_applied_toast, result.recommendedResolution, result.recommendedFps),
                    Toast.LENGTH_LONG
                ).show()
                refreshTick++
            }
        )
    )

    // Live summaries displayed on the Settings Hub so key choices are readable without opening sections.
    val liveVideoRes = prefs.getString("list_resolution", "1920x1080") ?: "1920x1080"
    val liveVideoFps = prefs.getString("list_fps", "60") ?: "60"
    val liveVideoMbps = (prefs.getInt("seekbar_bitrate_kbps", 20000) / 1000)
    val liveVideoCodec = (prefs.getString("video_format", "auto") ?: "auto").uppercase()
    val liveHdr = prefs.getBoolean("checkbox_enable_hdr", false)
    val videoHubSubtitle = remember(refreshTick) {
        buildString {
            append("$liveVideoRes · $liveVideoFps FPS · $liveVideoMbps Mbps · $liveVideoCodec")
            if (liveHdr) append(" · HDR")
        }
    }

    val fsrEnabled = prefs.getBoolean("pref_video_upscale_enable", false)
    val fsrSharpness = prefs.getInt("pref_video_upscale_sharpness", 35)
    val ullEnabled = prefs.getBoolean("checkbox_ultra_low_latency", false)
    val asyncDecodeEnabled = prefs.getBoolean("checkbox_async_decode", true)
    val upscalerHubSubtitle = remember(refreshTick) {
        val fsrState = if (fsrEnabled) "FSR $fsrSharpness%" else "FSR Off"
        val latencyState = when {
            ullEnabled -> "Ultra-Low Latency"
            asyncDecodeEnabled -> "Async Decode"
            else -> "Sync Decode"
        }
        "$fsrState · $latencyState"
    }

    val audioVal = prefs.getString("list_audio_config", "2") ?: "2"
    val audioLabel = audioConfigOptions.firstOrNull { it.first == audioVal }?.second ?: audioVal
    val virtualDisplayEnabled = prefs.getBoolean("checkbox_use_virtual_display", false)
    val hostAudioSubtitle = remember(refreshTick, audioLabel) {
        if (virtualDisplayEnabled) "$audioLabel · Virtual Display" else audioLabel
    }

    val deadzonePct = prefs.getInt("seekbar_deadzone", 5)
    val rumbleEnabled = prefs.getBoolean("checkbox_enable_rumble", true)
    val gamepadSubtitle = remember(refreshTick) {
        "Deadzone $deadzonePct% · Rumble ${if (rumbleEnabled) "On" else "Off"}"
    }

    val mouseModeVal = prefs.getString("mouse_mode_list", "0") ?: "0"
    val touchMouseSubtitle = remember(refreshTick, mouseModeVal) {
        mouseModeOptions.firstOrNull { it.first == mouseModeVal }?.second ?: mouseModeVal
    }

    val oscEnabled = prefs.getBoolean("checkbox_show_onscreen_controls", false)
    val oscOpacityPct = prefs.getInt("seekbar_osc_opacity", 90)
    val oscSubtitle = remember(refreshTick) {
        if (oscEnabled) "Controls $oscOpacityPct%" else "Controls Off"
    }

    val currentThemeVal = prefs.getString(PREF_THEME_MODE, THEME_MODE_SYSTEM) ?: THEME_MODE_SYSTEM
    val currentPaletteVal = prefs.getString(PREF_COLOR_PALETTE, COLOR_PALETTE_DYNAMIC) ?: COLOR_PALETTE_DYNAMIC
    val uiOverlaySubtitle = remember(refreshTick, currentThemeVal, currentPaletteVal) {
        "${themeModeOptions.firstOrNull { it.first == currentThemeVal }?.second ?: currentThemeVal} · ${
            colorPaletteOptions.firstOrNull { it.first == currentPaletteVal }?.second ?: currentPaletteVal
        }"
    }

    val categories = listOf(
        SettingCategory(
            id = "video",
            title = str(R.string.category_video_settings),
            subtitle = videoHubSubtitle,
            icon = Icons.Rounded.Tv,
            badgeShape = MaterialShapes.Cookie9Sided,
            items = listOf(
                ChoiceSetting(
                    key = "list_resolution",
                    title = str(R.string.title_resolution_list),
                    summary = str(R.string.summary_resolution_list),
                    defaultValue = "1920x1080",
                    options = resolutionOptions,
                    keywords = "resolution 360p 480p 720p 1080p 1440p 4k native разрешение видео экран"
                ),
                ChoiceSetting(
                    key = "list_fps",
                    title = str(R.string.title_fps_list),
                    summary = str(R.string.summary_fps_list),
                    defaultValue = "60",
                    options = fpsOptions,
                    keywords = "fps frame rate refresh hz частота кадров герцовка"
                ),
                SliderSetting(
                    key = "seekbar_bitrate_kbps",
                    title = str(R.string.title_seekbar_bitrate),
                    summary = str(R.string.summary_seekbar_bitrate),
                    defaultValue = 20000,
                    minValue = 1000,
                    maxValue = 150000,
                    stepSize = 500,
                    formatValue = { "${it / 1000f} Mbps" },
                    keywords = "bitrate mbps kbps битрейт поток качество"
                ),
                ChoiceSetting(
                    key = "video_format",
                    title = str(R.string.title_video_format),
                    summary = str(R.string.summary_video_format),
                    defaultValue = "auto",
                    options = videoCodecOptions,
                    keywords = "codec av1 hevc h265 h264 avc кодек видео"
                ),
                ChoiceSetting(
                    key = "frame_pacing",
                    title = str(R.string.title_frame_pacing),
                    summary = str(R.string.summary_frame_pacing),
                    defaultValue = "latency",
                    options = framePacingOptions,
                    keywords = "frame pacing warp latency smoothness balanced gpu raw плавность задержка"
                ),
                ToggleSetting(
                    key = "checkbox_enable_hdr",
                    title = str(R.string.title_enable_hdr),
                    summary = str(R.string.summary_enable_hdr),
                    defaultValue = false,
                    keywords = "hdr hdr10 main10 high dynamic range цвет"
                ),
                SliderSetting(
                    key = "seekbar_metered_bitrate_kbps",
                    title = str(R.string.title_seekbar_metered_bitrate),
                    summary = str(R.string.summary_seekbar_metered_bitrate),
                    defaultValue = 0,
                    minValue = 0,
                    maxValue = 100000,
                    stepSize = 500,
                    formatValue = { if (it == 0) meteredAutoLabel else "${it / 1000f} Mbps" },
                    keywords = "metered bitrate mobile cellular lte 5g битрейт мобильная сеть",
                    isAdvanced = true,
                ),
                ChoiceSetting(
                    key = "list_video_scale_mode",
                    title = str(R.string.title_video_scale_mode),
                    summary = str(R.string.summary_video_scale_mode),
                    defaultValue = "fit",
                    options = videoScaleOptions,
                    keywords = "scale fit fill stretch масштаб растянуть",
                    isAdvanced = true,
                ),
                ToggleSetting(
                    key = "checkbox_full_range",
                    title = str(R.string.title_full_range),
                    summary = str(R.string.summary_full_range),
                    defaultValue = false,
                    keywords = "full range rgb yuv 0-255 полный диапазон цвет",
                    isAdvanced = true,
                ),
                ToggleSetting(
                    key = "checkbox_unlock_fps",
                    title = str(R.string.title_unlock_fps),
                    summary = str(R.string.summary_unlock_fps),
                    defaultValue = false,
                    keywords = "unlock fps 90 120 144 165 разблокировать частоту кадров",
                    isAdvanced = true,
                ),
                ToggleSetting(
                    key = "checkbox_reduce_refresh_rate",
                    title = str(R.string.title_checkbox_reduce_refresh_rate),
                    summary = str(R.string.summary_checkbox_reduce_refresh_rate),
                    defaultValue = false,
                    keywords = "reduce refresh rate battery экономия энергии частота",
                    isAdvanced = true,
                ),
                ToggleSetting(
                    key = "checkbox_auto_orientation",
                    title = str(R.string.title_auto_orientation),
                    summary = str(R.string.summary_auto_orientation),
                    defaultValue = false,
                    keywords = "auto orientation portrait landscape поворот экрана ориентация",
                    isAdvanced = true,
                ),
                ToggleSetting(
                    key = "checkbox_auto_invert_video_resolution",
                    title = str(R.string.title_checkbox_auto_invert_video_resolution),
                    summary = str(R.string.summary_checkbox_auto_invert_video_resolution),
                    defaultValue = true,
                    keywords = "invert resolution portrait вертикальный портретный инверсия",
                    isAdvanced = true,
                ),
                ToggleSetting(
                    key = "checkbox_enable_view_top_center",
                    title = str(R.string.title_top_center_display),
                    summary = str(R.string.summary_top_center_display),
                    defaultValue = false,
                    keywords = "top center align выравнивание вверху",
                    isAdvanced = true,
                ),
                ToggleSetting(
                    key = "checkbox_enable_fullexdisplay",
                    title = str(R.string.title_fullexdisplay_mode),
                    summary = str(R.string.summary_fullexdisplay_mode),
                    defaultValue = false,
                    keywords = "external display monitor hdmi usb-c внешний монитор дисплей",
                    isAdvanced = true,
                ),
                ToggleSetting(
                    key = "checkbox_prevent_packet_loss",
                    title = str(R.string.title_prevent_packet_loss),
                    summary = str(R.string.summary_prevent_packet_loss),
                    defaultValue = false,
                    keywords = "packet loss network udp потеря пакетов сеть",
                    isAdvanced = true,
                ),
                ToggleSetting(
                    key = "checkbox_tv_compositor_workaround",
                    title = str(R.string.title_tv_compositor_workaround),
                    summary = str(R.string.summary_tv_compositor_workaround),
                    defaultValue = false,
                    keywords = "android tv compositor flicker мигание телевизор",
                    isAdvanced = true,
                )
            )
        ),
        SettingCategory(
            id = "upscaler_latency",
            title = str(R.string.settings_category_upscaler),
            subtitle = upscalerHubSubtitle,
            icon = Icons.Rounded.Speed,
            badgeShape = MaterialShapes.Gem,
            items = listOf(
                ToggleSetting(
                    key = "pref_video_upscale_enable",
                    title = str(R.string.pref_video_upscale_enable_title),
                    summary = str(R.string.pref_video_upscale_enable_summary),
                    defaultValue = false,
                    keywords = "fsr amd easu rcas upscale sharpening апскейл резкость"
                ),
                ChoiceSetting(
                    key = "pref_video_upscale_mode",
                    title = str(R.string.pref_video_upscale_mode_title),
                    summary = str(R.string.pref_video_upscale_enable_summary),
                    defaultValue = "rcas",
                    options = upscaleModeOptions,
                    keywords = "fsr mode rcas easu режим апскейла",
                    inlineControl = true,
                ),
                SliderSetting(
                    key = "pref_video_upscale_sharpness",
                    title = str(R.string.pref_video_upscale_sharpness_title),
                    summary = str(R.string.pref_video_upscale_sharpness_summary),
                    defaultValue = 35,
                    minValue = 0,
                    maxValue = 100,
                    stepSize = 1,
                    unitSuffix = "%",
                    keywords = "fsr rcas sharpness резкость шарпенинг"
                ),
                ToggleSetting(
                    key = "checkbox_ultra_low_latency",
                    title = str(R.string.title_checkbox_ultra_low_latency),
                    summary = str(R.string.summary_checkbox_ultra_low_latency),
                    defaultValue = false,
                    keywords = "ultra low latency snapdragon 8gen2 8gen3 8elite сверхнизкая задержка"
                ),
                ToggleSetting(
                    key = "checkbox_async_decode",
                    title = str(R.string.async_decode_title),
                    summary = str(R.string.async_decode_summary),
                    defaultValue = true,
                    keywords = "async decode mediacodec асинхронное декодирование"
                ),
                ChoiceSetting(
                    key = "pref_video_upscale_preset",
                    title = str(R.string.pref_video_upscale_preset_title),
                    summary = str(R.string.pref_video_upscale_preset_summary),
                    defaultValue = "1",
                    options = upscalePresetOptions,
                    storeAsInt = true,
                    keywords = "fsr preset performance balanced quality пресет",
                    isAdvanced = true,
                ),
                ToggleSetting(
                    key = "checkbox_gpu_path_mode",
                    title = str(R.string.gpu_path_mode_title),
                    summary = str(R.string.gpu_path_mode_summary),
                    defaultValue = false,
                    keywords = "fast gl path direct present opengl быстрый тракт",
                    isAdvanced = true,
                ),
                ToggleSetting(
                    key = "checkbox_Vsync",
                    title = str(R.string.Vsync_title),
                    summary = str(R.string.Vsync_summary),
                    defaultValue = false,
                    keywords = "vsync vertical sync вертикальная синхронизация",
                    isAdvanced = true,
                ),
                ToggleSetting(
                    key = "checkbox_fastVsync",
                    title = str(R.string.fastVsync_title),
                    summary = str(R.string.fastVsync_summary),
                    defaultValue = false,
                    keywords = "fast vsync быстрая синхронизация",
                    isAdvanced = true,
                ),
                ToggleSetting(
                    key = PreferenceConfiguration.ASYNC_PREFER_LOWER_DELAYS_PREF_STRING,
                    title = str(R.string.pref_async_prefer_lower_delays_title),
                    summary = str(R.string.pref_async_prefer_lower_delays_summary),
                    defaultValue = true,
                    keywords = "async lfr lower delays drop stale асинхронный сброс кадров задержка",
                    isAdvanced = true,
                ),
                ToggleSetting(
                    key = "checkbox_immediate_frame_delivery",
                    title = str(R.string.immediate_frame_delivery_title),
                    summary = str(R.string.immediate_frame_delivery_summary),
                    defaultValue = false,
                    keywords = "immediate frame delivery подача кадров без ожидания",
                    isAdvanced = true,
                ),
                ToggleSetting(
                    key = "checkbox_sync_lfr",
                    title = str(R.string.pref_sync_lfr_title),
                    summary = str(R.string.pref_sync_lfr_summary),
                    defaultValue = false,
                    keywords = "sync lfr latest frame rendering свежий кадр",
                    isAdvanced = true,
                ),
                ToggleSetting(
                    key = PreferenceConfiguration.NVIDIA_LATENCY_RECOVERY_PREF_STRING,
                    title = str(R.string.title_checkbox_nvidia_latency_recovery),
                    summary = nvidiaRecoverySummary,
                    defaultValue = true,
                    keywords = "nvidia latency recovery shield decode задержка восстановление кодек",
                    isAdvanced = true,
                ),
                ToggleSetting(
                    key = PreferenceConfiguration.CPU_WARMUP_ENABLED_PREF_STRING,
                    title = str(R.string.pref_cpu_warmup_enable_title),
                    summary = cpuTopologySummary,
                    defaultValue = false,
                    keywords = "cpu boost warmup prime big cores разгон планировщик ядра",
                    isAdvanced = true,
                ),
                ChoiceSetting(
                    key = PreferenceConfiguration.CPU_WARMUP_MODE_PREF_STRING,
                    title = str(R.string.title_list_cpu_warmup_mode),
                    summary = str(R.string.summary_list_cpu_warmup_mode),
                    defaultValue = "big",
                    options = cpuWarmupModeOptions,
                    keywords = "cpu cluster prime big little auto кластер ядер",
                    isAdvanced = true,
                ),
                SliderSetting(
                    key = PreferenceConfiguration.CPU_WARMUP_THREADS_PREF_STRING,
                    title = str(R.string.title_seekbar_cpu_warmup_threads),
                    summary = str(R.string.summary_seekbar_cpu_warmup_threads),
                    defaultValue = 0,
                    minValue = 0,
                    maxValue = 8,
                    stepSize = 1,
                    keywords = "cpu warmup threads workers потоки",
                    isAdvanced = true,
                ),
                SliderSetting(
                    key = PreferenceConfiguration.CPU_WARMUP_DUTY_PREF_STRING,
                    title = str(R.string.title_seekbar_cpu_warmup_duty),
                    summary = str(R.string.summary_seekbar_cpu_warmup_duty),
                    defaultValue = 25,
                    minValue = 5,
                    maxValue = 65,
                    stepSize = 5,
                    unitSuffix = "%",
                    keywords = "cpu warmup duty cycle percentage скважность нагрузка",
                    isAdvanced = true,
                ),
                ToggleSetting(
                    key = "checkbox_custom_decoder_output_timeout_us",
                    title = str(R.string.decoder_output_dequeue_timeout_custom_title),
                    summary = str(R.string.decoder_output_dequeue_timeout_custom_summary),
                    defaultValue = false,
                    keywords = "decoder dequeue timeout custom таймаут декодера",
                    isAdvanced = true,
                ),
                SliderSetting(
                    key = "seekbar_decoder_output_timeout_us",
                    title = str(R.string.decoder_output_dequeue_timeout_title),
                    summary = str(R.string.decoder_output_dequeue_timeout_summary),
                    defaultValue = 50000,
                    minValue = 0,
                    maxValue = 50000,
                    stepSize = 1000,
                    unitSuffix = " us",
                    keywords = "decoder dequeue timeout microseconds мкс таймаут",
                    isAdvanced = true,
                )
            )
        ),
        SettingCategory(
            id = "host_audio",
            title = str(R.string.settings_category_host_audio),
            subtitle = hostAudioSubtitle,
            icon = Icons.Rounded.Dns,
            badgeShape = MaterialShapes.Flower,
            items = listOf(
                ChoiceSetting(
                    key = "list_audio_config",
                    title = str(R.string.title_audio_config_list),
                    summary = str(R.string.summary_audio_config_list),
                    defaultValue = "2",
                    options = audioConfigOptions,
                    keywords = "audio stereo 5.1 7.1 surround звук стерео объемный"
                ),
                ToggleSetting(
                    key = "checkbox_use_virtual_display",
                    title = str(R.string.title_checkbox_use_virtual_display),
                    summary = str(R.string.summary_checkbox_use_virtual_display),
                    defaultValue = false,
                    keywords = "virtual display vibepollo apollo sunshine виртуальный дисплей монитор"
                ),
                ToggleSetting(
                    key = "checkbox_host_audio",
                    title = str(R.string.title_checkbox_host_audio),
                    summary = str(R.string.summary_checkbox_host_audio),
                    defaultValue = false,
                    keywords = "host pc audio speakers звук на пк"
                ),
                ToggleSetting(
                    key = "checkbox_enable_sops",
                    title = str(R.string.title_checkbox_enable_sops),
                    summary = str(R.string.summary_checkbox_enable_sops),
                    defaultValue = true,
                    keywords = "sops optimize game settings оптимизировать настройки игр"
                ),
                ToggleSetting(
                    key = "checkbox_smart_clipboard_sync",
                    title = str(R.string.title_smart_clipboard_sync),
                    summary = str(R.string.summary_smart_clipboard_sync),
                    defaultValue = false,
                    keywords = "clipboard sync copy paste буфер обмена синхронизация"
                ),
                ToggleSetting(
                    key = "checkbox_enable_audiofx",
                    title = str(R.string.title_checkbox_enable_audiofx),
                    summary = str(R.string.summary_checkbox_enable_audiofx),
                    defaultValue = false,
                    keywords = "audiofx equalizer dolby эквалайзер аудио эффекты"
                ),
                ToggleSetting(
                    key = "checkbox_enforce_display_mode",
                    title = str(R.string.title_checkbox_enforce_display_mode),
                    summary = str(R.string.summary_checkbox_enforce_display_mode),
                    defaultValue = false,
                    keywords = "enforce display mode refresh rate принудительный режим",
                    isAdvanced = true,
                ),
                ToggleSetting(
                    key = "checkbox_auto_resolution_scale_factor",
                    title = str(R.string.title_auto_resolution_scale_factor),
                    summary = autoScaleSummary,
                    defaultValue = false,
                    keywords = "auto resolution scale factor host масштаб разрешения хоста",
                    isAdvanced = true,
                ),
                SliderSetting(
                    key = "seekbar_resolution_scale_factor",
                    title = str(R.string.title_resolution_scale_factor),
                    summary = str(R.string.summary_resolution_scale_factor),
                    defaultValue = 100,
                    minValue = 20,
                    maxValue = 200,
                    stepSize = 5,
                    unitSuffix = "%",
                    keywords = "resolution scale factor super sampling коэффициент масштабирования",
                    isAdvanced = true,
                ),
                ToggleSetting(
                    key = "checkbox_smart_clipboard_sync_toast",
                    title = str(R.string.title_smart_clipboard_sync_toast),
                    summary = str(R.string.summary_smart_clipboard_sync_toast),
                    defaultValue = true,
                    keywords = "clipboard toast notification уведомление буфера",
                    isAdvanced = true,
                ),
                ToggleSetting(
                    key = "checkbox_hide_clipboard_content",
                    title = str(R.string.title_hide_clipboard_content),
                    summary = str(R.string.summary_hide_clipboard_content),
                    defaultValue = true,
                    keywords = "hide clipboard sensitive скрывать содержимое буфера",
                    isAdvanced = true,
                )
            )
        ),
        SettingCategory(
            id = "gamepad",
            title = str(R.string.category_gamepad_settings),
            subtitle = gamepadSubtitle,
            icon = Icons.Rounded.SportsEsports,
            badgeShape = MaterialShapes.SoftBurst,
            items = listOf(
                ToggleSetting(
                    key = "checkbox_snappy_input",
                    title = str(R.string.title_checkbox_snappy_input),
                    summary = str(R.string.summary_checkbox_snappy_input),
                    defaultValue = false,
                    keywords = "snappy input thread deadzone отклик ввод"
                ),
                ToggleSetting(
                    key = "checkbox_multi_controller",
                    title = str(R.string.title_checkbox_multi_controller),
                    summary = str(R.string.summary_checkbox_multi_controller),
                    defaultValue = true,
                    keywords = "multi controller gamepad несколько контроллеров геймпадов"
                ),
                ToggleSetting(
                    key = "checkbox_usb_driver",
                    title = str(R.string.title_checkbox_xb1_driver),
                    summary = str(R.string.summary_checkbox_xb1_driver),
                    defaultValue = true,
                    keywords = "usb driver xbox dualsense nintendo драйвер",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_usb_bind_all",
                    title = str(R.string.title_checkbox_usb_bind_all),
                    summary = str(R.string.summary_checkbox_usb_bind_all),
                    defaultValue = false,
                    keywords = "usb bind all override xbox переопределить usb",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_enable_rumble",
                    title = str(R.string.title_enable_rumble),
                    summary = str(R.string.summary_enable_rumble),
                    defaultValue = true,
                    keywords = "rumble vibration haptics вибрация геймпада"
                ),
                ToggleSetting(
                    key = "checkbox_vibrate_fallback",
                    title = str(R.string.title_checkbox_vibrate_fallback),
                    summary = str(R.string.summary_checkbox_vibrate_fallback),
                    defaultValue = false,
                    keywords = "vibrate fallback emulate rumble эмуляция вибрации телефоном",
                    isAdvanced = true
                ),
                SliderSetting(
                    key = "seekbar_vibrate_fallback_strength",
                    title = str(R.string.title_seekbar_vibrate_fallback_strength),
                    summary = str(R.string.summary_seekbar_vibrate_fallback_strength),
                    defaultValue = 100,
                    minValue = 5,
                    maxValue = 100,
                    stepSize = 5,
                    unitSuffix = "%",
                    keywords = "vibrate strength rumble intensity сила интенсивность вибрации",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_enable_device_rumble",
                    title = str(R.string.title_device_rumble),
                    summary = str(R.string.summary_device_rumble),
                    defaultValue = false,
                    keywords = "force device rumble принудительная вибрация устройства",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_flip_face_buttons",
                    title = str(R.string.title_checkbox_flip_face_buttons),
                    summary = str(R.string.summary_checkbox_flip_face_buttons),
                    defaultValue = false,
                    keywords = "flip face buttons ab xy nintendo switch поменять кнопки"
                ),
                SliderSetting(
                    key = "seekbar_deadzone",
                    title = str(R.string.title_seekbar_deadzone),
                    summary = str(R.string.summary_seekbar_deadzone),
                    defaultValue = 5,
                    minValue = 1,
                    maxValue = 50,
                    stepSize = 1,
                    unitSuffix = "%",
                    keywords = "deadzone analog stick мертвая зона стик"
                ),
                ToggleSetting(
                    key = "checkbox_gamepad_touchpad_as_mouse",
                    title = str(R.string.title_checkbox_gamepad_touchpad_as_mouse),
                    summary = str(R.string.summary_checkbox_gamepad_touchpad_as_mouse),
                    defaultValue = false,
                    keywords = "dualshock dualsense touchpad mouse тачпад геймпада как мышь",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_gamepad_motion_sensors",
                    title = str(R.string.title_checkbox_gamepad_motion_sensors),
                    summary = str(R.string.summary_checkbox_gamepad_motion_sensors),
                    defaultValue = true,
                    keywords = "gyro accelerometer motion sensors гироскоп датчики движения"
                ),
                ToggleSetting(
                    key = "checkbox_gamepad_motion_fallback",
                    title = str(R.string.title_checkbox_gamepad_motion_fallback),
                    summary = str(R.string.summary_checkbox_gamepad_motion_fallback),
                    defaultValue = false,
                    keywords = "emulate motion sensor fallback гироскоп телефона",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_force_device_motion",
                    title = str(R.string.title_checkbox_force_device_motion),
                    summary = str(R.string.summary_checkbox_force_device_motion),
                    defaultValue = false,
                    keywords = "force device motion всегда использовать датчики движения устройства",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_mouse_emulation",
                    title = str(R.string.title_checkbox_mouse_emulation),
                    summary = str(R.string.summary_checkbox_mouse_emulation),
                    defaultValue = true,
                    keywords = "mouse emulation start button эмуляция мыши на геймпаде",
                    isAdvanced = true
                ),
                ChoiceSetting(
                    key = "analog_scrolling",
                    title = str(R.string.title_analog_scrolling),
                    summary = str(R.string.summary_analog_scrolling),
                    defaultValue = "right",
                    options = analogScrollOptions,
                    keywords = "analog stick scroll прокрутка стиком",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_enable_joyconfix",
                    title = str(R.string.title_joyconfix),
                    summary = str(R.string.summary_joyconfix),
                    defaultValue = false,
                    keywords = "nintendo switch joycon fix джойкон",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_gamepad_enable_battery_report",
                    title = str(R.string.title_checkbox_gamepad_enable_battery_report),
                    summary = str(R.string.summary_checkbox_gamepad_enable_battery_report),
                    defaultValue = true,
                    keywords = "gamepad battery report заряд батареи геймпада",
                    isAdvanced = true
                )
            )
        ),
        SettingCategory(
            id = "touch_mouse",
            title = str(R.string.category_input_settings),
            subtitle = touchMouseSubtitle,
            icon = Icons.Rounded.TouchApp,
            badgeShape = MaterialShapes.Ghostish,
            items = listOf(
                ChoiceSetting(
                    key = "mouse_mode_list",
                    title = str(R.string.title_touchscreen_mode),
                    summary = str(R.string.summary_touchscreen_mode),
                    defaultValue = "0",
                    options = mouseModeOptions,
                    keywords = "touchscreen mode multi touch trackpad mouse режим сенсора тачпад мышь"
                ),
                ToggleSetting(
                    key = "checkbox_remember_mouse_mode",
                    title = str(R.string.title_checkbox_remember_mouse_mode),
                    summary = str(R.string.summary_checkbox_remember_mouse_mode),
                    defaultValue = false,
                    keywords = "remember mouse mode запоминать режим мыши"
                ),
                ToggleSetting(
                    key = "checkbox_absolute_mouse_mode",
                    title = str(R.string.title_checkbox_absolute_mouse_mode),
                    summary = str(R.string.summary_checkbox_absolute_mouse_mode),
                    defaultValue = false,
                    keywords = "remote desktop absolute mouse удаленный рабочий стол"
                ),
                ToggleSetting(
                    key = "checkbox_mouse_local_cursor",
                    title = str(R.string.title_checkbox_mouse_local_cursor),
                    summary = str(R.string.summary_checkbox_mouse_local_cursor),
                    defaultValue = false,
                    keywords = "local mouse cursor локальный курсор мыши",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_mouse_nav_buttons",
                    title = str(R.string.title_checkbox_mouse_nav_buttons),
                    summary = str(R.string.summary_checkbox_mouse_nav_buttons),
                    defaultValue = false,
                    keywords = "mouse forward back navigation buttons боковые кнопки мыши",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_multi_touch_gestures",
                    title = str(R.string.title_checkbox_multi_touch_gestures),
                    summary = str(R.string.summary_checkbox_multi_touch_gestures),
                    defaultValue = false,
                    keywords = "multi touch gestures 3 4 5 finger мультитач жесты"
                ),
                SliderSetting(
                    key = "seekbar_trackpad_sensitivity_x",
                    title = str(R.string.title_trackpad_sensitivity_x),
                    summary = str(R.string.summary_trackpad_sensitivity_x),
                    defaultValue = 100,
                    minValue = -500,
                    maxValue = 500,
                    stepSize = 10,
                    unitSuffix = "%",
                    keywords = "trackpad sensitivity x чувствительность трекпада",
                    isAdvanced = true
                ),
                SliderSetting(
                    key = "seekbar_trackpad_sensitivity_y",
                    title = str(R.string.title_trackpad_sensitivity_y),
                    summary = str(R.string.summary_trackpad_sensitivity_y),
                    defaultValue = 100,
                    minValue = -500,
                    maxValue = 500,
                    stepSize = 10,
                    unitSuffix = "%",
                    keywords = "trackpad sensitivity y чувствительность трекпада",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_trackpad_drag_drop_vibration",
                    title = str(R.string.title_trackpad_drag_drop_vibration),
                    summary = str(R.string.summary_trackpad_drag_drop_vibration),
                    defaultValue = false,
                    keywords = "trackpad drag drop vibration перетаскивание вибрация",
                    isAdvanced = true
                ),
                SliderSetting(
                    key = "seekbar_trackpad_drag_drop_threshold",
                    title = str(R.string.title_trackpad_drag_drop_threshold),
                    summary = str(R.string.summary_trackpad_drag_drop_threshold),
                    defaultValue = 250,
                    minValue = 50,
                    maxValue = 1000,
                    stepSize = 25,
                    unitSuffix = " ms",
                    keywords = "trackpad drag drop hold threshold время удержания",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_trackpad_swap_axis",
                    title = str(R.string.title_trackpad_swap_axis),
                    summary = str(R.string.summary_trackpad_swap_axis),
                    defaultValue = false,
                    keywords = "trackpad swap axis поменять оси трекпада",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_enable_touch_sensitivity",
                    title = str(R.string.title_touch_sensitivity),
                    summary = str(R.string.summary_touch_sensitivity),
                    defaultValue = false,
                    keywords = "touch screen sensitivity чувствительность сенсора",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_enable_global_touch_sensitivity",
                    title = str(R.string.title_global_touch_sensitivity),
                    summary = str(R.string.summary_global_touch_sensitivity),
                    defaultValue = false,
                    keywords = "global touch sensitivity глобальная чувствительность",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_enable_touch_sensitivity_rotation_auto",
                    title = str(R.string.title_auto_touch_sensitivity),
                    summary = str(R.string.summary_auto_touch_sensitivity),
                    defaultValue = true,
                    keywords = "auto center touch sensitivity возврат в центр",
                    isAdvanced = true
                ),
                SliderSetting(
                    key = "seekbar_touch_sensitivity_opacity_x",
                    title = str(R.string.title_touch_sensitivity_x),
                    summary = str(R.string.summary_touch_sensitivity_x),
                    defaultValue = 100,
                    minValue = 10,
                    maxValue = 400,
                    stepSize = 10,
                    unitSuffix = "%",
                    keywords = "touch sensitivity x axis чувствительность экрана по x",
                    isAdvanced = true
                ),
                SliderSetting(
                    key = "seekbar_touch_sensitivity_opacity_y",
                    title = str(R.string.title_touch_sensitivity_y),
                    summary = str(R.string.summary_touch_sensitivity_y),
                    defaultValue = 100,
                    minValue = 10,
                    maxValue = 400,
                    stepSize = 10,
                    unitSuffix = "%",
                    keywords = "touch sensitivity y axis чувствительность экрана по y",
                    isAdvanced = true
                ),
                SliderSetting(
                    key = "seekbar_touchpad_sensitivity_opacity",
                    title = str(R.string.title_touchpad_sensitivity_x),
                    summary = str(R.string.summary_touchpad_sensitivity_x),
                    defaultValue = 100,
                    minValue = 20,
                    maxValue = 200,
                    stepSize = 5,
                    unitSuffix = "%",
                    keywords = "virtual touchpad sensitivity x чувствительность тачпада",
                    isAdvanced = true
                ),
                SliderSetting(
                    key = "seekbar_touchpad_sensitivity_y_opacity",
                    title = str(R.string.title_touchpad_sensitivity_y),
                    summary = str(R.string.summary_touchpad_sensitivity_y),
                    defaultValue = 100,
                    minValue = 20,
                    maxValue = 200,
                    stepSize = 5,
                    unitSuffix = "%",
                    keywords = "virtual touchpad sensitivity y чувствительность тачпада",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_force_qwerty",
                    title = str(R.string.title_checkbox_force_qwerty),
                    summary = str(R.string.summary_checkbox_force_qwerty),
                    defaultValue = true,
                    keywords = "qwerty keyboard layout принудительная раскладка клавиатуры",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_back_as_meta",
                    title = str(R.string.title_checkbox_back_as_meta),
                    summary = str(R.string.summary_back_as_meta),
                    defaultValue = false,
                    keywords = "back button meta win key кнопка назад как win",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_back_as_guide",
                    title = str(R.string.title_back_as_guide),
                    summary = str(R.string.summary_back_as_guide),
                    defaultValue = false,
                    keywords = "back button guide xbox home ps кнопка назад как guide",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_ignore_synth_events",
                    title = str(R.string.title_checkbox_ignore_synth_events),
                    summary = str(R.string.summary_ignore_synth_events),
                    defaultValue = false,
                    keywords = "ignore synthetic events синтетические события",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_enable_commit_text",
                    title = str(R.string.title_enable_commit_text),
                    summary = str(R.string.summary_enable_commit_text),
                    defaultValue = false,
                    keywords = "commit text swipe voice typing голосовой ввод свайп"
                )
            )
        ),
        SettingCategory(
            id = "osc_keyboard",
            title = str(R.string.category_on_screen_controls_settings),
            subtitle = oscSubtitle,
            icon = Icons.Rounded.Gamepad,
            badgeShape = MaterialShapes.Clover4Leaf,
            items = listOf(
                ToggleSetting(
                    key = "checkbox_show_onscreen_controls",
                    title = str(R.string.title_checkbox_show_onscreen_controls),
                    summary = str(R.string.summary_checkbox_show_onscreen_controls),
                    defaultValue = false,
                    keywords = "osc onscreen controls virtual gamepad экранные кнопки виртуальный геймпад"
                ),
                ToggleSetting(
                    key = "checkbox_hide_osc_when_has_gamepad",
                    title = str(R.string.title_hide_osc_when_has_gamepad),
                    summary = str(R.string.summary_hide_osc_when_has_gamepad),
                    defaultValue = true,
                    keywords = "hide osc gamepad connected скрывать экранные кнопки"
                ),
                ToggleSetting(
                    key = "checkbox_vibrate_osc",
                    title = str(R.string.title_checkbox_vibrate_osc),
                    summary = str(R.string.summary_checkbox_vibrate_osc),
                    defaultValue = true,
                    keywords = "vibrate osc buttons вибрация экранных кнопок",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_only_show_L3R3",
                    title = str(R.string.title_only_l3r3),
                    summary = str(R.string.summary_only_l3r3),
                    defaultValue = false,
                    keywords = "only l3 r3 buttons только кнопки стиков",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_show_guide_button",
                    title = str(R.string.title_show_guide_button),
                    summary = str(R.string.summary_show_guide_button),
                    defaultValue = true,
                    keywords = "show guide home button показать кнопку guide",
                    isAdvanced = true
                ),
                SliderSetting(
                    key = "seekbar_osc_opacity",
                    title = str(R.string.title_osc_opacity),
                    summary = str(R.string.summary_osc_opacity),
                    defaultValue = 90,
                    minValue = 15,
                    maxValue = 100,
                    stepSize = 5,
                    unitSuffix = "%",
                    keywords = "osc opacity transparency прозрачность экранных кнопок"
                ),
                ToggleSetting(
                    key = "checkbox_onscreen_style_official",
                    title = str(R.string.title_checkbox_onscreen_style_official),
                    summary = str(R.string.summary_checkbox_onscreen_style_official),
                    defaultValue = false,
                    keywords = "official skin style официальная тема",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_enable_analog_stick_new",
                    title = str(R.string.title_free_analog_stick),
                    summary = str(R.string.summary_free_analog_stick),
                    defaultValue = false,
                    keywords = "free analog stick floating плавающий свободный стик",
                    isAdvanced = true
                ),
                SliderSetting(
                    key = "seekbar_osc_free_analog_stick_opacity",
                    title = str(R.string.title_free_analog_stick_opacity),
                    summary = str(R.string.summary_free_analog_stick_opacity),
                    defaultValue = 20,
                    minValue = 0,
                    maxValue = 100,
                    stepSize = 5,
                    unitSuffix = "%",
                    keywords = "free stick opacity прозрачность свободного стика",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_enable_keyboard",
                    title = str(R.string.title_show_virtual_key),
                    summary = str(R.string.summary_show_virtual_key),
                    defaultValue = false,
                    keywords = "virtual keys special keyboard виртуальные спец клавиши"
                ),
                ToggleSetting(
                    key = "checkbox_vibrate_keyboard",
                    title = str(R.string.title_checkbox_vibrate_osc),
                    summary = str(R.string.summary_vibrate_keyboard),
                    defaultValue = false,
                    keywords = "vibrate virtual keyboard вибрация виртуальных клавиш",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_enable_keyboard_square",
                    title = str(R.string.title_square_buttons),
                    summary = str(R.string.summary_square_buttons),
                    defaultValue = false,
                    keywords = "square buttons квадратные кнопки",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_enable_clear_default_special_button",
                    title = str(R.string.title_clear_default_special_button),
                    summary = str(R.string.summary_clear_default_special_button),
                    defaultValue = false,
                    keywords = "disable default special keys отключить стандартные спец клавиши",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_enable_sticky_modifier_key_virtual_keyboard",
                    title = str(R.string.title_checkbox_enable_sticky_modifier_key_virtual_keyboard),
                    summary = str(R.string.summary_checkbox_enable_sticky_modifier_key_virtual_keyboard),
                    defaultValue = true,
                    keywords = "sticky modifier shift ctrl alt фиксированные модификаторы",
                    isAdvanced = true
                ),
                SliderSetting(
                    key = "seekbar_keyboard_axi_opacity",
                    title = str(R.string.title_keyboard_opacity),
                    summary = str(R.string.summary_osc_opacity),
                    defaultValue = 90,
                    minValue = 30,
                    maxValue = 100,
                    stepSize = 5,
                    unitSuffix = "%",
                    keywords = "virtual keyboard opacity прозрачность клавиатуры",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "onscreen_keyboard_autofit",
                    title = str(R.string.title_onscreen_keyboard_autofit),
                    summary = str(R.string.summary_onscreen_keyboard_autofit),
                    defaultValue = false,
                    keywords = "keyboard autofit disable автоподгон размера клавиатуры",
                    isAdvanced = true
                ),
                SliderSetting(
                    key = "seekbar_onscreen_keyboard_height",
                    title = str(R.string.title_onscreen_keyboard_height),
                    summary = str(R.string.summary_onscreen_keyboard_height),
                    defaultValue = 200,
                    minValue = 150,
                    maxValue = 800,
                    stepSize = 10,
                    unitSuffix = " dp",
                    keywords = "keyboard height высота клавиатуры",
                    isAdvanced = true
                ),
                SliderSetting(
                    key = "seekbar_onscreen_keyboard_width",
                    title = str(R.string.title_onscreen_keyboard_width),
                    summary = str(R.string.summary_onscreen_keyboard_width),
                    defaultValue = 1000,
                    minValue = 300,
                    maxValue = 1000,
                    stepSize = 25,
                    unitSuffix = " permille",
                    keywords = "keyboard width ширина клавиатуры",
                    isAdvanced = true
                ),
                ChoiceSetting(
                    key = "list_onscreen_keyboard_align_mode",
                    title = str(R.string.title_onscreen_keyboard_align_mode),
                    summary = str(R.string.summary_onscreen_keyboard_align_mode),
                    defaultValue = "center",
                    options = keyboardAlignOptions,
                    keywords = "keyboard align left center right выравнивание клавиатуры",
                    inlineControl = true
                )
            )
        ),
        SettingCategory(
            id = "ui_overlay",
            title = str(R.string.category_ui_settings),
            subtitle = uiOverlaySubtitle,
            icon = Icons.Rounded.Palette,
            badgeShape = MaterialShapes.Diamond,
            items = listOf(
                ChoiceSetting(
                    key = PREF_THEME_MODE,
                    title = str(R.string.title_theme_mode),
                    summary = str(R.string.summary_theme_mode),
                    defaultValue = THEME_MODE_SYSTEM,
                    options = themeModeOptions,
                    keywords = "theme mode dark light system appearance тема светлая темная",
                    inlineControl = true
                ),
                ChoiceSetting(
                    key = PREF_COLOR_PALETTE,
                    title = str(R.string.title_color_palette),
                    summary = str(R.string.summary_color_palette),
                    defaultValue = COLOR_PALETTE_DYNAMIC,
                    options = colorPaletteOptions,
                    keywords = "color palette dynamic material you vibemis палитра цвета",
                    inlineControl = true
                ),
                ChoiceSetting(
                    key = "list_languages",
                    title = str(R.string.title_language_list),
                    summary = str(R.string.summary_language_list),
                    defaultValue = "default",
                    options = languageOptions,
                    keywords = "language locale russian english язык интерфейс русский"
                ),
                ToggleSetting(
                    key = "checkbox_full_screen",
                    title = str(R.string.title_checkbox_full_screen),
                    summary = str(R.string.summary_checkbox_full_screen),
                    defaultValue = true,
                    keywords = "fullscreen immersive bars полноэкранный режим"
                ),
                ToggleSetting(
                    key = "checkbox_enable_pip",
                    title = str(R.string.title_checkbox_enable_pip),
                    summary = str(R.string.summary_checkbox_enable_pip),
                    defaultValue = false,
                    keywords = "pip picture in picture картинка в картинке"
                ),
                ToggleSetting(
                    key = "checkbox_small_icon_mode",
                    title = str(R.string.title_checkbox_small_icon_mode),
                    summary = str(R.string.summary_checkbox_small_icon_mode),
                    defaultValue = false,
                    keywords = "small box art icons маленькие иконки сетка"
                ),
                ToggleSetting(
                    key = "checkbox_resume_without_confirm",
                    title = str(R.string.title_checkbox_resume_without_confirm),
                    summary = str(R.string.summary_checkbox_resume_without_confirm),
                    defaultValue = false,
                    keywords = "quick resume without confirm быстрое возобновление",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_enable_quit_dialog",
                    title = str(R.string.title_quit_dialog),
                    summary = str(R.string.summary_quit_dialog),
                    defaultValue = true,
                    keywords = "back menu quit dialog быстрое меню назад",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_enable_floating_button",
                    title = str(R.string.title_floating_button),
                    summary = str(R.string.summary_floating_button),
                    defaultValue = false,
                    keywords = "floating button плавающая кнопка",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_show_overlay_zoom_toggle_button",
                    title = str(R.string.title_show_overlay_zoom_toggle_button),
                    summary = str(R.string.summary_show_overlay_zoom_toggle_button),
                    defaultValue = false,
                    keywords = "zoom pan overlay toggle button масштабирование зум кнопка",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_remember_zoom_pan",
                    title = str(R.string.title_checkbox_remember_zoom_pan),
                    summary = str(R.string.summary_checkbox_remember_zoom_pan),
                    defaultValue = false,
                    keywords = "remember zoom pan запоминать масштаб",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_disable_warnings",
                    title = str(R.string.title_checkbox_disable_warnings),
                    summary = str(R.string.summary_checkbox_disable_warnings),
                    defaultValue = false,
                    keywords = "disable warnings connection messages отключить предупреждения",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_enable_post_stream_toast",
                    title = str(R.string.title_enable_post_stream_toast),
                    summary = str(R.string.summary_enable_post_stream_toast),
                    defaultValue = false,
                    keywords = "post stream latency toast отчет о задержке после стрима",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_enable_perf_overlay",
                    title = str(R.string.title_enable_perf_overlay),
                    summary = str(R.string.summary_enable_perf_overlay),
                    defaultValue = false,
                    keywords = "performance stats overlay telemetry hud статистика оверлей"
                ),
                ToggleSetting(
                    key = "checkbox_enable_perf_overlay_lite",
                    title = str(R.string.title_checkbox_enable_perf_overlay_lite),
                    summary = str(R.string.summary_checkbox_enable_perf_overlay_lite),
                    defaultValue = false,
                    keywords = "performance overlay lite упрощенный режим статистики",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_enable_perf_overlay_lite_advanced",
                    title = str(R.string.title_checkbox_enable_perf_overlay_lite_advanced),
                    summary = str(R.string.summary_checkbox_enable_perf_overlay_lite_advanced),
                    defaultValue = false,
                    keywords = "lite+ advanced overlay расширенная статистика",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_enable_perf_overlay_lite_oledshift",
                    title = str(R.string.title_checkbox_enable_perf_overlay_lite_oledshift),
                    summary = str(R.string.summary_checkbox_enable_perf_overlay_lite_oledshift),
                    defaultValue = true,
                    keywords = "oled burn-in pixel shift защита от выгорания",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_enable_perf_overlay_lite_dialog",
                    title = str(R.string.title_checkbox_enable_perf_overlay_lite_dialog),
                    summary = str(R.string.summary_checkbox_enable_perf_overlay_lite_dialog),
                    defaultValue = false,
                    keywords = "lite overlay tap menu нажатие на статистику открывает меню",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_enable_perf_overlay_mini",
                    title = str(R.string.title_checkbox_enable_perf_overlay_mini),
                    summary = str(R.string.summary_checkbox_enable_perf_overlay_mini),
                    defaultValue = false,
                    keywords = "mini overlay компактный мини оверлей",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_enable_perf_overlay_bottom",
                    title = str(R.string.title_checkbox_enable_perf_overlay_bottom),
                    summary = str(R.string.summary_checkbox_enable_perf_overlay_bottom),
                    defaultValue = false,
                    keywords = "overlay bottom переместить панель вниз",
                    isAdvanced = true
                ),
                ToggleSetting(
                    key = "checkbox_show_server_stats",
                    title = str(R.string.title_checkbox_show_server_stats),
                    summary = str(R.string.summary_checkbox_show_server_stats),
                    defaultValue = false,
                    keywords = "server stats host metrics статистика сервера хоста",
                    isAdvanced = true
                )
            )
        ),
        SettingCategory(
            id = "diagnostics",
            title = str(R.string.title_debug_info),
            subtitle = str(R.string.summary_debug_info),
            icon = Icons.Rounded.Build,
            badgeShape = MaterialShapes.Slanted,
            items = listOf(
                ActionSetting(
                    key = "action_reset_osc",
                    title = str(R.string.title_reset_osc),
                    summary = str(R.string.summary_reset_osc),
                    keywords = "reset osc layout сбросить расположение кнопок",
                    onClick = {
                        context.getSharedPreferences("osc_preferences", Context.MODE_PRIVATE)
                            .edit()
                            .clear()
                            .apply()
                    }
                ),
                ActionSetting(
                    key = "action_reset_keyboard",
                    title = str(R.string.title_reset_keyboard_layout),
                    summary = str(R.string.summary_reset_keyboard_layout),
                    keywords = "reset virtual keyboard layout сбросить раскладку клавиатуры",
                    onClick = {
                        context.getSharedPreferences("OSC_Keyboard", Context.MODE_PRIVATE)
                            .edit()
                            .clear()
                            .apply()
                    }
                ),
                ActionSetting(
                    key = "action_debug_info",
                    title = str(R.string.title_debug_info),
                    summary = str(R.string.summary_debug_info),
                    keywords = "debug info vibration test codecs отладка тест вибрации декодеры",
                    onClick = {
                        context.startActivity(Intent(context, DebugInfoActivity::class.java))
                    }
                )
            )
        )
    )

    val trimmedQuery = searchQuery.trim().lowercase()
    val matchingQuickActions = if (trimmedQuery.isEmpty()) {
        emptyList()
    } else {
        quickSetupActions.filter { item ->
            item.title.lowercase().contains(trimmedQuery) ||
                item.summary.lowercase().contains(trimmedQuery) ||
                item.keywords.lowercase().contains(trimmedQuery) ||
                item.key.lowercase().contains(trimmedQuery)
        }
    }
    val searchCategories = if (trimmedQuery.isEmpty()) {
        emptyList()
    } else {
        categories.mapNotNull { category ->
            val matchingItems = category.items.filter { item ->
                item.title.lowercase().contains(trimmedQuery) ||
                    item.summary.lowercase().contains(trimmedQuery) ||
                    item.keywords.lowercase().contains(trimmedQuery) ||
                    item.key.lowercase().contains(trimmedQuery) ||
                    category.title.lowercase().contains(trimmedQuery)
            }
            if (matchingItems.isEmpty()) null else category.copy(items = matchingItems)
        }
    }

    val totalSettingsCount = quickSetupActions.size + categories.sumOf { it.items.size }
    val reducedMotion = LocalReducedMotion.current
    val motionScheme = MaterialTheme.motionScheme

    infoDialogEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { infoDialogEntry = null },
            shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = entry.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = { infoDialogEntry = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        val adaptive = rememberVibemisAdaptiveLayout()
        val isTwoPane = adaptive.useTwoPane
        // Persistent Global Search Header (searches across all categories including advanced settings)
        val searchInteractionSource = remember { MutableInteractionSource() }
        val isSearchFocused by searchInteractionSource.collectIsFocusedAsState()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 16.dp,
                    vertical = if (adaptive.isCompactHeight) 4.dp else 8.dp
                ),
            contentAlignment = Alignment.Center
        ) {
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                interactionSource = searchInteractionSource,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 840.dp)
                    .heightIn(min = if (adaptive.isCompactHeight) 48.dp else 52.dp)
                    .then(
                        if (isSearchFocused) {
                            Modifier.border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape
                            )
                        } else {
                            Modifier
                        }
                    ),
                singleLine = true,
                shape = CircleShape,
                placeholder = {
                    Text(
                        text = "${stringResource(R.string.searchpreference_search)} ($totalSettingsCount)",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = stringResource(R.string.searchpreference_search),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.searchpreference_clear)
                            )
                        }
                    }
                } else null,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                )
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (isTwoPane) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    // Master Navigation Pane
                    Surface(
                        color = Color.Transparent,
                        modifier = Modifier
                            .weight(0.40f)
                            .widthIn(min = 280.dp, max = 380.dp)
                            .fillMaxHeight()
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            item(key = "quick_setup_pane") {
                                QuickSetupSection(
                                    actions = quickSetupActions,
                                    compact = true
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            itemsIndexed(categories, key = { _, cat -> cat.id }) { index, category ->
                                CategoryHubRow(
                                    category = category,
                                    selected = trimmedQuery.isEmpty() && twoPaneCategoryId == category.id,
                                    shape = categoryGroupItemShape(index, categories.lastIndex),
                                    onClick = {
                                        twoPaneCategoryId = category.id
                                        if (trimmedQuery.isNotEmpty()) {
                                            searchQuery = ""
                                        }
                                    }
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(vertical = 10.dp)
                            .width(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    )

                    // Detail / Search Results Pane
                    Box(
                        modifier = Modifier
                            .weight(0.60f)
                            .fillMaxHeight()
                    ) {
                        if (trimmedQuery.isNotEmpty()) {
                            SearchResultsPane(
                                matchingQuickActions = matchingQuickActions,
                                matchingCategories = searchCategories,
                                prefs = prefs,
                                refreshTick = refreshTick,
                                onChanged = { refreshTick++ },
                                onShowInfo = { infoDialogEntry = it }
                            )
                        } else {
                            val selectedCategory = categories.firstOrNull { it.id == twoPaneCategoryId }
                                ?: categories.first()
                            val expandedAdvanced = expandedAdvancedMap[selectedCategory.id] == true
                            CategoryDetailPane(
                                category = selectedCategory,
                                prefs = prefs,
                                refreshTick = refreshTick,
                                expandedAdvanced = expandedAdvanced,
                                showBackBar = false,
                                onToggleAdvanced = {
                                    expandedAdvancedMap[selectedCategory.id] = !expandedAdvanced
                                },
                                onBack = {},
                                onChanged = { refreshTick++ },
                                onShowInfo = { infoDialogEntry = it }
                            )
                        }
                    }
                }
            } else {
                // Single-Pane Phone Flow: Global Search OR Animated Hub <-> Category Detail
                if (trimmedQuery.isNotEmpty()) {
                    SearchResultsPane(
                        matchingQuickActions = matchingQuickActions,
                        matchingCategories = searchCategories,
                        prefs = prefs,
                        refreshTick = refreshTick,
                        onChanged = { refreshTick++ },
                        onShowInfo = { infoDialogEntry = it }
                    )
                } else {
                    val activeCategory = openedCategoryId?.let { id ->
                        categories.firstOrNull { it.id == id }
                    }
                    BackHandler(enabled = activeCategory != null) {
                        openedCategoryId = null
                    }

                    AnimatedContent(
                        targetState = activeCategory,
                        transitionSpec = {
                            if (reducedMotion) {
                                fadeIn(snap()) togetherWith fadeOut(snap())
                            } else if (targetState != null && initialState == null) {
                                (slideInHorizontally(motionScheme.defaultSpatialSpec()) { it / 4 } +
                                    fadeIn(motionScheme.fastEffectsSpec())) togetherWith
                                    (slideOutHorizontally(motionScheme.defaultSpatialSpec()) { -it / 6 } +
                                        fadeOut(motionScheme.fastEffectsSpec()))
                            } else {
                                (slideInHorizontally(motionScheme.defaultSpatialSpec()) { -it / 6 } +
                                    fadeIn(motionScheme.fastEffectsSpec())) togetherWith
                                    (slideOutHorizontally(motionScheme.defaultSpatialSpec()) { it / 4 } +
                                        fadeOut(motionScheme.fastEffectsSpec()))
                            }
                        },
                        label = "settings_hub_detail",
                        modifier = Modifier.fillMaxSize()
                    ) { category ->
                        if (category == null) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.TopCenter
                            ) {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .widthIn(max = 840.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    item(key = "quick_setup_hub") {
                                        QuickSetupSection(
                                            actions = quickSetupActions,
                                            compact = false
                                        )
                                        Spacer(modifier = Modifier.height(11.dp))
                                    }
                                    itemsIndexed(categories, key = { _, item -> item.id }) { index, itemCategory ->
                                        CategoryHubRow(
                                            category = itemCategory,
                                            selected = false,
                                            shape = categoryGroupItemShape(index, categories.lastIndex),
                                            onClick = { openedCategoryId = itemCategory.id }
                                        )
                                    }
                                    item(key = "hub_bottom_space") {
                                        Spacer(modifier = Modifier.height(20.dp))
                                    }
                                }
                            }
                        } else {
                            val expandedAdvanced = expandedAdvancedMap[category.id] == true
                            CategoryDetailPane(
                                category = category,
                                prefs = prefs,
                                refreshTick = refreshTick,
                                expandedAdvanced = expandedAdvanced,
                                showBackBar = true,
                                onToggleAdvanced = {
                                    expandedAdvancedMap[category.id] = !expandedAdvanced
                                },
                                onBack = { openedCategoryId = null },
                                onChanged = { refreshTick++ },
                                onShowInfo = { infoDialogEntry = it }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun categoryGroupItemShape(index: Int, lastIndex: Int): Shape {
    val outer = MaterialTheme.shapes.large
    val inner = RoundedCornerShape(6.dp)
    return when {
        lastIndex <= 0 -> outer
        index == 0 -> RoundedCornerShape(
            topStart = outer.topStart,
            topEnd = outer.topEnd,
            bottomStart = inner.bottomStart,
            bottomEnd = inner.bottomEnd,
        )
        index == lastIndex -> RoundedCornerShape(
            topStart = inner.topStart,
            topEnd = inner.topEnd,
            bottomStart = outer.bottomStart,
            bottomEnd = outer.bottomEnd,
        )
        else -> inner
    }
}

@Composable
private fun QuickSetupSection(
    actions: List<ActionSetting>,
    compact: Boolean
) {
    if (actions.isEmpty()) return
    val adaptive = rememberVibemisAdaptiveLayout()
    val useSideBySide = !compact && adaptive.widthDp >= 600

    if (compact) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            actions.forEachIndexed { index, action ->
                val isPrimary = index == 0
                QuickSetupCompactCard(
                    action = action,
                    icon = if (isPrimary) Icons.Rounded.DisplaySettings else Icons.Rounded.Tune,
                    containerColor = if (isPrimary) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = if (isPrimary) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    iconContainerColor = if (isPrimary) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    iconContentColor = if (isPrimary) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }
        return
    }

    if (!useSideBySide) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.forEachIndexed { index, action ->
                val isPrimary = index == 0
                QuickSetupCard(
                    action = action,
                    icon = if (isPrimary) Icons.Rounded.DisplaySettings else Icons.Rounded.Tune,
                    containerColor = if (isPrimary) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = if (isPrimary) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    summaryColor = if (isPrimary) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    iconContainerColor = if (isPrimary) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    iconContentColor = if (isPrimary) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                    showChevron = isPrimary,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            actions.forEachIndexed { index, action ->
                val isPrimary = index == 0
                QuickSetupCard(
                    action = action,
                    icon = if (isPrimary) Icons.Rounded.DisplaySettings else Icons.Rounded.Tune,
                    containerColor = if (isPrimary) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = if (isPrimary) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    summaryColor = if (isPrimary) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    iconContainerColor = if (isPrimary) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    iconContentColor = if (isPrimary) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                    showChevron = isPrimary,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }
    }
}

@Composable
private fun QuickSetupCard(
    action: ActionSetting,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    summaryColor: Color,
    iconContainerColor: Color,
    iconContentColor: Color,
    showChevron: Boolean,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember(action.key) { MutableInteractionSource() }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier
            .heightIn(min = 60.dp)
            .clip(MaterialTheme.shapes.large)
            .expressiveFocusAndPress(
                interactionSource = interactionSource,
                shape = MaterialTheme.shapes.large,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = action.onClick
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = iconContainerColor,
                contentColor = iconContentColor,
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = action.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (action.summary.isNotBlank()) {
                    Text(
                        text = action.summary.replace('\n', ' '),
                        style = MaterialTheme.typography.bodySmall,
                        color = summaryColor,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (showChevron) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun QuickSetupCompactCard(
    action: ActionSetting,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    iconContainerColor: Color,
    iconContentColor: Color,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember(action.key) { MutableInteractionSource() }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier
            .heightIn(min = 52.dp)
            .clip(MaterialTheme.shapes.medium)
            .expressiveFocusAndPress(
                interactionSource = interactionSource,
                shape = MaterialTheme.shapes.medium,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = action.onClick
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(9.dp),
                color = iconContainerColor,
                contentColor = iconContentColor,
                modifier = Modifier.size(28.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Text(
                text = action.title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun CategoryHubRow(
    category: SettingCategory,
    selected: Boolean,
    shape: Shape = MaterialTheme.shapes.large,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember(category.id) { MutableInteractionSource() }
    val coreCount = remember(category.items) { category.items.count { !it.isAdvanced } }
    val totalCount = category.items.size
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val badgeContainerColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val badgeContentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.primary
    }

    Surface(
        shape = shape,
        color = containerColor,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(shape)
            .expressiveFocusAndPress(
                interactionSource = interactionSource,
                shape = shape,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = badgeContainerColor,
                contentColor = badgeContentColor,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = category.icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = category.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = category.subtitle.ifBlank {
                        stringResource(R.string.settings_hub_subtitle, coreCount, totalCount)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CategoryDetailPane(
    category: SettingCategory,
    prefs: SharedPreferences,
    refreshTick: Int,
    expandedAdvanced: Boolean,
    showBackBar: Boolean,
    onToggleAdvanced: () -> Unit,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    onShowInfo: (SettingEntry) -> Unit,
) {
    val coreEntries = remember(category.id, category.items) {
        category.items.filter { !it.isAdvanced }
    }
    val advancedEntries = remember(category.id, category.items) {
        category.items.filter { it.isAdvanced }
    }
    val reducedMotion = LocalReducedMotion.current
    val motionScheme = MaterialTheme.motionScheme
    val chevronRotation by animateFloatAsState(
        targetValue = if (expandedAdvanced) 180f else 0f,
        animationSpec = if (reducedMotion) snap() else motionScheme.fastSpatialSpec(),
        label = "advanced_chevron_rotation"
    )

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 840.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(key = "detail_header_${category.id}") {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (showBackBar) {
                            val backInteraction = remember(category.id) { MutableInteractionSource() }
                            IconButton(
                                onClick = onBack,
                                interactionSource = backInteraction,
                                modifier = Modifier
                                    .size(48.dp)
                                    .expressiveFocusAndPress(
                                        interactionSource = backInteraction,
                                        shape = MaterialTheme.shapes.medium
                                    )
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = stringResource(R.string.settings_all_categories),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        } else {
                            VibemisShapeBadge(
                                polygon = category.badgeShape,
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                size = 40.dp
                            ) {
                                Icon(
                                    imageVector = category.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = category.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = stringResource(
                                    R.string.settings_hub_subtitle,
                                    coreEntries.size,
                                    category.items.size
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            if (coreEntries.isNotEmpty()) {
                item(key = "core_${category.id}") {
                    SettingsExpressiveGroup(
                        title = null,
                        entries = coreEntries,
                        prefs = prefs,
                        refreshTick = refreshTick,
                        onChanged = onChanged,
                        onShowInfo = onShowInfo
                    )
                }
            }

            if (advancedEntries.isNotEmpty()) {
                item(key = "advanced_toggle_${category.id}") {
                    val toggleInteraction = remember(category.id) { MutableInteractionSource() }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = if (expandedAdvanced) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .clip(MaterialTheme.shapes.large)
                                .expressiveFocusAndPress(
                                    interactionSource = toggleInteraction,
                                    shape = MaterialTheme.shapes.large
                                )
                                .clickable(
                                    interactionSource = toggleInteraction,
                                    indication = LocalIndication.current,
                                    role = Role.Button,
                                    onClick = onToggleAdvanced
                                )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Tune,
                                    contentDescription = null,
                                    tint = if (expandedAdvanced) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = stringResource(
                                        if (expandedAdvanced) {
                                            R.string.settings_advanced_hide
                                        } else {
                                            R.string.settings_advanced_toggle
                                        },
                                        advancedEntries.size
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (expandedAdvanced) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    imageVector = Icons.Rounded.ExpandMore,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(22.dp)
                                        .rotate(chevronRotation)
                                )
                            }
                        }

                        AnimatedVisibility(
                            visible = expandedAdvanced,
                            enter = if (reducedMotion) fadeIn(snap()) else fadeIn(motionScheme.defaultEffectsSpec()),
                            exit = if (reducedMotion) fadeOut(snap()) else fadeOut(motionScheme.fastEffectsSpec())
                        ) {
                            SettingsExpressiveGroup(
                                title = null,
                                entries = advancedEntries,
                                prefs = prefs,
                                refreshTick = refreshTick,
                                onChanged = onChanged,
                                onShowInfo = onShowInfo
                            )
                        }
                    }
                }
            }

            item(key = "detail_bottom_${category.id}") {
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SearchResultsPane(
    matchingQuickActions: List<ActionSetting>,
    matchingCategories: List<SettingCategory>,
    prefs: SharedPreferences,
    refreshTick: Int,
    onChanged: () -> Unit,
    onShowInfo: (SettingEntry) -> Unit,
) {
    if (matchingQuickActions.isEmpty() && matchingCategories.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            VibemisShapeBadge(
                polygon = MaterialShapes.Ghostish,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 64.dp,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.searchpreference_no_results),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 840.dp)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }

            if (matchingQuickActions.isNotEmpty()) {
                item(key = "search_quick_actions") {
                    SettingsExpressiveGroup(
                        title = stringResource(R.string.wizard_title),
                        entries = matchingQuickActions,
                        prefs = prefs,
                        refreshTick = refreshTick,
                        onChanged = onChanged,
                        onShowInfo = onShowInfo,
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            items(matchingCategories, key = { it.id }) { category ->
                SettingsExpressiveGroup(
                    title = category.title,
                    entries = category.items,
                    prefs = prefs,
                    refreshTick = refreshTick,
                    onChanged = onChanged,
                    onShowInfo = onShowInfo,
                    modifier = Modifier.animateItem(),
                )
            }

            item { Spacer(modifier = Modifier.height(28.dp)) }
        }
    }
}

@Composable
private fun SettingsExpressiveGroup(
    title: String?,
    entries: List<SettingEntry>,
    prefs: SharedPreferences,
    refreshTick: Int,
    onChanged: () -> Unit,
    onShowInfo: (SettingEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        if (!title.isNullOrBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp, bottom = 6.dp)
            )
        }
        val outerCorner = MaterialTheme.shapes.large
        val innerCorner = MaterialTheme.shapes.extraSmall
        entries.forEachIndexed { index, entry ->
            val itemShape = when {
                entries.size == 1 -> outerCorner
                index == 0 -> RoundedCornerShape(
                    topStart = outerCorner.topStart,
                    topEnd = outerCorner.topEnd,
                    bottomStart = innerCorner.bottomStart,
                    bottomEnd = innerCorner.bottomEnd,
                )
                index == entries.lastIndex -> RoundedCornerShape(
                    topStart = innerCorner.topStart,
                    topEnd = innerCorner.topEnd,
                    bottomStart = outerCorner.bottomStart,
                    bottomEnd = outerCorner.bottomEnd,
                )
                else -> innerCorner
            }
            Surface(
                shape = itemShape,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    SettingEntryView(
                        entry = entry,
                        prefs = prefs,
                        refreshTick = refreshTick,
                        onChanged = onChanged,
                        onShowInfo = onShowInfo
                    )
                }
            }
        }
    }
}

private fun hasLongSummary(summary: String): Boolean =
    summary.length > 64 || summary.contains('\n')

@Composable
private fun SettingInfoIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(48.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.Info,
            contentDescription = stringResource(R.string.settings_info_button_desc),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(19.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingEntryView(
    entry: SettingEntry,
    prefs: SharedPreferences,
    refreshTick: Int,
    onChanged: () -> Unit,
    onShowInfo: (SettingEntry) -> Unit
) {
    val showInfoIcon = hasLongSummary(entry.summary)

    when (entry) {
        is ChoiceSetting -> {
            val currentValue = remember(entry.key, refreshTick) {
                if (entry.storeAsInt) {
                    try {
                        prefs.getInt(entry.key, entry.defaultValue.toIntOrNull() ?: 0).toString()
                    } catch (_: ClassCastException) {
                        prefs.getString(entry.key, entry.defaultValue) ?: entry.defaultValue
                    }
                } else {
                    prefs.getString(entry.key, entry.defaultValue) ?: entry.defaultValue
                }
            }
            val currentLabel = entry.options.firstOrNull { it.first == currentValue }?.second ?: currentValue
            var showDialog by remember { mutableStateOf(false) }

            if (entry.inlineControl && entry.options.size <= 4) {
                val saveChoice: (String) -> Unit = { value ->
                    if (entry.storeAsInt) {
                        prefs.edit().putInt(entry.key, value.toIntOrNull() ?: 0).apply()
                    } else {
                        prefs.edit().putString(entry.key, value).apply()
                    }
                    onChanged()
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = entry.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (entry.summary.isNotBlank()) {
                                Text(
                                    text = entry.summary.replace('\n', ' '),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (showInfoIcon) {
                            SettingInfoIconButton(onClick = { onShowInfo(entry) })
                        }
                    }
                    if (entry.options.all { it.second.length <= 18 }) {
                        VibemisConnectedSegmentedSelector(
                            options = entry.options,
                            selectedValue = currentValue,
                            onSelect = saveChoice,
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            entry.options.forEach { (value, label) ->
                                val selected = currentValue == value
                                val interactionSource = remember(value) { MutableInteractionSource() }
                                val shapes = FilledTonalToggleButtonDefaults.shapesFor(ToggleButtonDefaults.size)
                                FilledTonalToggleButton(
                                    checked = selected,
                                    onCheckedChange = { saveChoice(value) },
                                    shapes = shapes,
                                    colors = FilledTonalToggleButtonDefaults.colors(
                                        checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        contentColor = MaterialTheme.colorScheme.onSurface,
                                    ),
                                    interactionSource = interactionSource,
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .expressiveFocusAndPress(
                                            interactionSource = interactionSource,
                                            shape = if (selected) shapes.checkedShape else shapes.shape,
                                        ),
                                ) {
                                    Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            } else {
                val rowInteraction = remember(entry.key) { MutableInteractionSource() }
                val stackValuePillBelow = currentLabel.length > 14
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(MaterialTheme.shapes.small)
                        .expressiveFocusAndPress(
                            interactionSource = rowInteraction,
                            shape = MaterialTheme.shapes.small,
                        )
                        .clickable(
                            interactionSource = rowInteraction,
                            indication = LocalIndication.current,
                            role = Role.Button,
                        ) { showDialog = true }
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = entry.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (entry.summary.isNotBlank()) {
                                Text(
                                    text = entry.summary.replace('\n', ' '),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (showInfoIcon) {
                            SettingInfoIconButton(onClick = { onShowInfo(entry) })
                        }
                        if (!stackValuePillBelow) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.widthIn(max = 180.dp)
                            ) {
                                Text(
                                    text = currentLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                    if (stackValuePillBelow) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.widthIn(max = 320.dp)
                        ) {
                            Text(
                                text = currentLabel,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                if (showDialog) {
                    AlertDialog(
                        onDismissRequest = { showDialog = false },
                        shape = MaterialTheme.shapes.large,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        title = {
                            Text(
                                text = entry.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        text = {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 360.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                entry.options.forEach { (value, label) ->
                                    val isSelected = currentValue == value
                                    val optionInteraction = remember(value) { MutableInteractionSource() }
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .expressiveFocusAndPress(
                                                interactionSource = optionInteraction,
                                                shape = MaterialTheme.shapes.small,
                                            )
                                            .clickable(
                                                interactionSource = optionInteraction,
                                                indication = LocalIndication.current,
                                                role = Role.RadioButton,
                                            ) {
                                                if (entry.storeAsInt) {
                                                    prefs.edit().putInt(entry.key, value.toIntOrNull() ?: 0).apply()
                                                } else {
                                                    prefs.edit().putString(entry.key, value).apply()
                                                }
                                                onChanged()
                                                showDialog = false
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            RadioButton(
                                                selected = isSelected,
                                                onClick = null
                                            )
                                            Text(
                                                text = label,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) {
                                                    MaterialTheme.colorScheme.onSecondaryContainer
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showDialog = false }) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    )
                }
            }
        }

        is ToggleSetting -> {
            val checked = remember(entry.key, refreshTick) {
                try {
                    prefs.getBoolean(entry.key, entry.defaultValue)
                } catch (_: ClassCastException) {
                    entry.defaultValue
                }
            }
            val toggleInteraction = remember(entry.key) { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(MaterialTheme.shapes.small)
                    .expressiveFocusAndPress(
                        interactionSource = toggleInteraction,
                        shape = MaterialTheme.shapes.small,
                    )
                    .toggleable(
                        value = checked,
                        interactionSource = toggleInteraction,
                        indication = LocalIndication.current,
                        role = Role.Switch,
                        onValueChange = { newValue ->
                            prefs.edit().putBoolean(entry.key, newValue).apply()
                            onChanged()
                        }
                    )
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (entry.summary.isNotBlank()) {
                        Text(
                            text = entry.summary.replace('\n', ' '),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (showInfoIcon) {
                    SettingInfoIconButton(onClick = { onShowInfo(entry) })
                }
                Switch(
                    checked = checked,
                    onCheckedChange = null
                )
            }
        }

        is SliderSetting -> {
            val savedInt = remember(entry.key, refreshTick) {
                try {
                    prefs.getInt(entry.key, entry.defaultValue)
                } catch (_: ClassCastException) {
                    prefs.getString(entry.key, entry.defaultValue.toString())?.toIntOrNull()
                        ?: entry.defaultValue
                }
            }
            var sliderValue by remember(savedInt) {
                mutableIntStateOf(savedInt.coerceIn(entry.minValue, entry.maxValue))
            }
            val formatted = entry.formatValue?.invoke(sliderValue)
                ?: "$sliderValue${entry.unitSuffix}"

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (showInfoIcon) {
                        SettingInfoIconButton(onClick = { onShowInfo(entry) })
                    }
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = formatted,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
                if (entry.summary.isNotBlank()) {
                    Text(
                        text = entry.summary.replace('\n', ' '),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                @Suppress("DEPRECATION")
                Slider(
                    value = sliderValue.toFloat(),
                    onValueChange = { raw ->
                        val snapped = if (entry.stepSize > 1) {
                            ((raw / entry.stepSize).roundToInt() * entry.stepSize)
                                .coerceIn(entry.minValue, entry.maxValue)
                        } else {
                            raw.roundToInt().coerceIn(entry.minValue, entry.maxValue)
                        }
                        sliderValue = snapped
                        prefs.edit().putInt(entry.key, snapped).apply()
                    },
                    onValueChangeFinished = { onChanged() },
                    valueRange = entry.minValue.toFloat()..entry.maxValue.toFloat()
                )
            }
        }

        is ActionSetting -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (entry.summary.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = entry.summary.replace('\n', ' '),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (showInfoIcon) {
                            SettingInfoIconButton(onClick = { onShowInfo(entry) })
                        }
                    }
                }
                val actionInteraction = remember(entry.key) { MutableInteractionSource() }
                OutlinedButton(
                    onClick = entry.onClick,
                    interactionSource = actionInteraction,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .expressiveFocusAndPress(
                            interactionSource = actionInteraction,
                            shape = MaterialTheme.shapes.medium,
                        )
                ) {
                    Text(
                        text = entry.title,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
