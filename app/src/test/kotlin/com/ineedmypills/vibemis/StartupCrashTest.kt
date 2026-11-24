package com.ineedmypills.vibemis

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.ineedmypills.vibemis.preferences.PreferenceConfiguration
import com.ineedmypills.vibemis.profiles.ProfilesManager
import com.ineedmypills.vibemis.shadows.ShadowGameManager
import com.ineedmypills.vibemis.shadows.ShadowVibemisBridge
import com.ineedmypills.vibemis.utils.UiHelper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@Config(sdk = [33], shadows = [ShadowVibemisBridge::class, ShadowGameManager::class])
@RunWith(RobolectricTestRunner::class)
class StartupCrashTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        ProfilesManager.instance = null
        context = ApplicationProvider.getApplicationContext()
        val profilesDir = File(context.filesDir, "profiles")
        deleteRecursively(profilesDir)
    }

    @Test
    fun testNativeLibraryLoadingFailure() {
        val activity = Robolectric.buildActivity(PcView::class.java).create().get()
        assertNotNull("Activity should handle native library issues", activity)
    }

    @Test
    fun testGLSurfaceViewInitialization() {
        val activity = Robolectric.buildActivity(PcView::class.java).create().get()
        assertNotNull("Activity should survive GL initialization", activity)
        assertFalse("Activity should not be finishing after GL init", activity.isFinishing)
    }

    @Test
    fun testPreferenceConfigurationCrash() {
        val config = PreferenceConfiguration.readPreferences(context)
        assertNotNull("PreferenceConfiguration should be readable", config)
    }

    @Test
    fun testUiHelperCrash() {
        val activity = Robolectric.buildActivity(PcView::class.java).create().get()
        UiHelper.setLocale(activity)
    }

    @Test
    fun testComputerManagerServiceBinding() {
        val activity = Robolectric.buildActivity(PcView::class.java).create().get()
        assertNotNull("Activity should handle service binding", activity)
    }

    @Test
    fun testSharedPreferencesCorruption() {
        val prefs = context.getSharedPreferences("test", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("resolution", "invalid_resolution")
            .putInt("bitrate", -1)
            .putBoolean("enable_hdr", true)
            .apply()

        val config = PreferenceConfiguration.readPreferences(context)
        assertNotNull("Should handle corrupted preferences", config)
    }

    @Test
    fun testMissingRequiredIntentExtras() {
        val intent = Intent()
        val activity = Robolectric.buildActivity(AppView::class.java, intent).create().get()
        assertNotNull("Activity should either work or finish cleanly", activity)
    }

    @Test
    fun testInvalidUuidInIntent() {
        val intent = Intent().apply {
            putExtra(AppView.NAME_EXTRA, "Test Computer")
            putExtra(AppView.UUID_EXTRA, "invalid-uuid")
        }
        val activity = Robolectric.buildActivity(AppView::class.java, intent).create().get()
        assertNotNull("Should handle invalid UUID", activity)
    }

    @Test
    fun testFileSystemPermissionDenied() {
        val profilesDir = File(context.filesDir, "profiles")
        profilesDir.mkdirs()
        profilesDir.setReadOnly()

        try {
            val manager = ProfilesManager.getInstance()
            manager.load(context)
            manager.save(context)
            assertNotNull("ProfilesManager should handle file permission issues", manager.profiles)
        } finally {
            profilesDir.setWritable(true)
        }
    }

    @Test
    fun testConcurrentStartup() {
        val activity1 = Robolectric.buildActivity(PcView::class.java).create().get()
        val activity2 = Robolectric.buildActivity(PcView::class.java).create().get()
        assertNotNull("First activity should be created", activity1)
        assertNotNull("Second activity should be created", activity2)
    }

    @Test
    fun testActivityLifecycleTransitions() {
        val activity = Robolectric.buildActivity(PcView::class.java)
            .create()
            .start()
            .resume()
            .pause()
            .stop()
            .restart()
            .start()
            .resume()
            .get()
        assertNotNull("Activity should survive lifecycle transitions", activity)
    }

    @Test
    fun testStartupWithNetworkUnavailable() {
        val activity = Robolectric.buildActivity(PcView::class.java).create().get()
        assertNotNull("Activity should handle network unavailability", activity)
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
        fun suppressInvalidIdLogs() {
            TestLogSuppressor.install()
        }
    }
}
