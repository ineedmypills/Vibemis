package com.ineedmypills.vibemis.utils

import android.app.Activity
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ineedmypills.vibemis.R
import com.ineedmypills.vibemis.ui.components.PhysicalCenteredOverlayBox
import com.ineedmypills.vibemis.ui.theme.VibemisExpressiveTheme

class SpinnerDialog private constructor(
    private val activity: Activity,
    private val title: String?,
    initialMessage: String?,
    private val finish: Boolean
) : Runnable {
    private var composeView: ComposeView? = null
    private var isShowing = false
    private var visibleState by mutableStateOf(false)
    private var messageState by mutableStateOf(initialMessage.orEmpty())

    fun dismiss() {
        activity.runOnUiThread(this)
    }

    fun setMessage(message: String?) {
        activity.runOnUiThread {
            messageState = message.orEmpty()
        }
    }

    private fun detachComposeViewImmediately() {
        isShowing = false
        visibleState = false
        val view = composeView
        if (view != null) {
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            content?.removeView(view)
            composeView = null
        }
    }

    private fun beginDismiss() {
        if (!isShowing) return
        isShowing = false
        visibleState = false
        if (activity.isFinishing || activity.isDestroyed) {
            detachComposeViewImmediately()
        }
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    override fun run() {
        if (activity.isFinishing || activity.isDestroyed) {
            detachComposeViewImmediately()
            return
        }

        if (!isShowing) {
            val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
            isShowing = true
            visibleState = false
            val hostView = ComposeView(activity).apply {
                setContent {
                    VibemisExpressiveTheme {
                        LaunchedEffect(Unit) {
                            visibleState = true
                        }

                        BackHandler(enabled = finish) {
                            synchronized(rundownDialogs) {
                                rundownDialogs.remove(this@SpinnerDialog)
                            }
                            detachComposeViewImmediately()
                            activity.finish()
                        }

                        PhysicalCenteredOverlayBox(
                            activity = activity,
                            visible = visibleState,
                            maxWidth = 440.dp,
                            onDismissFinished = { detachComposeViewImmediately() },
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 20.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                if (!title.isNullOrBlank()) {
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                                ) {
                                    ContainedLoadingIndicator(
                                        modifier = Modifier.size(48.dp),
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        indicatorColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = messageState,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                if (finish) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        TextButton(
                                            onClick = {
                                                synchronized(rundownDialogs) {
                                                    rundownDialogs.remove(this@SpinnerDialog)
                                                }
                                                detachComposeViewImmediately()
                                                activity.finish()
                                            }
                                        ) {
                                            Text(stringResource(R.string.cancel))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            composeView = hostView
            synchronized(rundownDialogs) {
                rundownDialogs.add(this)
                content.addView(hostView)
            }
        } else {
            synchronized(rundownDialogs) {
                rundownDialogs.remove(this)
                beginDismiss()
            }
        }
    }

    companion object {
        private val rundownDialogs = ArrayList<SpinnerDialog>()

        @JvmStatic
        fun displayDialog(activity: Activity, title: String?, message: String?, finish: Boolean): SpinnerDialog {
            val spinner = SpinnerDialog(activity, title, message, finish)
            activity.runOnUiThread(spinner)
            return spinner
        }

        @JvmStatic
        fun closeDialogs(activity: Activity) {
            synchronized(rundownDialogs) {
                val i = rundownDialogs.iterator()
                while (i.hasNext()) {
                    val dialog = i.next()
                    if (dialog.activity === activity) {
                        i.remove()
                        activity.runOnUiThread {
                            if (dialog.isShowing) {
                                dialog.detachComposeViewImmediately()
                            }
                        }
                    }
                }
            }
        }
    }
}
