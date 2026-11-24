package com.ineedmypills.vibemis.binding.video

import android.os.SystemClock
import kotlin.math.max
import kotlin.math.min

class VideoStats {

    @JvmField
    var decoderTimeMs: Long = 0
    @JvmField
    var totalTimeMs: Long = 0
    @JvmField
    var endToEndLatencyMs: Long = 0 // Tracks old latency metric (uptime - PTS)
    @JvmField
    var totalFrames: Int = 0
    @JvmField
    var totalFramesReceived: Int = 0
    @JvmField
    var totalFramesRendered: Int = 0
    @JvmField
    var frameLossEvents: Int = 0
    @JvmField
    var framesLost: Int = 0
    @JvmField
    var minHostProcessingLatency: Char = '\u0000'
    @JvmField
    var maxHostProcessingLatency: Char = '\u0000'
    @JvmField
    var totalHostProcessingLatency: Int = 0
    @JvmField
    var framesWithHostProcessingLatency: Int = 0
    @JvmField
    var measurementStartTimestamp: Long = 0
    @JvmField
    var decoderSamples: Long = 0
    @JvmField
    var decoderMisses: Long = 0

    fun add(other: VideoStats) {
        this.decoderTimeMs += other.decoderTimeMs
        this.totalTimeMs += other.totalTimeMs
        this.endToEndLatencyMs += other.endToEndLatencyMs
        this.totalFrames += other.totalFrames
        this.totalFramesReceived += other.totalFramesReceived
        this.totalFramesRendered += other.totalFramesRendered
        this.frameLossEvents += other.frameLossEvents
        this.framesLost += other.framesLost

        // Decode latency sample counters
        this.decoderSamples += other.decoderSamples
        this.decoderMisses += other.decoderMisses

        if (this.minHostProcessingLatency.code == 0) {
            this.minHostProcessingLatency = other.minHostProcessingLatency
        } else if (other.minHostProcessingLatency.code != 0) {
            this.minHostProcessingLatency = min(
                this.minHostProcessingLatency.code,
                other.minHostProcessingLatency.code
            ).toChar()
        }
        this.maxHostProcessingLatency = max(
            this.maxHostProcessingLatency.code,
            other.maxHostProcessingLatency.code
        ).toChar()
        this.totalHostProcessingLatency += other.totalHostProcessingLatency
        this.framesWithHostProcessingLatency += other.framesWithHostProcessingLatency

        if (this.measurementStartTimestamp == 0L) {
            this.measurementStartTimestamp = other.measurementStartTimestamp
        }
    }

    fun copy(other: VideoStats) {
        this.decoderTimeMs = other.decoderTimeMs
        this.totalTimeMs = other.totalTimeMs
        this.endToEndLatencyMs = other.endToEndLatencyMs
        this.totalFrames = other.totalFrames
        this.totalFramesReceived = other.totalFramesReceived
        this.totalFramesRendered = other.totalFramesRendered
        this.frameLossEvents = other.frameLossEvents
        this.framesLost = other.framesLost
        this.minHostProcessingLatency = other.minHostProcessingLatency
        this.maxHostProcessingLatency = other.maxHostProcessingLatency
        this.totalHostProcessingLatency = other.totalHostProcessingLatency
        this.framesWithHostProcessingLatency = other.framesWithHostProcessingLatency
        this.measurementStartTimestamp = other.measurementStartTimestamp

        // Decode latency sample accounting
        this.decoderSamples = other.decoderSamples
        this.decoderMisses = other.decoderMisses
    }

    fun clear() {
        this.decoderTimeMs = 0
        this.totalTimeMs = 0
        this.endToEndLatencyMs = 0
        this.totalFrames = 0
        this.totalFramesReceived = 0
        this.totalFramesRendered = 0
        this.frameLossEvents = 0
        this.framesLost = 0
        this.minHostProcessingLatency = '\u0000'
        this.maxHostProcessingLatency = '\u0000'
        this.totalHostProcessingLatency = 0
        this.framesWithHostProcessingLatency = 0
        this.measurementStartTimestamp = 0

        // Decode latency sample accounting
        this.decoderSamples = 0
        this.decoderMisses = 0
    }

    val fps: VideoStatsFps
        get() {
            val elapsed = (SystemClock.uptimeMillis() - this.measurementStartTimestamp) / 1000f
            val fps = VideoStatsFps()
            if (elapsed > 0) {
                fps.totalFps = this.totalFrames / elapsed
                fps.receivedFps = this.totalFramesReceived / elapsed
                fps.renderedFps = this.totalFramesRendered / elapsed
            }
            return fps
        }
}

class VideoStatsFps {
    @JvmField
    var totalFps: Float = 0f
    @JvmField
    var receivedFps: Float = 0f
    @JvmField
    var renderedFps: Float = 0f
}
