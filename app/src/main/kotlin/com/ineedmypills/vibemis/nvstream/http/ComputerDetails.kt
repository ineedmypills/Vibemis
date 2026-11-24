package com.ineedmypills.vibemis.nvstream.http

import java.security.cert.X509Certificate
import java.util.Objects

class ComputerDetails {
    enum class State {
        ONLINE, OFFLINE, UNKNOWN
    }

    class AddressTuple(address: String?, port: Int) {
        @JvmField var address: String
        @JvmField var port: Int

        init {
            requireNotNull(address) { "Address cannot be null" }
            require(port > 0) { "Invalid port" }

            this.address = if (address.startsWith("[") && address.endsWith("]")) {
                address.substring(1, address.length - 1)
            } else {
                address
            }
            this.port = port
        }

        override fun hashCode(): Int = Objects.hash(address, port)

        override fun equals(other: Any?): Boolean {
            if (other !is AddressTuple) return false
            return address == other.address && port == other.port
        }

        override fun toString(): String {
            return if (address.contains(":")) {
                "[$address]:$port"
            } else {
                "$address:$port"
            }
        }
    }

    @JvmField var uuid: String = ""
    @JvmField var name: String = ""
    @JvmField var localAddress: AddressTuple? = null
    @JvmField var remoteAddress: AddressTuple? = null
    @JvmField var manualAddress: AddressTuple? = null
    @JvmField var ipv6Address: AddressTuple? = null
    @JvmField var macAddress: String? = null
    @JvmField var serverCert: X509Certificate? = null

    @JvmField var state: State = State.UNKNOWN
    @JvmField var permission: Int = -1
    @JvmField var activeAddress: AddressTuple? = null
    @JvmField var httpsPort: Int = 0
    @JvmField var externalPort: Int = 0
    @JvmField var pairState: PairingManager.PairState? = null
    @JvmField var runningGameId: Int = 0
    @JvmField var runningGameUUID: String? = null
    @JvmField var rawAppList: String? = null
    @JvmField var nvidiaServer: Boolean = false

    @JvmField var vDisplaySupported: Boolean = false
    @JvmField var vDisplayDriverReady: Boolean = false

    @JvmField var serverCommands: List<String>? = null

    @JvmField var vibepolloCapabilities: VibepolloCapabilities = VibepolloCapabilities()

    constructor() {
        state = State.UNKNOWN
    }

    constructor(details: ComputerDetails) {
        update(details)
    }

    fun guessExternalPort(): Int {
        return when {
            externalPort != 0 -> externalPort
            remoteAddress != null -> remoteAddress!!.port
            activeAddress != null -> activeAddress!!.port
            ipv6Address != null -> ipv6Address!!.port
            localAddress != null -> localAddress!!.port
            else -> NvHTTP.DEFAULT_HTTP_PORT
        }
    }

    fun update(details: ComputerDetails) {
        this.state = details.state
        this.name = details.name
        this.uuid = details.uuid
        this.permission = details.permission
        if (details.activeAddress != null) {
            this.activeAddress = details.activeAddress
        }
        if (details.localAddress != null && !details.localAddress!!.address.startsWith("127.")) {
            this.localAddress = details.localAddress
        }
        if (details.remoteAddress != null) {
            this.remoteAddress = details.remoteAddress
        } else if (this.remoteAddress != null && details.externalPort != 0) {
            this.remoteAddress!!.port = details.externalPort
        }
        if (details.manualAddress != null) {
            this.manualAddress = details.manualAddress
        }
        if (details.ipv6Address != null) {
            this.ipv6Address = details.ipv6Address
        }
        if (details.macAddress != null && details.macAddress != "00:00:00:00:00:00") {
            this.macAddress = details.macAddress
        }
        if (details.serverCert != null) {
            this.serverCert = details.serverCert
        }
        this.externalPort = details.externalPort
        this.httpsPort = details.httpsPort
        this.pairState = details.pairState
        this.runningGameId = details.runningGameId
        this.runningGameUUID = details.runningGameUUID
        this.nvidiaServer = details.nvidiaServer
        this.rawAppList = details.rawAppList

        this.vDisplayDriverReady = details.vDisplayDriverReady
        this.vDisplaySupported = details.vDisplaySupported

        this.serverCommands = details.serverCommands
        this.vibepolloCapabilities = details.vibepolloCapabilities
    }

    override fun toString(): String {
        val permissionsStr = if (permission < 0) {
            "N/A\n"
        } else {
            "0x" + Integer.toHexString(permission) + "\n" +
                " - Controller Input: " + ((permission and 0x00000100) != 0) + "\n" +
                " - Touch Input: " + ((permission and 0x00000200) != 0) + "\n" +
                " - Pen Input: " + ((permission and 0x00000400) != 0) + "\n" +
                " - Mouse Input: " + ((permission and 0x00000800) != 0) + "\n" +
                " - Keyboard Input: " + ((permission and 0x00001000) != 0) + "\n\n" +
                " - Server Command: " + ((permission and 0x00100000) != 0) + "\n\n" +
                " - List Apps: " + ((permission and 0x01000000) != 0) + "\n" +
                " - View Streams: " + ((permission and (0x02000000 or 0x01000000)) != 0) + "\n" +
                " - Launch Apps: " + ((permission and (0x04000000 or 0x02000000 or 0x01000000)) != 0) + "\n"
        }

        return "Name: $name\n" +
            "State: $state\n" +
            "Active Address: $activeAddress\n" +
            "UUID: $uuid\n\n" +
            "Permissions: $permissionsStr\n" +
            "Local Address: $localAddress\n" +
            "Remote Address: $remoteAddress\n" +
            "IPv6 Address: $ipv6Address\n" +
            "Manual Address: $manualAddress\n" +
            "MAC Address: $macAddress\n" +
            "Pair State: $pairState\n" +
            "Running Game ID: $runningGameId\n" +
            "Running Game UUID: $runningGameUUID\n" +
            "HTTPS Port: $httpsPort\n"
    }
}
