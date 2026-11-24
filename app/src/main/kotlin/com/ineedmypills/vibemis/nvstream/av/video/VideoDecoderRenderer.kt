package com.ineedmypills.vibemis.nvstream.av.video

abstract class VideoDecoderRenderer {
    abstract fun setup(format: Int, width: Int, height: Int, redrawRate: Int): Int
    abstract fun start()
    abstract fun stop()

    abstract fun submitDecodeUnit(
        decodeUnitData: ByteArray,
        decodeUnitLength: Int,
        decodeUnitType: Int,
        frameNumber: Int,
        frameType: Int,
        frameHostProcessingLatency: Char,
        receiveTimeMs: Long,
        enqueueTimeMs: Long
    ): Int

    abstract fun cleanup()
    abstract fun getCapabilities(): Int
    abstract fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?)
}
