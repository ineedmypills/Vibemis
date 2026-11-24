package com.ineedmypills.vibemis.utils

import kotlin.math.sqrt

class Vector2d {
    var x: Float = 0f
        set(value) {
            field = value
            magnitude = sqrt((field * field + y * y).toDouble())
        }
    var y: Float = 0f
        set(value) {
            field = value
            magnitude = sqrt((x * x + field * field).toDouble())
        }
    var magnitude: Double = 0.0
        private set

    init {
        initialize(0f, 0f)
    }

    fun initialize(x: Float, y: Float) {
        this.x = x
        this.y = y
    }

    fun getNormalized(vector: Vector2d) {
        vector.initialize((x / magnitude).toFloat(), (y / magnitude).toFloat())
    }

    fun scalarMultiply(factor: Double) {
        initialize((x * factor).toFloat(), (y * factor).toFloat())
    }

    companion object {
        @JvmField
        val ZERO = Vector2d()
    }
}
