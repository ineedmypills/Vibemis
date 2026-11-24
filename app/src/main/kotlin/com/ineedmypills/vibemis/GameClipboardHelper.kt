package com.ineedmypills.vibemis

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.PersistableBundle
import com.ineedmypills.vibemis.nvstream.http.NvHTTP
import com.ineedmypills.vibemis.preferences.PreferenceConfiguration

internal class GameClipboardHelper(
    private val activity: Activity,
    private val clipboardManager: ClipboardManager
) {
    private fun notifyClipboardStatus(message: String) {
        (activity as? Game)?.showTransientOverlayMessage(message)
    }
    @Volatile
    private var clipboardSyncRunning = false

    fun getClipboardContent(force: Boolean): String? {
        if (!clipboardManager.hasPrimaryClip()) return null
        val clipDescription = clipboardManager.primaryClipDescription
        if (!force && clipDescription != null) {
            val extras = clipDescription.extras
            if (extras != null && extras.getBoolean(Game.CLIPBOARD_IDENTIFIER)) {
                return null
            }
        }

        val clipData = clipboardManager.primaryClip ?: return null
        if (clipData.itemCount <= 0) return null
        val item = clipData.getItemAt(0)

        if (clipDescription != null) {
            val clonedClip = cloneClipData(clipDescription, item)
            clipboardManager.setPrimaryClip(clonedClip)
        }

        val clipText = item.text ?: return null
        return clipText.toString()
    }

    private fun cloneClipData(clipDescription: ClipDescription, item: ClipData.Item): ClipData {
        val clonedDescription = ClipDescription(clipDescription)
        val extras = clipDescription.extras ?: PersistableBundle()
        extras.putBoolean(Game.CLIPBOARD_IDENTIFIER, true)
        clonedDescription.extras = extras
        return ClipData(clonedDescription, item)
    }

    fun sendClipboard(
        httpConn: NvHTTP?,
        prefConfig: PreferenceConfiguration,
        force: Boolean
    ): Boolean {
        if (httpConn == null) {
            VibemisLog.warning("httpConn not ready, cannot send clipboard!")
            return false
        }

        val clipboardText = getClipboardContent(force) ?: return false
        Thread {
            try {
                if (!httpConn.sendClipboard(clipboardText)) {
                    if (prefConfig.smartClipboardSyncToast) {
                        notifyClipboardStatus(activity.getString(R.string.clipboard_sync_unsupported))
                    }
                } else {
                    if (prefConfig.smartClipboardSyncToast) {
                        notifyClipboardStatus(activity.getString(R.string.send_clipboard_success))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (prefConfig.smartClipboardSyncToast) {
                    notifyClipboardStatus(activity.getString(R.string.send_clipboard_failed) + e.message)
                }
            }
        }.start()

        return true
    }

    fun getClipboard(
        httpConn: NvHTTP?,
        prefConfig: PreferenceConfiguration,
        isMenuOpen: Boolean,
        delay: Int
    ): Boolean {
        if (httpConn == null) {
            VibemisLog.warning("httpConn not ready, cannot get clipboard!")
            return false
        }
        if (delay == 0 && isMenuOpen) {
            return false
        }

        Thread {
            if (clipboardSyncRunning) return@Thread
            clipboardSyncRunning = true
            try {
                if (delay > 0) {
                    Thread.sleep(delay.toLong())
                }
                val clipboardContent = httpConn.getClipboard()
                val clipData = ClipData.newPlainText(Game.CLIPBOARD_IDENTIFIER, clipboardContent)
                val clipDescription = clipData.description
                val newExtras = PersistableBundle()
                newExtras.putBoolean(Game.CLIPBOARD_IDENTIFIER, true)
                if (prefConfig.hideClipboardContent) {
                    newExtras.putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
                clipDescription.extras = newExtras

                clipboardManager.setPrimaryClip(clipData)
                if (prefConfig.smartClipboardSyncToast) {
                    notifyClipboardStatus(activity.getString(R.string.get_clipboard_success))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (prefConfig.smartClipboardSyncToast) {
                    notifyClipboardStatus(activity.getString(R.string.get_clipboard_failed) + e.message)
                }
            } finally {
                clipboardSyncRunning = false
            }
        }.start()

        return true
    }
}
