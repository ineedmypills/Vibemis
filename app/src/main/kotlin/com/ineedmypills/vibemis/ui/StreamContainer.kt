package com.ineedmypills.vibemis.ui

import android.content.Context
import android.graphics.PixelFormat
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import com.ineedmypills.vibemis.Game
import com.ineedmypills.vibemis.preferences.PreferenceConfiguration

class StreamContainer(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs), SurfaceHolder.Callback {

    interface InputCallbacks {
        fun handleKeyUp(event: KeyEvent): Boolean
        fun handleKeyDown(event: KeyEvent): Boolean
        fun handleCommitText(text: CharSequence): Boolean
        fun handleDeleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean
        fun handleFocusChange(hasWindowFocus: Boolean): Boolean
    }

    private var game: Game? = null
    var surfaceView: SurfaceView? = null
        private set
    var surface: Surface? = null
        private set
    private var onSurfaceAvailable: Runnable? = null
    private var mInputCallbacks: InputCallbacks? = null
    private var commitTextEnabled = false

    private var desiredAspectRatio = 0.0
    private var fillDisplay = false
    private var isSurfaceReady = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun init(game: Game, prefConfig: PreferenceConfiguration?) {
        if (this.game != null) return

        this.game = game
        isSurfaceReady = false
        surface = null

        val childParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        val sv = SurfaceView(context)
        surfaceView = sv
        addView(sv, childParams)

        sv.holder.addCallback(this)
        if (sv.holder.surface != null && sv.holder.surface.isValid) {
            surfaceChanged(sv.holder, PixelFormat.RGBA_8888, sv.width, sv.height)
        }
    }

    fun setDesiredAspectRatio(aspectRatio: Double) {
        this.desiredAspectRatio = aspectRatio
        requestLayout()
    }

    fun setFillDisplay(fillDisplay: Boolean) {
        this.fillDisplay = fillDisplay
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (desiredAspectRatio == 0.0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val measuredHeight: Int
        val measuredWidth: Int

        if (fillDisplay) {
            if (widthSize < heightSize * desiredAspectRatio) {
                measuredHeight = heightSize
                measuredWidth = (heightSize * desiredAspectRatio).toInt()
            } else {
                measuredWidth = widthSize
                measuredHeight = (widthSize / desiredAspectRatio).toInt()
            }
        } else {
            if (widthSize > heightSize * desiredAspectRatio) {
                measuredHeight = heightSize
                measuredWidth = (measuredHeight * desiredAspectRatio).toInt()
            } else {
                measuredWidth = widthSize
                measuredHeight = (measuredWidth / desiredAspectRatio).toInt()
            }
        }

        setMeasuredDimension(measuredWidth, measuredHeight)
        val childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY)
        val childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
        measureChildren(childWidthMeasureSpec, childHeightMeasureSpec)
    }

    fun setInputCallbacks(callbacks: InputCallbacks?) {
        this.mInputCallbacks = callbacks
    }

    fun setCommitTextEnabled(enabled: Boolean) {
        this.commitTextEnabled = enabled
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        val callbacks = mInputCallbacks
        if (callbacks != null) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (callbacks.handleKeyDown(event)) return true
            } else if (event.action == KeyEvent.ACTION_UP) {
                if (callbacks.handleKeyUp(event)) return true
            }
        }
        return super.onKeyPreIme(keyCode, event)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        mInputCallbacks?.handleFocusChange(hasWindowFocus)
    }

    override fun onCheckIsTextEditor(): Boolean {
        return commitTextEnabled || super.onCheckIsTextEditor()
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!commitTextEnabled) {
            return super.onCreateInputConnection(outAttrs)
        }
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                return mInputCallbacks?.handleCommitText(text) == true || super.commitText(text, newCursorPosition)
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                return mInputCallbacks?.handleDeleteSurroundingText(beforeLength, afterLength) == true ||
                    super.deleteSurroundingText(beforeLength, afterLength)
            }
        }
    }

    fun setOnSurfaceAvailable(callback: Runnable?) {
        this.onSurfaceAvailable = callback
        if (isSurfaceReady && onSurfaceAvailable != null) {
            onSurfaceAvailable?.run()
        }
    }

    private fun notifySurfaceReady() {
        isSurfaceReady = true
        onSurfaceAvailable?.run()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        game?.surfaceCreated(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (width > 0 && height > 0) {
            surface = holder.surface
            notifySurfaceReady()
        }
        game?.surfaceChanged(holder, format, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isSurfaceReady = false
        surface = null
        game?.surfaceDestroyed(holder)
    }

    fun onDestroy() {}
}
