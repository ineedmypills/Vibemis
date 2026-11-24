package com.ineedmypills.vibemis.perf

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import com.ineedmypills.vibemis.VibemisLog
import com.ineedmypills.vibemis.nvstream.jni.VibemisBridge
import com.ineedmypills.vibemis.preferences.PreferenceConfiguration
import kotlin.math.max

/**
 * Thermal-aware CPU performance controller.
 * Uses Android Dynamic Performance Framework (ADPF PerformanceHintManager) on Android 12+
 * and native core-affinity pinning without wasteful synthetic spin-wait battery drain.
 */
object CpuWarmUp {

    private var adpfSession: PerformanceHintManager.Session? = null

    @Volatile
    private var running = false

    @Synchronized
    fun start(context: Context) {
        if (running) return
        val appCtx = context.applicationContext ?: return
        val cfg = PreferenceConfiguration.readPreferences(appCtx)
        if (!cfg.cpuWarmupEnabled || cfg.cpuWarmupMode == "off") {
            return
        }

        // Pin existing decoder/network/GL threads to performance cores
        try {
            VibemisBridge.pinHotThreadsToBigCores()
        } catch (_: Throwable) {
        }

        val targetFps = max(30, cfg.fps.toInt())
        val targetWorkNs = when (cfg.cpuWarmupMode) {
            "aggressive" -> (1_000_000_000L / targetFps) * 75L / 100L
            "medium" -> (1_000_000_000L / targetFps) * 85L / 100L
            else -> 1_000_000_000L / targetFps
        }.coerceIn(2_000_000L, 33_333_333L)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val mgr = appCtx.getSystemService(PerformanceHintManager::class.java)
                val session = mgr?.createHintSession(intArrayOf(Process.myTid()), targetWorkNs)
                if (session != null) {
                    adpfSession = session
                    // Provide an initial budget signal so scheduler boosts appropriately without busy-spinning
                    session.reportActualWorkDuration(targetWorkNs)
                    VibemisLog.info("CpuWarmUp ADPF session started: targetNs=$targetWorkNs mode=${cfg.cpuWarmupMode}")
                }
            } catch (t: Throwable) {
                VibemisLog.warning("CpuWarmUp ADPF session unavailable: ${t.javaClass.simpleName}")
            }
        }

        running = true
        VibemisLog.info(
            "CpuWarmUp active: mode=${cfg.cpuWarmupMode} " +
                "topology(prime=${safeCoreCount { VibemisBridge.getPrimeCoreCount() }}," +
                "big=${safeCoreCount { VibemisBridge.getBigCoreCount() }}," +
                "little=${safeCoreCount { VibemisBridge.getLittleCoreCount() }})"
        )
    }

    @Synchronized
    fun stop() {
        if (!running && adpfSession == null) return
        running = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                adpfSession?.close()
            } catch (_: Throwable) {
            }
        }
        adpfSession = null
        VibemisLog.info("CpuWarmUp stopped")
    }

    private inline fun safeCoreCount(block: () -> Int): Int = try {
        block()
    } catch (_: Throwable) {
        0
    }
}
