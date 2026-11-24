package com.ineedmypills.vibemis.nvstream.mdns

interface MdnsDiscoveryListener {
    fun notifyComputerAdded(computer: MdnsComputer)
    fun notifyDiscoveryFailure(e: Exception)
}
