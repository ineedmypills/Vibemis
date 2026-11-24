package com.ineedmypills.vibemis

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.ineedmypills.vibemis.computers.ComputerManagerListener
import com.ineedmypills.vibemis.computers.ComputerManagerService
import com.ineedmypills.vibemis.discovery.DiscoveryService
import com.ineedmypills.vibemis.nvstream.http.ComputerDetails
import com.ineedmypills.vibemis.nvstream.http.VibemisCryptoProvider
import com.ineedmypills.vibemis.nvstream.http.NvApp
import com.ineedmypills.vibemis.nvstream.http.NvHTTP
import com.ineedmypills.vibemis.nvstream.http.PairingManager
import com.ineedmypills.vibemis.shadows.ShadowGameManager
import com.ineedmypills.vibemis.shadows.ShadowVibemisBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.net.ServerSocket
import java.util.Base64
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Config(sdk = [33], shadows = [ShadowVibemisBridge::class, ShadowGameManager::class])
@RunWith(RobolectricTestRunner::class)
class AppListAndPollerHardeningTest {

    @Test
    fun nvHttpGetAppListByReader_trimsVibepolloTitlesAndMetadata() {
        val xml = """
            <root status_code="200">
                <App>
                    <AppTitle>     Desktop</AppTitle>
                    <ID> 1001 </ID>
                    <UUID> 9a1c5a25-58fe-40e0-b9aa-7d3f00000000 </UUID>
                    <IDX> 0 </IDX>
                    <IsHdrSupported> 1 </IsHdrSupported>
                </App>
                <App>
                    <AppTitle>  Resume</AppTitle>
                    <ID>2147483501</ID>
                    <UUID>9a1c5a25-58fe-40e0-b9aa-7d3f00000001</UUID>
                    <IDX>1</IDX>
                    <IsHdrSupported>0</IsHdrSupported>
                </App>
                <App>
                    <AppTitle> Terminate</AppTitle>
                    <ID>2147483504</ID>
                    <UUID>9a1c5a25-58fe-40e0-b9aa-7d3f00000004</UUID>
                    <IDX>2</IDX>
                </App>
                <App>
                    <AppTitle>    Remote Monitor</AppTitle>
                    <ID>2147483505</ID>
                    <UUID>9a1c5a25-58fe-40e0-b9aa-7d3f00000005</UUID>
                    <IDX>3</IDX>
                </App>
                <App>
                    <AppTitle>   Remote Input</AppTitle>
                    <ID>2147483506</ID>
                    <UUID>9a1c5a25-58fe-40e0-b9aa-7d3f00000006</UUID>
                    <IDX>4</IDX>
                </App>
            </root>
        """.trimIndent()

        val apps = NvHTTP.getAppListByReader(StringReader(xml))
        assertEquals(listOf("Desktop", "Resume", "Terminate", "Remote Monitor", "Remote Input"), apps.map { it.appName })
        assertEquals(1001, apps[0].appId)
        assertEquals("9a1c5a25-58fe-40e0-b9aa-7d3f00000000", apps[0].appUUID)
        assertTrue(apps[0].isHdrSupported)
    }

    @Test
    fun nvApp_trimsNameInConstructorAndSetter() {
        val constructed = NvApp("   Desktop  ")
        assertEquals("Desktop", constructed.appName)

        val mutated = NvApp()
        mutated.appName = "  Remote Monitor\n"
        assertEquals("Remote Monitor", mutated.appName)
    }

    @Test
    fun pcViewPlurals_formatsSingularAndPluralHostsCorrectly() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val res = ctx.resources
        assertEquals("1 host (1 online)", res.getQuantityString(R.plurals.pcview_subtitle_hosts, 1, 1, 1))
        assertEquals("2 hosts (1 online)", res.getQuantityString(R.plurals.pcview_subtitle_hosts, 2, 2, 1))
        assertEquals("0 hosts (0 online)", res.getQuantityString(R.plurals.pcview_subtitle_hosts, 0, 0, 0))
        assertEquals(
            "Failed to start \"Desktop\" (code -408)",
            ctx.getString(R.string.conn_error_stage_code_fmt, ctx.getString(R.string.conn_error_msg), "Desktop", -408)
        )

        val ruCfg = Configuration(res.configuration).apply { setLocale(Locale.forLanguageTag("ru")) }
        val ruCtx = ctx.createConfigurationContext(ruCfg)
        val ruRes = ruCtx.resources
        assertEquals("1 хост (1 в сети)", ruRes.getQuantityString(R.plurals.pcview_subtitle_hosts, 1, 1, 1))
        assertEquals("2 хоста (1 в сети)", ruRes.getQuantityString(R.plurals.pcview_subtitle_hosts, 2, 2, 1))
        assertEquals("5 хостов (2 в сети)", ruRes.getQuantityString(R.plurals.pcview_subtitle_hosts, 5, 5, 2))
        assertEquals(
            "Запуск не удался «Desktop» (код -408)",
            ruCtx.getString(R.string.conn_error_stage_code_fmt, ruCtx.getString(R.string.conn_error_msg), "Desktop", -408)
        )
    }

    @Test
    fun applistPoller_onlineStartAndInterruptDuringConnect_pollsAndExitsCleanly() {
        val uncaught = AtomicReference<Throwable?>()
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        val appContext = ApplicationProvider.getApplicationContext<Context>() as Application
        val glPrefs = appContext.getSharedPreferences("GlPreferences", 0)
        glPrefs.edit()
            .putString("Renderer", "TestRenderer")
            .putString("Fingerprint", android.os.Build.FINGERPRINT)
            .commit()
        val testCertPem = """
            -----BEGIN CERTIFICATE-----
            MIIC3jCCAcagAwIBAgIUC5l/J2ISuykOb6bQxCfWsig49HgwDQYJKoZIhvcNAQEL
            BQAwDzENMAsGA1UEAwwEVGVzdDAeFw0yNjExMTQxNDQ0MThaFw0zNjExMTExNDQ0
            MThaMA8xDTALBgNVBAMMBFRlc3QwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEK
            AoIBAQDFQTbMBejlsaNAcB+U0nz3hYFu96J0eR3IEU7zKPIw2cjxaQNKnUFbtk9c
            4BTcX/KiyJ4f3LTtx+eib3Swg1lcHg8zac7YbTeCvMv9lIqqOJxngrBcg8PFuHli
            2jHBRfZiMYtv8lKtlrIED1Llgd49RzdzCKi0wfuNC2DyPJNJ8qNHhCy+x+ZrwthG
            WMVsUAk8zqNVe9UtoREpgavnRyMf5D5sAfbWA4cCnc2Yx3fz/LIcjeIeXHVnC2XS
            egnB7AHHy0LMI8/mZv/ghuJo4LVRmVGUFh4BCnNCdASXNmIcncC8u1xOPR2pfjQo
            RtYfI8H2DeoqCIxBEPW7ad9TLj0HAgMBAAGjMjAwMB0GA1UdDgQWBBS4htr+4LOX
            1d5VvnOETlzi/wh2MjAPBgNVHRMBAf8EBTADAQH/MA0GCSqGSIb3DQEBCwUAA4IB
            AQB5/QzTrc/UT1Q2J7enq+hgR16ZWe9BsATiE32OyznPu5ykQNAhnYPte3jnwXJW
            VDYErlo2eSF6viiXIV6QctVFjb5z5Ee541QgQKGmhFDambbb06CDNfmsB44JTBV2
            rPrMy5Y+vVkY1KRAMI9nCfOvLwovkbKbFjpVhr5kKr/tkzwndcy1/jZgj0AHpM1F
            CasRVziKZhTXHVtxq7hJXGFVKJh9m9UuR/bfwKga87dYoeKwaFEUQwsPGmMOEcCc
            1KaCXT2qKhVksvY1uCrWwWudSk4JopBVdXrFaZ22I/cF/SnTwl0YgznUjz22uezj
            W4hUpNie6c3RiA1np0iUIYvx
            -----END CERTIFICATE-----
        """.trimIndent() + "\n"
        val testKeyDerB64 =
            "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDFQTbMBejlsaNAcB+U0nz3hYFu96J0eR3IEU7zKPIw2cjxaQNKnUFbtk9c" +
                "4BTcX/KiyJ4f3LTtx+eib3Swg1lcHg8zac7YbTeCvMv9lIqqOJxngrBcg8PFuHli2jHBRfZiMYtv8lKtlrIED1Llgd49RzdzCKi0wfuNC2Dy" +
                "PJNJ8qNHhCy+x+ZrwthGWMVsUAk8zqNVe9UtoREpgavnRyMf5D5sAfbWA4cCnc2Yx3fz/LIcjeIeXHVnC2XSegnB7AHHy0LMI8/mZv/ghuJo" +
                "4LVRmVGUFh4BCnNCdASXNmIcncC8u1xOPR2pfjQoRtYfI8H2DeoqCIxBEPW7ad9TLj0HAgMBAAECggEAD+G/pGRYFiEGqz+GgMMEe3bCWcmS" +
                "c5z01ZD0hwYlnzf0bTYupP6jA9wFPnhu9Yy8H83V/WwVx+IFN8I65k4NDypUav1P7C5kU8sZBDfGL7e90A++XL83L1+Pq8fe+FgbuFg+M9X" +
                "nlkEV4Cx5A4WV+9+/X9cIo6p4CH6BYGpEKaXn5D8j0gjKDg3Cw5cZqSP9Zk+PmeFA2hUuQYIEiJ70anV9WUDGtNZu1wNSd+CHL5E1b49Omd" +
                "cAlri4EWJMlit6g5MprlxcnTXTaBml3UBrSrgLeeztyKGyr7TBdybhoc5Arn0Xc6VCfVb0zxRyLk9FJvIRzasTT4/JWBc3TXvy0QKBgQD4s" +
                "56dUges+/DCycT/SASpDJVSTTEnOjdm9xe9cEQZpHU5Mq2izzHDlJS+nZ6YQeAPIx0OynJPAYtgcV7dSif6+NjU8ntgGlVWUF0+Hves6/pI" +
                "vPjEe6BY+Gb0vykSENK8K3OnZIFErnixuUQptMYAq28sFe1H5rYSOlckHnyMewKBgQDLCxkECAkXHJO30Yz0FyXEJeRaohvoTHQH3PLXFPA6" +
                "OFuGyfbTH6LpwGgkRQyFhFvaJdABTBMLPcA/4k0dHTuRYdXPCJ5tdPI6C7qbk7RqaF7KYtoWyQFoM6GwVm8lg1+EKNp8LchbpvR1Cqbiv55" +
                "Ohpcz0niQM0NUtoKSnOjJ5QKBgQCV4HFbL58ukVojmULd8UHVup6WTaysjjnrFEe7Q1fW+DQotzDK6AB+dCE7ynNUIIbtuGR/kGNY135uLY" +
                "RU6aa0aZkKo+LUJKp+7Ml0cWcjT8ayZSeqCTK1y2UIY9dVaswE2E7HdxssD9Tujc7VL3sP1kCWGdS4OwJIc94mBzVZ1wKBgFs+WniIT+V17" +
                "x/86FXMjSkvSgY8sUPcD4x1CyAChc30A5V37xaDMgfPcj1SVkUt7MUElwvuLuSvVFShErxRpbI4gAewvNk9Y5kYKjy9zKbAzlAuygjRPXOh" +
                "ODjxLrNx7RuL7Fs/i7k8NsDeD474Q2DQdORR4eITV1+bQOQ35RjNAoGAB5w2A8US/pcO/eMRCxI24FSYzcM3J6w8ef6OlEn2mZgtqZcT9QGS" +
                "053wWzGlC162mMTuk8LVKirTjdF/bNUHTsyEDRUWsRbhs9D+opFMwsthVwim7+J6UOXeaJxswS7voEnw0V2eEvGpPytthMQr6URW9uiSboDeXHDMJ4cQph4="
        File(appContext.filesDir, "client.crt").writeText(testCertPem)
        File(appContext.filesDir, "client.key").writeBytes(Base64.getDecoder().decode(testKeyDerB64))
        val discoveryCn = ComponentName(appContext, DiscoveryService::class.java)
        Shadows.shadowOf(appContext).setComponentNameAndServiceForBindService(
            discoveryCn,
            mock(DiscoveryService.DiscoveryBinder::class.java)
        )

        val acceptedLatch = CountDownLatch(1)
        val server = ServerSocket(0)
        val acceptThread = Thread {
            try {
                server.accept().use {
                    acceptedLatch.countDown()
                    Thread.sleep(3000)
                }
            } catch (_: Throwable) {
            }
        }.apply { start() }

        val serviceController = Robolectric.buildService(ComputerManagerService::class.java).create()
        val binder = serviceController.get().onBind(null) as ComputerManagerService.ComputerManagerBinder

        try {
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                if (thread.name.startsWith("App list polling thread")) {
                    uncaught.compareAndSet(null, throwable)
                } else {
                    prevHandler?.uncaughtException(thread, throwable)
                }
            }
            binder.startPolling(ComputerManagerListener { })

            val details = ComputerDetails().apply {
                name = "OnlineTestPC"
                uuid = "test-uuid-online-interrupt"
                state = ComputerDetails.State.ONLINE
                pairState = PairingManager.PairState.PAIRED
                localAddress = ComputerDetails.AddressTuple("127.0.0.1", server.localPort)
                activeAddress = localAddress
                httpsPort = server.localPort
            }

            val poller = binder.createAppListPoller(details)
            poller.start()
            assertTrue("Online ApplistPoller must enter poll loop immediately after start()", acceptedLatch.await(3, TimeUnit.SECONDS))
            poller.stop()
            Thread.sleep(200)

            assertNull("ApplistPoller must not crash when stopped mid-request", uncaught.get())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(prevHandler)
            runCatching { server.close() }
            runCatching { acceptThread.interrupt() }
            serviceController.destroy()
        }
    }

    @Test
    fun nvHttp_interruptDuringBlockedCall_throwsIOExceptionNotInterruptedException() {
        val acceptedLatch = CountDownLatch(1)
        val caughtRef = AtomicReference<Throwable?>()
        val wasInterrupted = AtomicBoolean(false)
        val doneLatch = CountDownLatch(1)

        val server = ServerSocket(0)
        val acceptThread = Thread {
            try {
                server.accept().use {
                    acceptedLatch.countDown()
                    Thread.sleep(3000)
                }
            } catch (_: Throwable) {
            }
        }.apply { start() }

        val http = NvHTTP(
            ComputerDetails.AddressTuple("127.0.0.1", server.localPort),
            server.localPort,
            "0123456789abcdef",
            null,
            mock(VibemisCryptoProvider::class.java)
        )

        val callerThread = Thread {
            try {
                http.getServerInfo(true)
            } catch (t: Throwable) {
                caughtRef.set(t)
                wasInterrupted.set(Thread.currentThread().isInterrupted)
            } finally {
                doneLatch.countDown()
            }
        }.apply { start() }

        try {
            assertTrue("NvHTTP should connect to mock socket", acceptedLatch.await(3, TimeUnit.SECONDS))
            callerThread.interrupt()
            assertTrue("Interrupted NvHTTP call should return promptly", doneLatch.await(3, TimeUnit.SECONDS))
            assertTrue(
                "Expected IOException (not uncaught InterruptedException), got ${caughtRef.get()}",
                caughtRef.get() is IOException
            )
        } finally {
            runCatching { server.close() }
            runCatching { acceptThread.interrupt() }
        }
    }

    @Test
    fun applistPoller_startAndStopWhileOffline_exitsCleanlyWithoutUncaughtException() {
        val uncaught = AtomicReference<Throwable?>()
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        val appContext = ApplicationProvider.getApplicationContext<Context>() as Application
        val discoveryCn = ComponentName(appContext, DiscoveryService::class.java)
        Shadows.shadowOf(appContext).setComponentNameAndServiceForBindService(
            discoveryCn,
            mock(DiscoveryService.DiscoveryBinder::class.java)
        )

        val serviceController = Robolectric.buildService(ComputerManagerService::class.java).create()
        val binder = serviceController.get().onBind(null) as ComputerManagerService.ComputerManagerBinder

        try {
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                if (thread.name.startsWith("App list polling thread")) {
                    uncaught.compareAndSet(null, throwable)
                } else {
                    prevHandler?.uncaughtException(thread, throwable)
                }
            }

            val details = ComputerDetails().apply {
                name = "TestPC"
                uuid = "test-uuid-interrupt"
                state = ComputerDetails.State.OFFLINE
                pairState = PairingManager.PairState.PAIRED
                localAddress = ComputerDetails.AddressTuple("127.0.0.1", 1)
                activeAddress = localAddress
                httpsPort = 1
            }

            val poller = binder.createAppListPoller(details)
            poller.start()
            poller.start() // Idempotent guard check
            Thread.sleep(150)
            poller.stop()
            Thread.sleep(150)

            assertNull("ApplistPoller must not crash with uncaught exception", uncaught.get())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(prevHandler)
            serviceController.destroy()
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun suppressLogs() {
            TestLogSuppressor.install()
        }
    }
}
