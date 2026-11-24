package com.ineedmypills.vibemis.grid.assets

import com.ineedmypills.vibemis.nvstream.http.ComputerDetails
import com.ineedmypills.vibemis.nvstream.http.NvApp

class CachedAppAssetLoader private constructor() {
    class LoaderTuple(
        @JvmField val computer: ComputerDetails,
        @JvmField val app: NvApp
    ) {
        override fun equals(other: Any?): Boolean {
            if (other !is LoaderTuple) return false
            return computer.uuid == other.computer.uuid && app.appId == other.app.appId
        }

        override fun hashCode(): Int {
            return 31 * computer.uuid.hashCode() + app.appId
        }

        override fun toString(): String = "(${computer.uuid}, ${app.appId})"
    }
}
