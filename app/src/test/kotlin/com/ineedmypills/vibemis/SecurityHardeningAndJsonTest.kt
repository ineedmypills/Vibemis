package com.ineedmypills.vibemis

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ineedmypills.vibemis.binding.crypto.AndroidCryptoProvider
import com.ineedmypills.vibemis.nvstream.http.NvHTTP
import com.ineedmypills.vibemis.nvstream.http.VibepolloCapabilities
import com.ineedmypills.vibemis.profiles.ProfilesManager
import com.ineedmypills.vibemis.profiles.SettingsProfile
import com.ineedmypills.vibemis.shadows.ShadowGameManager
import com.ineedmypills.vibemis.shadows.ShadowVibemisBridge
import com.ineedmypills.vibemis.utils.CacheHelper
import com.ineedmypills.vibemis.utils.KeyConfigHelper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID
import javax.crypto.KeyGenerator

@Config(sdk = [33], shadows = [ShadowVibemisBridge::class, ShadowGameManager::class])
@RunWith(RobolectricTestRunner::class)
class SecurityHardeningAndJsonTest {

    @Test
    fun cacheHelper_rejectsTraversalAndInvalidComponents() {
        val root = Files.createTempDirectory("vibemis_cache_test").toFile()
        try {
            val valid = CacheHelper.openPath(true, root, "boxart", "host-1", "101.png")
            assertTrue(valid.canonicalPath.startsWith(root.canonicalPath + File.separator))

            assertThrows(SecurityException::class.java) {
                CacheHelper.openPath(false, root, "..", "secret.key")
            }
            assertThrows(SecurityException::class.java) {
                CacheHelper.openPath(false, root, "boxart/../../etc", "passwd")
            }
            assertThrows(SecurityException::class.java) {
                CacheHelper.openPath(false, root, "bad\\path")
            }
            assertThrows(SecurityException::class.java) {
                CacheHelper.openPath(false, root, "null\u0000byte")
            }
            assertThrows(SecurityException::class.java) {
                CacheHelper.openPath(false, root, "")
            }
            assertThrows(SecurityException::class.java) {
                CacheHelper.openPath(false, root)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cacheHelper_readInputStreamBoundedAndUtf8() {
        val text = "Vibemis-UTF8-\u041f\u0440\u0438\u0432\u0435\u0442"
        val loaded = CacheHelper.readInputStreamToString(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        assertEquals(text, loaded)
    }

    @Test
    fun androidCryptoProvider_sealsUnsealsAndDetectsTampering() {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        val secretKey = keyGen.generateKey()

        val rawPkcs8 = ByteArray(128) { i -> (i * 7 + 3).toByte() }
        assertFalse(AndroidCryptoProvider.hasSealedMagic(rawPkcs8))

        val sealed = AndroidCryptoProvider.sealPrivateKeyWithKey(rawPkcs8, secretKey)
        assertTrue(AndroidCryptoProvider.hasSealedMagic(sealed))

        val recovered = AndroidCryptoProvider.unsealPrivateKeyWithKey(sealed, secretKey)
        assertNotNull(recovered)
        assertArrayEquals(rawPkcs8, recovered)

        val tampered = sealed.copyOf()
        tampered[tampered.lastIndex] = (tampered[tampered.lastIndex].toInt() xor 0xFF).toByte()
        assertNull(AndroidCryptoProvider.unsealPrivateKeyWithKey(tampered, secretKey))
    }

    @Test
    fun keyConfigHelper_parsesValidAndRejectsMalformedJson() {
        val json = """
            {
              "data": [
                {
                  "id": "alt_tab",
                  "name": "Alt+Tab",
                  "sticky": false,
                  "keys": ["VK_LMENU", "VK_TAB"]
                }
              ]
            }
        """.trimIndent()
        val parsed = KeyConfigHelper.parseShortcutFile(json)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.data.size)
        assertEquals("alt_tab", parsed.data[0].id)
        assertEquals(listOf("VK_LMENU", "VK_TAB"), parsed.data[0].keys)

        assertNull(KeyConfigHelper.parseShortcutFile("{broken"))
        assertNull(KeyConfigHelper.parseShortcutFile(""))
    }

    @Test
    fun profilesManager_orgJsonRoundtripPreservesTypes() {
        ProfilesManager.instance = null
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val profilesDir = File(ctx.filesDir, "profiles")
        profilesDir.deleteRecursively()
        try {
            val mgr = ProfilesManager.getInstance()
            assertTrue(mgr.load(ctx))

            val id = UUID.randomUUID()
            val opts = LinkedHashMap<String, Any?>()
            opts["checkbox_enable_hdr"] = true
            opts["seekbar_bitrate_kbps"] = 85000
            opts["list_fps"] = "120"
            opts["string_set_test"] = setOf("a", "b")
            opts["nullable_test"] = null

            val profile = SettingsProfile(id, "Living Room 4K", 1000L, 2000L, opts)
            mgr.add(profile)
            mgr.setActive(id)

            ProfilesManager.instance = null
            val reloaded = ProfilesManager.getInstance()
            assertTrue(reloaded.load(ctx))
            assertEquals(id, reloaded.active?.uuid)
            assertEquals("Living Room 4K", reloaded.active?.name)

            val overlayPrefs = reloaded.getOverlayingSharedPreferences(ctx)
            assertEquals(true, overlayPrefs.getBoolean("checkbox_enable_hdr", false))
            assertEquals(85000, overlayPrefs.getInt("seekbar_bitrate_kbps", 0))
            assertEquals("120", overlayPrefs.getString("list_fps", null))
            assertEquals(setOf("a", "b"), overlayPrefs.getStringSet("string_set_test", null))
        } finally {
            profilesDir.deleteRecursively()
            ProfilesManager.instance = null
        }
    }

    @Test
    fun nvHttp_singlePassXmlAndVibepolloCapabilities() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <root status_code="200">
              <hostname>DesktopRig</hostname>
              <uniqueid>ABC-123</uniqueid>
              <appversion>7.1.431.-1</appversion>
              <HttpsPort>47984</HttpsPort>
              <ExternalPort>47989</ExternalPort>
              <PairStatus>1</PairStatus>
              <state>MJOLNIR_SERVER_FREE</state>
              <ServerCodecModeSupport>8388867</ServerCodecModeSupport>
              <SleepSupported>1</SleepSupported>
              <SleepAllowed>1</SleepAllowed>
              <PyroWaveHostLinkMbps>1000</PyroWaveHostLinkMbps>
            </root>
        """.trimIndent()
        val parsed = NvHTTP.parseXmlSinglePass(xml)
        assertEquals("DesktopRig", parsed.requireValue("hostname"))
        assertEquals("ABC-123", parsed.requireValue("uniqueid"))

        val caps = VibepolloCapabilities.fromParsedXml(parsed)
        assertTrue(caps.sleepSupported)
        assertTrue(caps.sleepAllowed)
        assertEquals(1000, caps.pyroWaveHostLinkMbps)
    }

    @Test
    fun nvHttp_singlePassXmlCoalescesSplitTextAndEntities() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <root status_code="200">
              <hostname>Gaming &amp; TV &lt;4K&gt;</hostname>
              <ServerCommand>Cmd &amp; One</ServerCommand>
              <ServerCommand>Cmd &amp; Two</ServerCommand>
            </root>
        """.trimIndent()
        val parsed = NvHTTP.parseXmlSinglePass(xml)
        assertEquals("Gaming & TV <4K>", parsed.requireValue("hostname"))
        assertEquals(listOf("Cmd & One", "Cmd & Two"), parsed.allValues("ServerCommand"))
    }
}
