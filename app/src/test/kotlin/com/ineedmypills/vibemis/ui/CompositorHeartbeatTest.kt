package com.ineedmypills.vibemis.ui

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class CompositorHeartbeatTest {
    @Test
    fun startAndStop_updatesPixelIndependently() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val view = View(context)
        view.visibility = View.GONE
        val heartbeat = CompositorHeartbeat(view)

        heartbeat.start()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(View.VISIBLE, view.visibility)
        assertEquals(CompositorHeartbeat.COLOR_PHASE_ONE, backgroundColor(view))

        Shadows.shadowOf(Looper.getMainLooper())
            .idleFor(CompositorHeartbeat.INTERVAL_MS, TimeUnit.MILLISECONDS)
        assertEquals(CompositorHeartbeat.COLOR_PHASE_TWO, backgroundColor(view))

        heartbeat.stop()
        assertEquals(View.GONE, view.visibility)

        val stoppedColor = backgroundColor(view)
        Shadows.shadowOf(Looper.getMainLooper())
            .idleFor(CompositorHeartbeat.INTERVAL_MS * 2, TimeUnit.MILLISECONDS)
        assertEquals(stoppedColor, backgroundColor(view))
    }

    private fun backgroundColor(view: View): Int {
        return (view.background as ColorDrawable).color
    }
}
