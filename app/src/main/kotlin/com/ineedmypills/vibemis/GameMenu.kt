package com.ineedmypills.vibemis

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ineedmypills.vibemis.binding.input.GameInputDevice
import com.ineedmypills.vibemis.binding.input.KeyboardTranslator
import com.ineedmypills.vibemis.nvstream.http.HardwareMetricsSnapshot
import com.ineedmypills.vibemis.nvstream.http.VibepolloClient
import com.ineedmypills.vibemis.nvstream.jni.VibemisBridge
import com.ineedmypills.vibemis.preferences.PreferenceConfiguration
import com.ineedmypills.vibemis.ui.components.LocalReducedMotion
import com.ineedmypills.vibemis.ui.components.PhysicalCenteredOverlayBox
import com.ineedmypills.vibemis.ui.components.VibemisContainedLoadingView
import com.ineedmypills.vibemis.ui.components.VibemisShapeBadge
import com.ineedmypills.vibemis.ui.components.expressiveFocusAndPress
import com.ineedmypills.vibemis.ui.theme.VibemisExpressiveTheme
import com.ineedmypills.vibemis.utils.Dialog
import com.ineedmypills.vibemis.utils.KeyConfigHelper
import com.ineedmypills.vibemis.utils.KeyMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Provides in-stream quick actions for ongoing Vibemis / Vibepollo sessions.
 */
class GameMenu @JvmOverloads constructor(
    private val game: Game,
    private val dialogScreenContext: Context = game
) : Game.GameMenuCallbacks {

    enum class OptionRole {
        ACTION,
        SESSION_WARNING,
        SESSION_DESTRUCTIVE,
    }

    class MenuOption @JvmOverloads constructor(
        @JvmField val label: String,
        @JvmField val withGameFocus: Boolean,
        @JvmField val runnable: Runnable?,
        val icon: ImageVector? = null,
        val role: OptionRole = OptionRole.ACTION,
    ) {
        constructor(label: String, runnable: Runnable?) : this(label, false, runnable)
    }

    private var currentComposeView: ComposeView? = null
    private var attachedOverlayView: ComposeView? = null
    private var menuVisibleState by mutableStateOf(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun hostActivity(): Activity = (dialogScreenContext as? Activity) ?: game

    private fun getString(id: Int): String = game.resources.getString(id)

    private fun sendKeys(vararg keys: Int) {
        game.sendKeys(ShortArray(keys.size) { keys[it].toShort() })
    }

    private fun sendKeyArray(keys: ShortArray) {
        game.sendKeys(keys)
    }

    private fun runWithGameFocus(runnable: Runnable) {
        if (game.isFinishing) return
        if (!game.hasWindowFocus() && dialogScreenContext is Game) {
            mainHandler.postDelayed({ runWithGameFocus(runnable) }, TEST_GAME_FOCUS_DELAY)
            return
        }
        runnable.run()
    }

    private fun run(option: MenuOption) {
        val action = option.runnable ?: return
        if (option.withGameFocus) {
            runWithGameFocus(action)
        } else {
            action.run()
        }
    }

    private fun detachViewImmediately(view: ComposeView?) {
        val target = view ?: return
        (target.parent as? ViewGroup)?.removeView(target)
        if (currentComposeView === target) {
            currentComposeView = null
        }
        if (attachedOverlayView === target) {
            attachedOverlayView = null
        }
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    private fun showMenuDialog(title: String, options: Array<MenuOption>) {
        val activity = hostActivity()
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            detachViewImmediately(attachedOverlayView)
            val rootView = activity.findViewById<ViewGroup>(android.R.id.content) ?: return@runOnUiThread
            menuVisibleState = false
            val sessionOptions = options.filter {
                it.runnable != null && it.role != OptionRole.ACTION
            }
            val actionOptions = options.filter {
                it.runnable != null && it.role == OptionRole.ACTION
            }

            lateinit var overlayView: ComposeView
            overlayView = ComposeView(activity).apply {
                setContent {
                    VibemisExpressiveTheme {
                        LaunchedEffect(Unit) {
                            menuVisibleState = true
                        }

                        BackHandler {
                            hideMenu()
                        }

                        PhysicalCenteredOverlayBox(
                            activity = activity,
                            visible = menuVisibleState && currentComposeView === overlayView,
                            maxWidth = 560.dp,
                            onScrimClick = { hideMenu() },
                            onDismissFinished = { detachViewImmediately(overlayView) },
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 22.dp, vertical = 18.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    VibemisShapeBadge(
                                        polygon = MaterialShapes.Cookie9Sided,
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        size = 40.dp,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.SportsEsports,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(onClick = { hideMenu() }) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = stringResource(R.string.game_menu_close),
                                        )
                                    }
                                }

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f, fill = false)
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    if (sessionOptions.isNotEmpty()) {
                                        Text(
                                            text = stringResource(R.string.game_menu_section_session),
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            sessionOptions.forEach { option ->
                                                val isDestructive = option.role == OptionRole.SESSION_DESTRUCTIVE
                                                val interaction = remember(option.label) { MutableInteractionSource() }
                                                Button(
                                                    onClick = {
                                                        hideMenu()
                                                        run(option)
                                                    },
                                                    interactionSource = interaction,
                                                    shape = RoundedCornerShape(16.dp),
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = if (isDestructive) {
                                                            MaterialTheme.colorScheme.errorContainer
                                                        } else {
                                                            MaterialTheme.colorScheme.tertiaryContainer
                                                        },
                                                        contentColor = if (isDestructive) {
                                                            MaterialTheme.colorScheme.onErrorContainer
                                                        } else {
                                                            MaterialTheme.colorScheme.onTertiaryContainer
                                                        },
                                                    ),
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .heightIn(min = 48.dp)
                                                        .expressiveFocusAndPress(
                                                            interactionSource = interaction,
                                                            shape = RoundedCornerShape(16.dp),
                                                        ),
                                                ) {
                                                    Icon(
                                                        imageVector = option.icon ?: if (isDestructive) {
                                                            Icons.Rounded.PowerSettingsNew
                                                        } else {
                                                            Icons.Rounded.LinkOff
                                                        },
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp),
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = option.label,
                                                        fontWeight = FontWeight.SemiBold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = stringResource(R.string.game_menu_section_actions),
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }

                                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                                        val columns = if (maxWidth >= 340.dp && actionOptions.size > 2) 2 else 1
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            actionOptions.chunked(columns).forEach { rowOptions ->
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                ) {
                                                    rowOptions.forEach { option ->
                                                        GameMenuActionTile(
                                                            option = option,
                                                            onClick = {
                                                                hideMenu()
                                                                run(option)
                                                            },
                                                            modifier = Modifier.weight(1f),
                                                        )
                                                    }
                                                    if (rowOptions.size < columns) {
                                                        Spacer(modifier = Modifier.weight(1f))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                FilledTonalButton(
                                    onClick = { hideMenu() },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp),
                                    shape = RoundedCornerShape(16.dp),
                                ) {
                                    Text(stringResource(R.string.game_menu_cancel))
                                }
                            }
                        }
                    }
                }
            }
            currentComposeView = overlayView
            attachedOverlayView = overlayView
            rootView.addView(overlayView)
        }
    }

    @Composable
    private fun GameMenuActionTile(
        option: MenuOption,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val interaction = remember(option.label) { MutableInteractionSource() }
        val shape = RoundedCornerShape(16.dp)
        Surface(
            modifier = modifier
                .heightIn(min = 52.dp)
                .expressiveFocusAndPress(
                    interactionSource = interaction,
                    shape = shape,
                )
                .clip(shape)
                .clickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    onClick = onClick,
                ),
            shape = shape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (option.icon != null) {
                    Icon(
                        imageVector = option.icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    private fun showVibepolloPanelDialog() {
        val activity = hostActivity()
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            detachViewImmediately(attachedOverlayView)
            val rootView = activity.findViewById<ViewGroup>(android.R.id.content) ?: return@runOnUiThread
            menuVisibleState = false

            lateinit var overlayView: ComposeView
            overlayView = ComposeView(activity).apply {
                setContent {
                    VibemisExpressiveTheme {
                        var snapshot by remember { mutableStateOf<HardwareMetricsSnapshot?>(null) }

                        LaunchedEffect(Unit) {
                            menuVisibleState = true
                            while (isActive) {
                                snapshot = withContext(Dispatchers.IO) { game.fetchVibepolloMetrics() }
                                delay(1500L)
                            }
                        }

                        BackHandler {
                            hideMenu()
                        }

                        PhysicalCenteredOverlayBox(
                            activity = activity,
                            visible = menuVisibleState && currentComposeView === overlayView,
                            maxWidth = 520.dp,
                            onScrimClick = { hideMenu() },
                            onDismissFinished = { detachViewImmediately(overlayView) },
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 22.dp, vertical = 18.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    VibemisShapeBadge(
                                        polygon = MaterialShapes.Gem,
                                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                        size = 40.dp,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Speed,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                    Text(
                                        text = stringResource(R.string.vibepollo_panel_title),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(onClick = { hideMenu() }) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = stringResource(R.string.vibepollo_panel_close),
                                        )
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = stringResource(R.string.vibepollo_panel_telemetry_title),
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            snapshot?.gpuTemperatureC?.let { tempC ->
                                                val tempColor = when {
                                                    tempC >= 85f -> MaterialTheme.colorScheme.errorContainer
                                                    tempC >= 75f -> MaterialTheme.colorScheme.tertiaryContainer
                                                    else -> MaterialTheme.colorScheme.secondaryContainer
                                                }
                                                val tempTextColor = when {
                                                    tempC >= 85f -> MaterialTheme.colorScheme.onErrorContainer
                                                    tempC >= 75f -> MaterialTheme.colorScheme.onTertiaryContainer
                                                    else -> MaterialTheme.colorScheme.onSecondaryContainer
                                                }
                                                Surface(
                                                    shape = RoundedCornerShape(10.dp),
                                                    color = tempColor,
                                                    contentColor = tempTextColor,
                                                ) {
                                                    Text(
                                                        text = stringResource(
                                                            R.string.vibepollo_metric_temp,
                                                            String.format(Locale.US, "%.0f", tempC),
                                                        ),
                                                        style = MaterialTheme.typography.labelMedium,
                                                        fontFamily = FontFamily.Monospace,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                    )
                                                }
                                            }
                                        }
                                        val s = snapshot
                                        when {
                                            s == null -> VibemisContainedLoadingView(
                                                title = stringResource(R.string.vibepollo_panel_loading),
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                            !s.errorMessage.isNullOrBlank() -> Text(
                                                text = "${stringResource(R.string.vibepollo_panel_unavailable)}: ${s.errorMessage}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            else -> {
                                                if (!s.gpuName.isNullOrBlank()) {
                                                    Text(
                                                        text = s.gpuName,
                                                        style = MaterialTheme.typography.labelMedium,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                s.cpuUsagePercent?.let { cpu ->
                                                    TelemetryMetricBar(
                                                        label = stringResource(
                                                            R.string.vibepollo_metric_cpu,
                                                            String.format(Locale.US, "%.1f", cpu),
                                                        ),
                                                        fraction = (cpu / 100.0).toFloat().coerceIn(0f, 1f),
                                                        barColor = MaterialTheme.colorScheme.secondary,
                                                    )
                                                }
                                                s.gpuUsagePercent?.let { gpu ->
                                                    TelemetryMetricBar(
                                                        label = stringResource(
                                                            R.string.vibepollo_metric_gpu,
                                                            String.format(Locale.US, "%.1f", gpu),
                                                        ),
                                                        fraction = (gpu / 100.0).toFloat().coerceIn(0f, 1f),
                                                        barColor = MaterialTheme.colorScheme.primary,
                                                    )
                                                }
                                                s.gpuEncoderPercent?.let { enc ->
                                                    TelemetryMetricBar(
                                                        label = stringResource(
                                                            R.string.vibepollo_metric_encoder,
                                                            String.format(Locale.US, "%.1f", enc),
                                                        ),
                                                        fraction = (enc / 100.0).toFloat().coerceIn(0f, 1f),
                                                        barColor = MaterialTheme.colorScheme.tertiary,
                                                    )
                                                }
                                                if (s.gpuVramUsedMb != null) {
                                                    val vramPct = s.gpuVramUsagePercent?.let {
                                                        " (${String.format(Locale.US, "%.0f", it)}%)"
                                                    } ?: ""
                                                    TelemetryMetricBar(
                                                        label = stringResource(
                                                            R.string.vibepollo_metric_vram,
                                                            String.format(Locale.US, "%.0f", s.gpuVramUsedMb),
                                                            vramPct,
                                                        ),
                                                        fraction = ((s.gpuVramUsagePercent ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f),
                                                        barColor = MaterialTheme.colorScheme.secondary,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                HorizontalDivider()

                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    FilledTonalButton(
                                        onClick = {
                                            VibepolloClient.bindDualSenseSpeakerOwner(0)
                                            game.showTransientOverlayMessage(getString(R.string.vibepollo_action_bind_dualsense_toast))
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 48.dp)
                                    ) {
                                        Icon(Icons.Rounded.Gamepad, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = stringResource(R.string.vibepollo_action_bind_dualsense_1),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    FilledTonalButton(
                                        onClick = {
                                            VibepolloClient.bindDualSenseSpeakerOwner(1)
                                            game.showTransientOverlayMessage(getString(R.string.vibepollo_action_bind_dualsense_toast))
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 48.dp)
                                    ) {
                                        Icon(Icons.Rounded.Gamepad, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = stringResource(R.string.vibepollo_action_bind_dualsense_2),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }

                                FilledTonalButton(
                                    onClick = {
                                        VibemisBridge.requestIdrFrame()
                                        game.showTransientOverlayMessage(getString(R.string.game_menu_idr_requested_toast))
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                ) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.game_menu_request_idr))
                                }

                                Button(
                                    onClick = {
                                        hideMenu()
                                        game.quitAndSleepHost()
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                ) {
                                    Icon(Icons.Rounded.Bedtime, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.vibepollo_action_quit_sleep))
                                }

                                FilledTonalButton(
                                    onClick = { hideMenu() },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                ) {
                                    Text(stringResource(R.string.vibepollo_panel_close))
                                }
                            }
                        }
                    }
                }
            }
            currentComposeView = overlayView
            attachedOverlayView = overlayView
            rootView.addView(overlayView)
        }
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    private fun TelemetryMetricBar(
        label: String,
        fraction: Float,
        barColor: Color,
    ) {
        val reducedMotion = LocalReducedMotion.current
        val animatedFraction by animateFloatAsState(
            targetValue = fraction,
            animationSpec = if (reducedMotion) snap() else MaterialTheme.motionScheme.defaultEffectsSpec(),
            label = "telemetryMetricFraction",
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
            LinearProgressIndicator(
                progress = { animatedFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = barColor,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
    }

    private fun showSpecialKeysMenu() {
        val options = ArrayList<MenuOption>()

        if (!PreferenceConfiguration.readPreferences(game).disableDefaultExtraKeys) {
            options.add(
                MenuOption(getString(R.string.game_menu_send_keys_esc)) {
                    sendKeys(KeyboardTranslator.VK_ESCAPE)
                }
            )
            options.add(
                MenuOption(getString(R.string.game_menu_send_keys_f11)) {
                    sendKeys(KeyboardTranslator.VK_F11)
                }
            )
            options.add(
                MenuOption(getString(R.string.game_menu_send_keys_alt_f4)) {
                    sendKeys(KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_F4)
                }
            )
            options.add(
                MenuOption(getString(R.string.game_menu_send_keys_alt_enter)) {
                    sendKeys(KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_RETURN)
                }
            )
            options.add(
                MenuOption(getString(R.string.game_menu_send_keys_ctrl_v)) {
                    sendKeys(KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_V)
                }
            )
            options.add(
                MenuOption(getString(R.string.game_menu_send_keys_win)) {
                    sendKeys(KeyboardTranslator.VK_LWIN)
                }
            )
            options.add(
                MenuOption(getString(R.string.game_menu_send_keys_win_d)) {
                    sendKeys(KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_D)
                }
            )
            options.add(
                MenuOption(getString(R.string.game_menu_send_keys_win_g)) {
                    sendKeys(KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_G)
                }
            )
            options.add(
                MenuOption(getString(R.string.game_menu_send_keys_ctrl_alt_tab)) {
                    sendKeys(
                        KeyboardTranslator.VK_LCONTROL,
                        KeyboardTranslator.VK_LMENU,
                        KeyboardTranslator.VK_TAB
                    )
                }
            )
            options.add(
                MenuOption(getString(R.string.game_menu_send_keys_shift_tab)) {
                    sendKeys(KeyboardTranslator.VK_LSHIFT, KeyboardTranslator.VK_TAB)
                }
            )
            options.add(
                MenuOption(getString(R.string.game_menu_send_keys_win_shift_left)) {
                    sendKeys(
                        KeyboardTranslator.VK_LWIN,
                        KeyboardTranslator.VK_LSHIFT,
                        KeyboardTranslator.VK_LEFT
                    )
                }
            )
            options.add(
                MenuOption(getString(R.string.game_menu_send_keys_ctrl_alt_shift_f1)) {
                    sendKeys(
                        KeyboardTranslator.VK_LCONTROL,
                        KeyboardTranslator.VK_LMENU,
                        KeyboardTranslator.VK_LSHIFT,
                        KeyboardTranslator.VK_F1
                    )
                }
            )
            options.add(
                MenuOption(getString(R.string.game_menu_send_keys_ctrl_alt_shift_f12)) {
                    sendKeys(
                        KeyboardTranslator.VK_LCONTROL,
                        KeyboardTranslator.VK_LMENU,
                        KeyboardTranslator.VK_LSHIFT,
                        KeyboardTranslator.VK_F12
                    )
                }
            )
            options.add(
                MenuOption(getString(R.string.game_menu_send_keys_alt_b)) {
                    sendKeys(
                        KeyboardTranslator.VK_LWIN,
                        KeyboardTranslator.VK_LMENU,
                        KeyboardTranslator.VK_B
                    )
                }
            )
        }

        val preferences = game.getSharedPreferences(PREF_NAME, Activity.MODE_PRIVATE)
        val value = preferences.getString(KEY_NAME, "")

        if (!TextUtils.isEmpty(value)) {
            try {
                val shortcutFile = KeyConfigHelper.parseShortcutFile(value)
                val data = shortcutFile?.data
                if (!data.isNullOrEmpty()) {
                    for (sc in data) {
                        val keys = sc.keys
                        val keyCodes = ShortArray(keys.size)
                        for (i in keys.indices) {
                            val code = keys[i]
                            val keycode = when {
                                code.startsWith("0x") -> code.substring(2).toInt(16)
                                code.startsWith("VK_") -> {
                                    val field = KeyMapper::class.java.getDeclaredField(code)
                                    field.getInt(null)
                                }
                                else -> throw IllegalArgumentException("Unknown key code: $code")
                            }
                            keyCodes[i] = keycode.toShort()
                        }
                        options.add(MenuOption(sc.name) { sendKeyArray(keyCodes) })
                    }
                }
            } catch (_: Exception) {
                game.showTransientOverlayMessage(getString(R.string.wrong_import_format))
            }
        }
        options.add(MenuOption(getString(R.string.game_menu_cancel), null))

        showMenuDialog(getString(R.string.game_menu_send_keys), options.toTypedArray())
    }

    private fun showAdvancedMenu(device: GameInputDevice?) {
        val options = ArrayList<MenuOption>()
        if (game.allowChangeMouseMode) {
            options.add(
                MenuOption(getString(R.string.game_menu_select_mouse_mode), true, {
                    game.selectMouseMode(dialogScreenContext)
                }, Icons.Rounded.Mouse)
            )
        }

        options.add(MenuOption(getString(R.string.game_menu_toggle_hud), true, { game.toggleHUD() }, Icons.Rounded.Speed))
        options.add(
            MenuOption(getString(R.string.game_menu_toggle_floating_button), true, {
                game.toggleFloatingButtonVisibility()
            }, Icons.Rounded.TouchApp)
        )
        options.add(
            MenuOption(getString(R.string.game_menu_toggle_keyboard_model), true, {
                game.toggleKeyboardController()
            }, Icons.Rounded.Keyboard)
        )
        if (!game.isOnExternalDisplay) {
            options.add(
                MenuOption(getString(R.string.game_menu_toggle_virtual_model), true, {
                    game.toggleVirtualController()
                }, Icons.Rounded.Gamepad)
            )
        }
        options.add(
            MenuOption(getString(R.string.game_menu_toggle_virtual_keyboard_model), true, {
                game.toggleFullKeyboard()
            }, Icons.Rounded.Keyboard)
        )
        options.add(
            MenuOption(getString(R.string.game_menu_task_manager), true, {
                sendKeys(
                    KeyboardTranslator.VK_LCONTROL,
                    KeyboardTranslator.VK_LSHIFT,
                    KeyboardTranslator.VK_ESCAPE
                )
            }, Icons.Rounded.Terminal)
        )

        options.add(
            MenuOption(getString(R.string.game_menu_send_keys), false, {
                hideMenu()
                showSpecialKeysMenu()
            }, Icons.Rounded.Keyboard)
        )

        options.add(
            MenuOption(getString(R.string.game_menu_switch_touch_sensitivity_model), true, {
                game.switchTouchSensitivity()
            }, Icons.Rounded.TouchApp)
        )
        options.add(
            MenuOption(getString(R.string.vibepollo_action_bind_dualsense_1), true, {
                VibepolloClient.bindDualSenseSpeakerOwner(0)
                game.showTransientOverlayMessage(getString(R.string.vibepollo_action_bind_dualsense_toast))
            }, Icons.Rounded.Gamepad)
        )
        if (device != null) {
            options.addAll(device.getGameMenuOptions())
        }
        options.add(MenuOption(getString(R.string.game_menu_cancel), null))
        showMenuDialog(getString(R.string.game_menu_advanced), options.toTypedArray())
    }

    private fun showServerCmd(serverCmds: ArrayList<String>) {
        val options = ArrayList<MenuOption>()
        for ((index, str) in serverCmds.withIndex()) {
            options.add(
                MenuOption(
                    label = "> $str",
                    withGameFocus = true,
                    runnable = { game.sendExecServerCmd(index) },
                    icon = Icons.Rounded.Terminal,
                )
            )
        }
        options.add(MenuOption(getString(R.string.game_menu_cancel), null))
        showMenuDialog(getString(R.string.game_menu_server_cmd), options.toTypedArray())
    }

    override fun showMenu(device: GameInputDevice?) {
        val options = ArrayList<MenuOption>()

        options.add(
            MenuOption(
                label = getString(R.string.game_menu_disconnect),
                withGameFocus = false,
                runnable = { game.disconnect() },
                icon = Icons.Rounded.LinkOff,
                role = OptionRole.SESSION_WARNING,
            )
        )
        options.add(
            MenuOption(
                label = getString(R.string.game_menu_quit_session),
                withGameFocus = false,
                runnable = { game.quit() },
                icon = Icons.Rounded.PowerSettingsNew,
                role = OptionRole.SESSION_DESTRUCTIVE,
            )
        )
        options.add(
            MenuOption(getString(R.string.game_menu_upload_clipboard), true, { game.sendClipboard(true) }, Icons.Rounded.ContentPaste)
        )
        options.add(
            MenuOption(getString(R.string.game_menu_fetch_clipboard), true, { game.getClipboard(0) }, Icons.Rounded.ContentPaste)
        )

        options.add(
            MenuOption(
                label = getString(R.string.game_menu_server_cmd),
                withGameFocus = true,
                runnable = {
                    val serverCmds = game.serverCmds
                    if (serverCmds.isEmpty()) {
                        Dialog.displayDialog(
                            hostActivity(),
                            getString(R.string.game_dialog_title_server_cmd_empty),
                            getString(R.string.game_dialog_message_server_cmd_empty),
                            false
                        )
                    } else {
                        showServerCmd(serverCmds)
                    }
                },
                icon = Icons.Rounded.Terminal,
            )
        )

        options.add(
            MenuOption(getString(R.string.game_menu_toggle_keyboard), true, { game.toggleKeyboard() }, Icons.Rounded.Keyboard)
        )

        val zoomLabelId = if (game.isZoomModeEnabled) {
            R.string.game_menu_disable_zoom_mode
        } else {
            R.string.game_menu_enable_zoom_mode
        }
        options.add(
            MenuOption(getString(zoomLabelId), true, { game.toggleZoomMode() }, Icons.Rounded.ZoomIn)
        )

        if (dialogScreenContext === game) {
            options.add(
                MenuOption(getString(R.string.game_menu_rotate_screen), true, { game.rotateScreen() }, Icons.Rounded.ScreenRotation)
            )
        }

        options.add(
            MenuOption(
                label = getString(R.string.game_menu_request_idr),
                withGameFocus = true,
                runnable = {
                    VibemisBridge.requestIdrFrame()
                    game.showTransientOverlayMessage(getString(R.string.game_menu_idr_requested_toast))
                },
                icon = Icons.Rounded.Refresh,
            )
        )
        options.add(
            MenuOption(
                label = getString(R.string.game_menu_vibepollo_panel),
                withGameFocus = true,
                runnable = { showVibepolloPanelDialog() },
                icon = Icons.Rounded.Speed,
            )
        )

        options.add(
            MenuOption(getString(R.string.game_menu_advanced), true, { showAdvancedMenu(device) }, Icons.Rounded.Tune)
        )
        options.add(MenuOption(getString(R.string.game_menu_cancel), null))

        showMenuDialog(getString(R.string.quick_menu_title), options.toTypedArray())
    }

    override fun hideMenu() {
        val view = currentComposeView ?: return
        currentComposeView = null
        menuVisibleState = false
        val activity = hostActivity()
        if (activity.isFinishing || activity.isDestroyed) {
            detachViewImmediately(view)
        }
    }

    override fun isMenuOpen(): Boolean = currentComposeView != null && menuVisibleState

    companion object {
        const val KEY_UP_DELAY = 25L
        private const val TEST_GAME_FOCUS_DELAY = 10L
        const val PREF_NAME = "specialPrefs"
        const val KEY_NAME = "special_key"
    }
}
