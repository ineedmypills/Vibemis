package com.ineedmypills.vibemis.shadows

import com.ineedmypills.vibemis.nvstream.jni.VibemisBridge
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@Implements(value = VibemisBridge::class, isInAndroidSdk = false)
class ShadowVibemisBridge {
    companion object {
        @JvmStatic
        @Implementation
        protected fun __staticInitializer__() {
            // no-op
        }

        @JvmField
        val AUDIO_CONFIGURATION_STEREO = AudioConfiguration(2, 0x3)

        @JvmField
        val AUDIO_CONFIGURATION_51_SURROUND = AudioConfiguration(6, 0x3F)

        @JvmField
        val AUDIO_CONFIGURATION_71_SURROUND = AudioConfiguration(8, 0x63F)

        const val DR_OK = 0

        @JvmStatic
        fun CAPABILITY_SLICES_PER_FRAME(s: Byte): Int = 0

        @JvmStatic
        fun getPendingAudioDuration(): Int = 0

        @JvmStatic
        fun cleanupBridge() {}

        @JvmStatic
        @Implementation
        fun pinCurrentThreadToBigCores() {}

        @JvmStatic
        @Implementation
        fun requestIdrFrame() {}

        @JvmStatic
        @Implementation
        fun pinHotThreadsToBigCores(): Int = 0

        @JvmStatic
        @Implementation
        fun getPrimeCoreCount(): Int = 1

        @JvmStatic
        @Implementation
        fun getBigCoreCount(): Int = 3

        @JvmStatic
        @Implementation
        fun getLittleCoreCount(): Int = 4

        @JvmStatic
        @Implementation
        fun pinCurrentThreadToCoreSet(coreSet: String): Boolean = true
    }

    class AudioConfiguration(
        @JvmField val channelCount: Int,
        @JvmField val channelMask: Int
    ) {
        fun toInt(): Int = 0
    }
}
