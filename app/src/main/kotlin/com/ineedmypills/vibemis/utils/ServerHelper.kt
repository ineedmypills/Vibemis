package com.ineedmypills.vibemis.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.view.Display
import com.ineedmypills.vibemis.AppView
import com.ineedmypills.vibemis.Game
import com.ineedmypills.vibemis.VibemisLog
import com.ineedmypills.vibemis.R
import com.ineedmypills.vibemis.ShortcutTrampoline
import com.ineedmypills.vibemis.binding.PlatformBinding
import com.ineedmypills.vibemis.computers.ComputerManagerService
import com.ineedmypills.vibemis.nvstream.http.ComputerDetails
import com.ineedmypills.vibemis.nvstream.http.HostHttpResponseException
import com.ineedmypills.vibemis.nvstream.http.NvApp
import com.ineedmypills.vibemis.nvstream.http.NvHTTP
import com.ineedmypills.vibemis.nvstream.jni.VibemisBridge
import com.ineedmypills.vibemis.preferences.PreferenceConfiguration
import org.xmlpull.v1.XmlPullParserException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.UnknownHostException
import java.security.cert.CertificateEncodingException

object ServerHelper {
    @JvmStatic
    @Throws(IOException::class)
    fun getCurrentAddressFromComputer(computer: ComputerDetails): ComputerDetails.AddressTuple {
        return computer.activeAddress ?: throw IOException("No active address for ${computer.name}")
    }

    @JvmStatic
    fun createPcShortcutIntent(parent: Activity, computer: ComputerDetails): Intent {
        return Intent(parent, ShortcutTrampoline::class.java).apply {
            putExtra(AppView.NAME_EXTRA, computer.name)
            putExtra(AppView.UUID_EXTRA, computer.uuid)
            action = Intent.ACTION_DEFAULT
        }
    }

    @JvmStatic
    fun createAppShortcutIntent(parent: Activity, computer: ComputerDetails, app: NvApp): Intent {
        return Intent(parent, ShortcutTrampoline::class.java).apply {
            putExtra(AppView.NAME_EXTRA, computer.name)
            putExtra(AppView.UUID_EXTRA, computer.uuid)
            putExtra(Game.EXTRA_APP_NAME, app.appName)
            putExtra(Game.EXTRA_APP_UUID, app.appUUID)
            putExtra(Game.EXTRA_APP_ID, "${app.appId}")
            putExtra(Game.EXTRA_APP_HDR, app.isHdrSupported)
            action = Intent.ACTION_DEFAULT
        }
    }

    @JvmStatic
    fun getActiveDisplay(context: Context, prefs: PreferenceConfiguration): Display {
        val secondary = getSecondaryDisplay(context)
        return if (secondary != null && prefs.enableFullExDisplay) {
            secondary
        } else {
            (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        }
    }

    @JvmStatic
    fun getSecondaryDisplay(context: Context): Display? {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = displayManager.displays
        val mainDisplayId = Display.DEFAULT_DISPLAY
        var secondaryDisplayId = -1
        for (displayVariant in displays) {
            VibemisLog.info(displayVariant.toString())
            if (displayVariant.displayId != mainDisplayId) {
                secondaryDisplayId = displayVariant.displayId
                break
            }
        }
        return if (secondaryDisplayId != -1) displayManager.getDisplay(secondaryDisplayId) else null
    }

    @JvmStatic
    fun createStartIntent(
        parent: Activity,
        app: NvApp,
        computer: ComputerDetails,
        managerBinder: ComputerManagerService.ComputerManagerBinder,
        withVDisplay: Boolean
    ): Intent {
        var gameIntent: Intent? = null
        val prefConfig = PreferenceConfiguration.readPreferences(parent)
        val secondaryDisplay = getSecondaryDisplay(parent)
        if (prefConfig.enableFullExDisplay && secondaryDisplay != null) {
            val displayContext = parent.createDisplayContext(secondaryDisplay)
            gameIntent = Intent(displayContext, Game::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        if (gameIntent == null) {
            gameIntent = Intent(parent, Game::class.java)
        }
        val activeAddress = computer.activeAddress
        gameIntent.putExtra(Game.EXTRA_HOST, activeAddress?.address)
        gameIntent.putExtra(Game.EXTRA_PORT, activeAddress?.port ?: NvHTTP.DEFAULT_HTTP_PORT)
        gameIntent.putExtra(Game.EXTRA_HTTPS_PORT, computer.httpsPort)
        gameIntent.putExtra(Game.EXTRA_APP_NAME, app.appName)
        gameIntent.putExtra(Game.EXTRA_APP_UUID, app.appUUID)
        gameIntent.putExtra(Game.EXTRA_APP_ID, app.appId)
        gameIntent.putExtra(Game.EXTRA_APP_HDR, app.isHdrSupported)
        gameIntent.putExtra(Game.EXTRA_UNIQUEID, managerBinder.uniqueId)
        gameIntent.putExtra(Game.EXTRA_PC_UUID, computer.uuid)
        gameIntent.putExtra(Game.EXTRA_PC_NAME, computer.name)
        gameIntent.putExtra(Game.EXTRA_VDISPLAY, withVDisplay)
        gameIntent.putStringArrayListExtra(
            Game.EXTRA_SERVER_COMMANDS,
            computer.serverCommands?.let { ArrayList(it) }
        )

        try {
            computer.serverCert?.let {
                gameIntent.putExtra(Game.EXTRA_SERVER_CERT, it.encoded)
            }
        } catch (e: CertificateEncodingException) {
            e.printStackTrace()
        }

        if (prefConfig.enableFullExDisplay && secondaryDisplay != null) {
            gameIntent.putExtra(Game.EXTRA_DISPLAY_ID, secondaryDisplay.displayId)
            return Intent(parent, ExternalDisplayControlActivity::class.java).apply {
                putExtra(ExternalDisplayControlActivity.EXTRA_LAUNCH_INTENT, gameIntent)
            }
        }

        return gameIntent
    }

    @JvmStatic
    fun doStart(
        parent: Activity,
        app: NvApp,
        computer: ComputerDetails,
        managerBinder: ComputerManagerService.ComputerManagerBinder,
        withVDisplay: Boolean
    ) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Dialog.displayDialog(
                parent,
                parent.getString(R.string.conn_error_title),
                parent.getString(R.string.pair_pc_offline),
                false
            )
            return
        }
        parent.startActivity(createStartIntent(parent, app, computer, managerBinder, withVDisplay))
    }

    @JvmStatic
    fun doNetworkTest(parent: Activity, computer: ComputerDetails? = null) {
        val target = computer?.activeAddress
            ?: computer?.localAddress
            ?: computer?.remoteAddress
            ?: computer?.manualAddress
        if (target == null) {
            Dialog.displayDialog(
                parent,
                parent.resources.getString(R.string.nettest_title_done),
                parent.resources.getString(R.string.nettest_text_inconclusive),
                false
            )
            return
        }
        Thread {
            val spinnerDialog = SpinnerDialog.displayDialog(
                parent,
                parent.resources.getString(R.string.nettest_title_waiting),
                parent.resources.getString(R.string.nettest_text_waiting),
                false
            )

            val ret = VibemisBridge.testClientConnectivity(
                target.address,
                target.port,
                VibemisBridge.VB_PORT_FLAG_TCP_47984 or VibemisBridge.VB_PORT_FLAG_TCP_47989
            )
            spinnerDialog.dismiss()

            val dialogSummary = when (ret) {
                VibemisBridge.VB_TEST_RESULT_INCONCLUSIVE -> parent.resources.getString(R.string.nettest_text_inconclusive)
                0 -> parent.resources.getString(R.string.nettest_text_success)
                else -> parent.resources.getString(R.string.nettest_text_failure) + VibemisBridge.stringifyPortFlags(ret, "\n")
            }

            Dialog.displayDialog(
                parent,
                parent.resources.getString(R.string.nettest_title_done),
                dialogSummary,
                false
            )
        }.start()
    }

    @JvmStatic
    fun doQuit(
        parent: Activity,
        httpConn: NvHTTP,
        appName: String?,
        onComplete: Runnable?,
        onFail: Runnable?
    ) {
        Thread {
            var message: String? = null
            var techDetails: String? = null
            var failed = false
            try {
                if (!httpConn.quitApp()) {
                    failed = true
                    message = "${parent.resources.getString(R.string.applist_quit_fail)} $appName"
                }
            } catch (e: HostHttpResponseException) {
                failed = true
                message = if (e.errorCode == 599) {
                    "This session wasn't started by this device, so it cannot be quit. End streaming on the original device or the PC itself. (Error code: ${e.errorCode})"
                } else {
                    e.message
                }
                techDetails = "HTTP ${e.errorCode}: ${e.message}"
            } catch (e: UnknownHostException) {
                failed = true
                message = parent.resources.getString(R.string.error_unknown_host)
                techDetails = "UnknownHostException: ${e.message}"
            } catch (e: FileNotFoundException) {
                failed = true
                message = parent.resources.getString(R.string.error_404)
                techDetails = "FileNotFoundException: ${e.message}"
            } catch (e: IOException) {
                failed = true
                message = e.message
                techDetails = "IOException: ${e.message}"
                e.printStackTrace()
            } catch (e: XmlPullParserException) {
                failed = true
                message = e.message
                techDetails = "XmlPullParserException: ${e.message}"
                e.printStackTrace()
            } finally {
                if (failed) {
                    onFail?.run()
                } else {
                    onComplete?.run()
                }
            }

            if (failed && message != null) {
                Dialog.displayDetailedErrorDialog(
                    parent,
                    parent.resources.getString(R.string.conn_error_title),
                    message,
                    techDetails,
                    false
                )
            }
        }.start()
    }

    @JvmStatic
    fun doQuit(
        parent: Activity,
        computer: ComputerDetails,
        app: NvApp,
        managerBinder: ComputerManagerService.ComputerManagerBinder,
        onComplete: Runnable?
    ) {
        try {
            val httpConn = NvHTTP(
                getCurrentAddressFromComputer(computer),
                computer.httpsPort,
                managerBinder.uniqueId,
                computer.serverCert,
                PlatformBinding.getCryptoProvider(parent)
            )
            doQuit(parent, httpConn, app.appName, onComplete, null)
        } catch (e: Exception) {
            e.printStackTrace()
            Dialog.displayDetailedErrorDialog(
                parent,
                parent.resources.getString(R.string.conn_error_title),
                e.message ?: parent.resources.getString(R.string.applist_quit_fail),
                "${e.javaClass.simpleName}: ${e.message}",
                false
            )
        }
    }
}
