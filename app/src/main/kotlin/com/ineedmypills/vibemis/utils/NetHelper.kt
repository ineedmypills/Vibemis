package com.ineedmypills.vibemis.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object NetHelper {
    @JvmStatic
    fun isActiveNetworkVpn(context: Context): Boolean {
        val connMgr = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connMgr.activeNetwork
        if (activeNetwork != null) {
            val netCaps = connMgr.getNetworkCapabilities(activeNetwork)
            if (netCaps != null) {
                return netCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                        !netCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            }
        }
        return false
    }
}
