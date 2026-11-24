package com.ineedmypills.vibemis.utils

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhonelinkLock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ineedmypills.vibemis.R
import com.ineedmypills.vibemis.ui.components.PhysicalCenteredOverlayBox
import com.ineedmypills.vibemis.ui.components.VibemisShapeBadge
import com.ineedmypills.vibemis.ui.components.expressiveFocusAndPress
import com.ineedmypills.vibemis.ui.theme.VibemisExpressiveTheme

class Dialog private constructor(
    private val activity: Activity,
    private val title: String?,
    private val message: String?,
    private val technicalDetails: String?,
    private val isError: Boolean,
    private val runOnDismiss: Runnable
) : Runnable {
    private var composeView: ComposeView? = null
    private var visible by mutableStateOf(false)

    private fun detachComposeViewImmediately() {
        visible = false
        val view = composeView
        if (view != null) {
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            content?.removeView(view)
            composeView = null
        }
    }

    private fun beginDismiss() {
        visible = false
        if (activity.isFinishing || activity.isDestroyed) {
            detachComposeViewImmediately()
        }
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    override fun run() {
        if (activity.isFinishing || activity.isDestroyed) return

        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val msgText = message.orEmpty()
        val detailsText = technicalDetails?.trim().orEmpty()
        val isPinDialog = msgText.contains("PIN", ignoreCase = true) && !isError
        val extractedPin = if (isPinDialog) {
            Regex("(?<!\\d)(\\d{4})(?!\\d)").find(msgText)?.groupValues?.getOrNull(1)
        } else {
            null
        }
        val looksLikeError = isError ||
            title.orEmpty().contains("ошиб", ignoreCase = true) ||
            title.orEmpty().contains("прерван", ignoreCase = true) ||
            title.orEmpty().contains("error", ignoreCase = true) ||
            title.orEmpty().contains("fail", ignoreCase = true) ||
            title.orEmpty().contains("terminated", ignoreCase = true)

        visible = false
        val hostView = ComposeView(activity).apply {
            setContent {
                VibemisExpressiveTheme {
                    LaunchedEffect(Unit) {
                        visible = true
                    }

                    var copied by remember { mutableStateOf(false) }
                    val scrollState = rememberScrollState()

                    BackHandler {
                        synchronized(rundownDialogs) {
                            rundownDialogs.remove(this@Dialog)
                            beginDismiss()
                        }
                        runOnDismiss.run()
                    }

                    PhysicalCenteredOverlayBox(
                        activity = activity,
                        visible = visible,
                        maxWidth = 560.dp,
                        onDismissFinished = { detachComposeViewImmediately() },
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                val badgePolygon = when {
                                    extractedPin != null -> MaterialShapes.SoftBurst
                                    looksLikeError -> MaterialShapes.Diamond
                                    else -> MaterialShapes.Circle
                                }
                                VibemisShapeBadge(
                                    polygon = badgePolygon,
                                    containerColor = if (looksLikeError) {
                                        MaterialTheme.colorScheme.errorContainer
                                    } else {
                                        MaterialTheme.colorScheme.primaryContainer
                                    },
                                    contentColor = if (looksLikeError) {
                                        MaterialTheme.colorScheme.onErrorContainer
                                    } else {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    },
                                    size = 42.dp,
                                ) {
                                    Icon(
                                        imageVector = when {
                                            extractedPin != null -> Icons.Default.PhonelinkLock
                                            looksLikeError -> Icons.Default.ErrorOutline
                                            else -> Icons.Default.Info
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                if (!title.isNullOrBlank()) {
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }

                            SelectionContainer(
                                modifier = Modifier.weight(1f, fill = false)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .verticalScroll(scrollState),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    if (extractedPin != null) {
                                        Surface(
                                            shape = RoundedCornerShape(20.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 16.dp, horizontal = 16.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = extractedPin,
                                                    style = MaterialTheme.typography.displayMedium,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    letterSpacing = 8.sp
                                                )
                                            }
                                        }
                                    }
                                    if (msgText.isNotBlank()) {
                                        Text(
                                            text = msgText,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    if (detailsText.isNotBlank()) {
                                        Surface(
                                            shape = RoundedCornerShape(16.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(12.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = stringResource(R.string.dialog_details_header),
                                                        style = MaterialTheme.typography.labelLarge,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                    FilledTonalIconButton(
                                                        onClick = {
                                                            val clipText = buildString {
                                                                if (!title.isNullOrBlank()) appendLine(title)
                                                                if (msgText.isNotBlank()) appendLine(msgText)
                                                                appendLine()
                                                                append(detailsText)
                                                            }
                                                            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                                            cm?.setPrimaryClip(ClipData.newPlainText("Vibemis Diagnostics", clipText))
                                                            copied = true
                                                        }
                                                    ) {
                                                        Icon(
                                                            imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                                                            contentDescription = stringResource(R.string.dialog_copy_details),
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }
                                                Text(
                                                    text = detailsText,
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        fontFamily = FontFamily.Monospace,
                                                        lineHeight = 17.sp
                                                    ),
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val helpInteraction = remember { MutableInteractionSource() }
                                FilledTonalButton(
                                    onClick = {
                                        synchronized(rundownDialogs) {
                                            rundownDialogs.remove(this@Dialog)
                                            detachComposeViewImmediately()
                                        }
                                        runOnDismiss.run()
                                        HelpLauncher.launchTroubleshooting(activity)
                                    },
                                    interactionSource = helpInteraction,
                                    modifier = Modifier.expressiveFocusAndPress(
                                        interactionSource = helpInteraction,
                                        shape = RoundedCornerShape(20.dp),
                                    ),
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 6.dp)
                                    )
                                    Text(stringResource(R.string.help))
                                }
                                val okInteraction = remember { MutableInteractionSource() }
                                Button(
                                    onClick = {
                                        synchronized(rundownDialogs) {
                                            rundownDialogs.remove(this@Dialog)
                                            beginDismiss()
                                        }
                                        runOnDismiss.run()
                                    },
                                    interactionSource = okInteraction,
                                    modifier = Modifier.expressiveFocusAndPress(
                                        interactionSource = okInteraction,
                                        shape = RoundedCornerShape(20.dp),
                                    ),
                                ) {
                                    Text(stringResource(android.R.string.ok))
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
    }

    companion object {
        private val rundownDialogs = ArrayList<Dialog>()

        @JvmStatic
        fun closeDialogs() {
            synchronized(rundownDialogs) {
                for (d in rundownDialogs) {
                    d.activity.runOnUiThread {
                        d.detachComposeViewImmediately()
                    }
                }
                rundownDialogs.clear()
            }
        }

        @JvmStatic
        fun displayDialog(activity: Activity, title: String?, message: String?, endAfterDismiss: Boolean) {
            activity.runOnUiThread(
                Dialog(
                    activity = activity,
                    title = title,
                    message = message,
                    technicalDetails = null,
                    isError = false,
                    runOnDismiss = Runnable {
                        if (endAfterDismiss) {
                            activity.finish()
                        }
                    }
                )
            )
        }

        @JvmStatic
        fun displayDialog(activity: Activity, title: String?, message: String?, runOnDismiss: Runnable) {
            activity.runOnUiThread(
                Dialog(
                    activity = activity,
                    title = title,
                    message = message,
                    technicalDetails = null,
                    isError = false,
                    runOnDismiss = runOnDismiss
                )
            )
        }

        @JvmStatic
        fun displayDetailedErrorDialog(
            activity: Activity,
            title: String?,
            summary: String?,
            technicalDetails: String?,
            endAfterDismiss: Boolean
        ) {
            activity.runOnUiThread(
                Dialog(
                    activity = activity,
                    title = title,
                    message = summary,
                    technicalDetails = technicalDetails,
                    isError = true,
                    runOnDismiss = Runnable {
                        if (endAfterDismiss) {
                            activity.finish()
                        }
                    }
                )
            )
        }
    }
}
