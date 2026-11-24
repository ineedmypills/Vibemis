package com.ineedmypills.vibemis.ui

import android.app.Application
import android.app.GameManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.ineedmypills.vibemis.PcView
import com.ineedmypills.vibemis.R
import com.ineedmypills.vibemis.TestLogSuppressor
import com.ineedmypills.vibemis.computers.ComputerManagerService
import com.ineedmypills.vibemis.profiles.ProfilesManager
import com.ineedmypills.vibemis.profiles.SettingsProfile
import com.ineedmypills.vibemis.shadows.ShadowGameManager
import com.ineedmypills.vibemis.shadows.ShadowVibemisBridge
import org.junit.After
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
class PcViewLayoutTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private var testProfileUuid: UUID? = null
    private var scenario: ActivityScenario<PcView>? = null

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

        val pm = ProfilesManager.getInstance()
        val uuid = UUID.randomUUID()
        testProfileUuid = uuid
        val profile = SettingsProfile(
            uuid,
            "Игровой профиль 120Hz Ultra",
            1L,
            1L,
            emptyMap<String, Any?>()
        )
        pm.add(profile)
        pm.setActive(profile.uuid)
    }

    @After
    fun tearDown() {
        scenario?.close()
        val pm = ProfilesManager.getInstance()
        pm.setActive(null)
        testProfileUuid?.let { pm.delete(it) }
    }

    @Test
    @Config(qualifiers = "ru-rRU-w360dp-h800dp-xxhdpi")
    fun pcView_360dpWithActiveProfile_bottomBarIsDisplayedAndNonOverlapping() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        com.ineedmypills.vibemis.preferences.PreferenceConfiguration.getDefaultSharedPreferences(ctx).edit()
            .putString(com.ineedmypills.vibemis.ui.components.PREF_COLOR_PALETTE, com.ineedmypills.vibemis.ui.components.COLOR_PALETTE_VIBEMIS)
            .putString(com.ineedmypills.vibemis.ui.components.PREF_THEME_MODE, com.ineedmypills.vibemis.ui.components.THEME_MODE_DARK)
            .commit()
        scenario = ActivityScenario.launch(PcView::class.java)
        composeRule.waitForIdle()
        val chip = composeRule.onNodeWithText("Игровой профиль 120Hz Ultra", substring = false)
        val wizard = composeRule.onNodeWithContentDescription(ctx.getString(R.string.wizard_title))
        val settings = composeRule.onNodeWithContentDescription(ctx.getString(R.string.title_settings))
        val addFab = composeRule.onNodeWithContentDescription(ctx.getString(R.string.title_add_pc))
        chip.assertIsDisplayed()
        wizard.assertIsDisplayed()
        settings.assertIsDisplayed()
        addFab.assertIsDisplayed()
        assertTrue(chip.getBoundsInRoot().right <= wizard.getBoundsInRoot().left + 1.dp)
        assertTrue(addFab.getBoundsInRoot().right <= 360.dp)
    }

    @Test
    @Config(qualifiers = "ru-rRU-w320dp-h640dp-xhdpi")
    fun pcView_narrow320dpWithLongActiveProfile_bottomBarDoesNotOverlapOrClip() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        scenario = ActivityScenario.launch(PcView::class.java)
        composeRule.waitForIdle()

        val chip = composeRule.onNodeWithText("Игровой профиль 120Hz Ultra", substring = false)
        val wizard = composeRule.onNodeWithContentDescription(ctx.getString(R.string.wizard_title))
        val settings = composeRule.onNodeWithContentDescription(ctx.getString(R.string.title_settings))
        val addFab = composeRule.onNodeWithContentDescription(ctx.getString(R.string.title_add_pc))

        chip.assertIsDisplayed()
        wizard.assertIsDisplayed()
        settings.assertIsDisplayed()
        addFab.assertIsDisplayed()

        val chipBounds = chip.getBoundsInRoot()
        val wizardBounds = wizard.getBoundsInRoot()
        val settingsBounds = settings.getBoundsInRoot()
        val fabBounds = addFab.getBoundsInRoot()

        assertTrue("Chip right ${chipBounds.right} overlapped wizard left ${wizardBounds.left}", chipBounds.right <= wizardBounds.left + 1.dp)
        assertTrue("Wizard right ${wizardBounds.right} overlapped settings left ${settingsBounds.left}", wizardBounds.right <= settingsBounds.left + 1.dp)
        assertTrue("Settings right ${settingsBounds.right} overlapped FAB left ${fabBounds.left}", settingsBounds.right <= fabBounds.left + 1.dp)
        assertTrue("FAB right ${fabBounds.right} clipped past 320.dp", fabBounds.right <= 320.dp)
    }

    @Test
    @Config(qualifiers = "ru-rRU-w800dp-h360dp-xxhdpi")
    fun pcView_landscape360dpHeight_emptyStateAddButtonIsReachable() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        scenario = ActivityScenario.launch(PcView::class.java)
        composeRule.waitForIdle()

        val addButton = composeRule.onAllNodesWithText(ctx.getString(R.string.title_add_pc))[0]
        addButton.performScrollTo()
        addButton.assertIsDisplayed()
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun suppressLogs() {
            TestLogSuppressor.install()
        }
    }
}
