package com.ineedmypills.vibemis.discovery

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.ineedmypills.vibemis.nvstream.mdns.MdnsComputer
import com.ineedmypills.vibemis.nvstream.mdns.MdnsDiscoveryAgent
import com.ineedmypills.vibemis.nvstream.mdns.MdnsDiscoveryListener
import com.ineedmypills.vibemis.nvstream.mdns.NsdManagerDiscoveryAgent
import com.ineedmypills.vibemis.nvstream.mdns.RustMdnsDiscoveryAgent

class DiscoveryService : Service() {
    private lateinit var discoveryAgent: MdnsDiscoveryAgent
    private var boundListener: MdnsDiscoveryListener? = null
    private val binder = DiscoveryBinder()

    inner class DiscoveryBinder : Binder() {
        fun setListener(listener: MdnsDiscoveryListener?) {
            boundListener = listener
        }

        fun startDiscovery(queryIntervalMs: Int) {
            discoveryAgent.startDiscovery(queryIntervalMs)
        }

        fun stopDiscovery() {
            discoveryAgent.stopDiscovery()
        }

        val computerSet: List<MdnsComputer>
            get() = discoveryAgent.computerSet
    }

    override fun onCreate() {
        val listener = object : MdnsDiscoveryListener {
            override fun notifyComputerAdded(computer: MdnsComputer) {
                boundListener?.notifyComputerAdded(computer)
            }

            override fun notifyDiscoveryFailure(e: Exception) {
                boundListener?.notifyDiscoveryFailure(e)
            }
        }

        discoveryAgent = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            RustMdnsDiscoveryAgent(applicationContext, listener)
        } else {
            NsdManagerDiscoveryAgent(applicationContext, listener)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        discoveryAgent.stopDiscovery()
        boundListener = null
        return false
    }
}
