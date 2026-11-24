package com.ineedmypills.vibemis.ui

import android.content.Context
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.any
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StreamViewCommitTextTest {
    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun commitText_isForwarded_whenEnabled() {
        val view = StreamView(ctx)
        val cb = Mockito.mock(StreamView.InputCallbacks::class.java)
        view.setInputCallbacks(cb)
        view.setCommitTextEnabled(true)

        val ei = EditorInfo()
        val ic = view.onCreateInputConnection(ei)
        assertNotNull("InputConnection should be created when commitText is enabled", ic)

        ic!!.commitText("hello", 1)

        verify(cb, times(1)).handleCommitText("hello")
    }

    @Test
    fun commitText_notForwarded_whenDisabled() {
        val view = StreamView(ctx)
        val cb = Mockito.mock(StreamView.InputCallbacks::class.java)
        view.setInputCallbacks(cb)
        view.setCommitTextEnabled(false)

        val ei = EditorInfo()
        val ic = view.onCreateInputConnection(ei)
        ic?.commitText("hello", 1)

        verify(cb, never()).handleCommitText(Mockito.anyString() ?: "")
    }
}
