package com.ineedmypills.vibemis.ui

import android.app.Application
import android.app.GameManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import com.ineedmypills.vibemis.PcView
import com.ineedmypills.vibemis.R
import com.ineedmypills.vibemis.TestLogSuppressor
import com.ineedmypills.vibemis.computers.ComputerManagerService
import com.ineedmypills.vibemis.preferences.PreferenceConfiguration
import com.ineedmypills.vibemis.preferences.StreamSettings
import com.ineedmypills.vibemis.profiles.ProfilesManager
import com.ineedmypills.vibemis.shadows.ShadowGameManager
import com.ineedmypills.vibemis.shadows.ShadowVibemisBridge
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], shadows = [ShadowVibemisBridge::class, ShadowGameManager::class])
@RunWith(RobolectricTestRunner::class)
class ComposeUiLayoutAndOverflowTest {

    @get:Rule
    val settingsRule = createAndroidComposeRule<StreamSettings>()

    @Before
    fun setUpEnv() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val glPrefs = appContext.getSharedPreferences("GlPreferences", 0)
        glPrefs.edit()
            .putString("Renderer", "TestRenderer")
            .putString("Fingerprint", Build.FINGERPRINT)
            .commit()

        val shadowApp = Shadows.shadowOf(appContext as Application)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            shadowApp.setSystemService(Context.GAME_SERVICE, mock(GameManager::class.java))
        }
        val cn = ComponentName(appContext, ComputerManagerService::class.java)
        val binder = mock(ComputerManagerService.ComputerManagerBinder::class.java)
        shadowApp.setComponentNameAndServiceForBindService(cn, binder)
    }

    private fun SemanticsNodeInteraction.assertNoTextOverflow(label: String, expectedSubstring: String? = null) {
        val node = fetchSemanticsNode()
        val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)
            ?: throw AssertionError("$label missing GetTextLayoutResult action")
        val results = mutableListOf<TextLayoutResult>()
        action.action?.invoke(results)
        assertTrue("$label returned empty TextLayoutResult", results.isNotEmpty())
        val matching = if (expectedSubstring != null) {
            results.filter { it.layoutInput.text.text.contains(expectedSubstring) }
        } else {
            results
        }
        assertTrue("$label found no TextLayoutResult matching '$expectedSubstring' among ${results.map { it.layoutInput.text.text }}", matching.isNotEmpty())
        for (res in matching) {
            val isTruncatedOrClipped = (res.lineCount > 0 && res.isLineEllipsized(res.lineCount - 1)) || res.didOverflowHeight
            assertFalse(
                "$label truncated or clipped (lines=${res.lineCount}, maxLines=${res.layoutInput.maxLines}, ellipsized=${res.lineCount > 0 && res.isLineEllipsized(res.lineCount - 1)}, didOverflowH=${res.didOverflowHeight}, size=${res.size}, text='${res.layoutInput.text.text}')",
                isTruncatedOrClipped
            )
        }
    }

    @Test
    @Config(qualifiers = "ru-rRU-w360dp-h800dp-xxhdpi")
    fun streamSettings_russianPhone360dp_quickSetupAndCategoriesHaveNoTruncation() {
        val ctx = settingsRule.activity
        PreferenceConfiguration.getDefaultSharedPreferences(ctx).edit()
            .putString(com.ineedmypills.vibemis.ui.components.PREF_COLOR_PALETTE, com.ineedmypills.vibemis.ui.components.COLOR_PALETTE_VIBEMIS)
            .putString(com.ineedmypills.vibemis.ui.components.PREF_THEME_MODE, com.ineedmypills.vibemis.ui.components.THEME_MODE_DARK)
            .commit()
        settingsRule.waitForIdle()

        settingsRule.onNodeWithText(ctx.getString(R.string.wizard_title), useUnmergedTree = true)
            .assertIsDisplayed()
            .assertNoTextOverflow("wizard_title", ctx.getString(R.string.wizard_title))

        settingsRule.onNodeWithText(ctx.getString(R.string.wizard_summary), useUnmergedTree = true)
            .assertIsDisplayed()
            .assertNoTextOverflow("wizard_summary", ctx.getString(R.string.wizard_summary))

        settingsRule.onNodeWithText(ctx.getString(R.string.autotune_action_title), useUnmergedTree = true)
            .assertIsDisplayed()
            .assertNoTextOverflow("autotune_action_title", ctx.getString(R.string.autotune_action_title))

        settingsRule.onNodeWithText(ctx.getString(R.string.autotune_action_summary), useUnmergedTree = true)
            .assertIsDisplayed()
            .assertNoTextOverflow("autotune_action_summary", ctx.getString(R.string.autotune_action_summary))

        // Verify visible categories don't truncate titles
        settingsRule.onNodeWithText(ctx.getString(R.string.category_video_settings), useUnmergedTree = true)
            .assertIsDisplayed()
            .assertNoTextOverflow("category_video_settings", ctx.getString(R.string.category_video_settings))

        // Test click-through into Video Settings and back
        settingsRule.onNodeWithText(ctx.getString(R.string.category_video_settings)).performClick()
        settingsRule.waitForIdle()
        settingsRule.onNodeWithContentDescription(ctx.getString(R.string.settings_all_categories))
            .assertIsDisplayed()
            .performClick()
        settingsRule.waitForIdle()
        settingsRule.onNodeWithText(ctx.getString(R.string.wizard_title)).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "ru-rRU-w360dp-h800dp-xxhdpi", fontScale = 1.3f)
    fun streamSettings_russianFontScale130_quickSetupAndCategoryTitlesHaveNoTruncation() {
        val ctx = settingsRule.activity
        settingsRule.waitForIdle()

        settingsRule.onNodeWithText(ctx.getString(R.string.wizard_title), useUnmergedTree = true)
            .assertIsDisplayed()
            .assertNoTextOverflow("wizard_title_130", ctx.getString(R.string.wizard_title))
        settingsRule.onNodeWithText(ctx.getString(R.string.wizard_summary), useUnmergedTree = true)
            .assertIsDisplayed()
            .assertNoTextOverflow("wizard_summary_130", ctx.getString(R.string.wizard_summary))
        settingsRule.onNodeWithText(ctx.getString(R.string.autotune_action_title), useUnmergedTree = true)
            .assertIsDisplayed()
            .assertNoTextOverflow("autotune_action_title_130", ctx.getString(R.string.autotune_action_title))
        settingsRule.onNodeWithText(ctx.getString(R.string.autotune_action_summary), useUnmergedTree = true)
            .assertIsDisplayed()
            .assertNoTextOverflow("autotune_action_summary_130", ctx.getString(R.string.autotune_action_summary))

        val oscTitle = ctx.getString(R.string.category_on_screen_controls_settings)
        settingsRule.onNode(hasScrollAction())
            .performScrollToNode(hasText(oscTitle))
        settingsRule.onNodeWithText(oscTitle, useUnmergedTree = true)
            .assertIsDisplayed()
            .assertNoTextOverflow("category_osc_130", oscTitle)
    }

    @Test
    @Config(qualifiers = "ru-rRU-w360dp-h800dp-xxhdpi")
    fun streamSettings_lightTheme_rendersCleanlyAndCategoriesHaveNoTruncation() {
        val ctx = settingsRule.activity
        PreferenceConfiguration.getDefaultSharedPreferences(ctx).edit()
            .putString(com.ineedmypills.vibemis.ui.components.PREF_COLOR_PALETTE, com.ineedmypills.vibemis.ui.components.COLOR_PALETTE_VIBEMIS)
            .putString(com.ineedmypills.vibemis.ui.components.PREF_THEME_MODE, com.ineedmypills.vibemis.ui.components.THEME_MODE_LIGHT)
            .commit()
        settingsRule.waitForIdle()

        settingsRule.onNodeWithText(ctx.getString(R.string.wizard_summary), useUnmergedTree = true)
            .assertIsDisplayed()
            .assertNoTextOverflow("wizard_summary_light", ctx.getString(R.string.wizard_summary))
        val oscTitle = ctx.getString(R.string.category_on_screen_controls_settings)
        settingsRule.onNode(hasScrollAction())
            .performScrollToNode(hasText(oscTitle))
        settingsRule.onNodeWithText(oscTitle, useUnmergedTree = true)
            .assertIsDisplayed()
            .assertNoTextOverflow("category_osc_light", oscTitle)
    }

    @Test
    @Config(qualifiers = "ru-rRU-w600dp-h900dp-xxhdpi")
    fun streamSettings_russianTablet600dp_sideBySideQuickSetupHasNoTruncation() {
        val ctx = settingsRule.activity
        settingsRule.waitForIdle()

        val wizardTitle = settingsRule.onNodeWithText(ctx.getString(R.string.wizard_title), useUnmergedTree = true)
        val autoTuneTitle = settingsRule.onNodeWithText(ctx.getString(R.string.autotune_action_title), useUnmergedTree = true)
        wizardTitle.assertIsDisplayed().assertNoTextOverflow("wizard_title_600dp", ctx.getString(R.string.wizard_title))
        autoTuneTitle.assertIsDisplayed().assertNoTextOverflow("autotune_action_title_600dp", ctx.getString(R.string.autotune_action_title))

        settingsRule.onNodeWithText(ctx.getString(R.string.wizard_summary), useUnmergedTree = true)
            .assertIsDisplayed()
            .assertNoTextOverflow("wizard_summary_600dp", ctx.getString(R.string.wizard_summary))
        settingsRule.onNodeWithText(ctx.getString(R.string.autotune_action_summary), useUnmergedTree = true)
            .assertIsDisplayed()
            .assertNoTextOverflow("autotune_action_summary_600dp", ctx.getString(R.string.autotune_action_summary))
    }

    @Test
    @Config(qualifiers = "ru-rRU-w360dp-h800dp-xxhdpi")
    fun streamSettings_searchAndToggleWork() {
        val ctx = settingsRule.activity
        val prefs = PreferenceConfiguration.getDefaultSharedPreferences(ctx)
        prefs.edit().putBoolean("checkbox_enable_hdr", false).commit()

        settingsRule.waitForIdle()
        settingsRule.onAllNodesWithText(ctx.getString(R.string.searchpreference_search), substring = true)[0]
            .performTextInput("HDR")
        settingsRule.waitForIdle()
        settingsRule.onNodeWithText(ctx.getString(R.string.title_enable_hdr))
            .assertIsDisplayed()
            .performClick()
        settingsRule.waitForIdle()
        assertTrue(prefs.getBoolean("checkbox_enable_hdr", false))
    }

    @Test
    @Config(qualifiers = "en-rUS-w952dp-h426dp-xxhdpi")
    fun streamSettings_twoPaneLandscape_categoriesVisibleAndCodecValueNotTruncated() {
        val ctx = settingsRule.activity
        settingsRule.waitForIdle()

        // Both compact Quick Setup buttons and Video/Upscaler categories should be visible above the fold.
        settingsRule.onNodeWithText(ctx.getString(R.string.wizard_title), useUnmergedTree = true)
            .assertIsDisplayed()
            .assertNoTextOverflow("wizard_title_landscape", ctx.getString(R.string.wizard_title))
        settingsRule.onNodeWithText(ctx.getString(R.string.autotune_action_title), useUnmergedTree = true)
            .assertIsDisplayed()
            .assertNoTextOverflow("autotune_title_landscape", ctx.getString(R.string.autotune_action_title))
        settingsRule.onAllNodesWithText(ctx.getString(R.string.category_video_settings), useUnmergedTree = true)[0]
            .assertIsDisplayed()
        settingsRule.onAllNodesWithText(ctx.getString(R.string.settings_category_upscaler), useUnmergedTree = true)[0]
            .assertIsDisplayed()

        // In Video Settings detail pane, codec title & "Automatic (Recommended)" pill must not truncate.
        val detailList = settingsRule.onAllNodes(hasScrollAction())[1]
        detailList.performScrollToNode(hasText(ctx.getString(R.string.title_video_format)))
        settingsRule.onNodeWithText(ctx.getString(R.string.title_video_format), useUnmergedTree = true)
            .assertIsDisplayed()
            .assertNoTextOverflow("title_video_format", ctx.getString(R.string.title_video_format))
        settingsRule.onNodeWithText(ctx.getString(R.string.videoformat_auto), useUnmergedTree = true)
            .assertIsDisplayed()
            .assertNoTextOverflow("videoformat_auto_pill", ctx.getString(R.string.videoformat_auto))
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun suppressInvalidIdLogs() {
            TestLogSuppressor.install()
        }
    }
}
