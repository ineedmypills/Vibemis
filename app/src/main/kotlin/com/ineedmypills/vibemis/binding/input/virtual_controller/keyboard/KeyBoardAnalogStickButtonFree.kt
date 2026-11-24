package com.ineedmypills.vibemis.binding.input.virtual_controller.keyboard

import android.content.Context

class KeyBoardAnalogStickButtonFree(
    controller: KeyBoardController,
    elementId: String,
    context: Context,
    keyInfo: IntArray
) : keyAnalogStickFree(controller, context, elementId) {

    private val MIN_CIRCLE_R = 10000
    private val EIGHTH_PI = 0.4142f
    private val EIGHTH_THREE_PI = 2.4142f
    private val NEGATIVE_EIGHTH_PI = -0.4142f
    private val NEGATIVE_EIGHTH_THREE_PI = -2.4142f

    private val stickIndex = IntArray(4)
    private val stickBool = BooleanArray(4)
    private val stickSender = IntArray(5)

    private var listener: KeyBoardAnalogStickListener? = null

    fun setListener(listener: KeyBoardAnalogStickListener?) {
        this.listener = listener
    }

    init {
        for (i in keyInfo.indices) {
            stickSender[i] = keyInfo[i]
        }
        addAnalogStickListener(object : AnalogStickListener {
            override fun onMovement(x: Float, y: Float) {
                val ix = (x * 0x7FFE).toInt().toShort().toInt()
                val iy = (y * 0x7FFE).toInt().toShort().toInt()

                if (iy > 0) {
                    stickIndex[0] = iy
                    stickIndex[1] = -1
                } else if (iy < 0) {
                    stickIndex[0] = -1
                    stickIndex[1] = -iy
                } else {
                    stickIndex[0] = 0
                    stickIndex[1] = -1
                }

                if (ix > 0) {
                    stickIndex[2] = -1
                    stickIndex[3] = ix
                } else if (ix < 0) {
                    stickIndex[2] = -ix
                    stickIndex[3] = -1
                } else {
                    stickIndex[2] = 0
                    stickIndex[3] = -1
                }

                val b = ix * ix + iy * iy > MIN_CIRCLE_R * MIN_CIRCLE_R
                if (iy >= EIGHTH_THREE_PI * ix && iy >= NEGATIVE_EIGHTH_THREE_PI * ix && b) {
                    stickBool[0] = true; stickBool[1] = false; stickBool[2] = false; stickBool[3] = false
                } else if (iy < EIGHTH_THREE_PI * ix && iy < NEGATIVE_EIGHTH_THREE_PI * ix && b) {
                    stickBool[0] = false; stickBool[1] = true; stickBool[2] = false; stickBool[3] = false
                } else if (iy >= EIGHTH_PI * ix && iy < NEGATIVE_EIGHTH_PI * ix && b) {
                    stickBool[0] = false; stickBool[1] = false; stickBool[2] = true; stickBool[3] = false
                } else if (iy < EIGHTH_PI * ix && iy >= NEGATIVE_EIGHTH_PI * ix && b) {
                    stickBool[0] = false; stickBool[1] = false; stickBool[2] = false; stickBool[3] = true
                } else if (iy < NEGATIVE_EIGHTH_THREE_PI * ix && iy >= NEGATIVE_EIGHTH_PI * ix && b) {
                    stickBool[0] = true; stickBool[1] = false; stickBool[2] = true; stickBool[3] = false
                } else if (iy >= NEGATIVE_EIGHTH_THREE_PI * ix && iy < NEGATIVE_EIGHTH_PI * ix && b) {
                    stickBool[0] = false; stickBool[1] = true; stickBool[2] = false; stickBool[3] = true
                } else if (iy >= EIGHTH_PI * ix && iy < EIGHTH_THREE_PI * ix && b) {
                    stickBool[0] = true; stickBool[1] = false; stickBool[2] = false; stickBool[3] = true
                } else if (iy < EIGHTH_PI * ix && iy >= EIGHTH_THREE_PI * ix && b) {
                    stickBool[0] = false; stickBool[1] = true; stickBool[2] = true; stickBool[3] = false
                } else {
                    stickBool[0] = false; stickBool[1] = false; stickBool[2] = false; stickBool[3] = false
                }

                for (i in 0 until 4) {
                    listener?.onkeyEvent(stickSender[i], stickBool[i])
                }
            }

            override fun onClick() {}

            override fun onDoubleClick() {
                listener?.onkeyEvent(stickSender[4], true)
            }

            override fun onRevoke() {
                stickIndex[0] = 0
                stickIndex[1] = -1
                stickIndex[2] = 0
                stickIndex[3] = -1
                stickBool[0] = false
                stickBool[1] = false
                stickBool[2] = false
                stickBool[3] = false
                for (i in 0 until 4) {
                    listener?.onkeyEvent(stickSender[i], stickBool[i])
                }
                listener?.onkeyEvent(stickSender[4], false)
            }
        })
    }

    fun interface KeyBoardAnalogStickListener {
        fun onkeyEvent(code: Int, isPress: Boolean)
    }
}
