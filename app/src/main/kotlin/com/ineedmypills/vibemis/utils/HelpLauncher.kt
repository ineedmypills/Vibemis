package com.ineedmypills.vibemis.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.ineedmypills.vibemis.HelpActivity

object HelpLauncher {
    @JvmStatic
    fun launchUrl(context: Context, url: String) {
        var resolvedUrl = url
        if (resolvedUrl.startsWith("@")) {
            try {
                val resId = resolvedUrl.substring(1).toInt()
                resolvedUrl = context.getString(resId)
            } catch (_: Exception) {
            }
        }
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(resolvedUrl))
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
                context.startActivity(i)
                return
            }
        } catch (_: Exception) {
        }

        val i = Intent(context, HelpActivity::class.java)
        i.data = Uri.parse(resolvedUrl)
        context.startActivity(i)
    }

    @JvmStatic
    fun launchSetupGuide(context: Context) {
        launchUrl(context, "https://github.com/Nonary/Vibepollo#readme")
    }

    @JvmStatic
    fun launchTroubleshooting(context: Context) {
        launchUrl(context, "https://docs.lizardbyte.dev/projects/sunshine/latest/md_docs_2troubleshooting.html")
    }
}
