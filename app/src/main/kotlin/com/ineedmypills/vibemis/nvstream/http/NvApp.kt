package com.ineedmypills.vibemis.nvstream.http

import com.ineedmypills.vibemis.VibemisLog

class NvApp {
    var appName: String = ""
        set(value) {
            field = value.trim()
        }
    var appUUID: String? = ""
    var appId: Int = 0
        set(value) {
            field = value
            isInitialized = true
        }
    var appIndex: Int = 0
    var isInitialized: Boolean = false
        private set
    var isHdrSupported: Boolean = false

    constructor()

    constructor(appName: String) {
        this.appName = appName
    }

    constructor(appName: String?, appUUID: String?, appId: Int, hdrSupported: Boolean) {
        this.appName = appName ?: ""
        this.appUUID = appUUID
        this.appId = appId
        this.isHdrSupported = hdrSupported
        this.isInitialized = true
    }

    fun setAppId(appId: String?) {
        if (appId == null) return
        try {
            this.appId = appId.toInt()
            this.isInitialized = true
        } catch (e: NumberFormatException) {
            VibemisLog.warning("Malformed app ID: $appId")
        }
    }

    fun setAppIndex(appIndex: String?) {
        if (appIndex == null) return
        try {
            this.appIndex = appIndex.toInt()
            this.isInitialized = true
        } catch (e: NumberFormatException) {
            VibemisLog.warning("Malformed app index: $appIndex")
        }
    }

    fun isRemoteInputApp(): Boolean {
        val uuid = appUUID?.trim()
        return REMOTE_INPUT_UUID.equals(uuid, ignoreCase = true) ||
            VIBEPOLLO_INPUT_UUID.equals(uuid, ignoreCase = true) ||
            appId in REMOTE_INPUT_IDS
    }

    fun isRemoteMonitorApp(): Boolean {
        val uuid = appUUID?.trim()
        return VIBEPOLLO_MONITOR_UUID.equals(uuid, ignoreCase = true) ||
            appId in REMOTE_MONITOR_IDS
    }

    fun isVibepolloControlAction(): Boolean {
        val uuid = appUUID?.trim()
        return CONTROL_ACTION_UUIDS.any { it.equals(uuid, ignoreCase = true) } ||
            appId in CONTROL_ACTION_IDS
    }

    fun isVibepolloSyntheticApp(): Boolean {
        val uuid = appUUID?.trim()
        return isRemoteInputApp() ||
            isRemoteMonitorApp() ||
            isVibepolloControlAction() ||
            ALL_SYNTHETIC_UUIDS.any { it.equals(uuid, ignoreCase = true) } ||
            appId in ALL_SYNTHETIC_IDS
    }

    override fun toString(): String {
        return buildString {
            append("Name: ").append(appName).append("\n")
            append("UUID: ").append(appUUID).append("\n")
            append("ID: ").append(appId).append("\n")
            append("HDR Supported: ").append(if (isHdrSupported) "Yes" else "Unknown").append("\n")
        }
    }

    companion object {
        const val REMOTE_INPUT_UUID = "8CB5C136-DA67-4F99-B4A1-F9CD35005CF4"
        const val VIBEPOLLO_RESUME_UUID = "9a1c5a25-58fe-40e0-b9aa-7d3f00000001"
        const val VIBEPOLLO_DISCONNECT_MONITOR_UUID = "9a1c5a25-58fe-40e0-b9aa-7d3f00000002"
        const val VIBEPOLLO_DISCONNECT_INPUT_UUID = "9a1c5a25-58fe-40e0-b9aa-7d3f00000003"
        const val VIBEPOLLO_TERMINATE_UUID = "9a1c5a25-58fe-40e0-b9aa-7d3f00000004"
        const val VIBEPOLLO_MONITOR_UUID = "9a1c5a25-58fe-40e0-b9aa-7d3f00000005"
        const val VIBEPOLLO_INPUT_UUID = "9a1c5a25-58fe-40e0-b9aa-7d3f00000006"
        const val VIBEPOLLO_RUNNING_GAME_UUID = "9a1c5a25-58fe-40e0-b9aa-7d3f00000007"

        private val CONTROL_ACTION_UUIDS = arrayOf(
            VIBEPOLLO_DISCONNECT_MONITOR_UUID,
            VIBEPOLLO_DISCONNECT_INPUT_UUID,
            VIBEPOLLO_TERMINATE_UUID
        )

        private val ALL_SYNTHETIC_UUIDS = arrayOf(
            REMOTE_INPUT_UUID,
            VIBEPOLLO_RESUME_UUID,
            VIBEPOLLO_DISCONNECT_MONITOR_UUID,
            VIBEPOLLO_DISCONNECT_INPUT_UUID,
            VIBEPOLLO_TERMINATE_UUID,
            VIBEPOLLO_MONITOR_UUID,
            VIBEPOLLO_INPUT_UUID,
            VIBEPOLLO_RUNNING_GAME_UUID
        )

        private val REMOTE_INPUT_IDS = setOf(2147483506, 2147483516, 2147483606, -2068186362)
        private val REMOTE_MONITOR_IDS = setOf(2147483505, 2147483515, 2147483605, -2068186363)
        private val CONTROL_ACTION_IDS = setOf(
            2147483502, 2147483503, 2147483504, 2147483514,
            2147483602, 2147483603, 2147483604,
            -2068186366, -2068186365, -2068186364
        )
        private val ALL_SYNTHETIC_IDS = setOf(
            2147483501, 2147483502, 2147483503, 2147483504, 2147483505, 2147483506, 2147483507,
            2147483511, 2147483514, 2147483515, 2147483516,
            2147483601, 2147483602, 2147483603, 2147483604, 2147483605, 2147483606, 2147483607,
            -2068186367, -2068186366, -2068186365, -2068186364, -2068186363, -2068186362, -2068186361
        )
    }
}
