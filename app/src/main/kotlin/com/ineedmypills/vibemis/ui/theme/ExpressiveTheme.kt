package com.ineedmypills.vibemis.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Distinct radii separate metadata tags (8dp/14dp), inputs (20dp), host/game cards (28dp), and sheets (36dp).
val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

// Native system sans-serif ensures zero-overhead rendering and complete glyph coverage across all 28 locales.
val ExpressiveTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.25).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 21.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = true),
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None
        )
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    )
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VibemisExpressiveTheme(
    darkTheme: Boolean? = null,
    dynamicColor: Boolean? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val themePrefs = com.ineedmypills.vibemis.ui.components.rememberVibemisThemePreferences()
    val systemDark = isSystemInDarkTheme()
    val resolvedDarkTheme = darkTheme ?: when (themePrefs.themeMode) {
        com.ineedmypills.vibemis.ui.components.THEME_MODE_DARK -> true
        com.ineedmypills.vibemis.ui.components.THEME_MODE_LIGHT -> false
        else -> systemDark
    }
    val resolvedDynamicColor = dynamicColor
        ?: (themePrefs.colorPalette == com.ineedmypills.vibemis.ui.components.COLOR_PALETTE_DYNAMIC)
    val reducedMotion = com.ineedmypills.vibemis.ui.components.rememberReducedMotion()

    val colorScheme = when {
        resolvedDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (resolvedDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        resolvedDarkTheme -> com.ineedmypills.vibemis.ui.components.VibemisDarkColorScheme
        else -> com.ineedmypills.vibemis.ui.components.VibemisLightColorScheme
    }

    com.ineedmypills.vibemis.ui.components.VibemisSystemBarEffect(isDarkTheme = resolvedDarkTheme)

    androidx.compose.runtime.CompositionLocalProvider(
        com.ineedmypills.vibemis.ui.components.LocalReducedMotion provides reducedMotion,
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = if (reducedMotion) {
                com.ineedmypills.vibemis.ui.components.ReducedMotionScheme
            } else {
                MotionScheme.expressive()
            },
            shapes = ExpressiveShapes,
            typography = ExpressiveTypography,
            content = content
        )
    }
}
