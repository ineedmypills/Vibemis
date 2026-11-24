package com.ineedmypills.vibemis

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.ineedmypills.vibemis.computers.ComputerDatabaseManager
import com.ineedmypills.vibemis.computers.ComputerManagerListener
import com.ineedmypills.vibemis.computers.ComputerManagerService
import com.ineedmypills.vibemis.nvstream.http.ComputerDetails
import com.ineedmypills.vibemis.nvstream.http.NvApp
import com.ineedmypills.vibemis.nvstream.http.NvHTTP
import com.ineedmypills.vibemis.nvstream.http.PairingManager
import com.ineedmypills.vibemis.nvstream.wol.WakeOnLanSender
import com.ineedmypills.vibemis.preferences.PreferenceConfiguration
import com.ineedmypills.vibemis.utils.CacheHelper
import com.ineedmypills.vibemis.utils.Dialog
import com.ineedmypills.vibemis.utils.ServerHelper
import com.ineedmypills.vibemis.utils.ShortcutHelper
import com.ineedmypills.vibemis.utils.SpinnerDialog
import com.ineedmypills.vibemis.utils.UiHelper
import org.xmlpull.v1.XmlPullParserException
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.StringReader
import java.util.UUID

class ShortcutTrampoline : AppCompatActivity() {
    private lateinit var prefConfig: PreferenceConfiguration
    private var uuidString: String? = null
    private var app: NvApp? = null
    private val intentStack = ArrayList<Intent>()

    private var wakeHostTries = 10
    private var computer: ComputerDetails? = null
    private var blockingLoadSpinner: SpinnerDialog? = null
    private var managerBinder: ComputerManagerService.ComputerManagerBinder? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            val localBinder = binder as ComputerManagerService.ComputerManagerBinder
            Thread {
                localBinder.waitForReady()
                managerBinder = localBinder
                val comp = localBinder.getComputer(uuidString)
                computer = comp

                if (comp == null) {
                    Dialog.displayDialog(
                        this@ShortcutTrampoline,
                        resources.getString(R.string.conn_error_title),
                        resources.getString(R.string.scut_pc_not_found),
                        true
                    )
                    blockingLoadSpinner?.dismiss()
                    blockingLoadSpinner = null
                    if (managerBinder != null) {
                        unbindService(this)
                        managerBinder = null
                    }
                    return@Thread
                }

                localBinder.invalidateStateForComputer(comp.uuid)
                localBinder.startPolling(ComputerManagerListener { details ->
                    if (!details.uuid.equals(uuidString, ignoreCase = true)) {
                        return@ComputerManagerListener
                    }

                    if (details.state != ComputerDetails.State.ONLINE &&
                        details.macAddress != null &&
                        --wakeHostTries >= 0
                    ) {
                        try {
                            WakeOnLanSender.sendWolPacket(comp)
                            localBinder.invalidateStateForComputer(comp.uuid)
                            return@ComputerManagerListener
                        } catch (_: IOException) {
                        }
                    }

                    if (details.state != ComputerDetails.State.UNKNOWN) {
                        runOnUiThread {
                            blockingLoadSpinner?.dismiss()
                            blockingLoadSpinner = null

                            val activeBinder = managerBinder
                            if (activeBinder == null) {
                                finish()
                                return@runOnUiThread
                            }

                            if (details.state == ComputerDetails.State.ONLINE &&
                                details.pairState == PairingManager.PairState.PAIRED
                            ) {
                                val targetApp = app
                                if (targetApp != null) {
                                    if (details.runningGameId == 0 ||
                                        details.runningGameId == targetApp.appId ||
                                        details.runningGameUUID == targetApp.appUUID
                                    ) {
                                        intentStack.add(
                                            ServerHelper.createStartIntent(
                                                this@ShortcutTrampoline,
                                                targetApp,
                                                details,
                                                activeBinder,
                                                prefConfig.useVirtualDisplay
                                            )
                                        )
                                        finish()
                                        startActivities(intentStack.toTypedArray())
                                    } else {
                                        val startIntent = ServerHelper.createStartIntent(
                                            this@ShortcutTrampoline,
                                            targetApp,
                                            details,
                                            activeBinder,
                                            prefConfig.useVirtualDisplay
                                        )
                                        UiHelper.displayQuitConfirmationDialog(
                                            this@ShortcutTrampoline,
                                            {
                                                intentStack.add(startIntent)
                                                finish()
                                                startActivities(intentStack.toTypedArray())
                                            },
                                            { finish() }
                                        )
                                    }
                                } else {
                                    finish()
                                    val pcIntent = Intent(this@ShortcutTrampoline, PcView::class.java).apply {
                                        action = Intent.ACTION_MAIN
                                        flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    intentStack.add(pcIntent)

                                    val appViewIntent = Intent(intent).apply {
                                        setClass(this@ShortcutTrampoline, AppView::class.java)
                                    }
                                    intentStack.add(appViewIntent)

                                    if (details.runningGameId != 0) {
                                        intentStack.add(
                                            ServerHelper.createStartIntent(
                                                this@ShortcutTrampoline,
                                                NvApp(null, null, details.runningGameId, false),
                                                details,
                                                activeBinder,
                                                prefConfig.useVirtualDisplay
                                            )
                                        )
                                    }
                                    startActivities(intentStack.toTypedArray())
                                }
                            } else if (details.state == ComputerDetails.State.OFFLINE) {
                                Dialog.displayDialog(
                                    this@ShortcutTrampoline,
                                    resources.getString(R.string.conn_error_title),
                                    resources.getString(R.string.error_pc_offline),
                                    true
                                )
                            } else if (details.pairState != PairingManager.PairState.PAIRED) {
                                Dialog.displayDialog(
                                    this@ShortcutTrampoline,
                                    resources.getString(R.string.conn_error_title),
                                    resources.getString(R.string.scut_not_paired),
                                    true
                                )
                            }

                            managerBinder?.let {
                                it.stopPolling()
                                unbindService(this)
                                managerBinder = null
                            }
                        }
                    }
                })
            }.start()
        }

        override fun onServiceDisconnected(className: ComponentName) {
            managerBinder = null
        }
    }

    protected fun validateHostInput(hostUUID: String?, hostName: String?): Boolean {
        if (hostUUID == null && hostName == null) {
            Dialog.displayDialog(
                this,
                resources.getString(R.string.conn_error_title),
                resources.getString(R.string.scut_invalid_uuid),
                true
            )
            return false
        }

        if (!hostUUID.isNullOrEmpty()) {
            try {
                UUID.fromString(hostUUID)
            } catch (_: IllegalArgumentException) {
                Dialog.displayDialog(
                    this,
                    resources.getString(R.string.conn_error_title),
                    resources.getString(R.string.scut_invalid_uuid),
                    true
                )
                return false
            }
        } else if (hostName.isNullOrEmpty()) {
            Dialog.displayDialog(
                this,
                resources.getString(R.string.conn_error_title),
                resources.getString(R.string.scut_invalid_uuid),
                true
            )
            return false
        }
        return true
    }

    protected fun validateAppInput(appUUID: String?, appIDStr: String?, appName: String?): Boolean {
        if (appUUID == null && appIDStr == null && appName == null) {
            return false
        }
        if (!appUUID.isNullOrEmpty()) {
            try {
                UUID.fromString(appUUID)
            } catch (_: IllegalArgumentException) {
                Dialog.displayDialog(
                    this,
                    resources.getString(R.string.conn_error_title),
                    resources.getString(R.string.scut_invalid_app_id),
                    true
                )
                return false
            }
        } else if (!appIDStr.isNullOrEmpty()) {
            try {
                appIDStr.toInt()
            } catch (_: NumberFormatException) {
                Dialog.displayDialog(
                    this,
                    resources.getString(R.string.conn_error_title),
                    resources.getString(R.string.scut_invalid_app_id),
                    true
                )
                return false
            }
        }
        return true
    }

    private fun parseLauncherFileData(fileUri: Uri?): Map<String, String>? {
        if (fileUri == null) return null
        val launcherData = HashMap<String, String>()
        return try {
            val stream = contentResolver.openInputStream(fileUri)
                ?: throw IOException("Unable to open .vbm stream")
            stream.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                    var totalChars = 0
                    var lineCount = 0
                    while (true) {
                        val currentLine = reader.readLine() ?: break
                        lineCount++
                        totalChars += currentLine.length
                        if (lineCount > MAX_LAUNCHER_LINES || totalChars > MAX_LAUNCHER_FILE_CHARS) {
                            throw IOException(".vbm file exceeds maximum allowed size")
                        }
                        val trimmed = currentLine.trim()
                        if (trimmed.startsWith("#") || trimmed.isEmpty()) continue
                        if (!trimmed.startsWith("[")) {
                            throw IOException("Invalid .vbm file format")
                        }
                        val sep = trimmed.indexOf(' ')
                        if (sep in 1 until (trimmed.length - 1)) {
                            var key = trimmed.substring(0, sep).trim()
                            val value = trimmed.substring(sep + 1).trim()
                            if (key.endsWith("]")) {
                                key = key.substring(1, key.length - 1)
                                launcherData[key] = value
                            } else {
                                throw IOException("Invalid .vbm file format")
                            }
                        }
                    }
                }
            }
            launcherData
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission error reading .vbm file", e)
            Dialog.displayDialog(
                this,
                resources.getString(R.string.conn_error_title),
                resources.getString(R.string.scut_invalid_uuid),
                true
            )
            null
        } catch (e: IOException) {
            Log.e(TAG, "Error reading .vbm file", e)
            Dialog.displayDialog(
                this,
                resources.getString(R.string.conn_error_title),
                resources.getString(R.string.conn_error_msg),
                true
            )
            null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefConfig = PreferenceConfiguration.readPreferences(this)
        UiHelper.notifyNewRootView(this)
        val dbManager = ComputerDatabaseManager(this)

        val action = intent.action
        val dataUri = intent.data

        var hostUUID: String? = null
        var hostName: String? = null
        var appName: String? = null
        var appUUID: String? = null
        var appIDStr: String? = null

        if (Intent.ACTION_VIEW == action && dataUri != null) {
            val launcherData = parseLauncherFileData(dataUri) ?: return
            hostUUID = launcherData[ShortcutHelper.KEY_HOST_UUID]
            hostName = launcherData[ShortcutHelper.KEY_HOST_NAME]
            appName = launcherData[ShortcutHelper.KEY_APP_NAME]
            appUUID = launcherData[ShortcutHelper.KEY_APP_UUID]
            appIDStr = launcherData[ShortcutHelper.KEY_APP_ID]
        }

        if (hostUUID == null) hostUUID = intent.getStringExtra(AppView.UUID_EXTRA)
        if (hostName == null) hostName = intent.getStringExtra(AppView.NAME_EXTRA)
        if (appUUID == null) appUUID = intent.getStringExtra(Game.EXTRA_APP_UUID)
        if (appIDStr == null) appIDStr = intent.getStringExtra(Game.EXTRA_APP_ID)
        if (appName == null) appName = intent.getStringExtra(Game.EXTRA_APP_NAME)

        if (!validateHostInput(hostUUID, hostName)) {
            return
        }

        if (hostUUID.isNullOrEmpty()) {
            val foundComputer = dbManager.getComputerByName(hostName)
            if (foundComputer == null) {
                Dialog.displayDialog(
                    this,
                    resources.getString(R.string.conn_error_title),
                    resources.getString(R.string.scut_pc_not_found),
                    true
                )
                return
            }
            hostUUID = foundComputer.uuid
        }

        uuidString = hostUUID
        setIntent(Intent(intent).putExtra(AppView.UUID_EXTRA, uuidString))

        if (validateAppInput(appUUID, appIDStr, appName)) {
            val hdr = intent.getBooleanExtra(Game.EXTRA_APP_HDR, false)
            when {
                !appUUID.isNullOrEmpty() -> {
                    app = NvApp(appName, appUUID, -1, hdr)
                }
                !appIDStr.isNullOrEmpty() -> {
                    app = NvApp(appName, null, appIDStr.toInt(), hdr)
                }
                !appName.isNullOrEmpty() -> {
                    try {
                        var appID = -1
                        var appUuidFromFile: String? = null
                        val rawAppList = CacheHelper.readInputStreamToString(
                            CacheHelper.openCacheFileForInput(cacheDir, "applist", uuidString)
                        )
                        if (rawAppList.isEmpty()) {
                            Dialog.displayDialog(
                                this,
                                resources.getString(R.string.conn_error_title),
                                "${resources.getString(R.string.scut_invalid_app_id)} (applist cache empty or unreadable)",
                                true
                            )
                            return
                        }
                        val applist = NvHTTP.getAppListByReader(StringReader(rawAppList))
                        for (candidate in applist) {
                            if (candidate.appName.equals(appName, ignoreCase = true)) {
                                appID = candidate.appId
                                appUuidFromFile = candidate.appUUID
                                break
                            }
                        }
                        if (appID < 0 && appUuidFromFile == null) {
                            Dialog.displayDialog(
                                this,
                                resources.getString(R.string.conn_error_title),
                                "${resources.getString(R.string.scut_invalid_app_id)} (app not found in cache)",
                                true
                            )
                            return
                        }
                        if (intent.getStringExtra(Game.EXTRA_APP_ID) == null && appID != -1) {
                            intent.putExtra(Game.EXTRA_APP_ID, appID.toString())
                        }
                        if (intent.getStringExtra(Game.EXTRA_APP_UUID) == null && appUuidFromFile != null) {
                            intent.putExtra(Game.EXTRA_APP_UUID, appUuidFromFile)
                        }
                        app = NvApp(appName, appUuidFromFile, appID, hdr)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing app list from cache", e)
                        Dialog.displayDialog(
                            this,
                            resources.getString(R.string.conn_error_title),
                            "${resources.getString(R.string.scut_invalid_app_id)} (error parsing applist cache)",
                            true
                        )
                        return
                    }
                }
            }
        }

        bindService(
            Intent(this, ComputerManagerService::class.java),
            serviceConnection,
            Service.BIND_AUTO_CREATE
        )

        blockingLoadSpinner = SpinnerDialog.displayDialog(
            this,
            resources.getString(R.string.conn_establishing_title),
            resources.getString(R.string.applist_connect_msg),
            true
        )
    }

    override fun onStop() {
        super.onStop()
        blockingLoadSpinner?.dismiss()
        blockingLoadSpinner = null
        Dialog.closeDialogs()
        managerBinder?.let {
            it.stopPolling()
            unbindService(serviceConnection)
            managerBinder = null
        }
        finish()
    }

    companion object {
        private const val TAG = "ShortcutTrampoline"
        private const val MAX_LAUNCHER_LINES = 64
        private const val MAX_LAUNCHER_FILE_CHARS = 32 * 1024
    }
}
