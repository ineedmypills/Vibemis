package com.ineedmypills.vibemis.binding.video

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ConfigurationInfo
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import com.ineedmypills.vibemis.VibemisLog
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.util.Collections
import java.util.LinkedList
import java.util.Locale
import java.util.regex.Pattern

object MediaCodecHelper {
    private val preferredDecoders = LinkedList<String>()
    private val blacklistedDecoderPrefixes = LinkedList<String>()
    private val spsFixupBitstreamFixupDecoderPrefixes = LinkedList<String>()
    private val blacklistedAdaptivePlaybackPrefixes = LinkedList<String>()
    private val baselineProfileHackPrefixes = LinkedList<String>()
    private val directSubmitPrefixes = LinkedList<String>()
    private val constrainedHighProfilePrefixes = LinkedList<String>()
    private val whitelistedHevcDecoders = LinkedList<String>()
    private val refFrameInvalidationAvcPrefixes = LinkedList<String>()
    private val refFrameInvalidationHevcPrefixes = LinkedList<String>()
    private val useFourSlicesPrefixes = LinkedList<String>()
    private val qualcommDecoderPrefixes = LinkedList<String>()
    private val tegraDecoderPrefixes = LinkedList<String>()
    private val mtkDecoderPrefixes = LinkedList<String>()
    private val kirinDecoderPrefixes = LinkedList<String>()
    private val exynosDecoderPrefixes = LinkedList<String>()
    private val amlogicDecoderPrefixes = LinkedList<String>()
    private val knownVendorLowLatencyOptions = LinkedList<String>()

    @JvmField
    val SHOULD_BYPASS_SOFTWARE_BLOCK: Boolean =
        Build.HARDWARE == "ranchu" || Build.HARDWARE == "cheets" || Build.BRAND == "Android-x86"

    private var isLowEndSnapdragon = false
    private var isAdreno620 = false
    private var initialized = false

    init {
        directSubmitPrefixes.addAll(
            listOf(
                "omx.qcom",
                "omx.sec",
                "omx.exynos",
                "omx.intel",
                "omx.brcm",
                "omx.TI",
                "omx.arc",
                "omx.nvidia",
                "c2."
            )
        )

        refFrameInvalidationHevcPrefixes.add("omx.exynos")
        refFrameInvalidationHevcPrefixes.add("c2.exynos")

        if (!SHOULD_BYPASS_SOFTWARE_BLOCK) {
            blacklistedDecoderPrefixes.add("omx.google")
            blacklistedDecoderPrefixes.add("AVCDecoder")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                blacklistedDecoderPrefixes.add("OMX.ffmpeg")
            }
        }
        blacklistedDecoderPrefixes.add("OMX.qcom.video.decoder.hevcswvdec")
        blacklistedDecoderPrefixes.add("OMX.SEC.hevc.sw.dec")

        spsFixupBitstreamFixupDecoderPrefixes.addAll(listOf("omx.nvidia", "omx.qcom", "omx.brcm"))
        baselineProfileHackPrefixes.add("omx.intel")
        blacklistedAdaptivePlaybackPrefixes.addAll(listOf("omx.intel", "omx.mtk"))
        constrainedHighProfilePrefixes.add("omx.intel")

        if (Build.HARDWARE == "ranchu") {
            whitelistedHevcDecoders.add("omx.google")
        }
        whitelistedHevcDecoders.add("omx.exynos")

        if (!Build.DEVICE.equals("shieldtablet", ignoreCase = true) &&
            !Build.DEVICE.equals("mocha", ignoreCase = true)
        ) {
            whitelistedHevcDecoders.add("omx.nvidia")
        }

        if (Build.DEVICE.startsWith("BRAVIA_")) {
            whitelistedHevcDecoders.add("omx.mtk")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !Build.DEVICE.equals("sabrina", ignoreCase = true)) {
            whitelistedHevcDecoders.add("omx.amlogic")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            whitelistedHevcDecoders.add("omx.realtek")
        }

        whitelistedHevcDecoders.add("c2.")

        useFourSlicesPrefixes.addAll(
            listOf("omx.google", "AVCDecoder", "omx.ffmpeg", "c2.android")
        )

        knownVendorLowLatencyOptions.addAll(
            listOf(
                "vendor.qti-ext-dec-low-latency.enable",
                "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req",
                "vendor.rtc-ext-dec-low-latency.enable",
                "vendor.low-latency.enable"
            )
        )

        qualcommDecoderPrefixes.addAll(listOf("omx.qcom", "c2.qti", "c2.qcom"))
        tegraDecoderPrefixes.addAll(listOf("omx.nvidia", "c2.nvidia"))
        mtkDecoderPrefixes.addAll(listOf("omx.mtk", "c2.mtk"))
        kirinDecoderPrefixes.addAll(listOf("omx.hisi", "c2.hisi"))
        exynosDecoderPrefixes.addAll(listOf("omx.exynos", "c2.exynos"))
        amlogicDecoderPrefixes.addAll(listOf("omx.amlogic", "c2.amlogic"))
    }

    @JvmStatic
    fun isNvidiaDecoder(decoderName: String): Boolean =
        isDecoderInList(tegraDecoderPrefixes, decoderName)

    @JvmStatic
    fun isQualcommDecoder(decoderName: String): Boolean =
        isDecoderInList(qualcommDecoderPrefixes, decoderName)

    private fun isPowerVR(glRenderer: String): Boolean =
        glRenderer.lowercase(Locale.US).contains("powervr")

    private fun getAdrenoVersionString(glRenderer: String): String? {
        val lower = glRenderer.lowercase(Locale.US).trim()
        if (!lower.contains("adreno")) return null
        val modelNumberPattern = Pattern.compile("(.*)([0-9]{3})(.*)")
        val matcher = modelNumberPattern.matcher(lower)
        if (!matcher.matches()) return null
        val modelNumber = matcher.group(2)
        VibemisLog.info("Found Adreno GPU: $modelNumber")
        return modelNumber
    }

    private fun isLowEndSnapdragonRenderer(glRenderer: String): Boolean {
        val modelNumber = getAdrenoVersionString(glRenderer) ?: return false
        return modelNumber[1] == '0'
    }

    private fun getAdrenoRendererModelNumber(glRenderer: String): Int {
        val modelNumber = getAdrenoVersionString(glRenderer) ?: return -1
        return modelNumber.toInt()
    }

    private fun isGLES31SnapdragonRenderer(glRenderer: String): Boolean =
        getAdrenoRendererModelNumber(glRenderer) >= 400

    @JvmStatic
    fun initialize(context: Context?, glRenderer: String?) {
        if (initialized || context == null) return
        val renderer = glRenderer ?: ""

        if (context.packageManager.hasSystemFeature("amazon.hardware.fire_tv") ||
            Build.MANUFACTURER.equals("Amazon", ignoreCase = true)
        ) {
            whitelistedHevcDecoders.add("omx.mtk")
            refFrameInvalidationHevcPrefixes.add("omx.mtk")
            refFrameInvalidationHevcPrefixes.add("c2.mtk")
            whitelistedHevcDecoders.add("omx.amlogic")
            refFrameInvalidationHevcPrefixes.add("omx.amlogic")
            refFrameInvalidationHevcPrefixes.add("c2.amlogic")
        }

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val configInfo = activityManager.deviceConfigurationInfo
        if (configInfo.reqGlEsVersion != ConfigurationInfo.GL_ES_VERSION_UNDEFINED) {
            VibemisLog.info("OpenGL ES version: ${configInfo.reqGlEsVersion}")

            isLowEndSnapdragon = isLowEndSnapdragonRenderer(renderer)
            isAdreno620 = getAdrenoRendererModelNumber(renderer) == 620

            if (configInfo.reqGlEsVersion >= 0x30000) {
                VibemisLog.info("Added omx.nvidia/c2.nvidia to reference frame invalidation support list")
                refFrameInvalidationAvcPrefixes.add("omx.nvidia")

                if (!Build.DEVICE.equals("dragon", ignoreCase = true) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    refFrameInvalidationHevcPrefixes.add("omx.nvidia")
                }

                refFrameInvalidationAvcPrefixes.add("c2.nvidia")
                refFrameInvalidationHevcPrefixes.add("c2.nvidia")

                VibemisLog.info("Added omx.qcom/c2.qti to reference frame invalidation support list")
                refFrameInvalidationAvcPrefixes.addAll(
                    listOf("omx.qcom", "c2.qti", "c2.mtk", "omx.mtk", "omx.amlogic", "c2.amlogic")
                )
                refFrameInvalidationHevcPrefixes.addAll(
                    listOf("omx.qcom", "c2.qti", "c2.mtk", "omx.mtk", "c2.qcom", "omx.amlogic", "c2.amlogic")
                )
            }

            if (isGLES31SnapdragonRenderer(renderer)) {
                VibemisLog.info("Added omx.qcom/c2.qti to HEVC decoders based on GLES 3.1+ support")
                whitelistedHevcDecoders.add("omx.qcom")
                whitelistedHevcDecoders.add("c2.qti")
            } else {
                blacklistedDecoderPrefixes.add("OMX.qcom.video.decoder.hevc")
                useFourSlicesPrefixes.add("omx.qcom")
            }

            if (isPowerVR(renderer)) {
                VibemisLog.info("Added omx.mtk to HEVC decoders based on PowerVR GPU")
                whitelistedHevcDecoders.add("omx.mtk")
                if (renderer.contains("GX6")) {
                    VibemisLog.info("Added omx.mtk/c2.mtk to RFI list for HEVC")
                    refFrameInvalidationHevcPrefixes.add("omx.mtk")
                    refFrameInvalidationHevcPrefixes.add("c2.mtk")
                }
            }
        }

        initialized = true
    }

    private fun isDecoderInList(decoderList: List<String>, decoderName: String): Boolean {
        check(initialized) { "MediaCodecHelper must be initialized before use" }
        for (badPrefix in decoderList) {
            if (decoderName.length >= badPrefix.length) {
                val prefix = decoderName.substring(0, badPrefix.length)
                if (prefix.equals(badPrefix, ignoreCase = true)) {
                    return true
                }
            }
        }
        return false
    }

    private fun decoderSupportsAndroidRLowLatency(decoderInfo: MediaCodecInfo, mimeType: String?): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mimeType != null) {
            try {
                if (decoderInfo.getCapabilitiesForType(mimeType).isFeatureSupported(CodecCapabilities.FEATURE_LowLatency)) {
                    VibemisLog.info("Low latency decoding mode supported (FEATURE_LowLatency)")
                    return true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return false
    }

    private fun decoderSupportsKnownVendorLowLatencyOption(decoderName: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            var testCodec: MediaCodec? = null
            try {
                testCodec = MediaCodec.createByCodecName(decoderName)
                for (supportedOption in testCodec.supportedVendorParameters) {
                    for (knownLowLatencyOption in knownVendorLowLatencyOptions) {
                        if (supportedOption.equals(knownLowLatencyOption, ignoreCase = true)) {
                            VibemisLog.info("$decoderName supports known low latency option: $supportedOption")
                            return true
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                testCodec?.release()
            }
        }
        return false
    }

    private fun decoderSupportsMaxOperatingRate(decoderName: String): Boolean {
        return (
            isDecoderInList(qualcommDecoderPrefixes, decoderName) ||
            isDecoderInList(refFrameInvalidationHevcPrefixes, decoderName) ||
            isDecoderInList(refFrameInvalidationAvcPrefixes, decoderName)
        ) && !isAdreno620
    }

    @JvmStatic
    fun setDecoderLowLatencyOptions(
        videoFormat: MediaFormat,
        decoderInfo: MediaCodecInfo,
        ultraLowLatency: Boolean,
        tryNumber: Int
    ): Boolean {
        var setNewOption = false

        if (isNvidiaDecoder(decoderInfo.name)) {
            safeSet(videoFormat, "media.low-latency.enable", 1)
            safeSet(videoFormat, "vendor.low-latency.enable", 1)
            safeSet(videoFormat, "disable-output-reorder", 1)
            safeSet(videoFormat, "vendor.nvidia.disable-output-reorder", 1)
            setNewOption = true
        }

        if (tryNumber < 1) {
            videoFormat.setInteger("low-latency", 1)
            setNewOption = true
            if (!ultraLowLatency && decoderSupportsAndroidRLowLatency(decoderInfo, videoFormat.getString(MediaFormat.KEY_MIME))) {
                return true
            }
        }

        if (tryNumber < 2) {
            videoFormat.setInteger("vdec-lowlatency", 1)
            setNewOption = true
        }

        if (tryNumber < 3) {
            if (decoderSupportsMaxOperatingRate(decoderInfo.name)) {
                videoFormat.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
            } else {
                videoFormat.setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            setNewOption = true
        }

        val decName = decoderInfo.name
        if (isDecoderInList(qualcommDecoderPrefixes, decName)) {
            if (tryNumber < 4) {
                val isOmxQcom = decName.lowercase(Locale.US).startsWith("omx.qcom")
                safeSet(videoFormat, "vendor.qti-ext-dec-picture-order.enable", if (isOmxQcom) 0 else 1)
                setNewOption = true
            }
            if (tryNumber < 5) {
                videoFormat.setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
                videoFormat.setInteger("vendor.qti-ext-output-sw-fence-enable.value", 1)
                videoFormat.setInteger("vendor.qti-ext-output-fence.enable", 1)
                videoFormat.setInteger("vendor.qti-ext-output-fence.fence_type", 1)
                setNewOption = true
            }
        } else if (isDecoderInList(mtkDecoderPrefixes, decName)) {
            when (tryNumber) {
                0 -> {
                    setNewOption = true
                    VibemisLog.info("Applying MTK decoder preset 0 to $decName")
                    safeSet(videoFormat, MediaFormat.KEY_LOW_LATENCY, 1)
                    safeSet(videoFormat, "vdec-lowlatency", 1)
                    safeSet(videoFormat, "media.low-latency.enable", 1)
                    safeSet(videoFormat, "vendor.low-latency.enable", 1)
                    safeSet(videoFormat, "vendor.mtk.vdec.low-latency.mode", 1)
                    safeSet(videoFormat, "vendor.mtk.vdec.disable-idle", 1)
                    safeSet(videoFormat, "vendor.mtk.vdec.vsync.adjust.enable", 0)
                    if (ultraLowLatency) {
                        safeSet(videoFormat, "vendor.mtk.vdec.ultra-low-latency", 1)
                    }
                    safeSet(videoFormat, "vendor.mtk.vdec.preload.frame.count", 0)
                    safeSet(videoFormat, "vendor.mtk.vdec.input.max.queue.depth", 2)
                    safeSet(videoFormat, "vendor.mtk.vdec.output.max.queue.depth", 2)
                    safeSet(videoFormat, "vendor.mtk.vdec.buffer.fetch.timeout.ms", 2)
                    safeSet(videoFormat, "vendor.mtk.vdec.buffer.fetch.timeout.ms.value", 2)
                    safeSet(videoFormat, "vendor.mtk.vdec.bq.guard.interval.time", 2)
                    safeSet(videoFormat, "vendor.mtk.vdec.bq.guard.interval.time.value", 2)
                    safeSet(videoFormat, "vendor.mtk.vdec.cpu.boost.mode", 1)
                    safeSet(videoFormat, "vendor.mtk.vdec.cpu.boost.mode.value", 1)
                    safeSet(videoFormat, "vendor.mtk.vdec.dvfs.mode", 1)
                    safeSet(videoFormat, "vendor.mtk.vdec.dvfs.level", 1)
                    safeSet(videoFormat, MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
                    safeSet(videoFormat, MediaFormat.KEY_PRIORITY, 0)
                }
                1 -> {
                    setNewOption = true
                    VibemisLog.info("Applying MTK decoder preset 1 to $decName")
                    safeSet(videoFormat, MediaFormat.KEY_LOW_LATENCY, 1)
                    safeSet(videoFormat, "vdec-lowlatency", 1)
                    safeSet(videoFormat, MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
                    safeSet(videoFormat, MediaFormat.KEY_PRIORITY, 0)
                }
                2 -> {
                    setNewOption = true
                    VibemisLog.info("Applying MTK decoder preset 2 to $decName")
                    safeSet(videoFormat, MediaFormat.KEY_LOW_LATENCY, 1)
                    safeSet(videoFormat, MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
                    safeSet(videoFormat, MediaFormat.KEY_PRIORITY, 0)
                }
                3 -> {
                    setNewOption = true
                    VibemisLog.info("Applying MTK decoder preset 3 to $decName")
                    safeSet(videoFormat, MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
                    safeSet(videoFormat, MediaFormat.KEY_PRIORITY, 0)
                }
            }
        } else if (isDecoderInList(kirinDecoderPrefixes, decName)) {
            if (tryNumber < 4) {
                videoFormat.setInteger("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req", 1)
                videoFormat.setInteger("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy", -1)
                setNewOption = true
            }
        } else if (isDecoderInList(exynosDecoderPrefixes, decName)) {
            if (tryNumber < 4) {
                videoFormat.setInteger("vendor.rtc-ext-dec-low-latency.enable", 1)
                setNewOption = true
            }
        } else if (isDecoderInList(amlogicDecoderPrefixes, decName)) {
            if (tryNumber < 4) {
                videoFormat.setInteger("vendor.low-latency.enable", 1)
                setNewOption = true
            }
        }

        try {
            val dn = decName.lowercase(Locale.US)
            if (isNvidiaDecoder(decName) || isQualcommDecoder(decName) || dn.startsWith("omx.qcom")) {
                applyExtraVendorOptions(videoFormat, decName)
                setNewOption = true
            }
        } catch (_: Throwable) {}

        return setNewOption
    }

    @JvmStatic
    fun decoderSupportsFusedIdrFrame(decoderInfo: MediaCodecInfo, mimeType: String): Boolean {
        try {
            if (decoderInfo.getCapabilitiesForType(mimeType).isFeatureSupported(CodecCapabilities.FEATURE_AdaptivePlayback)) {
                VibemisLog.info("Decoder supports fused IDR frames (FEATURE_AdaptivePlayback)")
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    @JvmStatic
    fun decoderSupportsAdaptivePlayback(decoderInfo: MediaCodecInfo, mimeType: String): Boolean {
        if (isDecoderInList(blacklistedAdaptivePlaybackPrefixes, decoderInfo.name)) {
            VibemisLog.info("Decoder blacklisted for adaptive playback")
            return false
        }
        try {
            if (decoderInfo.getCapabilitiesForType(mimeType).isFeatureSupported(CodecCapabilities.FEATURE_AdaptivePlayback)) {
                VibemisLog.info("Adaptive playback supported (FEATURE_AdaptivePlayback)")
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    @JvmStatic
    fun decoderNeedsConstrainedHighProfile(decoderName: String): Boolean =
        isDecoderInList(constrainedHighProfilePrefixes, decoderName)

    @JvmStatic
    fun decoderCanDirectSubmit(decoderName: String): Boolean =
        isDecoderInList(directSubmitPrefixes, decoderName) && !isExynos4Device()

    @JvmStatic
    fun decoderNeedsSpsBitstreamRestrictions(decoderName: String): Boolean =
        isDecoderInList(spsFixupBitstreamFixupDecoderPrefixes, decoderName)

    @JvmStatic
    fun decoderNeedsBaselineSpsHack(decoderName: String): Boolean =
        isDecoderInList(baselineProfileHackPrefixes, decoderName)

    @JvmStatic
    fun getDecoderOptimalSlicesPerFrame(decoderName: String): Byte =
        if (isDecoderInList(useFourSlicesPrefixes, decoderName)) 4 else 1

    @JvmStatic
    fun decoderSupportsRefFrameInvalidationAvc(decoderName: String, videoHeight: Int): Boolean {
        if (videoHeight > 720 && isLowEndSnapdragon) return false
        if (Build.DEVICE == "b3" || Build.DEVICE == "b5") return false
        return isDecoderInList(refFrameInvalidationAvcPrefixes, decoderName)
    }

    @JvmStatic
    fun decoderSupportsRefFrameInvalidationHevc(decoderInfo: MediaCodecInfo): Boolean {
        if (decoderSupportsAndroidRLowLatency(decoderInfo, "video/hevc") ||
            decoderSupportsKnownVendorLowLatencyOption(decoderInfo.name)
        ) {
            VibemisLog.info("Enabling HEVC RFI based on low latency option support")
            return true
        }
        return isDecoderInList(refFrameInvalidationHevcPrefixes, decoderInfo.name)
    }

    @JvmStatic
    fun decoderSupportsRefFrameInvalidationAv1(decoderInfo: MediaCodecInfo): Boolean {
        if (decoderSupportsAndroidRLowLatency(decoderInfo, "video/av01") ||
            decoderSupportsKnownVendorLowLatencyOption(decoderInfo.name)
        ) {
            VibemisLog.info("Enabling AV1 RFI based on low latency option support")
            return true
        }
        return false
    }

    @JvmStatic
    fun decoderIsWhitelistedForHevc(decoderInfo: MediaCodecInfo): Boolean {
        if (decoderInfo.name.contains("sw")) {
            VibemisLog.info("Disallowing HEVC on software decoder: ${decoderInfo.name}")
            return false
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            (!decoderInfo.isHardwareAccelerated || decoderInfo.isSoftwareOnly)
        ) {
            VibemisLog.info("Disallowing HEVC on software decoder: ${decoderInfo.name}")
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibemisLog.info("Media performance class: ${Build.VERSION.MEDIA_PERFORMANCE_CLASS}")
            if (Build.VERSION.MEDIA_PERFORMANCE_CLASS >= Build.VERSION_CODES.S) {
                VibemisLog.info("Allowing HEVC based on media performance class")
                return true
            }
        }

        if (decoderSupportsAndroidRLowLatency(decoderInfo, "video/hevc")) {
            VibemisLog.info("Allowing HEVC based on FEATURE_LowLatency support")
            return true
        }

        return isDecoderInList(whitelistedHevcDecoders, decoderInfo.name)
    }

    @JvmStatic
    fun isDecoderWhitelistedForAv1(decoderInfo: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        if (decoderInfo.name.contains("sw")) {
            VibemisLog.info("Disallowing AV1 on software decoder: ${decoderInfo.name}")
            return false
        } else if (!decoderInfo.isHardwareAccelerated || decoderInfo.isSoftwareOnly) {
            VibemisLog.info("Disallowing AV1 on software decoder: ${decoderInfo.name}")
            return false
        }
        return true
    }

    private fun getMediaCodecList(): LinkedList<MediaCodecInfo> {
        val infoList = LinkedList<MediaCodecInfo>()
        val mcl = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        Collections.addAll(infoList, *mcl.codecInfos)
        return infoList
    }

    @JvmStatic
    @Throws(Exception::class)
    fun dumpDecoders(): String {
        val sb = StringBuilder()
        for (codecInfo in getMediaCodecList()) {
            if (codecInfo.isEncoder) continue
            sb.append("Decoder: ").append(codecInfo.name).append("\n")
            for (type in codecInfo.supportedTypes) {
                sb.append("\t").append(type).append("\n")
                val caps = codecInfo.getCapabilitiesForType(type)
                for (profile in caps.profileLevels) {
                    sb.append("\t\t").append(profile.profile).append(" ").append(profile.level).append("\n")
                }
            }
        }
        return sb.toString()
    }

    private fun findPreferredDecoder(): MediaCodecInfo? {
        check(initialized) { "MediaCodecHelper must be initialized before use" }
        for (preferredDecoder in preferredDecoders) {
            for (codecInfo in getMediaCodecList()) {
                if (codecInfo.isEncoder) continue
                if (preferredDecoder.equals(codecInfo.name, ignoreCase = true)) {
                    VibemisLog.info("Preferred decoder choice is ${codecInfo.name}")
                    return codecInfo
                }
            }
        }
        return null
    }

    private fun isCodecBlacklisted(codecInfo: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (!SHOULD_BYPASS_SOFTWARE_BLOCK && codecInfo.isSoftwareOnly) {
                VibemisLog.info("Skipping software-only decoder: ${codecInfo.name}")
                return true
            }
        }
        if (isDecoderInList(blacklistedDecoderPrefixes, codecInfo.name)) {
            VibemisLog.info("Skipping blacklisted decoder: ${codecInfo.name}")
            return true
        }
        return false
    }

    @JvmStatic
    fun findFirstDecoder(mimeType: String): MediaCodecInfo? {
        for (codecInfo in getMediaCodecList()) {
            if (codecInfo.isEncoder) continue
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && codecInfo.isAlias) continue
            for (mime in codecInfo.supportedTypes) {
                if (mime.equals(mimeType, ignoreCase = true)) {
                    if (isCodecBlacklisted(codecInfo)) continue
                    VibemisLog.info("First decoder choice is ${codecInfo.name}")
                    return codecInfo
                }
            }
        }
        return null
    }

    @JvmStatic
    fun findProbableSafeDecoder(mimeType: String, requiredProfile: Int): MediaCodecInfo? {
        val info = findPreferredDecoder()
        if (info != null) return info
        return try {
            findKnownSafeDecoder(mimeType, requiredProfile)
        } catch (_: Exception) {
            findFirstDecoder(mimeType)
        }
    }

    @Throws(Exception::class)
    private fun findKnownSafeDecoder(mimeType: String, requiredProfile: Int): MediaCodecInfo? {
        for (i in 0 until 2) {
            for (codecInfo in getMediaCodecList()) {
                if (codecInfo.isEncoder) continue
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && codecInfo.isAlias) continue
                for (mime in codecInfo.supportedTypes) {
                    if (mime.equals(mimeType, ignoreCase = true)) {
                        VibemisLog.info("Examining decoder capabilities of ${codecInfo.name} (round ${i + 1})")
                        if (isCodecBlacklisted(codecInfo)) continue
                        val caps = codecInfo.getCapabilitiesForType(mime)
                        if (i == 0 && !decoderSupportsAndroidRLowLatency(codecInfo, mime)) {
                            VibemisLog.info("Skipping decoder that lacks FEATURE_LowLatency for round 1")
                            continue
                        }
                        if (requiredProfile != -1) {
                            for (profile in caps.profileLevels) {
                                if (profile.profile == requiredProfile) {
                                    VibemisLog.info("Decoder ${codecInfo.name} supports required profile")
                                    return codecInfo
                                }
                            }
                            VibemisLog.info("Decoder ${codecInfo.name} does NOT support required profile")
                        } else {
                            return codecInfo
                        }
                    }
                }
            }
        }
        return null
    }

    @JvmStatic
    @Throws(Exception::class)
    fun readCpuinfo(): String {
        val cpuInfo = StringBuilder()
        BufferedReader(FileReader(File("/proc/cpuinfo"))).use { br ->
            while (true) {
                val ch = br.read()
                if (ch == -1) break
                cpuInfo.append(ch.toChar())
            }
        }
        return cpuInfo.toString()
    }

    private fun stringContainsIgnoreCase(string: String, substring: String): Boolean =
        string.lowercase(Locale.ENGLISH).contains(substring.lowercase(Locale.ENGLISH))

    @JvmStatic
    fun isExynos4Device(): Boolean {
        try {
            val cpuInfo = readCpuinfo()
            if (stringContainsIgnoreCase(cpuInfo, "SMDK4")) {
                VibemisLog.info("Found SMDK4 in /proc/cpuinfo")
                return true
            }
            if (stringContainsIgnoreCase(cpuInfo, "Exynos 4")) {
                VibemisLog.info("Found Exynos 4 in /proc/cpuinfo")
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            val systemDir = File("/sys/devices/system")
            val files = systemDir.listFiles()
            if (files != null) {
                for (f in files) {
                    if (stringContainsIgnoreCase(f.name, "exynos4")) {
                        VibemisLog.info("Found exynos4 in /sys/devices/system")
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return false
    }

    private fun safeSet(format: MediaFormat, key: String, value: Int) {
        try {
            format.setInteger(key, value)
        } catch (_: Throwable) {}
    }

    @JvmStatic
    fun applyExtraVendorOptions(videoFormat: MediaFormat?, decoderName: String?) {
        if (videoFormat == null || decoderName == null) return
        if (isNvidiaDecoder(decoderName)) {
            safeSet(videoFormat, "media.low-latency.enable", 1)
            safeSet(videoFormat, "vendor.low-latency.enable", 1)
            safeSet(videoFormat, "disable-output-reorder", 1)
            safeSet(videoFormat, "vendor.nvidia.disable-output-reorder", 1)
        }
        if (decoderName.lowercase(Locale.US).startsWith("omx.qcom")) {
            try { videoFormat.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt()) } catch (_: Throwable) {}
            try { videoFormat.setInteger(MediaFormat.KEY_PRIORITY, 0) } catch (_: Throwable) {}
        }
    }

    @JvmStatic
    fun applyFrameworkLowLatencyPostStart(codec: MediaCodec?) {
        if (codec == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val b = Bundle()
                b.putInt("low-latency", 1)
                codec.setParameters(b)
                VibemisLog.info("Applied post-start low-latency parameter")
            } catch (_: Throwable) {}
        }
    }

    @JvmStatic
    fun applyFrameworkLowLatencyPostStart(
        codec: MediaCodec?,
        @Suppress("UNUSED_PARAMETER") decoderInfo: MediaCodecInfo?,
        @Suppress("UNUSED_PARAMETER") mime: String?
    ) {
        applyFrameworkLowLatencyPostStart(codec)
    }
}
