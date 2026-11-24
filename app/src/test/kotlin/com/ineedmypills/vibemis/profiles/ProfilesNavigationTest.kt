package com.ineedmypills.vibemis.profiles

import android.app.Application
import android.app.GameManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.ineedmypills.vibemis.ProfilesActivity
import com.ineedmypills.vibemis.R
import com.ineedmypills.vibemis.TestLogSuppressor
import com.ineedmypills.vibemis.computers.ComputerManagerService
import com.ineedmypills.vibemis.shadows.ShadowGameManager
import com.ineedmypills.vibemis.shadows.ShadowVibemisBridge
import org.junit.Assert.assertNotNull
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], shadows = [ShadowVibemisBridge::class, ShadowGameManager::class])
@RunWith(RobolectricTestRunner::class)
class ProfilesNavigationTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()
    private fun prepareEnvironment() {
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
    }

    @Test
    fun profilesActivity_startsWithoutCrash() {
        prepareEnvironment()
        val activity = Robolectric.buildActivity(ProfilesActivity::class.java).setup().get()
        assertNotNull(activity)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(activity.getString(R.string.profiles_title)).assertIsDisplayed()
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun suppressInvalidIdLogs() {
            TestLogSuppressor.install()
        }
    }
}
