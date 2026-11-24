@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.ineedmypills.vibemis

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.app.Service
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Display
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.View.OnGenericMotionListener
import android.view.View.OnSystemUiVisibilityChangeListener
import android.view.View.OnTouchListener
import android.view.ViewParent
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.ineedmypills.vibemis.preferences.PreferenceConfiguration
import com.ineedmypills.vibemis.binding.PlatformBinding
import com.ineedmypills.vibemis.binding.input.ControllerHandler
import com.ineedmypills.vibemis.binding.input.GameInputDevice
import com.ineedmypills.vibemis.binding.input.KeyboardTranslator
import com.ineedmypills.vibemis.binding.input.capture.InputCaptureManager
import com.ineedmypills.vibemis.binding.input.capture.InputCaptureProvider
import com.ineedmypills.vibemis.binding.input.driver.UsbDriverService
import com.ineedmypills.vibemis.binding.input.touch.AbsoluteTouchContext
import com.ineedmypills.vibemis.binding.input.touch.RelativeTouchContext
import com.ineedmypills.vibemis.binding.input.touch.TouchContext
import com.ineedmypills.vibemis.binding.input.touch.TrackpadContext
import com.ineedmypills.vibemis.binding.input.virtual_controller.VirtualController
import com.ineedmypills.vibemis.binding.input.virtual_controller.keyboard.KeyBoardController
import com.ineedmypills.vibemis.binding.input.virtual_controller.keyboard.KeyBoardLayoutController
import com.ineedmypills.vibemis.binding.video.CrashListener
import com.ineedmypills.vibemis.binding.video.MediaCodecDecoderRenderer
import com.ineedmypills.vibemis.binding.video.MediaCodecHelper
import com.ineedmypills.vibemis.binding.video.PerfOverlayListener
import com.ineedmypills.vibemis.nvstream.NvConnection
import com.ineedmypills.vibemis.nvstream.NvConnectionListener
import com.ineedmypills.vibemis.nvstream.StreamConfiguration
import com.ineedmypills.vibemis.nvstream.http.ComputerDetails
import com.ineedmypills.vibemis.nvstream.http.HardwareMetricsSnapshot
import com.ineedmypills.vibemis.nvstream.http.NvApp
import com.ineedmypills.vibemis.nvstream.http.NvHTTP
import com.ineedmypills.vibemis.nvstream.http.VibepolloClient
import com.ineedmypills.vibemis.nvstream.input.KeyboardPacket
import com.ineedmypills.vibemis.nvstream.input.MouseButtonPacket
import com.ineedmypills.vibemis.nvstream.jni.VibemisBridge
import com.ineedmypills.vibemis.perf.CpuWarmUp
import com.ineedmypills.vibemis.preferences.GlPreferences
import com.ineedmypills.vibemis.profiles.ProfilesManager
import com.ineedmypills.vibemis.ui.CompositorHeartbeat
import com.ineedmypills.vibemis.ui.ExternalControllerView
import com.ineedmypills.vibemis.ui.GameGestures
import com.ineedmypills.vibemis.ui.StreamContainer
import com.ineedmypills.vibemis.utils.Dialog
import com.ineedmypills.vibemis.utils.ExternalDisplayControlActivity
import com.ineedmypills.vibemis.utils.FSRSizerInstaller
import com.ineedmypills.vibemis.utils.MouseModeOption
import com.ineedmypills.vibemis.utils.PanZoomHandler
import com.ineedmypills.vibemis.utils.ServerHelper
import com.ineedmypills.vibemis.utils.ShortcutHelper
import com.ineedmypills.vibemis.utils.SpinnerDialog
import com.ineedmypills.vibemis.utils.UiHelper
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.Queue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class Game : AppCompatActivity(), SurfaceHolder.Callback,
    OnGenericMotionListener, OnTouchListener, NvConnectionListener,
    OnSystemUiVisibilityChangeListener, GameGestures, StreamContainer.InputCallbacks,
    ExternalControllerView.InputCallbacks,
    PerfOverlayListener, UsbDriverService.UsbDriverStateListener, View.OnKeyListener {

    interface GameMenuCallbacks {
        fun showMenu(device: GameInputDevice?)
        fun hideMenu()
        fun isMenuOpen(): Boolean
    }

    companion object {
        @JvmField
        var instance: Game? = null

        @Volatile
        private var sHdrStreamActive = false

        @JvmStatic
        fun setHdrStreamActive(active: Boolean) {
            sHdrStreamActive = active
        }

        @JvmStatic
        fun isHdrStreamActive(): Boolean = sHdrStreamActive

        @JvmStatic
        fun updateHdrWindowMode(enable: Boolean) {
            try {
                val inst = instance ?: return
                inst.runOnUiThread {
                    try {
                        inst.window.colorMode = if (enable) {
                            ActivityInfo.COLOR_MODE_HDR
                        } else {
                            ActivityInfo.COLOR_MODE_DEFAULT
                        }
                        VibemisLog.info("Display HDR mode: ${if (enable) "enabled" else "disabled"}")
                    } catch (t: Throwable) {
                        VibemisLog.warning("HDR window mode switch failed: $t")
                    }
                }
            } catch (_: Throwable) {
            }
        }

        @JvmStatic
        fun formatCurrentTime(currentTimeMillis: Long): String {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            return dateFormat.format(Date(currentTimeMillis))
        }

        private const val REFERENCE_HORIZ_RES = 1280
        private const val REFERENCE_VERT_RES = 720

        private const val STYLUS_DOWN_DEAD_ZONE_DELAY = 100
        private const val STYLUS_DOWN_DEAD_ZONE_RADIUS = 20

        private const val STYLUS_UP_DEAD_ZONE_DELAY = 150
        private const val STYLUS_UP_DEAD_ZONE_RADIUS = 50

        private const val THREE_FINGER_TAP_THRESHOLD = 300
        private const val FOUR_FINGER_TAP_THRESHOLD = 300
        private const val FIVE_FINGER_TAP_THRESHOLD = 300

        private const val PERF_OVERLAY_UPDATE_INTERVAL_MS = 100L
        private const val CLICK_ACTION_THRESHOLD = 5f
        private const val UTF8_CHUNK_SIZE = 512

        const val EXTRA_HOST = "Host"
        const val EXTRA_PORT = "Port"
        const val EXTRA_HTTPS_PORT = "HttpsPort"
        const val EXTRA_APP_NAME = "AppName"
        const val EXTRA_APP_UUID = "AppUUID"
        const val EXTRA_APP_ID = "AppId"
        const val EXTRA_UNIQUEID = "UniqueId"
        const val EXTRA_PC_UUID = "UUID"
        const val EXTRA_PC_NAME = "PcName"
        const val EXTRA_APP_HDR = "HDR"
        const val EXTRA_SERVER_CERT = "ServerCert"
        const val EXTRA_VDISPLAY = "VirtualDisplay"
        const val EXTRA_SERVER_COMMANDS = "ServerCommands"
        const val EXTRA_DISPLAY_ID = "DisplayID"

        const val CLIPBOARD_IDENTIFIER = "VibemisStreaming"
    }

    private var lastButtonState = 0

    private val touchContextMap = arrayOfNulls<TouchContext>(2)
    private val trackpadContextMap = arrayOfNulls<TouchContext>(2)
    private lateinit var panZoomHandler: PanZoomHandler
    private var threeFingerDownTime: Long = 0
    private var fourFingerDownTime: Long = 0
    private var fiveFingerDownTime: Long = 0

    private lateinit var timerHandler: Handler

    private lateinit var controllerHandler: ControllerHandler
    private lateinit var keyboardTranslator: KeyboardTranslator
    private var virtualController: VirtualController? = null
    private var keyBoardController: KeyBoardController? = null
    private var keyBoardLayoutController: KeyBoardLayoutController? = null

    private lateinit var prefConfig: PreferenceConfiguration
    private lateinit var tombstonePrefs: SharedPreferences

    private var displayWidth = 0
    private var displayHeight = 0
    private var currentOrientation = 0

    @JvmField
    var conn: NvConnection? = null
    private var spinner: SpinnerDialog? = null
    private var displayedFailureDialog = false
    private val pendingErrorDetails = java.util.concurrent.CopyOnWriteArrayList<String>()
    private var connecting = false

    @JvmField
    var connected = false
    private var autoEnterPip = false
    private var surfaceCreated = false
    private var attemptedConnection = false
    private var suppressPipRefCount = 0
    private var pcName: String? = null
    private var appName: String? = null
    private lateinit var app: NvApp
    private var desiredRefreshRate = 0f
    private var lastAppliedSurfaceFrameRate = -1f

    private lateinit var inputCaptureProvider: InputCaptureProvider
    private var modifierFlags = 0
    private var grabbedInput = true
    private var cursorVisible = false
    private var isPanZoomMode = false
    private var synthClickPending = false
    private var pointerSwiping = false
    private var waitingForAllModifiersUp = false
    private var specialKeyCode = KeyEvent.KEYCODE_UNKNOWN

    private var fsrSizer: FSRSizerInstaller.AutoCloser? = null
    private lateinit var streamContainer: StreamContainer
    private var synthTouchDownTime: Long = 0

    private var pendingDrag = false
    private var isDragging = false
    private var lastTouchDownX = 0f
    private var lastTouchDownY = 0f

    private var lastAbsTouchUpTime: Long = 0
    private var lastAbsTouchDownTime: Long = 0
    private var lastAbsTouchUpX = 0f
    private var lastAbsTouchUpY = 0f
    private var lastAbsTouchDownX = 0f
    private var lastAbsTouchDownY = 0f

    private var quitOnStop = false
    @Volatile
    private var sleepAfterQuit = false
    private var isHidingOverlays = false
    private var floatingButtonShown = false
    private var overlayToggleZoomButtonShown = false
    private lateinit var notificationOverlayView: TextView
    private var requestedNotificationOverlayVisibility = View.GONE
    private lateinit var performanceOverlayView: View
    private var performanceOverlayLite: TextView? = null
    private var performanceOverlayMini: TextView? = null
    private var performanceOverlayBig: TextView? = null
    private var compositorHeartbeat: CompositorHeartbeat? = null

    @Volatile
    private var pendingPerfOverlayText: String? = null
    private var lastAppliedPerfOverlayText: String? = null
    private val perfOverlayUpdateScheduled = AtomicBoolean(false)

    private val perfOverlayApplyRunnable = Runnable {
        perfOverlayUpdateScheduled.set(false)
        if (!connected || !prefConfig.enablePerfOverlay) {
            pendingPerfOverlayText = null
            lastAppliedPerfOverlayText = null
            return@Runnable
        }
        if (performanceOverlayView.visibility != View.VISIBLE) {
            return@Runnable
        }
        val text = pendingPerfOverlayText ?: return@Runnable
        if (text == lastAppliedPerfOverlayText) {
            return@Runnable
        }
        lastAppliedPerfOverlayText = text

        try {
            when {
                prefConfig.enablePerfOverlayLite -> {
                    if (performanceOverlayLite?.visibility == View.VISIBLE) {
                        performanceOverlayLite?.text = text
                    }
                }
                prefConfig.enablePerfOverlayMini -> {
                    if (performanceOverlayMini?.visibility == View.VISIBLE) {
                        performanceOverlayMini?.text = text
                    }
                }
                else -> {
                    if (performanceOverlayBig?.visibility == View.VISIBLE) {
                        performanceOverlayBig?.text = text
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private lateinit var decoderRenderer: MediaCodecDecoderRenderer
    private var reportedCrash = false

    private var highPerfWifiLock: WifiManager.WifiLock? = null
    private var lowLatencyWifiLock: WifiManager.WifiLock? = null

    private var connectedToUsbDriverService = false
    private val usbDriverServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
            val binder = iBinder as UsbDriverService.UsbDriverBinder
            binder.setListener(controllerHandler)
            binder.setStateListener(this@Game)
            binder.start()
            connectedToUsbDriverService = true
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            connectedToUsbDriverService = false
        }
    }

    private var appUUID: String? = null
    private var host: String? = null
    private var port = 0
    private var httpsPort = 0
    private var appId = 0
    private var uniqueId: String? = null
    private var serverCert: X509Certificate? = null
    private var vDisplay = false
    private var serverCommands: ArrayList<String>? = null

    private lateinit var rootView: ViewParent
    private lateinit var clipboardHelper: GameClipboardHelper
    private lateinit var touchPenHelper: GameTouchPenHelper
    private var httpConn: NvHTTP? = null

    @JvmField
    var gameMenuCallbacks: GameMenuCallbacks? = null

    @JvmField
    var isInputOnly = true

    @JvmField
    var allowChangeMouseMode = true
    private var onExternalDisplay = false
    private var floatingMenuButton: ImageButton? = null
    private var overlayToggleButton: ImageButton? = null
    private var floatingButtonDX = 0f
    private var floatingButtonDY = 0f
    private var isButtonMoving = false
    private var floatingButtonStartX = 0f
    private var floatingButtonStartY = 0f

    private var zoomButtonDX = 0f
    private var zoomButtonDY = 0f
    private var isZoomButtonMoving = false
    private var zoomButtonStartX = 0f
    private var zoomButtonStartY = 0f

    private val commitTextQueue: Queue<String> = ArrayDeque()
    private val commitTextHandler = Handler(Looper.getMainLooper())

    private val flushCommitTextQueue = object : Runnable {
        override fun run() {
            if (commitTextQueue.isEmpty()) return
            val chunk = commitTextQueue.poll()
            if (chunk != null) {
                conn?.sendUtf8Text(chunk)
            }
            if (!commitTextQueue.isEmpty()) {
                commitTextHandler.postDelayed(this, 15)
            }
        }
    }

    private val backgroundPing = object : Runnable {
        override fun run() {
            if (connected) {
                timerHandler.postDelayed(this, 20)
                VibemisBridge.sendEmptyPayload()
            }
        }
    }

    @SuppressLint("MissingInflatedId", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        instance = this
        timerHandler = Handler(Looper.getMainLooper())

        UiHelper.setLocale(this)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        prefConfig = PreferenceConfiguration.readPreferences(this)
        tombstonePrefs = getSharedPreferences("DecoderTombstone", 0)

        if (prefConfig.fullScreen) {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        @Suppress("DEPRECATION")
        window.decorView.setOnSystemUiVisibilityChangeListener(this)
        volumeControlStream = AudioManager.STREAM_MUSIC

        setContentView(R.layout.activity_game)

        val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardHelper = GameClipboardHelper(this, clipboardManager)

        spinner = SpinnerDialog.displayDialog(
            this,
            resources.getString(R.string.conn_establishing_title),
            resources.getString(R.string.conn_establishing_msg),
            true
        )

        val displayId = intent.getIntExtra(EXTRA_DISPLAY_ID, Display.DEFAULT_DISPLAY)
        val currentDisplay = getSystemService(DisplayManager::class.java).getDisplay(displayId)
            ?: @Suppress("DEPRECATION") windowManager.defaultDisplay

        onExternalDisplay = currentDisplay.displayId != Display.DEFAULT_DISPLAY

        val nativeDisplaySize = Point()
        val currentMode = currentDisplay.mode
        if (currentMode != null) {
            nativeDisplaySize.x = currentMode.physicalWidth
            nativeDisplaySize.y = currentMode.physicalHeight
        } else {
            @Suppress("DEPRECATION")
            currentDisplay.getRealSize(nativeDisplaySize)
        }

        var shouldInvertDecoderResolution = false

        if (onExternalDisplay && currentMode != null) {
            displayWidth = currentMode.physicalWidth
            displayHeight = currentMode.physicalHeight
            prefConfig.width = displayWidth
            prefConfig.height = displayHeight
            prefConfig.fps = currentMode.refreshRate
            prefConfig.videoScaleMode = PreferenceConfiguration.ScaleMode.STRETCH
            prefConfig.enableFloatingButton = false
            prefConfig.showOverlayZoomToggleButton = false
            prefConfig.enablePip = false
            currentOrientation = Configuration.ORIENTATION_LANDSCAPE
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        } else {
            currentOrientation = if (prefConfig.autoOrientation) {
                resources.configuration.orientation
            } else {
                Configuration.ORIENTATION_LANDSCAPE
            }

            val portraitMode = currentOrientation == Configuration.ORIENTATION_PORTRAIT
            shouldInvertDecoderResolution = portraitMode && prefConfig.autoInvertVideoResolution

            displayWidth = if (shouldInvertDecoderResolution) prefConfig.height else prefConfig.width
            displayHeight = if (shouldInvertDecoderResolution) prefConfig.width else prefConfig.height

            setPreferredOrientationForActivity()
        }

        if (prefConfig.videoScaleMode == PreferenceConfiguration.ScaleMode.STRETCH ||
            GameDisplayHelper.shouldIgnoreInsetsForResolution(this, prefConfig, displayWidth, displayHeight)
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        cursorVisible = prefConfig.enableMouseLocalCursor

        streamContainer = findViewById(R.id.streamContainer)
        streamContainer.init(this, prefConfig)
        streamContainer.setOnGenericMotionListener(this)
        streamContainer.setOnKeyListener(this)
        streamContainer.setInputCallbacks(this)
        streamContainer.setCommitTextEnabled(prefConfig.enableCommitText)

        rootView = streamContainer.parent
        touchPenHelper = GameTouchPenHelper(resources, streamContainer, prefConfig)

        if (prefConfig.alignDisplayTopCenter) {
            val params = streamContainer.layoutParams as FrameLayout.LayoutParams
            params.gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
        }

        val backgroundTouchView = findViewById<View>(R.id.backgroundTouchView)
        backgroundTouchView.setOnTouchListener(this)

        panZoomHandler = PanZoomHandler(
            applicationContext,
            this,
            streamContainer.surfaceView!!,
            streamContainer,
            prefConfig
        )

        if (prefConfig.rememberZoomPan) {
            streamContainer.post {
                panZoomHandler.setInitialZoomAndPan(
                    prefConfig.zoomScale,
                    prefConfig.panOffsetX,
                    prefConfig.panOffsetY
                )
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val unbufferedClasses = InputDevice.SOURCE_CLASS_BUTTON or
                InputDevice.SOURCE_CLASS_JOYSTICK or
                InputDevice.SOURCE_CLASS_POINTER or
                InputDevice.SOURCE_CLASS_POSITION or
                InputDevice.SOURCE_CLASS_TRACKBALL
            streamContainer.requestUnbufferedDispatch(unbufferedClasses)
            backgroundTouchView.requestUnbufferedDispatch(unbufferedClasses)
        }

        notificationOverlayView = findViewById(R.id.notificationOverlay)
        performanceOverlayView = findViewById(R.id.performanceOverlay)
        performanceOverlayLite = findViewById(R.id.performanceOverlayLite)
        performanceOverlayMini = findViewById(R.id.performanceOverlayMini)
        performanceOverlayBig = findViewById(R.id.performanceOverlayBig)
        compositorHeartbeat = CompositorHeartbeat(findViewById(R.id.tvCompositorHeartbeat))

        inputCaptureProvider = InputCaptureManager.getInputCaptureProvider(this)

        streamContainer.setOnCapturedPointerListener { view, motionEvent ->
            handleMotionEvent(view, motionEvent)
        }

        val connMgr = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val isMetered = connMgr.isActiveNetworkMetered
        if (isMetered) {
            displayTransientMessage(resources.getString(R.string.conn_metered))
        }

        val wifiMgr = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            @Suppress("DEPRECATION")
            highPerfWifiLock = wifiMgr.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Vibemis High Perf Lock").apply {
                setReferenceCounted(false)
                acquire()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                lowLatencyWifiLock = wifiMgr.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "Vibemis Low Latency Lock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }

        appName = intent.getStringExtra(EXTRA_APP_NAME)
        pcName = intent.getStringExtra(EXTRA_PC_NAME)
        host = intent.getStringExtra(EXTRA_HOST)
        port = intent.getIntExtra(EXTRA_PORT, NvHTTP.DEFAULT_HTTP_PORT)
        httpsPort = intent.getIntExtra(EXTRA_HTTPS_PORT, 0)
        appUUID = intent.getStringExtra(EXTRA_APP_UUID)
        appId = intent.getIntExtra(EXTRA_APP_ID, StreamConfiguration.INVALID_APP_ID)
        uniqueId = intent.getStringExtra(EXTRA_UNIQUEID)
        vDisplay = intent.getBooleanExtra(EXTRA_VDISPLAY, false)
        serverCommands = intent.getStringArrayListExtra(EXTRA_SERVER_COMMANDS)
        val appSupportsHdr = intent.getBooleanExtra(EXTRA_APP_HDR, false)
        val derCertData = intent.getByteArrayExtra(EXTRA_SERVER_CERT)

        app = NvApp(appName ?: "app", appUUID, appId, appSupportsHdr)

        try {
            if (derCertData != null) {
                serverCert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(derCertData)) as X509Certificate
                httpConn = NvHTTP(
                    ComputerDetails.AddressTuple(host ?: "", port),
                    httpsPort,
                    uniqueId ?: "",
                    serverCert,
                    PlatformBinding.getCryptoProvider(this)
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (appId == StreamConfiguration.INVALID_APP_ID) {
            finish()
            return
        }

        val glPrefs = GlPreferences.readPreferences(this)
        MediaCodecHelper.initialize(this, glPrefs.glRenderer)

        var willStreamHdr = false
        if (prefConfig.enableHdr) {
            if (onExternalDisplay) {
                willStreamHdr = true
            } else {
                val hdrCaps = currentDisplay.hdrCapabilities
                if (hdrCaps != null) {
                    @Suppress("DEPRECATION")
                    for (hdrType in hdrCaps.supportedHdrTypes) {
                        if (hdrType == Display.HdrCapabilities.HDR_TYPE_HDR10) {
                            willStreamHdr = true
                            break
                        }
                    }
                }
                if (!willStreamHdr) {
                    displayTransientMessage("Display does not support HDR10")
                }
            }
        }

        try {
            updateHdrWindowMode(willStreamHdr)
        } catch (_: Throwable) {
        }

        if (prefConfig.enablePerfOverlay) {
            performanceOverlayView.visibility = View.VISIBLE
            when {
                prefConfig.enablePerfOverlayLite -> {
                    performanceOverlayLite?.visibility = View.VISIBLE
                    if (prefConfig.enablePerfOverlayLiteDialog) {
                        performanceOverlayLite?.setOnClickListener { showGameMenu(null) }
                    }
                }
                prefConfig.enablePerfOverlayMini -> {
                    performanceOverlayMini?.visibility = View.VISIBLE
                    if (prefConfig.enablePerfOverlayMiniDialog) {
                        performanceOverlayMini?.setOnClickListener { showGameMenu(null) }
                    }
                }
                else -> performanceOverlayBig?.visibility = View.VISIBLE
            }

            if (prefConfig.enablePerfOverlayBottom) {
                val params = performanceOverlayView.layoutParams as FrameLayout.LayoutParams
                params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                performanceOverlayView.layoutParams = params
            }
        }

        decoderRenderer = MediaCodecDecoderRenderer(
            this,
            prefConfig,
            CrashListener {
                tombstonePrefs.edit().putInt("CrashCount", tombstonePrefs.getInt("CrashCount", 0) + 1).commit()
                reportedCrash = true
            },
            tombstonePrefs.getInt("CrashCount", 0),
            connMgr.isActiveNetworkMetered,
            willStreamHdr,
            shouldInvertDecoderResolution,
            glPrefs.glRenderer,
            this
        )

        installGlUpscaleSizerIfNeeded()

        if (willStreamHdr && !decoderRenderer.isHevcMain10Hdr10Supported() && !decoderRenderer.isAv1Main10Supported()) {
            willStreamHdr = false
            displayTransientMessage("Decoder does not support HDR10 profile")
        }

        setHdrStreamActive(willStreamHdr)

        if (prefConfig.videoFormat == PreferenceConfiguration.FormatOption.FORCE_HEVC && !decoderRenderer.isHevcSupported()) {
            displayTransientMessage("No HEVC decoder found")
        }

        if (prefConfig.videoFormat == PreferenceConfiguration.FormatOption.FORCE_AV1 && !decoderRenderer.isAv1Supported()) {
            displayTransientMessage("No AV1 decoder found")
        }

        var supportedVideoFormats = VibemisBridge.VIDEO_FORMAT_H264
        if (decoderRenderer.isHevcSupported()) {
            supportedVideoFormats = supportedVideoFormats or VibemisBridge.VIDEO_FORMAT_H265
            if (willStreamHdr && decoderRenderer.isHevcMain10Hdr10Supported()) {
                supportedVideoFormats = supportedVideoFormats or VibemisBridge.VIDEO_FORMAT_H265_MAIN10
            }
        }
        if (decoderRenderer.isAv1Supported()) {
            supportedVideoFormats = supportedVideoFormats or VibemisBridge.VIDEO_FORMAT_AV1_MAIN8
            if (willStreamHdr && decoderRenderer.isAv1Main10Supported()) {
                supportedVideoFormats = supportedVideoFormats or VibemisBridge.VIDEO_FORMAT_AV1_MAIN10
            }
        }

        var gamepadMask = ControllerHandler.getAttachedControllerMask(this).toInt()
        if (!prefConfig.multiController) {
            gamepadMask = 1
        }
        if (prefConfig.onscreenController) {
            gamepadMask = gamepadMask or 1
        }

        val displayRefreshRate = GameDisplayHelper.prepareDisplayForRendering(
            this,
            prefConfig,
            currentDisplay,
            streamContainer,
            displayWidth,
            displayHeight,
            onExternalDisplay
        )
        desiredRefreshRate = displayRefreshRate
        VibemisLog.info("Display refresh rate: $displayRefreshRate")

        val roundedRefreshRate = displayRefreshRate.roundToInt()
        var chosenFrameRate = prefConfig.fps
        if (prefConfig.framePacing == PreferenceConfiguration.FRAME_PACING_CAP_FPS) {
            if (prefConfig.fps >= roundedRefreshRate) {
                if (prefConfig.fps > roundedRefreshRate + 3) {
                    prefConfig.framePacing = PreferenceConfiguration.FRAME_PACING_BALANCED
                    VibemisLog.info("Using drop mode for FPS > Hz")
                } else if (roundedRefreshRate <= 49) {
                    prefConfig.framePacing = PreferenceConfiguration.FRAME_PACING_BALANCED
                    VibemisLog.info("Bogus refresh rate: $roundedRefreshRate")
                } else {
                    chosenFrameRate = (roundedRefreshRate - 1).toFloat()
                    VibemisLog.info("Adjusting FPS target for screen to $chosenFrameRate")
                }
            }
        }

        if (prefConfig.framePacingWarpFactor > 0) {
            chosenFrameRate *= prefConfig.framePacingWarpFactor
        }

        var resolutionScaleFactor = prefConfig.resolutionScaleFactor
        if (prefConfig.autoResolutionScaleFactor) {
            resolutionScaleFactor = PreferenceConfiguration.calculateAutoResolutionScaleFactor(
                displayWidth,
                displayHeight,
                nativeDisplaySize.x,
                nativeDisplaySize.y
            )
            VibemisLog.info("Auto host resolution scale factor: $resolutionScaleFactor% (stream ${displayWidth}x${displayHeight}, device ${nativeDisplaySize.x}x${nativeDisplaySize.y})")
        }

        val config = StreamConfiguration.Builder()
            .setResolution(displayWidth, displayHeight)
            .setLaunchRefreshRate(prefConfig.fps)
            .setRefreshRate(chosenFrameRate)
            .setVirtualDisplay(vDisplay)
            .setResolutionScaleFactor(resolutionScaleFactor)
            .setApp(app)
            .setEnableUltraLowLatency(prefConfig.enableUltraLowLatency)
            .setBitrate(if (isMetered) prefConfig.meteredBitrate else prefConfig.bitrate)
            .setEnableSops(prefConfig.enableSops)
            .enableLocalAudioPlayback(prefConfig.playHostAudio)
            .setMaxPacketSize(1392)
            .setRemoteConfiguration(StreamConfiguration.STREAM_CFG_AUTO)
            .setSupportedVideoFormats(supportedVideoFormats)
            .setAttachedGamepadMask(gamepadMask)
            .setAttachedPlayStationControllerMask(
                ControllerHandler.getAttachedPlayStationControllerMask(this).toInt()
            )
            .setClientName(Build.MODEL ?: "Android")
            .setClientVrrRequested(
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && (
                    currentDisplay.supportedModes.size > 1 ||
                        prefConfig.framePacing == PreferenceConfiguration.FRAME_PACING_MIN_LATENCY ||
                        prefConfig.framePacing == PreferenceConfiguration.FRAME_PACING_GPU_RAW
                )
            )
            .setClientRefreshRateX100((displayRefreshRate * 100).toInt())
            .setAudioConfiguration(prefConfig.audioConfiguration)
            .setColorSpace(decoderRenderer.getPreferredColorSpace())
            .setColorRange(decoderRenderer.getPreferredColorRange())
            .setPersistGamepadsAfterDisconnect(!prefConfig.multiController)
            .build()

        val activeConn = NvConnection(
            applicationContext,
            ComputerDetails.AddressTuple(host ?: "", port),
            httpsPort,
            uniqueId ?: "",
            config,
            PlatformBinding.getCryptoProvider(this),
            serverCert
        )
        conn = activeConn
        controllerHandler = ControllerHandler(this, activeConn, this, prefConfig)
        keyboardTranslator = KeyboardTranslator(prefConfig)

        val inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        inputManager.registerInputDeviceListener(keyboardTranslator, null)

        for (i in trackpadContextMap.indices) {
            trackpadContextMap[i] = TrackpadContext(
                activeConn,
                i,
                prefConfig.trackpadSwapAxis,
                prefConfig.trackpadSensitivityX,
                prefConfig.trackpadSensitivityY
            )
        }

        if (app.isRemoteInputApp()) {
            isInputOnly = true
            allowChangeMouseMode = false
            applyMouseMode(2)
        } else {
            if (prefConfig.enableFullExDisplay && onExternalDisplay) {
                StartExternalDisplayControlReceiver.requestFocusToExternalDisplayControl(this)
                listenForExternalDisplayRemoval()
            }
            initMouseMode()
        }

        if (prefConfig.onscreenController) {
            if (prefConfig.hideOSCWhenHasGamepad) {
                if (!controllerHandler.hasController()) {
                    initVirtualController()
                }
            } else {
                initVirtualController()
            }
        }

        if (prefConfig.enableKeyboard) {
            initKeyboardController()
        }

        if (!decoderRenderer.isAvcSupported()) {
            spinner?.dismiss()
            spinner = null
            Dialog.displayDialog(
                this,
                resources.getString(R.string.conn_error_title),
                "This device or ROM doesn't support hardware accelerated H.264 playback.",
                true
            )
            return
        }

        streamContainer.setOnSurfaceAvailable {
            if (!attemptedConnection) {
                VibemisLog.info("Surface is available, starting connection...")
                attemptedConnection = true
                VibemisBridge.setVideoSurface(streamContainer.surface)
                val fsrModeInt = when (prefConfig.videoUpscaleMode) {
                    "easu_rcas" -> 2
                    "rcas" -> 1
                    else -> 0
                }
                VibemisBridge.setFsrConfig(
                    prefConfig.videoUpscaleEnable,
                    fsrModeInt,
                    prefConfig.videoUpscalePreset,
                    prefConfig.videoUpscaleSharpness,
                    prefConfig.enableHdr
                )
                decoderRenderer.setRenderTarget(streamContainer.surface)
                activeConn.start(
                    decoderRenderer,
                    this@Game
                )
            }
        }

        gameMenuCallbacks = GameMenu(this)

        floatingMenuButton = findViewById(R.id.floatingMenuButton)
        updateFloatingButtonVisibility(prefConfig.enableBackMenu && prefConfig.enableFloatingButton)
        initFloatingButton()

        overlayToggleButton = findViewById(R.id.overlayToggleZoomButton)
        setupOverlayToggleButton()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupOverlayToggleButton() {
        val btn = overlayToggleButton ?: return
        if (prefConfig.showOverlayZoomToggleButton) {
            btn.visibility = View.VISIBLE
            updateZoomButtonAppearance()
            btn.setOnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        zoomButtonStartX = event.rawX
                        zoomButtonStartY = event.rawY
                        zoomButtonDX = view.x - event.rawX
                        zoomButtonDY = view.y - event.rawY
                        isZoomButtonMoving = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        var newX = event.rawX + zoomButtonDX
                        var newY = event.rawY + zoomButtonDY
                        if (abs(event.rawX - zoomButtonStartX) > CLICK_ACTION_THRESHOLD ||
                            abs(event.rawY - zoomButtonStartY) > CLICK_ACTION_THRESHOLD
                        ) {
                            isZoomButtonMoving = true
                        }
                        newX = max(0f, newX)
                        newY = max(0f, newY)
                        val maxOffsetX = (window.decorView.width - view.width).toFloat()
                        val maxOffsetY = (window.decorView.height - view.height).toFloat()
                        newX = min(newX, maxOffsetX)
                        newY = min(newY, maxOffsetY)
                        view.x = newX
                        view.y = newY
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isZoomButtonMoving) {
                            toggleZoomMode()
                            updateZoomButtonAppearance()
                        }
                        isZoomButtonMoving = false
                        true
                    }
                    else -> false
                }
            }
        } else {
            btn.visibility = View.GONE
        }
    }

    private fun updateZoomButtonAppearance() {
        val btn = overlayToggleButton ?: return
        btn.setBackgroundResource(
            if (isPanZoomMode) R.drawable.floating_menu_button_active
            else R.drawable.floating_menu_button
        )
        btn.alpha = 1.0f
    }

    private fun listenForExternalDisplayRemoval() {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {
                if (ServerHelper.getSecondaryDisplay(baseContext) == null) {
                    handleDisplayRemoved()
                    finish()
                }
            }
            override fun onDisplayChanged(displayId: Int) {}
        }, null)
    }

    private fun handleDisplayRemoved() {
        NotificationManagerCompat.from(baseContext).cancel(ExternalDisplayControlActivity.SECONDARY_SCREEN_NOTIFICATION_ID)
        ExternalDisplayControlActivity.closeExternalDisplayControl()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initFloatingButton() {
        val btn = floatingMenuButton ?: return
        btn.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    floatingButtonStartX = event.rawX
                    floatingButtonStartY = event.rawY
                    floatingButtonDX = view.x - event.rawX
                    floatingButtonDY = view.y - event.rawY
                    isButtonMoving = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    var newX = event.rawX + floatingButtonDX
                    var newY = event.rawY + floatingButtonDY
                    if (abs(event.rawX - floatingButtonStartX) > CLICK_ACTION_THRESHOLD ||
                        abs(event.rawY - floatingButtonStartY) > CLICK_ACTION_THRESHOLD
                    ) {
                        isButtonMoving = true
                    }
                    newX = max(0f, newX)
                    newY = max(0f, newY)
                    val maxOffsetX = (window.decorView.width - view.width).toFloat()
                    val maxOffsetY = (window.decorView.height - view.height).toFloat()
                    newX = min(newX, maxOffsetX)
                    newY = min(newY, maxOffsetY)
                    view.x = newX
                    view.y = newY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isButtonMoving) {
                        showGameMenu(null)
                    }
                    isButtonMoving = false
                    true
                }
                else -> false
            }
        }
    }

    private fun initKeyboardController() {
        val activeConn = conn ?: return
        keyBoardController = KeyBoardController(activeConn, rootView as FrameLayout, this).apply {
            refreshLayout()
            show()
        }
    }

    val isKeyboardLayoutVisible: Boolean
        get() = keyBoardLayoutController?.shown == true

    private fun initVirtualController() {
        virtualController = VirtualController(controllerHandler, rootView as FrameLayout, this).apply {
            refreshLayout()
            show()
        }
    }

    private fun initkeyBoardLayoutController() {
        keyBoardLayoutController = KeyBoardLayoutController(rootView as FrameLayout, this, prefConfig).apply {
            refreshLayout()
            show()
        }
    }

    fun toggleKeyboardController() {
        val kc = keyBoardController
        if (kc == null) {
            initKeyboardController()
            return
        }
        kc.toggleVisibility()
    }

    fun toggleFullKeyboard() {
        if (isOnExternalDisplay) {
            ExternalDisplayControlActivity.toggleFullKeyboard()
            return
        }
        val klc = keyBoardLayoutController
        if (klc == null) {
            initkeyBoardLayoutController()
            return
        }
        klc.toggleVisibility()
    }

    fun toggleVirtualController() {
        val vc = virtualController
        if (vc == null) {
            initVirtualController()
            prefConfig.onscreenController = true
            return
        }
        prefConfig.onscreenController = vc.switchShowHide() != 0
    }

    private fun setPreferredOrientationForActivity() {
        val display = ServerHelper.getActiveDisplay(this, prefConfig)
        if (PreferenceConfiguration.isSquarishScreen(display)) {
            var desiredOrientation = Configuration.ORIENTATION_UNDEFINED
            if (prefConfig.onscreenController) {
                desiredOrientation = Configuration.ORIENTATION_LANDSCAPE
            }
            if (PreferenceConfiguration.isNativeResolution(prefConfig.width, prefConfig.height)) {
                desiredOrientation = if (displayWidth > displayHeight) {
                    Configuration.ORIENTATION_LANDSCAPE
                } else {
                    Configuration.ORIENTATION_PORTRAIT
                }
            }
            requestedOrientation = when (desiredOrientation) {
                Configuration.ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                Configuration.ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                else -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            }
        } else {
            requestedOrientation = if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setPreferredOrientationForActivity()
        virtualController?.refreshLayout()
        keyBoardController?.refreshLayout()
        keyBoardLayoutController?.refreshLayout()

        if (isInPictureInPictureMode) {
            isHidingOverlays = true
            floatingButtonShown = floatingMenuButton?.isShown == true
            if (floatingButtonShown) {
                floatingMenuButton?.visibility = View.GONE
            }
            overlayToggleZoomButtonShown = overlayToggleButton?.isShown == true
            if (overlayToggleZoomButtonShown) {
                overlayToggleButton?.visibility = View.GONE
            }
            virtualController?.hide()
            if (keyBoardController?.shown == true) {
                keyBoardController?.hide(true)
            }
            if (keyBoardLayoutController?.shown == true) {
                keyBoardLayoutController?.hide(true)
            }
            hideGameMenu()
            performanceOverlayView.visibility = View.GONE
            notificationOverlayView.visibility = View.GONE
            controllerHandler.disableSensors()
            UiHelper.notifyStreamEnteringPiP(this)
        } else {
            isHidingOverlays = false
            if (floatingButtonShown) {
                floatingMenuButton?.visibility = View.VISIBLE
            }
            if (overlayToggleZoomButtonShown) {
                overlayToggleButton?.visibility = View.VISIBLE
            }
            virtualController?.show()
            if (keyBoardController?.shown == true) {
                keyBoardController?.show()
            }
            if (keyBoardLayoutController?.shown == true) {
                keyBoardLayoutController?.show()
            }
            if (prefConfig.enablePerfOverlay) {
                performanceOverlayView.visibility = View.VISIBLE
            }
            notificationOverlayView.visibility = requestedNotificationOverlayVisibility
            controllerHandler.enableSensors()
            UiHelper.notifyStreamExitingPiP(this)
        }
    }

    private fun getPictureInPictureParams(autoEnter: Boolean): PictureInPictureParams {
        return GameDisplayHelper.buildPictureInPictureParams(
            prefConfig,
            streamContainer,
            rootView as View,
            appName,
            pcName,
            autoEnter
        )
    }

    fun updatePipAutoEnter() {
        if (!prefConfig.enablePip || isOnExternalDisplay) {
            return
        }
        val autoEnter = connected && suppressPipRefCount == 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setPictureInPictureParams(getPictureInPictureParams(autoEnter))
        } else {
            autoEnterPip = autoEnter
        }
    }

    fun setMetaKeyCaptureState(enabled: Boolean) {
        GameDisplayHelper.setMetaKeyCaptureState(this, enabled)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (autoEnterPip && !isOnExternalDisplay) {
                try {
                    enterPictureInPictureMode(getPictureInPictureParams(false))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.R)
    override fun onPictureInPictureRequested(): Boolean {
        if (autoEnterPip && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            enterPictureInPictureMode(getPictureInPictureParams(false))
        }
        return true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        this.modifierFlags = 0
        inputCaptureProvider.onWindowFocusChanged(hasFocus)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    }

    val isOnExternalDisplay: Boolean
        get() = onExternalDisplay

    private fun installGlUpscaleSizerIfNeeded() {
        if (!prefConfig.videoUpscaleEnable) return
        try {
            val surfaceView = streamContainer.surfaceView ?: return
            fsrSizer = FSRSizerInstaller.installForSurfaceView(
                this,
                surfaceView
            ) { width, height ->
                decoderRenderer.setUpscalerPresentationSizeHint(width, height)
            }.apply { start() }
        } catch (t: Throwable) {
            VibemisLog.warning("Unable to install GlUpscale Surface sizer: $t")
            fsrSizer = null
        }
    }

    private fun getPresentSurfaceOrNull(): Surface? {
        return try {
            val sv = streamContainer.surfaceView ?: return null
            val h = sv.holder ?: return null
            val s = h.surface
            if (s != null && s.isValid) s else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun applySurfaceFrameRateHintIfPossible() {
        lastAppliedSurfaceFrameRate = GameDisplayHelper.applySurfaceFrameRateHintIfPossible(
            prefConfig,
            getPresentSurfaceOrNull(),
            lastAppliedSurfaceFrameRate
        )
    }

    @SuppressLint("InlinedApi")
    private val hideSystemUi = Runnable {
        @Suppress("DEPRECATION")
        if (!prefConfig.fullScreen || isInMultiWindowMode) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    private fun hideSystemUi(delay: Int) {
        val h = window.decorView.handler
        if (h != null) {
            h.removeCallbacks(hideSystemUi)
            h.postDelayed(hideSystemUi, delay.toLong())
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean) {
        @Suppress("DEPRECATION")
        super.onMultiWindowModeChanged(isInMultiWindowMode)
        if (isInMultiWindowMode) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            decoderRenderer.notifyVideoBackground()
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            decoderRenderer.notifyVideoForeground()
        }
        hideSystemUi(50)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        VibemisBridge.stopNdkMotionSensors()
        VibemisBridge.setVideoSurface(null)
        compositorHeartbeat?.stop()
        timerHandler.removeCallbacksAndMessages(null)

        if (prefConfig.enableFullExDisplay) handleDisplayRemoved()

        if (::controllerHandler.isInitialized) {
            controllerHandler.destroy()
        }
        if (::keyboardTranslator.isInitialized) {
            val inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
            inputManager.unregisterInputDeviceListener(keyboardTranslator)
        }

        lowLatencyWifiLock?.release()
        highPerfWifiLock?.release()

        try {
            CpuWarmUp.stop()
        } catch (_: Throwable) {
        }

        if (prefConfig.rememberZoomPan && ::panZoomHandler.isInitialized) {
            val basePrefs = PreferenceConfiguration.getDefaultSharedPreferences(this)
            basePrefs.edit()
                .putFloat("number_zoom_scale", panZoomHandler.scaleFactor)
                .putFloat("number_pan_offset_x", panZoomHandler.childX)
                .putFloat("number_pan_offset_y", panZoomHandler.childY)
                .apply()
        }

        if (connectedToUsbDriverService) {
            unbindService(usbDriverServiceConnection)
        }
        try {
            updateHdrWindowMode(false)
        } catch (_: Throwable) {
        }

        if (::inputCaptureProvider.isInitialized) {
            inputCaptureProvider.destroy()
        }
        if (::streamContainer.isInitialized) {
            streamContainer.onDestroy()
        }
    }

    override fun onPause() {
        if (isFinishing) {
            if (::controllerHandler.isInitialized) {
                controllerHandler.stop()
            }
            setInputGrabState(false)
        }
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        if (prefConfig.enableTvCompositorWorkaround) {
            compositorHeartbeat?.start()
        }
        if (prefConfig.cpuWarmupEnabled) {
            try {
                CpuWarmUp.start(this)
            } catch (_: Throwable) {
            }
        }
        try {
            fsrSizer?.start()
        } catch (_: Throwable) {
        }
    }

    override fun onStop() {
        compositorHeartbeat?.stop()
        try {
            CpuWarmUp.stop()
        } catch (_: Throwable) {
        }
        try {
            fsrSizer?.stop()
        } catch (_: Throwable) {
        }
        super.onStop()

        SpinnerDialog.closeDialogs(this)
        Dialog.closeDialogs()

        virtualController?.hide()
        keyBoardController?.hide()
        keyBoardLayoutController?.hide()

        if (conn != null) {
            val videoFormat = decoderRenderer.activeVideoFormat
            displayedFailureDialog = true
            stopConnection()
            var message: String? = null
            var selectedVideoFormat = ""

            val averageEndToEndLat = decoderRenderer.getAverageEndToEndLatency()
            val averageDecoderLat = decoderRenderer.getAverageDecoderLatency()

            if (averageEndToEndLat > 0) {
                message = "${resources.getString(R.string.conn_client_latency)} $averageEndToEndLat ms"
                if (averageDecoderLat > 0) {
                    message += " (${resources.getString(R.string.conn_client_latency_hw)} $averageDecoderLat ms)"
                }
            } else if (averageDecoderLat > 0) {
                message = "${resources.getString(R.string.conn_hardware_latency)} $averageDecoderLat ms"
            }

            selectedVideoFormat += " ["
            selectedVideoFormat += when {
                (videoFormat and VibemisBridge.VIDEO_FORMAT_MASK_H264) != 0 -> "H.264"
                (videoFormat and VibemisBridge.VIDEO_FORMAT_MASK_H265) != 0 -> "HEVC"
                (videoFormat and VibemisBridge.VIDEO_FORMAT_MASK_AV1) != 0 -> "AV1"
                else -> "UNKNOWN"
            }
            if ((videoFormat and VibemisBridge.VIDEO_FORMAT_MASK_10BIT) != 0) {
                selectedVideoFormat += " HDR"
            }
            selectedVideoFormat += "]"

            if (message != null) {
                message += selectedVideoFormat
                VibemisLog.info(message)
            }

            if (!reportedCrash && tombstonePrefs.getInt("CrashCount", 0) != 0) {
                tombstonePrefs.edit()
                    .putInt("CrashCount", 0)
                    .putInt("LastNotifiedCrashCount", 0)
                    .apply()
            }
        }

        finish()
    }

    private fun setInputGrabState(grab: Boolean) {
        if (grab) {
            inputCaptureProvider.enableCapture()
            if (cursorVisible) {
                inputCaptureProvider.showCursor()
            }
        } else {
            inputCaptureProvider.disableCapture()
        }

        if (GameDisplayHelper.isSamsungDevice()) {
            try {
                setMetaKeyCaptureState(grab)
            } catch (_: Throwable) {
            }
        }
        grabbedInput = grab
    }

    private val toggleGrab = Runnable {
        setInputGrabState(!grabbedInput)
    }

    private fun handleSpecialKeys(androidKeyCode: Int, down: Boolean): Boolean {
        var modifierMask = 0
        var nonModifierKeyCode = KeyEvent.KEYCODE_UNKNOWN

        when (androidKeyCode) {
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> modifierMask = KeyboardPacket.MODIFIER_CTRL.toInt()
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> modifierMask = KeyboardPacket.MODIFIER_SHIFT.toInt()
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> modifierMask = KeyboardPacket.MODIFIER_ALT.toInt()
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> modifierMask = KeyboardPacket.MODIFIER_META.toInt()
            else -> nonModifierKeyCode = androidKeyCode
        }

        if (down) {
            this.modifierFlags = this.modifierFlags or modifierMask
        } else {
            this.modifierFlags = this.modifierFlags and modifierMask.inv()
        }

        if (waitingForAllModifiersUp || specialKeyCode != KeyEvent.KEYCODE_UNKNOWN) {
            if (specialKeyCode == androidKeyCode) {
                return true
            } else if (modifierFlags != 0) {
                return down
            } else {
                when (specialKeyCode) {
                    KeyEvent.KEYCODE_Z -> {
                        window.decorView.handler?.postDelayed(toggleGrab, 250)
                    }
                    KeyEvent.KEYCODE_Q -> finish()
                    KeyEvent.KEYCODE_C -> {
                        if (!grabbedInput) {
                            inputCaptureProvider.enableCapture()
                            grabbedInput = true
                        }
                        cursorVisible = !cursorVisible
                        if (cursorVisible) {
                            inputCaptureProvider.showCursor()
                        } else {
                            inputCaptureProvider.hideCursor()
                        }
                    }
                }
                specialKeyCode = KeyEvent.KEYCODE_UNKNOWN
                waitingForAllModifiersUp = false
            }
        } else if ((modifierFlags and (KeyboardPacket.MODIFIER_CTRL.toInt() or KeyboardPacket.MODIFIER_ALT.toInt() or KeyboardPacket.MODIFIER_SHIFT.toInt())) ==
            (KeyboardPacket.MODIFIER_CTRL.toInt() or KeyboardPacket.MODIFIER_ALT.toInt() or KeyboardPacket.MODIFIER_SHIFT.toInt()) &&
            (down && nonModifierKeyCode != KeyEvent.KEYCODE_UNKNOWN)
        ) {
            return when (androidKeyCode) {
                KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_C -> {
                    specialKeyCode = androidKeyCode
                    waitingForAllModifiersUp = true
                    true
                }
                else -> false
            }
        }
        return false
    }

    private fun getModifierState(event: KeyEvent): Byte {
        var modifier = getModifierState().toInt()
        if (event.isShiftPressed) modifier = modifier or KeyboardPacket.MODIFIER_SHIFT.toInt()
        if (event.isCtrlPressed) modifier = modifier or KeyboardPacket.MODIFIER_CTRL.toInt()
        if (event.isAltPressed) modifier = modifier or KeyboardPacket.MODIFIER_ALT.toInt()
        if (event.isMetaPressed) modifier = modifier or KeyboardPacket.MODIFIER_META.toInt()
        return modifier.toByte()
    }

    private fun getModifierState(): Byte = modifierFlags.toByte()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return handleKeyDown(event) || super.onKeyDown(keyCode, event)
    }

    override fun handleKeyDown(event: KeyEvent): Boolean {
        if ((event.flags and KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) {
            return false
        }
        val deviceId = event.deviceId
        if (prefConfig.ignoreSynthEvents && deviceId <= 0) {
            return false
        }

        val eventSource = event.source
        if ((eventSource == InputDevice.SOURCE_MOUSE || eventSource == InputDevice.SOURCE_MOUSE_RELATIVE) &&
            event.keyCode == KeyEvent.KEYCODE_BACK
        ) {
            if (!prefConfig.mouseNavButtons) {
                conn?.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT)
            }
            return true
        }

        var handled = false
        if (ControllerHandler.isGameControllerDevice(event.device)) {
            handled = controllerHandler.handleButtonDown(event)
        }

        if (!handled) {
            if (handleSpecialKeys(event.keyCode, true)) {
                return true
            }
            if (!grabbedInput) {
                return false
            }

            var translated = keyboardTranslator.translate(event.keyCode, event.scanCode, deviceId)
            if (translated.toInt() == 0) {
                if (prefConfig.backAsMeta && event.keyCode == KeyEvent.KEYCODE_BACK) {
                    translated = 0x5b.toShort()
                } else {
                    val unicodeChar = event.unicodeChar
                    if ((unicodeChar and KeyCharacterMap.COMBINING_ACCENT) == 0 &&
                        (unicodeChar and KeyCharacterMap.COMBINING_ACCENT_MASK) != 0
                    ) {
                        conn?.sendUtf8Text("${unicodeChar.toChar()}")
                        return true
                    }
                    return false
                }
            }

            if (event.repeatCount > 0) {
                return true
            }

            conn?.sendKeyboardInput(
                translated,
                KeyboardPacket.KEY_DOWN,
                getModifierState(event),
                if (keyboardTranslator.hasNormalizedMapping(event.keyCode, deviceId)) 0 else VibemisBridge.SS_KBE_FLAG_NON_NORMALIZED
            )
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return handleKeyUp(event) || super.onKeyUp(keyCode, event)
    }

    override fun handleKeyUp(event: KeyEvent): Boolean {
        if ((event.flags and KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) {
            return false
        }
        val deviceId = event.deviceId
        if (prefConfig.ignoreSynthEvents && deviceId <= 0) {
            return false
        }

        val eventSource = event.source
        if ((eventSource == InputDevice.SOURCE_MOUSE || eventSource == InputDevice.SOURCE_MOUSE_RELATIVE) &&
            event.keyCode == KeyEvent.KEYCODE_BACK
        ) {
            if (!prefConfig.mouseNavButtons) {
                conn?.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
            }
            return true
        }

        var handled = false
        if (ControllerHandler.isGameControllerDevice(event.device)) {
            handled = controllerHandler.handleButtonUp(event)
        }

        if (!handled) {
            if (handleSpecialKeys(event.keyCode, false)) {
                return true
            }
            if (!grabbedInput) {
                return false
            }

            var translated = keyboardTranslator.translate(event.keyCode, event.scanCode, deviceId)
            if (translated.toInt() == 0) {
                if (prefConfig.backAsMeta && event.keyCode == KeyEvent.KEYCODE_BACK) {
                    translated = 0x5b.toShort()
                } else {
                    val unicodeChar = event.unicodeChar
                    return (unicodeChar and KeyCharacterMap.COMBINING_ACCENT) == 0 &&
                        (unicodeChar and KeyCharacterMap.COMBINING_ACCENT_MASK) != 0
                }
            }

            conn?.sendKeyboardInput(
                translated,
                KeyboardPacket.KEY_UP,
                getModifierState(event),
                if (keyboardTranslator.hasNormalizedMapping(event.keyCode, deviceId)) 0 else VibemisBridge.SS_KBE_FLAG_NON_NORMALIZED
            )
        }
        return true
    }

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        return handleKeyMultiple(event) || @Suppress("DEPRECATION") super.onKeyMultiple(keyCode, repeatCount, event)
    }

    fun handleKeyMultiple(event: KeyEvent): Boolean {
        @Suppress("DEPRECATION")
        val chars = event.characters
        if (event.keyCode != KeyEvent.KEYCODE_UNKNOWN || chars == null) {
            return false
        }
        conn?.sendUtf8Text(chars)
        return true
    }

    fun sendKeys(keys: ShortArray) {
        val modifier = byteArrayOf(0)
        for (key in keys) {
            conn?.sendKeyboardInput(key, KeyboardPacket.KEY_DOWN, modifier[0], 0)
            modifier[0] = (modifier[0].toInt() or KeyboardTranslator.getModifier(key).toInt()).toByte()
        }

        Handler(Looper.getMainLooper()).postDelayed({
            for (pos in keys.indices.reversed()) {
                val key = keys[pos]
                modifier[0] = (modifier[0].toInt() and KeyboardTranslator.getModifier(key).toInt().inv()).toByte()
                conn?.sendKeyboardInput(key, KeyboardPacket.KEY_UP, modifier[0], 0)
            }
        }, GameMenu.KEY_UP_DELAY)
    }

    override fun handleFocusChange(hasWindowFocus: Boolean): Boolean {
        if (connected && prefConfig.smartClipboardSync) {
            return if (hasWindowFocus) {
                sendClipboard(false)
            } else {
                getClipboard(0)
            }
        }
        return false
    }

    fun sendClipboard(force: Boolean): Boolean {
        return clipboardHelper.sendClipboard(httpConn, prefConfig, force)
    }

    fun getClipboard(delay: Int): Boolean {
        return clipboardHelper.getClipboard(
            httpConn,
            prefConfig,
            gameMenuCallbacks?.isMenuOpen() == true,
            delay
        )
    }

    private fun getTouchContext(actionIndex: Int, inputContextMap: Array<TouchContext?>): TouchContext? {
        return if (actionIndex < inputContextMap.size) inputContextMap[actionIndex] else null
    }

    override fun toggleKeyboard() {
        if (isOnExternalDisplay) {
            ExternalDisplayControlActivity.toggleKeyboard()
        } else {
            VibemisLog.info("Toggling keyboard overlay")
            val inputManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            @Suppress("DEPRECATION")
            inputManager.toggleSoftInput(0, 0)
        }
    }

    fun handleMotionEvent(view: View?, event: MotionEvent): Boolean {
        if (!grabbedInput) return false
        val deviceId = event.deviceId
        if (prefConfig.ignoreSynthEvents && deviceId <= 0) {
            return false
        }

        val activeConn = conn ?: return false
        val eventSource = event.source
        val deviceSources = event.device?.sources ?: 0

        if ((eventSource and InputDevice.SOURCE_CLASS_JOYSTICK) != 0) {
            if (controllerHandler.handleMotionEvent(event)) {
                return true
            }
        } else if ((deviceSources and InputDevice.SOURCE_CLASS_JOYSTICK) != 0 &&
            controllerHandler.tryHandleTouchpadEvent(event)
        ) {
            return true
        } else if ((eventSource and InputDevice.SOURCE_CLASS_POINTER) != 0 ||
            (eventSource and InputDevice.SOURCE_CLASS_POSITION) != 0 ||
            eventSource == InputDevice.SOURCE_MOUSE_RELATIVE
        ) {
            val hasActionButton = event.actionButton != 0
            if (eventSource == InputDevice.SOURCE_MOUSE ||
                ((eventSource and InputDevice.SOURCE_CLASS_POSITION) != 0 && hasActionButton) ||
                eventSource == InputDevice.SOURCE_MOUSE_RELATIVE ||
                (event.pointerCount >= 1 &&
                    (event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE ||
                        event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
                        event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER)) ||
                eventSource == 12290
            ) {
                var buttonState = event.buttonState
                var changedButtons = buttonState xor lastButtonState

                if ((eventSource and InputDevice.SOURCE_CLASS_POSITION) != 0 &&
                    event.pointerCount == 2 &&
                    event.actionButton == MotionEvent.BUTTON_PRIMARY
                ) {
                    if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS) {
                        buttonState = buttonState or MotionEvent.BUTTON_SECONDARY
                    } else if (event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE) {
                        buttonState = buttonState and MotionEvent.BUTTON_SECONDARY.inv()
                    }
                    buttonState = buttonState and MotionEvent.BUTTON_PRIMARY.inv()
                    buttonState = buttonState or (lastButtonState and MotionEvent.BUTTON_PRIMARY)
                    changedButtons = buttonState xor lastButtonState
                }

                if (!inputCaptureProvider.isCapturingActive()) {
                    return true
                }

                if (inputCaptureProvider.eventHasRelativeMouseAxes(event)) {
                    val deltaX = inputCaptureProvider.getRelativeAxisX(event).toInt().toShort()
                    val deltaY = inputCaptureProvider.getRelativeAxisY(event).toInt().toShort()
                    if (deltaX.toInt() != 0 || deltaY.toInt() != 0) {
                        if (prefConfig.absoluteMouseMode) {
                            activeConn.sendMouseMoveAsMousePosition(
                                deltaX,
                                deltaY,
                                streamContainer.width.toShort(),
                                streamContainer.height.toShort()
                            )
                        } else {
                            activeConn.sendMouseMove(deltaX, deltaY)
                        }
                    }
                } else if ((eventSource and InputDevice.SOURCE_CLASS_POSITION) != 0) {
                    val device = event.device
                    if (device != null) {
                        val xRange = device.getMotionRange(MotionEvent.AXIS_X, eventSource)
                        val yRange = device.getMotionRange(MotionEvent.AXIS_Y, eventSource)
                        if (xRange != null && yRange != null && xRange.min == 0f && yRange.min == 0f) {
                            val xMax = xRange.max.toInt()
                            val yMax = yRange.max.toInt()
                            if (xMax <= Short.MAX_VALUE && yMax <= Short.MAX_VALUE) {
                                activeConn.sendMousePosition(
                                    event.x.toInt().toShort(),
                                    event.y.toInt().toShort(),
                                    xMax.toShort(),
                                    yMax.toShort()
                                )
                            }
                        }
                    }
                } else if (view != null && touchPenHelper.trySendPenEvent(activeConn, view, event)) {
                    return true
                } else if (view != null) {
                    if (event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
                        val eventAction = event.actionMasked
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            event.classification == MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE
                        ) {
                            if (!pointerSwiping) {
                                pointerSwiping = true
                                handleTouchInput(event, trackpadContextMap, false, prefConfig.trackpadSwapAxis, MotionEvent.ACTION_POINTER_DOWN, 1, 2)
                            }
                            return handleTouchInput(event, trackpadContextMap, false, prefConfig.trackpadSwapAxis, MotionEvent.ACTION_MOVE, 1, 2)
                        } else if (pointerSwiping && eventAction == MotionEvent.ACTION_UP) {
                            pointerSwiping = false
                            synthClickPending = false
                            handleTouchInput(event, trackpadContextMap, false, prefConfig.trackpadSwapAxis, MotionEvent.ACTION_POINTER_UP, 1, 2)
                            return true
                        }

                        val dx = event.x - lastTouchDownX
                        val dy = event.y - lastTouchDownY
                        val positionDelta = sqrt((dx * dx + dy * dy).toDouble())

                        if (synthClickPending &&
                            event.eventTime - synthTouchDownTime >= prefConfig.trackpadDragDropThreshold
                        ) {
                            if (positionDelta > 50) {
                                pendingDrag = false
                            } else if (pendingDrag) {
                                pendingDrag = false
                                isDragging = true
                                if (prefConfig.trackpadDragDropVibration) {
                                    @Suppress("DEPRECATION")
                                    val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                                    vibrator.vibrate(VibrationEffect.createOneShot(20, 127))
                                }
                                activeConn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT)
                                return true
                            }
                        }

                        when (eventAction) {
                            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_MOVE -> {
                                updateMousePosition(view, event)
                                return true
                            }
                            MotionEvent.ACTION_HOVER_EXIT, MotionEvent.ACTION_DOWN -> {
                                pendingDrag = true
                                synthClickPending = true
                                lastTouchDownX = event.x
                                lastTouchDownY = event.y
                                synthTouchDownTime = event.eventTime
                                return true
                            }
                            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_UP -> {
                                if (synthClickPending) {
                                    val timeDiff = event.eventTime - synthTouchDownTime
                                    if (eventSource == 12290) {
                                        if (timeDiff < 120) {
                                            activeConn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT)
                                            activeConn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
                                        }
                                    } else {
                                        if (timeDiff < 20) {
                                            activeConn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT)
                                            activeConn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
                                        } else if (timeDiff < 120) {
                                            activeConn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT)
                                            activeConn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
                                        }
                                    }
                                    if (isDragging) {
                                        isDragging = false
                                        activeConn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
                                    }
                                    pendingDrag = false
                                    synthClickPending = false
                                }
                                return true
                            }
                            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                                synthClickPending = false
                            }
                        }
                    } else {
                        updateMousePosition(view, event)
                    }
                }

                if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
                    activeConn.sendMouseHighResScroll((event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 120).toInt().toShort())
                    activeConn.sendMouseHighResHScroll((event.getAxisValue(MotionEvent.AXIS_HSCROLL) * 120).toInt().toShort())
                }

                if ((changedButtons and MotionEvent.BUTTON_PRIMARY) != 0) {
                    if ((buttonState and MotionEvent.BUTTON_PRIMARY) != 0) {
                        activeConn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT)
                    } else {
                        activeConn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
                    }
                }

                if ((changedButtons and (MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_STYLUS_PRIMARY)) != 0) {
                    if ((buttonState and (MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_STYLUS_PRIMARY)) != 0) {
                        activeConn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT)
                    } else {
                        activeConn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
                    }
                }

                if ((changedButtons and (MotionEvent.BUTTON_TERTIARY or MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0) {
                    if ((buttonState and (MotionEvent.BUTTON_TERTIARY or MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0) {
                        activeConn.sendMouseButtonDown(MouseButtonPacket.BUTTON_MIDDLE)
                    } else {
                        activeConn.sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE)
                    }
                }

                if (prefConfig.mouseNavButtons) {
                    if ((changedButtons and MotionEvent.BUTTON_BACK) != 0) {
                        if ((buttonState and MotionEvent.BUTTON_BACK) != 0) {
                            activeConn.sendMouseButtonDown(MouseButtonPacket.BUTTON_X1)
                        } else {
                            activeConn.sendMouseButtonUp(MouseButtonPacket.BUTTON_X1)
                        }
                    }
                    if ((changedButtons and MotionEvent.BUTTON_FORWARD) != 0) {
                        if ((buttonState and MotionEvent.BUTTON_FORWARD) != 0) {
                            activeConn.sendMouseButtonDown(MouseButtonPacket.BUTTON_X2)
                        } else {
                            activeConn.sendMouseButtonUp(MouseButtonPacket.BUTTON_X2)
                        }
                    }
                }

                if (event.pointerCount == 1 && event.actionIndex == 0) {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        when (event.getToolType(0)) {
                            MotionEvent.TOOL_TYPE_STYLUS -> {
                                lastAbsTouchDownTime = event.eventTime
                                lastAbsTouchDownX = event.getX(0)
                                lastAbsTouchDownY = event.getY(0)
                                activeConn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT)
                            }
                            MotionEvent.TOOL_TYPE_ERASER -> {
                                lastAbsTouchDownTime = event.eventTime
                                lastAbsTouchDownX = event.getX(0)
                                lastAbsTouchDownY = event.getY(0)
                                activeConn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT)
                            }
                        }
                    } else if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        when (event.getToolType(0)) {
                            MotionEvent.TOOL_TYPE_STYLUS -> {
                                lastAbsTouchUpTime = event.eventTime
                                lastAbsTouchUpX = event.getX(0)
                                lastAbsTouchUpY = event.getY(0)
                                activeConn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
                            }
                            MotionEvent.TOOL_TYPE_ERASER -> {
                                lastAbsTouchUpTime = event.eventTime
                                lastAbsTouchUpX = event.getX(0)
                                lastAbsTouchUpY = event.getY(0)
                                activeConn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
                            }
                        }
                    }
                }

                lastButtonState = buttonState
            } else {
                if (eventSource == InputDevice.SOURCE_TOUCHPAD) {
                    return handleTouchInput(event, trackpadContextMap, false)
                } else {
                    val vc = virtualController
                    if (vc != null &&
                        (vc.controllerMode == VirtualController.ControllerMode.MoveButtons ||
                            vc.controllerMode == VirtualController.ControllerMode.ResizeButtons)
                    ) {
                        return true
                    }

                    if (isPanZoomMode) {
                        panZoomHandler.handleTouchEvent(event)
                        return true
                    }

                    if (touchContextMap[0] == null) {
                        return true
                    }

                    if (prefConfig.enableMultiTouchGestures || !prefConfig.enableMultiTouchScreen) {
                        val pointerCount = event.pointerCount
                        if (pointerCount > 2) {
                            val eventAction = event.actionMasked
                            if ((eventAction == MotionEvent.ACTION_POINTER_DOWN ||
                                    eventAction == MotionEvent.ACTION_POINTER_UP ||
                                    eventAction == MotionEvent.ACTION_UP) &&
                                view != null &&
                                handleMultiTouchGesture(event, eventAction, pointerCount, view)
                            ) {
                                return true
                            }
                        }
                    }

                    if (prefConfig.enableMultiTouchScreen &&
                        !prefConfig.touchscreenTrackpad &&
                        view != null &&
                        touchPenHelper.trySendTouchEvent(activeConn, view, event)
                    ) {
                        return true
                    }

                    return handleTouchInput(event, touchContextMap, true)
                }
            }
            return true
        }
        return false
    }

    private fun handleTouchInput(
        event: MotionEvent,
        inputContextMap: Array<TouchContext?>,
        isTouchScreen: Boolean
    ): Boolean {
        return handleTouchInput(
            event,
            inputContextMap,
            isTouchScreen,
            false,
            event.actionMasked,
            event.actionIndex,
            event.pointerCount
        )
    }

    private fun handleTouchInput(
        event: MotionEvent,
        inputContextMap: Array<TouchContext?>,
        isTouchScreen: Boolean,
        invertAxis: Boolean,
        eventAction: Int,
        actionIndex: Int,
        pointerCount: Int
    ): Boolean {
        val context = getTouchContext(actionIndex, inputContextMap) ?: return false
        val actualActionIndex = event.actionIndex
        val actualPointerCount = event.pointerCount
        val shouldDuplicateMovement = actualPointerCount < pointerCount

        if (eventAction == MotionEvent.ACTION_MOVE) {
            for (i in 0 until event.historySize) {
                for (aTouchContextMap in inputContextMap) {
                    if (aTouchContextMap != null && aTouchContextMap.getActionIndex() < pointerCount) {
                        val aActionIndex = if (shouldDuplicateMovement) 0 else aTouchContextMap.getActionIndex()
                        var historicalX = event.getHistoricalX(aActionIndex, i).toInt()
                        var historicalY = event.getHistoricalY(aActionIndex, i).toInt()
                        if (isTouchScreen) {
                            val normalizedCoords = touchPenHelper.getNormalizedCoordinates(
                                streamContainer,
                                historicalX.toFloat(),
                                historicalY.toFloat()
                            )
                            historicalX = normalizedCoords[0].toInt()
                            historicalY = normalizedCoords[1].toInt()
                        }
                        if (invertAxis) {
                            aTouchContextMap.touchMoveEvent(historicalY, historicalX, event.getHistoricalEventTime(i))
                        } else {
                            aTouchContextMap.touchMoveEvent(historicalX, historicalY, event.getHistoricalEventTime(i))
                        }
                    }
                }
            }

            for (aTouchContextMap in inputContextMap) {
                if (aTouchContextMap != null && aTouchContextMap.getActionIndex() < pointerCount) {
                    val aActionIndex = if (shouldDuplicateMovement) 0 else aTouchContextMap.getActionIndex()
                    var currentX = event.getX(aActionIndex).toInt()
                    var currentY = event.getY(aActionIndex).toInt()
                    if (isTouchScreen) {
                        val normalizedCoords = touchPenHelper.getNormalizedCoordinates(
                            streamContainer,
                            currentX.toFloat(),
                            currentY.toFloat()
                        )
                        currentX = normalizedCoords[0].toInt()
                        currentY = normalizedCoords[1].toInt()
                    }
                    if (invertAxis) {
                        aTouchContextMap.touchMoveEvent(currentY, currentX, event.eventTime)
                    } else {
                        aTouchContextMap.touchMoveEvent(currentX, currentY, event.eventTime)
                    }
                }
            }
            return true
        }

        var eventX = event.getX(actualActionIndex).toInt()
        var eventY = event.getY(actualActionIndex).toInt()

        if (isTouchScreen) {
            val normalizedCoords = touchPenHelper.getNormalizedCoordinates(
                streamContainer,
                eventX.toFloat(),
                eventY.toFloat()
            )
            eventX = normalizedCoords[0].toInt()
            eventY = normalizedCoords[1].toInt()
        }

        when (eventAction) {
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_DOWN -> {
                for (touchContext in inputContextMap) {
                    touchContext?.setPointerCount(pointerCount)
                }
                context.touchDownEvent(eventX, eventY, event.eventTime, true)
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                if (prefConfig.touchscreenTrackpad) {
                    if (pointerCount == 1 &&
                        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || (event.flags and MotionEvent.FLAG_CANCELED) == 0)
                    ) {
                        val currentEventTime = event.eventTime
                        when {
                            currentEventTime - threeFingerDownTime < THREE_FINGER_TAP_THRESHOLD -> {
                                toggleKeyboard()
                                return true
                            }
                            currentEventTime - fourFingerDownTime < FOUR_FINGER_TAP_THRESHOLD -> {
                                toggleFullKeyboard()
                                return true
                            }
                            currentEventTime - fiveFingerDownTime < FIVE_FINGER_TAP_THRESHOLD -> {
                                if (prefConfig.enableBackMenu) {
                                    showGameMenu(null)
                                }
                                return true
                            }
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && (event.flags and MotionEvent.FLAG_CANCELED) != 0) {
                    context.cancelTouch()
                } else {
                    context.touchUpEvent(eventX, eventY, event.eventTime)
                }

                for (touchContext in inputContextMap) {
                    touchContext?.setPointerCount(pointerCount - 1)
                }
                if (actionIndex == 0 && pointerCount > 1 && !context.isCancelled()) {
                    var pointer1X = event.getX(1).toInt()
                    var pointer1Y = event.getY(1).toInt()
                    if (isTouchScreen) {
                        val normalizedCoords = touchPenHelper.getNormalizedCoordinates(
                            streamContainer,
                            pointer1X.toFloat(),
                            pointer1Y.toFloat()
                        )
                        pointer1X = normalizedCoords[0].toInt()
                        pointer1Y = normalizedCoords[1].toInt()
                    }
                    context.touchDownEvent(pointer1X, pointer1Y, event.eventTime, false)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                for (aTouchContext in inputContextMap) {
                    aTouchContext?.cancelTouch()
                    aTouchContext?.setPointerCount(0)
                }
            }
            else -> return false
        }
        return true
    }

    private fun handleMultiTouchGesture(
        event: MotionEvent,
        eventAction: Int,
        pointerCount: Int,
        view: View
    ): Boolean {
        if (eventAction == MotionEvent.ACTION_POINTER_DOWN) {
            when (pointerCount) {
                3 -> threeFingerDownTime = event.eventTime
                4 -> {
                    threeFingerDownTime = 0
                    fourFingerDownTime = event.eventTime
                }
                5 -> {
                    threeFingerDownTime = 0
                    fourFingerDownTime = 0
                    fiveFingerDownTime = event.eventTime
                }
            }
        }

        when (eventAction) {
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val currentEventTime = event.eventTime
                if (pointerCount >= 5 && fiveFingerDownTime > 0 && currentEventTime - fiveFingerDownTime < FIVE_FINGER_TAP_THRESHOLD) {
                    if (prefConfig.enableBackMenu) {
                        showGameMenu(null)
                    }
                    fiveFingerDownTime = 0
                } else if (pointerCount == 4 && fourFingerDownTime > 0 && currentEventTime - fourFingerDownTime < FOUR_FINGER_TAP_THRESHOLD) {
                    toggleFullKeyboard()
                    fourFingerDownTime = 0
                } else if (pointerCount == 3 && threeFingerDownTime > 0 && currentEventTime - threeFingerDownTime < THREE_FINGER_TAP_THRESHOLD) {
                    toggleKeyboard()
                    threeFingerDownTime = 0
                } else {
                    threeFingerDownTime = 0
                    fourFingerDownTime = 0
                    fiveFingerDownTime = 0
                    cancelStaleTouchState(event, view)
                    return false
                }
            }
            else -> return false
        }

        cancelStaleTouchState(event, view)
        return true
    }

    private fun cancelStaleTouchState(event: MotionEvent, view: View) {
        val cancelEvent = MotionEvent.obtain(event)
        cancelEvent.action = MotionEvent.ACTION_CANCEL
        view.dispatchTouchEvent(cancelEvent)
        cancelEvent.recycle()
        for (aTouchContext in touchContextMap) {
            aTouchContext?.cancelTouch()
            aTouchContext?.setPointerCount(0)
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        return handleMotionEvent(null, event) || super.onGenericMotionEvent(event)
    }

    private fun updateMousePosition(touchedView: View, event: MotionEvent) {
        var eventX: Float
        var eventY: Float
        if (touchedView === streamContainer) {
            eventX = event.getX(0)
            eventY = event.getY(0)
        } else {
            eventX = event.getX(0) - streamContainer.x
            eventY = event.getY(0) - streamContainer.y
        }

        if (event.pointerCount == 1 && event.actionIndex == 0 &&
            (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER ||
                event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS)
        ) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_HOVER_ENTER,
                MotionEvent.ACTION_HOVER_EXIT,
                MotionEvent.ACTION_HOVER_MOVE -> {
                    val dx = eventX - lastAbsTouchUpX
                    val dy = eventY - lastAbsTouchUpY
                    if (event.eventTime - lastAbsTouchUpTime <= STYLUS_UP_DEAD_ZONE_DELAY &&
                        sqrt((dx * dx + dy * dy).toDouble()) <= STYLUS_UP_DEAD_ZONE_RADIUS
                    ) {
                        return
                    }
                }
                MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_UP -> {
                    val dx = eventX - lastAbsTouchDownX
                    val dy = eventY - lastAbsTouchDownY
                    if (event.eventTime - lastAbsTouchDownTime <= STYLUS_DOWN_DEAD_ZONE_DELAY &&
                        sqrt((dx * dx + dy * dy).toDouble()) <= STYLUS_DOWN_DEAD_ZONE_RADIUS
                    ) {
                        return
                    }
                }
            }
        }

        eventX = min(max(eventX, 0f), streamContainer.width.toFloat())
        eventY = min(max(eventY, 0f), streamContainer.height.toFloat())

        conn?.sendMousePosition(
            eventX.toInt().toShort(),
            eventY.toInt().toShort(),
            streamContainer.width.toShort(),
            streamContainer.height.toShort()
        )
    }

    override fun onGenericMotion(view: View, event: MotionEvent): Boolean {
        return handleMotionEvent(view, event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(view: View, event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            view.requestUnbufferedDispatch(event)
        }
        return handleMotionEvent(view, event)
    }

    override fun stageStarting(stage: String) {
        runOnUiThread {
            spinner?.setMessage("${resources.getString(R.string.conn_starting)} $stage")
        }
    }

    override fun stageComplete(stage: String) {}

    private fun stopConnection() {
        if (connecting || connected) {
            connecting = false
            connected = false
            updatePipAutoEnter()

            controllerHandler.stop()
            UiHelper.notifyStreamEnded(this)

            Thread {
                conn?.stop()
                val activeHttp = httpConn
                if (activeHttp != null && quitOnStop) {
                    try {
                        Thread.sleep(1000)
                        activeHttp.quitApp()
                        VibemisLog.info("${resources.getString(R.string.applist_quit_success)} $appName")
                        if (sleepAfterQuit) {
                            Thread.sleep(400)
                            val sleepAccepted = activeHttp.requestHostSleep()
                            VibemisLog.info("Vibepollo host sleep after quit: accepted=$sleepAccepted")
                        }
                    } catch (e: Exception) {
                        VibemisLog.warning("Failed to quit app on stop: ${e.message}")
                    }
                }
            }.start()
        }
    }

    private fun buildConnectionDiagnostics(stage: String, errorCode: Int, portFlags: Int, portTestResult: Int): String {
        return buildString {
            appendLine("Хост: ${intent.getStringExtra(EXTRA_HOST) ?: "?"}:${intent.getIntExtra(EXTRA_PORT, NvHTTP.DEFAULT_HTTP_PORT)} (HTTPS ${intent.getIntExtra(EXTRA_HTTPS_PORT, 47984)})")
            if (!pcName.isNullOrBlank()) {
                appendLine("ПК: $pcName")
            }
            appendLine("Приложение: ${appName ?: "?"} (ID: ${if (::app.isInitialized) app.appId else 0})")
            appendLine("Этап: $stage")
            val hexCode = "0x${Integer.toHexString(errorCode).uppercase(Locale.US)}"
            appendLine("Код ошибки: $errorCode ($hexCode)")
            appendLine("Поток: ${prefConfig.width}x${prefConfig.height} @ ${prefConfig.fps} fps, ${prefConfig.bitrate} kbps")
            appendLine("Кодек: ${prefConfig.videoFormat}, HDR: ${prefConfig.enableHdr}, FSR: ${prefConfig.videoUpscaleEnable}, VDisplay: ${intent.getBooleanExtra(EXTRA_VDISPLAY, false)}")
            if (pendingErrorDetails.isNotEmpty()) {
                appendLine("Ответ сервера: ${pendingErrorDetails.distinct().joinToString("; ")}")
            }
            if (portFlags != 0) {
                appendLine("Порты: ${VibemisBridge.stringifyPortFlags(portFlags, ", ")} (тест: $portTestResult)")
            }
        }.trim()
    }

    override fun stageFailed(stage: String, portFlags: Int, errorCode: Int): Boolean {
        val portTestResult = VibemisBridge.testClientConnectivity(
            host ?: "",
            port,
            portFlags and (VibemisBridge.VB_PORT_FLAG_TCP_47984 or VibemisBridge.VB_PORT_FLAG_TCP_47989)
        )
        if (errorCode == 0 && portFlags != 0 && (portTestResult == VibemisBridge.VB_TEST_RESULT_INCONCLUSIVE || portTestResult == 0)) {
            spinner?.setMessage(resources.getString(R.string.unlocking_or_starting))
            return true
        }

        runOnUiThread {
            spinner?.dismiss()
            spinner = null

            if (!displayedFailureDialog) {
                displayedFailureDialog = true
                VibemisLog.severe("$stage failed: $errorCode")

                val currentSurface = streamContainer.surface
                if (stage.contains("video") && currentSurface != null && currentSurface.isValid) {
                    pendingErrorDetails.add(resources.getString(R.string.video_decoder_init_failed))
                }

                var dialogText = getString(R.string.conn_error_stage_code_fmt, resources.getString(R.string.conn_error_msg), stage, errorCode)
                val knownExplanation = when (errorCode) {
                    403 -> "${resources.getString(R.string.error_msg_permission_denied)} (${resources.getString(R.string.permission_launch_app)})"
                    503 -> getString(R.string.conn_error_explain_503)
                    -2 -> if (stage.contains("RTSP", ignoreCase = true)) {
                        getString(R.string.conn_error_explain_rtsp_timeout)
                    } else null
                    -4 -> if (stage.contains("RTSP", ignoreCase = true)) {
                        getString(R.string.conn_error_explain_rtsp_decrypt)
                    } else null
                    -5 -> if (stage.contains("RTSP", ignoreCase = true)) {
                        getString(R.string.conn_error_explain_rtsp_malformed)
                    } else null
                    -408 -> resources.getString(R.string.error_msg_timeout)
                    else -> null
                }
                if (knownExplanation != null) {
                    dialogText += "\n\n$knownExplanation"
                } else if (pendingErrorDetails.isNotEmpty()) {
                    dialogText += "\n\n" + pendingErrorDetails.distinct().joinToString("\n")
                }
                if (portFlags != 0) {
                    dialogText += "\n\n${resources.getString(R.string.check_ports_msg)}\n${VibemisBridge.stringifyPortFlags(portFlags, "\n")}"
                }
                if (portTestResult != VibemisBridge.VB_TEST_RESULT_INCONCLUSIVE && portTestResult != 0) {
                    dialogText += "\n\n${resources.getString(R.string.nettest_text_blocked)}"
                }

                val diagnostics = buildConnectionDiagnostics(stage, errorCode, portFlags, portTestResult)
                Dialog.displayDetailedErrorDialog(
                    this@Game,
                    resources.getString(R.string.conn_error_title),
                    dialogText,
                    diagnostics,
                    true
                )
                finishSecondScreen()
            }
        }
        return false
    }

    private fun finishSecondScreen() {
        if (prefConfig.enableFullExDisplay) {
            Handler(Looper.getMainLooper()).postDelayed({ finish() }, 2000)
        }
    }

    override fun connectionTerminated(errorCode: Int) {
        val portFlags = VibemisBridge.getPortFlagsFromTerminationErrorCode(errorCode)
        val portTestResult = VibemisBridge.testClientConnectivity(
            host ?: "",
            port,
            portFlags and (VibemisBridge.VB_PORT_FLAG_TCP_47984 or VibemisBridge.VB_PORT_FLAG_TCP_47989)
        )

        runOnUiThread {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controllerHandler.stop()
            timerHandler.removeCallbacksAndMessages(null)
            setInputGrabState(false)

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    getPresentSurfaceOrNull()?.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                } else {
                    val lp = window.attributes
                    lp.preferredRefreshRate = 0f
                    window.attributes = lp
                }
                lastAppliedSurfaceFrameRate = -1f
            } catch (_: Throwable) {
            }

            if (!displayedFailureDialog) {
                displayedFailureDialog = true
                VibemisLog.severe("Connection terminated: $errorCode")
                stopConnection()

                if (errorCode != VibemisBridge.VB_ERROR_GRACEFUL_TERMINATION) {
                    var message: String = if (portTestResult != VibemisBridge.VB_TEST_RESULT_INCONCLUSIVE && portTestResult != 0) {
                        resources.getString(R.string.nettest_text_blocked)
                    } else {
                        when (errorCode) {
                            VibemisBridge.VB_ERROR_NO_VIDEO_TRAFFIC -> resources.getString(R.string.no_video_received_error)
                            VibemisBridge.VB_ERROR_NO_VIDEO_FRAME -> resources.getString(R.string.no_frame_received_error)
                            VibemisBridge.VB_ERROR_UNEXPECTED_EARLY_TERMINATION,
                            VibemisBridge.VB_ERROR_PROTECTED_CONTENT -> resources.getString(R.string.early_termination_error)
                            VibemisBridge.VB_ERROR_FRAME_CONVERSION -> resources.getString(R.string.frame_conversion_error)
                            else -> {
                                val errorCodeString = if (abs(errorCode) > 1000) {
                                    Integer.toHexString(errorCode)
                                } else {
                                    errorCode.toString()
                                }
                                "${resources.getString(R.string.conn_terminated_msg)}\n\n${resources.getString(R.string.error_code_prefix)} $errorCodeString"
                            }
                        }
                    }

                    if (pendingErrorDetails.isNotEmpty()) {
                        message += "\n\n" + pendingErrorDetails.distinct().joinToString("\n")
                    }
                    if (portFlags != 0) {
                        message += "\n\n${resources.getString(R.string.check_ports_msg)}\n${VibemisBridge.stringifyPortFlags(portFlags, "\n")}"
                    }

                    val diagnostics = buildConnectionDiagnostics("Active Stream", errorCode, portFlags, portTestResult)
                    Dialog.displayDetailedErrorDialog(
                        this@Game,
                        resources.getString(R.string.conn_terminated_title),
                        message,
                        diagnostics,
                        true
                    )
                } else {
                    finish()
                }
            }
        }
    }

    override fun connectionStatusUpdate(connectionStatus: Int) {
        runOnUiThread {
            if (prefConfig.disableWarnings) return@runOnUiThread

            if (connectionStatus == VibemisBridge.CONN_STATUS_POOR) {
                notificationOverlayView.text = if (prefConfig.bitrate > 5000) {
                    resources.getString(R.string.slow_connection_msg)
                } else {
                    resources.getString(R.string.poor_connection_msg)
                }
                requestedNotificationOverlayVisibility = View.VISIBLE
            } else if (connectionStatus == VibemisBridge.CONN_STATUS_OKAY) {
                requestedNotificationOverlayVisibility = View.GONE
            }

            if (!isHidingOverlays) {
                notificationOverlayView.visibility = requestedNotificationOverlayVisibility
            }
        }
    }

    override fun connectionStarted() {
        runOnUiThread {
            spinner?.dismiss()
            spinner = null

            connected = true
            connecting = false
            updatePipAutoEnter()
            applySurfaceFrameRateHintIfPossible()

            timerHandler.postDelayed({ setInputGrabState(true) }, 500)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            UiHelper.notifyStreamConnected(this@Game)
            handleFocusChange(true)
            setupOverlayToggleButton()
            hideSystemUi(1000)

            if (prefConfig.preventPacketLoss) {
                timerHandler.postDelayed(backgroundPing, 1000)
            }
        }

        try {
            VibemisBridge.pinHotThreadsToBigCores()
        } catch (_: Throwable) {
        }

        val launchWarning = conn?.lastLaunchClientWarning ?: httpConn?.lastLaunchClientWarning
        if (!launchWarning.isNullOrBlank()) {
            val warningText = if (launchWarning == "virtual_display_disabled_by_client") {
                getString(R.string.vibepollo_warning_vdisplay_disabled)
            } else {
                launchWarning
            }
            displayTransientMessage(warningText)
        }

        if (prefConfig.usbDriver) {
            bindService(
                Intent(this, UsbDriverService::class.java),
                usbDriverServiceConnection,
                Service.BIND_AUTO_CREATE
            )
        }

        val computer = ComputerDetails().apply {
            name = pcName ?: ""
            uuid = intent.getStringExtra(EXTRA_PC_UUID) ?: ""
        }
        val shortcutHelper = ShortcutHelper(this)
        shortcutHelper.reportComputerShortcutUsed(computer)
        if (appName != null) {
            shortcutHelper.reportGameLaunched(computer, app)
        }
    }

    override fun displayMessage(message: String?) {
        if (!message.isNullOrBlank()) {
            pendingErrorDetails.add(message)
            VibemisLog.warning("Connection message: $message")
        }
    }

    override fun displayTransientMessage(message: String?) {
        if (!prefConfig.disableWarnings && !message.isNullOrBlank()) {
            showTransientOverlayMessage(message)
        }
    }

    fun showTransientOverlayMessage(message: String?) {
        if (message.isNullOrBlank()) return
        runOnUiThread {
            if (::notificationOverlayView.isInitialized) {
                notificationOverlayView.text = message
                requestedNotificationOverlayVisibility = View.VISIBLE
                if (!isHidingOverlays) {
                    notificationOverlayView.visibility = View.VISIBLE
                }
                timerHandler.postDelayed({
                    if (notificationOverlayView.text == message) {
                        requestedNotificationOverlayVisibility = View.GONE
                        notificationOverlayView.visibility = View.GONE
                    }
                }, 3500)
            }
        }
    }

    override fun rumble(controllerNumber: Short, lowFreqMotor: Short, highFreqMotor: Short) {
        if (prefConfig.enableRumble) {
            VibemisLog.info(String.format(null as Locale?, "Rumble on gamepad %d: %04x %04x", controllerNumber, lowFreqMotor, highFreqMotor))
            controllerHandler.handleRumble(controllerNumber, lowFreqMotor, highFreqMotor)
        }
    }

    override fun rumbleTriggers(controllerNumber: Short, leftTrigger: Short, rightTrigger: Short) {
        VibemisLog.info(String.format(null as Locale?, "Rumble on gamepad triggers %d: %04x %04x", controllerNumber, leftTrigger, rightTrigger))
        controllerHandler.handleRumbleTriggers(controllerNumber, leftTrigger, rightTrigger)
    }

    override fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) {
        VibemisLog.info("Display HDR mode: ${if (enabled) "enabled" else "disabled"}")
        decoderRenderer.setHdrMode(enabled, hdrMetadata)
    }

    override fun setMotionEventState(controllerNumber: Short, motionType: Byte, reportRateHz: Short) {
        if (prefConfig.gamepadMotionSensors && (prefConfig.gamepadMotionSensorsFallbackToDevice || prefConfig.forceMotionSensorsFallbackToDevice)) {
            if (reportRateHz > 0) {
                VibemisBridge.startNdkMotionSensors(controllerNumber.toByte(), reportRateHz)
            } else {
                VibemisBridge.stopNdkMotionSensors()
            }
        }
        controllerHandler.handleSetMotionEventState(controllerNumber, motionType, reportRateHz)
    }

    override fun setControllerLED(controllerNumber: Short, r: Byte, g: Byte, b: Byte) {
        controllerHandler.handleSetControllerLED(controllerNumber, r, g, b)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        check(surfaceCreated) { "Surface changed before creation!" }
        VibemisLog.info("surfaceChanged-->$width x $height ----$displayWidth x $displayHeight")
        panZoomHandler.handleSurfaceChange()
        if (!isInPictureInPictureMode) {
            updatePipAutoEnter()
        }
        applySurfaceFrameRateHintIfPossible()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceCreated = true
        applySurfaceFrameRateHintIfPossible()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        check(surfaceCreated) { "Surface destroyed before creation!" }
        if (attemptedConnection) {
            decoderRenderer.prepareForStop()
            if (connected) {
                stopConnection()
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onSystemUiVisibilityChange(visibility: Int) {
        if (!connected) return
        if ((visibility and View.SYSTEM_UI_FLAG_FULLSCREEN) == 0 ||
            (visibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0
        ) {
            hideSystemUi(2000)
        }
    }

    override fun onPerfUpdate(text: String) {
        if (!connected || !prefConfig.enablePerfOverlay) return
        pendingPerfOverlayText = text
        if (perfOverlayUpdateScheduled.compareAndSet(false, true)) {
            timerHandler.postDelayed(perfOverlayApplyRunnable, PERF_OVERLAY_UPDATE_INTERVAL_MS)
        }
    }

    override fun onUsbPermissionPromptStarting() {
        suppressPipRefCount++
        updatePipAutoEnter()
    }

    override fun onUsbPermissionPromptCompleted() {
        suppressPipRefCount--
        updatePipAutoEnter()
    }

    override fun onKey(view: View?, keyCode: Int, keyEvent: KeyEvent): Boolean {
        return when (keyEvent.action) {
            KeyEvent.ACTION_DOWN -> handleKeyDown(keyEvent)
            KeyEvent.ACTION_UP -> handleKeyUp(keyEvent)
            KeyEvent.ACTION_MULTIPLE -> handleKeyMultiple(keyEvent)
            else -> false
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (prefConfig.enableBackMenu) {
            showGameMenu(null)
            return
        }
        super.onBackPressed()
    }

    fun sendExecServerCmd(cmdId: Int) {
        conn?.sendExecServerCmd(cmdId)
    }

    val serverCmds: ArrayList<String>
        get() = serverCommands ?: arrayListOf()

    val isZoomModeEnabled: Boolean
        get() = isPanZoomMode

    fun toggleZoomMode() {
        this.isPanZoomMode = !this.isPanZoomMode
        if (this.isPanZoomMode) {
            showTransientOverlayMessage(getString(R.string.pan_zoom_mode_enabled))
        } else {
            showTransientOverlayMessage(getString(R.string.pan_zoom_mode_disabled))
        }
        updateZoomButtonAppearance()
        ExternalDisplayControlActivity.instance?.toggleZoomMode(false)
    }

    fun rotateScreen() {
        if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            currentOrientation = Configuration.ORIENTATION_PORTRAIT
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        } else {
            currentOrientation = Configuration.ORIENTATION_LANDSCAPE
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        }
    }

    private fun initMouseMode() {
        val mouseModes = resources.getStringArray(R.array.mouse_mode_names)
        val savedMouseModeIndexStr = ProfilesManager.getInstance()
            .getOverlayingSharedPreferences(this)
            .getString("mouse_mode_list", "0") ?: "0"

        val savedMouseModeIndex = savedMouseModeIndexStr.toIntOrNull() ?: 0
        val savedMouseModeString = if (savedMouseModeIndex in mouseModes.indices) {
            mouseModes[savedMouseModeIndex]
        } else {
            null
        }

        val natural = getString(R.string.mouse_mode_track_pad_natural)
        val gaming = getString(R.string.mouse_mode_track_pad_gaming)
        val disabled = getString(R.string.mouse_mode_disabled)

        var naturalIndex = 2
        for (i in mouseModes.indices) {
            if (mouseModes[i] == natural) {
                naturalIndex = i
                break
            }
        }

        if (isOnExternalDisplay) {
            if (savedMouseModeString != null &&
                (savedMouseModeString == natural || savedMouseModeString == gaming || savedMouseModeString == disabled)
            ) {
                applyMouseMode(savedMouseModeIndex)
            } else {
                applyMouseMode(naturalIndex)
            }
        } else {
            applyMouseMode(savedMouseModeIndex)
        }
    }

    fun selectMouseMode(context: Context) {
        val allModes = resources.getStringArray(R.array.mouse_mode_names)
        val allowedLabels = setOf(
            getString(R.string.mouse_mode_track_pad_natural),
            getString(R.string.mouse_mode_track_pad_gaming),
            getString(R.string.mouse_mode_disabled)
        )

        val options = ArrayList<MouseModeOption>()
        for (i in allModes.indices) {
            val label = allModes[i]
            val isAllowed = !isOnExternalDisplay || allowedLabels.contains(label)
            if (isAllowed) {
                options.add(MouseModeOption(i, label))
            }
        }
        options.add(MouseModeOption(-1, getString(R.string.toggle_local_mouse_cursor)))

        val labels = Array(options.size) { options[it].label }
        val optionArray = options.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle(getString(R.string.game_menu_select_mouse_mode))
            .setItems(labels) { dialog, which ->
                dialog.dismiss()
                val selected = optionArray[which]
                if (selected.index == -1) {
                    toggleMouseLocalCursor()
                } else {
                    applyMouseMode(selected.index)
                    if (prefConfig.rememberMouseMode) {
                        ProfilesManager.getInstance().getOverlayingSharedPreferences(this)
                            .edit()
                            .putString("mouse_mode_list", selected.index.toString())
                            .apply()
                    }
                }
            }
            .create()
            .show()
    }

    private fun toggleMouseLocalCursor() {
        if (!grabbedInput) {
            inputCaptureProvider.enableCapture()
            grabbedInput = true
        }
        cursorVisible = !cursorVisible
        if (cursorVisible) {
            inputCaptureProvider.showCursor()
        } else {
            inputCaptureProvider.hideCursor()
        }
    }

    private fun applyMouseMode(mode: Int) {
        val activeConn = conn ?: return
        when (mode) {
            0 -> {
                prefConfig.enableMultiTouchScreen = true
                prefConfig.touchscreenTrackpad = false
            }
            1, 5 -> {
                prefConfig.enableMultiTouchScreen = false
                prefConfig.touchscreenTrackpad = false
            }
            2, 3 -> {
                prefConfig.enableMultiTouchScreen = false
                prefConfig.touchscreenTrackpad = true
            }
            4 -> {}
        }

        for (i in touchContextMap.indices) {
            touchContextMap[i]?.cancelTouch()
            touchContextMap[i] = when {
                mode == 4 -> null
                !prefConfig.touchscreenTrackpad -> AbsoluteTouchContext(activeConn, i, streamContainer, mode == 5)
                mode == 3 -> RelativeTouchContext(activeConn, i, REFERENCE_HORIZ_RES, REFERENCE_VERT_RES, streamContainer, prefConfig)
                else -> TrackpadContext(activeConn, i)
            }
        }

        isPanZoomMode = false
        updateZoomButtonAppearance()
    }

    fun toggleHUD() {
        prefConfig.enablePerfOverlay = !prefConfig.enablePerfOverlay
        if (prefConfig.enablePerfOverlay) {
            performanceOverlayView.visibility = View.VISIBLE
            when {
                prefConfig.enablePerfOverlayLite -> performanceOverlayLite?.visibility = View.VISIBLE
                prefConfig.enablePerfOverlayMini -> performanceOverlayMini?.visibility = View.VISIBLE
                else -> performanceOverlayBig?.visibility = View.VISIBLE
            }
        } else {
            performanceOverlayView.visibility = View.GONE
        }
    }

    fun switchTouchSensitivity() {
        prefConfig.enableTouchSensitivity = !prefConfig.enableTouchSensitivity
    }

    fun disconnect() {
        if (prefConfig.smartClipboardSync) {
            getClipboard(-1)
        }
        finish()
    }

    fun fetchVibepolloMetrics(): HardwareMetricsSnapshot? {
        val activeHttp = httpConn ?: return null
        return VibepolloClient.fetchHardwareMetrics(activeHttp)
    }

    fun quitAndSleepHost() {
        val context: Context = if (isOnExternalDisplay && ExternalDisplayControlActivity.instance != null) {
            ExternalDisplayControlActivity.instance!!
        } else {
            this
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.vibepollo_action_quit_sleep)
            .setMessage(R.string.game_dialog_message_quit_confirm)
            .setPositiveButton(getString(R.string.yes)) { dialog, _ ->
                quitOnStop = true
                sleepAfterQuit = true
                dialog.dismiss()
                finish()
            }
            .setNegativeButton(getString(R.string.no)) { dialog, _ -> dialog.dismiss() }
            .create()
            .show()
    }

    fun quit() {
        val context: Context = if (isOnExternalDisplay && ExternalDisplayControlActivity.instance != null) {
            ExternalDisplayControlActivity.instance!!
        } else {
            this
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.game_dialog_title_quit_confirm)
            .setMessage(R.string.game_dialog_message_quit_confirm)
            .setPositiveButton(getString(R.string.yes)) { dialog, _ ->
                quitOnStop = true
                dialog.dismiss()
                finish()
            }
            .setNegativeButton(getString(R.string.no)) { dialog, _ -> dialog.dismiss() }
            .create()
            .show()
    }

    override fun showGameMenu(device: GameInputDevice?) {
        if (isOnExternalDisplay) {
            ExternalDisplayControlActivity.toggleGameMenu()
        } else {
            gameMenuCallbacks?.showMenu(device)
        }
    }

    fun hideGameMenu() {
        gameMenuCallbacks?.hideMenu()
    }

    private fun updateFloatingButtonVisibility(show: Boolean) {
        floatingMenuButton?.visibility = if (show) View.VISIBLE else View.GONE
    }

    fun toggleFloatingButtonVisibility() {
        val btn = floatingMenuButton ?: return
        updateFloatingButtonVisibility(btn.visibility == View.GONE)
    }

    override fun handleCommitText(text: CharSequence): Boolean {
        if (!prefConfig.enableCommitText || conn == null) {
            return false
        }
        enqueueCommitText(text.toString())
        return true
    }

    override fun handleDeleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val activeConn = conn
        if (!prefConfig.enableCommitText || activeConn == null) {
            return false
        }
        if (beforeLength > 0) {
            val backspaceCode = keyboardTranslator.translate(KeyEvent.KEYCODE_DEL, 0, -1)
            for (i in 0 until beforeLength) {
                activeConn.sendKeyboardInput(backspaceCode, KeyboardPacket.KEY_DOWN, 0, 0)
                activeConn.sendKeyboardInput(backspaceCode, KeyboardPacket.KEY_UP, 0, 0)
            }
        }
        return true
    }

    private fun enqueueCommitText(text: String?) {
        if (text.isNullOrEmpty()) return
        val utf8 = text.toByteArray(StandardCharsets.UTF_8)
        var offset = 0
        while (offset < utf8.size) {
            var end = min(offset + UTF8_CHUNK_SIZE, utf8.size)
            while (end < utf8.size && (utf8[end].toInt() and 0xC0) == 0x80) {
                end--
            }
            val chunk = String(utf8, offset, end - offset, StandardCharsets.UTF_8)
            commitTextQueue.add(chunk)
            offset = end
        }
        if (commitTextQueue.size == 1) {
            commitTextHandler.post(flushCommitTextQueue)
        }
    }

    fun mouseMove(deltaX: Int, deltaY: Int) {
        conn?.sendMouseMove(deltaX.toShort(), deltaY.toShort())
    }

    fun mouseButtonEvent(buttonId: Int, down: Boolean) {
        val activeConn = conn ?: return
        val button = buttonId.toByte()
        if (down) {
            activeConn.sendMouseButtonDown(button)
        } else {
            activeConn.sendMouseButtonUp(button)
        }
    }
}
