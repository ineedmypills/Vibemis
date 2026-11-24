package com.ineedmypills.vibemis.profiles

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ineedmypills.vibemis.TestLogSuppressor
import com.ineedmypills.vibemis.shadows.ShadowGameManager
import com.ineedmypills.vibemis.shadows.ShadowVibemisBridge
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
class ProfilesManagerTest {
    private lateinit var context: Context
    private lateinit var manager: ProfilesManager
    private lateinit var profilesDir: File

    @Before
    fun setUp() {
        ProfilesManager.instance = null
        context = ApplicationProvider.getApplicationContext()
        profilesDir = File(context.filesDir, "profiles")
        deleteRecursively(profilesDir)
        manager = ProfilesManager.getInstance()
        manager.load(context)
    }

    @After
    fun tearDown() {
        deleteRecursively(profilesDir)
    }

    @Test
    fun addAndRetrieveProfile() {
        val p = SettingsProfile(UUID.randomUUID(), "Test", System.currentTimeMillis(), System.currentTimeMillis(), null)
        manager.add(p)
        assertEquals(1, manager.profiles.size)
        assertEquals(p.uuid, manager.profiles[0].uuid)
    }

    @Test
    fun setActivePersists() {
        val p = SettingsProfile(UUID.randomUUID(), "Active", System.currentTimeMillis(), System.currentTimeMillis(), null)
        manager.add(p)
        manager.setActive(p.uuid)
        val fresh = ProfilesManager.getInstance()
        fresh.load(context)
        assertNotNull(fresh.active)
        assertEquals(p.uuid, fresh.active!!.uuid)
    }

    @Test
    fun updateAndSaveProfile() {
        val p = SettingsProfile(UUID.randomUUID(), "Original", System.currentTimeMillis(), System.currentTimeMillis(), null)
        manager.add(p)
        p.name = "Updated"
        manager.update(p)

        val fresh = ProfilesManager.getInstance()
        fresh.load(context)
        assertEquals("Updated", fresh.profiles[0].name)
    }

    @Test
    fun deleteProfile() {
        val p = SettingsProfile(UUID.randomUUID(), "ToDelete", System.currentTimeMillis(), System.currentTimeMillis(), null)
        manager.add(p)
        assertEquals(1, manager.profiles.size)

        manager.delete(p.uuid)
        assertEquals(0, manager.profiles.size)
    }

    @Test
    fun deleteActiveProfile_resetsActive() {
        val p = SettingsProfile(UUID.randomUUID(), "ActiveToDelete", System.currentTimeMillis(), System.currentTimeMillis(), null)
        manager.add(p)
        manager.setActive(p.uuid)
        assertNotNull(manager.active)

        manager.delete(p.uuid)
        assertNull(manager.active)

        val fresh = ProfilesManager.getInstance()
        fresh.load(context)
        assertNull(fresh.active)
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
