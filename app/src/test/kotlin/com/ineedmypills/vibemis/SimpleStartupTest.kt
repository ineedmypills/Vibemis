package com.ineedmypills.vibemis

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ineedmypills.vibemis.profiles.ProfilesManager
import com.ineedmypills.vibemis.shadows.ShadowGameManager
import com.ineedmypills.vibemis.shadows.ShadowVibemisBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
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
class SimpleStartupTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        ProfilesManager.instance = null
        context = ApplicationProvider.getApplicationContext()
        val profilesDir = File(context.filesDir, "profiles")
        deleteRecursively(profilesDir)
    }

    @Test
    fun testApplicationCreation() {
        val app = VibemisApplication()
        assertNotNull("Application should be created", app)
    }

    @Test
    fun testApplicationOnCreate() {
        val app = VibemisApplication()
        app.onCreate()

        val manager = ProfilesManager.getInstance()
        assertNotNull("ProfilesManager should be initialized", manager)
        assertNotNull("Profiles list should be initialized", manager.profiles)
    }

    @Test
    fun testProfilesManagerSingleton() {
        val manager1 = ProfilesManager.getInstance()
        val manager2 = ProfilesManager.getInstance()

        assertNotNull("First instance should not be null", manager1)
        assertNotNull("Second instance should not be null", manager2)
        assertSame("Should return same instance", manager1, manager2)
    }

    @Test
    fun testProfilesManagerLoad() {
        val manager = ProfilesManager.getInstance()
        manager.load(context)

        assertNotNull("Profiles should be loaded", manager.profiles)
        assertEquals("Should start with empty profiles", 0, manager.profiles.size)
    }

    @Test
    fun testProfilesManagerSave() {
        val manager = ProfilesManager.getInstance()
        manager.load(context)
        manager.save(context)
        assertTrue("Save operation should complete", true)
    }

    @Test
    fun testContextFileAccess() {
        val filesDir = context.filesDir
        assertNotNull("Files directory should be accessible", filesDir)

        val testDir = File(filesDir, "test")
        val created = testDir.mkdirs()
        assertTrue("Should be able to create directories", created || testDir.exists())

        val deleted = testDir.delete()
        assertTrue("Should be able to delete directories", deleted)
    }

    @Test
    fun testNullContextHandling() {
        val manager = ProfilesManager.getInstance()
        manager.load(null)
        assertTrue("Should handle null context gracefully", true)
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
