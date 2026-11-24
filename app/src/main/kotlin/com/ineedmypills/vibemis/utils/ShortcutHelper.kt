package com.ineedmypills.vibemis.utils

import android.app.Activity
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import com.ineedmypills.vibemis.VibemisLog
import com.ineedmypills.vibemis.R
import com.ineedmypills.vibemis.nvstream.http.ComputerDetails
import com.ineedmypills.vibemis.nvstream.http.NvApp
import java.io.IOException
import java.util.LinkedList

class ShortcutHelper(private val context: Activity) {
    private val sm: ShortcutManager? = context.getSystemService(ShortcutManager::class.java)
    private val tvChannelHelper = TvChannelHelper(context)

    private fun reapShortcutsForDynamicAdd() {
        val manager = sm ?: return
        val dynamicShortcuts = manager.dynamicShortcuts
        while (dynamicShortcuts.isNotEmpty() && dynamicShortcuts.size >= manager.maxShortcutCountPerActivity) {
            var maxRankShortcut = dynamicShortcuts[0]
            for (scut in dynamicShortcuts) {
                if (maxRankShortcut.rank < scut.rank) {
                    maxRankShortcut = scut
                }
            }
            manager.removeDynamicShortcuts(listOf(maxRankShortcut.id))
        }
    }

    private fun getAllShortcuts(): List<ShortcutInfo> {
        val manager = sm ?: return emptyList()
        val list = LinkedList<ShortcutInfo>()
        list.addAll(manager.dynamicShortcuts)
        list.addAll(manager.pinnedShortcuts)
        return list
    }

    private fun getInfoForId(id: String?): ShortcutInfo? {
        if (id == null) return null
        return getAllShortcuts().firstOrNull { it.id == id }
    }

    private fun isExistingDynamicShortcut(id: String?): Boolean {
        val manager = sm ?: return false
        return manager.dynamicShortcuts.any { it.id == id }
    }

    fun reportComputerShortcutUsed(computer: ComputerDetails) {
        val uuid = computer.uuid
        if (getInfoForId(uuid) != null) {
            sm?.reportShortcutUsed(uuid)
        }
    }

    fun reportGameLaunched(computer: ComputerDetails, app: NvApp) {
        tvChannelHelper.createTvChannel(computer)
        tvChannelHelper.addGameToChannel(computer, app)
    }

    fun createAppViewShortcut(computer: ComputerDetails, forceAdd: Boolean, newlyPaired: Boolean) {
        val manager = sm
        val uuid = computer.uuid
        if (manager != null) {
            val sinfo = ShortcutInfo.Builder(context, uuid)
                .setIntent(ServerHelper.createPcShortcutIntent(context, computer))
                .setShortLabel(computer.name)
                .setLongLabel(computer.name)
                .setIcon(Icon.createWithResource(context, R.mipmap.ic_pc_scut))
                .build()

            val existingSinfo = getInfoForId(uuid)
            if (existingSinfo != null) {
                manager.updateShortcuts(listOf(sinfo))
                manager.enableShortcuts(listOf(uuid))
            }

            if (!isExistingDynamicShortcut(uuid)) {
                if (forceAdd) {
                    reapShortcutsForDynamicAdd()
                }
                if (manager.dynamicShortcuts.size < manager.maxShortcutCountPerActivity) {
                    manager.addDynamicShortcuts(listOf(sinfo))
                }
            }
        }

        if (newlyPaired) {
            tvChannelHelper.createTvChannel(computer)
            tvChannelHelper.requestChannelOnHomeScreen(computer)
        }
    }

    fun createAppViewShortcutForOnlineHost(details: ComputerDetails) {
        createAppViewShortcut(details, forceAdd = false, newlyPaired = false)
    }

    private fun getShortcutIdForGame(computer: ComputerDetails, app: NvApp): String =
        "${computer.uuid}${app.appId}"

    fun createPinnedGameShortcut(computer: ComputerDetails, app: NvApp, iconBits: Bitmap?): Boolean {
        val manager = sm ?: return false
        if (manager.isRequestPinShortcutSupported) {
            val appIcon = if (iconBits != null) {
                Icon.createWithAdaptiveBitmap(iconBits)
            } else {
                Icon.createWithResource(context, R.mipmap.ic_pc_scut)
            }

            val sInfo = ShortcutInfo.Builder(context, getShortcutIdForGame(computer, app))
                .setIntent(ServerHelper.createAppShortcutIntent(context, computer, app))
                .setShortLabel("${app.appName} (${computer.name})")
                .setIcon(appIcon)
                .build()

            return manager.requestPinShortcut(sInfo, null)
        }
        return false
    }

    fun disableComputerShortcut(computer: ComputerDetails, reason: CharSequence) {
        tvChannelHelper.deleteChannel(computer)
        val manager = sm ?: return
        val uuid = computer.uuid
        if (getInfoForId(uuid) != null) {
            manager.disableShortcuts(listOf(uuid), reason)
        }

        val appShortcutIds = getAllShortcuts()
            .filter { it.id.startsWith(uuid) }
            .map { it.id }
        manager.disableShortcuts(appShortcutIds, reason)
    }

    fun disableAppShortcut(computer: ComputerDetails, app: NvApp, reason: CharSequence) {
        tvChannelHelper.deleteProgram(computer, app)
        val manager = sm ?: return
        val id = getShortcutIdForGame(computer, app)
        if (getInfoForId(id) != null) {
            manager.disableShortcuts(listOf(id), reason)
        }
    }

    fun enableAppShortcut(computer: ComputerDetails, app: NvApp) {
        val manager = sm ?: return
        val id = getShortcutIdForGame(computer, app)
        if (getInfoForId(id) != null) {
            manager.enableShortcuts(listOf(id))
        }
    }

    @Suppress("DEPRECATION")
    fun exportLauncherFile(computer: ComputerDetails?, app: NvApp?) {
        if (computer == null || computer.uuid.isNullOrEmpty() || computer.name.isNullOrEmpty()) {
            Dialog.displayDialog(context, "Export", context.getString(R.string.export_launcher_computer_details_incomplete), false)
            VibemisLog.warning("exportLauncherFile: Computer details incomplete.")
            return
        }

        if (app == null || app.appName.isNullOrEmpty() || app.appUUID.isNullOrEmpty()) {
            Dialog.displayDialog(context, "Export", context.getString(R.string.export_launcher_app_details_incomplete), false)
            VibemisLog.warning("exportLauncherFile: App details incomplete.")
            return
        }

        val sb = StringBuilder()
        sb.append("# Vibemis app entry\n")
        sb.append("# Generated by Vibemis for Android\n\n")
        sb.append("[$KEY_HOST_UUID] ${computer.uuid}\n")
        sb.append("[$KEY_HOST_NAME] ${computer.name}\n")

        if (!app.appUUID.isNullOrEmpty()) {
            sb.append("[$KEY_APP_UUID] ${app.appUUID}\n")
        }
        if (!app.appName.isNullOrEmpty()) {
            sb.append("[$KEY_APP_NAME] ${app.appName}\n")
        } else if (app.appId > 0) {
            sb.append("[$KEY_APP_ID] ${app.appId}\n")
        }

        launcherFileContentToExport = sb.toString()
        val fileName = "${app.appName.trim()}.vbm"

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, fileName)
        }

        try {
            context.startActivityForResult(intent, REQUEST_CODE_EXPORT_LAUNCHER_FILE)
        } catch (e: Exception) {
            VibemisLog.severe("Failed to start activity for file export: ${e.message}")
            Dialog.displayDialog(context, "Export", context.getString(R.string.failed_to_initiate_file_export, e.message), false)
            launcherFileContentToExport = null
        }
    }

    companion object {
        const val REQUEST_CODE_EXPORT_LAUNCHER_FILE = 778

        @JvmField
        var launcherFileContentToExport: String? = null

        const val KEY_HOST_UUID = "host_uuid"
        const val KEY_HOST_NAME = "host_name"
        const val KEY_APP_UUID = "app_uuid"
        const val KEY_APP_NAME = "app_name"
        const val KEY_APP_ID = "app_id"

        @JvmStatic
        fun writeLauncherFileToUri(activityContext: Activity, uri: Uri?) {
            if (uri == null) {
                VibemisLog.warning("writeLauncherFileToUri: URI is null.")
                Dialog.displayDialog(activityContext, "Export", activityContext.getString(R.string.file_export_failed_no_location_selected), false)
                launcherFileContentToExport = null
                return
            }

            val content = launcherFileContentToExport
            if (content.isNullOrEmpty()) {
                VibemisLog.warning("writeLauncherFileToUri: No content to export.")
                Dialog.displayDialog(activityContext, "Export", activityContext.getString(R.string.file_export_failed_no_content_to_write), false)
                return
            }

            try {
                activityContext.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(content.toByteArray())
                    outputStream.flush()
                    VibemisLog.info("Successfully wrote .vbm file to: $uri")
                } ?: run {
                    VibemisLog.severe("Failed to open output stream for URI: $uri")
                    Dialog.displayDialog(activityContext, "Export", activityContext.getString(R.string.failed_to_open_file_for_writing), false)
                }
            } catch (e: IOException) {
                VibemisLog.severe("Error writing .vbm file to URI: $uri - ${e.message}")
                Dialog.displayDialog(activityContext, "Export", activityContext.getString(R.string.error_writing_file, e.message), false)
            } catch (e: Exception) {
                VibemisLog.severe("Unexpected error writing .vbm file to URI: $uri - ${e.message}")
                Dialog.displayDialog(activityContext, "Export", activityContext.getString(R.string.unexpected_error_during_file_export), false)
            } finally {
                launcherFileContentToExport = null
            }
        }
    }
}
