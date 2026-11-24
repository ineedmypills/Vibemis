package com.ineedmypills.vibemis.render

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import androidx.annotation.Keep
import com.ineedmypills.vibemis.VibemisLog
import com.ineedmypills.vibemis.nvstream.jni.VibemisBridge
import com.ineedmypills.vibemis.preferences.PreferenceConfiguration
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * GLES3 FidelityFX Super Resolution 1.0 (EASU + RCAS) and OES bypass renderer for SurfaceTexture decoder frames.
 */
class GlUpscaleRenderer(
    context: Context?,
    private val windowSurfaceInput: Surface?,
    srcW: Int,
    srcH: Int,
    private val prefs: PreferenceConfiguration?
) : SurfaceTexture.OnFrameAvailableListener {

    constructor(
        windowSurface: Surface?,
        srcW: Int,
        srcH: Int,
        prefs: PreferenceConfiguration?
    ) : this(null, windowSurface, srcW, srcH, prefs)

    private val srcW: Int = max(1, srcW)
    private val srcH: Int = max(1, srcH)

    @Volatile
    private var appContext: Context? = context?.applicationContext

    @Volatile
    private var hdrActive = false

    @Volatile
    private var hdrDirectPresent = false

    private val testPixelBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder())

    private var rcasOesChecked = false
    private var rcasOesHealthy = false
    private var rcasOesCheckAttempts = 0

    private var lastProgram = -1
    private var lastTexture = -1
    private var eglContextHandle = 0L
    private var eglWindowSurfaceHandle = 0L
    private var attachedEglContextHandle = 0L

    private val fsrStats = FsrTelemetry()
    private val easuGpuTimer = GpuTimeQueryRing()
    private val rcasGpuTimer = GpuTimeQueryRing()
    private val blitGpuTimer = GpuTimeQueryRing()

    @Volatile
    private var fsrOverlay = ""

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglWindowSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var oesTexId = 0
    private var decoderSurfaceTex: SurfaceTexture? = null
    private var decoderInputSurface: Surface? = null

    private var fbo = 0
    private var upscaledTex = 0
    private var fbW = 0
    private var fbH = 0
    private var fboW = 0
    private var fboH = 0

    private var srcFbo = 0
    private var srcRgbTex = 0
    private var srcFboW = 0
    private var srcFboH = 0

    private var progBlit = 0
    private var progOesTo2D = 0
    private var progEasuPerf = 0
    private var progEasuBalanced = 0
    private var progEasuQuality = 0
    private var progEasuPerf2D = 0
    private var progEasuBalanced2D = 0
    private var progEasuQuality2D = 0
    private var progRcas = 0
    private var progRcasFast = 0
    private var progRcasOes = 0
    private var progRcasOesHdr = 0

    private var blitUTex = -1
    private var blitUTexMat = -1
    private var oes2dUTex = -1

    private var easuPerfUTex = -1
    private var easuPerfUInvSrcSize = -1
    private var easuPerfUTexMat = -1
    private var easuQUTex = -1
    private var easuQUInvSrcSize = -1
    private var easuQUTexMat = -1
    private var easuBalUTex = -1
    private var easuBalUInvSrcSize = -1
    private var easuBalUTexMat = -1

    private var easuPerf2DUTex = -1
    private var easuPerf2DUInvSrcSize = -1
    private var easuPerf2DUTexMat = -1
    private var easuBal2DUTex = -1
    private var easuBal2DUInvSrcSize = -1
    private var easuBal2DUTexMat = -1
    private var easuQ2DUTex = -1
    private var easuQ2DUInvSrcSize = -1
    private var easuQ2DUTexMat = -1

    private var rcasUTex = -1
    private var rcasUInvDst = -1
    private var rcasUSharp = -1
    private var rcasULuma = -1

    private var rcasFastUTex = -1
    private var rcasFastUInvDst = -1
    private var rcasFastUSharp = -1
    private var rcasFastULuma = -1

    private var rcasOesUTex = -1
    private var rcasOesUInvDst = -1
    private var rcasOesUSharp = -1
    private var rcasOesUTexMat = -1
    private var rcasOesULuma = -1

    private var rcasOesHdrUTex = -1
    private var rcasOesHdrUInvDst = -1
    private var rcasOesHdrUSharp = -1
    private var rcasOesHdrUTexMat = -1
    private var rcasOesHdrULuma = -1

    private var vboPos = 0
    private var vboUv = 0
    private var quadPos: FloatBuffer? = null
    private var quadUv: FloatBuffer? = null

    private val texMatrix = FloatArray(16)

    @Volatile
    private var hintOutW = 0

    @Volatile
    private var hintOutH = 0

    private val tmpIntArray = IntArray(1)
    private val tmpViewportArray = IntArray(4)
    private val invalidateDefaultFbAttachments = intArrayOf(GLES30.GL_COLOR)
    private val invalidateColorAttachment = intArrayOf(GLES30.GL_COLOR_ATTACHMENT0)

    private var vao = 0
    private var hasVao = false

    private var lastSizeQueryNs = 0L
    private var sizeQueryNs = SIZE_QUERY_MIN_NS
    private var stableSizeQueryCount = 0
    private var lastSizeQueryOk = false
    private var swapFailStreak = 0
    private var makeCurrentFailStreak = 0

    private val running = AtomicBoolean(false)
    private val releaseRequested = AtomicBoolean(false)
    private var renderThread: Thread? = null

    @Volatile
    private var useChoreoVsync = false
    private val restartInProgress = AtomicBoolean(false)
    private var automaticRecoveryAttempts = 0

    @Volatile
    private var renderHandler: Handler? = null

    @Volatile
    private var renderTid = 0

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val frameLock = java.lang.Object()
    private var pendingFrames = 0

    @Volatile
    private var choreoFrameIntervalNs = CHOREO_INTERVAL_DEFAULT_NS

    @Volatile
    private var choreoDrainBudget = 1

    @Volatile
    private var lastChoreoFrameTimeNs = 0L

    @Volatile
    private var cachedAppVsyncOffsetNs = 0L

    @Volatile
    private var lastAppVsyncOffsetQueryNs = 0L

    private var curVpW = -1
    private var curVpH = -1
    private var twoDNearest = false
    private var oesNearest = false
    private var lastTex2DFilterTexId = -1
    private var twoDNearest2 = false
    private var lastTex2DFilterTexId2 = -1

    private var quadBound = false
    private var lastBoundVao = 0

    private var hasPresentationTimeExt = false
    private var swapIntervalZeroApplied = false
    private var swapIntervalZeroFailCount = 0
    private var sizeChangedSinceLastSwap = true
    private var hasEverUpdatedTex = false

    private var lastRcasInvDstW = -1
    private var lastRcasInvDstH = -1
    private var lastRcasSharp = -1f
    private var lastRcasFastInvDstW = -1
    private var lastRcasFastInvDstH = -1
    private var lastRcasFastSharp = -1f
    private var lastRcasOesInvDstW = -1
    private var lastRcasOesInvDstH = -1
    private var lastRcasOesSharp = -1f
    private var lastRcasOesHdrInvDstW = -1
    private var lastRcasOesHdrInvDstH = -1
    private var lastRcasOesHdrSharp = -1f

    private val fsrNotesSb = StringBuilder(96)
    private val simpleFsrNotesSb = StringBuilder(96)
    private var lastFsrNotesUpdateNs = 0L
    private var lastFsrNotesKind = -1
    private var lastFsrNotesOk = false
    private var lastFsrNotesPreset = -1
    private var lastFsrNotesUpX100 = Int.MIN_VALUE
    private var lastFsrNotesNearNative = false
    private var lastFsrNotesThrX100 = Int.MIN_VALUE
    private var lastFsrNotesReasonId = -1

    private var lastSimpleFsrNoteKind = -1
    private var lastSimpleFsrAllowSharpen = false
    private var lastSimpleFsrWinW = -1
    private var lastSimpleFsrWinH = -1
    private var lastSimpleFsrHintW = -1
    private var lastSimpleFsrHintH = -1

    private var lastFsrOverlayUpdateNs = 0L
    private var lastFsrOverlayMode = ""
    private var lastFsrOverlaySharp = -1f

    private var glErrorDirty = true

    private var activeTexUnit = -1
    private var lastTex2D = -1
    private var lastFbo = -1
    private var texMatrixSerial = 0L
    private var lastBlitTexMatSerial = -1L
    private var lastEasuPerfTexMatSerial = -1L
    private var lastEasuQualityTexMatSerial = -1L
    private var lastEasuBalTexMatSerial = -1L
    private var lastRcasOesTexMatSerial = -1L
    private var lastRcasOesHdrTexMatSerial = -1L

    init {
        if (context != null) {
            try {
                setPresentationSizeHintFromContext(context)
            } catch (_: Throwable) {
            }
        }
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running.get() || !useChoreoVsync) return

            val nowNsLocal = System.nanoTime()
            if (cachedAppVsyncOffsetNs == 0L ||
                (nowNsLocal - lastAppVsyncOffsetQueryNs) >= APP_VSYNC_OFFSET_QUERY_INTERVAL_NS
            ) {
                lastAppVsyncOffsetQueryNs = nowNsLocal
                cachedAppVsyncOffsetNs = queryAppVsyncOffsetNs(appContext)
            }
            val adjustedFrameTimeNs = frameTimeNanos - cachedAppVsyncOffsetNs
            val prevFrameTime = lastChoreoFrameTimeNs

            if (prevFrameTime == adjustedFrameTimeNs) {
                if (running.get() && useChoreoVsync) {
                    Choreographer.getInstance().postFrameCallback(this)
                }
                return
            }

            lastChoreoFrameTimeNs = adjustedFrameTimeNs

            choreoFrameIntervalNs = if (prevFrameTime != 0L) {
                val interval = adjustedFrameTimeNs - prevFrameTime
                if (interval in CHOREO_INTERVAL_MIN_NS..CHOREO_INTERVAL_MAX_NS) {
                    (choreoFrameIntervalNs * 4 + interval) / 5
                } else {
                    CHOREO_INTERVAL_DEFAULT_NS
                }
            } else {
                CHOREO_INTERVAL_DEFAULT_NS
            }

            choreoDrainBudget = calculateAdaptiveDrainConservative(choreoFrameIntervalNs)

            try {
                renderFrame(true)
            } catch (t: Throwable) {
                VibemisLog.warning("renderFrame error: $t")
                markGlErrorDirty()
                requestRendererRecoveryAsync("Choreographer renderFrame exception")
            }

            if (running.get() && useChoreoVsync) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    fun setHdrMode(hdrActive: Boolean, directPresent: Boolean) {
        if (this.hdrActive == hdrActive && this.hdrDirectPresent == directPresent) {
            return
        }
        this.hdrActive = hdrActive
        this.hdrDirectPresent = directPresent
        sizeChangedSinceLastSwap = true
        synchronized(frameLock) {
            frameLock.notifyAll()
        }
    }

    @Keep
    fun createDecoderInputSurface(): Surface? {
        if (!isGlReady()) {
            synchronized(this) {
                initEglAndGl()
                if (!isGlReady()) return null
            }
        }
        if (decoderInputSurface != null) return decoderInputSurface
        if (!ensureEglCurrent()) return null

        try {
            GLES20.glGenTextures(1, tmpIntArray, 0)
            oesTexId = tmpIntArray[0]

            if (lastTexture != oesTexId) {
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
                lastTexture = oesTexId
            }

            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val st = SurfaceTexture(oesTexId)
            decoderSurfaceTex = st
            attachedEglContextHandle = safeEglContextHandle(EGL14.eglGetCurrentContext())

            try {
                st.setDefaultBufferSize(srcW, srcH)
            } catch (_: Throwable) {
            }

            st.setOnFrameAvailableListener(this)
            val surf = Surface(st)
            decoderInputSurface = surf

            lastTexture = -1
            lastProgram = -1
            oesNearest = false
            return surf
        } finally {
            releaseEglCurrent()
        }
    }

    @Synchronized
    fun start() {
        if (releaseRequested.get()) return

        val existing = renderThread
        if (existing != null && existing.isAlive) {
            if (Thread.currentThread() !== existing) {
                try {
                    existing.join(120L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            if (existing.isAlive) {
                VibemisLog.warning("FSR: start() skipped because render thread is still alive")
                return
            } else {
                renderThread = null
                renderHandler = null
                renderTid = 0
                useChoreoVsync = false
            }
        }

        if (!isGlReady()) {
            initEglAndGl()
        }
        if (!isGlReady()) return
        if (running.getAndSet(true)) return

        val wantVsync = prefs?.enableVsync == true
        val balancedPacing = prefs?.framePacing == PreferenceConfiguration.FRAME_PACING_BALANCED
        useChoreoVsync = wantVsync && !balancedPacing

        if (useChoreoVsync) {
            val osPrio = desiredGlOsPriority(getEffectivePacing(), true)
            val ht = HandlerThread("GL-FSR1-Renderer", osPrio)
            renderThread = ht
            ht.start()

            val handler = Handler(ht.looper)
            renderHandler = handler
            handler.post {
                renderTid = Process.myTid()
                applyGlThreadPriorityNow()
                try {
                    VibemisBridge.pinCurrentThreadToBigCores()
                } catch (_: Throwable) {
                }
                try {
                    lastChoreoFrameTimeNs = 0L
                    choreoFrameIntervalNs = CHOREO_INTERVAL_DEFAULT_NS
                    choreoDrainBudget = 1
                    cachedAppVsyncOffsetNs = queryAppVsyncOffsetNs(appContext)
                    lastAppVsyncOffsetQueryNs = System.nanoTime()
                    Choreographer.getInstance().postFrameCallback(frameCallback)
                } catch (t: Throwable) {
                    VibemisLog.warning("Choreographer init failed: $t")
                }
            }
            return
        }

        val osPrio = desiredGlOsPriority(getEffectivePacing(), false)
        val thread = Thread(::renderLoop, "GL-FSR1-Renderer")
        try {
            thread.priority = if (osPrio == Process.THREAD_PRIORITY_URGENT_DISPLAY) {
                Thread.NORM_PRIORITY + 3
            } else {
                Thread.NORM_PRIORITY + 2
            }
        } catch (_: Throwable) {
        }
        renderThread = thread
        thread.start()
    }

    fun stop() {
        running.set(false)
        synchronized(frameLock) {
            frameLock.notifyAll()
        }

        if (useChoreoVsync) {
            if (Thread.currentThread() === renderThread) {
                try {
                    Choreographer.getInstance().removeFrameCallback(frameCallback)
                } catch (_: Throwable) {
                }
                try {
                    Looper.myLooper()?.quitSafely()
                } catch (_: Throwable) {
                }
                renderTid = 0
                return
            }

            renderHandler?.let { h ->
                try {
                    h.post {
                        try {
                            Choreographer.getInstance().removeFrameCallback(frameCallback)
                        } catch (_: Throwable) {
                        }
                        try {
                            Looper.myLooper()?.quitSafely()
                        } catch (_: Throwable) {
                        }
                    }
                } catch (_: Throwable) {
                }
            }

            (renderThread as? HandlerThread)?.let {
                try {
                    it.quitSafely()
                } catch (_: Throwable) {
                }
            }
        }

        val t = renderThread
        if (t != null && Thread.currentThread() !== t) {
            var stopped = false
            try {
                t.join(STOP_JOIN_TIMEOUT_MS)
                stopped = !t.isAlive
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }

            if (!stopped) {
                try {
                    t.interrupt()
                } catch (_: Throwable) {
                }
                if (t is HandlerThread) {
                    try {
                        t.quit()
                    } catch (_: Throwable) {
                    }
                }
                try {
                    t.join(STOP_JOIN_GRACE_MS)
                    stopped = !t.isAlive
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }

            if (stopped) {
                renderTid = 0
                renderThread = null
                renderHandler = null
                useChoreoVsync = false
            } else {
                VibemisLog.warning("FSR: render thread did not stop within timeout; skipping teardown to avoid EGL/GL races")
            }
        } else {
            renderTid = 0
            renderThread = null
            renderHandler = null
            useChoreoVsync = false
        }
    }

    @Synchronized
    fun release() {
        releaseRequested.set(true)
        stop()

        val t = renderThread
        if (t != null && t.isAlive) {
            VibemisLog.warning("FSR: release() aborted because render thread is still alive (avoiding EGL/GL teardown races)")
            return
        }

        try {
            decoderSurfaceTex?.let { st ->
                st.setOnFrameAvailableListener(null)
                st.release()
                decoderSurfaceTex = null
                attachedEglContextHandle = 0L
            }
            decoderInputSurface?.let { s ->
                s.release()
                decoderInputSurface = null
            }
        } catch (_: Throwable) {
        }

        destroyGl()
        destroyEgl()
    }

    override fun onFrameAvailable(st: SurfaceTexture?) {
        synchronized(frameLock) {
            val wasEmpty = pendingFrames == 0
            if (pendingFrames < MAX_PENDING_FRAMES) {
                pendingFrames++
            }
            if (wasEmpty) {
                frameLock.notify()
            }
        }
    }

    fun setFsrDebugEnabled(enabled: Boolean) {
        fsrStats.enabled = enabled
    }

    fun getFsrOverlayLine(): String = fsrOverlay

    fun getWeightMs(): Float = fsrStats.weightMs()

    fun setPresentationSizeHint(w: Int, h: Int) {
        hintOutW = max(0, w)
        hintOutH = max(0, h)
        sizeChangedSinceLastSwap = true
        synchronized(frameLock) {
            frameLock.notifyAll()
        }
    }

    fun setPresentationSizeHintFromDisplay(display: Display?) {
        if (display == null) return
        try {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(dm)
            setPresentationSizeHint(dm.widthPixels, dm.heightPixels)
        } catch (_: Throwable) {
        }
    }

    fun setPresentationSizeHintFromContext(ctx: Context?) {
        if (ctx == null) return
        var w = 0
        var h = 0

        try {
            val wm = ctx.getSystemService(WindowManager::class.java)
            if (wm != null) {
                if (Build.VERSION.SDK_INT >= 30) {
                    try {
                        val b = wm.maximumWindowMetrics.bounds
                        w = max(w, b.width())
                        h = max(h, b.height())
                    } catch (_: Throwable) {
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val d = wm.defaultDisplay
                    if (d != null) {
                        val dm = DisplayMetrics()
                        @Suppress("DEPRECATION")
                        d.getRealMetrics(dm)
                        w = max(w, dm.widthPixels)
                        h = max(h, dm.heightPixels)
                    }
                }
            }
        } catch (_: Throwable) {
        }

        try {
            val dmSvc = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val d = dmSvc?.getDisplay(Display.DEFAULT_DISPLAY)
            if (d != null) {
                val dmets = DisplayMetrics()
                @Suppress("DEPRECATION")
                d.getRealMetrics(dmets)
                w = max(w, dmets.widthPixels)
                h = max(h, dmets.heightPixels)
            }
        } catch (_: Throwable) {
        }

        if (w <= 0 || h <= 0) {
            try {
                val dmets = ctx.resources.displayMetrics
                w = max(w, dmets.widthPixels)
                h = max(h, dmets.heightPixels)
            } catch (_: Throwable) {
            }
        }

        if (w > 0 && h > 0) {
            setPresentationSizeHint(w, h)
            try {
                VibemisLog.info("FSR: presentation hint (auto) = ${w}x$h")
            } catch (_: Throwable) {
            }
        }
    }

    @Keep
    fun applyVsyncSetting() {
        val wantVsync = prefs?.enableVsync == true
        val balancedPacing = prefs?.framePacing == PreferenceConfiguration.FRAME_PACING_BALANCED
        val wantChoreoBackend = wantVsync && !balancedPacing

        if (running.get() && wantChoreoBackend != useChoreoVsync) {
            requestBackendRestartAsync()
            return
        }

        useChoreoVsync = wantChoreoBackend
        applyGlThreadPriorityNow()
        synchronized(frameLock) {
            frameLock.notifyAll()
        }
    }

    @Keep
    fun applyThreadPriorities() {
        applyGlThreadPriorityNow()
    }

    private fun requestBackendRestartAsync() {
        requestRendererRestartAsync("VSync backend change", false)
    }

    private fun requestRendererRecoveryAsync(reason: String) {
        requestRendererRestartAsync(reason, true)
    }

    private fun requestRendererRestartAsync(reason: String, automaticRecovery: Boolean) {
        if (releaseRequested.get()) return
        if (!restartInProgress.compareAndSet(false, true)) return

        running.set(false)
        synchronized(frameLock) {
            frameLock.notifyAll()
        }

        val recoveryAttempt = if (automaticRecovery) ++automaticRecoveryAttempts else 0

        Thread({
            try {
                val threadToStop = renderThread
                stop()

                if (threadToStop != null && threadToStop.isAlive) {
                    try {
                        threadToStop.join(STOP_JOIN_TIMEOUT_MS + STOP_JOIN_GRACE_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }

                if (renderThread != null && renderThread?.isAlive == true) {
                    VibemisLog.warning("FSR: renderer restart aborted (render thread still alive), reason=$reason")
                    return@Thread
                }

                if (releaseRequested.get()) return@Thread

                if (automaticRecovery) {
                    val delayMs = min(1000L, 50L shl min(4, max(0, recoveryAttempt - 1)))
                    try {
                        Thread.sleep(delayMs)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                    if (releaseRequested.get()) return@Thread
                    swapFailStreak = 0
                    makeCurrentFailStreak = 0
                    VibemisLog.warning("FSR: restarting renderer after $reason (attempt $recoveryAttempt)")
                }

                start()
            } finally {
                restartInProgress.set(false)
            }
        }, if (automaticRecovery) "GL-FSR1-Recovery" else "GL-FSR1-Restart").start()
    }

    private fun getEffectivePacing(): Int =
        prefs?.framePacing ?: PreferenceConfiguration.FRAME_PACING_BALANCED

    private fun applyGlThreadPriorityNow() {
        val t = renderThread
        if (t == null || !t.isAlive) return

        val osPrio = desiredGlOsPriority(getEffectivePacing(), useChoreoVsync)
        try {
            if (t is HandlerThread) {
                Process.setThreadPriority(t.threadId, osPrio)
            } else if (renderTid != 0) {
                Process.setThreadPriority(renderTid, osPrio)
            }
        } catch (_: Throwable) {
        }

        try {
            t.priority = if (osPrio == Process.THREAD_PRIORITY_URGENT_DISPLAY) {
                Thread.NORM_PRIORITY + 3
            } else {
                Thread.NORM_PRIORITY + 2
            }
        } catch (_: Throwable) {
        }
    }

    private fun renderLoop() {
        renderTid = Process.myTid()
        applyGlThreadPriorityNow()
        try {
            VibemisBridge.pinCurrentThreadToBigCores()
        } catch (_: Throwable) {
        }

        try {
            while (running.get()) {
                try {
                    renderFrame(true)
                } catch (t: Throwable) {
                    VibemisLog.warning("FSR: renderFrame crashed: $t")
                    markGlErrorDirty()
                    requestRendererRecoveryAsync("renderFrame exception")
                }
            }
        } finally {
            try {
                releaseEglCurrent()
            } catch (_: Throwable) {
            }
            renderTid = 0
        }
    }

    private fun renderFrame(allowWait: Boolean) {
        val fsrEnabled = fsrStats.enabled
        if (!isGlReady()) {
            synchronized(this) { initEglAndGl() }
            if (!isGlReady()) return
        }

        val curCtxH = safeEglContextHandle(EGL14.eglGetCurrentContext())
        val curDrawH = safeEglSurfaceHandle(EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW))
        val curReadH = safeEglSurfaceHandle(EGL14.eglGetCurrentSurface(EGL14.EGL_READ))

        val needMakeCurrent = curCtxH == 0L || curCtxH != eglContextHandle ||
            curDrawH != eglWindowSurfaceHandle || curReadH != eglWindowSurfaceHandle

        if (needMakeCurrent) {
            if (!EGL14.eglMakeCurrent(eglDisplay, eglWindowSurface, eglWindowSurface, eglContext)) {
                val err = EGL14.eglGetError()
                VibemisLog.warning("FSR: eglMakeCurrent failed err=0x${err.toString(16)}")
                if (++makeCurrentFailStreak >= 3) {
                    requestRendererRecoveryAsync("repeated eglMakeCurrent failures (0x${err.toString(16)})")
                }
                return
            }

            makeCurrentFailStreak = 0
            swapIntervalZeroApplied = false
            ensureSwapIntervalZero()
            resetCachedGlBindings()
            markGlErrorDirty()
        }

        val drainCount: Int
        synchronized(frameLock) {
            if (allowWait && pendingFrames == 0) {
                try {
                    if (useChoreoVsync) {
                        frameLock.wait(0L, CHOREO_LATE_LATCH_NS)
                    } else {
                        frameLock.wait(33)
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }

            val total = pendingFrames
            val maxDrainNow = if (useChoreoVsync) {
                val base = min(CHOREO_MAX_DRAIN_UPDATETEXIMAGE, max(1, choreoDrainBudget))
                if (total >= 3) MAX_DRAIN_UPDATETEXIMAGE else min(MAX_DRAIN_UPDATETEXIMAGE, base)
            } else {
                MAX_DRAIN_UPDATETEXIMAGE
            }

            drainCount = min(total, maxDrainNow)
            pendingFrames = total - drainCount
        }

        val newFrame = drainCount > 0
        var didUpdateTex = false

        val st = decoderSurfaceTex
        if (st != null && newFrame) {
            try {
                if (!ensureSurfaceTextureAttached()) return
                if (glErrorDirty) {
                    clearGlErrors()
                    glErrorDirty = false
                }
                val loops = min(drainCount, MAX_DRAIN_UPDATETEXIMAGE)
                for (i in 0 until loops) {
                    st.updateTexImage()
                }
                st.getTransformMatrix(texMatrix)
                texMatrixSerial++
                hasEverUpdatedTex = true
                didUpdateTex = true
            } catch (t: Throwable) {
                VibemisLog.warning("updateTexImage failed: $t")
                markGlErrorDirty()
                return
            }
        }

        val nowNs = System.nanoTime()
        val forceSizeQuery = (fbW <= 0 || fbH <= 0) || sizeChangedSinceLastSwap || swapFailStreak > 0
        if (forceSizeQuery || (nowNs - lastSizeQueryNs) >= sizeQueryNs) {
            val prevW = fbW
            val prevH = fbH
            refreshWindowSize()
            lastSizeQueryNs = nowNs
            val changed = fbW != prevW || fbH != prevH

            if (!lastSizeQueryOk) {
                sizeQueryNs = SIZE_QUERY_MIN_NS
                stableSizeQueryCount = 0
            } else if (changed) {
                sizeChangedSinceLastSwap = true
                sizeQueryNs = SIZE_QUERY_MIN_NS
                stableSizeQueryCount = 0
            } else if (!forceSizeQuery) {
                stableSizeQueryCount++
                if (stableSizeQueryCount >= 3) {
                    stableSizeQueryCount = 0
                    sizeQueryNs = min(SIZE_QUERY_MAX_NS, sizeQueryNs * 2L)
                }
            } else {
                stableSizeQueryCount = 0
                sizeQueryNs = max(sizeQueryNs, SIZE_QUERY_MIN_NS)
            }
        }

        if (fbW <= 0 || fbH <= 0) return
        if (!hasEverUpdatedTex) return
        if (!newFrame && !sizeChangedSinceLastSwap) return

        ensureViewport(fbW, fbH)

        val gpuPath = prefs?.gpuPathMode == true
        val fastBypassNow = computeFastBypassStatic(prefs)

        if (gpuPath || fastBypassNow) {
            bindFramebufferCached(0)
            invalidateDefaultFramebufferColor()
        } else {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }

        if (gpuPath) {
            if (fsrEnabled) {
                fsrStats.mode = "BYPASS"
                fsrStats.srcW = srcW
                fsrStats.srcH = srcH
                fsrStats.dstW = fbW
                fsrStats.dstH = fbH
                fsrStats.sharp = 0f
                fsrStats.sampling = "gpuPath"
                maybeUpdateSimpleFsrNote(SIMPLE_NOTE_GPU_PATH, false, 0, 0, 0, 0)
                maybeUpdateFsrOverlay()
            }
            bindFramebufferCached(0)
            drawOesToScreen(fbW, fbH, srcW, srcH)
            if (swapAndContinue()) sizeChangedSinceLastSwap = false
            return
        }

        val upscaleEnabled = prefs?.videoUpscaleEnable == true
        val mode = prefs?.videoUpscaleMode ?: "rcas"
        val modeNone = "none" == mode
        val modeRcasOnly = "rcas" == mode
        val modeEasuRcas = "easu_rcas" == mode
        val sharpUser = clamp01((prefs?.videoUpscaleSharpness ?: 35) / 100f)

        if (fastBypassNow && didUpdateTex && !sizeChangedSinceLastSwap && oesTexId != 0) {
            bindFramebufferCached(0)
            drawOesToScreen(fbW, fbH, srcW, srcH)
            if (swapAndContinue()) sizeChangedSinceLastSwap = false
            return
        }

        val dstTargetW = if (hintOutW > 0) hintOutW else fbW
        val dstTargetH = if (hintOutH > 0) hintOutH else fbH
        val scaleX = dstTargetW.toFloat() / srcW.toFloat()
        val scaleY = dstTargetH.toFloat() / srcH.toFloat()
        val nearThr = 0.05f
        val nearNative = abs(min(scaleX, scaleY) - 1.0f) < nearThr
        val canUpscaleNow = fbW != srcW || fbH != srcH

        if (hdrActive) {
            val allowSharpen = upscaleEnabled && !modeNone
            val preset = prefs?.videoUpscalePreset ?: 0
            var effSharp = 0f
            if (allowSharpen) {
                effSharp = mapUiSharpToInternalRcas(sharpUser, preset, nearNative) * 0.75f
            }

            var ok = false
            if (allowSharpen && effSharp > 0.001f) {
                ok = drawHdrRcasOesLumaSafe(fbW, fbH, effSharp)
            }

            if (fsrEnabled) {
                fsrStats.srcW = srcW
                fsrStats.srcH = srcH
                fsrStats.dstW = fbW
                fsrStats.dstH = fbH
                fsrStats.sharp = effSharp
                fsrStats.mode = if (ok) "HDR_RCAS_LUMA" else "HDR_BILINEAR"
                fsrStats.sampling = if (ok) "RCAS_OES_HDR_LUMA" else "bypass"
                maybeUpdateSimpleFsrNote(
                    if (ok) SIMPLE_NOTE_HDR_OK else SIMPLE_NOTE_HDR_FALLBACK,
                    allowSharpen, 0, 0, 0, 0
                )
                fsrStats.frames++
                pollFsrGpuTimers()
                if ((fsrStats.frames % 240L) == 0L) {
                    VibemisLog.info(fsrStats.periodicLine())
                }
                maybeUpdateFsrOverlay()
            }

            if (!ok) {
                drawOesToScreen(fbW, fbH, srcW, srcH)
            }
            if (swapAndContinue()) sizeChangedSinceLastSwap = false
            return
        }

        if (!upscaleEnabled || modeNone) {
            if (fsrEnabled) {
                fsrStats.mode = "BYPASS"
                fsrStats.srcW = srcW
                fsrStats.srcH = srcH
                fsrStats.dstW = fbW
                fsrStats.dstH = fbH
                fsrStats.sharp = 0f
                fsrStats.sampling = "bypass"
                maybeUpdateSimpleFsrNote(
                    if (modeNone) SIMPLE_NOTE_BYPASS_MODE_NONE else SIMPLE_NOTE_BYPASS_DISABLED,
                    false, fbW, fbH, dstTargetW, dstTargetH
                )
                maybeUpdateFsrOverlay()
            }
            bindFramebufferCached(0)
            drawOesToScreen(fbW, fbH, srcW, srcH)
        } else if (modeEasuRcas &&
            (progEasuPerf2D != 0 || progEasuBalanced2D != 0 || progEasuQuality2D != 0 ||
                progEasuPerf != 0 || progEasuBalanced != 0 || progEasuQuality != 0) &&
            !nearNative &&
            canUpscaleNow
        ) {
            val preset = prefs.videoUpscalePreset
            val upRatio = max(scaleX, scaleY)
            val effSharp = mapUiSharpToInternalEasuRcasLinear(sharpUser, preset)
            val ok = drawEasuRcasSafe(fbW, fbH, effSharp, preset)

            if (fsrEnabled) {
                fsrStats.mode = "EASU+RCAS"
                fsrStats.srcW = srcW
                fsrStats.srcH = srcH
                fsrStats.dstW = fbW
                fsrStats.dstH = fbH
                fsrStats.sharp = effSharp
                maybeUpdateFsrNotesEasuRcas(ok, preset, upRatio)
                fsrStats.frames++
                pollFsrGpuTimers()
                if ((fsrStats.frames % 240L) == 0L) {
                    VibemisLog.info(fsrStats.periodicLine())
                }
                maybeUpdateFsrOverlay()
            }

            if (!ok) {
                drawOesToScreen(fbW, fbH, srcW, srcH)
            }
        } else {
            val preset = prefs.videoUpscalePreset
            val effSharp = mapUiSharpToInternalRcas(sharpUser, preset, nearNative)
            val ok = drawRcasOnlySafe(fbW, fbH, effSharp)

            if (fsrEnabled) {
                fsrStats.mode = "RCAS_ONLY"
                fsrStats.srcW = srcW
                fsrStats.srcH = srcH
                fsrStats.dstW = fbW
                fsrStats.dstH = fbH
                fsrStats.sharp = effSharp

                val reasonId = when {
                    nearNative -> FSR_REASON_NEAR_NATIVE
                    srcW == fbW && srcH == fbH -> FSR_REASON_DST_EQ_SRC
                    modeRcasOnly -> FSR_REASON_EASU_DISABLED
                    else -> FSR_REASON_MODE_NOT_EASU_RCAS
                }
                maybeUpdateFsrNotesRcasOnly(ok, preset, nearNative, nearThr, reasonId)
                fsrStats.frames++
                pollFsrGpuTimers()
                if ((fsrStats.frames % 240L) == 0L) {
                    VibemisLog.info(fsrStats.periodicLine())
                }
                maybeUpdateFsrOverlay()
            }

            if (!ok) {
                drawOesToScreen(fbW, fbH, srcW, srcH)
            }
        }

        if (swapAndContinue()) sizeChangedSinceLastSwap = false
    }

    private fun swapAndContinue(): Boolean {
        if (hasPresentationTimeExt) {
            try {
                val presentNs = if (useChoreoVsync) {
                    val t = lastChoreoFrameTimeNs
                    if (t != 0L) t else System.nanoTime()
                } else {
                    System.nanoTime()
                }
                EGLExt.eglPresentationTimeANDROID(eglDisplay, eglWindowSurface, presentNs)
            } catch (_: Throwable) {
            }
        }

        val ok = EGL14.eglSwapBuffers(eglDisplay, eglWindowSurface)
        if (!ok) {
            val err = EGL14.eglGetError()
            swapFailStreak++
            VibemisLog.warning("FSR: eglSwapBuffers failed err=0x${err.toString(16)} streak=$swapFailStreak")
            if (swapFailStreak >= 6) {
                requestRendererRecoveryAsync("repeated eglSwapBuffers failures (0x${err.toString(16)})")
            }
        } else {
            swapFailStreak = 0
            makeCurrentFailStreak = 0
            if (automaticRecoveryAttempts != 0) automaticRecoveryAttempts = 0
        }
        return ok
    }

    private fun drawOesToScreen(dstW: Int, dstH: Int, srcW: Int, srcH: Int) {
        useProgram(progBlit)
        bindQuad()

        if (lastBlitTexMatSerial != texMatrixSerial) {
            GLES20.glUniformMatrix4fv(blitUTexMat, 1, false, texMatrix, 0)
            lastBlitTexMatSerial = texMatrixSerial
        }

        activeTexture0()
        if (lastTexture != oesTexId) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
            lastTexture = oesTexId
        }
        setOesFilter(dstW == srcW && dstH == srcH)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawHdrRcasOesLumaSafe(dstW: Int, dstH: Int, sharp: Float): Boolean {
        if (!hasEverUpdatedTex || progRcasOesHdr == 0) return false
        checkRcasOesHealthOnce(dstW, dstH)
        if (!rcasOesHealthy) return false

        val s = clamp01(sharp)
        if (s <= 0.001f) return false
        if (fsrStats.enabled) fsrStats.sampling = "RCAS_OES_HDR_LUMA"

        bindFramebufferCached(0)
        ensureViewport(dstW, dstH)
        useProgram(progRcasOesHdr)
        bindQuad()

        if (dstW != lastRcasOesHdrInvDstW || dstH != lastRcasOesHdrInvDstH) {
            GLES20.glUniform2f(rcasOesHdrUInvDst, 1.0f / max(1, dstW), 1.0f / max(1, dstH))
            lastRcasOesHdrInvDstW = dstW
            lastRcasOesHdrInvDstH = dstH
        }
        if (abs(s - lastRcasOesHdrSharp) > 0.0005f) {
            GLES20.glUniform1f(rcasOesHdrUSharp, s)
            lastRcasOesHdrSharp = s
        }
        if (lastRcasOesHdrTexMatSerial != texMatrixSerial) {
            GLES20.glUniformMatrix4fv(rcasOesHdrUTexMat, 1, false, texMatrix, 0)
            lastRcasOesHdrTexMatSerial = texMatrixSerial
        }

        activeTexture0()
        if (lastTexture != oesTexId) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
            lastTexture = oesTexId
        }
        setOesFilter(false)

        if (fsrStats.enabled) {
            fsrStats.ticRcas()
            rcasGpuTimer.begin()
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        if (fsrStats.enabled) {
            rcasGpuTimer.end()
            fsrStats.tocRcas()
        }
        return true
    }

    private fun drawRcasOnlySafe(dstW: Int, dstH: Int, sharp: Float): Boolean {
        if (hasEverUpdatedTex) {
            checkRcasOesHealthOnce(dstW, dstH)
        }
        val s = clamp01(sharp)

        if (hdrActive) {
            if (s <= 0.001f) return false
            return drawHdrRcasOesLumaSafe(dstW, dstH, s)
        }

        if (progRcasOes != 0 && rcasOesHealthy) {
            if (fsrStats.enabled) fsrStats.sampling = "RCAS_OES"

            bindFramebufferCached(0)
            ensureViewport(dstW, dstH)
            useProgram(progRcasOes)
            bindQuad()

            if (dstW != lastRcasOesInvDstW || dstH != lastRcasOesInvDstH) {
                GLES20.glUniform2f(rcasOesUInvDst, 1.0f / max(1, dstW), 1.0f / max(1, dstH))
                lastRcasOesInvDstW = dstW
                lastRcasOesInvDstH = dstH
            }
            if (abs(s - lastRcasOesSharp) > 0.0005f) {
                GLES20.glUniform1f(rcasOesUSharp, s)
                lastRcasOesSharp = s
            }
            if (lastRcasOesTexMatSerial != texMatrixSerial) {
                GLES20.glUniformMatrix4fv(rcasOesUTexMat, 1, false, texMatrix, 0)
                lastRcasOesTexMatSerial = texMatrixSerial
            }

            activeTexture0()
            if (lastTexture != oesTexId) {
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
                lastTexture = oesTexId
            }
            setOesFilter(!(dstW > srcW || dstH > srcH))

            if (fsrStats.enabled) {
                fsrStats.ticRcas()
                rcasGpuTimer.begin()
            }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            if (fsrStats.enabled) {
                rcasGpuTimer.end()
                fsrStats.tocRcas()
            }
            return true
        }

        if (!ensureFbo(dstW, dstH)) return false
        if (fsrStats.enabled) fsrStats.sampling = "OES->2D BLIT + RCAS_2D"

        bindFramebufferCached(fbo)
        ensureViewport(dstW, dstH)
        useProgram(progBlit)
        bindQuad()

        if (lastBlitTexMatSerial != texMatrixSerial) {
            GLES20.glUniformMatrix4fv(blitUTexMat, 1, false, texMatrix, 0)
            lastBlitTexMatSerial = texMatrixSerial
        }

        activeTexture0()
        if (lastTexture != oesTexId) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
            lastTexture = oesTexId
        }
        setOesFilter(!(dstW > srcW || dstH > srcH))

        if (fsrStats.enabled) {
            fsrStats.ticBlit()
            blitGpuTimer.begin()
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        if (fsrStats.enabled) {
            blitGpuTimer.end()
            fsrStats.tocBlit()
        }

        drawRcas2DPass(dstW, dstH, s, prefs?.videoUpscalePreset ?: 0)
        return true
    }

    private fun drawEasuRcasSafe(dstW: Int, dstH: Int, sharp: Float, presetIn: Int): Boolean {
        val have2DEasu = progEasuPerf2D != 0 || progEasuBalanced2D != 0 || progEasuQuality2D != 0
        if (!ensureFbo(dstW, dstH)) return false

        val preset = presetIn.coerceIn(0, 2)
        val s = clamp01(sharp)

        var stagedOk = if (have2DEasu) stageOesTo2D(srcW, srcH) else false
        var easuProg = 0
        var easuTexMatLoc = -1

        if (stagedOk) {
            when {
                preset == 2 && progEasuQuality2D != 0 -> {
                    easuProg = progEasuQuality2D
                    easuTexMatLoc = easuQ2DUTexMat
                }
                preset == 1 && progEasuBalanced2D != 0 -> {
                    easuProg = progEasuBalanced2D
                    easuTexMatLoc = easuBal2DUTexMat
                }
                progEasuPerf2D != 0 -> {
                    easuProg = progEasuPerf2D
                    easuTexMatLoc = easuPerf2DUTexMat
                }
                else -> stagedOk = false
            }
        }

        if (!stagedOk) {
            when {
                preset == 2 && progEasuQuality != 0 -> {
                    easuProg = progEasuQuality
                    easuTexMatLoc = easuQUTexMat
                }
                preset == 1 && progEasuBalanced != 0 -> {
                    easuProg = progEasuBalanced
                    easuTexMatLoc = easuBalUTexMat
                }
                progEasuPerf != 0 -> {
                    easuProg = progEasuPerf
                    easuTexMatLoc = easuPerfUTexMat
                }
                else -> return false
            }
        }

        if (fsrStats.enabled) {
            fsrStats.sampling = if (stagedOk) "OES->2D LINEAR + EASU_2D + RCAS_2D" else "OES_EASU + RCAS_2D"
        }

        bindFramebufferCached(fbo)
        ensureViewport(dstW, dstH)
        useProgram(easuProg)
        bindQuad()

        activeTexture0()
        if (stagedOk) {
            bindTex2DCached(srcRgbTex)
            setTex2DFilter(false)
        } else {
            if (lastTexture != oesTexId) {
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
                lastTexture = oesTexId
            }
            setOesFilter(false)
        }

        if (easuTexMatLoc >= 0) {
            val isQuality = easuProg == (if (stagedOk) progEasuQuality2D else progEasuQuality)
            val isBalanced = easuProg == (if (stagedOk) progEasuBalanced2D else progEasuBalanced)
            val needUpload = when {
                isQuality -> lastEasuQualityTexMatSerial != texMatrixSerial
                isBalanced -> lastEasuBalTexMatSerial != texMatrixSerial
                else -> lastEasuPerfTexMatSerial != texMatrixSerial
            }
            if (needUpload) {
                GLES20.glUniformMatrix4fv(easuTexMatLoc, 1, false, texMatrix, 0)
                when {
                    isQuality -> lastEasuQualityTexMatSerial = texMatrixSerial
                    isBalanced -> lastEasuBalTexMatSerial = texMatrixSerial
                    else -> lastEasuPerfTexMatSerial = texMatrixSerial
                }
            }
        }

        if (fsrStats.enabled) {
            fsrStats.ticEasu()
            easuGpuTimer.begin()
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        if (fsrStats.enabled) {
            easuGpuTimer.end()
            fsrStats.tocEasu()
        }

        drawRcas2DPass(dstW, dstH, s, preset)
        return true
    }

    private fun drawRcas2DPass(dstW: Int, dstH: Int, s: Float, preset: Int) {
        bindFramebufferCached(0)
        ensureViewport(dstW, dstH)

        val useFast = (preset == 0) && (progRcasFast != 0)
        val rcasProg = if (useFast) progRcasFast else progRcas
        useProgram(rcasProg)
        bindQuad()

        activeTexture0()
        bindTex2DCached(upscaledTex)
        setTex2DFilter(true)

        if (useFast) {
            if (dstW != lastRcasFastInvDstW || dstH != lastRcasFastInvDstH) {
                GLES20.glUniform2f(rcasFastUInvDst, 1.0f / max(1, dstW), 1.0f / max(1, dstH))
                lastRcasFastInvDstW = dstW
                lastRcasFastInvDstH = dstH
            }
            if (abs(s - lastRcasFastSharp) > 0.0005f) {
                GLES20.glUniform1f(rcasFastUSharp, s)
                lastRcasFastSharp = s
            }
        } else {
            if (dstW != lastRcasInvDstW || dstH != lastRcasInvDstH) {
                GLES20.glUniform2f(rcasUInvDst, 1.0f / max(1, dstW), 1.0f / max(1, dstH))
                lastRcasInvDstW = dstW
                lastRcasInvDstH = dstH
            }
            if (abs(s - lastRcasSharp) > 0.0005f) {
                GLES20.glUniform1f(rcasUSharp, s)
                lastRcasSharp = s
            }
        }

        if (fsrStats.enabled) {
            fsrStats.ticRcas()
            rcasGpuTimer.begin()
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        if (fsrStats.enabled) {
            rcasGpuTimer.end()
            fsrStats.tocRcas()
        }
    }

    private fun isGlReady(): Boolean =
        eglDisplay != EGL14.EGL_NO_DISPLAY &&
            eglContext != EGL14.EGL_NO_CONTEXT &&
            eglWindowSurface != EGL14.EGL_NO_SURFACE

    private fun refreshWindowSize() {
        if (!isGlReady()) {
            lastSizeQueryOk = false
            return
        }
        var w = 0
        var h = 0
        try {
            EGL14.eglQuerySurface(eglDisplay, eglWindowSurface, EGL14.EGL_WIDTH, tmpIntArray, 0)
            w = tmpIntArray[0]
            EGL14.eglQuerySurface(eglDisplay, eglWindowSurface, EGL14.EGL_HEIGHT, tmpIntArray, 0)
            h = tmpIntArray[0]
        } catch (_: Throwable) {
        }

        lastSizeQueryOk = w > 0 && h > 0
        if (!lastSizeQueryOk) return

        if (w != fbW || h != fbH) {
            fbW = w
            fbH = h
            curVpW = -1
            curVpH = -1
            if (fbo != 0 && (fboW != w || fboH != h)) {
                destroyFbo()
            }
        }
    }

    private fun ensureViewport(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (w != curVpW || h != curVpH) {
            GLES20.glViewport(0, 0, w, h)
            curVpW = w
            curVpH = h
        }
    }

    private fun ensureSrcStageFbo(w: Int, h: Int): Boolean {
        if (w <= 0 || h <= 0) return false
        if (srcRgbTex != 0 && srcFbo != 0 && w == srcFboW && h == srcFboH) return true
        createOrResizeSrcStageFbo(w, h)
        return srcRgbTex != 0 && srcFbo != 0
    }

    private fun createOrResizeSrcStageFbo(w: Int, h: Int) {
        if (srcRgbTex != 0) {
            if (lastTex2D == srcRgbTex) lastTex2D = -1
            invalidateTex2DFilterCache(srcRgbTex)
            tmpIntArray[0] = srcRgbTex
            GLES20.glDeleteTextures(1, tmpIntArray, 0)
            srcRgbTex = 0
        }
        if (srcFbo != 0) {
            if (lastFbo == srcFbo) lastFbo = -1
            tmpIntArray[0] = srcFbo
            GLES20.glDeleteFramebuffers(1, tmpIntArray, 0)
            srcFbo = 0
        }

        GLES20.glGenFramebuffers(1, tmpIntArray, 0)
        srcFbo = tmpIntArray[0]
        GLES20.glGenTextures(1, tmpIntArray, 0)
        srcRgbTex = tmpIntArray[0]

        bindTex2DCached(srcRgbTex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        bindFramebufferCached(srcFbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, srcRgbTex, 0)

        if (!isFboComplete()) {
            bindFramebufferCached(0)
            if (srcRgbTex != 0) {
                tmpIntArray[0] = srcRgbTex
                GLES20.glDeleteTextures(1, tmpIntArray, 0)
                srcRgbTex = 0
            }
            if (srcFbo != 0) {
                tmpIntArray[0] = srcFbo
                GLES20.glDeleteFramebuffers(1, tmpIntArray, 0)
                srcFbo = 0
            }
            srcFboW = 0
            srcFboH = 0
            return
        }

        srcFboW = w
        srcFboH = h
        bindFramebufferCached(0)
    }

    private fun stageOesTo2D(w: Int, h: Int): Boolean {
        if (progOesTo2D == 0 || oesTexId == 0) return false
        if (!ensureSrcStageFbo(w, h)) return false

        bindFramebufferCached(srcFbo)
        ensureViewport(w, h)
        useProgram(progOesTo2D)
        bindQuad()

        activeTexture0()
        if (lastTexture != oesTexId) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
            lastTexture = oesTexId
        }
        setOesFilter(false)

        if (fsrStats.enabled) {
            fsrStats.ticBlit()
            blitGpuTimer.begin()
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        if (fsrStats.enabled) {
            blitGpuTimer.end()
            fsrStats.tocBlit()
        }
        return true
    }

    private fun ensureFbo(w: Int, h: Int): Boolean {
        if (w <= 0 || h <= 0) return false
        if (upscaledTex != 0 && fbo != 0 && w == fboW && h == fboH) return true
        createOrResizeFbo(w, h)
        return upscaledTex != 0 && fbo != 0
    }

    private fun createOrResizeFbo(w: Int, h: Int) {
        destroyFbo()
        fboW = w
        fboH = h
        curVpW = -1
        curVpH = -1

        GLES20.glGenFramebuffers(1, tmpIntArray, 0)
        fbo = tmpIntArray[0]
        GLES20.glGenTextures(1, tmpIntArray, 0)
        upscaledTex = tmpIntArray[0]

        bindTex2DCached(upscaledTex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        bindFramebufferCached(fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, upscaledTex, 0)

        if (!isFboComplete()) {
            bindFramebufferCached(0)
            destroyFbo()
            return
        }
        bindFramebufferCached(0)
        twoDNearest = false
    }

    private fun isFboComplete(): Boolean {
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            VibemisLog.warning("FBO incomplete: 0x${status.toString(16)}")
            return false
        }
        return true
    }

    private fun destroyFbo() {
        if (srcRgbTex != 0) {
            if (lastTex2D == srcRgbTex) lastTex2D = -1
            invalidateTex2DFilterCache(srcRgbTex)
            tmpIntArray[0] = srcRgbTex
            GLES20.glDeleteTextures(1, tmpIntArray, 0)
            srcRgbTex = 0
        }
        if (srcFbo != 0) {
            if (lastFbo == srcFbo) lastFbo = -1
            tmpIntArray[0] = srcFbo
            GLES20.glDeleteFramebuffers(1, tmpIntArray, 0)
            srcFbo = 0
        }
        srcFboW = 0
        srcFboH = 0

        if (upscaledTex != 0) {
            if (lastTex2D == upscaledTex) lastTex2D = -1
            invalidateTex2DFilterCache(upscaledTex)
            tmpIntArray[0] = upscaledTex
            GLES20.glDeleteTextures(1, tmpIntArray, 0)
            upscaledTex = 0
        }
        if (fbo != 0) {
            if (lastFbo == fbo) lastFbo = -1
            tmpIntArray[0] = fbo
            GLES20.glDeleteFramebuffers(1, tmpIntArray, 0)
            fbo = 0
        }
        fboW = 0
        fboH = 0
    }

    private fun primeStaticUniforms() {
        if (progBlit != 0 && blitUTex >= 0) {
            GLES20.glUseProgram(progBlit)
            GLES20.glUniform1i(blitUTex, 0)
        }
        if (progOesTo2D != 0 && oes2dUTex >= 0) {
            GLES20.glUseProgram(progOesTo2D)
            GLES20.glUniform1i(oes2dUTex, 0)
        }
        val invX = 1.0f / srcW
        val invY = 1.0f / srcH
        fun primeEasu(prog: Int, uTex: Int, uInv: Int) {
            if (prog == 0) return
            GLES20.glUseProgram(prog)
            if (uTex >= 0) GLES20.glUniform1i(uTex, 0)
            if (uInv >= 0) GLES20.glUniform2f(uInv, invX, invY)
        }
        primeEasu(progEasuPerf, easuPerfUTex, easuPerfUInvSrcSize)
        primeEasu(progEasuBalanced, easuBalUTex, easuBalUInvSrcSize)
        primeEasu(progEasuQuality, easuQUTex, easuQUInvSrcSize)
        primeEasu(progEasuPerf2D, easuPerf2DUTex, easuPerf2DUInvSrcSize)
        primeEasu(progEasuBalanced2D, easuBal2DUTex, easuBal2DUInvSrcSize)
        primeEasu(progEasuQuality2D, easuQ2DUTex, easuQ2DUInvSrcSize)

        if (progRcas != 0 && rcasUTex >= 0) {
            GLES20.glUseProgram(progRcas)
            GLES20.glUniform1i(rcasUTex, 0)
            setRcasLumaUniform(rcasULuma, false)
        }
        if (progRcasFast != 0 && rcasFastUTex >= 0) {
            GLES20.glUseProgram(progRcasFast)
            GLES20.glUniform1i(rcasFastUTex, 0)
            setRcasLumaUniform(rcasFastULuma, false)
        }
        if (progRcasOes != 0 && rcasOesUTex >= 0) {
            GLES20.glUseProgram(progRcasOes)
            GLES20.glUniform1i(rcasOesUTex, 0)
            setRcasLumaUniform(rcasOesULuma, false)
        }
        if (progRcasOesHdr != 0 && rcasOesHdrUTex >= 0) {
            GLES20.glUseProgram(progRcasOesHdr)
            GLES20.glUniform1i(rcasOesHdrUTex, 0)
            setRcasLumaUniform(rcasOesHdrULuma, true)
        }
        lastProgram = -1
    }

    private fun resetCachedGlBindings() {
        lastProgram = -1
        lastTexture = -1
        curVpW = -1
        curVpH = -1
        twoDNearest = false
        oesNearest = false
        activeTexUnit = -1
        lastTex2D = -1
        lastFbo = -1
        lastBlitTexMatSerial = -1L
        lastEasuPerfTexMatSerial = -1L
        lastEasuQualityTexMatSerial = -1L
        lastEasuBalTexMatSerial = -1L
        lastRcasOesTexMatSerial = -1L
        lastRcasOesHdrTexMatSerial = -1L
        lastRcasInvDstW = -1
        lastRcasInvDstH = -1
        lastRcasSharp = -1f
        lastRcasFastInvDstW = -1
        lastRcasFastInvDstH = -1
        lastRcasFastSharp = -1f
        lastRcasOesInvDstW = -1
        lastRcasOesInvDstH = -1
        lastRcasOesSharp = -1f
        lastRcasOesHdrInvDstW = -1
        lastRcasOesHdrInvDstH = -1
        lastRcasOesHdrSharp = -1f
        quadBound = false
        lastBoundVao = 0
    }

    private fun initEglAndGl() {
        if (isGlReady()) return

        synchronized(this) {
            if (isGlReady()) return
            val winSurface = windowSurfaceInput
            if (winSurface == null || !winSurface.isValid) {
                VibemisLog.warning("GL init skipped: window surface null or invalid")
                return
            }

            try {
                eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw RuntimeException("No EGL display")

                val version = IntArray(2)
                if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                    throw RuntimeException("eglInitialize failed")
                }

                val cfgAttrs = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_NONE
                )
                val outCfg = arrayOfNulls<EGLConfig>(1)
                val numCfg = IntArray(1)
                if (!EGL14.eglChooseConfig(eglDisplay, cfgAttrs, 0, outCfg, 0, 1, numCfg, 0) || numCfg[0] <= 0) {
                    throw RuntimeException("eglChooseConfig failed")
                }
                val eglConfig = outCfg[0] ?: throw RuntimeException("eglConfig null")

                val ctxAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
                eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttrs, 0)
                if (eglContext == EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext failed")

                val surfAttrs = intArrayOf(EGL14.EGL_NONE)
                eglWindowSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, winSurface, surfAttrs, 0)
                if (eglWindowSurface == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreateWindowSurface failed")

                if (!EGL14.eglMakeCurrent(eglDisplay, eglWindowSurface, eglWindowSurface, eglContext)) {
                    throw RuntimeException("eglMakeCurrent failed")
                }

                eglContextHandle = safeEglContextHandle(eglContext)
                eglWindowSurfaceHandle = safeEglSurfaceHandle(eglWindowSurface)
                swapIntervalZeroApplied = false
                ensureSwapIntervalZero()
                resetCachedGlBindings()

                hasPresentationTimeExt = try {
                    EGL14.eglQueryString(eglDisplay, EGL14.EGL_EXTENSIONS)
                        ?.contains("EGL_ANDROID_presentation_time") == true
                } catch (_: Throwable) {
                    false
                }
            } catch (t: Throwable) {
                VibemisLog.warning("GL init failed: $t")
                destroyEgl()
                return
            }

            try {
                val pos = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
                val uv = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
                quadPos = ByteBuffer.allocateDirect(pos.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                    put(pos).position(0)
                }
                quadUv = ByteBuffer.allocateDirect(uv.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                    put(uv).position(0)
                }

                GLES20.glGenBuffers(1, tmpIntArray, 0); vboPos = tmpIntArray[0]
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboPos)
                GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, (quadPos?.capacity() ?: 8) * 4, quadPos, GLES20.GL_STATIC_DRAW)

                GLES20.glGenBuffers(1, tmpIntArray, 0); vboUv = tmpIntArray[0]
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboUv)
                GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, (quadUv?.capacity() ?: 8) * 4, quadUv, GLES20.GL_STATIC_DRAW)
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

                try {
                    val vaoId = IntArray(1)
                    GLES30.glGenVertexArrays(1, vaoId, 0)
                    vao = vaoId[0]
                    GLES30.glBindVertexArray(vao)
                    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboPos)
                    GLES20.glEnableVertexAttribArray(0)
                    GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0)
                    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboUv)
                    GLES20.glEnableVertexAttribArray(1)
                    GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 0, 0)
                    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
                    GLES30.glBindVertexArray(0)
                    hasVao = vao != 0
                } catch (_: Throwable) {
                    hasVao = false
                }

                val progVs = compileShader(GLES20.GL_VERTEX_SHADER, VS)
                val vsBlit = compileShader(GLES20.GL_VERTEX_SHADER, VS_BLIT_TEXMAT)
                try {
                    progBlit = linkProgram(vsBlit, FS_OES_BLIT)
                } finally {
                    try { GLES20.glDeleteShader(vsBlit) } catch (_: Throwable) {}
                }

                progOesTo2D = linkProgram(progVs, FS_OES_TO_2D)
                progEasuPerf = linkProgram(progVs, FS_EASU_PERF)
                progEasuBalanced = linkProgram(progVs, FS_EASU_BALANCED)
                progEasuQuality = linkProgram(progVs, FS_EASU_QUALITY)
                progEasuPerf2D = runCatching { linkProgram(progVs, fsOesTo2D(FS_EASU_PERF)) }.getOrDefault(0)
                progEasuBalanced2D = runCatching { linkProgram(progVs, fsOesTo2D(FS_EASU_BALANCED)) }.getOrDefault(0)
                progEasuQuality2D = runCatching { linkProgram(progVs, fsOesTo2D(FS_EASU_QUALITY)) }.getOrDefault(0)
                progRcas = linkProgram(progVs, FS_RCAS)
                progRcasFast = runCatching { linkProgram(progVs, FS_RCAS_FAST) }.getOrDefault(0)

                var vsRcasOes = 0
                try {
                    vsRcasOes = compileShader(GLES20.GL_VERTEX_SHADER, VS_RCAS_OES)
                    progRcasOes = linkProgram(vsRcasOes, "#define USE_OES\n#define RCAS_OES_VS\n$FS_RCAS")
                    rcasOesUTex = GLES20.glGetUniformLocation(progRcasOes, "uTexOES")
                    rcasOesUInvDst = GLES20.glGetUniformLocation(progRcasOes, "uInvDstSize")
                    rcasOesUSharp = GLES20.glGetUniformLocation(progRcasOes, "uSharp")
                    rcasOesUTexMat = GLES20.glGetUniformLocation(progRcasOes, "uTexMatrix")
                    rcasOesULuma = GLES20.glGetUniformLocation(progRcasOes, "uLumaCoeffs")

                    progRcasOesHdr = runCatching {
                        val p = linkProgram(
                            vsRcasOes,
                            "#define USE_OES\n#define RCAS_OES_VS\n#define RCAS_LUMA_ONLY\n$FS_RCAS"
                        )
                        rcasOesHdrUTex = GLES20.glGetUniformLocation(p, "uTexOES")
                        rcasOesHdrUInvDst = GLES20.glGetUniformLocation(p, "uInvDstSize")
                        rcasOesHdrUSharp = GLES20.glGetUniformLocation(p, "uSharp")
                        rcasOesHdrUTexMat = GLES20.glGetUniformLocation(p, "uTexMatrix")
                        rcasOesHdrULuma = GLES20.glGetUniformLocation(p, "uLumaCoeffs")
                        p
                    }.getOrDefault(0)
                } catch (_: Throwable) {
                    progRcasOes = 0
                } finally {
                    if (vsRcasOes != 0) {
                        try { GLES20.glDeleteShader(vsRcasOes) } catch (_: Throwable) {}
                    }
                }

                try { GLES20.glDeleteShader(progVs) } catch (_: Throwable) {}

                if (progEasuPerf != 0) {
                    easuPerfUTex = GLES20.glGetUniformLocation(progEasuPerf, "uTex")
                    easuPerfUInvSrcSize = GLES20.glGetUniformLocation(progEasuPerf, "uInvSrcSize")
                    easuPerfUTexMat = GLES20.glGetUniformLocation(progEasuPerf, "uTexMatrix")
                }
                if (progEasuQuality != 0) {
                    easuQUTex = GLES20.glGetUniformLocation(progEasuQuality, "uTex")
                    easuQUInvSrcSize = GLES20.glGetUniformLocation(progEasuQuality, "uInvSrcSize")
                    easuQUTexMat = GLES20.glGetUniformLocation(progEasuQuality, "uTexMatrix")
                }
                if (progEasuBalanced != 0) {
                    easuBalUTex = GLES20.glGetUniformLocation(progEasuBalanced, "uTex")
                    easuBalUInvSrcSize = GLES20.glGetUniformLocation(progEasuBalanced, "uInvSrcSize")
                    easuBalUTexMat = GLES20.glGetUniformLocation(progEasuBalanced, "uTexMatrix")
                }

                blitUTex = GLES20.glGetUniformLocation(progBlit, "uTex")
                blitUTexMat = GLES20.glGetUniformLocation(progBlit, "uTexMatrix")
                if (progOesTo2D != 0) {
                    oes2dUTex = GLES20.glGetUniformLocation(progOesTo2D, "uTex")
                }
                if (progEasuPerf2D != 0) {
                    easuPerf2DUTex = GLES20.glGetUniformLocation(progEasuPerf2D, "uTex")
                    easuPerf2DUInvSrcSize = GLES20.glGetUniformLocation(progEasuPerf2D, "uInvSrcSize")
                    easuPerf2DUTexMat = GLES20.glGetUniformLocation(progEasuPerf2D, "uTexMatrix")
                }
                if (progEasuBalanced2D != 0) {
                    easuBal2DUTex = GLES20.glGetUniformLocation(progEasuBalanced2D, "uTex")
                    easuBal2DUInvSrcSize = GLES20.glGetUniformLocation(progEasuBalanced2D, "uInvSrcSize")
                    easuBal2DUTexMat = GLES20.glGetUniformLocation(progEasuBalanced2D, "uTexMatrix")
                }
                if (progEasuQuality2D != 0) {
                    easuQ2DUTex = GLES20.glGetUniformLocation(progEasuQuality2D, "uTex")
                    easuQ2DUInvSrcSize = GLES20.glGetUniformLocation(progEasuQuality2D, "uInvSrcSize")
                    easuQ2DUTexMat = GLES20.glGetUniformLocation(progEasuQuality2D, "uTexMatrix")
                }

                rcasUTex = GLES20.glGetUniformLocation(progRcas, "uUpscaled")
                rcasUInvDst = GLES20.glGetUniformLocation(progRcas, "uInvDstSize")
                rcasUSharp = GLES20.glGetUniformLocation(progRcas, "uSharp")
                rcasULuma = GLES20.glGetUniformLocation(progRcas, "uLumaCoeffs")

                if (progRcasFast != 0) {
                    rcasFastUTex = GLES20.glGetUniformLocation(progRcasFast, "uUpscaled")
                    rcasFastUInvDst = GLES20.glGetUniformLocation(progRcasFast, "uInvDstSize")
                    rcasFastUSharp = GLES20.glGetUniformLocation(progRcasFast, "uSharp")
                    rcasFastULuma = GLES20.glGetUniformLocation(progRcasFast, "uLumaCoeffs")
                }

                useProgram(progBlit)
                if (blitUTex >= 0) GLES20.glUniform1i(blitUTex, 0)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                primeStaticUniforms()
                applyFixedState()
                releaseEglCurrent()
            } catch (t: Throwable) {
                VibemisLog.warning("GL shader/program setup failed: $t")
                destroyGl()
                destroyEgl()
            }
        }
    }

    private fun destroyGl() {
        val current = ensureEglCurrent()
        try {
            if (current) {
                destroyFbo()
                easuGpuTimer.release()
                rcasGpuTimer.release()
                blitGpuTimer.release()

                if (hasVao && vao != 0) {
                    try {
                        GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
                    } catch (_: Throwable) {
                    }
                    vao = 0
                    hasVao = false
                }
                if (vboPos != 0) {
                    tmpIntArray[0] = vboPos
                    GLES20.glDeleteBuffers(1, tmpIntArray, 0)
                    vboPos = 0
                }
                if (vboUv != 0) {
                    tmpIntArray[0] = vboUv
                    GLES20.glDeleteBuffers(1, tmpIntArray, 0)
                    vboUv = 0
                }

                fun deleteProg(p: Int): Int {
                    if (p != 0) GLES20.glDeleteProgram(p)
                    return 0
                }
                progBlit = deleteProg(progBlit)
                progOesTo2D = deleteProg(progOesTo2D)
                progEasuPerf = deleteProg(progEasuPerf)
                progEasuQuality = deleteProg(progEasuQuality)
                progEasuBalanced = deleteProg(progEasuBalanced)
                progEasuPerf2D = deleteProg(progEasuPerf2D)
                progEasuBalanced2D = deleteProg(progEasuBalanced2D)
                progEasuQuality2D = deleteProg(progEasuQuality2D)
                progRcas = deleteProg(progRcas)
                progRcasFast = deleteProg(progRcasFast)
                progRcasOes = deleteProg(progRcasOes)
                progRcasOesHdr = deleteProg(progRcasOesHdr)
            } else {
                vao = 0
                hasVao = false
                vboPos = 0
                vboUv = 0
                progBlit = 0
                progOesTo2D = 0
                progEasuPerf = 0
                progEasuBalanced = 0
                progEasuQuality = 0
                progEasuPerf2D = 0
                progEasuBalanced2D = 0
                progEasuQuality2D = 0
                progRcas = 0
                progRcasFast = 0
                progRcasOes = 0
                progRcasOesHdr = 0
                srcFbo = 0
                srcRgbTex = 0
                srcFboW = 0
                srcFboH = 0
                fbo = 0
                upscaledTex = 0
                fboW = 0
                fboH = 0
            }
        } finally {
            lastProgram = -1
            lastTexture = -1
            curVpW = -1
            curVpH = -1
            twoDNearest = false
            oesNearest = false
            rcasOesChecked = false
            rcasOesHealthy = false
            rcasOesCheckAttempts = 0
            hasEverUpdatedTex = false
            quadBound = false
            lastBoundVao = 0
            if (current) releaseEglCurrent()
        }
    }

    private fun destroyEgl() {
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglWindowSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglWindowSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
        } catch (t: Throwable) {
            VibemisLog.warning("EGL destroy failed: $t")
        } finally {
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            eglWindowSurface = EGL14.EGL_NO_SURFACE
            eglContextHandle = 0L
            eglWindowSurfaceHandle = 0L
            attachedEglContextHandle = 0L
            swapIntervalZeroApplied = false
        }
    }

    private fun bindQuad() {
        if (hasVao && vao != 0) {
            if (lastBoundVao != vao) {
                try {
                    GLES30.glBindVertexArray(vao)
                    lastBoundVao = vao
                } catch (_: Throwable) {
                    lastBoundVao = 0
                }
            }
            if (lastBoundVao == vao) {
                quadBound = true
                return
            }
        }

        if (quadBound) return

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboPos)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboUv)
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        quadBound = true
    }

    private fun ensureSwapIntervalZero() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || swapIntervalZeroApplied) return
        try {
            if (EGL14.eglSwapInterval(eglDisplay, 0)) {
                swapIntervalZeroApplied = true
                return
            }
            swapIntervalZeroApplied = false
            if (++swapIntervalZeroFailCount == 1) {
                VibemisLog.warning("FSR: eglSwapInterval(0) failed; driver may still block on vsync")
            }
        } catch (_: Throwable) {
            swapIntervalZeroApplied = false
            if (++swapIntervalZeroFailCount == 1) {
                VibemisLog.warning("FSR: eglSwapInterval(0) threw; driver may still block on vsync")
            }
        }
    }

    private fun applyFixedState() {
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_STENCIL_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glBlendEquation(GLES20.GL_FUNC_ADD)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ZERO)
        try { GLES20.glDisable(GLES20.GL_DITHER) } catch (_: Throwable) {}
        try { GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1) } catch (_: Throwable) {}
    }

    private fun invalidateTex2DFilterCache(texId: Int) {
        if (texId <= 0) return
        if (lastTex2DFilterTexId == texId) lastTex2DFilterTexId = -1
        if (lastTex2DFilterTexId2 == texId) lastTex2DFilterTexId2 = -1
    }

    private fun setTex2DFilter(toNearest: Boolean) {
        val texId = lastTex2D
        if (texId <= 0) return

        val needApply: Boolean
        when (texId) {
            lastTex2DFilterTexId -> {
                needApply = twoDNearest != toNearest
                twoDNearest = toNearest
            }
            lastTex2DFilterTexId2 -> {
                needApply = twoDNearest2 != toNearest
                twoDNearest2 = toNearest
                val oldId0 = lastTex2DFilterTexId
                val oldF0 = twoDNearest
                lastTex2DFilterTexId = lastTex2DFilterTexId2
                twoDNearest = twoDNearest2
                lastTex2DFilterTexId2 = oldId0
                twoDNearest2 = oldF0
            }
            else -> {
                needApply = true
                lastTex2DFilterTexId2 = lastTex2DFilterTexId
                twoDNearest2 = twoDNearest
                lastTex2DFilterTexId = texId
                twoDNearest = toNearest
            }
        }
        if (!needApply) return

        val filter = if (toNearest) GLES20.GL_NEAREST else GLES20.GL_LINEAR
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
    }

    private fun setOesFilter(toNearest: Boolean) {
        if (oesNearest == toNearest) return
        oesNearest = toNearest
        if (lastTexture != oesTexId) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
            lastTexture = oesTexId
        }
        val filter = if (toNearest) GLES20.GL_NEAREST else GLES20.GL_LINEAR
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, filter)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, filter)
    }

    private fun setRcasLumaUniform(loc: Int, hdr: Boolean) {
        if (loc < 0) return
        if (hdr) {
            GLES20.glUniform3f(loc, LUMA_2020_R, LUMA_2020_G, LUMA_2020_B)
        } else {
            GLES20.glUniform3f(loc, LUMA_709_R, LUMA_709_G, LUMA_709_B)
        }
    }

    private fun getBoundFramebuffer(): Int = try {
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, tmpIntArray, 0)
        tmpIntArray[0]
    } catch (_: Throwable) {
        0
    }

    private fun checkRcasOesHealthOnce(dstW: Int, dstH: Int) {
        if (rcasOesChecked) return
        if (progRcasOes == 0) {
            rcasOesHealthy = false
            rcasOesChecked = true
            return
        }
        if (rcasOesCheckAttempts >= RCAS_OES_CHECK_MAX_ATTEMPTS) {
            rcasOesChecked = true
            return
        }
        rcasOesCheckAttempts++

        activeTexture0()
        val prevFbo = getBoundFramebuffer()
        val prevTex2D = try {
            GLES20.glGetIntegerv(GLES20.GL_TEXTURE_BINDING_2D, tmpIntArray, 0)
            tmpIntArray[0]
        } catch (_: Throwable) {
            0
        }
        val prevViewport = tmpViewportArray
        try {
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, prevViewport, 0)
        } catch (_: Throwable) {
        }

        var testFbo = 0
        var testTex = 0
        var ambiguousAllZero = false

        try {
            clearGlErrors()
            GLES20.glGenFramebuffers(1, tmpIntArray, 0)
            testFbo = tmpIntArray[0]
            GLES20.glGenTextures(1, tmpIntArray, 0)
            testTex = tmpIntArray[0]

            bindTex2DCached(testTex)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 2, 2, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)

            bindFramebufferCached(testFbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, testTex, 0)
            if (!isFboComplete()) {
                rcasOesHealthy = false
                rcasOesChecked = true
                return
            }

            GLES20.glViewport(0, 0, 2, 2)
            useProgram(progRcasOes)
            bindQuad()

            GLES20.glUniform2f(rcasOesUInvDst, 1.0f / max(1, dstW), 1.0f / max(1, dstH))
            GLES20.glUniform1f(rcasOesUSharp, mapUiSharpToInternal(0.2f, true))
            GLES20.glUniformMatrix4fv(rcasOesUTexMat, 1, false, texMatrix, 0)
            setRcasLumaUniform(rcasOesULuma, false)

            if (lastTexture != oesTexId) {
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
                lastTexture = oesTexId
            }

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            val bb = testPixelBuffer
            bb.clear()
            GLES20.glReadPixels(0, 0, 2, 2, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bb)

            var sum = 0
            for (i in 0 until 16) sum = sum or (bb.get(i).toInt() and 0xFF)
            ambiguousAllZero = sum == 0
            rcasOesHealthy = !ambiguousAllZero

            if (hasVao) {
                try {
                    GLES30.glInvalidateFramebuffer(GLES30.GL_FRAMEBUFFER, 1, invalidateColorAttachment, 0)
                } catch (_: Throwable) {
                }
            }
        } finally {
            try {
                GLES20.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3])
            } catch (_: Throwable) {
            }
            curVpW = prevViewport[2]
            curVpH = prevViewport[3]
            bindFramebufferCached(prevFbo)
            bindTex2DCached(prevTex2D)
            if (testTex != 0) {
                tmpIntArray[0] = testTex
                try { GLES20.glDeleteTextures(1, tmpIntArray, 0) } catch (_: Throwable) {}
            }
            if (testFbo != 0) {
                tmpIntArray[0] = testFbo
                try { GLES20.glDeleteFramebuffers(1, tmpIntArray, 0) } catch (_: Throwable) {}
            }
        }

        if (ambiguousAllZero && rcasOesCheckAttempts < RCAS_OES_CHECK_MAX_ATTEMPTS) {
            rcasOesChecked = false
            rcasOesHealthy = false
            return
        }

        rcasOesChecked = true
        lastRcasOesInvDstW = -1
        lastRcasOesInvDstH = -1
        lastRcasOesSharp = -1f
        lastRcasOesTexMatSerial = -1L
        VibemisLog.info("RCAS_OES health=$rcasOesHealthy")
    }

    private fun useProgram(program: Int) {
        if (lastProgram != program) {
            GLES20.glUseProgram(program)
            lastProgram = program
        }
    }

    private fun activeTexture0() {
        if (activeTexUnit != 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            activeTexUnit = 0
        }
    }

    private fun bindFramebufferCached(fboId: Int) {
        if (lastFbo != fboId) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
            lastFbo = fboId
        }
    }

    private fun invalidateDefaultFramebufferColor() {
        try {
            GLES30.glInvalidateFramebuffer(GLES30.GL_FRAMEBUFFER, 1, invalidateDefaultFbAttachments, 0)
        } catch (_: Throwable) {
        }
    }

    private fun bindTex2DCached(texId: Int) {
        activeTexture0()
        if (lastTex2D != texId) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            lastTex2D = texId
        }
    }

    private fun ensureSurfaceTextureAttached(): Boolean {
        val st = decoderSurfaceTex ?: return false
        if (oesTexId == 0) return false

        var curH = eglContextHandle
        if (curH == 0L) {
            curH = safeEglContextHandle(eglContext)
            eglContextHandle = curH
        }
        if (curH == 0L) return false
        if (attachedEglContextHandle == curH) return true

        try {
            st.detachFromGLContext()
        } catch (_: Throwable) {
        }

        return try {
            st.attachToGLContext(oesTexId)
            attachedEglContextHandle = curH
            lastTexture = -1
            lastProgram = -1
            markGlErrorDirty()
            true
        } catch (t: Throwable) {
            attachedEglContextHandle = 0L
            VibemisLog.warning("FSR: SurfaceTexture attachToGLContext failed: $t")
            markGlErrorDirty()
            false
        }
    }

    private fun markGlErrorDirty() {
        glErrorDirty = true
    }

    private fun clearGlErrors() {
        while (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
        }
    }

    private fun ensureEglCurrent(): Boolean {
        if (!isGlReady()) return false
        if (eglContextHandle == 0L) eglContextHandle = safeEglContextHandle(eglContext)
        if (eglWindowSurfaceHandle == 0L) eglWindowSurfaceHandle = safeEglSurfaceHandle(eglWindowSurface)

        val curCtxH = safeEglContextHandle(EGL14.eglGetCurrentContext())
        val curDrawH = safeEglSurfaceHandle(EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW))
        val curReadH = safeEglSurfaceHandle(EGL14.eglGetCurrentSurface(EGL14.EGL_READ))

        if (curCtxH == eglContextHandle && curDrawH == eglWindowSurfaceHandle && curReadH == eglWindowSurfaceHandle) {
            return true
        }

        val ok = EGL14.eglMakeCurrent(eglDisplay, eglWindowSurface, eglWindowSurface, eglContext)
        if (!ok) {
            val err = EGL14.eglGetError()
            VibemisLog.warning("FSR: eglMakeCurrent failed err=0x${err.toString(16)}")
            return false
        }
        swapIntervalZeroApplied = false
        ensureSwapIntervalZero()
        return true
    }

    private fun releaseEglCurrent() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
        try {
            if (eglContextHandle == 0L) eglContextHandle = safeEglContextHandle(eglContext)
            val curCtxH = safeEglContextHandle(EGL14.eglGetCurrentContext())
            if (curCtxH != 0L && curCtxH == eglContextHandle) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                try { EGL14.eglReleaseThread() } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {
        }
    }

    private fun maybeUpdateFsrOverlay() {
        if (!fsrStats.enabled) return
        val now = System.nanoTime()
        val modeSame = fsrStats.mode == lastFsrOverlayMode
        val sharpSame = abs(fsrStats.sharp - lastFsrOverlaySharp) < 0.0005f
        if (modeSame && sharpSame && (now - lastFsrOverlayUpdateNs) < FSR_OVERLAY_UPDATE_NS) return

        lastFsrOverlayUpdateNs = now
        lastFsrOverlayMode = fsrStats.mode
        lastFsrOverlaySharp = fsrStats.sharp
        fsrOverlay = fsrStats.overlayLine()
    }

    private fun maybeUpdateFsrNotesEasuRcas(ok: Boolean, preset: Int, upRatio: Float) {
        val now = System.nanoTime()
        val upX100 = toScaled100(upRatio)
        val same = lastFsrNotesKind == FSR_NOTES_EASU_RCAS &&
            ok == lastFsrNotesOk &&
            preset == lastFsrNotesPreset &&
            upX100 == lastFsrNotesUpX100
        if (same && (now - lastFsrNotesUpdateNs) < FSR_OVERLAY_UPDATE_NS) return

        lastFsrNotesUpdateNs = now
        lastFsrNotesKind = FSR_NOTES_EASU_RCAS
        lastFsrNotesOk = ok
        lastFsrNotesPreset = preset
        lastFsrNotesUpX100 = upX100

        fsrNotesSb.setLength(0)
        fsrNotesSb.append(if (ok) "reason=easu_rcas" else "fallback:easu_rcas_failed")
        fsrNotesSb.append(" easu=").append(if (preset == 0) "P" else if (preset == 1) "B" else "Q")
        fsrNotesSb.append(" | preset=").append(preset).append(" up=")
        appendFixed2Scaled100(fsrNotesSb, upX100)
        fsrStats.notes = fsrNotesSb.toString()
    }

    private fun maybeUpdateFsrNotesRcasOnly(ok: Boolean, preset: Int, nearNative: Boolean, nearThr: Float, reasonId: Int) {
        val now = System.nanoTime()
        val thrX100 = toScaled100(nearThr)
        val same = lastFsrNotesKind == FSR_NOTES_RCAS_ONLY &&
            ok == lastFsrNotesOk &&
            preset == lastFsrNotesPreset &&
            nearNative == lastFsrNotesNearNative &&
            thrX100 == lastFsrNotesThrX100 &&
            reasonId == lastFsrNotesReasonId
        if (same && (now - lastFsrNotesUpdateNs) < FSR_OVERLAY_UPDATE_NS) return

        lastFsrNotesUpdateNs = now
        lastFsrNotesKind = FSR_NOTES_RCAS_ONLY
        lastFsrNotesOk = ok
        lastFsrNotesPreset = preset
        lastFsrNotesNearNative = nearNative
        lastFsrNotesThrX100 = thrX100
        lastFsrNotesReasonId = reasonId

        fsrNotesSb.setLength(0)
        if (ok) {
            when (reasonId) {
                FSR_REASON_NEAR_NATIVE -> fsrNotesSb.append("reason=nearNative")
                FSR_REASON_DST_EQ_SRC -> fsrNotesSb.append("reason=dstEqSrc")
                FSR_REASON_EASU_DISABLED -> fsrNotesSb.append("reason=easu_disabled")
                else -> fsrNotesSb.append("reason=mode!=easu_rcas")
            }
        } else {
            fsrNotesSb.append("fallback:rcas_only_failed")
        }
        fsrNotesSb.append(" | preset=").append(preset)
        fsrNotesSb.append(" | nearNative=").append(nearNative).append(" thr=")
        appendFixed2Scaled100(fsrNotesSb, thrX100)
        fsrStats.notes = fsrNotesSb.toString()
    }

    private fun maybeUpdateSimpleFsrNote(
        kind: Int,
        allowSharpen: Boolean,
        winW: Int,
        winH: Int,
        hintW: Int,
        hintH: Int
    ) {
        if (lastSimpleFsrNoteKind == kind &&
            lastSimpleFsrAllowSharpen == allowSharpen &&
            lastSimpleFsrWinW == winW &&
            lastSimpleFsrWinH == winH &&
            lastSimpleFsrHintW == hintW &&
            lastSimpleFsrHintH == hintH
        ) return

        lastSimpleFsrNoteKind = kind
        lastSimpleFsrAllowSharpen = allowSharpen
        lastSimpleFsrWinW = winW
        lastSimpleFsrWinH = winH
        lastSimpleFsrHintW = hintW
        lastSimpleFsrHintH = hintH

        simpleFsrNotesSb.setLength(0)
        when (kind) {
            SIMPLE_NOTE_GPU_PATH -> simpleFsrNotesSb.append("reason=gpuPathMode")
            SIMPLE_NOTE_HDR_OK -> simpleFsrNotesSb.append("hdr=1 | luma-only")
            SIMPLE_NOTE_HDR_FALLBACK -> simpleFsrNotesSb.append("hdr=1 | reason=").append(
                if (allowSharpen) "rcas_unavailable" else "disabled_or_mode_none"
            )
            SIMPLE_NOTE_BYPASS_MODE_NONE, SIMPLE_NOTE_BYPASS_DISABLED -> {
                simpleFsrNotesSb.append("reason=")
                    .append(if (kind == SIMPLE_NOTE_BYPASS_MODE_NONE) "bypass:mode_none" else "bypass:upscaleDisabled")
                    .append(" | win=").append(winW).append('x').append(winH)
                    .append(" hint=").append(hintW).append('x').append(hintH)
            }
        }
        fsrStats.notes = simpleFsrNotesSb.toString()
    }

    private fun pollFsrGpuTimers() {
        if (!fsrStats.enabled) return
        val easuNs = easuGpuTimer.pollOne()
        if (easuNs > 0L) fsrStats.pushGpuEasu(easuNs)
        val rcasNs = rcasGpuTimer.pollOne()
        if (rcasNs > 0L) fsrStats.pushGpuRcas(rcasNs)
        val blitNs = blitGpuTimer.pollOne()
        if (blitNs > 0L) fsrStats.pushGpuBlit(blitNs)
        if (easuGpuTimer.consumeDisjointTripped() ||
            rcasGpuTimer.consumeDisjointTripped() ||
            blitGpuTimer.consumeDisjointTripped()
        ) {
            fsrStats.disjointEvents++
        }
    }

    private class FsrTelemetry {
        @Volatile
        var enabled = false
        var frames = 0L

        private var easuCpuAvgNs = 0.0
        private var rcasCpuAvgNs = 0.0
        private var blitCpuAvgNs = 0.0
        private var easuGpuAvgNs = 0.0
        private var rcasGpuAvgNs = 0.0
        private var blitGpuAvgNs = 0.0
        var disjointEvents = 0

        var mode = "BYPASS"
        var sharp = 0f
        var srcW = 0
        var srcH = 0
        var dstW = 0
        var dstH = 0
        var sampling = ""
        var notes = ""

        private var tBlit = 0L
        private var tEasu = 0L
        private var tRcas = 0L
        private val lineSb = StringBuilder(256)

        private fun ewma(avg: Double, sample: Long): Double =
            if (avg == 0.0) sample.toDouble() else 0.2 * sample + 0.8 * avg

        fun ticBlit() { tBlit = System.nanoTime() }
        fun tocBlit() { blitCpuAvgNs = ewma(blitCpuAvgNs, System.nanoTime() - tBlit) }
        fun ticEasu() { tEasu = System.nanoTime() }
        fun tocEasu() { easuCpuAvgNs = ewma(easuCpuAvgNs, System.nanoTime() - tEasu) }
        fun ticRcas() { tRcas = System.nanoTime() }
        fun tocRcas() { rcasCpuAvgNs = ewma(rcasCpuAvgNs, System.nanoTime() - tRcas) }

        fun pushGpuEasu(gpuNs: Long) { easuGpuAvgNs = ewma(easuGpuAvgNs, gpuNs) }
        fun pushGpuRcas(gpuNs: Long) { rcasGpuAvgNs = ewma(rcasGpuAvgNs, gpuNs) }
        fun pushGpuBlit(gpuNs: Long) { blitGpuAvgNs = ewma(blitGpuAvgNs, gpuNs) }

        private fun easuBestNs(): Double = if (easuGpuAvgNs > 0.0) easuGpuAvgNs else easuCpuAvgNs
        private fun rcasBestNs(): Double = if (rcasGpuAvgNs > 0.0) rcasGpuAvgNs else rcasCpuAvgNs
        private fun blitBestNs(): Double = if (blitGpuAvgNs > 0.0) blitGpuAvgNs else blitCpuAvgNs

        fun weightMs(): Float {
            val includeBlit = sampling.contains("OES->2D")
            val blitMs = if (includeBlit) (blitBestNs() / 1e6).toFloat() else 0f
            val easuMs = (easuBestNs() / 1e6).toFloat()
            val rcasMs = (rcasBestNs() / 1e6).toFloat()
            return when (mode) {
                "EASU+RCAS" -> blitMs + easuMs + rcasMs
                "RCAS_ONLY", "HDR_RCAS_LUMA" -> blitMs + rcasMs
                else -> 0f
            }
        }

        private fun timingSrcTagForMode(): String {
            val includeBlit = sampling.contains("OES->2D")
            val easuGpu = easuGpuAvgNs > 0.0
            val rcasGpu = rcasGpuAvgNs > 0.0
            val blitGpu = includeBlit && blitGpuAvgNs > 0.0
            return when (mode) {
                "EASU+RCAS" -> {
                    val allGpu = easuGpu && rcasGpu && (!includeBlit || blitGpu)
                    val noGpu = !easuGpu && !rcasGpu && (!includeBlit || !blitGpu)
                    if (allGpu) "GPU" else if (noGpu) "CPU" else "MIX"
                }
                "RCAS_ONLY" -> {
                    if (includeBlit) {
                        if (rcasGpu && blitGpu) "GPU" else if (!rcasGpu && !blitGpu) "CPU" else "MIX"
                    } else {
                        if (rcasGpu) "GPU" else "CPU"
                    }
                }
                else -> "CPU"
            }
        }

        fun overlayLine(): String {
            lineSb.setLength(0)
            if (mode == "EASU+RCAS") {
                val easuMs = easuBestNs() / 1e6
                val rcasMs = rcasBestNs() / 1e6
                val includeBlit = sampling.contains("OES->2D")
                val blitMs = if (includeBlit) blitBestNs() / 1e6 else 0.0
                val totalMs = blitMs + easuMs + rcasMs
                val src = timingSrcTagForMode()

                lineSb.append("FSR ").append(mode).append(" | sharp=")
                appendFixed2(lineSb, sharp.toDouble())
                if (includeBlit) {
                    lineSb.append(" | BLIT=")
                    appendFixed2(lineSb, blitMs)
                    lineSb.append("ms")
                }
                lineSb.append(" | EASU=")
                appendFixed2(lineSb, easuMs)
                lineSb.append("ms RCAS=")
                appendFixed2(lineSb, rcasMs)
                lineSb.append("ms TOT=")
                appendFixed2(lineSb, totalMs)
                lineSb.append("ms (").append(src).append(')')
                return lineSb.toString()
            }

            if (mode == "RCAS_ONLY") {
                val rcasMs = rcasBestNs() / 1e6
                val includeBlit = sampling.contains("OES->2D")
                val blitMs = if (includeBlit) blitBestNs() / 1e6 else 0.0
                val totalMs = blitMs + rcasMs
                val src = timingSrcTagForMode()

                lineSb.append("FSR ").append(mode).append(" | sharp=")
                appendFixed2(lineSb, sharp.toDouble())
                if (includeBlit) {
                    lineSb.append(" | BLIT=")
                    appendFixed2(lineSb, blitMs)
                    lineSb.append("ms")
                }
                lineSb.append(" | RCAS=")
                appendFixed2(lineSb, rcasMs)
                lineSb.append("ms")
                if (includeBlit) {
                    lineSb.append(" TOT=")
                    appendFixed2(lineSb, totalMs)
                    lineSb.append("ms")
                }
                lineSb.append(" (").append(src).append(')')
                return lineSb.toString()
            }

            return "FSR $mode"
        }

        fun periodicLine(): String {
            lineSb.setLength(0)
            val easuMs = easuBestNs() / 1e6
            val rcasMs = rcasBestNs() / 1e6
            val includeBlit = sampling.contains("OES->2D")
            val blitMs = if (includeBlit) blitBestNs() / 1e6 else 0.0
            val src = timingSrcTagForMode()

            lineSb.append("FSR[").append(mode).append('/').append(src).append("] ")
                .append(srcW).append('x').append(srcH).append(" -> ")
                .append(dstW).append('x').append(dstH).append(" | sharp=")
            appendFixed2(lineSb, sharp.toDouble())
            lineSb.append(" | ").append(sampling)
            if (includeBlit) {
                lineSb.append(" | BLIT(avg)=")
                appendFixed2(lineSb, blitMs)
                lineSb.append("ms")
            }
            lineSb.append(" | EASU(avg)=")
            appendFixed2(lineSb, easuMs)
            lineSb.append("ms RCAS(avg)=")
            appendFixed2(lineSb, rcasMs)
            lineSb.append("ms | disjoint=").append(disjointEvents)
            if (notes.isNotEmpty()) {
                lineSb.append(" | ").append(notes)
            }
            return lineSb.toString()
        }
    }

    private class GpuTimeQueryRing {
        private var initTried = false
        private var supported = false
        private var useExt = false
        private val queries = IntArray(RING)
        private val pending = BooleanArray(RING)
        private var write = 0
        private var read = 0
        private var localActive = false
        private var disjointTripped = false
        private val tmpInt = IntArray(1)
        private val tmpLong = LongArray(1)

        private fun initIfNeeded() {
            if (initTried) return
            initTried = true
            if (!hasExt("GL_EXT_disjoint_timer_query")) {
                supported = false
                return
            }
            loadExtIfNeeded()
            supported = false
            useExt = false
            queries.fill(0)
            pending.fill(false)
            write = 0
            read = 0
            localActive = false
            globalActive = false

            try {
                GLES30.glGenQueries(RING, queries, 0)
                supported = queries[0] != 0
            } catch (_: Throwable) {
                supported = false
            }

            if (!supported && sGenQueriesEXT != null) {
                try {
                    sGenQueriesEXT?.invoke(null, RING, queries, 0)
                    supported = queries[0] != 0
                } catch (_: Throwable) {
                    supported = false
                }
            }

            if (supported) {
                useExt = sBeginQueryEXT != null && sEndQueryEXT != null && sGetQueryObjectuivEXT != null
            } else {
                queries.fill(0)
                pending.fill(false)
                write = 0
                read = 0
                localActive = false
                globalActive = false
            }
        }

        fun begin() {
            initIfNeeded()
            if (!supported || localActive || globalActive) return
            if (pending[write]) {
                pollOneInternal()
                if (pending[write]) return
            }
            val q = queries[write]
            if (q == 0) return

            try {
                if (useExt && sBeginQueryEXT != null) {
                    sBeginQueryEXT?.invoke(null, GL_TIME_ELAPSED_EXT, q)
                } else {
                    GLES30.glBeginQuery(GL_TIME_ELAPSED_EXT, q)
                }
                localActive = true
                globalActive = true
            } catch (_: Throwable) {
                supported = false
                localActive = false
                globalActive = false
            }
        }

        fun end() {
            if (!supported || !localActive) return
            try {
                if (useExt && sEndQueryEXT != null) {
                    sEndQueryEXT?.invoke(null, GL_TIME_ELAPSED_EXT)
                } else {
                    GLES30.glEndQuery(GL_TIME_ELAPSED_EXT)
                }
            } catch (_: Throwable) {
                supported = false
            } finally {
                pending[write] = true
                write = (write + 1) % RING
                localActive = false
                globalActive = false
            }
        }

        fun pollOne(): Long {
            initIfNeeded()
            return if (!supported) 0L else pollOneInternal()
        }

        fun consumeDisjointTripped(): Boolean {
            val v = disjointTripped
            disjointTripped = false
            return v
        }

        private fun clearPending() {
            pending.fill(false)
            write = 0
            read = 0
            localActive = false
            globalActive = false
        }

        private fun pollOneInternal(): Long {
            if (!pending[read]) return 0L
            try {
                tmpInt[0] = 0
                GLES20.glGetIntegerv(GL_GPU_DISJOINT_EXT, tmpInt, 0)
                if (tmpInt[0] != 0) {
                    disjointTripped = true
                    clearPending()
                    return 0L
                }
            } catch (_: Throwable) {
            }

            val q = queries[read]
            if (q == 0) {
                pending[read] = false
                read = (read + 1) % RING
                return 0L
            }

            try {
                tmpInt[0] = 0
                if (useExt && sGetQueryObjectuivEXT != null) {
                    sGetQueryObjectuivEXT?.invoke(null, q, GL_QUERY_RESULT_AVAILABLE, tmpInt, 0)
                } else {
                    GLES30.glGetQueryObjectuiv(q, GL_QUERY_RESULT_AVAILABLE, tmpInt, 0)
                }
                if (tmpInt[0] == 0) return 0L
            } catch (_: Throwable) {
                supported = false
                return 0L
            }

            val ns: Long
            try {
                ns = if (useExt) {
                    if (sGetQueryObjectui64vEXT != null) {
                        tmpLong[0] = 0L
                        sGetQueryObjectui64vEXT?.invoke(null, q, GL_QUERY_RESULT, tmpLong, 0)
                        tmpLong[0]
                    } else {
                        tmpInt[0] = 0
                        if (sGetQueryObjectuivEXT != null) {
                            sGetQueryObjectuivEXT?.invoke(null, q, GL_QUERY_RESULT, tmpInt, 0)
                        } else {
                            GLES30.glGetQueryObjectuiv(q, GL_QUERY_RESULT, tmpInt, 0)
                        }
                        tmpInt[0].toLong() and 0xFFFFFFFFL
                    }
                } else if (sGetQueryObjectui64vCore != null) {
                    tmpLong[0] = 0L
                    sGetQueryObjectui64vCore?.invoke(null, q, GL_QUERY_RESULT, tmpLong, 0)
                    tmpLong[0]
                } else {
                    tmpInt[0] = 0
                    GLES30.glGetQueryObjectuiv(q, GL_QUERY_RESULT, tmpInt, 0)
                    tmpInt[0].toLong() and 0xFFFFFFFFL
                }
            } catch (_: Throwable) {
                supported = false
                return 0L
            }

            pending[read] = false
            read = (read + 1) % RING
            return if (ns > 0L) ns else 0L
        }

        fun release() {
            if (!initTried) return
            if (queries[0] != 0) {
                try {
                    if (useExt && sDeleteQueriesEXT != null) {
                        sDeleteQueriesEXT?.invoke(null, RING, queries, 0)
                    } else {
                        GLES30.glDeleteQueries(RING, queries, 0)
                    }
                } catch (_: Throwable) {
                }
            }
            queries.fill(0)
            clearPending()
            supported = false
            initTried = false
            useExt = false
            disjointTripped = false
        }

        companion object {
            private const val GL_TIME_ELAPSED_EXT = 0x88BF
            private const val GL_GPU_DISJOINT_EXT = 0x8FBB
            private const val GL_QUERY_RESULT = 0x8866
            private const val GL_QUERY_RESULT_AVAILABLE = 0x8867
            private const val RING = 8

            private var globalActive = false
            private var sExtLoaded = false
            private var sGenQueriesEXT: Method? = null
            private var sDeleteQueriesEXT: Method? = null
            private var sBeginQueryEXT: Method? = null
            private var sEndQueryEXT: Method? = null
            private var sGetQueryObjectuivEXT: Method? = null
            private var sGetQueryObjectui64vEXT: Method? = null
            private var sGetQueryObjectui64vCore: Method? = null

            private fun loadExtIfNeeded() {
                if (sExtLoaded) return
                sExtLoaded = true
                val c = GLES30::class.java
                try {
                    sGenQueriesEXT = c.getMethod("glGenQueriesEXT", Int::class.javaPrimitiveType, IntArray::class.java, Int::class.javaPrimitiveType)
                    sDeleteQueriesEXT = c.getMethod("glDeleteQueriesEXT", Int::class.javaPrimitiveType, IntArray::class.java, Int::class.javaPrimitiveType)
                    sBeginQueryEXT = c.getMethod("glBeginQueryEXT", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    sEndQueryEXT = c.getMethod("glEndQueryEXT", Int::class.javaPrimitiveType)
                    sGetQueryObjectuivEXT = c.getMethod("glGetQueryObjectuivEXT", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, IntArray::class.java, Int::class.javaPrimitiveType)
                    sGetQueryObjectui64vEXT = runCatching {
                        c.getMethod("glGetQueryObjectui64vEXT", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, LongArray::class.java, Int::class.javaPrimitiveType)
                    }.getOrNull()
                } catch (_: Throwable) {
                    sGenQueriesEXT = null
                    sDeleteQueriesEXT = null
                    sBeginQueryEXT = null
                    sEndQueryEXT = null
                    sGetQueryObjectuivEXT = null
                    sGetQueryObjectui64vEXT = null
                }
                sGetQueryObjectui64vCore = runCatching {
                    c.getMethod("glGetQueryObjectui64v", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, LongArray::class.java, Int::class.javaPrimitiveType)
                }.getOrNull()
            }

            private fun hasExt(ext: String): Boolean {
                try {
                    val exts = GLES20.glGetString(GLES20.GL_EXTENSIONS)
                    if (exts != null && exts.contains(ext)) return true
                } catch (_: Throwable) {
                }
                try {
                    val n = IntArray(1)
                    GLES30.glGetIntegerv(GLES30.GL_NUM_EXTENSIONS, n, 0)
                    for (i in 0 until n[0]) {
                        if (ext == GLES30.glGetStringi(GLES30.GL_EXTENSIONS, i)) return true
                    }
                } catch (_: Throwable) {
                }
                return false
            }
        }
    }

    companion object {
        private const val EGL_OPENGL_ES3_BIT_KHR = 0x00000040
        private const val RCAS_OES_CHECK_MAX_ATTEMPTS = 3

        private const val SIZE_QUERY_MIN_NS = 400_000_000L
        private const val SIZE_QUERY_MAX_NS = 1_200_000_000L

        private const val MAX_PENDING_FRAMES = 8
        private const val MAX_DRAIN_UPDATETEXIMAGE = 4
        private const val CHOREO_MAX_DRAIN_UPDATETEXIMAGE = 2
        private const val CHOREO_INTERVAL_MIN_NS = 8_000_000L
        private const val CHOREO_INTERVAL_MAX_NS = 50_000_000L
        private const val CHOREO_INTERVAL_DEFAULT_NS = 16_666_666L
        private const val CHOREO_LATE_LATCH_NS = 600_000
        private const val APP_VSYNC_OFFSET_QUERY_INTERVAL_NS = 2_000_000_000L

        private const val STOP_JOIN_TIMEOUT_MS = 1200L
        private const val STOP_JOIN_GRACE_MS = 600L

        private const val FSR_OVERLAY_UPDATE_NS = 100_000_000L
        private const val FSR_NOTES_EASU_RCAS = 0
        private const val FSR_NOTES_RCAS_ONLY = 1
        private const val FSR_REASON_NEAR_NATIVE = 0
        private const val FSR_REASON_DST_EQ_SRC = 1
        private const val FSR_REASON_EASU_DISABLED = 2
        private const val FSR_REASON_MODE_NOT_EASU_RCAS = 3

        private const val SIMPLE_NOTE_GPU_PATH = 0
        private const val SIMPLE_NOTE_HDR_OK = 1
        private const val SIMPLE_NOTE_HDR_FALLBACK = 2
        private const val SIMPLE_NOTE_BYPASS_MODE_NONE = 3
        private const val SIMPLE_NOTE_BYPASS_DISABLED = 4

        private const val LUMA_709_R = 0.2126f
        private const val LUMA_709_G = 0.7152f
        private const val LUMA_709_B = 0.0722f
        private const val LUMA_2020_R = 0.2627f
        private const val LUMA_2020_G = 0.6780f
        private const val LUMA_2020_B = 0.0593f

        private fun queryAppVsyncOffsetNs(ctx: Context?): Long {
            if (ctx == null) return 0L
            return try {
                val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                dm?.getDisplay(Display.DEFAULT_DISPLAY)?.appVsyncOffsetNanos ?: 0L
            } catch (_: Throwable) {
                0L
            }
        }

        private fun calculateAdaptiveDrainConservative(estimatedIntervalNs: Long): Int =
            if (estimatedIntervalNs <= 14_000_000L) 1 else 2

        private fun desiredGlOsPriority(pacing: Int, usingChoreoBackend: Boolean): Int {
            if (usingChoreoBackend) return Process.THREAD_PRIORITY_URGENT_DISPLAY
            return when (pacing) {
                PreferenceConfiguration.FRAME_PACING_MIN_LATENCY,
                PreferenceConfiguration.FRAME_PACING_GPU_RAW,
                PreferenceConfiguration.FRAME_PACING_WARP,
                PreferenceConfiguration.FRAME_PACING_WARP2 -> Process.THREAD_PRIORITY_URGENT_DISPLAY
                else -> Process.THREAD_PRIORITY_DISPLAY
            }
        }

        private fun computeFastBypassStatic(prefs: PreferenceConfiguration?): Boolean {
            if (prefs == null) return false
            if (prefs.gpuPathMode || !prefs.videoUpscaleEnable) return true
            val mode = prefs.videoUpscaleMode
            return "none" == mode
        }

        private fun clamp01(v: Float): Float = min(1f, max(0f, v))

        private fun toScaled100(v: Float): Int =
            (v * 100.0f + if (v >= 0f) 0.5f else -0.5f).toInt()

        private fun appendFixed2Scaled100(sb: StringBuilder, scaledIn: Int) {
            var scaled = scaledIn
            if (scaled < 0) {
                sb.append('-')
                scaled = -scaled
            }
            val i = scaled / 100
            val f = scaled % 100
            sb.append(i).append('.')
            if (f < 10) sb.append('0')
            sb.append(f)
        }

        private fun appendFixed2(sb: StringBuilder, vIn: Double) {
            if (vIn.isNaN() || vIn.isInfinite()) {
                sb.append(vIn)
                return
            }
            var v = vIn
            if (v < 0.0) {
                sb.append('-')
                v = -v
            }
            val scaled = (v * 100.0 + 0.5).toLong()
            sb.append(scaled / 100).append('.')
            val frac = scaled % 100
            if (frac < 10) sb.append('0')
            sb.append(frac)
        }

        private fun mapUiSharpToInternal(ui: Float, nearNative: Boolean): Float {
            val s = clamp01(ui)
            if (s <= 0.02f) return 0f
            return if (nearNative) 0.55f * s else s
        }

        private fun mapUiSharpToInternalEasuRcasLinear(ui: Float, presetIn: Int): Float {
            var s = clamp01(ui)
            if (s <= 0.02f) return 0f
            val preset = presetIn.coerceIn(0, 2)
            val gain = when (preset) {
                0 -> 1.35f
                1 -> 1.10f
                else -> 1.00f
            }
            s *= gain
            return if (s > 1f) 1f else s
        }

        private fun mapUiSharpToInternalRcas(ui: Float, presetIn: Int, nearNative: Boolean): Float {
            var s = clamp01(ui)
            if (ui <= 0.05f) return 0f
            s = (s - 0.05f) / 0.95f
            s = (1.0 - exp(-3.0 * s)).toFloat()
            if (s <= 0.05f) return 0f
            s = (s - 0.05f) / 0.95f
            s = (1.0 - exp(-3.0 * s)).toFloat()

            val preset = presetIn.coerceIn(0, 2)
            val cap = when (preset) {
                0 -> if (nearNative) 0.18f else 0.25f
                1 -> if (nearNative) 0.28f else 0.40f
                else -> if (nearNative) 0.36f else 0.55f
            }
            return cap * s
        }

        private fun safeEglContextHandle(ctx: EGLContext?): Long {
            if (ctx == null) return 0L
            return try {
                ctx.nativeHandle
            } catch (_: Throwable) {
                0L
            }
        }

        private fun safeEglSurfaceHandle(s: EGLSurface?): Long {
            if (s == null) return 0L
            return try {
                s.nativeHandle
            } catch (_: Throwable) {
                0L
            }
        }

        private fun fsOesTo2D(oesFs: String): String =
            oesFs
                .replace("#extension GL_OES_EGL_image_external_essl3 : require\n", "")
                .replace("precision highp samplerExternalOES;\n", "")
                .replace("samplerExternalOES", "sampler2D")

        private fun compileShader(type: Int, src: String): Int {
            val sh = GLES20.glCreateShader(type)
            GLES20.glShaderSource(sh, src)
            GLES20.glCompileShader(sh)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(sh)
                GLES20.glDeleteShader(sh)
                throw RuntimeException("Shader compile failed: $log")
            }
            return sh
        }

        private fun linkProgram(vs: Int, fsSrc: String): Int {
            val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSrc)
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, vs)
            GLES20.glAttachShader(p, fs)
            GLES20.glBindAttribLocation(p, 0, "aPos")
            GLES20.glBindAttribLocation(p, 1, "aUv")
            GLES20.glLinkProgram(p)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(p)
                GLES20.glDeleteProgram(p)
                throw RuntimeException("Program link failed: $log")
            }
            GLES20.glDeleteShader(fs)
            return p
        }

        private val VS = """
            #version 300 es
            precision highp float;
            layout(location=0) in vec2 aPos;
            layout(location=1) in vec2 aUv;
            out vec2 vUv;
            void main(){ vUv=aUv; gl_Position=vec4(aPos,0.0,1.0); }
        """.trimIndent()

        private val VS_BLIT_TEXMAT = """
            #version 300 es
            precision highp float;
            layout(location=0) in vec2 aPos;
            layout(location=1) in vec2 aUv;
            uniform mat4 uTexMatrix;
            out vec2 vUv;
            void main(){ vUv=(uTexMatrix*vec4(aUv,0.0,1.0)).xy; gl_Position=vec4(aPos,0.0,1.0); }
        """.trimIndent()

        private val VS_RCAS_OES = """
            #version 300 es
            precision highp float;
            layout(location=0) in vec2 aPos;
            layout(location=1) in vec2 aUv;
            out vec2 vUv;
            out vec2 vUv0;
            out vec2 vStepX;
            out vec2 vStepY;
            uniform mat4 uTexMatrix;
            uniform vec2 uInvDstSize;
            void main(){
              vUv = aUv;
              vec2 texel = uInvDstSize;
              vUv0   = (uTexMatrix * vec4(aUv, 0.0, 1.0)).xy;
              vStepX = (uTexMatrix * vec4(texel.x, 0.0, 0.0, 0.0)).xy;
              vStepY = (uTexMatrix * vec4(0.0, texel.y, 0.0, 0.0)).xy;
              gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """.trimIndent()

        private val FS_OES_BLIT = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float;
            precision highp samplerExternalOES;
            in vec2 vUv;
            layout(location=0) out vec4 fragColor;
            uniform samplerExternalOES uTex;
            void main(){ fragColor = texture(uTex, vUv); }
        """.trimIndent()

        private val FS_OES_TO_2D = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float;
            in vec2 vUv;
            layout(location=0) out vec4 fragColor;
            uniform samplerExternalOES uTex;
            void main(){ fragColor = texture(uTex, vUv); }
        """.trimIndent()

        private val FS_EASU_QUALITY = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float;
            precision highp int;
            precision highp samplerExternalOES;

            in vec2 vUv;
            layout(location=0) out vec4 fragColor;

            uniform samplerExternalOES uTex;
            uniform mat4 uTexMatrix;
            uniform vec2 uInvSrcSize;

            float luma(vec3 c){ return c.g + 0.5 * (c.r + c.b); }

            float APrxLoRcpF1(float a){ return uintBitsToFloat(uint(0x7ef07ebb) - floatBitsToUint(a)); }
            float APrxLoRsqF1(float a){ return uintBitsToFloat(uint(0x5f347d74) - (floatBitsToUint(a) >> uint(1))); }

            void FsrEasuTap(
                inout vec3 aC,
                inout float aW,
                vec2 off,
                vec2 dir,
                vec2 len,
                float lob,
                float clp,
                vec3 c)
            {
                vec2 v;
                v.x = (off.x * ( dir.x)) + (off.y * dir.y);
                v.y = (off.x * (-dir.y)) + (off.y * dir.x);
                v *= len;
                float d2 = v.x * v.x + v.y * v.y;
                d2 = min(d2, clp);
                float wB = (2.0 / 5.0) * d2 + -1.0;
                float wA = lob * d2 + -1.0;
                wB *= wB;
                wA *= wA;
                wB = (25.0 / 16.0) * wB + (-(25.0 / 16.0 - 1.0));
                float w = wB * wA;
                aC += c * w;
                aW += w;
            }

            void FsrEasuSet(
                inout vec2 dir,
                inout float len,
                vec2 pp,
                bool biS, bool biT, bool biU, bool biV,
                float lA, float lB, float lC, float lD, float lE)
            {
                float w = 0.0;
                if (biS) w = (1.0 - pp.x) * (1.0 - pp.y);
                if (biT) w = (pp.x) * (1.0 - pp.y);
                if (biU) w = (1.0 - pp.x) * (pp.y);
                if (biV) w = (pp.x) * (pp.y);

                float dc = lD - lC;
                float cb = lC - lB;
                float lenX = max(abs(dc), abs(cb));
                lenX = APrxLoRcpF1(lenX);
                float dirX = lD - lB;
                lenX = clamp(abs(dirX) * lenX, 0.0, 1.0);
                lenX *= lenX;

                float ec = lE - lC;
                float ca = lC - lA;
                float lenY = max(abs(ec), abs(ca));
                lenY = APrxLoRcpF1(lenY);
                float dirY = lE - lA;
                lenY = clamp(abs(dirY) * lenY, 0.0, 1.0);
                lenY *= lenY;

                dir += vec2(dirX, dirY) * w;
                len += w * (lenX + lenY);
            }

            vec2 sampleUv(vec2 baseUv, vec2 stepX, vec2 stepY, float ox, float oy){
                return baseUv + stepX * ox + stepY * oy;
            }

            void main(){
                vec2 srcSize = 1.0 / max(uInvSrcSize, vec2(1e-6));
                vec2 pp = vUv * srcSize - vec2(0.5);
                vec2 fp = floor(pp);
                pp -= fp;

                vec2 baseSrcUv = (fp + vec2(0.5)) * uInvSrcSize;
                vec2 baseUv = (uTexMatrix * vec4(baseSrcUv, 0.0, 1.0)).xy;
                vec2 stepX = (uTexMatrix * vec4(uInvSrcSize.x, 0.0, 0.0, 0.0)).xy;
                vec2 stepY = (uTexMatrix * vec4(0.0, uInvSrcSize.y, 0.0, 0.0)).xy;

                vec3 bC = texture(uTex, sampleUv(baseUv, stepX, stepY,  0.0, -1.0)).rgb;
                vec3 cC = texture(uTex, sampleUv(baseUv, stepX, stepY,  1.0, -1.0)).rgb;
                vec3 eC = texture(uTex, sampleUv(baseUv, stepX, stepY, -1.0,  0.0)).rgb;
                vec3 fC = texture(uTex, sampleUv(baseUv, stepX, stepY,  0.0,  0.0)).rgb;
                vec3 gC = texture(uTex, sampleUv(baseUv, stepX, stepY,  1.0,  0.0)).rgb;
                vec3 hC = texture(uTex, sampleUv(baseUv, stepX, stepY,  2.0,  0.0)).rgb;
                vec3 iC = texture(uTex, sampleUv(baseUv, stepX, stepY, -1.0,  1.0)).rgb;
                vec3 jC = texture(uTex, sampleUv(baseUv, stepX, stepY,  0.0,  1.0)).rgb;
                vec3 kC = texture(uTex, sampleUv(baseUv, stepX, stepY,  1.0,  1.0)).rgb;
                vec3 lC = texture(uTex, sampleUv(baseUv, stepX, stepY,  2.0,  1.0)).rgb;
                vec3 nC = texture(uTex, sampleUv(baseUv, stepX, stepY,  0.0,  2.0)).rgb;
                vec3 oC = texture(uTex, sampleUv(baseUv, stepX, stepY,  1.0,  2.0)).rgb;

                float bL = luma(bC);
                float cL = luma(cC);
                float eL = luma(eC);
                float fL = luma(fC);
                float gL = luma(gC);
                float hL = luma(hC);
                float iL = luma(iC);
                float jL = luma(jC);
                float kL = luma(kC);
                float lL = luma(lC);
                float nL = luma(nC);
                float oL = luma(oC);

                vec2 dir = vec2(0.0);
                float len = 0.0;
                FsrEasuSet(dir, len, pp, true,  false, false, false, bL, eL, fL, gL, jL);
                FsrEasuSet(dir, len, pp, false, true,  false, false, cL, fL, gL, hL, kL);
                FsrEasuSet(dir, len, pp, false, false, true,  false, fL, iL, jL, kL, nL);
                FsrEasuSet(dir, len, pp, false, false, false, true,  gL, jL, kL, lL, oL);

                vec2 dir2 = dir * dir;
                float dirR = dir2.x + dir2.y;
                const float DIR_THRESHOLD = 32768.0;
                bool zro = dirR < (1.0 / DIR_THRESHOLD);
                dirR = APrxLoRsqF1(dirR);
                dirR = zro ? 1.0 : dirR;
                dir.x = zro ? 1.0 : dir.x;
                dir *= vec2(dirR);

                len = len * 0.5;
                len *= len;

                float stretch = (dir.x * dir.x + dir.y * dir.y) * APrxLoRcpF1(max(abs(dir.x), abs(dir.y)));
                vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 + -0.5 * len);

                float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;
                float clp = APrxLoRcpF1(lob);

                vec3 aC = vec3(0.0);
                float aW = 0.0;

                FsrEasuTap(aC, aW, vec2( 0.0,-1.0) - pp, dir, len2, lob, clp, bC);
                FsrEasuTap(aC, aW, vec2( 1.0,-1.0) - pp, dir, len2, lob, clp, cC);
                FsrEasuTap(aC, aW, vec2(-1.0, 1.0) - pp, dir, len2, lob, clp, iC);
                FsrEasuTap(aC, aW, vec2( 0.0, 1.0) - pp, dir, len2, lob, clp, jC);
                FsrEasuTap(aC, aW, vec2( 0.0, 0.0) - pp, dir, len2, lob, clp, fC);
                FsrEasuTap(aC, aW, vec2(-1.0, 0.0) - pp, dir, len2, lob, clp, eC);
                FsrEasuTap(aC, aW, vec2( 1.0, 1.0) - pp, dir, len2, lob, clp, kC);
                FsrEasuTap(aC, aW, vec2( 2.0, 1.0) - pp, dir, len2, lob, clp, lC);
                FsrEasuTap(aC, aW, vec2( 2.0, 0.0) - pp, dir, len2, lob, clp, hC);
                FsrEasuTap(aC, aW, vec2( 1.0, 0.0) - pp, dir, len2, lob, clp, gC);
                FsrEasuTap(aC, aW, vec2( 1.0, 2.0) - pp, dir, len2, lob, clp, oC);
                FsrEasuTap(aC, aW, vec2( 0.0, 2.0) - pp, dir, len2, lob, clp, nC);

                vec3 pix = aC / max(aW, 1e-6);
                vec3 mn = min(min(fC, gC), min(jC, kC));
                vec3 mx = max(max(fC, gC), max(jC, kC));
                pix = clamp(pix, mn, mx);
                fragColor = vec4(clamp(pix, 0.0, 1.0), 1.0);
            }
        """.trimIndent()

        private val FS_EASU_BALANCED = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision mediump float;
            precision highp samplerExternalOES;

            in highp vec2 vUv;
            layout(location=0) out vec4 fragColor;
            uniform samplerExternalOES uTex;
            uniform highp vec2 uInvSrcSize;
            uniform highp mat4 uTexMatrix;

            mediump float luma(mediump vec3 c){ return c.g + 0.5 * (c.r + c.b); }

            void main(){
              highp vec2 uv = (uTexMatrix * vec4(vUv, 0.0, 1.0)).xy;
              highp vec2 stepX = (uTexMatrix * vec4(uInvSrcSize.x, 0.0, 0.0, 0.0)).xy;
              highp vec2 stepY = (uTexMatrix * vec4(0.0, uInvSrcSize.y, 0.0, 0.0)).xy;

              mediump vec3 c = texture(uTex, uv).rgb;
              mediump vec3 l = texture(uTex, uv - stepX).rgb;
              mediump vec3 r = texture(uTex, uv + stepX).rgb;
              mediump vec3 d = texture(uTex, uv - stepY).rgb;
              mediump vec3 u = texture(uTex, uv + stepY).rgb;

              mediump float gx = luma(r) - luma(l);
              mediump float gy = luma(u) - luma(d);
              mediump float ax = abs(gx);
              mediump float ay = abs(gy);
              mediump float edge = clamp((ax + ay) * 1.40, 0.0, 1.0);

              mediump vec2 ed = normalize(vec2(gy, -gx) + vec2(1e-6));
              highp vec2 duv = ed.x * stepX + ed.y * stepY;

              mediump vec3 s1 = texture(uTex, uv + duv * 0.5).rgb;
              mediump vec3 s2 = texture(uTex, uv - duv * 0.5).rgb;
              mediump vec3 along = 0.5 * (s1 + s2);

              mediump float dirConfidence = abs(ax - ay) / (ax + ay + 1e-4);
              mediump float blend = edge * (0.66 + 0.10 * dirConfidence);
              mediump vec3 pix = mix(c, along, blend);

              mediump vec3 mn = min(c, min(min(l, r), min(u, d)));
              mediump vec3 mx = max(c, max(max(l, r), max(u, d)));
              pix = clamp(pix, mn, mx);
              fragColor = vec4(clamp(pix, 0.0, 1.0), 1.0);
            }
        """.trimIndent()

        private val FS_EASU_PERF = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float;
            precision highp samplerExternalOES;
            in vec2 vUv;
            layout(location=0) out vec4 fragColor;
            uniform samplerExternalOES uTex;
            uniform vec2 uInvSrcSize;
            uniform mat4 uTexMatrix;

            mediump float luma(mediump vec3 c){ return c.g + 0.5 * (c.r + c.b); }

            void main(){
              highp vec2 uv     = (uTexMatrix * vec4(vUv, 0.0, 1.0)).xy;
              highp vec2 stepX  = (uTexMatrix * vec4(uInvSrcSize.x, 0.0, 0.0, 0.0)).xy;
              highp vec2 stepY  = (uTexMatrix * vec4(0.0, uInvSrcSize.y, 0.0, 0.0)).xy;

              mediump vec3 c = texture(uTex, uv).rgb;
              mediump vec3 l = texture(uTex, uv - stepX).rgb;
              mediump vec3 r = texture(uTex, uv + stepX).rgb;
              mediump vec3 t = texture(uTex, uv + stepY).rgb;
              mediump vec3 b = texture(uTex, uv - stepY).rgb;

              mediump float lc = luma(c);
              mediump float ll = luma(l);
              mediump float lr = luma(r);
              mediump float lt = luma(t);
              mediump float lb = luma(b);

              mediump float gx = abs(lr - ll);
              mediump float gy = abs(lt - lb);
              mediump float invG = 1.0 / (gx + gy + 1e-4);
              mediump float verticalEdge = gx * invG;
              mediump float anis = abs(gx - gy) * invG;

              mediump float lowIso = 0.25 * (ll + lr + lt + lb);
              mediump float lowH   = 0.5 * (ll + lr);
              mediump float lowV   = 0.5 * (lt + lb);
              mediump float lowDir = mix(lowH, lowV, verticalEdge);
              mediump float low = mix(lowIso, lowDir, 0.78 * anis);

              mediump float detail = lc - low;
              mediump float ad = max(abs(detail) - (1.0 / 512.0), 0.0);
              detail = (detail < 0.0) ? -ad : ad;

              mediump float gain = 0.10 + 0.055 * anis;
              mediump float l2 = lc + detail * gain;

              mediump float mn = min(lc, min(min(ll, lr), min(lt, lb)));
              mediump float mx = max(lc, max(max(ll, lr), max(lt, lb)));
              mediump float pad = 0.008 + 0.018 * anis;
              l2 = clamp(l2, mn - pad, mx + pad);

              mediump float delta = l2 - lc;
              mediump float scale = (lc > 1e-3) ? (l2 / lc) : 1.0;
              mediump vec3 scaled = c * scale;
              mediump vec3 additive = c + vec3(delta);
              mediump float useScale = step(1e-3, lc);
              mediump vec3 outRgb = mix(additive, scaled, useScale);
              fragColor = vec4(clamp(outRgb, 0.0, 1.0), 1.0);
            }
        """.trimIndent()

        private val FS_RCAS = """
            #version 300 es
            #ifdef USE_OES
            #extension GL_OES_EGL_image_external_essl3 : require
            #endif
            precision highp float;

            in vec2 vUv;
            #if defined(USE_OES) && defined(RCAS_OES_VS)
            in vec2 vUv0;
            in vec2 vStepX;
            in vec2 vStepY;
            #endif

            layout(location=0) out vec4 fragColor;

            #ifdef USE_OES
            uniform samplerExternalOES uTexOES;
            uniform mat4 uTexMatrix;
            #else
            uniform sampler2D uUpscaled;
            #endif

            uniform vec2  uInvDstSize;
            uniform float uSharp;
            uniform vec3  uLumaCoeffs;

            float luma(vec3 c){ return dot(c, uLumaCoeffs); }

            void main(){
            #ifdef USE_OES
              #ifdef RCAS_OES_VS
                vec3 c  = texture(uTexOES, vUv0).rgb;
                vec3 rx = texture(uTexOES, vUv0 + vStepX).rgb;
                vec3 lx = texture(uTexOES, vUv0 - vStepX).rgb;
                vec3 ty = texture(uTexOES, vUv0 + vStepY).rgb;
                vec3 by = texture(uTexOES, vUv0 - vStepY).rgb;
              #else
                vec2 texel = uInvDstSize;
                vec2 uv0    = (uTexMatrix * vec4(vUv, 0.0, 1.0)).xy;
                vec2 stepX  = (uTexMatrix * vec4(texel.x, 0.0, 0.0, 0.0)).xy;
                vec2 stepY  = (uTexMatrix * vec4(0.0, texel.y, 0.0, 0.0)).xy;
                vec3 c  = texture(uTexOES, uv0).rgb;
                vec3 rx = texture(uTexOES, uv0 + stepX).rgb;
                vec3 lx = texture(uTexOES, uv0 - stepX).rgb;
                vec3 ty = texture(uTexOES, uv0 + stepY).rgb;
                vec3 by = texture(uTexOES, uv0 - stepY).rgb;
              #endif
            #else
              vec2 texel = uInvDstSize;
              vec2 uv0 = vUv;
              vec3 c  = texture(uUpscaled, uv0).rgb;
              vec3 rx = texture(uUpscaled, uv0 + vec2(texel.x, 0.0)).rgb;
              vec3 lx = texture(uUpscaled, uv0 - vec2(texel.x, 0.0)).rgb;
              vec3 ty = texture(uUpscaled, uv0 + vec2(0.0, texel.y)).rgb;
              vec3 by = texture(uUpscaled, uv0 - vec2(0.0, texel.y)).rgb;
            #endif

              float sharp = clamp(uSharp, 0.0, 1.0);
              if (sharp <= 0.0001) {
                fragColor = vec4(c, 1.0);
                return;
              }

            #ifdef RCAS_LUMA_ONLY
              float lc  = luma(c);
              float lrx = luma(rx);
              float llx = luma(lx);
              float lty = luma(ty);
              float lby = luma(by);

              float blur4  = 0.25 * (lrx + llx + lty + lby);
              float detail = lc - blur4;
              float edgeX = lrx - llx;
              float edgeY = lty - lby;
              float edgeW = 1.0 / (1.0 + 8.0 * (edgeX * edgeX + edgeY * edgeY));
              float outL = lc + detail * (1.35 * sharp * edgeW);

              float lo = min(lc, min(min(llx, lrx), min(lty, lby)));
              float hi = max(lc, max(max(llx, lrx), max(lty, lby)));
              float pad = 0.01 + 0.04 * sharp;
              outL = clamp(outL, lo - pad, hi + pad);

              float scale = (lc > 1e-5) ? (outL / lc) : 1.0;
              fragColor = vec4(clamp(c * scale, 0.0, 1.0), 1.0);
            #else
              vec3 mn4 = min(min(lx, rx), min(ty, by));
              vec3 mx4 = max(max(lx, rx), max(ty, by));

              vec3 hitMin = min(mn4, c) / max(4.0 * mx4, vec3(1e-6));
              vec3 denMax = min(4.0 * mn4 - vec3(4.0), vec3(-1e-6));
              vec3 hitMax = (vec3(1.0) - max(mx4, c)) / denMax;
              vec3 lobeRGB = max(-hitMin, hitMax);

              const float RCAS_LIMIT = 0.1875;
              float lobe = max(-RCAS_LIMIT,
                               min(max(max(lobeRGB.r, lobeRGB.g), lobeRGB.b), 0.0));
              lobe *= sharp;

              float rcpL = 1.0 / max(4.0 * lobe + 1.0, 1e-5);
              vec3 outc = (c + lobe * (lx + rx + ty + by)) * rcpL;
              fragColor = vec4(clamp(outc, 0.0, 1.0), 1.0);
            #endif
            }
        """.trimIndent()

        private val FS_RCAS_FAST = """
            #version 300 es
            precision highp float;
            in vec2 vUv;
            layout(location=0) out vec4 fragColor;
            uniform sampler2D uUpscaled;
            uniform vec2  uInvDstSize;
            uniform float uSharp;
            uniform vec3  uLumaCoeffs;
            mediump float fastLuma(mediump vec3 c){ return c.g + 0.5 * (c.r + c.b); }
            void main(){
              highp vec2 texel = uInvDstSize;
              highp vec2 uv0 = vUv;
              mediump vec3 c = texture(uUpscaled, uv0).rgb;
              mediump float sharp = clamp(uSharp, 0.0, 1.0);
              if (sharp <= 0.0001) { fragColor = vec4(c, 1.0); return; }

              mediump float lc = fastLuma(c);
              mediump float dx = dFdx(lc);
              mediump float dy = dFdy(lc);
              mediump float useY = step(abs(dy), abs(dx));
              highp vec2 axis = mix(vec2(texel.x, 0.0), vec2(0.0, texel.y), useY);

              mediump vec3 p = texture(uUpscaled, uv0 + axis).rgb;
              mediump vec3 n = texture(uUpscaled, uv0 - axis).rgb;
              mediump float lp = fastLuma(p);
              mediump float ln = fastLuma(n);

              mediump float blur = 0.5 * (lp + ln);
              mediump float detail = lc - blur;
              mediump float ad = max(abs(detail) - (1.0 / 512.0), 0.0);
              detail = (detail < 0.0) ? -ad : ad;

              mediump float gMax = max(abs(dx), abs(dy));
              mediump float gMin = min(abs(dx), abs(dy));
              mediump float dirConfidence = (gMax - gMin) / (gMax + gMin + 1e-4);
              mediump float k = sharp * (1.18 + 0.22 * dirConfidence);
              mediump float outL = lc + detail * k;

              mediump float lo = min(lc, min(lp, ln));
              mediump float hi = max(lc, max(lp, ln));
              mediump float pad = 0.008 + 0.035 * sharp;
              outL = clamp(outL, lo - pad, hi + pad);

              mediump vec3 outc = c + vec3(outL - lc);
              fragColor = vec4(clamp(outc, 0.0, 1.0), 1.0);
            }
        """.trimIndent()
    }
}
