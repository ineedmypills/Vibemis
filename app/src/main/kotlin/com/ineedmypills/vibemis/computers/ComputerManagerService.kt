@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package com.ineedmypills.vibemis.computers

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import com.ineedmypills.vibemis.VibemisLog
import com.ineedmypills.vibemis.binding.PlatformBinding
import com.ineedmypills.vibemis.discovery.DiscoveryService
import com.ineedmypills.vibemis.nvstream.NvConnection
import com.ineedmypills.vibemis.nvstream.http.ComputerDetails
import com.ineedmypills.vibemis.nvstream.http.NvHTTP
import com.ineedmypills.vibemis.nvstream.http.PairingManager
import com.ineedmypills.vibemis.nvstream.mdns.MdnsComputer
import com.ineedmypills.vibemis.nvstream.mdns.MdnsDiscoveryListener
import com.ineedmypills.vibemis.utils.CacheHelper
import com.ineedmypills.vibemis.utils.NetHelper
import com.ineedmypills.vibemis.utils.ServerHelper
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.InterruptedIOException
import java.io.StringReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.LinkedList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

class ComputerManagerService : Service() {
    private val binder = ComputerManagerBinder()

    private lateinit var dbManager: ComputerDatabaseManager
    private val dbRefCount = AtomicInteger(0)

    private lateinit var idManager: IdentityManager
    private val pollingTuples = LinkedList<PollingTuple>()
    private var listener: ComputerManagerListener? = null
    private val activePolls = AtomicInteger(0)
    private var pollingActive = false
    private val defaultNetworkLock = ReentrantLock()

    private lateinit var networkCallback: ConnectivityManager.NetworkCallback

    private var discoveryBinder: DiscoveryService.DiscoveryBinder? = null
    private val discoveryServiceConnectionLock = Object()
    private val discoveryServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            synchronized(discoveryServiceConnectionLock) {
                val privateBinder = binder as DiscoveryService.DiscoveryBinder
                privateBinder.setListener(createDiscoveryListener())
                discoveryBinder = privateBinder
                discoveryServiceConnectionLock.notifyAll()
            }
        }

        override fun onServiceDisconnected(className: ComponentName) {
            discoveryBinder = null
        }
    }

    @Throws(InterruptedException::class)
    private fun runPoll(details: ComputerDetails, newPc: Boolean, offlineCount: Int): Boolean {
        if (!getLocalDatabaseReference()) return false

        val pollTriesBeforeOffline =
            if (details.state == ComputerDetails.State.UNKNOWN) INITIAL_POLL_TRIES else OFFLINE_POLL_TRIES

        activePolls.incrementAndGet()

        try {
            if (!pollComputer(details)) {
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException()
                }
                if (!newPc && offlineCount < pollTriesBeforeOffline) {
                    releaseLocalDatabaseReference()
                    return false
                }
                details.state = ComputerDetails.State.OFFLINE
            }
        } catch (e: InterruptedException) {
            releaseLocalDatabaseReference()
            throw e
        } finally {
            activePolls.decrementAndGet()
        }

        if (details.state == ComputerDetails.State.ONLINE) {
            val existingComputer = dbManager.getComputerByUUID(details.uuid)
            if (!newPc && existingComputer == null) {
                releaseLocalDatabaseReference()
                return false
            }

            if (existingComputer != null) {
                existingComputer.update(details)
                dbManager.updateComputer(existingComputer)
            } else {
                try {
                    if (details.remoteAddress == null && details.activeAddress != null) {
                        val addr = InetAddress.getByName(details.activeAddress!!.address)
                        if (addr.isSiteLocalAddress) {
                            populateExternalAddress(details)
                        }
                    }
                } catch (_: UnknownHostException) {}

                dbManager.updateComputer(details)
            }
        }

        if (!newPc || details.state == ComputerDetails.State.ONLINE) {
            listener?.notifyComputerUpdated(details)
        }

        releaseLocalDatabaseReference()
        return true
    }

    private fun createPollingThread(tuple: PollingTuple): Thread {
        return object : Thread("Polling thread for ${tuple.computer.name}") {
            override fun run() {
                var offlineCount = 0
                while (!isInterrupted && pollingActive && tuple.thread === this) {
                    try {
                        synchronized(tuple.networkLock) {
                            if (!runPoll(tuple.computer, false, offlineCount)) {
                                VibemisLog.warning("${tuple.computer.name} is offline (try $offlineCount)")
                                offlineCount++
                            } else {
                                tuple.lastSuccessfulPollMs = SystemClock.elapsedRealtime()
                                offlineCount = 0
                            }
                        }
                        sleep(SERVERINFO_POLLING_PERIOD_MS.toLong())
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }
    }

    inner class ComputerManagerBinder : Binder() {
        fun startPolling(listener: ComputerManagerListener) {
            pollingActive = true
            this@ComputerManagerService.listener = listener
            discoveryBinder?.startDiscovery(MDNS_QUERY_PERIOD_MS)

            synchronized(pollingTuples) {
                for (tuple in pollingTuples) {
                    if (SystemClock.elapsedRealtime() - tuple.lastSuccessfulPollMs > POLL_DATA_TTL_MS) {
                        VibemisLog.info("Timing out polled state for ${tuple.computer.name}")
                        tuple.computer.state = ComputerDetails.State.UNKNOWN
                    }
                    listener.notifyComputerUpdated(tuple.computer)
                    if (tuple.thread == null) {
                        val t = createPollingThread(tuple)
                        tuple.thread = t
                        t.start()
                    }
                }
            }
        }

        fun waitForReady() {
            synchronized(discoveryServiceConnectionLock) {
                try {
                    while (discoveryBinder == null) {
                        discoveryServiceConnectionLock.wait(1000)
                    }
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                    Thread.currentThread().interrupt()
                }
            }
        }

        fun waitForPollingStopped() {
            while (activePolls.get() != 0) {
                try {
                    Thread.sleep(250)
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                    Thread.currentThread().interrupt()
                }
            }
        }

        @Throws(InterruptedException::class)
        fun addComputerBlocking(fakeDetails: ComputerDetails): Boolean {
            return this@ComputerManagerService.addComputerBlocking(fakeDetails)
        }

        fun removeComputer(computer: ComputerDetails) {
            this@ComputerManagerService.removeComputer(computer)
        }

        fun stopPolling() {
            this@ComputerManagerService.onUnbind(null)
        }

        fun createAppListPoller(computer: ComputerDetails): ApplistPoller {
            return ApplistPoller(computer)
        }

        val uniqueId: String
            get() = idManager.uniqueId

        fun getComputer(uuid: String?): ComputerDetails? {
            if (uuid == null) return null
            synchronized(pollingTuples) {
                return pollingTuples.firstOrNull { uuid == it.computer.uuid }?.computer
            }
        }

        fun invalidateStateForComputer(uuid: String?) {
            if (uuid == null) return
            synchronized(pollingTuples) {
                for (tuple in pollingTuples) {
                    if (uuid == tuple.computer.uuid) {
                        synchronized(tuple.networkLock) {
                            tuple.computer.state = ComputerDetails.State.UNKNOWN
                        }
                    }
                }
            }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        discoveryBinder?.stopDiscovery()
        pollingActive = false
        synchronized(pollingTuples) {
            for (tuple in pollingTuples) {
                tuple.thread?.interrupt()
                tuple.thread = null
            }
        }
        listener = null
        return false
    }

    @Suppress("DEPRECATION")
    private fun populateExternalAddress(details: ComputerDetails) {
        var boundToNetwork = false
        val activeNetworkIsVpn = NetHelper.isActiveNetworkVpn(this)
        val connMgr = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        if (activeNetworkIsVpn) {
            defaultNetworkLock.lock()
            for (net in connMgr.allNetworks) {
                val netCaps = connMgr.getNetworkCapabilities(net)
                if (netCaps != null &&
                    !netCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    !netCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                ) {
                    if (connMgr.bindProcessToNetwork(net)) {
                        boundToNetwork = true
                        break
                    }
                }
            }

            if (!activeNetworkIsVpn || boundToNetwork) {
                val stunResolvedAddress = NvConnection.findExternalAddressForMdns("stun.cloudflare.com", 3478)
                if (stunResolvedAddress != null) {
                    details.remoteAddress = ComputerDetails.AddressTuple(stunResolvedAddress, details.guessExternalPort())
                }
            }

            if (boundToNetwork) {
                connMgr.bindProcessToNetwork(null)
            }
            defaultNetworkLock.unlock()
        }
    }

    private fun createDiscoveryListener(): MdnsDiscoveryListener {
        return object : MdnsDiscoveryListener {
            override fun notifyComputerAdded(computer: MdnsComputer) {
                val details = ComputerDetails()
                computer.localAddress?.let { localAddr ->
                    details.localAddress = ComputerDetails.AddressTuple(localAddr.hostAddress, computer.port)
                    if (localAddr is Inet4Address) {
                        populateExternalAddress(details)
                    }
                }
                computer.ipv6Address?.let { v6Addr ->
                    details.ipv6Address = ComputerDetails.AddressTuple(v6Addr.hostAddress, computer.port)
                }

                try {
                    if (!addComputerBlocking(details)) {
                        VibemisLog.warning("Auto-discovered PC failed to respond: $details")
                    }
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                    Thread.currentThread().interrupt()
                }
            }

            override fun notifyDiscoveryFailure(e: Exception) {
                VibemisLog.severe("mDNS discovery failed")
                e.printStackTrace()
            }
        }
    }

    private fun addTuple(details: ComputerDetails) {
        synchronized(pollingTuples) {
            for (tuple in pollingTuples) {
                if (tuple.computer.uuid == details.uuid) {
                    tuple.computer.update(details)
                    if (pollingActive && tuple.thread == null) {
                        val t = createPollingThread(tuple)
                        tuple.thread = t
                        t.start()
                    }
                    return
                }
            }

            val tuple = PollingTuple(details, null)
            if (pollingActive) {
                tuple.thread = createPollingThread(tuple)
            }
            pollingTuples.add(tuple)
            tuple.thread?.start()
        }
    }

    @Throws(InterruptedException::class)
    fun addComputerBlocking(fakeDetails: ComputerDetails): Boolean {
        if (pollComputer(fakeDetails)) {
            synchronized(pollingTuples) {
                for (tuple in pollingTuples) {
                    if (tuple.computer.uuid == fakeDetails.uuid) {
                        fakeDetails.serverCert = tuple.computer.serverCert
                        break
                    }
                }
            }
            runPoll(fakeDetails, true, 0)
        }

        return if (fakeDetails.state == ComputerDetails.State.ONLINE) {
            VibemisLog.info("New PC (${fakeDetails.name}) is UUID ${fakeDetails.uuid}")
            addTuple(fakeDetails)
            true
        } else {
            false
        }
    }

    fun removeComputer(computer: ComputerDetails) {
        if (!getLocalDatabaseReference()) return
        dbManager.deleteComputer(computer)

        synchronized(pollingTuples) {
            val iter = pollingTuples.iterator()
            while (iter.hasNext()) {
                val tuple = iter.next()
                if (tuple.computer.uuid == computer.uuid) {
                    tuple.thread?.interrupt()
                    tuple.thread = null
                    iter.remove()
                    break
                }
            }
        }

        releaseLocalDatabaseReference()
    }

    private fun getLocalDatabaseReference(): Boolean {
        if (dbRefCount.get() == 0) return false
        dbRefCount.incrementAndGet()
        return true
    }

    private fun releaseLocalDatabaseReference() {
        if (dbRefCount.decrementAndGet() == 0) {
            dbManager.close()
        }
    }

    private fun tryPollIp(details: ComputerDetails, address: ComputerDetails.AddressTuple): ComputerDetails? {
        return try {
            val portMatchesActiveAddress = details.state == ComputerDetails.State.ONLINE &&
                details.activeAddress != null && address.port == details.activeAddress!!.port

            val http = NvHTTP(
                address,
                if (portMatchesActiveAddress) details.httpsPort else 0,
                idManager.uniqueId,
                details.serverCert,
                PlatformBinding.getCryptoProvider(this@ComputerManagerService)
            )

            val isLikelyOnline = details.state == ComputerDetails.State.ONLINE && address == details.activeAddress
            val newDetails = http.getComputerDetails(isLikelyOnline)

            if (newDetails.uuid.isEmpty()) {
                VibemisLog.severe("Polling returned no UUID!")
                null
            } else if (details.uuid.isNotEmpty() && details.uuid != newDetails.uuid) {
                VibemisLog.info("Polling returned the wrong PC!")
                null
            } else {
                newDetails
            }
        } catch (e: XmlPullParserException) {
            e.printStackTrace()
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: IOException) {
            null
        }
    }

    private class ParallelPollTuple(
        val address: ComputerDetails.AddressTuple?,
        val existingDetails: ComputerDetails
    ) {
        val monitor = Object()
        var complete = false
        var pollingThread: Thread? = null
        var returnedDetails: ComputerDetails? = null

        fun interrupt() {
            pollingThread?.interrupt()
        }
    }

    private fun startParallelPollThread(
        tuple: ParallelPollTuple,
        uniqueAddresses: HashSet<ComputerDetails.AddressTuple>
    ) {
        val address = tuple.address
        if (address == null || !uniqueAddresses.add(address)) {
            tuple.complete = true
            tuple.returnedDetails = null
            return
        }

        tuple.pollingThread = Thread({
            val details = tryPollIp(tuple.existingDetails, address)
            synchronized(tuple.monitor) {
                tuple.complete = true
                tuple.returnedDetails = details
                tuple.monitor.notify()
            }
        }, "Parallel Poll - $address - ${tuple.existingDetails.name}").also { it.start() }
    }

    @Throws(InterruptedException::class)
    private fun parallelPollPc(details: ComputerDetails): ComputerDetails? {
        val localInfo = ParallelPollTuple(details.localAddress, details)
        val manualInfo = ParallelPollTuple(details.manualAddress, details)
        val remoteInfo = ParallelPollTuple(details.remoteAddress, details)
        val ipv6Info = ParallelPollTuple(details.ipv6Address, details)

        val uniqueAddresses = HashSet<ComputerDetails.AddressTuple>()
        startParallelPollThread(localInfo, uniqueAddresses)
        startParallelPollThread(manualInfo, uniqueAddresses)
        startParallelPollThread(remoteInfo, uniqueAddresses)
        startParallelPollThread(ipv6Info, uniqueAddresses)

        try {
            for (info in arrayOf(localInfo, manualInfo, remoteInfo, ipv6Info)) {
                synchronized(info.monitor) {
                    while (!info.complete) {
                        info.monitor.wait()
                    }
                    info.returnedDetails?.let { res ->
                        res.activeAddress = info.address
                        return res
                    }
                }
            }
        } finally {
            localInfo.interrupt()
            manualInfo.interrupt()
            remoteInfo.interrupt()
            ipv6Info.interrupt()
        }

        return null
    }

    @Throws(InterruptedException::class)
    private fun pollComputer(details: ComputerDetails): Boolean {
        VibemisLog.info("Starting parallel poll for ${details.name} (${details.localAddress}, ${details.remoteAddress}, ${details.manualAddress}, ${details.ipv6Address})")
        val polledDetails = parallelPollPc(details)
        VibemisLog.info("Parallel poll for ${details.name} returned address: ${details.activeAddress}")

        return if (polledDetails != null) {
            details.update(polledDetails)
            true
        } else {
            false
        }
    }

    override fun onCreate() {
        bindService(
            Intent(this, DiscoveryService::class.java),
            discoveryServiceConnection,
            BIND_AUTO_CREATE
        )

        idManager = IdentityManager(this)
        dbManager = ComputerDatabaseManager(this)
        dbRefCount.set(1)

        if (!getLocalDatabaseReference()) return

        for (computer in dbManager.allComputers) {
            addTuple(computer)
        }

        releaseLocalDatabaseReference()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                VibemisLog.info("Resetting PC state for new available network")
                synchronized(pollingTuples) {
                    for (tuple in pollingTuples) {
                        tuple.computer.state = ComputerDetails.State.UNKNOWN
                        listener?.notifyComputerUpdated(tuple.computer)
                    }
                }
            }

            override fun onLost(network: Network) {
                VibemisLog.info("Offlining PCs due to network loss")
                synchronized(pollingTuples) {
                    for (tuple in pollingTuples) {
                        tuple.computer.state = ComputerDetails.State.OFFLINE
                        listener?.notifyComputerUpdated(tuple.computer)
                    }
                }
            }
        }

        val connMgr = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connMgr.registerDefaultNetworkCallback(networkCallback)
    }

    override fun onDestroy() {
        val connMgr = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connMgr.unregisterNetworkCallback(networkCallback)

        if (discoveryBinder != null) {
            unbindService(discoveryServiceConnection)
        }

        releaseLocalDatabaseReference()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    inner class ApplistPoller(private val computer: ComputerDetails) {
        @Volatile
        private var thread: Thread? = null
        private val pollEvent = Object()
        private var receivedAppList = false

        fun pollNow() {
            synchronized(pollEvent) {
                pollEvent.notify()
            }
        }

        private fun waitPollingDelay(): Boolean {
            try {
                synchronized(pollEvent) {
                    val waitMs = if (receivedAppList) APPLIST_POLLING_PERIOD_MS else APPLIST_FAILED_POLLING_RETRY_MS
                    pollEvent.wait(waitMs.toLong())
                }
            } catch (_: InterruptedException) {
                return false
            }
            val current = thread
            return current != null && current === Thread.currentThread() && !current.isInterrupted
        }

        private fun getPollingTuple(details: ComputerDetails): PollingTuple? {
            synchronized(pollingTuples) {
                return pollingTuples.firstOrNull { details.uuid == it.computer.uuid }
            }
        }

        @Synchronized
        fun start() {
            if (thread?.isAlive == true) return
            val pollerThread = Thread({
                var emptyAppListResponses = 0
                var stoppedByInterrupt = false
                do {
                    if (Thread.currentThread().isInterrupted || thread !== Thread.currentThread()) {
                        break
                    }
                    if (computer.state != ComputerDetails.State.ONLINE ||
                        computer.pairState != PairingManager.PairState.PAIRED
                    ) {
                        listener?.notifyComputerUpdated(computer)
                        continue
                    }

                    if (computer.uuid.isEmpty()) continue

                    val tuple = getPollingTuple(computer)

                    try {
                        val http = NvHTTP(
                            ServerHelper.getCurrentAddressFromComputer(computer),
                            computer.httpsPort,
                            idManager.uniqueId,
                            computer.serverCert,
                            PlatformBinding.getCryptoProvider(this@ComputerManagerService)
                        )

                        val appList = if (tuple != null) {
                            synchronized(tuple.networkLock) {
                                http.getAppListRaw()
                            }
                        } else {
                            http.getAppListRaw()
                        }

                        val list = NvHTTP.getAppListByReader(StringReader(appList))
                        if (list.isEmpty()) {
                            VibemisLog.warning("Empty app list received from ${computer.uuid}")
                            emptyAppListResponses++
                        }
                        if (appList.isNotEmpty() && (list.isNotEmpty() || emptyAppListResponses >= EMPTY_LIST_THRESHOLD)) {
                            try {
                                CacheHelper.openCacheFileForOutput(cacheDir, "applist", computer.uuid).use { cacheOut ->
                                    CacheHelper.writeStringToOutputStream(cacheOut, appList)
                                }
                            } catch (e: IOException) {
                                e.printStackTrace()
                            }

                            if (list.isNotEmpty()) {
                                emptyAppListResponses = 0
                            }

                            computer.rawAppList = appList
                            receivedAppList = true

                            if (listener != null && thread != null) {
                                listener?.notifyComputerUpdated(computer)
                            }
                        } else if (appList.isEmpty()) {
                            VibemisLog.warning("Null app list received from ${computer.uuid}")
                        }
                    } catch (_: InterruptedException) {
                        stoppedByInterrupt = true
                        Thread.currentThread().interrupt()
                        break
                    } catch (_: InterruptedIOException) {
                        stoppedByInterrupt = true
                        Thread.currentThread().interrupt()
                        break
                    } catch (e: IOException) {
                        if (Thread.currentThread().isInterrupted) {
                            stoppedByInterrupt = true
                            break
                        }
                        e.printStackTrace()
                    } catch (e: XmlPullParserException) {
                        e.printStackTrace()
                    }
                } while (!stoppedByInterrupt && waitPollingDelay())
            }, "App list polling thread for ${computer.name}")
            thread = pollerThread
            pollerThread.start()
        }

        @Synchronized
        fun stop() {
            thread?.interrupt()
            thread = null
        }
    }

    companion object {
        private const val SERVERINFO_POLLING_PERIOD_MS = 1500
        private const val APPLIST_POLLING_PERIOD_MS = 30000
        private const val APPLIST_FAILED_POLLING_RETRY_MS = 2000
        private const val MDNS_QUERY_PERIOD_MS = 1000
        private const val OFFLINE_POLL_TRIES = 3
        private const val INITIAL_POLL_TRIES = 2
        private const val EMPTY_LIST_THRESHOLD = 3
        private const val POLL_DATA_TTL_MS = 30000
    }
}

internal class PollingTuple(
    val computer: ComputerDetails,
    @Volatile var thread: Thread?
) {
    val networkLock = Any()
    var lastSuccessfulPollMs: Long = 0
}
