package com.ineedmypills.vibemis.computers

import com.ineedmypills.vibemis.nvstream.http.ComputerDetails

fun interface ComputerManagerListener {
    fun notifyComputerUpdated(details: ComputerDetails)
}
