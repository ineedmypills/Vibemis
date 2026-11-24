package com.ineedmypills.vibemis.binding.video

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaCodec.CodecException
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.Choreographer
import android.view.Surface
import com.ineedmypills.vibemis.preferences.PreferenceConfiguration
import com.ineedmypills.vibemis.BuildConfig
import com.ineedmypills.vibemis.Game
import com.ineedmypills.vibemis.VibemisLog
import com.ineedmypills.vibemis.nvstream.av.video.VideoDecoderRenderer
import com.ineedmypills.vibemis.nvstream.jni.VibemisBridge
import com.ineedmypills.vibemis.profiles.ProfilesManager
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MediaCodecDecoderRenderer(
    private val activity: Activity,
    private val prefs: PreferenceConfiguration?,
    private val crashListener: CrashListener,
    private val consecutiveCrashCount: Int,
    meteredData: Boolean,
    requestedHdr: Boolean,
    invertResolution: Boolean,
    private val glRenderer: String?,
    private val perfListener: PerfOverlayListener?
) : VideoDecoderRenderer(), Choreographer.FrameCallback {

    private var csdDirty = false

    @Volatile
    var isHdrActive = false
        private set

    private val decodeLatencyTracker = DecodeLatencyTracker()

    @Volatile
    private var nvidiaLatencyRecoveryController: NvidiaLatencyRecoveryController? = null

    private val perfOverlayHandler = Handler(Looper.getMainLooper())
    private var lastPerfOverlayDispatchNs = 0L

    private class ColdCodecConfig {
        var avcDecoder: MediaCodecInfo? = null
        var hevcDecoder: MediaCodecInfo? = null
        var av1Decoder: MediaCodecInfo? = null

        val vpsBuffers = ArrayList<ByteArray>()
        val spsBuffers = ArrayList<ByteArray>()
        val ppsBuffers = ArrayList<ByteArray>()

        var submittedCsd = false
        var currentHdrMetadata: ByteArray? = null

        var needsSpsBitstreamFixup = false
        var isExynos4 = false
        var adaptivePlayback = false
        var directSubmit = false
        var fusedIdrFrame = false
        var constrainedHighProfile = false
        var refFrameInvalidationAvc = false
        var refFrameInvalidationHevc = false
        var refFrameInvalidationAv1 = false
        var optimalSlicesPerFrame: Byte = 1
        var refFrameInvalidationActive = false

        var initialExceptionTimestamp = 0L
        var reportedCrash = false

        var inputFormat: MediaFormat? = null
        var outputFormat: MediaFormat? = null
        var configuredFormat: MediaFormat? = null
        var initialWidth = 0
        var initialHeight = 0
        var invertResolution = false
        var needsBaselineSpsHack = false
        var savedSps: ByteArray? = null
        var initialException: RendererException? = null
    }

    private val coldCfg = ColdCodecConfig()

    private var useAsyncCodec = ENABLE_ASYNC_DECODING

    @Volatile
    private var requestedUseAsyncCodec = useAsyncCodec
    private var nextAsyncDecodePollNs = 0L

    @Volatile
    private var syncLfrEnabled = false
    private val asyncCodec = AsyncCodecAdapter()

    @Volatile
    private var streamTargetFps: Int = prefs?.fps?.toInt() ?: 60
    private val nanoPacer = NanoPacer()
    private val nanoLatest = NanoPacer.LatestOutput()

    private val nanoPacerCallbacks = object : NanoPacer.OutputCallbacks {
        override fun nextOutputIndex(info: BufferInfo, timeoutUs: Int): Int =
            this@MediaCodecDecoderRenderer.nextOutputIndex(info, timeoutUs)

        override fun releaseOutputBuffer(index: Int, render: Boolean) {
            try {
                val codec = videoDecoder
                if (codec != null) {
                    releaseOutputBufferRenderLocked(codec, index, render)
                }
            } catch (_: Throwable) {}
        }

        override fun onDequeued(presentationTimeUs: Long, dequeueNs: Long) {
            try {
                decodeLatencyTracker.onDequeue(activeWindowVideoStats, presentationTimeUs, dequeueNs, USE_FRAME_RENDER_TIME)
            } catch (_: Throwable) {}
        }
    }

    private var nextInputBufferIndex = -1
    private var nextInputBuffer: ByteBuffer? = null

    private val context: Context = activity
    private var videoDecoder: MediaCodec? = null
    private val releaseOutputBufferLock = Any()

    private fun releaseOutputBufferNoRenderLocked(codec: MediaCodec?, index: Int) {
        if (codec == null || index < 0) return
        synchronized(releaseOutputBufferLock) {
            codec.releaseOutputBuffer(index, false)
        }
    }

    private fun releaseOutputBufferRenderLocked(codec: MediaCodec?, index: Int, render: Boolean) {
        if (codec == null || index < 0) return
        synchronized(releaseOutputBufferLock) {
            codec.releaseOutputBuffer(index, render)
        }
    }

    private fun releaseOutputBufferAtTimeLockedLollipop(codec: MediaCodec?, index: Int, renderTimeNs: Long) {
        if (codec == null || index < 0) return
        synchronized(releaseOutputBufferLock) {
            codec.releaseOutputBuffer(index, renderTimeNs)
        }
    }

    private var rendererThread: Thread? = null
    var activeVideoFormat: Int = 0
        private set
    private var renderTarget: Surface? = null

    @Volatile
    private var glPresentationHintW = 0

    @Volatile
    private var glPresentationHintH = 0

    @Volatile
    private var stopping = false

    private var foreground = true
    private val perfOverlayComposer = PerfOverlayComposer()

    private var inputDequeueHangStartMs = 0L
    private var inputTryAgainStreak = 0

    private fun resetInputBufferState() {
        inputTryAgainStreak = 0
        inputDequeueHangStartMs = 0L
        nextInputBufferIndex = -1
        nextInputBuffer = null
    }

    private val codecRecoveryType = AtomicInteger(CR_RECOVERY_TYPE_NONE)
    private val codecRecoveryMonitor = Any()
    private var codecRecoveryThreadQuiescedFlags = 0
    private var codecRecoveryAttempts = 0

    private var initialException: RendererException? = null

    private val activeWindowVideoStats = VideoStats()
    private val lastWindowVideoStats = VideoStats()
    private val globalVideoStats = VideoStats()

    private var lastTimestampUs = 0L
    private var lastFrameNumber = 0
    private var refreshRate = 0
    private var refreshRateHz = 0f

    private var nextFramePacingPollNs = 0L
    private var appliedFramePacing = Int.MIN_VALUE

    private var minDecodeTime = Float.MAX_VALUE
    var minDecoderLatencyFullLog: String = ""
        private set
    private var nextDecoderTimingPollNs = 0L

    @Volatile
    private var runtimeOutputDequeueTimeoutCustomEnabled: Boolean =
        prefs?.decoderOutputDequeueTimeoutCustom == true

    @Volatile
    private var runtimeOutputDequeueTimeoutUs: Int =
        prefs?.decoderOutputDequeueTimeoutUs ?: 50000

    private val outputBufferQueue = ArrayBlockingQueue<Int>(OUTPUT_BUFFER_QUEUE_LIMIT)
    private var lastRenderedFrameTimeNanos = 0L

    private var choreographerHandlerThread: HandlerThread? = null
    private var choreographerHandler: Handler? = null

    @Volatile
    private var lastPacingStreamFps = -1f

    @Volatile
    private var lastPacingDisplayHz = -1f

    @Volatile
    private var vsyncsPerFrame = 1.0

    @Volatile
    private var vsyncAccumulator = 0.0

    private val repostChoreographerCallback = Runnable {
        try {
            Choreographer.getInstance().postFrameCallback(this@MediaCodecDecoderRenderer)
        } catch (_: Throwable) {}
    }

    internal var numSpsIn = 0
    internal var numPpsIn = 0
    internal var numVpsIn = 0
    internal var numFramesIn = 0
    internal var numFramesOut = 0

    private var glUpscaler: GlUpscalerBridge? = null

    @Volatile
    private var rendererTid = 0

    init {
        syncLfrEnabled = prefs?.syncLfrEnabled == true
        coldCfg.invertResolution = invertResolution

        coldCfg.avcDecoder = findAvcDecoder()
        if (coldCfg.avcDecoder != null) {
            VibemisLog.info("Selected AVC decoder: ${coldCfg.avcDecoder!!.name}")
        } else {
            VibemisLog.warning("No AVC decoder found")
        }

        coldCfg.hevcDecoder = findHevcDecoder(prefs, meteredData, requestedHdr)
        if (coldCfg.hevcDecoder != null) {
            VibemisLog.info("Selected HEVC decoder: ${coldCfg.hevcDecoder!!.name}")
        } else {
            VibemisLog.info("No HEVC decoder found")
        }

        coldCfg.av1Decoder = findAv1Decoder(prefs)
        if (coldCfg.av1Decoder != null) {
            VibemisLog.info("Selected AV1 decoder: ${coldCfg.av1Decoder!!.name}")
        } else {
            VibemisLog.info("No AV1 decoder found")
        }

        var avcOptimalSlicesPerFrame = 0
        var hevcOptimalSlicesPerFrame = 0
        coldCfg.avcDecoder?.let { avc ->
            coldCfg.directSubmit = MediaCodecHelper.decoderCanDirectSubmit(avc.name)
            coldCfg.refFrameInvalidationAvc = MediaCodecHelper.decoderSupportsRefFrameInvalidationAvc(avc.name, coldCfg.initialHeight)
            avcOptimalSlicesPerFrame = MediaCodecHelper.getDecoderOptimalSlicesPerFrame(avc.name).toInt()

            if (coldCfg.directSubmit) {
                VibemisLog.info("Decoder ${avc.name} will use direct submit")
            }
            if (coldCfg.refFrameInvalidationAvc) {
                VibemisLog.info("Decoder ${avc.name} will use reference frame invalidation for AVC")
            }
            VibemisLog.info("Decoder ${avc.name} wants $avcOptimalSlicesPerFrame slices per frame")
        }

        coldCfg.hevcDecoder?.let { hevc ->
            coldCfg.refFrameInvalidationHevc = MediaCodecHelper.decoderSupportsRefFrameInvalidationHevc(hevc)
            hevcOptimalSlicesPerFrame = MediaCodecHelper.getDecoderOptimalSlicesPerFrame(hevc.name).toInt()

            if (coldCfg.refFrameInvalidationHevc) {
                VibemisLog.info("Decoder ${hevc.name} will use reference frame invalidation for HEVC")
            }
            VibemisLog.info("Decoder ${hevc.name} wants $hevcOptimalSlicesPerFrame slices per frame")
        }

        coldCfg.av1Decoder?.let { av1 ->
            coldCfg.refFrameInvalidationAv1 = MediaCodecHelper.decoderSupportsRefFrameInvalidationAv1(av1)
            if (coldCfg.refFrameInvalidationAv1) {
                VibemisLog.info("Decoder ${av1.name} will use reference frame invalidation for AV1")
            }
        }

        coldCfg.optimalSlicesPerFrame = max(avcOptimalSlicesPerFrame, hevcOptimalSlicesPerFrame).toByte()
        VibemisLog.info("Requesting ${coldCfg.optimalSlicesPerFrame} slices per frame")

        if (consecutiveCrashCount % 2 == 1) {
            coldCfg.refFrameInvalidationAvc = false
            coldCfg.refFrameInvalidationHevc = false
            VibemisLog.warning("Disabling RFI due to previous crash")
        }
    }

    private fun computeAsyncPreferLowerDelaysFromCurrentPrefs(effectivePacing: Int): Boolean {
        val def = when (effectivePacing) {
            PreferenceConfiguration.FRAME_PACING_GPU_RAW,
            PreferenceConfiguration.FRAME_PACING_MIN_LATENCY,
            PreferenceConfiguration.FRAME_PACING_WARP,
            PreferenceConfiguration.FRAME_PACING_WARP2 -> true
            else -> false
        }
        return readBooleanOverlayFirst(KEY_ASYNC_PREFER_LOWER_DELAYS, def)
    }

    private fun maybeReloadDecoderTimingPrefs() {
        val nowNs = System.nanoTime()
        if (nowNs < nextDecoderTimingPollNs) return
        nextDecoderTimingPollNs = nowNs + DECODER_TIMING_POLL_INTERVAL_NS

        val sp = PreferenceConfiguration.getDefaultSharedPreferences(context)
        val customEnabled = sp.getBoolean("checkbox_custom_decoder_output_timeout_us", false)
        val dequeueUs = clampInt(safeGetInt(sp, "seekbar_decoder_output_timeout_us", 50000), 0, 50000)

        runtimeOutputDequeueTimeoutCustomEnabled = customEnabled
        runtimeOutputDequeueTimeoutUs = dequeueUs

        val requestedSyncLfr = readBooleanOverlayFirst(
            KEY_SYNC_LFR_ENABLED,
            prefs?.syncLfrEnabled == true
        )
        syncLfrEnabled = requestedSyncLfr

        if (useAsyncCodec) {
            updateAsyncPreferLowerDelaysFromCurrentPrefs(getEffectivePacingForThreadPriorities())
        }

        if (prefs != null) {
            prefs.decoderOutputDequeueTimeoutCustom = customEnabled
            prefs.decoderOutputDequeueTimeoutUs = dequeueUs
            prefs.syncLfrEnabled = requestedSyncLfr
        }
    }

    private fun computeDesiredUseAsyncCodecFromPrefs(): Boolean {
        val userEnabled = readBooleanOverlayFirst(KEY_ASYNC_DECODE_ENABLED, true)
        return ENABLE_ASYNC_DECODING && userEnabled
    }

    private fun initAsyncDecodingFromSettings() {
        requestedUseAsyncCodec = computeDesiredUseAsyncCodecFromPrefs()
        useAsyncCodec = requestedUseAsyncCodec
        nextAsyncDecodePollNs = 0L
    }

    private fun maybeApplyRuntimeAsyncDecodingRequest() {
        val nowNs = System.nanoTime()
        if (nowNs < nextAsyncDecodePollNs) return
        nextAsyncDecodePollNs = nowNs + ASYNC_DECODE_POLL_INTERVAL_NS

        val desired = computeDesiredUseAsyncCodecFromPrefs()
        if (desired == requestedUseAsyncCodec) return

        requestedUseAsyncCodec = desired
        if (!stopping && videoDecoder != null) {
            if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESTART) ||
                codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESTART)
            ) {
                VibemisLog.info("Async decode mode change requested -> decoder restart (desired=${if (desired) "async" else "sync"})")
            }
        }
    }

    private fun findAvcDecoder(): MediaCodecInfo? {
        return MediaCodecHelper.findProbableSafeDecoder("video/avc", MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            ?: MediaCodecHelper.findFirstDecoder("video/avc")
    }

    private fun decoderCanMeetPerformancePoint(
        caps: MediaCodecInfo.VideoCapabilities,
        prefs: PreferenceConfiguration
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val targetPerfPoint = MediaCodecInfo.VideoCapabilities.PerformancePoint(
                coldCfg.initialWidth,
                coldCfg.initialHeight,
                prefs.fps.roundToInt()
            )
            val perfPoints = caps.supportedPerformancePoints
            if (perfPoints != null) {
                for (perfPoint in perfPoints) {
                    if (perfPoint.covers(targetPerfPoint)) {
                        return true
                    }
                }
                return false
            }
        }

        try {
            val fpsRange = caps.getAchievableFrameRatesFor(coldCfg.initialWidth, coldCfg.initialHeight)
            if (fpsRange != null) {
                return prefs.fps <= fpsRange.upper
            }
        } catch (_: IllegalArgumentException) {
            return false
        }

        return caps.areSizeAndRateSupported(coldCfg.initialWidth, coldCfg.initialHeight, prefs.fps.toDouble())
    }

    private fun decoderCanMeetPerformancePointWithHevcAndNotAvc(
        hevcDecoderInfo: MediaCodecInfo,
        avcDecoderInfo: MediaCodecInfo,
        prefs: PreferenceConfiguration
    ): Boolean {
        val avcCaps = avcDecoderInfo.getCapabilitiesForType("video/avc").videoCapabilities!!
        val hevcCaps = hevcDecoderInfo.getCapabilitiesForType("video/hevc").videoCapabilities!!
        return !decoderCanMeetPerformancePoint(avcCaps, prefs) && decoderCanMeetPerformancePoint(hevcCaps, prefs)
    }

    private fun decoderCanMeetPerformancePointWithAv1AndNotHevc(
        av1DecoderInfo: MediaCodecInfo,
        hevcDecoderInfo: MediaCodecInfo,
        prefs: PreferenceConfiguration
    ): Boolean {
        val av1Caps = av1DecoderInfo.getCapabilitiesForType("video/av01").videoCapabilities!!
        val hevcCaps = hevcDecoderInfo.getCapabilitiesForType("video/hevc").videoCapabilities!!
        return !decoderCanMeetPerformancePoint(hevcCaps, prefs) && decoderCanMeetPerformancePoint(av1Caps, prefs)
    }

    private fun decoderCanMeetPerformancePointWithAv1AndNotAvc(
        av1DecoderInfo: MediaCodecInfo,
        avcDecoderInfo: MediaCodecInfo,
        prefs: PreferenceConfiguration
    ): Boolean {
        val avcCaps = avcDecoderInfo.getCapabilitiesForType("video/avc").videoCapabilities!!
        val av1Caps = av1DecoderInfo.getCapabilitiesForType("video/av01").videoCapabilities!!
        return !decoderCanMeetPerformancePoint(avcCaps, prefs) && decoderCanMeetPerformancePoint(av1Caps, prefs)
    }

    private fun findHevcDecoder(
        prefs: PreferenceConfiguration?,
        @Suppress("UNUSED_PARAMETER") meteredNetwork: Boolean,
        requestedHdr: Boolean
    ): MediaCodecInfo? {
        if (prefs == null || prefs.videoFormat == PreferenceConfiguration.FormatOption.FORCE_H264) {
            return null
        }

        val hevcDecoderInfo = MediaCodecHelper.findProbableSafeDecoder("video/hevc", -1)
        if (hevcDecoderInfo != null && !MediaCodecHelper.decoderIsWhitelistedForHevc(hevcDecoderInfo)) {
            VibemisLog.info("Found HEVC decoder, but it's not whitelisted - ${hevcDecoderInfo.name}")
            when {
                prefs.videoFormat == PreferenceConfiguration.FormatOption.FORCE_HEVC ->
                    VibemisLog.info("Forcing HEVC enabled despite non-whitelisted decoder")
                requestedHdr ->
                    VibemisLog.info("Forcing HEVC enabled for HDR streaming")
                coldCfg.initialWidth > 4096 || coldCfg.initialHeight > 4096 ->
                    VibemisLog.info("Forcing HEVC enabled for over 4K streaming")
                coldCfg.avcDecoder != null &&
                    decoderCanMeetPerformancePointWithHevcAndNotAvc(hevcDecoderInfo, coldCfg.avcDecoder!!, prefs) ->
                    VibemisLog.info("Using non-whitelisted HEVC decoder to meet performance point")
                else -> return null
            }
        }
        return hevcDecoderInfo
    }

    private fun findAv1Decoder(prefs: PreferenceConfiguration?): MediaCodecInfo? {
        if (prefs == null || prefs.videoFormat != PreferenceConfiguration.FormatOption.FORCE_AV1) {
            return null
        }

        val decoderInfo = MediaCodecHelper.findProbableSafeDecoder("video/av01", -1)
        if (decoderInfo != null && !MediaCodecHelper.isDecoderWhitelistedForAv1(decoderInfo)) {
            VibemisLog.info("Found AV1 decoder, but it's not whitelisted - ${decoderInfo.name}")
            when {
                prefs.videoFormat == PreferenceConfiguration.FormatOption.FORCE_AV1 ->
                    VibemisLog.info("Forcing AV1 enabled despite non-whitelisted decoder")
                coldCfg.hevcDecoder != null &&
                    decoderCanMeetPerformancePointWithAv1AndNotHevc(decoderInfo, coldCfg.hevcDecoder!!, prefs) ->
                    VibemisLog.info("Using non-whitelisted AV1 decoder to meet performance point")
                coldCfg.hevcDecoder == null && coldCfg.avcDecoder != null &&
                    decoderCanMeetPerformancePointWithAv1AndNotAvc(decoderInfo, coldCfg.avcDecoder!!, prefs) ->
                    VibemisLog.info("Using non-whitelisted AV1 decoder to meet performance point")
                else -> return null
            }
        }
        return decoderInfo
    }

    fun setRenderTarget(renderTarget: Surface?) {
        if (this.renderTarget != null && this.renderTarget !== renderTarget && glUpscaler != null) {
            glUpscaler?.release()
            glUpscaler = null
        }
        this.renderTarget = renderTarget
        if (glUpscaler != null) {
            applyUpscalerPresentationHint()
        }
    }

    fun setUpscalerPresentationSizeHint(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        glPresentationHintW = width
        glPresentationHintH = height
        applyUpscalerPresentationHint()
    }

    private fun applyUpscalerPresentationHint() {
        val upscaler = glUpscaler ?: return
        val width = glPresentationHintW
        val height = glPresentationHintH
        if (width > 0 && height > 0) {
            upscaler.setPresentationSizeHint(width, height)
        } else {
            upscaler.setPresentationHint(context)
        }
    }

    fun isHevcSupported(): Boolean = coldCfg.hevcDecoder != null

    fun isAvcSupported(): Boolean = coldCfg.avcDecoder != null

    fun isHevcMain10Hdr10Supported(): Boolean {
        val hevc = coldCfg.hevcDecoder ?: return false
        for (profileLevel in hevc.getCapabilitiesForType("video/hevc").profileLevels) {
            if (profileLevel.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10) {
                VibemisLog.info("HEVC decoder ${hevc.name} supports HEVC Main10 HDR10")
                return true
            }
        }
        return false
    }

    fun isAv1Supported(): Boolean = coldCfg.av1Decoder != null

    fun isAv1Main10Supported(): Boolean {
        val av1 = coldCfg.av1Decoder ?: return false
        for (profileLevel in av1.getCapabilitiesForType("video/av01").profileLevels) {
            if (profileLevel.profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10) {
                VibemisLog.info("AV1 decoder ${av1.name} supports AV1 Main 10 HDR10")
                return true
            }
        }
        return false
    }

    fun getPreferredColorSpace(): Int = VibemisBridge.COLORSPACE_REC_709

    fun getPreferredColorRange(): Int =
        if (prefs?.fullRange == true) VibemisBridge.COLOR_RANGE_FULL else VibemisBridge.COLOR_RANGE_LIMITED

    fun notifyVideoForeground() {
        foreground = true
    }

    fun notifyVideoBackground() {
        foreground = false
    }

    private fun createBaseMediaFormat(mimeType: String): MediaFormat {
        val videoFormat = MediaFormat.createVideoFormat(mimeType, coldCfg.initialWidth, coldCfg.initialHeight)
        videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, refreshRate)

        if (coldCfg.adaptivePlayback) {
            videoFormat.setInteger(MediaFormat.KEY_MAX_WIDTH, coldCfg.initialWidth)
            videoFormat.setInteger(MediaFormat.KEY_MAX_HEIGHT, coldCfg.initialHeight)
        }

        videoFormat.setInteger(
            MediaFormat.KEY_COLOR_RANGE,
            if (getPreferredColorRange() == VibemisBridge.COLOR_RANGE_FULL) {
                MediaFormat.COLOR_RANGE_FULL
            } else {
                MediaFormat.COLOR_RANGE_LIMITED
            }
        )

        if ((activeVideoFormat and VibemisBridge.VIDEO_FORMAT_MASK_10BIT) == 0) {
            videoFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            when (getPreferredColorSpace()) {
                VibemisBridge.COLORSPACE_REC_601 ->
                    videoFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_NTSC)
                VibemisBridge.COLORSPACE_REC_709 ->
                    videoFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                VibemisBridge.COLORSPACE_REC_2020 ->
                    videoFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            }
        }
        return videoFormat
    }

    private fun configureAndStartDecoder(format: MediaFormat) {
        val hdrMeta = coldCfg.currentHdrMetadata
        if (hdrMeta != null) {
            val hdrStaticInfo = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN)
            val hdrMetadata = ByteBuffer.wrap(hdrMeta).order(ByteOrder.LITTLE_ENDIAN)

            hdrStaticInfo.put(0.toByte())
            repeat(12) {
                hdrStaticInfo.putShort(hdrMetadata.short)
            }
            hdrStaticInfo.rewind()
            format.setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, hdrStaticInfo)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            format.removeKey(MediaFormat.KEY_HDR_STATIC_INFO)
        }

        VibemisLog.info("Configuring with format: $format")

        var codecSurface = renderTarget
        if (prefs != null && prefs.videoUpscaleEnable) {
            if (glUpscaler == null) {
                glUpscaler = GlUpscalerBridge()
            }
            if (glUpscaler!!.ensureCreated(renderTarget, coldCfg.initialWidth, coldCfg.initialHeight, prefs, context)) {
                applyUpscalerPresentationHint()
                val inputSurface = glUpscaler!!.decoderInputSurface
                if (inputSurface != null) {
                    codecSurface = inputSurface
                }
            } else {
                try { glUpscaler?.release() } catch (_: Throwable) {}
                glUpscaler = null
            }
        }

        try { attachAsyncCodecIfNeeded() } catch (_: Throwable) {}

        val decoder = videoDecoder!!
        decoder.configure(format, codecSurface, null, 0)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && codecSurface != null && codecSurface !== renderTarget) {
            try {
                val desiredFps = when {
                    prefs != null && prefs.fps > 0 -> prefs.fps
                    streamTargetFps > 0 -> streamTargetFps.toFloat()
                    else -> 60f
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    codecSurface.setFrameRate(
                        desiredFps,
                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                        Surface.CHANGE_FRAME_RATE_ALWAYS
                    )
                } else {
                    codecSurface.setFrameRate(
                        desiredFps,
                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
                    )
                }
                VibemisLog.info("Applied decoder Surface frame-rate hint: $desiredFps fps")
            } catch (t: Throwable) {
                VibemisLog.warning("Decoder Surface.setFrameRate() failed: $t")
            }
        }

        glUpscaler?.let { upscaler ->
            upscaler.start()
            applyUpscalerVsyncSettingIfSupported()
            val dbg = prefs != null &&
                prefs.enablePerfOverlayLite &&
                prefs.enablePerfOverlayLiteAdvanced &&
                prefs.videoUpscaleEnable &&
                !prefs.gpuPathMode
            upscaler.setDebugEnabled(dbg)
        }

        coldCfg.configuredFormat = format
        coldCfg.submittedCsd = false
        coldCfg.vpsBuffers.clear()
        coldCfg.spsBuffers.clear()
        coldCfg.ppsBuffers.clear()

        decodeLatencyTracker.clear()
        perfOverlayComposer.reset()
        csdDirty = false

        coldCfg.inputFormat = decoder.inputFormat
        VibemisLog.info("Input format: ${coldCfg.inputFormat}")

        decoder.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
        decoder.start()
        MediaCodecHelper.applyFrameworkLowLatencyPostStart(decoder)

        try {
            val inF = decoder.inputFormat
            val outF = decoder.outputFormat
            VibemisLog.info("Decoder input format: $inF")
            VibemisLog.info("Decoder output format: $outF")
        } catch (_: Throwable) {
            VibemisLog.info("Decoder formats unavailable after start")
        }
    }

    private fun tryConfigureDecoder(
        selectedDecoderInfo: MediaCodecInfo,
        format: MediaFormat,
        throwOnCodecError: Boolean
    ): Boolean {
        var configured = false
        try {
            videoDecoder = MediaCodec.createByCodecName(selectedDecoderInfo.name)
            configureAndStartDecoder(format)
            VibemisLog.info("Using codec ${selectedDecoderInfo.name} for hardware decoding ${format.getString(MediaFormat.KEY_MIME)}")
            configured = true
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
            if (throwOnCodecError) throw e
        } catch (e: IllegalStateException) {
            e.printStackTrace()
            if (throwOnCodecError) throw e
        } catch (e: IOException) {
            e.printStackTrace()
            if (throwOnCodecError) throw RuntimeException(e)
        } finally {
            if (!configured && videoDecoder != null) {
                videoDecoder?.release()
                videoDecoder = null
            }
        }
        return configured
    }

    fun initializeDecoder(throwOnCodecError: Boolean): Int {
        val mimeType: String
        val selectedDecoderInfo: MediaCodecInfo?

        when {
            (activeVideoFormat and VibemisBridge.VIDEO_FORMAT_MASK_H264) != 0 -> {
                mimeType = "video/avc"
                selectedDecoderInfo = coldCfg.avcDecoder
                if (selectedDecoderInfo == null) {
                    VibemisLog.severe("No available AVC decoder!")
                    return -1
                }
                if (coldCfg.initialWidth > 4096 || coldCfg.initialHeight > 4096) {
                    VibemisLog.severe("> 4K streaming only supported on HEVC")
                    return -1
                }
                coldCfg.needsSpsBitstreamFixup = MediaCodecHelper.decoderNeedsSpsBitstreamRestrictions(selectedDecoderInfo.name)
                coldCfg.needsBaselineSpsHack = MediaCodecHelper.decoderNeedsBaselineSpsHack(selectedDecoderInfo.name)
                coldCfg.constrainedHighProfile = MediaCodecHelper.decoderNeedsConstrainedHighProfile(selectedDecoderInfo.name)
                coldCfg.isExynos4 = MediaCodecHelper.isExynos4Device()
                if (coldCfg.needsSpsBitstreamFixup) {
                    VibemisLog.info("Decoder ${selectedDecoderInfo.name} needs SPS bitstream restrictions fixup")
                }
                if (coldCfg.needsBaselineSpsHack) {
                    VibemisLog.info("Decoder ${selectedDecoderInfo.name} needs baseline SPS hack")
                }
                if (coldCfg.constrainedHighProfile) {
                    VibemisLog.info("Decoder ${selectedDecoderInfo.name} needs constrained high profile")
                }
                if (coldCfg.isExynos4) {
                    VibemisLog.info("Decoder ${selectedDecoderInfo.name} is on Exynos 4")
                }
                coldCfg.refFrameInvalidationActive = coldCfg.refFrameInvalidationAvc
            }
            (activeVideoFormat and VibemisBridge.VIDEO_FORMAT_MASK_H265) != 0 -> {
                mimeType = "video/hevc"
                selectedDecoderInfo = coldCfg.hevcDecoder
                if (selectedDecoderInfo == null) {
                    VibemisLog.severe("No available HEVC decoder!")
                    return -2
                }
                coldCfg.refFrameInvalidationActive = coldCfg.refFrameInvalidationHevc
            }
            (activeVideoFormat and VibemisBridge.VIDEO_FORMAT_MASK_AV1) != 0 -> {
                mimeType = "video/av01"
                selectedDecoderInfo = coldCfg.av1Decoder
                if (selectedDecoderInfo == null) {
                    VibemisLog.severe("No available AV1 decoder!")
                    return -2
                }
                coldCfg.refFrameInvalidationActive = coldCfg.refFrameInvalidationAv1
            }
            else -> {
                VibemisLog.severe("Unknown format")
                return -3
            }
        }

        coldCfg.adaptivePlayback = MediaCodecHelper.decoderSupportsAdaptivePlayback(selectedDecoderInfo, mimeType)
        coldCfg.fusedIdrFrame = MediaCodecHelper.decoderSupportsFusedIdrFrame(selectedDecoderInfo, mimeType)

        var tryNumber = 0
        while (true) {
            VibemisLog.info("Decoder configuration try: $tryNumber")
            val mediaFormat = createBaseMediaFormat(mimeType)
            val newFormat = MediaCodecHelper.setDecoderLowLatencyOptions(
                mediaFormat,
                selectedDecoderInfo,
                prefs?.enableUltraLowLatency == true,
                tryNumber
            )
            if (tryConfigureDecoder(selectedDecoderInfo, mediaFormat, !newFormat && throwOnCodecError)) {
                break
            }
            if (!newFormat) {
                return -5
            }
            tryNumber++
        }

        if (USE_FRAME_RENDER_TIME) {
            videoDecoder?.setOnFrameRenderedListener({ _, presentationTimeUs, renderTimeNanos ->
                val delta = (renderTimeNanos / 1000000L) - (presentationTimeUs / 1000)
                if (delta in 0 until 1000) {
                    activeWindowVideoStats.totalTimeMs += delta
                    activeWindowVideoStats.endToEndLatencyMs += delta
                }
            }, null)
        }

        return 0
    }

    override fun setup(format: Int, width: Int, height: Int, redrawRate: Int): Int {
        coldCfg.initialWidth = if (coldCfg.invertResolution) height else width
        coldCfg.initialHeight = if (coldCfg.invertResolution) width else height
        activeVideoFormat = format
        refreshRate = redrawRate
        refreshRateHz = queryDisplayRefreshRateHz()
        if (refreshRateHz <= 1f) {
            refreshRateHz = redrawRate.toFloat()
        }
        if (refreshRateHz <= 1f) {
            refreshRateHz = 60f
        }
        initAsyncDecodingFromSettings()
        return initializeDecoder(false)
    }

    private fun queryDisplayRefreshRateHz(): Float {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val d = activity.display
                if (d != null) {
                    val rr = d.refreshRate
                    if (rr > 1f && rr < 1000f) return rr
                }
            } catch (_: Throwable) {}
        }

        try {
            @Suppress("DEPRECATION")
            val d = activity.windowManager?.defaultDisplay
            if (d != null) {
                val rr = d.refreshRate
                if (rr > 1f && rr < 1000f) return rr
            }
        } catch (_: Throwable) {}

        return 0f
    }

    private fun doCodecRecoveryIfRequired(quiescenceFlag: Int): Boolean {
        if (codecRecoveryType.get() == CR_RECOVERY_TYPE_NONE) {
            return false
        }

        synchronized(codecRecoveryMonitor) {
            if (choreographerHandlerThread == null) {
                codecRecoveryThreadQuiescedFlags = codecRecoveryThreadQuiescedFlags or CR_FLAG_CHOREOGRAPHER
            }

            codecRecoveryThreadQuiescedFlags = codecRecoveryThreadQuiescedFlags or quiescenceFlag

            if (codecRecoveryThreadQuiescedFlags == CR_FLAG_ALL) {
                nvidiaLatencyRecoveryController?.recoveryBegin(codecRecoveryType.get())
                nextInputBuffer = null
                nextInputBufferIndex = -1

                try { asyncCodec.quiesceAndRelease(videoDecoder, asyncOutputRelease) } catch (_: Throwable) {}
                decodeLatencyTracker.clear()
                csdDirty = false

                if (codecRecoveryType.get() == CR_RECOVERY_TYPE_FLUSH) {
                    VibemisLog.warning("Flushing decoder")
                    try {
                        videoDecoder?.flush()
                        if (codecRecoveryType.get() == CR_RECOVERY_TYPE_FLUSH && useAsyncCodec) {
                            videoDecoder?.start()
                        }
                        codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_NONE)
                    } catch (e: IllegalStateException) {
                        e.printStackTrace()
                        codecRecoveryType.set(CR_RECOVERY_TYPE_RESTART)
                    }
                }

                if (codecRecoveryType.get() != CR_RECOVERY_TYPE_NONE) {
                    codecRecoveryAttempts++
                    VibemisLog.info("Codec recovery attempt: $codecRecoveryAttempts")
                }

                if (codecRecoveryType.get() == CR_RECOVERY_TYPE_RESTART) {
                    VibemisLog.warning("Trying to restart decoder after CodecException")
                    try {
                        useAsyncCodec = requestedUseAsyncCodec
                        try { detachAsyncCodec() } catch (_: Throwable) {}
                        videoDecoder?.stop()
                        try { attachAsyncCodecIfNeeded() } catch (_: Throwable) {}
                        configureAndStartDecoder(coldCfg.configuredFormat!!)
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
                    } catch (e: IllegalArgumentException) {
                        e.printStackTrace()
                        stopping = true
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
                    } catch (e: IllegalStateException) {
                        e.printStackTrace()
                        codecRecoveryType.set(CR_RECOVERY_TYPE_RESET)
                    }
                }

                if (codecRecoveryType.get() == CR_RECOVERY_TYPE_RESET) {
                    VibemisLog.warning("Trying to reset decoder after CodecException")
                    try {
                        useAsyncCodec = requestedUseAsyncCodec
                        try { detachAsyncCodec() } catch (_: Throwable) {}
                        videoDecoder?.reset()
                        try { attachAsyncCodecIfNeeded() } catch (_: Throwable) {}
                        configureAndStartDecoder(coldCfg.configuredFormat!!)
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
                    } catch (e: IllegalArgumentException) {
                        e.printStackTrace()
                        stopping = true
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
                    } catch (e: IllegalStateException) {
                        e.printStackTrace()
                    }
                }

                if (codecRecoveryType.get() == CR_RECOVERY_TYPE_RESET) {
                    VibemisLog.warning("Trying to recreate decoder after CodecException")
                    useAsyncCodec = requestedUseAsyncCodec
                    videoDecoder?.release()

                    try {
                        val err = initializeDecoder(true)
                        if (err != 0) {
                            throw IllegalStateException("Decoder reset failed: $err")
                        }
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
                    } catch (e: IllegalArgumentException) {
                        e.printStackTrace()
                        stopping = true
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
                    } catch (e: IllegalStateException) {
                        if (!coldCfg.reportedCrash) {
                            coldCfg.reportedCrash = true
                            crashListener.notifyCrash(e)
                        }
                        throw RendererException(this, e)
                    }
                }

                codecRecoveryThreadQuiescedFlags = 0
                @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                (codecRecoveryMonitor as Object).notifyAll()
            } else {
                while (codecRecoveryType.get() != CR_RECOVERY_TYPE_NONE) {
                    try {
                        VibemisLog.info("Waiting to quiesce decoder threads: $codecRecoveryThreadQuiescedFlags")
                        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                        (codecRecoveryMonitor as Object).wait(1000)
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }

        return true
    }

    private fun handleDecoderException(e: IllegalStateException): Boolean {
        if (stopping) return false

        if (e is CodecException) {
            if (e.isTransient) {
                VibemisLog.warning(e.diagnosticInfo)
                return true
            }
            VibemisLog.severe(e.diagnosticInfo)

            if (codecRecoveryAttempts < CR_MAX_TRIES) {
                if (e.isRecoverable) {
                    if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESTART)) {
                        VibemisLog.info("Decoder requires restart for recoverable CodecException")
                        e.printStackTrace()
                    } else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESTART)) {
                        VibemisLog.info("Decoder flush promoted to restart for recoverable CodecException")
                        e.printStackTrace()
                    } else if (codecRecoveryType.get() != CR_RECOVERY_TYPE_RESET &&
                        codecRecoveryType.get() != CR_RECOVERY_TYPE_RESTART
                    ) {
                        throw IllegalStateException("Unexpected codec recovery type: ${codecRecoveryType.get()}")
                    }
                } else {
                    if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESET)) {
                        VibemisLog.info("Decoder requires reset for non-recoverable CodecException")
                        e.printStackTrace()
                    } else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESET)) {
                        VibemisLog.info("Decoder flush promoted to reset for non-recoverable CodecException")
                        e.printStackTrace()
                    } else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_RESTART, CR_RECOVERY_TYPE_RESET)) {
                        VibemisLog.info("Decoder restart promoted to reset for non-recoverable CodecException")
                        e.printStackTrace()
                    } else if (codecRecoveryType.get() != CR_RECOVERY_TYPE_RESET) {
                        throw IllegalStateException("Unexpected codec recovery type: ${codecRecoveryType.get()}")
                    }
                }
                return false
            }
        } else {
            if (codecRecoveryAttempts < CR_MAX_TRIES) {
                if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESET)) {
                    VibemisLog.info("Decoder requires reset for IllegalStateException")
                    e.printStackTrace()
                } else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESET)) {
                    VibemisLog.info("Decoder flush promoted to reset for IllegalStateException")
                    e.printStackTrace()
                } else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_RESTART, CR_RECOVERY_TYPE_RESET)) {
                    VibemisLog.info("Decoder restart promoted to reset for IllegalStateException")
                    e.printStackTrace()
                } else if (codecRecoveryType.get() != CR_RECOVERY_TYPE_RESET) {
                    throw IllegalStateException("Unexpected codec recovery type: ${codecRecoveryType.get()}")
                }
                return false
            }
        }

        if (codecRecoveryType.get() == CR_RECOVERY_TYPE_NONE) {
            if (coldCfg.initialException != null) {
                if (SystemClock.uptimeMillis() - coldCfg.initialExceptionTimestamp >= EXCEPTION_REPORT_DELAY_MS) {
                    if (!coldCfg.reportedCrash) {
                        coldCfg.reportedCrash = true
                        crashListener.notifyCrash(initialException!!)
                    }
                    throw coldCfg.initialException!!
                }
            } else {
                initialException = RendererException(this, e)
                coldCfg.initialException = initialException
                coldCfg.initialExceptionTimestamp = SystemClock.uptimeMillis()
            }
        }

        return false
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (stopping || choreographerHandlerThread == null) return

        val isBalanced = prefs != null && prefs.framePacing == PreferenceConfiguration.FRAME_PACING_BALANCED
        if (!isBalanced) {
            doCodecRecoveryIfRequired(CR_FLAG_CHOREOGRAPHER)
            return
        }

        val streamFps = when {
            prefs.fps > 0 -> prefs.fps
            streamTargetFps > 0 -> streamTargetFps.toFloat()
            else -> 60f
        }
        val displayHz = when {
            refreshRateHz > 1f -> refreshRateHz
            refreshRate > 0 -> refreshRate.toFloat()
            else -> 60f
        }

        if (abs(streamFps - lastPacingStreamFps) > 0.01f ||
            abs(displayHz - lastPacingDisplayHz) > 0.01f
        ) {
            lastPacingStreamFps = streamFps
            lastPacingDisplayHz = displayHz
            vsyncAccumulator = 0.0
            vsyncsPerFrame = if (streamFps > 0.01f && displayHz > 0.01f) {
                (displayHz.toDouble() / streamFps.toDouble()).coerceIn(0.25, 8.0)
            } else {
                1.0
            }
        }

        var shouldRenderThisVsync = true
        if (vsyncsPerFrame > 1.02) {
            vsyncAccumulator += 1.0
            if (vsyncAccumulator + 1e-9 < vsyncsPerFrame) {
                shouldRenderThisVsync = false
            } else {
                vsyncAccumulator -= vsyncsPerFrame
            }
        } else {
            vsyncAccumulator = 0.0
        }

        if (shouldRenderThisVsync) {
            val nextOutputBuffer = outputBufferQueue.poll()
            if (nextOutputBuffer != null) {
                val codec = videoDecoder
                if (codec != null) {
                    var released = false
                    try {
                        releaseOutputBufferAtTimeLockedLollipop(codec, nextOutputBuffer, frameTimeNanos)
                        released = true
                    } catch (_: IllegalStateException) {}

                    if (released) {
                        lastRenderedFrameTimeNanos = frameTimeNanos
                        activeWindowVideoStats.totalFramesRendered++
                    } else {
                        try {
                            releaseOutputBufferNoRenderLocked(codec, nextOutputBuffer)
                        } catch (e: IllegalStateException) {
                            e.printStackTrace()
                            handleDecoderException(e)
                        }
                    }
                }
            } else if (vsyncsPerFrame > 1.02) {
                vsyncAccumulator = 0.0
            }
        }

        doCodecRecoveryIfRequired(CR_FLAG_CHOREOGRAPHER)

        val ch = choreographerHandler
        if (!stopping && ch != null && choreographerHandlerThread != null) {
            if (Looper.myLooper() == ch.looper) {
                try {
                    Choreographer.getInstance().postFrameCallback(this)
                } catch (_: Throwable) {}
            } else {
                ch.post(repostChoreographerCallback)
            }
        }
    }

    private fun startChoreographerThread() {
        if (prefs == null || prefs.framePacing != PreferenceConfiguration.FRAME_PACING_BALANCED) return

        if (choreographerHandlerThread == null) {
            choreographerHandlerThread = HandlerThread(
                "Video - Choreographer",
                Process.THREAD_PRIORITY_URGENT_DISPLAY
            ).apply { start() }
            choreographerHandler = Handler(choreographerHandlerThread!!.looper)
        } else if (choreographerHandler == null) {
            choreographerHandler = Handler(choreographerHandlerThread!!.looper)
        }

        lastPacingStreamFps = -1f
        lastPacingDisplayHz = -1f
        vsyncsPerFrame = 1.0
        vsyncAccumulator = 0.0

        choreographerHandler?.let {
            it.removeCallbacks(repostChoreographerCallback)
            it.post {
                try { VibemisBridge.pinCurrentThreadToBigCores() } catch (_: Throwable) {}
            }
            it.post(repostChoreographerCallback)
        }
    }

    private fun stopChoreographerThread() {
        val h = choreographerHandler
        val ht = choreographerHandlerThread
        choreographerHandler = null
        choreographerHandlerThread = null
        if (h == null || ht == null) return

        h.post {
            try { h.removeCallbacks(repostChoreographerCallback) } catch (_: Throwable) {}
            try { Choreographer.getInstance().removeFrameCallback(this@MediaCodecDecoderRenderer) } catch (_: Throwable) {}
            try {
                ht.quitSafely()
            } catch (_: Throwable) {
                try { ht.quit() } catch (_: Throwable) {}
            }
        }
    }

    private fun startRendererThread() {
        rendererThread = object : Thread("Video - Renderer (MediaCodec)") {
            override fun run() {
                rendererTid = Process.myTid()
                applyVideoThreadPriorities()
                try { VibemisBridge.pinCurrentThreadToBigCores() } catch (_: Throwable) {}
                val info = BufferInfo()
                val lfrInfo = BufferInfo()

                var lastCleanupNs = System.nanoTime()
                val cleanupIntervalNs = 1_000_000_000L

                while (!stopping) {
                    maybeApplyRuntimeFramePacing()
                    maybeApplyRuntimeAsyncDecodingRequest()
                    maybeReloadDecoderTimingPrefs()
                    drainAsyncNoRenderReleaseQueue()

                    val recoveryController = nvidiaLatencyRecoveryController
                    if (recoveryController != null && recoveryController.isActive()) {
                        recoveryController.updateMode(
                            nvidiaLatencyRecoveryModeMask(),
                            prefs?.fps ?: 0f
                        )
                    }

                    val pacingMode = prefs?.framePacing ?: PreferenceConfiguration.FRAME_PACING_BALANCED
                    val customTimeoutEnabled = runtimeOutputDequeueTimeoutCustomEnabled
                    val uiTimeoutUs = runtimeOutputDequeueTimeoutUs

                    val firstOutTimeoutUs = when {
                        prefs != null && prefs.immediateFrameDelivery -> 0
                        customTimeoutEnabled -> max(0, uiTimeoutUs)
                        else -> when (pacingMode) {
                            PreferenceConfiguration.FRAME_PACING_BALANCED -> OUT_DEQUEUE_TIMEOUT_BALANCED_US
                            PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS -> OUT_DEQUEUE_TIMEOUT_MAX_SMOOTH_US
                            PreferenceConfiguration.FRAME_PACING_CAP_FPS -> OUT_DEQUEUE_TIMEOUT_CAP_FPS_US
                            PreferenceConfiguration.FRAME_PACING_GPU_RAW,
                            PreferenceConfiguration.FRAME_PACING_MIN_LATENCY,
                            PreferenceConfiguration.FRAME_PACING_WARP,
                            PreferenceConfiguration.FRAME_PACING_WARP2 -> 500
                            else -> if (useAsyncCodec) OUT_DEQUEUE_TIMEOUT_MAX_SMOOTH_US else 5000
                        }
                    }

                    val nowNs = System.nanoTime()
                    if (nowNs - lastCleanupNs > cleanupIntervalNs) {
                        decodeLatencyTracker.maybeCleanup()
                        lastCleanupNs = nowNs
                    }

                    try {
                        val outIndex = nextOutputIndex(info, firstOutTimeoutUs)
                        if (outIndex >= 0) {
                            var presentationTimeUs = info.presentationTimeUs
                            var lastFlags = info.flags
                            var lastIndex = outIndex

                            numFramesOut++

                            val nowNsLocal = System.nanoTime()
                            var lastDequeueTimeNs = if (useAsyncCodec && asyncCodec.lastOutputReadyNs != 0L) {
                                asyncCodec.lastOutputReadyNs
                            } else {
                                nowNsLocal
                            }

                            try {
                                decodeLatencyTracker.onDequeue(
                                    activeWindowVideoStats,
                                    presentationTimeUs,
                                    lastDequeueTimeNs,
                                    USE_FRAME_RENDER_TIME
                                )
                            } catch (_: Throwable) {}

                            if (!useAsyncCodec && syncLfrEnabled) {
                                var drained = 0
                                while (drained < SYNC_LFR_MAX_DRAIN_PER_TICK &&
                                    (lastFlags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0
                                ) {
                                    val newerIndex = nextOutputIndex(lfrInfo, 0)
                                    if (newerIndex >= 0) {
                                        val newerDequeueNs = System.nanoTime()
                                        releaseOutputBufferNoRenderLocked(videoDecoder, lastIndex)

                                        lastIndex = newerIndex
                                        presentationTimeUs = lfrInfo.presentationTimeUs
                                        lastFlags = lfrInfo.flags
                                        lastDequeueTimeNs = newerDequeueNs

                                        numFramesOut++
                                        drained++

                                        try {
                                            decodeLatencyTracker.onDequeue(
                                                activeWindowVideoStats,
                                                presentationTimeUs,
                                                lastDequeueTimeNs,
                                                USE_FRAME_RENDER_TIME
                                            )
                                        } catch (_: Throwable) {}
                                        continue
                                    }

                                    if (newerIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                        handleOutputFormatChangeSync()
                                        continue
                                    }

                                    @Suppress("DEPRECATION")
                                    if (newerIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                                        continue
                                    }

                                    break
                                }
                            }

                            if (pacingMode != PreferenceConfiguration.FRAME_PACING_BALANCED) {
                                val eos = (lastFlags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                                val useNanoPacer = prefs != null && prefs.fastVsync

                                if (useNanoPacer) {
                                    val rrHz = when {
                                        refreshRateHz > 1f -> refreshRateHz
                                        refreshRate > 0 -> refreshRate.toFloat()
                                        else -> 60f
                                    }
                                    nanoPacer.updatePacingMode(
                                        true,
                                        this@MediaCodecDecoderRenderer.streamTargetFps,
                                        rrHz
                                    )

                                    nanoLatest.index = lastIndex
                                    nanoLatest.ptsUs = presentationTimeUs
                                    nanoLatest.flags = lastFlags
                                    nanoLatest.dequeueNs = lastDequeueTimeNs

                                    numFramesOut += nanoPacer.waitAndDrainLatest(info, nanoLatest, nanoPacerCallbacks)

                                    lastIndex = nanoLatest.index
                                    presentationTimeUs = nanoLatest.ptsUs
                                    lastFlags = nanoLatest.flags
                                    lastDequeueTimeNs = nanoLatest.dequeueNs
                                }

                                val recovery = nvidiaLatencyRecoveryController
                                val activeRecovery = if (recovery != null && recovery.isActive()) recovery else null
                                val recoveryEpoch = activeRecovery?.epoch() ?: 0L
                                val recoveryDecision = activeRecovery?.decide(recoveryEpoch, presentationTimeUs, lastFlags)
                                val recoveryDrop = recoveryDecision != null && recoveryDecision.drop
                                var releaseSucceeded = false
                                try {
                                    if (recoveryDrop) {
                                        val recoveryCodec = videoDecoder
                                            ?: throw IllegalStateException("NVIDIA recovery codec disappeared before release")
                                        releaseOutputBufferNoRenderLocked(recoveryCodec, lastIndex)
                                    } else {
                                        releaseBufferAccordingToMode(lastIndex, presentationTimeUs)
                                        activeWindowVideoStats.totalFramesRendered++
                                    }
                                    releaseSucceeded = true
                                } finally {
                                    if (activeRecovery != null && recoveryDecision != null) {
                                        activeRecovery.released(recoveryDecision, releaseSucceeded)
                                    }
                                }

                                if (eos) {
                                    VibemisLog.info("Output EOS received")
                                    continue
                                }
                            } else {
                                while (outputBufferQueue.remainingCapacity() == 0) {
                                    val old = outputBufferQueue.poll() ?: break
                                    try { releaseOutputBufferNoRenderLocked(videoDecoder, old) } catch (_: Throwable) {}
                                }

                                if (!outputBufferQueue.offer(lastIndex)) {
                                    try { releaseOutputBufferNoRenderLocked(videoDecoder, lastIndex) } catch (_: Throwable) {}
                                }

                                val eos = (lastFlags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                                if (eos) {
                                    VibemisLog.info("Output EOS received")
                                    continue
                                }
                            }
                        } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            if (!useAsyncCodec) {
                                handleOutputFormatChangeSync()
                            }
                        } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            if (firstOutTimeoutUs == 0) {
                                inputNonBlockingBackoff()
                            }
                        }
                    } catch (e: IllegalStateException) {
                        handleDecoderException(e)
                    } finally {
                        doCodecRecoveryIfRequired(CR_FLAG_RENDER_THREAD)
                    }
                }
                rendererTid = 0
            }
        }.apply {
            priority = Thread.NORM_PRIORITY + 2
            start()
        }
    }

    private fun fetchNextInputBuffer(): Boolean {
        val startNs = System.nanoTime()
        if (stopping) return false

        val codec = videoDecoder
        if (codec == null) {
            inputTryAgainStreak = 0
            inputDequeueHangStartMs = 0L
            nextInputBufferIndex = -1
            nextInputBuffer = null
            return false
        }

        if (nextInputBuffer != null) {
            if (nextInputBufferIndex >= 0) {
                inputTryAgainStreak = 0
                inputDequeueHangStartMs = 0L
                return true
            }
            nextInputBufferIndex = -1
            nextInputBuffer = null
        } else if (nextInputBufferIndex >= 0) {
            nextInputBufferIndex = -1
            inputTryAgainStreak = 0
            inputDequeueHangStartMs = 0L
        }

        val dequeueTimeoutUs = getInputDequeueTimeoutUs()
        var pendingException: IllegalStateException? = null
        var noBufferThisCall = false

        try {
            if (nextInputBuffer == null && nextInputBufferIndex < 0) {
                val t0 = System.nanoTime()
                nextInputBufferIndex = nextInputIndex(dequeueTimeoutUs)
                val elapsedUs = (System.nanoTime() - t0) / 1_000L

                if (nextInputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (dequeueTimeoutUs == 0) {
                        nextInputBufferIndex = -1
                        noBufferThisCall = true
                    } else {
                        val remainingUs = max(0, dequeueTimeoutUs - elapsedUs.toInt())
                        val quickBackoffUs = min(remainingUs, 1000)
                        if (quickBackoffUs > 0) {
                            nextInputBufferIndex = nextInputIndex(quickBackoffUs)
                        }
                    }
                }
            }

            if (!noBufferThisCall && nextInputBufferIndex >= 0) {
                inputTryAgainStreak = 0
                inputDequeueHangStartMs = 0L

                val buf = codec.getInputBuffer(nextInputBufferIndex)
                if (buf == null) {
                    val badIndex = nextInputBufferIndex
                    nextInputBufferIndex = -1
                    nextInputBuffer = null
                    inputTryAgainStreak = 0
                    inputDequeueHangStartMs = 0L
                    throw IllegalStateException("getInputBuffer() returned null for index $badIndex")
                }
                buf.clear()
                nextInputBuffer = buf
            }
        } catch (e: IllegalStateException) {
            pendingException = e
            inputTryAgainStreak = 0
            inputDequeueHangStartMs = 0L
            nextInputBufferIndex = -1
            nextInputBuffer = null
        }

        val codecRecovered = doCodecRecoveryIfRequired(CR_FLAG_INPUT_THREAD)
        if (codecRecovered) {
            inputTryAgainStreak = 0
            inputDequeueHangStartMs = 0L
            nextInputBufferIndex = -1
            nextInputBuffer = null
            return false
        }

        if (pendingException != null) {
            handleDecoderException(pendingException)
            return false
        }

        if (noBufferThisCall) return false

        if (nextInputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
            inputTryAgainStreak++
            val nowMs = SystemClock.uptimeMillis()

            if (inputDequeueHangStartMs == 0L) {
                inputDequeueHangStartMs = nowMs
            } else if ((nowMs - inputDequeueHangStartMs) >= 5000 && initialException == null) {
                val decoderHungException = DecoderHungException((nowMs - inputDequeueHangStartMs).toInt())
                if (!coldCfg.reportedCrash) {
                    coldCfg.reportedCrash = true
                    crashListener.notifyCrash(decoderHungException)
                }
                throw RendererException(this, decoderHungException)
            }

            nextInputBufferIndex = -1
            return false
        }

        val dtNs = System.nanoTime() - startNs
        if (dtNs >= 20_000_000L) {
            VibemisLog.warning("Dequeue input buffer ran long: ${dtNs / 1_000_000L} ms")
        }

        return nextInputBuffer != null
    }

    override fun start() {
        initFramePacingFromSettings()
        // NVIDIA latency recovery is opt-in. When disabled or statically incompatible,
        // do not instantiate the controller; all hot-path hooks remain null checks only.
        nvidiaLatencyRecoveryController = if (isNvidiaLatencyRecoveryRequestedAndStaticallyEligible()) {
            NvidiaLatencyRecoveryController.open(videoDecoder?.name)
        } else {
            null
        }
        startRendererThread()
        startChoreographerThread()
        try {
            VibemisBridge.pinHotThreadsToBigCores()
        } catch (_: Throwable) {}
    }

    fun prepareForStop() {
        nvidiaLatencyRecoveryController?.stop()
        stopping = true
        try { detachAsyncCodec() } catch (_: Throwable) {}
        drainOutputBufferQueueNoRender()
        decodeLatencyTracker.clear()

        rendererThread?.interrupt()
        resetInputBufferState()
        nanoPacer.reset()

        glUpscaler?.release()
        glUpscaler = null

        videoDecoder?.let {
            try { it.release() } catch (_: Throwable) {}
            videoDecoder = null
        }

        perfOverlayComposer.reset()

        synchronized(codecRecoveryMonitor) {
            codecRecoveryType.set(CR_RECOVERY_TYPE_NONE)
            @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
            (codecRecoveryMonitor as Object).notifyAll()
        }

        choreographerHandler?.post {
            try { choreographerHandlerThread?.quit() } catch (_: Throwable) {}
            try { Choreographer.getInstance().removeFrameCallback(this@MediaCodecDecoderRenderer) } catch (_: Throwable) {}
        }
    }

    private fun readFramePacingNameOverlayFirst(): String {
        return try {
            val overlay = ProfilesManager.getInstance().getOverlayingSharedPreferences(context)
            if (overlay.contains("frame_pacing")) {
                val v = overlay.getString("frame_pacing", "latency")
                if (v != null) return v
            }
            val ui = PreferenceConfiguration.getDefaultSharedPreferences(context)
            ui.getString("frame_pacing", "latency") ?: "latency"
        } catch (_: Throwable) {
            "latency"
        }
    }

    private fun readFramePacingModeOverlayFirst(): Int =
        mapFramePacingNameToMode(readFramePacingNameOverlayFirst())

    private fun readBooleanOverlayFirst(key: String, def: Boolean): Boolean {
        try {
            val overlay = ProfilesManager.getInstance().getOverlayingSharedPreferences(context)
            if (overlay.contains(key)) {
                return overlay.getBoolean(key, def)
            }
        } catch (_: Throwable) {}

        try {
            val ui = PreferenceConfiguration.getDefaultSharedPreferences(context)
            return ui.getBoolean(key, def)
        } catch (_: Throwable) {}

        return def
    }

    private fun applyUpscalerVsyncSettingIfSupported() {
        glUpscaler?.applyVsyncSetting()
    }

    private fun applyUpscalerThreadPrioritiesIfSupported() {
        glUpscaler?.applyThreadPriorities()
    }

    private fun drainOutputBufferQueueNoRender() {
        while (true) {
            val idx = outputBufferQueue.poll() ?: break
            try {
                releaseOutputBufferNoRenderLocked(videoDecoder, idx)
            } catch (_: Throwable) {}
        }
    }

    private fun applyFramePacingTransition(oldPacing: Int, newPacing: Int) {
        if (prefs != null) {
            prefs.framePacing = newPacing
        }
        updateAsyncPreferLowerDelaysFromCurrentPrefs(newPacing)

        if (oldPacing == PreferenceConfiguration.FRAME_PACING_BALANCED &&
            newPacing != PreferenceConfiguration.FRAME_PACING_BALANCED
        ) {
            drainOutputBufferQueueNoRender()
            lastRenderedFrameTimeNanos = 0L
            lastPacingStreamFps = -1f
            lastPacingDisplayHz = -1f
            vsyncsPerFrame = 1.0
            vsyncAccumulator = 0.0
            stopChoreographerThread()
        }

        if (newPacing == PreferenceConfiguration.FRAME_PACING_BALANCED) {
            outputBufferQueue.clear()
            lastRenderedFrameTimeNanos = 0L
            lastPacingStreamFps = -1f
            lastPacingDisplayHz = -1f
            vsyncsPerFrame = 1.0
            vsyncAccumulator = 0.0
            startChoreographerThread()
        }

        nanoPacer.reset()
        applyVideoThreadPriorities()
        applyUpscalerThreadPrioritiesIfSupported()
    }

    private fun initFramePacingFromSettings() {
        val selected = readFramePacingModeOverlayFirst()

        var requestedVsync = readBooleanOverlayFirst("checkbox_Vsync", false)
        val requestedFastVsync = readBooleanOverlayFirst("checkbox_fastVsync", false)

        if (requestedFastVsync && requestedVsync) {
            requestedVsync = false
        }

        val forcedDisableVsync = selected == PreferenceConfiguration.FRAME_PACING_BALANCED && requestedVsync
        if (forcedDisableVsync) {
            requestedVsync = false
        }

        var enableVsyncChanged = false
        var fastVsyncChanged = false
        if (prefs != null) {
            if (prefs.fastVsync != requestedFastVsync) {
                prefs.fastVsync = requestedFastVsync
                fastVsyncChanged = true
            }
            if (prefs.enableVsync != requestedVsync) {
                prefs.enableVsync = requestedVsync
                enableVsyncChanged = true
            }
        }

        if (forcedDisableVsync) {
            VibemisLog.info("Disabling VSync because Balanced pacing is active")
        }

        if (enableVsyncChanged || fastVsyncChanged) {
            applyUpscalerVsyncSettingIfSupported()
            applyVideoThreadPriorities()
            applyUpscalerThreadPrioritiesIfSupported()
        }

        appliedFramePacing = selected
        nextFramePacingPollNs = 0L
        if (prefs != null) {
            prefs.framePacing = selected
        }
        updateAsyncPreferLowerDelaysFromCurrentPrefs(selected)
    }

    private fun maybeApplyRuntimeFramePacing() {
        val nowNs = System.nanoTime()
        if (nowNs < nextFramePacingPollNs) return
        nextFramePacingPollNs = nowNs + FRAME_PACING_POLL_INTERVAL_NS

        val selected = readFramePacingModeOverlayFirst()
        var requestedVsync = readBooleanOverlayFirst("checkbox_Vsync", false)
        val requestedFastVsync = readBooleanOverlayFirst("checkbox_fastVsync", false)

        if (requestedFastVsync && requestedVsync) {
            requestedVsync = false
        }

        val forcedDisableVsync = selected == PreferenceConfiguration.FRAME_PACING_BALANCED && requestedVsync
        if (forcedDisableVsync) {
            requestedVsync = false
        }

        var enableVsyncChanged = false
        var fastVsyncChanged = false
        if (prefs != null) {
            if (prefs.fastVsync != requestedFastVsync) {
                prefs.fastVsync = requestedFastVsync
                fastVsyncChanged = true
            }
            if (prefs.enableVsync != requestedVsync) {
                prefs.enableVsync = requestedVsync
                enableVsyncChanged = true
            }
        }

        if (forcedDisableVsync && enableVsyncChanged) {
            VibemisLog.info("Disabling VSync because Balanced pacing is active (runtime check)")
        }

        if (enableVsyncChanged || fastVsyncChanged) {
            applyUpscalerVsyncSettingIfSupported()
        }

        updateAsyncPreferLowerDelaysFromCurrentPrefs(selected)
        if (selected == appliedFramePacing) return

        applyFramePacingTransition(appliedFramePacing, selected)
        appliedFramePacing = selected
    }

    override fun stop() {
        prepareForStop()

        decodeLatencyTracker.clear()
        perfOverlayComposer.reset()
        try { detachAsyncCodec() } catch (_: Throwable) {}
        drainOutputBufferQueueNoRender()
        resetInputBufferState()
        nanoPacer.reset()

        choreographerHandlerThread?.let { choreo ->
            try {
                choreo.join(1000)
            } catch (e: InterruptedException) {
                e.printStackTrace()
                Thread.currentThread().interrupt()
            }
            if (choreo.isAlive) {
                try { choreo.interrupt() } catch (_: Throwable) {}
                try {
                    choreo.join(250)
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                    Thread.currentThread().interrupt()
                }
                if (choreo.isAlive) {
                    VibemisLog.warning("Choreographer thread did not terminate in time")
                }
            }
        }

        rendererThread?.let { rt ->
            try {
                rt.join(1500)
            } catch (e: InterruptedException) {
                e.printStackTrace()
                Thread.currentThread().interrupt()
            }
            if (rt.isAlive) {
                try { rt.interrupt() } catch (_: Throwable) {}
                try {
                    rt.join(250)
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                    Thread.currentThread().interrupt()
                }
                if (rt.isAlive) {
                    VibemisLog.warning("Renderer thread did not terminate in time")
                }
            }
        }

        glUpscaler?.release()
        glUpscaler = null
    }

    override fun cleanup() {
        nvidiaLatencyRecoveryController?.stop()
        decodeLatencyTracker.clear()
        perfOverlayComposer.reset()
        try { detachAsyncCodec() } catch (_: Throwable) {}

        coldCfg.vpsBuffers.clear()
        coldCfg.spsBuffers.clear()
        coldCfg.ppsBuffers.clear()

        resetInputBufferState()
        nanoPacer.reset()
        drainOutputBufferQueueNoRender()
        outputBufferQueue.clear()

        glUpscaler?.release()
        glUpscaler = null

        videoDecoder?.let {
            try { it.release() } catch (_: Throwable) {}
            videoDecoder = null
        }
    }

    override fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) {
        if (coldCfg.currentHdrMetadata != null && (!enabled || hdrMetadata == null)) {
            coldCfg.currentHdrMetadata = null
        } else if (enabled && hdrMetadata != null && !coldCfg.currentHdrMetadata.contentEquals(hdrMetadata)) {
            coldCfg.currentHdrMetadata = hdrMetadata
        } else {
            return
        }

        codecRecoveryAttempts = 0
        if (!codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESTART)) {
            codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESTART)
        }
    }

    private fun queueNextInputBuffer(timestampUs: Long, codecFlags: Int): Boolean {
        var codecRecovered: Boolean
        try {
            val buf = nextInputBuffer!!
            val recovery = nvidiaLatencyRecoveryController
            val activeRecovery = if (recovery != null && recovery.isActive()) recovery else null
            videoDecoder?.queueInputBuffer(
                nextInputBufferIndex,
                0,
                buf.position(),
                timestampUs,
                codecFlags
            )
            if (activeRecovery != null && timestampUs > 0L) {
                activeRecovery.queued(timestampUs, System.nanoTime())
            }

            if (timestampUs != 0L) {
                val currentTimeUs = System.currentTimeMillis() * 1000L
                if (timestampUs > 0 && timestampUs < currentTimeUs + 3600_000_000L) {
                    decodeLatencyTracker.onEnqueue(timestampUs)
                }
            }

            nextInputBufferIndex = -1
            nextInputBuffer = null
        } catch (e: IllegalStateException) {
            if (handleDecoderException(e)) {
                nextInputBuffer?.clear()
            } else {
                nextInputBufferIndex = -1
                nextInputBuffer = null
            }
            return false
        } finally {
            codecRecovered = doCodecRecoveryIfRequired(CR_FLAG_INPUT_THREAD)
        }

        if (codecRecovered) return false
        return prefetchNextInputBuffer()
    }

    override fun submitDecodeUnit(
        decodeUnitData: ByteArray,
        decodeUnitLength: Int,
        decodeUnitType: Int,
        frameNumber: Int,
        frameType: Int,
        frameHostProcessingLatency: Char,
        receiveTimeMs: Long,
        enqueueTimeMs: Long
    ): Int {
        if (stopping) return VibemisBridge.DR_OK

        if (lastFrameNumber == 0) {
            activeWindowVideoStats.measurementStartTimestamp = SystemClock.uptimeMillis()
        } else if (frameNumber != lastFrameNumber && frameNumber != lastFrameNumber + 1) {
            activeWindowVideoStats.framesLost += frameNumber - lastFrameNumber - 1
            activeWindowVideoStats.totalFrames += frameNumber - lastFrameNumber - 1
            activeWindowVideoStats.frameLossEvents++
        }

        if (lastFrameNumber != frameNumber && frameType == VibemisBridge.FRAME_TYPE_IDR) {
            coldCfg.vpsBuffers.clear()
            coldCfg.spsBuffers.clear()
            coldCfg.ppsBuffers.clear()
        }

        lastFrameNumber = frameNumber

        if (SystemClock.uptimeMillis() >= activeWindowVideoStats.measurementStartTimestamp + 1000) {
            if (prefs == null ||
                (!prefs.enablePerfOverlay &&
                    !prefs.enablePerfOverlayLite &&
                    !prefs.enablePerfOverlayMini)
            ) {
                globalVideoStats.add(activeWindowVideoStats)
                lastWindowVideoStats.copy(activeWindowVideoStats)
                activeWindowVideoStats.clear()
                activeWindowVideoStats.measurementStartTimestamp = SystemClock.uptimeMillis()
            } else {
                val lastTwo = VideoStats()
                lastTwo.add(lastWindowVideoStats)
                lastTwo.add(activeWindowVideoStats)
                val fps = lastTwo.fps
                val decoder = when {
                    (activeVideoFormat and VibemisBridge.VIDEO_FORMAT_MASK_H264) != 0 ->
                        coldCfg.avcDecoder?.name ?: "(avc-null)"
                    (activeVideoFormat and VibemisBridge.VIDEO_FORMAT_MASK_H265) != 0 ->
                        coldCfg.hevcDecoder?.name ?: "(hevc-null)"
                    (activeVideoFormat and VibemisBridge.VIDEO_FORMAT_MASK_AV1) != 0 ->
                        coldCfg.av1Decoder?.name ?: "(av1-null)"
                    else -> "(unknown)"
                }

                val decodeDenom = if (lastTwo.decoderSamples > 0L) lastTwo.decoderSamples else lastTwo.totalFramesReceived.toLong()
                val decodeTimeMs = if (decodeDenom > 0L) {
                    (lastTwo.decoderTimeMs.toFloat() / 1000f) / decodeDenom.toFloat()
                } else {
                    0f
                }

                val endToEndTimeMs = if (lastTwo.totalFramesReceived > 0) {
                    lastTwo.endToEndLatencyMs.toFloat() / lastTwo.totalFramesReceived.toFloat()
                } else {
                    0f
                }

                val rttInfo = VibemisBridge.getEstimatedRttInfo()
                val prefsSnapshot = prefs

                val overlayNowNs = System.nanoTime()
                if (overlayNowNs - lastPerfOverlayDispatchNs >= PERF_OVERLAY_DISPATCH_INTERVAL_NS) {
                    lastPerfOverlayDispatchNs = overlayNowNs
                    perfOverlayHandler.post {
                        val hdrActiveSnapshot = isHdrActive
                        val isFsrActive = prefsSnapshot.videoUpscaleEnable && glUpscaler?.isReady == true
                        val fsrWeightMs = if (isFsrActive) glUpscaler!!.weightMs else 0f

                        val overlay = perfOverlayComposer.build(
                            context,
                            prefsSnapshot,
                            lastTwo,
                            fps,
                            decodeTimeMs,
                            rttInfo,
                            decoder,
                            endToEndTimeMs,
                            hdrActiveSnapshot,
                            coldCfg.initialWidth,
                            coldCfg.initialHeight,
                            isFsrActive,
                            fsrWeightMs
                        )

                        if (perfListener != null && prefsSnapshot.enablePerfOverlay) {
                            perfListener.onPerfUpdate(overlay.renderedLog)
                        }

                        val targetFpsMatched = fps.totalFps.toInt() == prefsSnapshot.fps.toInt()
                        if (minDecodeTime > decodeTimeMs && targetFpsMatched) {
                            minDecodeTime = decodeTimeMs
                            minDecoderLatencyFullLog = overlay.fullLog
                        }
                    }
                }

                globalVideoStats.add(activeWindowVideoStats)
                lastWindowVideoStats.copy(activeWindowVideoStats)
                activeWindowVideoStats.clear()
                activeWindowVideoStats.measurementStartTimestamp = SystemClock.uptimeMillis()
            }
        }

        var csdSubmittedForThisFrame = false

        if (frameType == VibemisBridge.FRAME_TYPE_IDR) {
            if (decodeUnitType == VibemisBridge.BUFFER_TYPE_SPS && (activeVideoFormat and VibemisBridge.VIDEO_FORMAT_MASK_H264) != 0) {
                numSpsIn++
                val rawSps = decodeUnitData.copyOf(decodeUnitLength)
                val naluBuffer = VibemisBridge.patchH264Sps(
                    rawSps,
                    coldCfg.initialWidth,
                    coldCfg.initialHeight,
                    refreshRate,
                    coldCfg.refFrameInvalidationActive,
                    coldCfg.needsBaselineSpsHack,
                    coldCfg.constrainedHighProfile
                )
                if (coldCfg.needsBaselineSpsHack) {
                    coldCfg.savedSps = rawSps
                }

                coldCfg.spsBuffers.clear()
                coldCfg.spsBuffers.add(naluBuffer)
                csdDirty = true
                return VibemisBridge.DR_OK
            } else if (decodeUnitType == VibemisBridge.BUFFER_TYPE_VPS) {
                numVpsIn++
                val naluBuffer = decodeUnitData.copyOf(decodeUnitLength)
                coldCfg.vpsBuffers.clear()
                coldCfg.vpsBuffers.add(naluBuffer)
                csdDirty = true
                return VibemisBridge.DR_OK
            } else if (decodeUnitType == VibemisBridge.BUFFER_TYPE_SPS) {
                numSpsIn++
                val naluBuffer = decodeUnitData.copyOf(decodeUnitLength)
                coldCfg.spsBuffers.add(naluBuffer)
                csdDirty = true
                return VibemisBridge.DR_OK
            } else if (decodeUnitType == VibemisBridge.BUFFER_TYPE_PPS) {
                numPpsIn++
                val naluBuffer = decodeUnitData.copyOf(decodeUnitLength)
                coldCfg.ppsBuffers.clear()
                coldCfg.ppsBuffers.add(naluBuffer)
                csdDirty = true
                return VibemisBridge.DR_OK
            } else if ((activeVideoFormat and (VibemisBridge.VIDEO_FORMAT_MASK_H264 or VibemisBridge.VIDEO_FORMAT_MASK_H265)) != 0) {
                if (!coldCfg.submittedCsd || (!coldCfg.fusedIdrFrame && csdDirty)) {
                    if (!fetchNextInputBuffer()) return VibemisBridge.DR_NEED_IDR

                    val buf = nextInputBuffer!!
                    buf.clear()

                    val csdBytes = coldCfg.vpsBuffers.sumOf { it.size } +
                        coldCfg.spsBuffers.sumOf { it.size } +
                        coldCfg.ppsBuffers.sumOf { it.size }

                    if (csdBytes > buf.remaining()) {
                        VibemisLog.info("CSD too large for one input buffer: $csdBytes > ${buf.remaining()}")
                        return VibemisBridge.DR_NEED_IDR
                    }

                    for (vpsBuffer in coldCfg.vpsBuffers) buf.put(vpsBuffer)
                    for (spsBuffer in coldCfg.spsBuffers) buf.put(spsBuffer)
                    for (ppsBuffer in coldCfg.ppsBuffers) buf.put(ppsBuffer)

                    if (!queueNextInputBuffer(0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)) {
                        return VibemisBridge.DR_NEED_IDR
                    }

                    csdSubmittedForThisFrame = true
                    coldCfg.submittedCsd = true
                    csdDirty = false

                    if (!coldCfg.fusedIdrFrame) {
                        coldCfg.vpsBuffers.clear()
                        coldCfg.spsBuffers.clear()
                        coldCfg.ppsBuffers.clear()
                    }

                    if (coldCfg.needsBaselineSpsHack) {
                        coldCfg.needsBaselineSpsHack = false
                        if (!replaySps()) return VibemisBridge.DR_NEED_IDR
                        VibemisLog.info("SPS replay complete")
                    }
                }
            }
        }

        if (frameHostProcessingLatency.code != 0) {
            if (activeWindowVideoStats.minHostProcessingLatency.code != 0) {
                activeWindowVideoStats.minHostProcessingLatency =
                    min(activeWindowVideoStats.minHostProcessingLatency.code, frameHostProcessingLatency.code).toChar()
            } else {
                activeWindowVideoStats.minHostProcessingLatency = frameHostProcessingLatency
            }
            activeWindowVideoStats.framesWithHostProcessingLatency += 1
        }
        activeWindowVideoStats.maxHostProcessingLatency =
            max(activeWindowVideoStats.maxHostProcessingLatency.code, frameHostProcessingLatency.code).toChar()
        activeWindowVideoStats.totalHostProcessingLatency += frameHostProcessingLatency.code

        activeWindowVideoStats.totalFramesReceived++
        activeWindowVideoStats.totalFrames++

        if (!FRAME_RENDER_TIME_ONLY) {
            activeWindowVideoStats.totalTimeMs += enqueueTimeMs - receiveTimeMs
        }

        if (!fetchNextInputBuffer()) return VibemisBridge.DR_NEED_IDR

        var codecFlags = 0
        if (frameType == VibemisBridge.FRAME_TYPE_IDR) {
            codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME

            if (coldCfg.fusedIdrFrame && !csdSubmittedForThisFrame) {
                val csdBytes = coldCfg.vpsBuffers.sumOf { it.size } +
                    coldCfg.spsBuffers.sumOf { it.size } +
                    coldCfg.ppsBuffers.sumOf { it.size }

                val buf = nextInputBuffer!!
                if (csdBytes + decodeUnitLength > buf.remaining()) {
                    buf.clear()
                    if (csdBytes > buf.remaining()) {
                        VibemisLog.info("Fused CSD too large for input buffer: $csdBytes > ${buf.remaining()}")
                        return VibemisBridge.DR_NEED_IDR
                    }
                    for (vpsBuffer in coldCfg.vpsBuffers) buf.put(vpsBuffer)
                    for (spsBuffer in coldCfg.spsBuffers) buf.put(spsBuffer)
                    for (ppsBuffer in coldCfg.ppsBuffers) buf.put(ppsBuffer)

                    if (!queueNextInputBuffer(0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)) {
                        return VibemisBridge.DR_NEED_IDR
                    }

                    csdSubmittedForThisFrame = true
                    coldCfg.submittedCsd = true
                    csdDirty = false

                    if (!fetchNextInputBuffer()) return VibemisBridge.DR_NEED_IDR
                } else {
                    for (vpsBuffer in coldCfg.vpsBuffers) buf.put(vpsBuffer)
                    for (spsBuffer in coldCfg.spsBuffers) buf.put(spsBuffer)
                    for (ppsBuffer in coldCfg.ppsBuffers) buf.put(ppsBuffer)
                    csdDirty = false
                }
            }
        }

        var timestampUs = enqueueTimeMs * 1000
        if (timestampUs <= lastTimestampUs) {
            timestampUs = lastTimestampUs + 1
        }
        lastTimestampUs = timestampUs
        numFramesIn++

        val buf = nextInputBuffer!!
        if (decodeUnitLength > buf.limit() - buf.position()) {
            val exception = IllegalArgumentException(
                "Decode unit length $decodeUnitLength too large for input buffer ${buf.limit()}"
            )
            if (!coldCfg.reportedCrash) {
                coldCfg.reportedCrash = true
                crashListener.notifyCrash(exception)
            }
            throw RendererException(this, exception)
        }

        buf.put(decodeUnitData, 0, decodeUnitLength)
        if (!queueNextInputBuffer(timestampUs, codecFlags)) {
            return VibemisBridge.DR_NEED_IDR
        }

        return VibemisBridge.DR_OK
    }

    private fun replaySps(): Boolean {
        if (!fetchNextInputBuffer()) return false
        val buf = nextInputBuffer!!

        val saved = coldCfg.savedSps ?: return false
        val replayed = VibemisBridge.patchH264Sps(
            saved,
            coldCfg.initialWidth,
            coldCfg.initialHeight,
            refreshRate,
            coldCfg.refFrameInvalidationActive,
            false,
            coldCfg.constrainedHighProfile
        )
        buf.put(replayed)
        coldCfg.savedSps = null

        return queueNextInputBuffer(0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
    }

    override fun getCapabilities(): Int {
        var capabilities = 0
        capabilities = capabilities or VibemisBridge.CAPABILITY_SLICES_PER_FRAME(coldCfg.optimalSlicesPerFrame)
        if (coldCfg.refFrameInvalidationAvc) {
            capabilities = capabilities or VibemisBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AVC
        }
        if (coldCfg.refFrameInvalidationHevc) {
            capabilities = capabilities or VibemisBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_HEVC
        }
        if (coldCfg.refFrameInvalidationAv1) {
            capabilities = capabilities or VibemisBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AV1
        }
        if (coldCfg.directSubmit) {
            capabilities = capabilities or VibemisBridge.CAPABILITY_DIRECT_SUBMIT
        }
        return capabilities
    }

    fun getAverageEndToEndLatency(): Int {
        if (globalVideoStats.totalFramesReceived == 0) return 0
        return (globalVideoStats.totalTimeMs / globalVideoStats.totalFramesReceived).toInt()
    }

    fun getAveragePureDecoderLatency(): Int {
        val samples = if (globalVideoStats.decoderSamples > 0) {
            globalVideoStats.decoderSamples
        } else {
            globalVideoStats.totalFramesReceived.toLong()
        }
        if (samples == 0L) return 0
        return ((globalVideoStats.decoderTimeMs / 1000L) / samples).toInt()
    }

    fun getAverageOldEndToEndLatency(): Int {
        if (globalVideoStats.totalFramesReceived == 0) return 0
        return (globalVideoStats.endToEndLatencyMs / globalVideoStats.totalFramesReceived).toInt()
    }

    fun getAverageDecoderLatency(): Int = getAveragePureDecoderLatency()

    fun performanceWasTracked(): Boolean = minDecodeTime < Float.MAX_VALUE

    @SuppressLint("DefaultLocale")
    fun getMinDecoderLatency(): String = String.format("%1$.2f", minDecodeTime)

    internal class DecoderHungException(private val hangTimeMs: Int) : RuntimeException() {
        override fun toString(): String =
            "Hang time: $hangTimeMs ms${RendererException.DELIMITER}${super.toString()}"
    }

    internal class RendererException(
        renderer: MediaCodecDecoderRenderer,
        originalException: Exception
    ) : RuntimeException() {
        private val text: String = generateText(renderer, originalException)

        override fun toString(): String = text

        private fun generateText(renderer: MediaCodecDecoderRenderer, originalException: Exception): String {
            val sb = StringBuilder()
            val phase = when {
                renderer.numVpsIn == 0 && renderer.numSpsIn == 0 && renderer.numPpsIn == 0 -> "PreSPSError"
                renderer.numSpsIn > 0 && renderer.numPpsIn == 0 -> "PrePPSError"
                renderer.numPpsIn > 0 && renderer.numFramesIn == 0 -> "PreIFrameError"
                renderer.numFramesIn > 0 && renderer.coldCfg.outputFormat == null -> "PreOutputConfigError"
                renderer.coldCfg.outputFormat != null && renderer.numFramesOut == 0 -> "PreOutputError"
                renderer.numFramesOut <= renderer.refreshRate * 30 -> "EarlyOutputError"
                else -> "ErrorWhileStreaming"
            }
            sb.append(phase)
            sb.append("Format: ").append(String.format("%x", renderer.activeVideoFormat)).append(DELIMITER)
            sb.append("AVC Decoder: ").append(renderer.coldCfg.avcDecoder?.name ?: "(none)").append(DELIMITER)
            sb.append("HEVC Decoder: ").append(renderer.coldCfg.hevcDecoder?.name ?: "(none)").append(DELIMITER)
            sb.append("AV1 Decoder: ").append(renderer.coldCfg.av1Decoder?.name ?: "(none)").append(DELIMITER)

            renderer.coldCfg.avcDecoder?.let { avc ->
                val caps = avc.getCapabilitiesForType("video/avc").videoCapabilities!!
                sb.append("AVC supported width range: ").append(caps.supportedWidths).append(DELIMITER)
                try {
                    val fpsRange = caps.getAchievableFrameRatesFor(renderer.coldCfg.initialWidth, renderer.coldCfg.initialHeight)
                    sb.append("AVC achievable FPS range: ").append(fpsRange).append(DELIMITER)
                } catch (_: IllegalArgumentException) {
                    sb.append("AVC achievable FPS range: UNSUPPORTED!").append(DELIMITER)
                }
            }

            renderer.coldCfg.hevcDecoder?.let { hevc ->
                val caps = hevc.getCapabilitiesForType("video/hevc").videoCapabilities!!
                sb.append("HEVC supported width range: ").append(caps.supportedWidths).append(DELIMITER)
                try {
                    val fpsRange = caps.getAchievableFrameRatesFor(renderer.coldCfg.initialWidth, renderer.coldCfg.initialHeight)
                    sb.append("HEVC achievable FPS range: ").append(fpsRange).append(DELIMITER)
                } catch (_: IllegalArgumentException) {
                    sb.append("HEVC achievable FPS range: UNSUPPORTED!").append(DELIMITER)
                }
            }

            renderer.coldCfg.av1Decoder?.let { av1 ->
                val caps = av1.getCapabilitiesForType("video/av01").videoCapabilities!!
                sb.append("AV1 supported width range: ").append(caps.supportedWidths).append(DELIMITER)
                try {
                    val fpsRange = caps.getAchievableFrameRatesFor(renderer.coldCfg.initialWidth, renderer.coldCfg.initialHeight)
                    sb.append("AV1 achievable FPS range: ").append(fpsRange).append(DELIMITER)
                } catch (_: IllegalArgumentException) {
                    sb.append("AV1 achievable FPS range: UNSUPPORTED!").append(DELIMITER)
                }
            }

            sb.append("Configured format: ").append(renderer.coldCfg.configuredFormat).append(DELIMITER)
            sb.append("Input format: ").append(renderer.coldCfg.inputFormat).append(DELIMITER)
            sb.append("Output format: ").append(renderer.coldCfg.outputFormat).append(DELIMITER)
            sb.append("Adaptive playback: ").append(renderer.coldCfg.adaptivePlayback).append(DELIMITER)
            sb.append("GL Renderer: ").append(renderer.glRenderer).append(DELIMITER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                sb.append("SOC: ").append(Build.SOC_MANUFACTURER).append(" - ").append(Build.SOC_MODEL).append(DELIMITER)
                sb.append("Performance class: ").append(Build.VERSION.MEDIA_PERFORMANCE_CLASS).append(DELIMITER)
            }
            sb.append("Consecutive crashes: ").append(renderer.consecutiveCrashCount).append(DELIMITER)
            sb.append("RFI active: ").append(renderer.coldCfg.refFrameInvalidationActive).append(DELIMITER)
            sb.append("Using modern SPS patching: true").append(DELIMITER)
            sb.append("Fused IDR frames: ").append(renderer.coldCfg.fusedIdrFrame).append(DELIMITER)
            sb.append("Video dimensions: ").append(renderer.coldCfg.initialWidth).append("x").append(renderer.coldCfg.initialHeight).append(DELIMITER)
            sb.append("FPS target: ").append(renderer.refreshRate).append(DELIMITER)
            sb.append("Bitrate: ").append(renderer.prefs?.bitrate ?: 0).append(" Kbps").append(DELIMITER)
            sb.append("CSD stats: ").append(renderer.numVpsIn).append(", ").append(renderer.numSpsIn).append(", ").append(renderer.numPpsIn).append(DELIMITER)
            sb.append("Frames in-out: ").append(renderer.numFramesIn).append(", ").append(renderer.numFramesOut).append(DELIMITER)
            sb.append("Total frames received: ").append(renderer.globalVideoStats.totalFramesReceived).append(DELIMITER)
            sb.append("Total frames rendered: ").append(renderer.globalVideoStats.totalFramesRendered).append(DELIMITER)
            sb.append("Frame losses: ").append(renderer.globalVideoStats.framesLost).append(" in ").append(renderer.globalVideoStats.frameLossEvents).append(" loss events").append(DELIMITER)
            sb.append("Average end-to-end client latency: ").append(renderer.getAverageEndToEndLatency()).append("ms").append(DELIMITER)
            sb.append("Average hardware decoder latency: ").append(renderer.getAverageDecoderLatency()).append("ms").append(DELIMITER)
            sb.append("Frame pacing mode: ").append(renderer.prefs?.framePacing ?: 0).append(DELIMITER)

            if (originalException is CodecException) {
                sb.append("Diagnostic Info: ").append(originalException.diagnosticInfo).append(DELIMITER)
                sb.append("Recoverable: ").append(originalException.isRecoverable).append(DELIMITER)
                sb.append("Transient: ").append(originalException.isTransient).append(DELIMITER)
                sb.append("Codec Error Code: ").append(originalException.errorCode).append(DELIMITER)
            }
            sb.append(originalException.toString())
            return sb.toString()
        }

        companion object {
            private const val serialVersionUID = 8985937536997012406L
            @JvmField
            val DELIMITER: String = if (BuildConfig.DEBUG) "\n" else " | "
        }
    }

    private val asyncOutputRelease = AsyncCodecAdapter.OutputRelease { codec, index ->
        try { releaseOutputBufferNoRenderLocked(codec, index) } catch (_: Throwable) {}
    }

    private val asyncCallbacks = object : AsyncCodecAdapter.Callbacks {
        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            try {
                coldCfg.outputFormat = format
                VibemisLog.info("Output format changed (async): ${coldCfg.outputFormat}")

                var isHdr = false
                try {
                    val std = try { format.getInteger("color-standard") } catch (_: Throwable) { -1 }
                    val tr = try { format.getInteger("color-transfer") } catch (_: Throwable) { -1 }
                    isHdr = std == MediaFormat.COLOR_STANDARD_BT2020 &&
                        (tr == MediaFormat.COLOR_TRANSFER_ST2084 || tr == MediaFormat.COLOR_TRANSFER_HLG)
                } catch (_: Throwable) {}

                if (isHdrActive != isHdr) {
                    isHdrActive = isHdr
                    try { Game.updateHdrWindowMode(isHdr) } catch (_: Throwable) {}
                }
            } catch (t: Throwable) {
                VibemisLog.warning("onOutputFormatChanged(async) failed: $t")
            }
        }

        override fun onCodecError(codec: MediaCodec, e: CodecException) {
            try {
                VibemisLog.warning("[Video] MediaCodec async error: $e")
                if (!handleDecoderException(e)) {
                    VibemisLog.info("Async error queued for recovery")
                }
            } catch (t: Throwable) {
                VibemisLog.severe("Error in async error handler: $t")
            }
        }
    }

    private fun updateAsyncPreferLowerDelaysFromCurrentPrefs(effectivePacing: Int) {
        asyncCodec.setPreferLowerDelays(computeAsyncPreferLowerDelaysFromCurrentPrefs(effectivePacing))
    }

    private fun nextInputIndex(timeoutUs: Int): Int {
        val codec = videoDecoder ?: return MediaCodec.INFO_TRY_AGAIN_LATER
        return if (!useAsyncCodec) {
            codec.dequeueInputBuffer(timeoutUs.toLong())
        } else {
            asyncCodec.dequeueInputIndex(timeoutUs)
        }
    }

    private fun nextOutputIndex(outInfo: BufferInfo, timeoutUs: Int): Int {
        val codec = videoDecoder
        if (codec == null) {
            asyncCodec.resetLastOutputReadyNs()
            return MediaCodec.INFO_TRY_AGAIN_LATER
        }
        val recovery = nvidiaLatencyRecoveryController
        val activeRecovery = if (recovery != null && recovery.isActive()) recovery else null
        val recoveryEpoch = activeRecovery?.epoch() ?: 0L
        return if (!useAsyncCodec) {
            asyncCodec.resetLastOutputReadyNs()
            val outputIndex = codec.dequeueOutputBuffer(outInfo, timeoutUs.toLong())
            if (activeRecovery != null && outputIndex >= 0) {
                activeRecovery.output(recoveryEpoch, System.nanoTime(), outInfo.presentationTimeUs)
            }
            outputIndex
        } else {
            val outputIndex = asyncCodec.dequeueOutputIndex(codec, outInfo, timeoutUs, asyncOutputRelease)
            if (activeRecovery != null && outputIndex >= 0) {
                activeRecovery.output(recoveryEpoch, asyncCodec.lastOutputReadyNs, outInfo.presentationTimeUs)
            }
            outputIndex
        }
    }

    private fun attachAsyncCodecIfNeeded() {
        val codec = videoDecoder
        if (!useAsyncCodec || codec == null) return
        updateAsyncPreferLowerDelaysFromCurrentPrefs(getEffectivePacingForThreadPriorities())
        asyncCodec.attachIfNeeded(
            codec,
            desiredCodecCallbackOsPriority(getEffectivePacingForThreadPriorities()),
            asyncCallbacks,
            asyncOutputRelease
        )
        applyVideoThreadPriorities()
    }

    private fun detachAsyncCodec() {
        asyncCodec.detach(videoDecoder, asyncOutputRelease)
    }

    fun isAsyncDecodingActive(): Boolean = useAsyncCodec && asyncCodec.isActive

    fun getAsyncDecodingStatus(): String =
        "AsyncDecoding: ${if (useAsyncCodec) "ENABLED" else "DISABLED"} " +
            "(API ${Build.VERSION.SDK_INT}, callbackThread=${if (asyncCodec.isActive) "alive" else "null"}, " +
            "inputQueue=${asyncCodec.inputQueueSize}, outputQueue=${asyncCodec.outputQueueSize})"

    private fun drainAsyncNoRenderReleaseQueue() {
        asyncCodec.drainNoRenderReleaseQueue(videoDecoder, asyncOutputRelease)
    }

    private fun isNvidiaLatencyRecoveryRequestedAndStaticallyEligible(): Boolean {
        val p = prefs ?: return false
        if (!p.nvidiaLatencyRecoveryEnabled) return false
        return NvidiaLatencyRecoveryEligibility.staticModeMask(
            p.framePacing,
            useAsyncCodec,
            syncLfrEnabled,
            p.fastVsync
        ) == 0
    }

    private fun nvidiaLatencyRecoveryModeMask(): Int {
        val p = prefs
        val pacing = p?.framePacing ?: Int.MIN_VALUE
        var mask = NvidiaLatencyRecoveryEligibility.staticModeMask(
            pacing,
            useAsyncCodec,
            syncLfrEnabled,
            p != null && p.fastVsync
        )
        if (codecRecoveryType.get() != CR_RECOVERY_TYPE_NONE) mask = mask or NvidiaLatencyRecoveryController.MODE_CODEC_RECOVERY
        if (stopping) mask = mask or NvidiaLatencyRecoveryController.MODE_STOPPING
        if (videoDecoder == null) mask = mask or NvidiaLatencyRecoveryController.MODE_NO_CODEC
        if (useAsyncCodec && !asyncCodec.isActive) mask = mask or NvidiaLatencyRecoveryController.MODE_ASYNC_NOT_READY
        return mask
    }

    private fun releaseBufferAccordingToMode(bufferIndex: Int, @Suppress("UNUSED_PARAMETER") presentationTimeUs: Long) {
        when (prefs?.framePacing) {
            PreferenceConfiguration.FRAME_PACING_GPU_RAW,
            PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS,
            PreferenceConfiguration.FRAME_PACING_CAP_FPS ->
                releaseOutputBufferAtTimeLockedLollipop(videoDecoder, bufferIndex, 0L)
            else ->
                releaseOutputBufferAtTimeLockedLollipop(videoDecoder, bufferIndex, System.nanoTime())
        }
    }

    private fun handleOutputFormatChangeSync() {
        VibemisLog.info("Output format changed (sync)")
        coldCfg.outputFormat = videoDecoder?.outputFormat
        try {
            val fmt = coldCfg.outputFormat ?: return
            val std = try { fmt.getInteger("color-standard") } catch (_: Throwable) { -1 }
            val tr = try { fmt.getInteger("color-transfer") } catch (_: Throwable) { -1 }
            val isHdr = std == MediaFormat.COLOR_STANDARD_BT2020 &&
                (tr == MediaFormat.COLOR_TRANSFER_ST2084 || tr == MediaFormat.COLOR_TRANSFER_HLG)
            isHdrActive = isHdr
            try { Game.updateHdrWindowMode(isHdr) } catch (_: Throwable) {}
        } catch (_: Throwable) {}
        VibemisLog.info("New output format: ${coldCfg.outputFormat}")
    }

    private fun prefetchNextInputBuffer(): Boolean {
        if (stopping) return true

        if (nextInputBuffer != null) {
            if (nextInputBufferIndex >= 0) return true
            nextInputBuffer = null
            nextInputBufferIndex = -1
        }

        val codec = videoDecoder
        if (codec == null) {
            nextInputBuffer = null
            nextInputBufferIndex = -1
            return true
        }

        var pendingException: IllegalStateException? = null
        try {
            if (nextInputBufferIndex < 0) {
                nextInputBufferIndex = nextInputIndex(0)
                if (nextInputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    nextInputBufferIndex = -1
                    inputNonBlockingBackoff()
                    return true
                }
            }

            if (nextInputBufferIndex >= 0) {
                inputTryAgainStreak = 0
                inputDequeueHangStartMs = 0L

                val buf = codec.getInputBuffer(nextInputBufferIndex)
                if (buf == null) {
                    val badIndex = nextInputBufferIndex
                    nextInputBufferIndex = -1
                    nextInputBuffer = null
                    throw IllegalStateException("getInputBuffer() returned null for index $badIndex")
                }
                buf.clear()
                nextInputBuffer = buf
            }
        } catch (e: IllegalStateException) {
            pendingException = e
            inputTryAgainStreak = 0
            inputDequeueHangStartMs = 0L
            nextInputBufferIndex = -1
            nextInputBuffer = null
        }

        if (doCodecRecoveryIfRequired(CR_FLAG_INPUT_THREAD)) {
            inputTryAgainStreak = 0
            inputDequeueHangStartMs = 0L
            nextInputBufferIndex = -1
            nextInputBuffer = null
            return false
        }

        if (pendingException != null) {
            handleDecoderException(pendingException)
            return false
        }

        return true
    }

    private fun getInputDequeueTimeoutUs(): Int {
        val p = prefs
        val wantedFps = when {
            p != null && p.fps > 0 -> p.fps
            refreshRateHz > 1f -> refreshRateHz
            refreshRate > 0 -> refreshRate.toFloat()
            else -> 60f
        }
        val immediate = p != null && p.immediateFrameDelivery
        return when {
            immediate -> 0
            wantedFps >= 120f -> 2000
            wantedFps >= 90f -> 3000
            else -> 4000
        }
    }

    private fun getEffectivePacingForThreadPriorities(): Int {
        val prefsPacing = prefs?.framePacing ?: PreferenceConfiguration.FRAME_PACING_BALANCED
        val applied = appliedFramePacing
        return if (applied != Int.MIN_VALUE && applied == prefsPacing) applied else prefsPacing
    }

    private fun isGlVsyncGateActiveForPriorities(pacing: Int): Boolean {
        val upscaler = glUpscaler ?: return false
        val p = prefs ?: return false
        if (!upscaler.isReady) return false
        if (!p.videoUpscaleEnable || p.gpuPathMode || !p.enableVsync) return false
        return pacing != PreferenceConfiguration.FRAME_PACING_BALANCED
    }

    private fun desiredRendererOsPriority(pacing: Int): Int {
        if (isGlVsyncGateActiveForPriorities(pacing) && prefs?.immediateFrameDelivery != true) {
            return Process.THREAD_PRIORITY_DISPLAY
        }
        if (prefs?.immediateFrameDelivery == true) {
            return Process.THREAD_PRIORITY_URGENT_DISPLAY
        }
        return when (pacing) {
            PreferenceConfiguration.FRAME_PACING_MIN_LATENCY,
            PreferenceConfiguration.FRAME_PACING_GPU_RAW,
            PreferenceConfiguration.FRAME_PACING_WARP,
            PreferenceConfiguration.FRAME_PACING_WARP2 -> Process.THREAD_PRIORITY_URGENT_DISPLAY
            else -> Process.THREAD_PRIORITY_DISPLAY
        }
    }

    private fun applyVideoThreadPriorities() {
        val pacing = getEffectivePacingForThreadPriorities()
        val rendererPrio = desiredRendererOsPriority(pacing)
        val codecPrio = desiredCodecCallbackOsPriority(pacing)
        val choreoPrio = rendererPrio

        val tid = rendererTid
        if (tid != 0) {
            try { Process.setThreadPriority(tid, rendererPrio) } catch (_: Throwable) {}
        }

        rendererThread?.let { rt ->
            try {
                rt.priority = if (rendererPrio == Process.THREAD_PRIORITY_URGENT_DISPLAY) {
                    Thread.NORM_PRIORITY + 3
                } else {
                    Thread.NORM_PRIORITY + 2
                }
            } catch (_: Throwable) {}
        }

        asyncCodec.callbackThread?.let { cb ->
            try { Process.setThreadPriority(cb.threadId, codecPrio) } catch (_: Throwable) {}
        }

        choreographerHandlerThread?.let { choreo ->
            try { Process.setThreadPriority(choreo.threadId, choreoPrio) } catch (_: Throwable) {}
        }
    }

    companion object {
        private const val PERF_OVERLAY_DISPATCH_INTERVAL_NS = 100_000_000L
        private const val USE_FRAME_RENDER_TIME = false
        private const val FRAME_RENDER_TIME_ONLY = false

        private const val ENABLE_ASYNC_DECODING = true
        private const val KEY_ASYNC_DECODE_ENABLED = "checkbox_async_decode"
        private const val ASYNC_DECODE_POLL_INTERVAL_NS = 500_000_000L
        private const val KEY_ASYNC_PREFER_LOWER_DELAYS = "checkbox_async_prefer_lower_delays"
        private const val KEY_SYNC_LFR_ENABLED = "checkbox_sync_lfr"
        private const val SYNC_LFR_MAX_DRAIN_PER_TICK = 4

        private const val CR_MAX_TRIES = 10
        private const val CR_RECOVERY_TYPE_NONE = 0
        private const val CR_RECOVERY_TYPE_FLUSH = 1
        private const val CR_RECOVERY_TYPE_RESTART = 2
        private const val CR_RECOVERY_TYPE_RESET = 3

        private const val CR_FLAG_INPUT_THREAD = 0x1
        private const val CR_FLAG_RENDER_THREAD = 0x2
        private const val CR_FLAG_CHOREOGRAPHER = 0x4
        private const val CR_FLAG_ALL = CR_FLAG_INPUT_THREAD or CR_FLAG_RENDER_THREAD or CR_FLAG_CHOREOGRAPHER

        private const val EXCEPTION_REPORT_DELAY_MS = 3000
        private const val FRAME_PACING_POLL_INTERVAL_NS = 250_000_000L
        private const val DECODER_TIMING_POLL_INTERVAL_NS = 250_000_000L

        private const val OUT_DEQUEUE_TIMEOUT_BALANCED_US = 3000
        private const val OUT_DEQUEUE_TIMEOUT_MAX_SMOOTH_US = 5000
        private const val OUT_DEQUEUE_TIMEOUT_CAP_FPS_US = 4000

        private const val OUTPUT_BUFFER_QUEUE_LIMIT = 2

        private fun clampInt(v: Int, minVal: Int, maxVal: Int): Int = v.coerceIn(minVal, maxVal)

        private fun safeGetInt(sp: SharedPreferences, key: String, def: Int): Int {
            return try {
                sp.getInt(key, def)
            } catch (_: ClassCastException) {
                sp.getString(key, null)?.trim()?.toIntOrNull() ?: def
            }
        }

        private fun mapFramePacingNameToMode(v: String?): Int {
            return when (v) {
                "latency" -> PreferenceConfiguration.FRAME_PACING_MIN_LATENCY
                "balanced" -> PreferenceConfiguration.FRAME_PACING_BALANCED
                "cap-fps" -> PreferenceConfiguration.FRAME_PACING_CAP_FPS
                "smoothness" -> PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS
                "gpu-raw" -> PreferenceConfiguration.FRAME_PACING_GPU_RAW
                "warp" -> PreferenceConfiguration.FRAME_PACING_WARP
                "warp2" -> PreferenceConfiguration.FRAME_PACING_WARP2
                else -> PreferenceConfiguration.FRAME_PACING_MIN_LATENCY
            }
        }

        private fun inputNonBlockingBackoff() {
            Thread.yield()
            LockSupport.parkNanos(200_000L)
        }

        private fun desiredCodecCallbackOsPriority(@Suppress("UNUSED_PARAMETER") pacing: Int): Int =
            Process.THREAD_PRIORITY_DISPLAY
    }
}
