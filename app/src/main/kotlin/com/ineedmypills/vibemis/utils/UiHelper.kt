package com.ineedmypills.vibemis.utils

import android.app.Activity
import android.app.GameManager
import android.app.GameState
import android.app.LocaleManager
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.text.Html
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ineedmypills.vibemis.VibemisLog
import com.ineedmypills.vibemis.R
import com.ineedmypills.vibemis.nvstream.http.ComputerDetails
import com.ineedmypills.vibemis.preferences.PreferenceConfiguration
import com.ineedmypills.vibemis.ui.components.expressiveFocusAndPress
import com.ineedmypills.vibemis.ui.theme.VibemisExpressiveTheme
import java.util.Locale

object UiHelper {
    private const val TV_VERTICAL_PADDING_DP = 15
    private const val TV_HORIZONTAL_PADDING_DP = 15

    private fun setGameModeStatus(context: Context, streaming: Boolean, interruptible: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val gameManager = context.getSystemService(GameManager::class.java)
            if (gameManager == null) {
                VibemisLog.warning("GameManager is null, maybe your system does not support it?")
                return
            }

            if (streaming) {
                gameManager.setGameState(
                    GameState(
                        false,
                        if (interruptible) GameState.MODE_GAMEPLAY_INTERRUPTIBLE else GameState.MODE_GAMEPLAY_UNINTERRUPTIBLE
                    )
                )
            } else {
                gameManager.setGameState(GameState(false, GameState.MODE_NONE))
            }
        }
    }

    @JvmStatic
    fun notifyStreamConnecting(context: Context) {
        setGameModeStatus(context, streaming = true, interruptible = true)
    }

    @JvmStatic
    fun notifyStreamConnected(context: Context) {
        setGameModeStatus(context, streaming = true, interruptible = false)
    }

    @JvmStatic
    fun notifyStreamEnteringPiP(context: Context) {
        setGameModeStatus(context, streaming = true, interruptible = true)
    }

    @JvmStatic
    fun notifyStreamExitingPiP(context: Context) {
        setGameModeStatus(context, streaming = true, interruptible = false)
    }

    @JvmStatic
    fun notifyStreamEnded(context: Context) {
        setGameModeStatus(context, streaming = false, interruptible = false)
    }

    @JvmStatic
    fun setLocale(activity: Activity) {
        val locale = PreferenceConfiguration.readPreferences(activity).language
        val desiredLocales = if (locale == PreferenceConfiguration.DEFAULT_LANGUAGE) {
            androidx.core.os.LocaleListCompat.getEmptyLocaleList()
        } else {
            val tag = locale.replace('_', '-')
            Locale.forLanguageTag(tag).let { Locale.setDefault(it) }
            androidx.core.os.LocaleListCompat.forLanguageTags(tag)
        }
        val currentLocales = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        val desiredTags = desiredLocales.toLanguageTags()
        val currentTags = currentLocales.toLanguageTags()
        val alreadySet = if (desiredLocales.isEmpty) {
            currentLocales.isEmpty
        } else {
            !currentLocales.isEmpty && currentTags.equals(desiredTags, ignoreCase = true)
        }
        if (!alreadySet) {
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(desiredLocales)
        }
    }

    @JvmStatic
    fun applyStatusBarPadding(view: View) {
        view.setOnApplyWindowInsetsListener { v, windowInsets ->
            val bottomInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                windowInsets.getInsets(android.view.WindowInsets.Type.tappableElement()).bottom
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                windowInsets.tappableElementInsets.bottom
            } else {
                @Suppress("DEPRECATION")
                windowInsets.systemWindowInsetBottom
            }
            v.setPadding(
                v.paddingLeft,
                v.paddingTop,
                v.paddingRight,
                bottomInset
            )
            windowInsets
        }
        view.requestApplyInsets()
    }

    @JvmStatic
    @Suppress("DEPRECATION")
    fun notifyNewRootView(activity: Activity) {
        val rootView = activity.findViewById<View>(android.R.id.content)
        val modeMgr = activity.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager

        setGameModeStatus(activity, streaming = false, interruptible = false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            activity.window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        if (modeMgr.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            val scale = activity.resources.displayMetrics.density
            val verticalPaddingPixels = (TV_VERTICAL_PADDING_DP * scale + 0.5f).toInt()
            val horizontalPaddingPixels = (TV_HORIZONTAL_PADDING_DP * scale + 0.5f).toInt()

            rootView.setPadding(
                horizontalPaddingPixels,
                verticalPaddingPixels,
                horizontalPaddingPixels,
                verticalPaddingPixels
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.findViewById<View>(android.R.id.content).setOnApplyWindowInsetsListener { view, windowInsets ->
                val tappableInsets = windowInsets.tappableElementInsets
                view.setPadding(
                    tappableInsets.left,
                    tappableInsets.top,
                    tappableInsets.right,
                    tappableInsets.bottom
                )

                if (tappableInsets.bottom != 0) {
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
                } else {
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
                }

                windowInsets
            }

            activity.window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    @JvmStatic
    fun showDecoderCrashDialog(activity: Activity) {
        val prefs = activity.getSharedPreferences("DecoderTombstone", 0)
        val crashCount = prefs.getInt("CrashCount", 0)
        val lastNotifiedCrashCount = prefs.getInt("LastNotifiedCrashCount", 0)

        if (crashCount != 0 && crashCount != lastNotifiedCrashCount) {
            if (crashCount % 3 == 0) {
                PreferenceConfiguration.resetStreamingSettings(activity)
                Dialog.displayDialog(
                    activity,
                    activity.resources.getString(R.string.title_decoding_reset),
                    activity.resources.getString(R.string.message_decoding_reset),
                    Runnable {
                        prefs.edit().putInt("LastNotifiedCrashCount", crashCount).apply()
                    }
                )
            } else {
                Dialog.displayDialog(
                    activity,
                    activity.resources.getString(R.string.title_decoding_error),
                    activity.resources.getString(R.string.message_decoding_error),
                    Runnable {
                        prefs.edit().putInt("LastNotifiedCrashCount", crashCount).apply()
                    }
                )
            }
        }
    }

    @JvmStatic
    fun displayConfirmationDialog(
        parent: Activity,
        title: String?,
        message: String,
        btnYesText: String?,
        btnNoText: String?,
        onYes: Runnable?,
        onNo: Runnable?
    ) {
        parent.runOnUiThread {
            if (parent.isFinishing) return@runOnUiThread
            val rootView = parent.findViewById<ViewGroup>(android.R.id.content) ?: return@runOnUiThread
            val cleanMessage = Html.fromHtml(message, Html.FROM_HTML_MODE_COMPACT).toString().trim()
            val composeView = ComposeView(parent)

            fun dismiss() {
                rootView.removeView(composeView)
            }

            composeView.setContent {
                VibemisExpressiveTheme {
                    AlertDialog(
                        onDismissRequest = {
                            dismiss()
                            onNo?.run()
                        },
                        shape = MaterialTheme.shapes.large,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        title = if (!title.isNullOrBlank()) {
                            {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else null,
                        text = {
                            Text(
                                text = cleanMessage,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        confirmButton = {
                            if (btnYesText != null) {
                                val yesInteraction = remember { MutableInteractionSource() }
                                Button(
                                    onClick = {
                                        dismiss()
                                        onYes?.run()
                                    },
                                    interactionSource = yesInteraction,
                                    shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier.expressiveFocusAndPress(
                                        interactionSource = yesInteraction,
                                        shape = MaterialTheme.shapes.medium,
                                    ),
                                ) {
                                    Text(text = btnYesText, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        },
                        dismissButton = if (btnNoText != null) {
                            {
                                val noInteraction = remember { MutableInteractionSource() }
                                FilledTonalButton(
                                    onClick = {
                                        dismiss()
                                        onNo?.run()
                                    },
                                    interactionSource = noInteraction,
                                    shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier.expressiveFocusAndPress(
                                        interactionSource = noInteraction,
                                        shape = MaterialTheme.shapes.medium,
                                    ),
                                ) {
                                    Text(text = btnNoText)
                                }
                            }
                        } else null
                    )
                }
            }

            rootView.addView(composeView)
        }
    }

    @JvmStatic
    fun displayVdisplayConfirmationDialog(
        parent: Activity,
        computer: ComputerDetails,
        onYes: Runnable?,
        onNo: Runnable?
    ) {
        val message = if (computer.vDisplaySupported) {
            parent.resources.getString(R.string.vdisplay_not_ready)
        } else {
            parent.resources.getString(R.string.vdisplay_not_supported)
        }
        displayConfirmationDialog(
            parent,
            null,
            message,
            parent.resources.getString(R.string.proceed),
            parent.resources.getString(R.string.cancel),
            onYes,
            onNo
        )
    }

    @JvmStatic
    fun displayQuitConfirmationDialog(parent: Activity, onYes: Runnable?, onNo: Runnable?) {
        displayConfirmationDialog(
            parent,
            null,
            parent.resources.getString(R.string.applist_quit_confirmation),
            parent.resources.getString(R.string.yes),
            parent.resources.getString(R.string.no),
            onYes,
            onNo
        )
    }

    @JvmStatic
    fun displayDeletePcConfirmationDialog(
        parent: Activity,
        computer: ComputerDetails,
        onYes: Runnable?,
        onNo: Runnable?
    ) {
        displayConfirmationDialog(
            parent,
            computer.name,
            parent.resources.getString(R.string.delete_pc_msg),
            parent.resources.getString(R.string.yes),
            parent.resources.getString(R.string.no),
            onYes,
            onNo
        )
    }

    @JvmStatic
    fun dpToPx(context: Context, dp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics)
}
