package com.ineedmypills.vibemis.ui.components

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalToggleButton
import androidx.compose.material3.FilledTonalToggleButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.graphics.shapes.RoundedPolygon
import com.ineedmypills.vibemis.preferences.PreferenceConfiguration
import kotlin.math.max

const val PREF_THEME_MODE = "list_theme_mode"
const val PREF_COLOR_PALETTE = "list_color_palette"
const val THEME_MODE_SYSTEM = "system"
const val THEME_MODE_DARK = "dark"
const val THEME_MODE_LIGHT = "light"
const val COLOR_PALETTE_DYNAMIC = "dynamic"
const val COLOR_PALETTE_VIBEMIS = "vibemis"

/**
 * WCAG AA verified Material 3 Expressive Dark color scheme built on
 * Lunar Violet (#C5B4FF), Solar Cyan (#7AD6FF), and Amber Pulse (#FFB77C).
 */
val VibemisDarkColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFC5B4FF),
    onPrimary = Color(0xFF24105A),
    primaryContainer = Color(0xFF3F2C7A),
    onPrimaryContainer = Color(0xFFE9E2FF),
    secondary = Color(0xFF7AD6FF),
    onSecondary = Color(0xFF003549),
    secondaryContainer = Color(0xFF144B63),
    onSecondaryContainer = Color(0xFFCBEFFF),
    tertiary = Color(0xFFFFB77C),
    onTertiary = Color(0xFF472300),
    tertiaryContainer = Color(0xFF653506),
    onTertiaryContainer = Color(0xFFFFE0C7),
    background = Color(0xFF111217),
    onBackground = Color(0xFFE6E6EE),
    surface = Color(0xFF111217),
    onSurface = Color(0xFFE6E6EE),
    surfaceVariant = Color(0xFF2A2C37),
    onSurfaceVariant = Color(0xFFC4C5D3),
    surfaceContainerLowest = Color(0xFF0C0D12),
    surfaceContainerLow = Color(0xFF191A21),
    surfaceContainer = Color(0xFF20222B),
    surfaceContainerHigh = Color(0xFF2A2C37),
    surfaceContainerHighest = Color(0xFF353846),
    outline = Color(0xFF8E90A0),
    outlineVariant = Color(0xFF434554),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/**
 * WCAG AA verified Material 3 Expressive Light color scheme paired with
 * the Vibemis kinetic-console accent palette.
 */
val VibemisLightColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF5A3FB8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6DEFF),
    onPrimaryContainer = Color(0xFF1C0754),
    secondary = Color(0xFF006689),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC6E7FF),
    onSecondaryContainer = Color(0xFF001E2B),
    tertiary = Color(0xFF934B00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDCBE),
    onTertiaryContainer = Color(0xFF2F1500),
    background = Color(0xFFFAF8FF),
    onBackground = Color(0xFF1A1822),
    surface = Color(0xFFFAF8FF),
    onSurface = Color(0xFF1A1822),
    surfaceVariant = Color(0xFFE6E0EE),
    onSurfaceVariant = Color(0xFF494456),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F0FA),
    surfaceContainer = Color(0xFFEEEAF5),
    surfaceContainerHigh = Color(0xFFE8E3F0),
    surfaceContainerHighest = Color(0xFFE2DCEB),
    outline = Color(0xFF6F6A7E),
    outlineVariant = Color(0xFFC9C3D4),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

val LocalReducedMotion = staticCompositionLocalOf { false }

/**
 * Zero-duration snap MotionScheme used when system Reduced Motion is active.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
object ReducedMotionScheme : MotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = snap()
}

fun isSystemReducedMotionEnabled(context: Context): Boolean {
    return try {
        val animatorsDisabled = !ValueAnimator.areAnimatorsEnabled()
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
        animatorsDisabled || scale <= 0f
    } catch (_: Throwable) {
        false
    }
}

@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) { isSystemReducedMotionEnabled(context) }
}

data class VibemisThemePreferences(
    val themeMode: String = THEME_MODE_SYSTEM,
    val colorPalette: String = COLOR_PALETTE_DYNAMIC,
)

@Composable
fun rememberVibemisThemePreferences(): VibemisThemePreferences {
    val context = LocalContext.current
    val prefs = remember(context) {
        runCatching { PreferenceConfiguration.getDefaultSharedPreferences(context) }.getOrNull()
    }
    var state by remember(prefs) {
        mutableStateOf(
            VibemisThemePreferences(
                themeMode = prefs?.getString(PREF_THEME_MODE, THEME_MODE_SYSTEM) ?: THEME_MODE_SYSTEM,
                colorPalette = prefs?.getString(PREF_COLOR_PALETTE, COLOR_PALETTE_DYNAMIC)
                    ?: COLOR_PALETTE_DYNAMIC,
            )
        )
    }

    DisposableEffect(prefs) {
        if (prefs == null) return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            if (key == PREF_THEME_MODE || key == PREF_COLOR_PALETTE) {
                state = VibemisThemePreferences(
                    themeMode = sp.getString(PREF_THEME_MODE, THEME_MODE_SYSTEM) ?: THEME_MODE_SYSTEM,
                    colorPalette = sp.getString(PREF_COLOR_PALETTE, COLOR_PALETTE_DYNAMIC)
                        ?: COLOR_PALETTE_DYNAMIC,
                )
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    return state
}

tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}

/**
 * Enables edge-to-edge layout on non-game UI activities and keeps system bar icon polarity
 * aligned with the active light or dark Expressive theme.
 */
fun ComponentActivity.enableVibemisEdgeToEdge(isDarkTheme: Boolean? = null) {
    val dark = isDarkTheme ?: ((resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES)
    runCatching {
        val statusBarStyle = if (dark) {
            SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        } else {
            SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        }
        val navBarStyle = if (dark) {
            SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        } else {
            SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = statusBarStyle, navigationBarStyle = navBarStyle)
    }
}

@Composable
fun VibemisSystemBarEffect(isDarkTheme: Boolean) {
    val view = LocalView.current
    val activity = LocalContext.current.findComponentActivity()
    DisposableEffect(activity, view, isDarkTheme) {
        if (activity != null) {
            val simpleName = activity::class.java.simpleName
            // Never disturb immersive full-screen flags on active stream/external display windows.
            if (simpleName != "Game" && simpleName != "ExternalDisplayControlActivity") {
                runCatching {
                    val controller = WindowCompat.getInsetsController(activity.window, view)
                    controller.isAppearanceLightStatusBars = !isDarkTheme
                    controller.isAppearanceLightNavigationBars = !isDarkTheme
                }
            }
        }
        onDispose {}
    }
}

@Immutable
data class VibemisAdaptiveLayout(
    val widthDp: Int,
    val heightDp: Int,
    val isLandscape: Boolean,
    val isMediumWidth: Boolean,
    val isExpandedWidth: Boolean,
    val isCompactHeight: Boolean,
) {
    val useTwoPane: Boolean
        get() = isExpandedWidth || (isLandscape && widthDp >= 640)
}

@Composable
fun rememberVibemisAdaptiveLayout(): VibemisAdaptiveLayout {
    val configuration = LocalConfiguration.current
    val widthDp = configuration.screenWidthDp
    val heightDp = configuration.screenHeightDp
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE || widthDp > heightDp
    return remember(widthDp, heightDp, isLandscape) {
        VibemisAdaptiveLayout(
            widthDp = widthDp,
            heightDp = heightDp,
            isLandscape = isLandscape,
            isMediumWidth = widthDp >= 600,
            isExpandedWidth = widthDp >= 840,
            isCompactHeight = heightDp < 480,
        )
    }
}

/**
 * Adds tactile M3 Expressive spring press feedback and a high-contrast (>= 3:1) gamepad/keyboard
 * focus border ring. Respects Reduced Motion by snapping scales to 1.0f.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun Modifier.expressiveFocusAndPress(
    interactionSource: InteractionSource,
    shape: Shape = RoundedCornerShape(24.dp),
    pressedScale: Float = 0.97f,
    focusedScale: Float = 1.02f,
    focusRingColor: Color? = null,
    focusRingWidth: Dp = 2.5.dp,
): Modifier = composed {
    val isPressed by interactionSource.collectIsPressedAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()
    val reducedMotion = LocalReducedMotion.current

    val targetScale = when {
        reducedMotion -> 1f
        isPressed -> pressedScale
        isFocused -> focusedScale
        else -> 1f
    }
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "expressiveFocusPressScale",
    )
    val ringColor = focusRingColor ?: MaterialTheme.colorScheme.primary
    val animatedBorderWidth by animateDpAsState(
        targetValue = if (isFocused) focusRingWidth else 0.dp,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "expressiveFocusRingWidth",
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .then(
            if (isFocused || animatedBorderWidth > 0.dp) {
                Modifier.border(
                    width = animatedBorderWidth,
                    color = ringColor,
                    shape = shape,
                )
            } else {
                Modifier
            }
        )
}

/**
 * Expressive polygonal icon container powered by [MaterialShapes] with smooth color transitions.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VibemisShapeBadge(
    polygon: RoundedPolygon,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    border: BorderStroke? = null,
    content: @Composable () -> Unit,
) {
    val reducedMotion = LocalReducedMotion.current
    val shape = polygon.toShape()
    val animatedBg by animateColorAsState(
        targetValue = containerColor,
        animationSpec = if (reducedMotion) snap() else MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "shapeBadgeBg",
    )
    val animatedFg by animateColorAsState(
        targetValue = contentColor,
        animationSpec = if (reducedMotion) snap() else MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "shapeBadgeFg",
    )

    Surface(
        modifier = modifier
            .size(size)
            .clip(shape),
        shape = shape,
        color = animatedBg,
        contentColor = animatedFg,
        border = border,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}

/**
 * Expressive loading view using M3 [ContainedLoadingIndicator] inside a balanced layout.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VibemisContainedLoadingView(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(24.dp)
            .widthIn(max = 360.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ContainedLoadingIndicator(
            modifier = Modifier.size(64.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            indicatorColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Physical-screen-centered, spring-animated overlay container for [Dialog], [SpinnerDialog],
 * and [GameMenu]. Plays exit animations before invoking [onDismissFinished] to detach the View.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PhysicalCenteredOverlayBox(
    activity: Activity,
    visible: Boolean,
    maxWidth: Dp = 520.dp,
    scrimAlpha: Float = 0.62f,
    onScrimClick: (() -> Unit)? = null,
    onDismissFinished: () -> Unit,
    content: @Composable () -> Unit,
) {
    val reducedMotion = LocalReducedMotion.current
    val localView = LocalView.current
    val density = LocalDensity.current
    var balanceLeftPx by remember { mutableIntStateOf(0) }
    var balanceRightPx by remember { mutableIntStateOf(0) }
    var balanceTopPx by remember { mutableIntStateOf(0) }
    var balanceBottomPx by remember { mutableIntStateOf(0) }
    var hadBeenVisible by remember { mutableStateOf(false) }

    LaunchedEffect(visible) {
        if (visible) {
            hadBeenVisible = true
        } else if (hadBeenVisible) {
            if (!reducedMotion) {
                kotlinx.coroutines.delay(190L)
            }
            onDismissFinished()
        }
    }

    val animatedScrimAlpha by animateFloatAsState(
        targetValue = if (visible) scrimAlpha else 0f,
        animationSpec = if (reducedMotion) snap() else MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "overlayScrimAlpha",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = animatedScrimAlpha))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onScrimClick?.invoke() },
            )
            .onGloballyPositioned { coords ->
                val loc = IntArray(2)
                localView.getLocationOnScreen(loc)
                val (screenW, screenH) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val b = activity.windowManager.currentWindowMetrics.bounds
                    b.width() to b.height()
                } else {
                    val dm = activity.resources.displayMetrics
                    dm.widthPixels to dm.heightPixels
                }
                val leftGap = max(0, loc[0])
                val rightGap = max(0, screenW - (loc[0] + coords.size.width))
                val topGap = max(0, loc[1])
                val bottomGap = max(0, screenH - (loc[1] + coords.size.height))
                balanceLeftPx = max(0, rightGap - leftGap)
                balanceRightPx = max(0, leftGap - rightGap)
                balanceTopPx = max(0, bottomGap - topGap)
                balanceBottomPx = max(0, topGap - bottomGap)
            }
            .padding(
                start = with(density) { balanceLeftPx.toDp() } + 24.dp,
                end = with(density) { balanceRightPx.toDp() } + 24.dp,
                top = with(density) { balanceTopPx.toDp() } + 16.dp,
                bottom = with(density) { balanceBottomPx.toDp() } + 16.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = if (reducedMotion) {
                fadeIn(snap())
            } else {
                fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()) +
                    scaleIn(
                        initialScale = 0.91f,
                        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                    )
            },
            exit = if (reducedMotion) {
                fadeOut(snap())
            } else {
                fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) +
                    scaleOut(
                        targetScale = 0.94f,
                        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                    )
            },
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = maxWidth)
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 8.dp,
            ) {
                content()
            }
        }
    }
}

/**
 * Shared Material 3 Expressive connected segmented selector using [FilledTonalToggleButton]
 * with position-aware morphing [ButtonGroupDefaults] shapes and focus indicators.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> VibemisConnectedSegmentedSelector(
    options: List<Pair<T, String>>,
    selectedValue: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) return
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val lastIndex = options.lastIndex
        options.forEachIndexed { index, (value, label) ->
            val checked = value == selectedValue
            val shapes = when {
                options.size == 1 -> FilledTonalToggleButtonDefaults.shapesFor(ToggleButtonDefaults.size)
                index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                index == lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
            }
            val interactionSource = remember(value) { androidx.compose.foundation.interaction.MutableInteractionSource() }
            FilledTonalToggleButton(
                checked = checked,
                onCheckedChange = { if (it || !checked) onSelect(value) },
                shapes = shapes,
                colors = FilledTonalToggleButtonDefaults.colors(
                    checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                interactionSource = interactionSource,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .expressiveFocusAndPress(
                        interactionSource = interactionSource,
                        shape = if (checked) shapes.checkedShape else shapes.shape,
                        pressedScale = 0.98f,
                        focusedScale = 1.01f,
                    ),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (checked) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

