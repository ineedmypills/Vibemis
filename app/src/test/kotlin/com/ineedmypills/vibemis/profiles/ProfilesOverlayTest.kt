package com.ineedmypills.vibemis.profiles

import android.content.Context
import com.ineedmypills.vibemis.preferences.PreferenceConfiguration
import androidx.test.core.app.ApplicationProvider
import com.ineedmypills.vibemis.TestLogSuppressor
import com.ineedmypills.vibemis.shadows.ShadowGameManager
import com.ineedmypills.vibemis.shadows.ShadowVibemisBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@Config(sdk = [33], shadows = [ShadowVibemisBridge::class, ShadowGameManager::class])
@RunWith(RobolectricTestRunner::class)
class ProfilesOverlayTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ProfilesManager.instance = null
        val profilesDir = File(context.filesDir, "profiles")
        deleteRecursively(profilesDir)
    }

    @Test
    fun overlaySharedPreferences_returnsPatchedValues() {
        val base = PreferenceConfiguration.getDefaultSharedPreferences(context)
        base.edit()
            .putBoolean("checkbox_ultra_low_latency", false)
            .putInt("seekbar_bitrate_kbps", 15000)
            .apply()

        val patch = HashMap<String, Any?>()
        patch["checkbox_ultra_low_latency"] = true
        patch["seekbar_bitrate_kbps"] = 30000

        val profile = SettingsProfile(UUID.randomUUID(), "Test", System.currentTimeMillis(), System.currentTimeMillis(), patch)
        val pm = ProfilesManager.getInstance()
        pm.add(profile)
        pm.setActive(profile.uuid)

        val overlay = pm.getOverlayingSharedPreferences(context)
        assertTrue(overlay.getBoolean("checkbox_ultra_low_latency", false))
        assertEquals(30000, overlay.getInt("seekbar_bitrate_kbps", 0))
    }

    @Test
    fun overlayPersistsAcrossSessions() {
        val patch = HashMap<String, Any?>()
        patch["checkbox_ultra_low_latency"] = true

        val profile = SettingsProfile(UUID.randomUUID(), "Persist", System.currentTimeMillis(), System.currentTimeMillis(), patch)
        val pm = ProfilesManager.getInstance()
        pm.add(profile)
        pm.setActive(profile.uuid)
        pm.save(context)

        ProfilesManager.instance = null
        val fresh = ProfilesManager.getInstance()
        fresh.load(context)

        val overlay = fresh.getOverlayingSharedPreferences(context)
        assertTrue(overlay.getBoolean("checkbox_ultra_low_latency", false))
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
