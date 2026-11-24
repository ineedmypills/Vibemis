package com.ineedmypills.vibemis.shadows

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@Implements(className = "com.android.internal.policy.BackdropFrameRenderer", isInAndroidSdk = false)
class ShadowBackdropFrameRenderer {
    @Implementation
    protected fun run() {
        // No-op to avoid calling into Choreographer which crashes under Robolectric
    }
}
