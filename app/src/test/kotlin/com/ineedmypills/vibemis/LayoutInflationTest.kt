package com.ineedmypills.vibemis

import android.content.Context
import android.view.InflateException
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Modifier

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class LayoutInflationTest {
    @Test
    fun allLayoutsInflateSuccessfully() {
        val base: Context = ApplicationProvider.getApplicationContext()
        val context = ContextThemeWrapper(base, androidx.appcompat.R.style.Theme_AppCompat)
        for (layoutId in getAllLayoutResourceIds()) {
            try {
                LayoutInflater.from(context).inflate(layoutId, null)
            } catch (_: InflateException) {
                val dummyRoot = FrameLayout(context)
                LayoutInflater.from(context).inflate(layoutId, dummyRoot, true)
            }
        }
    }

    private fun getAllLayoutResourceIds(): IntArray {
        val fields = R.layout::class.java.fields
        return fields
            .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
            .map { it.getInt(null) }
            .toIntArray()
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun suppressInvalidIdLogs() {
            TestLogSuppressor.install()
        }
    }
}
