package com.ineedmypills.vibemis.utils

import android.net.TrafficStats

object TrafficStatsHelper {
    @JvmStatic
    val allRxBytes: Long
        get() = TrafficStats.getTotalRxBytes()

    @JvmStatic
    val allTxBytes: Long
        get() = TrafficStats.getTotalTxBytes()

    @JvmStatic
    val allRxBytesMobile: Long
        get() = TrafficStats.getMobileRxBytes()

    @JvmStatic
    val allTxBytesMobile: Long
        get() = TrafficStats.getMobileTxBytes()

    @JvmStatic
    val allRxBytesWifi: Long
        get() = TrafficStats.getTotalRxBytes() - TrafficStats.getMobileRxBytes()

    @JvmStatic
    val allTxBytesWifi: Long
        get() = TrafficStats.getTotalTxBytes() - TrafficStats.getMobileTxBytes()

    @JvmStatic
    fun getPackageRxBytes(uid: Int): Long = TrafficStats.getUidRxBytes(uid)

    @JvmStatic
    fun getPackageTxBytes(uid: Int): Long = TrafficStats.getUidTxBytes(uid)
}
