package com.ineedmypills.vibemis.binding.video

import android.content.Context
import android.net.TrafficStats
import android.os.Process
import com.ineedmypills.vibemis.R
import com.ineedmypills.vibemis.preferences.PreferenceConfiguration
import com.ineedmypills.vibemis.utils.TrafficStatsHelper
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * PerfOverlayComposer: builds perf overlay strings (mini/lite/full) and encapsulates
 * OLED shift/blink state + bandwidth delta tracking.
 *
 * Intended to run on the UI thread (the caller decides threading).
 */
class PerfOverlayComposer {

    class Result(fullLog: String?, renderedLog: String?) {
        @JvmField
        val fullLog: String = fullLog ?: ""
        @JvmField
        val renderedLog: String = renderedLog ?: ""
    }

    @Volatile
    private var lastNetDataNum = 0L

    private var liteBlinkNextStartNs = 0L
    private var liteBlinkEndNs = 0L

    private var liteShiftNextNs = 0L
    private var liteShiftSpaces = 0 // 0..2 (ping-pong)

    fun reset() {
        lastNetDataNum = 0L
        liteBlinkNextStartNs = 0L
        liteBlinkEndNs = 0L
        liteShiftNextNs = 0L
        liteShiftSpaces = 0
    }

    /**
     * Builds overlay strings. Caller decides whether/when to dispatch to PerfOverlayListener.
     */
    fun build(
        context: Context?,
        prefsSnapshot: PreferenceConfiguration?,
        lastTwo: VideoStats?,
        fps: VideoStatsFps?,
        decodeTimeMs: Float,
        rttInfo: Long,
        decoder: String?,
        endToEndTimeMs: Float,
        hdrActive: Boolean,
        streamW: Int,
        streamH: Int,
        isFsrActive: Boolean,
        fsrWeightMsForE2e: Float
    ): Result {
        if (context == null || prefsSnapshot == null || lastTwo == null || fps == null) {
            return Result("", "")
        }

        val e2eTotalMs = endToEndTimeMs + fsrWeightMsForE2e

        // Pre-size StringBuilder based on overlay type to reduce allocations
        val sbCap = when {
            prefsSnapshot.enablePerfOverlayMini -> 96
            prefsSnapshot.enablePerfOverlayLite -> 192
            else -> 384
        }

        val sb = StringBuilder(sbCap)

        if (prefsSnapshot.enablePerfOverlayMini) {
            // Network bandwidth
            if (TrafficStatsHelper.getPackageRxBytes(Process.myUid()) != TrafficStats.UNSUPPORTED.toLong()) {
                val netData = TrafficStatsHelper.getPackageRxBytes(Process.myUid()) +
                    TrafficStatsHelper.getPackageTxBytes(Process.myUid())
                if (lastNetDataNum != 0L) {
                    val realtimeNetData = (netData - lastNetDataNum) / 1024f
                    if (realtimeNetData >= 1000) {
                        sb.append("BW: ").append(String.format(Locale.US, "%.1f", realtimeNetData / 1024f)).append(" M/s\n")
                    } else {
                        sb.append("BW: ").append(String.format(Locale.US, "%.1f", realtimeNetData)).append(" K/s\n")
                    }
                }
                lastNetDataNum = netData
            }

            // Packet loss percentage
            var plPct = 0f
            if (lastTwo.totalFrames > 0) {
                plPct = lastTwo.framesLost.toFloat() / lastTwo.totalFrames.toFloat() * 100f
            }
            sb.append("PL: ").append(String.format(Locale.US, "%.0f", plPct)).append("%\n")

            // Network latency and decode time
            sb.append("Net: ").append((rttInfo shr 32).toInt())
                .append("ms | Dec: ").append(String.format(Locale.US, "%.1f", decodeTimeMs)).append("ms\n")

            sb.append(String.format(Locale.US, "%.2f", fps.totalFps)).append(" FPS")
        } else if (prefsSnapshot.enablePerfOverlayLite) {
            // Network bandwidth with localization
            if (TrafficStatsHelper.getPackageRxBytes(Process.myUid()) != TrafficStats.UNSUPPORTED.toLong()) {
                val netData = TrafficStatsHelper.getPackageRxBytes(Process.myUid()) +
                    TrafficStatsHelper.getPackageTxBytes(Process.myUid())
                if (lastNetDataNum != 0L) {
                    sb.append(context.getString(R.string.perf_overlay_lite_bandwidth)).append(": ")
                    val realtimeNetData = (netData - lastNetDataNum) / 1024f
                    if (realtimeNetData >= 1000) {
                        sb.append(String.format(Locale.US, "%.2f", realtimeNetData / 1024f)).append("M/s\t ")
                    } else {
                        sb.append(String.format(Locale.US, "%.2f", realtimeNetData)).append("K/s\t ")
                    }
                }
                lastNetDataNum = netData
            }

            // Network latency and decode time
            sb.append(context.getString(R.string.perf_overlay_lite_network_decoding_delay)).append(": ")
            sb.append(context.getString(R.string.perf_overlay_lite_net, (rttInfo shr 32).toInt()))
            sb.append(" / ")
            sb.append(context.getString(R.string.perf_overlay_lite_dectime, decodeTimeMs))

            sb.append("\t ")
            // Packet loss percentage
            sb.append(context.getString(R.string.perf_overlay_lite_packet_loss)).append(": ")
            var liteLossPct = 0f
            if (lastTwo.totalFrames > 0) {
                liteLossPct = lastTwo.framesLost.toFloat() / lastTwo.totalFrames.toFloat() * 100f
            }
            sb.append(context.getString(R.string.perf_overlay_lite_netdrops, liteLossPct))

            // Advanced Lite: end-to-end latency
            if (prefsSnapshot.enablePerfOverlayLiteAdvanced) {
                sb.append(" / ")
                sb.append(context.getString(R.string.perf_overlay_lite_e2e, e2eTotalMs))
                sb.append("  ")
            }

            // FPS
            sb.append("\t FPS：")
            sb.append(context.getString(R.string.perf_overlay_lite_fps, fps.totalFps))

            // OLED protection: horizontal shifting
            try {
                if (prefsSnapshot.enablePerfOverlayLiteOledShift) {
                    val now = System.nanoTime()
                    if (now >= liteShiftNextNs) {
                        liteShiftNextNs = now + LITE_SHIFT_PERIOD_NS
                        // Ping-pong shift: 0 → 1 → 2 → 1 → 0
                        liteShiftSpaces = when (liteShiftSpaces) {
                            0 -> 1
                            1 -> 2
                            2 -> 1
                            else -> 0
                        }
                    }
                } else {
                    liteShiftSpaces = 0
                }
            } catch (_: Throwable) {}

            // OLED protection: blinking
            try {
                if (prefsSnapshot.enablePerfOverlayLiteOledShift) {
                    val now = System.nanoTime()
                    if (now >= liteBlinkNextStartNs) {
                        liteBlinkNextStartNs = now + LITE_BLINK_PERIOD_NS
                        liteBlinkEndNs = now + LITE_BLINK_DURATION_NS
                    }
                } else {
                    liteBlinkEndNs = 0L
                }
            } catch (_: Throwable) {}

            // Advanced Lite metrics: received/rendered FPS and HDR status
            if (prefsSnapshot.enablePerfOverlayLiteAdvanced) {
                sb.append("  R:").append(fps.renderedFps.toInt())
                sb.append("  ").append(if (hdrActive) "HDR" else "SDR")
                sb.append(' ').append(getLitePacingGlyph(prefsSnapshot, isFsrActive))
                if (prefsSnapshot.gpuPathMode) {
                    sb.append('G')
                }
            }

            // Server stats (host processing latency, from server) - keep at the end for readability
            if (prefsSnapshot.showServerStats && lastTwo.framesWithHostProcessingLatency > 0) {
                val avgHostMs = (lastTwo.totalHostProcessingLatency.toFloat() / 10f) /
                    lastTwo.framesWithHostProcessingLatency.toFloat()

                // Base format
                sb.append("  | SRV ")
                sb.append(String.format(Locale.US, "%.1f", avgHostMs)).append("ms")

                // Add min/max only when Lite Advanced is enabled
                if (prefsSnapshot.enablePerfOverlayLiteAdvanced) {
                    val minHostMs = lastTwo.minHostProcessingLatency.code.toFloat() / 10f
                    val maxHostMs = lastTwo.maxHostProcessingLatency.code.toFloat() / 10f
                    sb.append(" (")
                    sb.append(String.format(Locale.US, "%.1f", minHostMs)).append("–")
                    sb.append(String.format(Locale.US, "%.1f", maxHostMs)).append("ms)")
                }
            }
        } else {
            // Stream resolution and FPS
            sb.append(
                context.getString(
                    R.string.perf_overlay_streamdetails,
                    "${streamW}x$streamH",
                    fps.totalFps
                )
            )

            sb.append('\n')
            sb.append(context.getString(R.string.perf_overlay_decoder, decoder ?: "")).append('\n')
            sb.append(context.getString(R.string.perf_overlay_incomingfps, fps.receivedFps)).append('\n')
            sb.append(context.getString(R.string.perf_overlay_renderingfps, fps.renderedFps)).append('\n')

            // Packet loss
            var fullLossPct = 0f
            if (lastTwo.totalFrames > 0) {
                fullLossPct = lastTwo.framesLost.toFloat() / lastTwo.totalFrames.toFloat() * 100f
            }
            sb.append(context.getString(R.string.perf_overlay_netdrops, fullLossPct)).append('\n')

            // Network bandwidth
            if (TrafficStatsHelper.getPackageRxBytes(Process.myUid()) != TrafficStats.UNSUPPORTED.toLong()) {
                val netData = TrafficStatsHelper.getPackageRxBytes(Process.myUid()) +
                    TrafficStatsHelper.getPackageTxBytes(Process.myUid())
                if (lastNetDataNum != 0L) {
                    sb.append(context.getString(R.string.perf_overlay_lite_bandwidth)).append(": ")
                    val realtimeNetData = (netData - lastNetDataNum) / 1024f
                    if (realtimeNetData >= 1000) {
                        sb.append(String.format(Locale.US, "%.2f", realtimeNetData / 1024f)).append("M/s\n")
                    } else {
                        sb.append(String.format(Locale.US, "%.2f", realtimeNetData)).append("K/s\n")
                    }
                }
                lastNetDataNum = netData
            }

            // Network latency (min/current)
            sb.append(
                context.getString(
                    R.string.perf_overlay_netlatency,
                    (rttInfo shr 32).toInt(),
                    rttInfo.toInt()
                )
            ).append('\n')

            // Host processing latency stats
            if (lastTwo.framesWithHostProcessingLatency > 0) {
                sb.append(
                    context.getString(
                        R.string.perf_overlay_hostprocessinglatency,
                        lastTwo.minHostProcessingLatency.code.toFloat() / 10f,
                        lastTwo.maxHostProcessingLatency.code.toFloat() / 10f,
                        lastTwo.totalHostProcessingLatency.toFloat() / 10f /
                            lastTwo.framesWithHostProcessingLatency
                    )
                ).append('\n')
            }

            // Decode time and end-to-end latency
            sb.append(context.getString(R.string.perf_overlay_dectime, decodeTimeMs))
            sb.append(context.getString(R.string.perf_overlay_lite_e2e, e2eTotalMs))
        }

        val fullLog = sb.toString()

        // Apply OLED shift (prepend spaces)
        if (prefsSnapshot.enablePerfOverlayLite && prefsSnapshot.enablePerfOverlayLiteOledShift) {
            try {
                sb.insert(0, LITE_SHIFT_PREFIX[min(LITE_SHIFT_MAX_SPACES, max(0, liteShiftSpaces))])
            } catch (_: Throwable) {}
        }

        // Apply OLED blink (clear overlay during blink period)
        if (prefsSnapshot.enablePerfOverlayLite && prefsSnapshot.enablePerfOverlayLiteOledShift) {
            try {
                val now = System.nanoTime()
                if (liteBlinkEndNs > 0L && now < liteBlinkEndNs) {
                    sb.setLength(0)
                    sb.append(' ') // Minimal content for transparent overlay
                }
            } catch (_: Throwable) {}
        }

        val renderedLog = sb.toString()
        return Result(fullLog, renderedLog)
    }

    companion object {
        // Horizontal shift interval to prevent OLED burn-in on the Lite overlay.
        private const val LITE_SHIFT_PERIOD_NS = 30_000_000_000L
        private const val LITE_SHIFT_MAX_SPACES = 8

        // Blank the Lite overlay for 250ms every 5 minutes to let OLED pixels rest.
        private const val LITE_BLINK_PERIOD_NS = 300_000_000_000L
        private const val LITE_BLINK_DURATION_NS = 250_000_000L

        // Cached prefixes for Lite overlay OLED shift (0..LITE_SHIFT_MAX_SPACES)
        private val LITE_SHIFT_PREFIX = Array(LITE_SHIFT_MAX_SPACES + 1) { i ->
            " ".repeat(i)
        }

        // Lite pacing glyph
        private fun getLitePacingGlyph(p: PreferenceConfiguration?, isFsrActive: Boolean): String {
            if (p == null) {
                return "?"
            }

            val glyph = StringBuilder(3)

            when (p.framePacing) {
                PreferenceConfiguration.FRAME_PACING_GPU_RAW -> glyph.append('R')
                PreferenceConfiguration.FRAME_PACING_MIN_LATENCY -> glyph.append('L')
                PreferenceConfiguration.FRAME_PACING_BALANCED -> glyph.append('B')
                PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS -> glyph.append('S')
                PreferenceConfiguration.FRAME_PACING_CAP_FPS -> glyph.append('C')
                PreferenceConfiguration.FRAME_PACING_WARP -> glyph.append('W')
                PreferenceConfiguration.FRAME_PACING_WARP2 -> glyph.append('2')
                else -> glyph.append('?')
            }
            // Async decode glyph
            if (p.asyncDecodeEnabled) {
                glyph.append('A')
            }

            if (isFsrActive) {
                glyph.append('U')
            }

            if (p.enableVsync && p.framePacing != PreferenceConfiguration.FRAME_PACING_BALANCED) {
                glyph.append('V')
            }

            if (p.fastVsync) {
                glyph.append('F')
            }

            return glyph.toString()
        }
    }
}
