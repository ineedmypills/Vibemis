package com.ineedmypills.vibemis

import android.app.Activity
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ineedmypills.vibemis.binding.PlatformBinding
import com.ineedmypills.vibemis.computers.ComputerManagerListener
import com.ineedmypills.vibemis.computers.ComputerManagerService
import com.ineedmypills.vibemis.grid.assets.CachedAppAssetLoader
import com.ineedmypills.vibemis.grid.assets.DiskAssetLoader
import com.ineedmypills.vibemis.grid.assets.MemoryAssetLoader
import com.ineedmypills.vibemis.grid.assets.NetworkAssetLoader
import com.ineedmypills.vibemis.nvstream.http.ComputerDetails
import com.ineedmypills.vibemis.nvstream.http.NvApp
import com.ineedmypills.vibemis.nvstream.http.NvHTTP
import com.ineedmypills.vibemis.nvstream.http.PairingManager
import com.ineedmypills.vibemis.preferences.PreferenceConfiguration
import com.ineedmypills.vibemis.profiles.ProfilesManager
import com.ineedmypills.vibemis.ui.components.VibemisContainedLoadingView
import com.ineedmypills.vibemis.ui.components.VibemisShapeBadge
import com.ineedmypills.vibemis.ui.components.enableVibemisEdgeToEdge
import com.ineedmypills.vibemis.ui.components.expressiveFocusAndPress
import com.ineedmypills.vibemis.ui.components.rememberVibemisAdaptiveLayout
import com.ineedmypills.vibemis.ui.theme.VibemisExpressiveTheme
import com.ineedmypills.vibemis.utils.CacheHelper
import com.ineedmypills.vibemis.utils.Dialog
import com.ineedmypills.vibemis.utils.ServerHelper
import com.ineedmypills.vibemis.utils.ShortcutHelper
import com.ineedmypills.vibemis.utils.SpinnerDialog
import com.ineedmypills.vibemis.utils.UiHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.StringReader
import java.util.concurrent.ConcurrentHashMap

class AppView : AppCompatActivity() {
    private var uuidString: String? = null
    private var computerNameState by mutableStateOf("")
    private var shortcutHelper: ShortcutHelper? = null
    private var computer: ComputerDetails? = null
    private var poller: ComputerManagerService.ApplistPoller? = null
    private var blockingLoadSpinner: SpinnerDialog? = null
    private var lastRawApplist: String? = null
    private var lastRunningAppId by mutableIntStateOf(0)
    private var suspendGridUpdates = false
    private var inForeground = false
    private var showHiddenApps by mutableStateOf(false)
    private val hiddenAppIds = HashSet<Int>()
    private lateinit var prefConfig: PreferenceConfiguration

    private val allAppsState = mutableStateListOf<AppObject>()
    private val boxArtCache = ConcurrentHashMap<Int, Bitmap>()
    private val failedBoxArtIds = ConcurrentHashMap.newKeySet<Int>()
    private var activeProfileNameState by mutableStateOf("")
    private var selectedAppForSheet by mutableStateOf<AppObject?>(null)
    private var selectedAppForDetails by mutableStateOf<NvApp?>(null)
    private var canSleepHostState by mutableStateOf(false)

    private var memoryLoader: MemoryAssetLoader? = null
    private var diskLoader: DiskAssetLoader? = null
    private var networkLoader: NetworkAssetLoader? = null

    private var managerBinder: ComputerManagerService.ComputerManagerBinder? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            val localBinder = binder as ComputerManagerService.ComputerManagerBinder
            Thread {
                localBinder.waitForReady()
                val comp = localBinder.getComputer(uuidString)
                if (comp == null) {
                    finish()
                    return@Thread
                }
                computer = comp
                canSleepHostState = comp.vibepolloCapabilities.canPutHostToSleep

                shortcutHelper?.createAppViewShortcut(
                    comp,
                    true,
                    intent.getBooleanExtra(NEW_PAIR_EXTRA, false)
                )
                shortcutHelper?.reportComputerShortcutUsed(comp)

                memoryLoader = MemoryAssetLoader()
                diskLoader = DiskAssetLoader(this@AppView)
                networkLoader = NetworkAssetLoader(this@AppView, localBinder.uniqueId)

                managerBinder = localBinder
                populateAppGridWithCache()
                startComputerUpdates()
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
        shortcutHelper = ShortcutHelper(this)
        UiHelper.setLocale(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false)
        }

        showHiddenApps = intent.getBooleanExtra(SHOW_HIDDEN_APPS_EXTRA, false)
        uuidString = intent.getStringExtra(UUID_EXTRA)
        computerNameState = intent.getStringExtra(NAME_EXTRA) ?: "Host"
        title = computerNameState

        val hiddenAppsPrefs = getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
        val savedHidden = hiddenAppsPrefs.getStringSet(uuidString, emptySet()).orEmpty()
        for (idStr in savedHidden) {
            idStr.toIntOrNull()?.let { hiddenAppIds.add(it) }
        }

        prefConfig = PreferenceConfiguration.readPreferences(this)
        activeProfileNameState = ProfilesManager.getInstance().activeName

        bindService(
            Intent(this, ComputerManagerService::class.java),
            serviceConnection,
            Service.BIND_AUTO_CREATE
        )

        setupComposeContent()
    }

    private fun setupComposeContent() {
        val composeView = ComposeView(this).apply {
            setContent {
                VibemisExpressiveTheme {
                    AppViewScreen()
                }
            }
        }
        setContentView(composeView)
    }

    fun openProfiles() {
        startActivity(Intent(this, ProfilesActivity::class.java))
    }

    private fun startComputerUpdates() {
        val binder = managerBinder ?: return
        if (!inForeground) return

        binder.startPolling(ComputerManagerListener { details ->
            if (suspendGridUpdates) return@ComputerManagerListener
            if (!details.uuid.equals(uuidString, ignoreCase = true)) return@ComputerManagerListener

            if (details.state == ComputerDetails.State.OFFLINE) {
                runOnUiThread {
                    Dialog.displayDialog(
                        this@AppView,
                        getString(R.string.conn_error_title),
                        getString(R.string.lost_connection),
                        true
                    )
                }
                return@ComputerManagerListener
            }

            if (details.state == ComputerDetails.State.ONLINE &&
                details.pairState != PairingManager.PairState.PAIRED
            ) {
                val notPairedMsg = resources.getString(R.string.scut_not_paired)
                Thread({
                    shortcutHelper?.disableComputerShortcut(details, notPairedMsg)
                }, "ShortcutDisable").start()
                runOnUiThread {
                    Dialog.displayDialog(
                        this@AppView,
                        getString(R.string.conn_error_title),
                        notPairedMsg,
                        true
                    )
                }
                return@ComputerManagerListener
            }

            canSleepHostState = details.vibepolloCapabilities.canPutHostToSleep

            if (details.rawAppList == null || details.rawAppList == lastRawApplist) {
                if (details.runningGameId != lastRunningAppId) {
                    lastRunningAppId = details.runningGameId
                    updateUiWithServerinfo(details)
                }
                return@ComputerManagerListener
            }

            lastRunningAppId = details.runningGameId
            lastRawApplist = details.rawAppList

            try {
                updateUiWithAppList(NvHTTP.getAppListByReader(StringReader(details.rawAppList)))
                updateUiWithServerinfo(details)
                blockingLoadSpinner?.dismiss()
                blockingLoadSpinner = null
            } catch (_: XmlPullParserException) {
            } catch (_: IOException) {
            }
        })

        val currentComputer = computer
        if (poller == null && currentComputer != null) {
            poller = binder.createAppListPoller(currentComputer)
        }
        poller?.start()
    }

    private fun stopComputerUpdates() {
        poller?.stop()
        managerBinder?.stopPolling()
        memoryLoader?.clearCache()
    }

    private fun populateAppGridWithCache() {
        try {
            val raw = CacheHelper.readInputStreamToString(
                CacheHelper.openCacheFileForInput(cacheDir, "applist", uuidString)
            )
            lastRawApplist = raw
            val applist = NvHTTP.getAppListByReader(StringReader(raw))
            updateUiWithAppList(applist)
            VibemisLog.info("Loaded applist from cache")
        } catch (_: Exception) {
            VibemisLog.info("Loading applist from the network")
            blockingLoadSpinner = SpinnerDialog.displayDialog(
                this,
                resources.getString(R.string.applist_refresh_title),
                resources.getString(R.string.applist_refresh_msg),
                true
            )
        }
    }

    private fun updateUiWithServerinfo(details: ComputerDetails) {
        runOnUiThread {
            lastRunningAppId = details.runningGameId
            for (i in allAppsState.indices) {
                val item = allAppsState[i]
                val shouldBeRunning = item.app.appId == details.runningGameId
                if (item.isRunning != shouldBeRunning) {
                    item.isRunning = shouldBeRunning
                    allAppsState[i] = AppObject(item.app).apply {
                        isRunning = shouldBeRunning
                        isHidden = item.isHidden
                    }
                }
            }
        }
    }

    private fun updateUiWithAppList(appList: List<NvApp>) {
        val comp = computer
        if (comp != null && shortcutHelper != null) {
            val snapshot = ArrayList(appList)
            Thread({
                for (app in snapshot) {
                    shortcutHelper?.enableAppShortcut(comp, app)
                }
            }, "ShortcutEnable").start()
        }
        runOnUiThread {
            val updatedList = ArrayList<AppObject>(appList.size)
            for (app in appList) {
                val obj = AppObject(app).apply {
                    isRunning = (app.appId == lastRunningAppId)
                    isHidden = hiddenAppIds.contains(app.appId)
                }
                updatedList.add(obj)
            }
            updatedList.sortWith { lhs, rhs ->
                val lIdx = lhs.app.appIndex
                val rIdx = rhs.app.appIndex
                if (lIdx == rIdx) {
                    lhs.app.appName.lowercase().compareTo(rhs.app.appName.lowercase())
                } else {
                    lIdx - rIdx
                }
            }
            allAppsState.clear()
            allAppsState.addAll(updatedList)
        }
    }

    private fun saveHiddenApps() {
        val strSet = hiddenAppIds.map { it.toString() }.toHashSet()
        getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
            .edit()
            .putStringSet(uuidString, strSet)
            .apply()
        for (i in allAppsState.indices) {
            val item = allAppsState[i]
            val hidden = hiddenAppIds.contains(item.app.appId)
            if (item.isHidden != hidden) {
                allAppsState[i] = AppObject(item.app).apply {
                    isRunning = item.isRunning
                    isHidden = hidden
                }
            }
        }
    }

    private suspend fun loadBoxArtBitmap(app: NvApp): Bitmap? = withContext(Dispatchers.IO) {
        if (failedBoxArtIds.contains(app.appId)) return@withContext null
        val comp = computer ?: return@withContext null
        val mem = memoryLoader ?: return@withContext null
        val disk = diskLoader ?: return@withContext null
        val net = networkLoader ?: return@withContext null

        val tuple = CachedAppAssetLoader.LoaderTuple(comp, app)
        mem.loadBitmapFromCache(tuple)?.bitmap?.let {
            boxArtCache[app.appId] = it
            return@withContext it
        }
        disk.loadBitmapFromCache(tuple, 1)?.let { scaled ->
            mem.populateCache(tuple, scaled)
            scaled.bitmap?.let { boxArtCache[app.appId] = it }
            return@withContext scaled.bitmap
        }
        net.getBitmapStream(tuple)?.use { stream ->
            disk.populateCacheWithStream(tuple, stream)
        }
        disk.loadBitmapFromCache(tuple, 1)?.let { scaled ->
            mem.populateCache(tuple, scaled)
            scaled.bitmap?.let { boxArtCache[app.appId] = it }
            return@withContext scaled.bitmap
        }
        failedBoxArtIds.add(app.appId)
        null
    }

    private fun handleAppTileLaunch(
        app: NvApp,
        comp: ComputerDetails,
        binder: ComputerManagerService.ComputerManagerBinder,
        useVirtualDisplay: Boolean
    ) {
        if (app.isVibepolloControlAction()) {
            Thread {
                var feedback: String? = null
                var errorDetail: String? = null
                try {
                    val httpConn = NvHTTP(
                        ServerHelper.getCurrentAddressFromComputer(comp),
                        comp.httpsPort,
                        binder.uniqueId,
                        comp.serverCert,
                        PlatformBinding.getCryptoProvider(this@AppView)
                    )
                    feedback = httpConn.executeVibepolloControlAction(app)
                } catch (e: Exception) {
                    errorDetail = e.message ?: e.javaClass.simpleName
                }
                runOnUiThread {
                    val msg = errorDetail ?: feedback ?: getString(R.string.vibepollo_control_executed)
                    Toast.makeText(this@AppView, msg, Toast.LENGTH_SHORT).show()
                    poller?.pollNow()
                }
            }.start()
            return
        }

        if (useVirtualDisplay && !(comp.vDisplaySupported && comp.vDisplayDriverReady)) {
            UiHelper.displayVdisplayConfirmationDialog(
                this@AppView,
                comp,
                { ServerHelper.doStart(this@AppView, app, comp, binder, true) },
                null
            )
        } else {
            ServerHelper.doStart(this@AppView, app, comp, binder, useVirtualDisplay)
        }
    }

    private fun doSleepHost() {
        val comp = computer ?: return
        val binder = managerBinder ?: return
        Thread {
            var errDetail: String? = null
            val accepted = try {
                val httpConn = NvHTTP(
                    ServerHelper.getCurrentAddressFromComputer(comp),
                    comp.httpsPort,
                    binder.uniqueId,
                    comp.serverCert,
                    PlatformBinding.getCryptoProvider(this@AppView)
                )
                httpConn.requestHostSleep()
            } catch (e: Exception) {
                errDetail = "${e.javaClass.simpleName}: ${e.message}"
                false
            }
            runOnUiThread {
                if (accepted) {
                    finish()
                } else {
                    Dialog.displayDetailedErrorDialog(
                        this@AppView,
                        comp.name,
                        getString(R.string.sleep_host_failed),
                        errDetail,
                        false
                    )
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        UiHelper.showDecoderCrashDialog(this)
        inForeground = true
        failedBoxArtIds.clear()
        prefConfig = PreferenceConfiguration.readPreferences(this)
        activeProfileNameState = ProfilesManager.getInstance().activeName
        startComputerUpdates()
    }

    override fun onPause() {
        super.onPause()
        inForeground = false
        stopComputerUpdates()
    }

    override fun onDestroy() {
        super.onDestroy()
        SpinnerDialog.closeDialogs(this)
        Dialog.closeDialogs()
        if (managerBinder != null) {
            unbindService(serviceConnection)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ShortcutHelper.REQUEST_CODE_EXPORT_LAUNCHER_FILE) {
            val uri = data?.data
            if (resultCode == Activity.RESULT_OK && uri != null) {
                ShortcutHelper.writeLauncherFileToUri(this, uri)
            } else {
                ShortcutHelper.launcherFileContentToExport = null
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    private fun AppViewScreen() {
        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        val gridState = rememberLazyGridState()
        val adaptive = rememberVibemisAdaptiveLayout()
        val visibleApps = remember(allAppsState.toList(), showHiddenApps) {
            allAppsState.filter { showHiddenApps || !it.isHidden }
        }
        val runningAppName = remember(allAppsState.toList(), lastRunningAppId) {
            if (lastRunningAppId == 0) {
                null
            } else {
                allAppsState.firstOrNull { it.app.appId == lastRunningAppId }?.app?.appName
            }
        }
        val minCellSize = when {
            prefConfig.smallIconMode -> 124.dp
            adaptive.isExpandedWidth -> 188.dp
            adaptive.isLandscape -> 172.dp
            else -> 164.dp
        }
        val subtitleText = if (lastRunningAppId != 0) {
            if (!runningAppName.isNullOrBlank()) {
                stringResource(R.string.appview_subtitle_running, runningAppName)
            } else {
                stringResource(R.string.streaming_session_active)
            }
        } else {
            stringResource(
                R.string.appview_subtitle_apps,
                visibleApps.size,
                activeProfileNameState.ifEmpty { stringResource(R.string.vibemis_subtitle) }
            )
        }
        val subtitleColor = if (lastRunningAppId != 0) {
            MaterialTheme.colorScheme.tertiary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
        val profileLabel = activeProfileNameState.ifEmpty { stringResource(R.string.profiles_title) }

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (adaptive.isCompactHeight) {
                        Modifier
                    } else {
                        Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
                    }
                ),
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                val appBarActions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
                    AssistChip(
                        onClick = { openProfiles() },
                        label = {
                            Text(
                                text = profileLabel,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = stringResource(R.string.profiles_title),
                                modifier = Modifier.size(AssistChipDefaults.IconSize),
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (activeProfileNameState.isEmpty()) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            },
                            labelColor = if (activeProfileNameState.isEmpty()) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            },
                            leadingIconContentColor = if (activeProfileNameState.isEmpty()) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            },
                        ),
                        border = null,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .widthIn(max = 152.dp),
                    )
                    if (canSleepHostState) {
                        IconButton(onClick = { doSleepHost() }) {
                            Icon(Icons.Default.Bedtime, contentDescription = stringResource(R.string.sleep_host))
                        }
                    }
                    IconButton(onClick = { showHiddenApps = !showHiddenApps }) {
                        Icon(
                            if (showHiddenApps) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = stringResource(R.string.toggle_hidden_apps)
                        )
                    }
                }
                if (adaptive.isCompactHeight) {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = computerNameState,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = subtitleText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = subtitleColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { finish() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                            }
                        },
                        actions = appBarActions
                    )
                } else {
                    LargeFlexibleTopAppBar(
                        title = {
                            Text(
                                text = computerNameState,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        subtitle = {
                            Text(
                                text = subtitleText,
                                color = if (lastRunningAppId != 0) MaterialTheme.colorScheme.tertiary else Color.Unspecified,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { finish() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                            }
                        },
                        actions = appBarActions,
                        scrollBehavior = scrollBehavior
                    )
                }
            }
        ) { innerPadding ->
            if (visibleApps.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (allAppsState.isEmpty()) {
                        VibemisContainedLoadingView(
                            title = stringResource(R.string.applist_refresh_title),
                        )
                    } else {
                        VibemisShapeBadge(
                            polygon = MaterialShapes.Ghostish,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            size = 64.dp,
                        ) {
                            Icon(
                                Icons.Default.VisibilityOff,
                                contentDescription = null,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.all_apps_hidden),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        FilledTonalButton(onClick = { showHiddenApps = true }) {
                            Icon(Icons.Default.Visibility, contentDescription = null)
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(stringResource(R.string.show_hidden_apps))
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = minCellSize),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(visibleApps, key = { it.app.appId }) { appObj ->
                        AppExpressivePosterCard(
                            appObj = appObj,
                            onClick = {
                                val comp = computer ?: return@AppExpressivePosterCard
                                val binder = managerBinder ?: return@AppExpressivePosterCard
                                if (appObj.app.isVibepolloControlAction()) {
                                    handleAppTileLaunch(appObj.app, comp, binder, false)
                                } else if (lastRunningAppId != 0) {
                                    if ((prefConfig.resumeWithoutConfirm && lastRunningAppId == appObj.app.appId) ||
                                        appObj.app.isVibepolloSyntheticApp()
                                    ) {
                                        handleAppTileLaunch(appObj.app, comp, binder, prefConfig.useVirtualDisplay)
                                    } else {
                                        selectedAppForSheet = appObj
                                    }
                                } else {
                                    handleAppTileLaunch(appObj.app, comp, binder, prefConfig.useVirtualDisplay)
                                }
                            },
                            onLongClick = { selectedAppForSheet = appObj },
                            onResumeRunning = {
                                val comp = computer ?: return@AppExpressivePosterCard
                                val binder = managerBinder ?: return@AppExpressivePosterCard
                                ServerHelper.doStart(this@AppView, appObj.app, comp, binder, prefConfig.useVirtualDisplay)
                            },
                            onQuitRunning = {
                                val comp = computer ?: return@AppExpressivePosterCard
                                val binder = managerBinder ?: return@AppExpressivePosterCard
                                UiHelper.displayQuitConfirmationDialog(
                                    this@AppView,
                                    {
                                        suspendGridUpdates = true
                                        ServerHelper.doQuit(this@AppView, comp, appObj.app, binder) {
                                            suspendGridUpdates = false
                                            poller?.pollNow()
                                        }
                                    },
                                    null
                                )
                            }
                        )
                    }
                }
            }
        }

        selectedAppForSheet?.let { appObj ->
            AppActionsBottomSheet(
                appObj = appObj,
                onDismiss = { selectedAppForSheet = null }
            )
        }

        selectedAppForDetails?.let { nvApp ->
            AlertDialog(
                onDismissRequest = { selectedAppForDetails = null },
                title = { Text(stringResource(R.string.title_details)) },
                text = { Text(nvApp.toString(), style = MaterialTheme.typography.bodySmall) },
                confirmButton = {
                    TextButton(onClick = { selectedAppForDetails = null }) {
                        Text(stringResource(android.R.string.ok))
                    }
                }
            )
        }
    }

    @OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    private fun AppExpressivePosterCard(
        appObj: AppObject,
        onClick: () -> Unit,
        onLongClick: () -> Unit,
        onResumeRunning: () -> Unit,
        onQuitRunning: () -> Unit,
    ) {
        val bitmap by produceState<Bitmap?>(initialValue = boxArtCache[appObj.app.appId], appObj.app.appId) {
            if (value == null) {
                value = loadBoxArtBitmap(appObj.app)
            }
        }
        val interactionSource = remember { MutableInteractionSource() }

        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .alpha(if (appObj.isHidden) 0.45f else 1f)
                .expressiveFocusAndPress(
                    interactionSource = interactionSource,
                    shape = MaterialTheme.shapes.large,
                )
                .clip(MaterialTheme.shapes.large)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val cardWidth = maxWidth
                val bmp = bitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = appObj.app.appName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (!appObj.isRunning) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                start = 14.dp,
                                end = 14.dp,
                                top = if (appObj.app.isVibepolloSyntheticApp()) 28.dp else 16.dp,
                                bottom = 16.dp
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        val fallbackIcon = when {
                            appObj.app.isRemoteMonitorApp() -> Icons.Default.Tv
                            appObj.app.isRemoteInputApp() -> Icons.Default.Gamepad
                            appObj.app.isVibepolloControlAction() -> Icons.Default.PowerSettingsNew
                            else -> Icons.Default.SportsEsports
                        }
                        VibemisShapeBadge(
                            polygon = if (appObj.app.isVibepolloSyntheticApp()) {
                                MaterialShapes.SoftBurst
                            } else {
                                MaterialShapes.Gem
                            },
                            containerColor = if (appObj.app.isVibepolloSyntheticApp()) {
                                MaterialTheme.colorScheme.tertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            },
                            contentColor = if (appObj.app.isVibepolloSyntheticApp()) {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            },
                            size = 56.dp,
                        ) {
                            Icon(
                                fallbackIcon,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = appObj.app.appName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (appObj.app.isVibepolloSyntheticApp()) {
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.94f),
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.vibepollo_badge_label),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                if (bmp != null && !appObj.isRunning) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    0.0f to Color.Transparent,
                                    0.30f to Color.Black.copy(alpha = 0.65f),
                                    1.0f to Color.Black.copy(alpha = 0.90f),
                                )
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = appObj.app.appName,
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (appObj.isRunning) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.56f))
                            .padding(10.dp),
                    ) {
                        Text(
                            text = appObj.app.appName,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(end = if (appObj.app.isVibepolloSyntheticApp()) 72.dp else 0.dp)
                        )
                        VibemisShapeBadge(
                            polygon = MaterialShapes.Cookie9Sided,
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            size = 54.dp,
                            modifier = Modifier.align(Alignment.Center),
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = stringResource(R.string.action_resume_short),
                                modifier = Modifier.size(30.dp)
                            )
                        }

                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (cardWidth >= 190.dp) {
                                FilledTonalButton(
                                    onClick = onResumeRunning,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = stringResource(R.string.action_resume_short),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                FilledTonalButton(
                                    onClick = onQuitRunning,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = stringResource(R.string.action_quit_short),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            } else {
                                FilledTonalIconButton(
                                    onClick = onResumeRunning,
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                    ),
                                ) {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = stringResource(R.string.action_resume_short),
                                    )
                                }
                                FilledTonalIconButton(
                                    onClick = onQuitRunning,
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                    ),
                                ) {
                                    Icon(
                                        Icons.Default.Stop,
                                        contentDescription = stringResource(R.string.action_quit_short),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AppActionsBottomSheet(
        appObj: AppObject,
        onDismiss: () -> Unit
    ) {
        val comp = computer ?: return
        val binder = managerBinder ?: return
        val app = appObj.app

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
                    text = app.appName,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                if (app.isVibepolloControlAction()) {
                    SheetItem(
                        icon = Icons.Default.PowerSettingsNew,
                        title = stringResource(R.string.vibepollo_control_run_action),
                        onClick = {
                            onDismiss()
                            handleAppTileLaunch(app, comp, binder, false)
                        }
                    )
                } else if (lastRunningAppId == 0 || app.isVibepolloSyntheticApp()) {
                    SheetItem(
                        icon = Icons.Default.PlayArrow,
                        title = stringResource(R.string.applist_menu_start_primarydisplay),
                        onClick = {
                            onDismiss()
                            ServerHelper.doStart(this@AppView, app, comp, binder, false)
                        }
                    )
                    SheetItem(
                        icon = Icons.Default.Tv,
                        title = stringResource(R.string.applist_menu_start_vdisplay),
                        onClick = {
                            onDismiss()
                            ServerHelper.doStart(this@AppView, app, comp, binder, true)
                        }
                    )
                } else if (lastRunningAppId == app.appId) {
                    SheetItem(
                        icon = Icons.Default.PlayArrow,
                        title = stringResource(R.string.applist_menu_resume),
                        onClick = {
                            onDismiss()
                            ServerHelper.doStart(this@AppView, app, comp, binder, prefConfig.useVirtualDisplay)
                        }
                    )
                    SheetItem(
                        icon = Icons.Default.Stop,
                        title = stringResource(R.string.applist_menu_quit),
                        onClick = {
                            onDismiss()
                            UiHelper.displayQuitConfirmationDialog(
                                this@AppView,
                                {
                                    suspendGridUpdates = true
                                    ServerHelper.doQuit(this@AppView, comp, app, binder) {
                                        suspendGridUpdates = false
                                        poller?.pollNow()
                                    }
                                },
                                null
                            )
                        }
                    )
                } else {
                    SheetItem(
                        icon = Icons.Default.PlayArrow,
                        title = stringResource(R.string.applist_menu_quit_and_start),
                        onClick = {
                            onDismiss()
                            UiHelper.displayQuitConfirmationDialog(
                                this@AppView,
                                { ServerHelper.doStart(this@AppView, app, comp, binder, prefConfig.useVirtualDisplay) },
                                null
                            )
                        }
                    )
                }

                if (lastRunningAppId != app.appId || appObj.isHidden) {
                    SheetItem(
                        icon = if (appObj.isHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        title = stringResource(R.string.applist_menu_hide_app),
                        onClick = {
                            onDismiss()
                            if (appObj.isHidden) {
                                hiddenAppIds.remove(app.appId)
                            } else {
                                hiddenAppIds.add(app.appId)
                            }
                            saveHiddenApps()
                        }
                    )
                }

                boxArtCache[app.appId]?.let { bmp ->
                    SheetItem(
                        icon = Icons.Default.PushPin,
                        title = stringResource(R.string.applist_menu_scut),
                        onClick = {
                            onDismiss()
                            if (shortcutHelper?.createPinnedGameShortcut(comp, app, bmp) == false) {
                                Dialog.displayDialog(
                                    this@AppView,
                                    getString(R.string.conn_error_title),
                                    getString(R.string.unable_to_pin_shortcut),
                                    false
                                )
                            }
                        }
                    )
                }

                SheetItem(
                    icon = Icons.Default.FileDownload,
                    title = stringResource(R.string.applist_menu_export_launcher),
                    onClick = {
                        onDismiss()
                        shortcutHelper?.exportLauncherFile(comp, app)
                    }
                )

                SheetItem(
                    icon = Icons.Default.Info,
                    title = stringResource(R.string.applist_menu_details),
                    onClick = {
                        onDismiss()
                        selectedAppForDetails = app
                    }
                )
            }
        }
    }

    @Composable
    private fun SheetItem(
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

    @androidx.compose.runtime.Stable
    class AppObject(@JvmField val app: NvApp) {
        @JvmField
        var isRunning: Boolean = false

        @JvmField
        var isHidden: Boolean = false

        override fun toString(): String = app.appName
    }

    companion object {
        const val HIDDEN_APPS_PREF_FILENAME = "HiddenApps"
        const val NAME_EXTRA = "Name"
        const val UUID_EXTRA = "UUID"
        const val NEW_PAIR_EXTRA = "NewPair"
        const val SHOW_HIDDEN_APPS_EXTRA = "ShowHiddenApps"
    }
}
