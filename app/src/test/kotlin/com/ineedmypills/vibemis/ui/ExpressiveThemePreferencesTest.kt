package com.ineedmypills.vibemis.ui

import android.app.Application
import android.app.GameManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.SnapSpec
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.test.core.app.ApplicationProvider
import com.ineedmypills.vibemis.DebugInfoActivity
import com.ineedmypills.vibemis.HelpActivity
import com.ineedmypills.vibemis.PcView
import com.ineedmypills.vibemis.TestLogSuppressor
import com.ineedmypills.vibemis.computers.ComputerManagerService
import com.ineedmypills.vibemis.preferences.AddComputerManually
import com.ineedmypills.vibemis.preferences.PreferenceConfiguration
import com.ineedmypills.vibemis.preferences.StreamSettings
import com.ineedmypills.vibemis.shadows.ShadowGameManager
import com.ineedmypills.vibemis.shadows.ShadowVibemisBridge
import com.ineedmypills.vibemis.ui.components.COLOR_PALETTE_DYNAMIC
import com.ineedmypills.vibemis.ui.components.COLOR_PALETTE_VIBEMIS
import com.ineedmypills.vibemis.ui.components.PREF_COLOR_PALETTE
import com.ineedmypills.vibemis.ui.components.PREF_THEME_MODE
import com.ineedmypills.vibemis.ui.components.ReducedMotionScheme
import com.ineedmypills.vibemis.ui.components.THEME_MODE_DARK
import com.ineedmypills.vibemis.ui.components.THEME_MODE_LIGHT
import com.ineedmypills.vibemis.ui.components.THEME_MODE_SYSTEM
import com.ineedmypills.vibemis.ui.components.VibemisDarkColorScheme
import com.ineedmypills.vibemis.ui.components.VibemisLightColorScheme
import com.ineedmypills.vibemis.ui.components.VibemisThemePreferences
import com.ineedmypills.vibemis.ui.components.isSystemReducedMotionEnabled
import com.ineedmypills.vibemis.ui.wizard.SetupWizardActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@Config(sdk = [33], shadows = [ShadowVibemisBridge::class, ShadowGameManager::class])
@RunWith(RobolectricTestRunner::class)
class ExpressiveThemePreferencesTest {
    private fun prepareEnvironment(): Context {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val glPrefs = appContext.getSharedPreferences("GlPreferences", 0)
        glPrefs.edit()
            .putString("Renderer", "TestRenderer")
            .putString("Fingerprint", Build.FINGERPRINT)
            .commit()

        val shadowApp = Shadows.shadowOf(appContext as Application)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            shadowApp.setSystemService(Context.GAME_SERVICE, mock(GameManager::class.java))
        } else {
            shadowApp.setSystemService(Context.GAME_SERVICE, null)
        }

        val cn = ComponentName(appContext, ComputerManagerService::class.java)
        val binder = mock(ComputerManagerService.ComputerManagerBinder::class.java)
        shadowApp.setComponentNameAndServiceForBindService(cn, binder)
        return appContext
    }

    @Test
    fun reducedMotion_respectsAnimatorDurationScale() {
        val context = prepareEnvironment()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f)
        assertFalse(isSystemReducedMotionEnabled(context))

        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0.0f)
        assertTrue(isSystemReducedMotionEnabled(context))

        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f)
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Test
    fun reducedMotionScheme_usesSnapSpecsForAllChannels() {
        assertTrue(ReducedMotionScheme.defaultSpatialSpec<Float>() is SnapSpec)
        assertTrue(ReducedMotionScheme.fastSpatialSpec<Float>() is SnapSpec)
        assertTrue(ReducedMotionScheme.slowSpatialSpec<Float>() is SnapSpec)
        assertTrue(ReducedMotionScheme.defaultEffectsSpec<Float>() is SnapSpec)
        assertTrue(ReducedMotionScheme.fastEffectsSpec<Float>() is SnapSpec)
        assertTrue(ReducedMotionScheme.slowEffectsSpec<Float>() is SnapSpec)
    }

    @Test
    fun themePreferences_defaultsAndPersistenceWork() {
        val context = prepareEnvironment()
        val defaults = VibemisThemePreferences()
        assertEquals(THEME_MODE_SYSTEM, defaults.themeMode)
        assertEquals(COLOR_PALETTE_DYNAMIC, defaults.colorPalette)

        val prefs = PreferenceConfiguration.getDefaultSharedPreferences(context)
        prefs.edit()
            .putString(PREF_THEME_MODE, THEME_MODE_DARK)
            .putString(PREF_COLOR_PALETTE, COLOR_PALETTE_VIBEMIS)
            .commit()

        assertEquals(THEME_MODE_DARK, prefs.getString(PREF_THEME_MODE, THEME_MODE_SYSTEM))
        assertEquals(COLOR_PALETTE_VIBEMIS, prefs.getString(PREF_COLOR_PALETTE, COLOR_PALETTE_DYNAMIC))

        prefs.edit()
            .putString(PREF_THEME_MODE, THEME_MODE_LIGHT)
            .commit()
        assertEquals(THEME_MODE_LIGHT, prefs.getString(PREF_THEME_MODE, THEME_MODE_SYSTEM))
    }

    @Test
    fun expressiveActivities_launchWithoutCrash() {
        prepareEnvironment()
        listOf(
            PcView::class.java,
            SetupWizardActivity::class.java,
            StreamSettings::class.java,
            AddComputerManually::class.java,
            DebugInfoActivity::class.java,
            HelpActivity::class.java,
        ).forEach { activityClass ->
            val controller = Robolectric.buildActivity(activityClass)
            try {
                assertNotNull(controller.create().start().resume().get())
            } finally {
                controller.destroy()
            }
        }
    }

    @Test
    fun colorSchemes_meetWcagAaTextContrast() {
        fun contrastRatio(fg: Color, bg: Color): Double {
            val l1 = fg.luminance()
            val l2 = bg.luminance()
            val lighter = maxOf(l1, l2)
            val darker = minOf(l1, l2)
            return (lighter + 0.05) / (darker + 0.05)
        }

        fun assertSchemeContrast(name: String, scheme: ColorScheme) {
            val pairs = listOf(
                "onBackground/background" to (scheme.onBackground to scheme.background),
                "onSurface/surface" to (scheme.onSurface to scheme.surface),
                "onSurface/surfaceContainer" to (scheme.onSurface to scheme.surfaceContainer),
                "onSurfaceVariant/surfaceContainer" to (scheme.onSurfaceVariant to scheme.surfaceContainer),
                "onSurfaceVariant/surfaceContainerHigh" to (scheme.onSurfaceVariant to scheme.surfaceContainerHigh),
                "primary/surfaceContainerHighest" to (scheme.primary to scheme.surfaceContainerHighest),
                "onPrimary/primary" to (scheme.onPrimary to scheme.primary),
                "onPrimaryContainer/primaryContainer" to (scheme.onPrimaryContainer to scheme.primaryContainer),
                "onSecondaryContainer/secondaryContainer" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
                "onTertiaryContainer/tertiaryContainer" to (scheme.onTertiaryContainer to scheme.tertiaryContainer),
                "onErrorContainer/errorContainer" to (scheme.onErrorContainer to scheme.errorContainer),
            )
            for ((pairName, colors) in pairs) {
                val ratio = contrastRatio(colors.first, colors.second)
                assertTrue("$name $pairName contrast $ratio < 4.5", ratio >= 4.5)
            }
        }

        assertSchemeContrast("VibemisDark", VibemisDarkColorScheme)
        assertSchemeContrast("VibemisLight", VibemisLightColorScheme)
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun suppressInvalidIdLogs() {
            TestLogSuppressor.install()
        }
    }
}
