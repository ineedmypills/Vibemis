package com.ineedmypills.vibemis.binding.input.capture

import android.app.Activity
import com.ineedmypills.vibemis.VibemisLog
import com.ineedmypills.vibemis.R

object InputCaptureManager {
    @JvmStatic
    fun getInputCaptureProvider(activity: Activity): InputCaptureProvider {
        return if (AndroidNativePointerCaptureProvider.isCaptureProviderSupported()) {
            VibemisLog.info("Using Android O+ native mouse capture")
            AndroidNativePointerCaptureProvider(activity, activity.findViewById(R.id.streamContainer))
        } else if (AndroidPointerIconCaptureProvider.isCaptureProviderSupported()) {
            VibemisLog.info("Using Android N+ pointer hiding")
            AndroidPointerIconCaptureProvider(activity, activity.findViewById(R.id.streamContainer))
        } else {
            VibemisLog.info("Mouse capture not available")
            NullCaptureProvider()
        }
    }
}
