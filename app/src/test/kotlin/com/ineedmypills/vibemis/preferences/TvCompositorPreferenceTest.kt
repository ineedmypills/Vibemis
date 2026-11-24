package com.ineedmypills.vibemis.preferences

import android.content.Context
import com.ineedmypills.vibemis.preferences.PreferenceConfiguration
import androidx.test.core.app.ApplicationProvider
import com.ineedmypills.vibemis.TestLogSuppressor
import com.ineedmypills.vibemis.shadows.ShadowGameManager
import com.ineedmypills.vibemis.shadows.ShadowVibemisBridge
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33], shadows = [ShadowVibemisBridge::class, ShadowGameManager::class])
@RunWith(RobolectricTestRunner::class)
class TvCompositorPreferenceTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceConfiguration.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    @Test
    fun compositorWorkaround_isOptInAndLoadsFromPreferences() {
        assertFalse(PreferenceConfiguration.readPreferences(context).enableTvCompositorWorkaround)

        PreferenceConfiguration.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean("checkbox_tv_compositor_workaround", true)
            .commit()

        assertTrue(PreferenceConfiguration.readPreferences(context).enableTvCompositorWorkaround)
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun suppressInvalidIdLogs() {
            TestLogSuppressor.install()
        }
    }
}
