package com.ineedmypills.vibemis.profiles

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ineedmypills.vibemis.TestLogSuppressor
import com.ineedmypills.vibemis.preferences.PreferenceConfiguration
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
import java.util.UUID

@Config(sdk = [33], shadows = [ShadowVibemisBridge::class, ShadowGameManager::class])
@RunWith(RobolectricTestRunner::class)
class OverlayPreferencesTest {
    private lateinit var ctx: Context

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        ProfilesManager.instance = null
    }

    @Test
    fun overlayPref_CoercesDoubleToInt() {
        val pm = ProfilesManager.getInstance()
        pm.load(ctx)

        val json = "{\"video_bitrate_kbps\":15000}"
        val t = object : TypeToken<MutableMap<String, Any?>>() {}.type
        val opts: MutableMap<String, Any?> = Gson().fromJson(json, t)

        val profile = SettingsProfile(UUID.randomUUID(), "Test", 0, 0, opts)
        pm.add(profile)
        pm.setActive(profile.uuid)

        val sp = pm.getOverlayingSharedPreferences(ctx)
        assertEquals(15000, sp.getInt("video_bitrate_kbps", -1))
    }

    @Test
    fun overlayPref_CoercesDoubleToLong() {
        val pm = ProfilesManager.getInstance()
        pm.load(ctx)
        val json = "{\"decoder_flush_delay_ms\":250.0}"
        val t = object : TypeToken<MutableMap<String, Any?>>() {}.type
        val opts: MutableMap<String, Any?> = Gson().fromJson(json, t)

        val profile = SettingsProfile(UUID.randomUUID(), "TestLong", 0, 0, opts)
        pm.add(profile)
        pm.setActive(profile.uuid)

        val sp = pm.getOverlayingSharedPreferences(ctx)
        assertEquals(250L, sp.getLong("decoder_flush_delay_ms", -1))
    }

    @Test
    fun overlayPref_RemembersZoomOptionsBetweenSessions() {
        val pm = ProfilesManager.getInstance()
        pm.load(ctx)

        val json = "{\"checkbox_show_overlay_zoom_toggle_button\":true," +
            "\"checkbox_remember_zoom_pan\":true," +
            "\"number_zoom_scale\":1.5," +
            "\"number_pan_offset_x\":0.25," +
            "\"number_pan_offset_y\":0.25}"
        val t = object : TypeToken<MutableMap<String, Any?>>() {}.type
        val opts: MutableMap<String, Any?> = Gson().fromJson(json, t)

        val profile = SettingsProfile(UUID.randomUUID(), "ZoomTest", 0, 0, opts)
        pm.add(profile)
        pm.setActive(profile.uuid)

        val sp = pm.getOverlayingSharedPreferences(ctx)
        assertTrue(sp.getBoolean("checkbox_show_overlay_zoom_toggle_button", false))
        assertTrue(sp.getBoolean("checkbox_remember_zoom_pan", false))
        assertEquals(1.5f, sp.getFloat("number_zoom_scale", -1f), 0.0001f)
        assertEquals(0.25f, sp.getFloat("number_pan_offset_x", -1f), 0.0001f)
        assertEquals(0.25f, sp.getFloat("number_pan_offset_y", -1f), 0.0001f)

        val cfg = PreferenceConfiguration.readPreferences(ctx)
        assertTrue(cfg.showOverlayZoomToggleButton)
        assertTrue(cfg.rememberZoomPan)
        assertEquals(1.5f, cfg.zoomScale, 0.0001f)
        assertEquals(0.25f, cfg.panOffsetX, 0.0001f)
        assertEquals(0.25f, cfg.panOffsetY, 0.0001f)

        ProfilesManager.instance = null
        val pm2 = ProfilesManager.getInstance()
        pm2.load(ctx)

        val cfg2 = PreferenceConfiguration.readPreferences(ctx)
        assertTrue(cfg2.showOverlayZoomToggleButton)
        assertTrue(cfg2.rememberZoomPan)
        assertEquals(1.5f, cfg2.zoomScale, 0.0001f)
        assertEquals(0.25f, cfg2.panOffsetX, 0.0001f)
        assertEquals(0.25f, cfg2.panOffsetY, 0.0001f)
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun suppressInvalidIdLogs() {
            TestLogSuppressor.install()
        }
    }
}
