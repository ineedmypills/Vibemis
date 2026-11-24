package com.ineedmypills.vibemis.autotune

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ineedmypills.vibemis.TestLogSuppressor
import com.ineedmypills.vibemis.preferences.PreferenceConfiguration
import com.ineedmypills.vibemis.profiles.ProfilesManager
import com.ineedmypills.vibemis.profiles.SettingsProfile
import com.ineedmypills.vibemis.shadows.ShadowGameManager
import com.ineedmypills.vibemis.shadows.ShadowVibemisBridge
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@Config(sdk = [33], shadows = [ShadowVibemisBridge::class, ShadowGameManager::class])
@RunWith(RobolectricTestRunner::class)
class AutoTuneHelperTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        ProfilesManager.instance = null
        context = ApplicationProvider.getApplicationContext()
        val profilesDir = File(context.filesDir, "profiles")
        deleteRecursively(profilesDir)
    }

    @Test
    fun testAnalyzeProducesValidResult() {
        val result = AutoTuneHelper.analyze(context)
        assertNotNull(result)
        assertTrue(result.displayWidth > 0)
        assertTrue(result.displayHeight > 0)
        assertTrue(result.maxRefreshRate > 0)
        assertTrue(result.recommendedResolution.isNotEmpty())
        assertTrue(result.recommendedFps.toInt() >= 30)
        assertTrue(result.recommendedBitrateKbps > 0)
        assertEquals("FORCE_HEVC", result.recommendedVideoFormat)
        assertTrue(result.ultraLowLatency)
        assertTrue(result.snappyInput)
    }

    @Test
    fun testApplyAutoTuneUpdatesPreferences() {
        val result = AutoTuneHelper.applyAutoTune(context)
        val prefs = PreferenceConfiguration.getDefaultSharedPreferences(context)

        assertEquals(result.recommendedResolution, prefs.getString("list_resolution", null))
        assertEquals(result.recommendedFps, prefs.getString("list_fps", null))
        assertEquals(result.recommendedBitrateKbps, prefs.getInt("seekbar_bitrate_kbps", 0))
        assertEquals("FORCE_HEVC", prefs.getString("video_format", null))
        assertTrue(prefs.getBoolean("checkbox_ultra_low_latency", false))
        assertTrue(prefs.getBoolean("checkbox_snappy_input", false))
        assertTrue(prefs.getBoolean("checkbox_async_decode", false))
    }

    @Test
    fun testApplyAutoTuneUpdatesActiveProfile() {
        val pm = ProfilesManager.getInstance()
        pm.load(context)
        val now = System.currentTimeMillis()
        val profile = SettingsProfile(
            UUID.randomUUID(),
            "Test Profile",
            now,
            now,
            HashMap()
        )
        pm.add(profile)
        pm.setActive(profile.uuid)

        val result = AutoTuneHelper.applyAutoTune(context)
        val active = pm.active
        assertNotNull(active)
        assertEquals(result.recommendedResolution, active?.options?.get("list_resolution"))
        assertEquals(result.recommendedFps, active?.options?.get("list_fps"))
        assertEquals(result.recommendedBitrateKbps, active?.options?.get("seekbar_bitrate_kbps"))
        assertEquals("FORCE_HEVC", active?.options?.get("video_format"))
    }

    @Test
    fun testWizardStringsFormatting() {
        val result = AutoTuneHelper.analyze(context)
        val res = context.resources

        // Test default (English) locale formatting
        val bannerStr = res.getString(
            com.ineedmypills.vibemis.R.string.wizard_autotune_banner_desc,
            result.recommendedResolution,
            result.recommendedFps,
            result.recommendedBitrateKbps / 1000,
            "HEVC"
        )
        assertTrue(bannerStr.contains(result.recommendedResolution))
        assertTrue(bannerStr.contains(result.recommendedFps))

        val toastStr = res.getString(
            com.ineedmypills.vibemis.R.string.autotune_applied_toast,
            result.recommendedResolution,
            result.recommendedFps
        )
        assertTrue(toastStr.contains(result.recommendedResolution))
        assertTrue(toastStr.contains(result.recommendedFps))
    }

    private fun deleteRecursively(f: File?) {
        if (f == null || !f.exists()) return
        if (f.isDirectory) {
            f.listFiles()?.forEach { deleteRecursively(it) }
        }
        f.delete()
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun suppressLogs() {
            TestLogSuppressor.install()
        }
    }
}
