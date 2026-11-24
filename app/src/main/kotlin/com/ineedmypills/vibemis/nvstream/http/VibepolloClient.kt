package com.ineedmypills.vibemis.nvstream.http

import com.ineedmypills.vibemis.nvstream.jni.VibemisBridge
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException

/**
 * Vibepollo host capabilities and protocol helpers (Paired-Client Sleep v1, On-Demand Hardware
 * Metrics v1, PyroWave GPU Wavelet Codec, DualSense Haptics/Speaker PCM).
 *
 * Compatible with https://github.com/Nonary/Vibepollo
 */
data class VibepolloCapabilities(
    @JvmField val powerActionVersion: Int = 0,
    @JvmField val sleepSupported: Boolean = false,
    @JvmField val sleepAllowed: Boolean = false,
    @JvmField val hardwareMetricsVersion: Int = 0,
    @JvmField val frameLimiterSupported: Boolean = false,
    @JvmField val frameLimiterEnabled: Boolean = false,
    @JvmField val virtualDisplayFrameLimiterEnabled: Boolean = false,
    @JvmField val frameLimiterFpsLimitMilliHz: Int = 0,
    @JvmField val pyroWaveCodecMask: Long = 0L,
    @JvmField val pyroWaveHostLinkMbps: Int = 0,
    @JvmField val pyroWaveBandwidthProbeBytes: Int = 0,
    @JvmField val pyroWaveCriticalFecPercentage: Int = 0
) {
    val canPutHostToSleep: Boolean
        get() = powerActionVersion >= 1 && sleepSupported && sleepAllowed

    val supportsHardwareMetrics: Boolean
        get() = hardwareMetricsVersion >= 1

    val supportsPyroWave: Boolean
        get() = (pyroWaveCodecMask and SCM_MASK_PYROWAVE) != 0L

    val supportsPyroWaveHdr10: Boolean
        get() = (pyroWaveCodecMask and (SCM_PYROWAVE_HDR10 or SCM_PYROWAVE_HDR10_444)) != 0L

    val supportsPyroWave444: Boolean
        get() = (pyroWaveCodecMask and (SCM_PYROWAVE_444 or SCM_PYROWAVE_HDR10_444)) != 0L

    companion object {
        const val SCM_PYROWAVE = 0x00800000L
        const val SCM_PYROWAVE_444 = 0x01000000L
        const val SCM_PYROWAVE_HDR10 = 0x02000000L
        const val SCM_PYROWAVE_HDR10_444 = 0x04000000L
        const val SCM_MASK_PYROWAVE =
            SCM_PYROWAVE or SCM_PYROWAVE_444 or SCM_PYROWAVE_HDR10 or SCM_PYROWAVE_HDR10_444

        @JvmStatic
        @Throws(IOException::class, XmlPullParserException::class)
        fun parseFromServerInfo(serverInfoXml: String): VibepolloCapabilities {
            return fromParsedXml(NvHTTP.parseXmlSinglePass(serverInfoXml))
        }

        @JvmStatic
        fun fromParsedXml(parsed: NvHTTP.ParsedXmlDocument): VibepolloCapabilities {
            val powerVersion = parsed.firstValue("PowerActionVersion")?.toIntOrNull() ?: 0
            val sleepSupported = parsed.firstValue("SleepSupported") == "1"
            val sleepAllowed = parsed.firstValue("SleepAllowed") == "1"
            val hardwareMetricsVersion = parsed.firstValue("HardwareMetricsVersion")?.toIntOrNull() ?: 0
            val frameLimiterSupported = parsed.firstValue("FrameLimiterSupported") == "1"
            val frameLimiterEnabled = parsed.firstValue("FrameLimiterEnabled") == "1"
            val virtualDisplayFrameLimiterEnabled =
                parsed.firstValue("VirtualDisplayFrameLimiterEnabled") == "1"
            val frameLimiterFpsLimitMilliHz =
                parsed.firstValue("FrameLimiterFpsLimitMilliHz")?.toIntOrNull() ?: 0

            val codecSupport = parsed.firstValue("ServerCodecModeSupport")?.toLongOrNull() ?: 0L
            val pyroWaveMask = codecSupport and SCM_MASK_PYROWAVE

            val hostLinkMbps = parsed.firstValue("PyroWaveHostLinkMbps")?.toIntOrNull() ?: 0
            val probeBytes = parsed.firstValue("PyroWaveBandwidthProbeBytes")?.toIntOrNull() ?: 0
            val criticalFecPct = parsed.firstValue("PyroWaveCriticalFecPercentage")?.toIntOrNull() ?: 0

            return VibepolloCapabilities(
                powerActionVersion = powerVersion,
                sleepSupported = sleepSupported,
                sleepAllowed = sleepAllowed,
                hardwareMetricsVersion = hardwareMetricsVersion,
                frameLimiterSupported = frameLimiterSupported,
                frameLimiterEnabled = frameLimiterEnabled,
                virtualDisplayFrameLimiterEnabled = virtualDisplayFrameLimiterEnabled,
                frameLimiterFpsLimitMilliHz = frameLimiterFpsLimitMilliHz,
                pyroWaveCodecMask = pyroWaveMask,
                pyroWaveHostLinkMbps = hostLinkMbps,
                pyroWaveBandwidthProbeBytes = probeBytes,
                pyroWaveCriticalFecPercentage = criticalFecPct
            )
        }
    }
}

data class HardwareMetricsSnapshot(
    val version: Int = 0,
    val sampledAtUnixMs: Long = 0L,
    val intervalMs: Int = 0,
    val cpuUsagePercent: Double? = null,
    val gpuName: String? = null,
    val gpuUsagePercent: Double? = null,
    val gpuEncoderPercent: Double? = null,
    val gpuTemperatureC: Double? = null,
    val gpuVramUsedMb: Double? = null,
    val gpuVramUsagePercent: Double? = null,
    val errorMessage: String? = null
)

object VibepolloClient {
    private fun JSONObject.optFiniteDouble(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val v = optDouble(key, Double.NaN)
        return if (v.isFinite()) v else null
    }

    /**
     * Fetches on-demand Vibepollo hardware telemetry (`GET /api/hardware-metrics`) over paired HTTPS.
     */
    @JvmStatic
    fun fetchHardwareMetrics(nvHttp: NvHTTP): HardwareMetricsSnapshot {
        return try {
            val jsonStr = nvHttp.executePairedHttpsGet(
                "api/hardware-metrics",
                null,
                appendClientIdentity = false
            )
            val root = JSONObject(jsonStr)
            val cpuObj = root.optJSONObject("cpu")
            val gpusArr = root.optJSONArray("gpus")
            val firstGpu = if (gpusArr != null && gpusArr.length() > 0) gpusArr.optJSONObject(0) else null
            val gpuMetrics = firstGpu?.optJSONObject("metrics")

            HardwareMetricsSnapshot(
                version = root.optInt("version", 1),
                sampledAtUnixMs = root.optLong("sampled_at_unix_ms", 0L),
                intervalMs = root.optInt("interval_ms", 0),
                cpuUsagePercent = cpuObj?.optFiniteDouble("cpu_usage_percent"),
                gpuName = firstGpu?.optString("name")?.takeIf { it.isNotBlank() },
                gpuUsagePercent = gpuMetrics?.optFiniteDouble("gpu_usage_percent"),
                gpuEncoderPercent = gpuMetrics?.optFiniteDouble("gpu_encoder_percent"),
                gpuTemperatureC = gpuMetrics?.optFiniteDouble("gpu_temperature_c"),
                gpuVramUsedMb = gpuMetrics?.optFiniteDouble("gpu_vram_used_mb"),
                gpuVramUsagePercent = gpuMetrics?.optFiniteDouble("gpu_vram_usage_percent"),
                errorMessage = null
            )
        } catch (e: HostHttpResponseException) {
            HardwareMetricsSnapshot(errorMessage = e.errorMessage ?: e.message)
        } catch (e: Exception) {
            HardwareMetricsSnapshot(errorMessage = e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Executes the Vibepollo Paired-Client Sleep Protocol v1:
     * 1. GET /power-actions/v1 over paired HTTPS to obtain a one-time SleepToken (valid 30s).
     * 2. POST /power-actions/v1/sleep?token=<SleepToken> with an empty body and exact 1-arg query.
     *
     * @return true if the host accepted the sleep request (`SleepAccepted == 1`).
     */
    @JvmStatic
    @Throws(IOException::class, XmlPullParserException::class)
    fun requestHostSleep(nvHttp: NvHTTP): Boolean {
        val capabilityXml = nvHttp.executePairedHttpsGet(
            "power-actions/v1",
            null,
            appendClientIdentity = false
        )
        val sleepAllowed = NvHTTP.getXmlString(capabilityXml, "SleepAllowed", false) == "1"
        val sleepToken = NvHTTP.getXmlString(capabilityXml, "SleepToken", false)
        if (!sleepAllowed || sleepToken.isNullOrBlank()) {
            return false
        }

        val emptyBody = ByteArray(0).toRequestBody(null)
        val responseXml = nvHttp.executePairedHttpsPost(
            "power-actions/v1/sleep",
            "token=$sleepToken",
            emptyBody,
            appendClientIdentity = false
        )
        return NvHTTP.getXmlString(responseXml, "SleepAccepted", false) == "1"
    }

    /**
     * Binds the active DualSense controller number for Vibepollo controller speaker PCM & haptics.
     */
    @JvmStatic
    fun bindDualSenseSpeakerOwner(controllerNumber: Short): Int {
        return VibemisBridge.sendControllerSpeakerOwner(controllerNumber)
    }
}
