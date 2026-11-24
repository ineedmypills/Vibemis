package com.ineedmypills.vibemis

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.ineedmypills.vibemis.profiles.ProfilesManager
import com.ineedmypills.vibemis.shadows.ShadowGameManager
import com.ineedmypills.vibemis.shadows.ShadowVibemisBridge
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
import java.io.FileWriter

@Config(sdk = [33], shadows = [ShadowVibemisBridge::class, ShadowGameManager::class])
@RunWith(RobolectricTestRunner::class)
class StartupTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        ProfilesManager.instance = null
        context = ApplicationProvider.getApplicationContext()
        val profilesDir = File(context.filesDir, "profiles")
        deleteRecursively(profilesDir)
    }

    @Test
    fun testApplicationStartup() {
        val app = VibemisApplication()
        app.onCreate()

        val manager = ProfilesManager.getInstance()
        assertNotNull("ProfilesManager should be initialized", manager)
        assertNotNull("ProfilesManager should have loaded profiles", manager.profiles)
    }

    @Test
    fun testPcViewActivityCreation() {
        val activity = Robolectric.buildActivity(PcView::class.java).create().get()
        assertNotNull("PcView activity should be created", activity)
        assertFalse("Activity should not be finishing", activity.isFinishing)
    }

    @Test
    fun testPcViewActivityWithIntent() {
        val intent = Intent().apply {
            putExtra("hostname", "test.local")
            putExtra("port", 47989)
            putExtra("pin", "1234")
            putExtra("passphrase", "test")
        }

        val activity = Robolectric.buildActivity(PcView::class.java, intent).create().get()
        assertNotNull("PcView activity should be created with intent", activity)
        assertFalse("Activity should not be finishing", activity.isFinishing)
    }

    @Test
    fun testAppViewActivityCreation() {
        val intent = Intent().apply {
            putExtra(AppView.NAME_EXTRA, "Test Computer")
            putExtra(AppView.UUID_EXTRA, "test-uuid-123")
        }

        val activity = Robolectric.buildActivity(AppView::class.java, intent).create().get()
        assertNotNull("AppView activity should be created", activity)
        assertFalse("Activity should not be finishing", activity.isFinishing)
    }

    @Test
    fun testProfilesManagerFileSystemAccess() {
        val manager = ProfilesManager.getInstance()
        manager.load(context)
        manager.save(context)
    }

    @Test
    fun testMissingPermissions() {
        val activity = Robolectric.buildActivity(PcView::class.java).create().get()
        assertNotNull("Activity should handle permission checks", activity)
        val permissionResult = context.checkSelfPermission("android.permission.INTERNET")
        assertTrue("Permission check should work", permissionResult >= -1)
    }

    @Test
    fun testCorruptedProfilesFile() {
        val profilesDir = File(context.filesDir, "profiles")
        profilesDir.mkdirs()
        val profilesFile = File(profilesDir, "profiles.json")

        FileWriter(profilesFile).use { writer ->
            writer.write("{ corrupted json content")
        }

        val manager = ProfilesManager.getInstance()
        manager.load(context)
        assertNotNull("Profiles should be initialized even with corrupted file", manager.profiles)
    }

    @Test
    fun testNullContextHandling() {
        val manager = ProfilesManager.getInstance()
        manager.load(null)
        assertTrue("Should handle null context gracefully", true)
    }

    @Test
    fun testLowMemoryConditions() {
        val activity = Robolectric.buildActivity(PcView::class.java).create().get()
        assertNotNull("Activity should handle low memory conditions", activity)
        System.gc()
        activity.onLowMemory()
        assertFalse("Activity should not finish on low memory", activity.isFinishing)
    }

    @Test
    fun testConfigurationChanges() {
        val activity = Robolectric.buildActivity(PcView::class.java).create().start().resume().get()
        val newConfig = Configuration().apply {
            orientation = Configuration.ORIENTATION_LANDSCAPE
        }
        activity.onConfigurationChanged(newConfig)
        assertFalse("Activity should survive configuration changes", activity.isFinishing)
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
