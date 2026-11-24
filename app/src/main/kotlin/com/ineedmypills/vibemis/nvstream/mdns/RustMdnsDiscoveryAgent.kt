package com.ineedmypills.vibemis.nvstream.mdns

import android.content.Context
import android.net.wifi.WifiManager
import com.ineedmypills.vibemis.VibemisLog
import com.ineedmypills.vibemis.nvstream.jni.VibemisBridge
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Pure-Rust UDP multicast mDNS discovery agent (for Android API < 34), replacing JmDNS + SLF4J.
 */
class RustMdnsDiscoveryAgent(
    context: Context,
    listener: MdnsDiscoveryListener
) : MdnsDiscoveryAgent(listener) {

    private val multicastLock: WifiManager.MulticastLock? = run {
        val wifiMgr = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiMgr?.createMulticastLock("Vibemis Rust mDNS")?.apply {
            setReferenceCounted(false)
        }
    }

    @Volatile
    private var pollThread: Thread? = null

    @Volatile
    private var running = false

    override fun startDiscovery(discoveryIntervalMs: Int) {
        stopDiscovery()
        try {
            multicastLock?.acquire()
        } catch (e: SecurityException) {
            VibemisLog.warning("MulticastLock acquire failed: ${e.message}")
        }

        val startedNative = try {
            VibemisBridge.mdnsStartDiscovery(discoveryIntervalMs)
        } catch (e: UnsatisfiedLinkError) {
            listener.notifyDiscoveryFailure(RuntimeException("Rust mDNS native library unavailable", e))
            releaseMulticastLock()
            return
        }

        if (!startedNative) {
            listener.notifyDiscoveryFailure(RuntimeException("Failed to start Rust mDNS discovery"))
            releaseMulticastLock()
            return
        }

        running = true
        pollThread = Thread({
            try {
                while (running && !Thread.currentThread().isInterrupted) {
                    val batch = try {
                        VibemisBridge.mdnsDrainDiscoveredHosts()
                    } catch (_: UnsatisfiedLinkError) {
                        null
                    }
                    if (!batch.isNullOrEmpty()) {
                        parseAndReportBatch(batch)
                    }
                    try {
                        Thread.sleep(300L)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            } finally {
                running = false
            }
        }, "Rust mDNS Poller").also { it.start() }
    }

    override fun stopDiscovery() {
        running = false
        pollThread?.interrupt()
        pollThread = null
        try {
            VibemisBridge.mdnsStopDiscovery()
        } catch (_: UnsatisfiedLinkError) {
        }
        releaseMulticastLock()
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock.release()
            }
        } catch (_: Throwable) {
        }
    }

    private fun parseAndReportBatch(encoded: String) {
        for (line in encoded.lineSequence()) {
            if (line.isBlank()) continue
            val parts = line.split('\t')
            if (parts.size < 4) continue
            val name = parts[0]
            val port = parts[1].toIntOrNull() ?: continue
            val v4List = ArrayList<Inet4Address>()
            val v6List = ArrayList<Inet6Address>()

            if (parts[2].isNotEmpty()) {
                for (ipStr in parts[2].split(',')) {
                    try {
                        val addr = InetAddress.getByName(ipStr)
                        if (addr is Inet4Address) v4List.add(addr)
                    } catch (_: Exception) {
                    }
                }
            }
            if (parts[3].isNotEmpty()) {
                for (ipStr in parts[3].split(',')) {
                    try {
                        val addr = InetAddress.getByName(ipStr)
                        if (addr is Inet6Address) v6List.add(addr)
                    } catch (_: Exception) {
                    }
                }
            }

            if (v4List.isNotEmpty() || v6List.isNotEmpty()) {
                reportNewComputer(name, port, v4List.toTypedArray(), v6List.toTypedArray())
            }
        }
    }
}
