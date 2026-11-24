package com.ineedmypills.vibemis

import android.app.ActivityManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ineedmypills.vibemis.preferences.PreferenceConfiguration
import com.ineedmypills.vibemis.binding.PlatformBinding
import com.ineedmypills.vibemis.binding.crypto.AndroidCryptoProvider
import com.ineedmypills.vibemis.computers.ComputerManagerListener
import com.ineedmypills.vibemis.computers.ComputerManagerService
import com.ineedmypills.vibemis.grid.assets.DiskAssetLoader
import com.ineedmypills.vibemis.nvstream.http.ComputerDetails
import com.ineedmypills.vibemis.nvstream.http.NvApp
import com.ineedmypills.vibemis.nvstream.http.NvHTTP
import com.ineedmypills.vibemis.nvstream.http.PairingManager
import com.ineedmypills.vibemis.nvstream.http.PairingManager.PairState
import com.ineedmypills.vibemis.nvstream.wol.WakeOnLanSender
import com.ineedmypills.vibemis.preferences.AddComputerManually
import com.ineedmypills.vibemis.preferences.GlPreferences
import com.ineedmypills.vibemis.preferences.StreamSettings
import com.ineedmypills.vibemis.profiles.ProfilesManager
import com.ineedmypills.vibemis.ui.components.VibemisContainedLoadingView
import com.ineedmypills.vibemis.ui.components.VibemisShapeBadge
import com.ineedmypills.vibemis.ui.components.enableVibemisEdgeToEdge
import com.ineedmypills.vibemis.ui.components.expressiveFocusAndPress
import com.ineedmypills.vibemis.ui.components.rememberVibemisAdaptiveLayout
import com.ineedmypills.vibemis.ui.theme.VibemisExpressiveTheme
import com.ineedmypills.vibemis.ui.wizard.SetupWizardActivity
import com.ineedmypills.vibemis.utils.Dialog
import com.ineedmypills.vibemis.utils.HelpLauncher
import com.ineedmypills.vibemis.utils.ServerHelper
import com.ineedmypills.vibemis.utils.ShortcutHelper
import com.ineedmypills.vibemis.utils.UiHelper
import org.xmlpull.v1.XmlPullParserException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.UnknownHostException
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class PcView : AppCompatActivity() {
    private var shortcutHelper: ShortcutHelper? = null
    private var managerBinder: ComputerManagerService.ComputerManagerBinder? = null
    private var freezeUpdates = false
    private var runningPolling = false
    private var inForeground = false
    private var completeOnCreateCalled = false
    private var pendingPairingAddress: ComputerDetails.AddressTuple? = null
    private var pendingPairingPin: String? = null
    private var pendingPairingPassphrase: String? = null

    private val computersState = mutableStateListOf<ComputerObject>()
    private var activeProfileNameState by mutableStateOf("")
    private var selectedComputerForMenu by mutableStateOf<ComputerObject?>(null)
    private var selectedComputerForOtp by mutableStateOf<ComputerDetails?>(null)
    private var selectedComputerForDetails by mutableStateOf<ComputerDetails?>(null)
    private var pendingExternalPairPrompt by mutableStateOf<Triple<ComputerDetails, String?, String?>?>(null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            val localBinder = binder as ComputerManagerService.ComputerManagerBinder
            Thread {
                localBinder.waitForReady()
                managerBinder = localBinder
                startComputerUpdates()
                AndroidCryptoProvider(this@PcView).clientCertificate
            }.start()
        }

        override fun onServiceDisconnected(className: ComponentName) {
            managerBinder = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableVibemisEdgeToEdge()
        super.onCreate(savedInstanceState)
        inForeground = true

        val glPrefs = GlPreferences.readPreferences(this)
        if (glPrefs.savedFingerprint != Build.FINGERPRINT || glPrefs.glRenderer.isEmpty()) {
            val surfaceView = GLSurfaceView(this)
            surfaceView.setRenderer(object : GLSurfaceView.Renderer {
                override fun onSurfaceCreated(gl10: GL10, eglConfig: EGLConfig) {
                    glPrefs.glRenderer = gl10.glGetString(GL10.GL_RENDERER) ?: ""
                    glPrefs.savedFingerprint = Build.FINGERPRINT
                    glPrefs.writePreferences()
                    VibemisLog.info("Fetched GL Renderer: " + glPrefs.glRenderer)
                    runOnUiThread { completeOnCreate() }
                }

                override fun onSurfaceChanged(gl10: GL10, w: Int, h: Int) {}
                override fun onDrawFrame(gl10: GL10) {}
            })
            setContentView(surfaceView)
        } else {
            VibemisLog.info("Cached GL Renderer: " + glPrefs.glRenderer)
            completeOnCreate()
        }

        val hostname = intent.getStringExtra("hostname")
        val port = intent.getIntExtra("port", NvHTTP.DEFAULT_HTTP_PORT)
        pendingPairingPin = intent.getStringExtra("pin")
        pendingPairingPassphrase = intent.getStringExtra("passphrase")

        if (hostname != null && pendingPairingPin != null && pendingPairingPassphrase != null) {
            pendingPairingAddress = ComputerDetails.AddressTuple(hostname, port)
        } else {
            pendingPairingPin = null
            pendingPairingPassphrase = null
        }
    }

    private fun completeOnCreate() {
        completeOnCreateCalled = true
        shortcutHelper = ShortcutHelper(this)
        UiHelper.setLocale(this)

        bindService(
            Intent(this, ComputerManagerService::class.java),
            serviceConnection,
            Service.BIND_AUTO_CREATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false)
        }

        refreshProfileButton()
        setupComposeContent()
    }

    private fun setupComposeContent() {
        val composeView = ComposeView(this).apply {
            setContent {
                VibemisExpressiveTheme {
                    PcViewScreen()
                }
            }
        }
        setContentView(composeView)
    }

    fun openProfiles() {
        startActivity(Intent(this, ProfilesActivity::class.java))
    }

    private fun refreshProfileButton() {
        activeProfileNameState = ProfilesManager.getInstance().activeName
    }

    private fun startComputerUpdates() {
        val binder = managerBinder
        if (binder != null && !runningPolling && inForeground) {
            freezeUpdates = false
            binder.startPolling(ComputerManagerListener { details ->
                if (!freezeUpdates) {
                    runOnUiThread { updateComputer(details) }

                    if (details.pairState == PairState.PAIRED) {
                        shortcutHelper?.createAppViewShortcutForOnlineHost(details)
                    }
                    val targetAddr = pendingPairingAddress
                    if (targetAddr != null &&
                        details.state == ComputerDetails.State.ONLINE &&
                        targetAddr == details.activeAddress
                    ) {
                        val pin = pendingPairingPin
                        val pass = pendingPairingPassphrase
                        pendingPairingAddress = null
                        pendingPairingPin = null
                        pendingPairingPassphrase = null
                        runOnUiThread {
                            pendingExternalPairPrompt = Triple(details, pin, pass)
                        }
                    }
                }
            })
            runningPolling = true
        }
    }

    private fun stopComputerUpdates(wait: Boolean) {
        val binder = managerBinder ?: return
        if (!runningPolling) return
        freezeUpdates = true
        binder.stopPolling()
        if (wait) {
            binder.waitForPollingStopped()
        }
        runningPolling = false
    }

    override fun onDestroy() {
        super.onDestroy()
        if (managerBinder != null) {
            unbindService(serviceConnection)
        }
    }

    override fun onResume() {
        super.onResume()
        UiHelper.showDecoderCrashDialog(this)
        refreshProfileButton()
        inForeground = true
        startComputerUpdates()
    }

    override fun onPause() {
        super.onPause()
        inForeground = false
        stopComputerUpdates(false)
    }

    override fun onStop() {
        super.onStop()
        Dialog.closeDialogs()
    }

    private fun updateComputer(details: ComputerDetails) {
        val index = computersState.indexOfFirst { it.details.uuid == details.uuid }
        if (index >= 0) {
            computersState[index] = ComputerObject(details)
        } else {
            computersState.add(ComputerObject(details))
        }
        computersState.sortWith { a, b ->
            a.details.name.lowercase().compareTo(b.details.name.lowercase())
        }
    }

    private fun removeComputer(details: ComputerDetails) {
        managerBinder?.removeComputer(details)
        DiskAssetLoader(this).deleteAssetsForComputer(details.uuid)
        getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
            .edit()
            .remove(details.uuid)
            .apply()

        shortcutHelper?.disableComputerShortcut(
            details,
            resources.getString(R.string.scut_deleted_pc)
        )
        computersState.removeAll { it.details.uuid == details.uuid }
    }

    private fun doPair(computer: ComputerDetails, otp: String?, passphrase: String?) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Dialog.displayDialog(this, getString(R.string.conn_error_title), getString(R.string.pair_pc_offline), false)
            return
        }
        val binder = managerBinder
        if (binder == null) {
            Dialog.displayDialog(this, getString(R.string.conn_error_title), getString(R.string.error_manager_not_running), false)
            return
        }

        Thread {
            var message: String? = null
            var techDetails: String? = null
            var success = false
            try {
                stopComputerUpdates(true)
                val addr = ServerHelper.getCurrentAddressFromComputer(computer)
                val httpConn = NvHTTP(
                    addr,
                    computer.httpsPort,
                    binder.uniqueId,
                    computer.serverCert,
                    PlatformBinding.getCryptoProvider(this@PcView)
                )
                if (httpConn.pairState == PairState.PAIRED) {
                    success = true
                } else {
                    val pinStr = otp ?: PairingManager.generatePinString()
                    if (passphrase == null) {
                        Dialog.displayDialog(
                            this@PcView,
                            resources.getString(R.string.pair_pairing_title),
                            "${resources.getString(R.string.pair_pairing_msg)} $pinStr\n\n${resources.getString(R.string.pair_pairing_help)}",
                            false
                        )
                    } else {
                        Dialog.displayDialog(
                            this@PcView,
                            resources.getString(R.string.pair_pairing_title),
                            "${resources.getString(R.string.pair_otp_pairing_msg)}\n\n${resources.getString(R.string.pair_otp_pairing_help)}",
                            false
                        )
                    }

                    val pm = httpConn.pairingManager
                    val pairResult = pm.pair(httpConn.getServerInfo(true), pinStr, passphrase)
                    when (pairResult) {
                        PairState.PIN_WRONG -> message = resources.getString(R.string.pair_incorrect_pin)
                        PairState.FAILED -> message = if (computer.runningGameId != 0) {
                            resources.getString(R.string.pair_pc_ingame)
                        } else {
                            resources.getString(R.string.pair_fail)
                        }
                        PairState.ALREADY_IN_PROGRESS -> message = resources.getString(R.string.pair_already_in_progress)
                        PairState.PAIRED -> {
                            success = true
                            binder.getComputer(computer.uuid)?.serverCert = pm.pairedCert
                            binder.invalidateStateForComputer(computer.uuid)
                        }
                        else -> {}
                    }
                    if (!success) {
                        techDetails = "PC: ${computer.name}\nAddress: ${addr.address}:${addr.port} (HTTPS ${computer.httpsPort})\nPairState: $pairResult"
                    }
                }
            } catch (e: UnknownHostException) {
                message = resources.getString(R.string.error_unknown_host)
                techDetails = "UnknownHostException: ${e.message}"
            } catch (e: FileNotFoundException) {
                message = resources.getString(R.string.error_404)
                techDetails = "FileNotFoundException: ${e.message}"
            } catch (e: XmlPullParserException) {
                message = e.message ?: resources.getString(R.string.pair_fail)
                techDetails = "XmlPullParserException: ${e.message}"
            } catch (e: IOException) {
                message = e.message ?: resources.getString(R.string.pair_fail)
                techDetails = "IOException: ${e.message}"
            }

            Dialog.closeDialogs()
            runOnUiThread {
                if (message != null) {
                    Dialog.displayDetailedErrorDialog(
                        this@PcView,
                        resources.getString(R.string.pair_pairing_title),
                        message,
                        techDetails,
                        false
                    )
                }
                if (success) {
                    doAppList(computer, newlyPaired = true, showHiddenGames = false)
                } else {
                    startComputerUpdates()
                }
            }
        }.start()
    }

    private fun doWakeOnLan(computer: ComputerDetails) {
        if (computer.state == ComputerDetails.State.ONLINE) {
            return
        }
        if (computer.macAddress == null) {
            Dialog.displayDialog(this, getString(R.string.conn_error_title), getString(R.string.wol_no_mac), false)
            return
        }
        Thread {
            val message = try {
                WakeOnLanSender.sendWolPacket(computer)
                resources.getString(R.string.wol_waking_msg)
            } catch (e: IOException) {
                "${resources.getString(R.string.wol_fail)}\n\n${e.message.orEmpty()}"
            }
            Dialog.displayDialog(this@PcView, computer.name, message, false)
        }.start()
    }

    private fun doSleepHost(computer: ComputerDetails) {
        val binder = managerBinder ?: return
        Thread {
            var errDetail: String? = null
            val accepted = try {
                val httpConn = NvHTTP(
                    ServerHelper.getCurrentAddressFromComputer(computer),
                    computer.httpsPort,
                    binder.uniqueId,
                    computer.serverCert,
                    PlatformBinding.getCryptoProvider(this@PcView)
                )
                httpConn.requestHostSleep()
            } catch (e: Exception) {
                errDetail = "${e.javaClass.simpleName}: ${e.message}"
                false
            }
            if (!accepted) {
                Dialog.displayDetailedErrorDialog(
                    this@PcView,
                    computer.name,
                    getString(R.string.sleep_host_failed),
                    errDetail,
                    false
                )
            }
        }.start()
    }

    private fun doUnpair(computer: ComputerDetails) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Dialog.displayDialog(this, getString(R.string.conn_error_title), getString(R.string.error_pc_offline), false)
            return
        }
        val binder = managerBinder ?: return
        Thread {
            var failed = false
            val message = try {
                val httpConn = NvHTTP(
                    ServerHelper.getCurrentAddressFromComputer(computer),
                    computer.httpsPort,
                    binder.uniqueId,
                    computer.serverCert,
                    PlatformBinding.getCryptoProvider(this@PcView)
                )
                if (httpConn.pairState == PairState.PAIRED) {
                    httpConn.unpair()
                    if (httpConn.pairState == PairState.NOT_PAIRED) {
                        null
                    } else {
                        failed = true
                        resources.getString(R.string.unpair_fail)
                    }
                } else {
                    failed = true
                    resources.getString(R.string.unpair_error)
                }
            } catch (e: Exception) {
                failed = true
                e.message ?: resources.getString(R.string.unpair_fail)
            }
            if (failed && message != null) {
                Dialog.displayDialog(this@PcView, computer.name, message, false)
            }
        }.start()
    }

    private fun doAppList(computer: ComputerDetails, newlyPaired: Boolean, showHiddenGames: Boolean) {
        if (computer.state == ComputerDetails.State.OFFLINE) {
            Dialog.displayDialog(this, getString(R.string.conn_error_title), getString(R.string.error_pc_offline), false)
            return
        }
        if (managerBinder == null) {
            Dialog.displayDialog(this, getString(R.string.conn_error_title), getString(R.string.error_manager_not_running), false)
            return
        }
        val i = Intent(this, AppView::class.java).apply {
            putExtra(AppView.NAME_EXTRA, computer.name)
            putExtra(AppView.UUID_EXTRA, computer.uuid)
            putExtra(AppView.NEW_PAIR_EXTRA, newlyPaired)
            putExtra(AppView.SHOW_HIDDEN_APPS_EXTRA, showHiddenGames)
        }
        startActivity(i)
    }

    @OptIn(
        ExperimentalMaterial3Api::class,
        ExperimentalMaterial3ExpressiveApi::class,
        ExperimentalFoundationApi::class,
        ExperimentalLayoutApi::class,
    )
    @Composable
    private fun PcViewScreen() {
        val topAppBarScrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        val isFireTv = remember { packageManager.hasSystemFeature("amazon.hardware.fire_tv") }
        val adaptive = rememberVibemisAdaptiveLayout()
        val onlineCount = remember(computersState.toList()) {
            computersState.count { it.details.state == ComputerDetails.State.ONLINE }
        }
        val gridMinCellSize = when {
            adaptive.isExpandedWidth -> 300.dp
            adaptive.isLandscape || adaptive.isMediumWidth -> 264.dp
            else -> 280.dp
        }

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (adaptive.isCompactHeight) {
                        Modifier
                    } else {
                        Modifier.nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                    }
                ),
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                val subtitleText = if (computersState.isEmpty()) {
                    stringResource(R.string.pcview_subtitle_empty)
                } else {
                    pluralStringResource(R.plurals.pcview_subtitle_hosts, computersState.size, computersState.size, onlineCount)
                }
                if (adaptive.isCompactHeight) {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = stringResource(R.string.app_label),
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = subtitleText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        actions = {
                            if (!isFireTv) {
                                IconButton(onClick = { HelpLauncher.launchSetupGuide(this@PcView) }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.HelpOutline,
                                        contentDescription = stringResource(R.string.help)
                                    )
                                }
                            }
                        }
                    )
                } else {
                    LargeFlexibleTopAppBar(
                        title = {
                            Text(
                                text = stringResource(R.string.app_label),
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        subtitle = {
                            Text(
                                text = subtitleText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        actions = {
                            if (!isFireTv) {
                                IconButton(onClick = { HelpLauncher.launchSetupGuide(this@PcView) }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.HelpOutline,
                                        contentDescription = stringResource(R.string.help)
                                    )
                                }
                            }
                        },
                        scrollBehavior = topAppBarScrollBehavior
                    )
                }
            },
            bottomBar = {
                BottomAppBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    actions = {
                        if (activeProfileNameState.isNotEmpty()) {
                            AssistChip(
                                onClick = { openProfiles() },
                                shape = AssistChipDefaults.shape,
                                label = {
                                    Text(
                                        text = activeProfileNameState,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Person,
                                        contentDescription = stringResource(R.string.profiles_title),
                                        modifier = Modifier.size(AssistChipDefaults.IconSize),
                                    )
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                ),
                                border = null,
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .padding(start = 4.dp, end = 4.dp)
                                    .widthIn(max = 220.dp),
                            )
                        } else {
                            val profileInteraction = remember { MutableInteractionSource() }
                            IconButton(
                                onClick = { openProfiles() },
                                interactionSource = profileInteraction,
                                modifier = Modifier.expressiveFocusAndPress(
                                    interactionSource = profileInteraction,
                                    shape = CircleShape,
                                ),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Person,
                                    contentDescription = stringResource(R.string.profiles_title),
                                )
                            }
                        }
                        val wizardInteraction = remember { MutableInteractionSource() }
                        IconButton(
                            onClick = {
                                startActivity(Intent(this@PcView, SetupWizardActivity::class.java))
                            },
                            interactionSource = wizardInteraction,
                            modifier = Modifier.expressiveFocusAndPress(
                                interactionSource = wizardInteraction,
                                shape = CircleShape,
                            ),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Tune,
                                contentDescription = stringResource(R.string.wizard_title),
                            )
                        }
                        val settingsInteraction = remember { MutableInteractionSource() }
                        IconButton(
                            onClick = {
                                startActivity(Intent(this@PcView, StreamSettings::class.java))
                            },
                            interactionSource = settingsInteraction,
                            modifier = Modifier.expressiveFocusAndPress(
                                interactionSource = settingsInteraction,
                                shape = CircleShape,
                            ),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = stringResource(R.string.title_settings),
                            )
                        }
                    },
                    floatingActionButton = {
                        val addInteraction = remember { MutableInteractionSource() }
                        FloatingActionButton(
                            onClick = {
                                startActivity(Intent(this@PcView, AddComputerManually::class.java))
                            },
                            interactionSource = addInteraction,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            elevation = FloatingActionButtonDefaults.bottomAppBarFabElevation(),
                            modifier = Modifier.expressiveFocusAndPress(
                                interactionSource = addInteraction,
                                shape = FloatingActionButtonDefaults.shape,
                            ),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = stringResource(R.string.title_add_pc),
                            )
                        }
                    }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (computersState.isEmpty()) {
                    EmptyDiscoveryState(
                        modifier = Modifier.fillMaxSize(),
                        onAddManually = {
                            startActivity(Intent(this@PcView, AddComputerManually::class.java))
                        }
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = gridMinCellSize),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(computersState, key = { it.details.uuid }) { computerObj ->
                            val d = computerObj.details
                            HostExpressiveCard(
                                computerObj = computerObj,
                                onClick = {
                                    when {
                                        d.state == ComputerDetails.State.OFFLINE ||
                                            d.state == ComputerDetails.State.UNKNOWN -> {
                                            selectedComputerForMenu = computerObj
                                        }
                                        d.pairState != PairState.PAIRED -> {
                                            doPair(d, null, null)
                                        }
                                        else -> {
                                            doAppList(d, newlyPaired = false, showHiddenGames = false)
                                        }
                                    }
                                },
                                onLongClick = { selectedComputerForMenu = computerObj },
                                onResumeGame = {
                                    val binder = managerBinder ?: return@HostExpressiveCard
                                    ServerHelper.doStart(
                                        this@PcView,
                                        NvApp("app", null, d.runningGameId, false),
                                        d,
                                        binder,
                                        false
                                    )
                                },
                                onQuitGame = {
                                    val binder = managerBinder ?: return@HostExpressiveCard
                                    UiHelper.displayQuitConfirmationDialog(
                                        this@PcView,
                                        {
                                            ServerHelper.doQuit(
                                                this@PcView,
                                                d,
                                                NvApp("app", null, 0, false),
                                                binder,
                                                null
                                            )
                                        },
                                        null
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }

        selectedComputerForMenu?.let { computerObj ->
            HostActionsBottomSheet(
                computerObj = computerObj,
                onDismiss = { selectedComputerForMenu = null }
            )
        }

        selectedComputerForOtp?.let { details ->
            OtpPairingDialog(
                computer = details,
                onDismiss = { selectedComputerForOtp = null },
                onConfirm = { pin, pass ->
                    selectedComputerForOtp = null
                    doPair(details, pin, pass)
                }
            )
        }

        selectedComputerForDetails?.let { details ->
            AlertDialog(
                onDismissRequest = { selectedComputerForDetails = null },
                title = { Text(stringResource(R.string.title_details)) },
                text = { Text(details.toString(), style = MaterialTheme.typography.bodySmall) },
                confirmButton = {
                    TextButton(onClick = { selectedComputerForDetails = null }) {
                        Text(stringResource(android.R.string.ok))
                    }
                }
            )
        }

        pendingExternalPairPrompt?.let { (details, pin, pass) ->
            AlertDialog(
                onDismissRequest = { pendingExternalPairPrompt = null },
                title = { Text(stringResource(R.string.pair_pc_confirm_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.pair_pc_confirm_message,
                            details.name.ifBlank { details.activeAddress?.address ?: "" }
                        )
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        pendingExternalPairPrompt = null
                        doPair(details, pin, pass)
                    }) {
                        Text(stringResource(R.string.proceed))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingExternalPairPrompt = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
    }

    @Composable
    private fun EmptyDiscoveryState(modifier: Modifier = Modifier, onAddManually: () -> Unit) {
        Column(
            modifier = modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            VibemisContainedLoadingView(
                title = stringResource(R.string.searching_pc),
                subtitle = stringResource(R.string.vibemis_subtitle),
            )
            Spacer(modifier = Modifier.height(8.dp))
            FilledTonalButton(onClick = onAddManually) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.title_add_pc))
            }
        }
    }

    @OptIn(
        ExperimentalFoundationApi::class,
        ExperimentalLayoutApi::class,
        ExperimentalMaterial3ExpressiveApi::class,
    )
    @Composable
    private fun HostExpressiveCard(
        computerObj: ComputerObject,
        onClick: () -> Unit,
        onLongClick: () -> Unit,
        onResumeGame: () -> Unit,
        onQuitGame: () -> Unit,
    ) {
        val d = computerObj.details
        val isOnline = d.state == ComputerDetails.State.ONLINE
        val isPaired = d.pairState == PairState.PAIRED
        val isInGame = isOnline && d.runningGameId != 0
        val vibepollo = d.vibepolloCapabilities
        val cardInteraction = remember { MutableInteractionSource() }

        val statusContainerColor by animateColorAsState(
            targetValue = when {
                isInGame -> MaterialTheme.colorScheme.tertiaryContainer
                isOnline && isPaired -> MaterialTheme.colorScheme.primaryContainer
                isOnline -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHighest
            },
            label = "statusContainerColor"
        )
        val statusIconColor by animateColorAsState(
            targetValue = when {
                isInGame -> MaterialTheme.colorScheme.onTertiaryContainer
                isOnline && isPaired -> MaterialTheme.colorScheme.onPrimaryContainer
                isOnline -> MaterialTheme.colorScheme.onSecondaryContainer
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            label = "statusIconColor"
        )
        val badgePolygon = when {
            isInGame -> MaterialShapes.Cookie9Sided
            isOnline && isPaired -> MaterialShapes.Clover4Leaf
            isOnline -> MaterialShapes.SoftBurst
            else -> MaterialShapes.Slanted
        }

        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .expressiveFocusAndPress(
                    interactionSource = cardInteraction,
                    shape = MaterialTheme.shapes.large,
                )
                .clip(MaterialTheme.shapes.large)
                .combinedClickable(
                    interactionSource = cardInteraction,
                    indication = LocalIndication.current,
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.elevatedCardColors(
                containerColor = when {
                    isInGame -> MaterialTheme.colorScheme.surfaceContainerHighest
                    isOnline -> MaterialTheme.colorScheme.surfaceContainerHigh
                    else -> MaterialTheme.colorScheme.surfaceContainerLow
                }
            )
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    VibemisShapeBadge(
                        polygon = badgePolygon,
                        containerColor = statusContainerColor,
                        contentColor = statusIconColor,
                        size = 48.dp,
                    ) {
                        Icon(
                            imageVector = if (isInGame) Icons.Default.PlayArrow else Icons.Default.Computer,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = d.name,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val subtitle = when {
                            !isOnline -> stringResource(R.string.pcview_menu_header_offline)
                            !isPaired -> stringResource(R.string.pcview_menu_pair_pc)
                            isInGame -> stringResource(R.string.streaming_session_active)
                            else -> d.activeAddress?.toString() ?: stringResource(R.string.pcview_menu_header_online)
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isInGame) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onLongClick) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.title_details))
                    }
                }

                AnimatedVisibility(visible = isInGame) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = onResumeGame,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.action_resume_short),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        OutlinedButton(
                            onClick = onQuitGame,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.action_quit_short),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible = isOnline && (d.vDisplaySupported || vibepollo.supportsPyroWave || vibepollo.canPutHostToSleep)
                ) {
                    Column {
                        Spacer(modifier = Modifier.height(12.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            itemVerticalAlignment = Alignment.CenterVertically
                        ) {
                            if (vibepollo.supportsPyroWave) {
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ) {
                                    Text(
                                        text = stringResource(R.string.badge_pyrowave),
                                        style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                            if (d.vDisplaySupported) {
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Tv,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = stringResource(R.string.badge_virtual_display),
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                    }
                                }
                            }
                            if (vibepollo.canPutHostToSleep) {
                                AssistChip(
                                    onClick = { doSleepHost(d) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Bedtime,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    label = { Text(stringResource(R.string.sleep_host)) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun HostActionsBottomSheet(
        computerObj: ComputerObject,
        onDismiss: () -> Unit
    ) {
        val d = computerObj.details
        val isOnline = d.state == ComputerDetails.State.ONLINE
        val isPaired = d.pairState == PairState.PAIRED

        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp)
            ) {
                Text(
                    text = d.name,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                if (!isOnline) {
                    SheetActionItem(
                        icon = Icons.Default.PowerSettingsNew,
                        title = stringResource(R.string.pcview_menu_send_wol),
                        onClick = {
                            onDismiss()
                            doWakeOnLan(d)
                        }
                    )
                } else if (!isPaired) {
                    SheetActionItem(
                        icon = Icons.Default.Key,
                        title = stringResource(R.string.pcview_menu_pair_pc_otp),
                        onClick = {
                            onDismiss()
                            selectedComputerForOtp = d
                        }
                    )
                    SheetActionItem(
                        icon = Icons.Default.Link,
                        title = stringResource(R.string.pcview_menu_pair_pc),
                        onClick = {
                            onDismiss()
                            doPair(d, null, null)
                        }
                    )
                } else {
                    if (d.runningGameId != 0) {
                        SheetActionItem(
                            icon = Icons.Default.PlayArrow,
                            title = stringResource(R.string.applist_menu_resume),
                            onClick = {
                                onDismiss()
                                val binder = managerBinder ?: return@SheetActionItem
                                ServerHelper.doStart(
                                    this@PcView,
                                    NvApp("app", null, d.runningGameId, false),
                                    d,
                                    binder,
                                    false
                                )
                            }
                        )
                        SheetActionItem(
                            icon = Icons.Default.Stop,
                            title = stringResource(R.string.applist_menu_quit),
                            onClick = {
                                onDismiss()
                                val binder = managerBinder ?: return@SheetActionItem
                                UiHelper.displayQuitConfirmationDialog(
                                    this@PcView,
                                    { ServerHelper.doQuit(this@PcView, d, NvApp("app", null, 0, false), binder, null) },
                                    null
                                )
                            }
                        )
                    }
                    SheetActionItem(
                        icon = Icons.Default.Computer,
                        title = stringResource(R.string.pcview_menu_app_list),
                        onClick = {
                            onDismiss()
                            doAppList(d, newlyPaired = false, showHiddenGames = true)
                        }
                    )
                    if (d.vibepolloCapabilities.canPutHostToSleep) {
                        SheetActionItem(
                            icon = Icons.Default.Bedtime,
                            title = stringResource(R.string.put_host_to_sleep),
                            onClick = {
                                onDismiss()
                                doSleepHost(d)
                            }
                        )
                    }
                    SheetActionItem(
                        icon = Icons.Default.LinkOff,
                        title = stringResource(R.string.unpair_host),
                        onClick = {
                            onDismiss()
                            doUnpair(d)
                        }
                    )
                }

                if (isOnline && !d.nvidiaServer) {
                    SheetActionItem(
                        icon = Icons.Default.OpenInBrowser,
                        title = stringResource(R.string.pcview_menu_open_management_page),
                        onClick = {
                            onDismiss()
                            val url = computerObj.guessManagementUrl()
                            if (url != null) {
                                HelpLauncher.launchUrl(this@PcView, url)
                            }
                        }
                    )
                }

                SheetActionItem(
                    icon = Icons.Default.NetworkCheck,
                    title = stringResource(R.string.pcview_menu_test_network),
                    onClick = {
                        onDismiss()
                        ServerHelper.doNetworkTest(this@PcView, d)
                    }
                )
                SheetActionItem(
                    icon = Icons.Default.Info,
                    title = stringResource(R.string.pcview_menu_details),
                    onClick = {
                        onDismiss()
                        selectedComputerForDetails = d
                    }
                )
                SheetActionItem(
                    icon = Icons.Default.Delete,
                    title = stringResource(R.string.pcview_menu_delete_pc),
                    onClick = {
                        onDismiss()
                        if (!ActivityManager.isUserAMonkey()) {
                            UiHelper.displayDeletePcConfirmationDialog(
                                this@PcView,
                                d,
                                { removeComputer(d) },
                                null
                            )
                        }
                    }
                )
            }
        }
    }

    @Composable
    private fun SheetActionItem(
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        title: String,
        onClick: () -> Unit
    ) {
        Surface(
            onClick = onClick,
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            ListItem(
                leadingContent = { Icon(icon, contentDescription = null) }
            ) {
                Text(title)
            }
        }
    }

    @Composable
    private fun OtpPairingDialog(
        computer: ComputerDetails,
        onDismiss: () -> Unit,
        onConfirm: (String, String) -> Unit
    ) {
        var pin by remember { mutableStateOf("") }
        var passphrase by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.pcview_menu_pair_pc_otp)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(computer.name, style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { if (it.length <= 4) pin = it },
                        label = { Text(stringResource(R.string.pin_four_digits)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text(stringResource(R.string.pair_passphrase_hint)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
            },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        if (pin.length == 4 && passphrase.length >= 4) {
                            onConfirm(pin, passphrase)
                        } else {
                            Dialog.displayDialog(
                                this@PcView,
                                getString(R.string.pair_pairing_title),
                                getString(R.string.pair_pin_length_msg),
                                false
                            )
                        }
                    }
                ) {
                    Text(stringResource(R.string.proceed))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    @androidx.compose.runtime.Stable
    class ComputerObject(@JvmField var details: ComputerDetails) {
        override fun toString(): String = details.name

        fun guessManagementUrl(): String? {
            val active = details.activeAddress ?: return null
            return "https://${active.address}:${details.guessExternalPort() + 1}"
        }
    }
}
