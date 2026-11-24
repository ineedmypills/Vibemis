package com.ineedmypills.vibemis.nvstream.wol

import com.ineedmypills.vibemis.nvstream.http.ComputerDetails
import com.ineedmypills.vibemis.nvstream.jni.VibemisBridge
import java.io.IOException

object WakeOnLanSender {
    @JvmStatic
    @Throws(IOException::class)
    fun sendWolPacket(computer: ComputerDetails) {
        val macAddress = computer.macAddress
        if (macAddress.isNullOrBlank()) {
            throw IOException("Missing MAC address for Wake-on-LAN")
        }

        var sentAny = false
        for (address in arrayOf(
            computer.localAddress,
            computer.remoteAddress,
            computer.manualAddress,
            computer.ipv6Address
        )) {
            if (address == null) continue
            if (VibemisBridge.sendWolPacket(macAddress, address.address, address.port)) {
                sentAny = true
            }
        }

        if (!sentAny) {
            throw IOException("Failed to send Wake-on-LAN packet via Rust core")
        }
    }
}
