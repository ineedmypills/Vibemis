package com.ineedmypills.vibemis

import java.io.IOException
import java.util.logging.FileHandler
import java.util.logging.Logger

object VibemisLog {
    private val LOGGER: Logger = Logger.getLogger(VibemisLog::class.java.name)

    @JvmStatic
    fun info(msg: String?) {
        LOGGER.info(msg ?: "null")
    }

    @JvmStatic
    fun warning(msg: String?) {
        LOGGER.warning(msg ?: "null")
    }

    @JvmStatic
    fun severe(msg: String?) {
        LOGGER.severe(msg ?: "null")
    }

    @JvmStatic
    @Throws(IOException::class)
    fun setFileHandler(fileName: String) {
        LOGGER.addHandler(FileHandler(fileName))
    }
}
