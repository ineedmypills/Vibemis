package com.ineedmypills.vibemis.preferences

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.view.Display
import com.ineedmypills.vibemis.nvstream.jni.VibemisBridge
import com.ineedmypills.vibemis.profiles.ProfilesManager
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class PreferenceConfiguration {
    @JvmField var snappyInput: Boolean = true
    @JvmField var immediateFrameDelivery: Boolean = false
    @JvmField var asyncDecodeEnabled: Boolean = true
    @JvmField var asyncPreferLowerDelays: Boolean = DEFAULT_ASYNC_PREFER_LOWER_DELAYS
    @JvmField var syncLfrEnabled: Boolean = false
    @JvmField var nvidiaLatencyRecoveryEnabled: Boolean = DEFAULT_NVIDIA_LATENCY_RECOVERY
    @JvmField var cpuWarmupEnabled: Boolean = DEFAULT_CPU_WARMUP_ENABLED
    @JvmField var cpuWarmupMode: String = DEFAULT_CPU_WARMUP_MODE
    @JvmField var cpuWarmupThreads: Int = DEFAULT_CPU_WARMUP_THREADS
    @JvmField var cpuWarmupDuty: Int = DEFAULT_CPU_WARMUP_DUTY
    @JvmField var decoderOutputDequeueTimeoutCustom: Boolean = false
    @JvmField var decoderOutputDequeueTimeoutUs: Int = 50000

    @JvmField var videoUpscaleEnable: Boolean = false
    @JvmField var gpuPathMode: Boolean = false
    @JvmField var videoUpscaleMode: String = DEFAULT_VIDEO_UPSCALE_MODE
    @JvmField var videoUpscaleSharpness: Int = DEFAULT_VIDEO_UPSCALE_SHARP
    @JvmField var videoUpscalePreset: Int = DEFAULT_VIDEO_UPSCALE_PRESET

    enum class ScaleMode {
        FIT,
        FILL,
        STRETCH
    }

    enum class FormatOption {
        AUTO,
        FORCE_AV1,
        FORCE_HEVC,
        FORCE_H264
    }

    enum class AnalogStickForScrolling {
        NONE,
        RIGHT,
        LEFT
    }

    @JvmField var width: Int = 1280
    @JvmField var height: Int = 720
    @JvmField var bitrate: Int = 10000
    @JvmField var fps: Float = 60f
    @JvmField var enableUltraLowLatency: Boolean = false
    @JvmField var meteredBitrate: Int = 0
    @JvmField var videoFormat: FormatOption = FormatOption.AUTO
    @JvmField var framePacingWarpFactor: Int = 0
    @JvmField var deadzonePercentage: Int = DEFAULT_DEADZONE
    @JvmField var oscOpacity: Int = DEFAULT_OPACITY
    @JvmField var oscKeyboardOpacity: Int = DEFAULT_OPACITY
    @JvmField var onscreenKeyboardHeight: Int = 200
    @JvmField var onscreenKeyboardAutoFitDisabled: Boolean = false
    @JvmField var onscreenKeyboardWidth: Int = 1000
    @JvmField var onscreenKeyboardAlignMode: String = DEFAULT_ONSCREEN_KEYBOARD_ALIGN_MODE
    @JvmField var enforceDisplayMode: Boolean = false
    @JvmField var useVirtualDisplay: Boolean = false
    @JvmField var enableSops: Boolean = true
    @JvmField var playHostAudio: Boolean = false
    @JvmField var disableWarnings: Boolean = false
    @JvmField var fullScreen: Boolean = true
    @JvmField var videoScaleMode: ScaleMode = ScaleMode.FIT
    @JvmField var language: String = DEFAULT_LANGUAGE
    @JvmField var smallIconMode: Boolean = false
    @JvmField var multiController: Boolean = true
    @JvmField var usbDriver: Boolean = true
    @JvmField var flipFaceButtons: Boolean = false
    @JvmField var onscreenController: Boolean = false
    @JvmField var hideOSCWhenHasGamepad: Boolean = true
    @JvmField var enableBatteryReport: Boolean = true
    @JvmField var forceQwerty: Boolean = true
    @JvmField var backAsMeta: Boolean = false
    @JvmField var ignoreSynthEvents: Boolean = false
    @JvmField var backAsGuide: Boolean = false
    @JvmField var smartClipboardSync: Boolean = false
    @JvmField var smartClipboardSyncToast: Boolean = true
    @JvmField var hideClipboardContent: Boolean = true
    @JvmField var stickyModifierKey: Boolean = true
    @JvmField var onlyL3R3: Boolean = false
    @JvmField var showGuideButton: Boolean = true
    @JvmField var enableHdr: Boolean = false
    @JvmField var enablePip: Boolean = false
    @JvmField var enablePerfOverlay: Boolean = false
    @JvmField var enableTvCompositorWorkaround: Boolean = false
    @JvmField var enablePerfOverlayLite: Boolean = false
    @JvmField var enablePerfOverlayLiteAdvanced: Boolean = false
    @JvmField var enablePerfOverlayLiteOledShift: Boolean = true
    @JvmField var enablePerfOverlayLiteDialog: Boolean = false
    @JvmField var enablePerfOverlayMini: Boolean = false
    @JvmField var enablePerfOverlayMiniDialog: Boolean = false
    @JvmField var enablePerfOverlayBottom: Boolean = false
    @JvmField var showServerStats: Boolean = false
    @JvmField var enableLatencyToast: Boolean = false
    @JvmField var enableBackMenu: Boolean = true
    @JvmField var enableFloatingButton: Boolean = false
    @JvmField var showOverlayZoomToggleButton: Boolean = false
    @JvmField var autoInvertVideoResolution: Boolean = true
    @JvmField var autoResolutionScaleFactor: Boolean = false
    @JvmField var resolutionScaleFactor: Int = DEFAULT_RESOLUTION_SCALE_FACTOR
    @JvmField var resumeWithoutConfirm: Boolean = false
    @JvmField var autoOrientation: Boolean = false
    @JvmField var enableKeyboard: Boolean = false
    @JvmField var enableJoyConFix: Boolean = false
    @JvmField var enableNewAnalogStick: Boolean = false
    @JvmField var enableFullExDisplay: Boolean = false
    @JvmField var enableVsync: Boolean = false
    @JvmField var fastVsync: Boolean = false
    @JvmField var alignDisplayTopCenter: Boolean = false
    @JvmField var touchSensitivityX: Int = 100
    @JvmField var touchSensitivityY: Int = 100
    @JvmField var touchSensitivityRotationAuto: Boolean = true
    @JvmField var touchSensitivityGlobal: Boolean = false
    @JvmField var enableTouchSensitivity: Boolean = false
    @JvmField var touchPadSensitivity: Int = 100
    @JvmField var touchPadYSensitity: Int = 100
    @JvmField var enableMultiTouchScreen: Boolean = true
    @JvmField var enableMouseLocalCursor: Boolean = false
    @JvmField var enableMultiTouchGestures: Boolean = false
    @JvmField var disableDefaultExtraKeys: Boolean = false
    @JvmField var enableDeviceRumble: Boolean = false
    @JvmField var enableCommitText: Boolean = false
    @JvmField var enableKeyboardVibrate: Boolean = false
    @JvmField var enableKeyboardSquare: Boolean = false
    @JvmField var enableOnScreenStyleOfficial: Boolean = false
    @JvmField var enableNewAnalogStickOpacity: Int = 20
    @JvmField var trackpadSensitivityX: Int = DEFAULT_TRACKPAD_SENSITIVITY_X
    @JvmField var trackpadSensitivityY: Int = DEFAULT_TRACKPAD_SENSITIVITY_Y
    @JvmField var trackpadDragDropVibration: Boolean = false
    @JvmField var trackpadDragDropThreshold: Int = DEFAULT_TRACKPAD_DRAG_DROP_THRESHOLD
    @JvmField var trackpadSwapAxis: Boolean = false
    @JvmField var bindAllUsb: Boolean = false
    @JvmField var mouseEmulation: Boolean = true
    @JvmField var analogStickForScrolling: AnalogStickForScrolling = AnalogStickForScrolling.RIGHT
    @JvmField var mouseNavButtons: Boolean = false
    @JvmField var rememberMouseMode: Boolean = false
    @JvmField var unlockFps: Boolean = false
    @JvmField var vibrateOsc: Boolean = true
    @JvmField var vibrateFallbackToDevice: Boolean = false
    @JvmField var vibrateFallbackToDeviceStrength: Int = 100
    @JvmField var touchscreenTrackpad: Boolean = true
    @JvmField var audioConfiguration: VibemisBridge.AudioConfiguration = VibemisBridge.AUDIO_CONFIGURATION_STEREO
    @JvmField var framePacing: Int = FRAME_PACING_MIN_LATENCY
    @JvmField var absoluteMouseMode: Boolean = false
    @JvmField var enableAudioFx: Boolean = false
    @JvmField var reduceRefreshRate: Boolean = false
    @JvmField var fullRange: Boolean = false
    @JvmField var gamepadMotionSensors: Boolean = true
    @JvmField var gamepadTouchpadAsMouse: Boolean = false
    @JvmField var gamepadMotionSensorsFallbackToDevice: Boolean = false
    @JvmField var forceMotionSensorsFallbackToDevice: Boolean = false
    @JvmField var enableRumble: Boolean = true
    @JvmField var preventPacketLoss: Boolean = false
    @JvmField var rememberZoomPan: Boolean = false
    @JvmField var zoomScale: Float = DEFAULT_ZOOM_SCALE
    @JvmField var panOffsetX: Float = DEFAULT_PAN_OFFSET
    @JvmField var panOffsetY: Float = DEFAULT_PAN_OFFSET

    companion object {
        private const val IMMEDIATE_FRAME_DELIVERY_PREF_STRING = "checkbox_immediate_frame_delivery"
        private const val DEFAULT_IMMEDIATE_FRAME_DELIVERY = false
        private const val ASYNC_DECODE_ENABLED_PREF_STRING = "checkbox_async_decode"
        private const val DEFAULT_ASYNC_DECODE_ENABLED = true
        const val ASYNC_PREFER_LOWER_DELAYS_PREF_STRING = "checkbox_async_prefer_lower_delays"
        private const val DEFAULT_ASYNC_PREFER_LOWER_DELAYS = true
        private const val SYNC_LFR_ENABLED_PREF_STRING = "checkbox_sync_lfr"
        private const val DEFAULT_SYNC_LFR_ENABLED = false
        const val NVIDIA_LATENCY_RECOVERY_PREF_STRING = "checkbox_nvidia_latency_recovery"
        private const val DEFAULT_NVIDIA_LATENCY_RECOVERY = true
        const val CPU_WARMUP_ENABLED_PREF_STRING = "checkbox_cpu_warmup_enabled"
        private const val DEFAULT_CPU_WARMUP_ENABLED = false
        const val CPU_WARMUP_MODE_PREF_STRING = "list_cpu_warmup_mode"
        private const val DEFAULT_CPU_WARMUP_MODE = "big"
        const val CPU_WARMUP_THREADS_PREF_STRING = "seekbar_cpu_warmup_threads"
        private const val DEFAULT_CPU_WARMUP_THREADS = 0
        const val CPU_WARMUP_DUTY_PREF_STRING = "seekbar_cpu_warmup_duty"
        private const val DEFAULT_CPU_WARMUP_DUTY = 25
        private const val DECODER_OUTPUT_DEQUEUE_TIMEOUT_CUSTOM_PREF_STRING = "checkbox_custom_decoder_output_timeout_us"
        private const val DEFAULT_DECODER_OUTPUT_DEQUEUE_TIMEOUT_CUSTOM = false
        private const val DECODER_OUTPUT_DEQUEUE_TIMEOUT_US_PREF_STRING = "seekbar_decoder_output_timeout_us"
        private const val DEFAULT_DECODER_OUTPUT_DEQUEUE_TIMEOUT_US = 50000

        private const val VIDEO_UPSCALE_ENABLE_PREF_STRING = "pref_video_upscale_enable"
        private const val VIDEO_UPSCALE_MODE_PREF_STRING = "pref_video_upscale_mode"
        private const val VIDEO_UPSCALE_SHARP_PREF_STRING = "pref_video_upscale_sharpness"
        private const val VIDEO_UPSCALE_PRESET_PREF_STRING = "pref_video_upscale_preset"

        const val CHECKBOX_GPU_PATH_MODE = "checkbox_gpu_path_mode"

        private const val DEFAULT_VIDEO_UPSCALE_MODE = "rcas"
        private const val DEFAULT_VIDEO_UPSCALE_SHARP = 35
        private const val DEFAULT_VIDEO_UPSCALE_PRESET = 1

        const val AUTO_RESOLUTION_SCALE_FACTOR_PREF_STRING = "checkbox_auto_resolution_scale_factor"

        private const val LEGACY_RES_FPS_PREF_STRING = "list_resolution_fps"
        private const val LEGACY_ENABLE_51_SURROUND_PREF_STRING = "checkbox_51_surround"
        private const val LEGACY_STRETCH_PREF_STRING = "checkbox_stretch_video"
        private const val LEGACY_ENFORCE_REFRESH_RATE_STRING = "checkbox_enforce_refresh_rate"

        const val RESOLUTION_PREF_STRING = "list_resolution"
        const val FPS_PREF_STRING = "list_fps"
        const val BITRATE_PREF_STRING = "seekbar_bitrate_kbps"
        private const val BITRATE_PREF_OLD_STRING = "seekbar_bitrate"
        private const val METERED_BITRATE_PREF_STRING = "seekbar_metered_bitrate_kbps"
        private const val ENABLE_ULTRA_LOW_LATENCY_PREF_STRING = "checkbox_ultra_low_latency"
        private const val SNAPPY_INPUT_PREF_STRING = "checkbox_snappy_input"
        private const val ENFORCE_DISPLAY_MODE_PREF_STRING = "checkbox_enforce_display_mode"
        private const val USE_VIRTUAL_DISPLAY_PREF_STRING = "checkbox_use_virtual_display"
        private const val AUTO_INVERT_VIDEO_RESOLUTION_PREF_STRING = "checkbox_auto_invert_video_resolution"
        private const val RESOLUTION_SCALE_FACTOR_PREF_STRING = "seekbar_resolution_scale_factor"
        private const val RESUME_WITHOUT_CONFIRM_PREF_STRING = "checkbox_resume_without_confirm"
        private const val VIDEO_SCALE_MODE_PREF_STRING = "list_video_scale_mode"
        private const val SOPS_PREF_STRING = "checkbox_enable_sops"
        private const val DISABLE_TOASTS_PREF_STRING = "checkbox_disable_warnings"
        private const val HOST_AUDIO_PREF_STRING = "checkbox_host_audio"
        private const val DEADZONE_PREF_STRING = "seekbar_deadzone"
        private const val OSC_OPACITY_PREF_STRING = "seekbar_osc_opacity"
        private const val LANGUAGE_PREF_STRING = "list_languages"
        private const val SMALL_ICONS_PREF_STRING = "checkbox_small_icon_mode"
        private const val MULTI_CONTROLLER_PREF_STRING = "checkbox_multi_controller"
        const val AUDIO_CONFIG_PREF_STRING = "list_audio_config"
        private const val USB_DRIVER_PREF_SRING = "checkbox_usb_driver"
        private const val VIDEO_FORMAT_PREF_STRING = "video_format"
        private const val ONSCREEN_CONTROLLER_PREF_STRING = "checkbox_show_onscreen_controls"
        private const val CHECKBOX_HIDE_OSC_WHEN_HAS_GAMEPAD = "checkbox_hide_osc_when_has_gamepad"
        private const val ONLY_L3_R3_PREF_STRING = "checkbox_only_show_L3R3"
        private const val SHOW_GUIDE_BUTTON_PREF_STRING = "checkbox_show_guide_button"
        private const val LEGACY_DISABLE_FRAME_DROP_PREF_STRING = "checkbox_disable_frame_drop"
        private const val ENABLE_HDR_PREF_STRING = "checkbox_enable_hdr"
        private const val ENABLE_PIP_PREF_STRING = "checkbox_enable_pip"
        private const val ENABLE_PERF_OVERLAY_STRING = "checkbox_enable_perf_overlay"
        private const val TV_COMPOSITOR_WORKAROUND_PREF_STRING = "checkbox_tv_compositor_workaround"
        private const val BIND_ALL_USB_STRING = "checkbox_usb_bind_all"
        private const val MOUSE_EMULATION_STRING = "checkbox_mouse_emulation"
        private const val REMEMBER_MOUSE_MODE_PREF_STRING = "checkbox_remember_mouse_mode"
        private const val ANALOG_SCROLLING_PREF_STRING = "analog_scrolling"
        private const val MOUSE_NAV_BUTTONS_STRING = "checkbox_mouse_nav_buttons"
        const val UNLOCK_FPS_STRING = "checkbox_unlock_fps"
        private const val VIBRATE_OSC_PREF_STRING = "checkbox_vibrate_osc"
        private const val VIBRATE_FALLBACK_PREF_STRING = "checkbox_vibrate_fallback"
        private const val VIBRATE_FALLBACK_STRENGTH_PREF_STRING = "seekbar_vibrate_fallback_strength"
        private const val FLIP_FACE_BUTTONS_PREF_STRING = "checkbox_flip_face_buttons"
        private const val LATENCY_TOAST_PREF_STRING = "checkbox_enable_post_stream_toast"
        private const val FRAME_PACING_PREF_STRING = "frame_pacing"
        private const val ABSOLUTE_MOUSE_MODE_PREF_STRING = "checkbox_absolute_mouse_mode"
        private const val ENABLE_AUDIO_FX_PREF_STRING = "checkbox_enable_audiofx"
        private const val REDUCE_REFRESH_RATE_PREF_STRING = "checkbox_reduce_refresh_rate"
        private const val FULL_RANGE_PREF_STRING = "checkbox_full_range"
        private const val GAMEPAD_TOUCHPAD_AS_MOUSE_PREF_STRING = "checkbox_gamepad_touchpad_as_mouse"
        private const val GAMEPAD_MOTION_SENSORS_PREF_STRING = "checkbox_gamepad_motion_sensors"
        private const val GAMEPAD_MOTION_FALLBACK_PREF_STRING = "checkbox_gamepad_motion_fallback"
        private const val FORCE_MOTION_SENSORS_FALLBACK_PREF_STRING = "checkbox_force_device_motion"
        private const val FULL_SCREEN_PREF_STRING = "checkbox_full_screen"
        private const val ENABLE_RUMBLE_PREF_STRING = "checkbox_enable_rumble"
        private const val PREVENT_PACKET_LOSS_PREF_STRING = "checkbox_prevent_packet_loss"
        private const val LIST_ONSCREEN_KEYBOARD_ALIGN_MODE = "list_onscreen_keyboard_align_mode"
        private const val CHECKBOX_ENABLE_BATTERY_REPORT = "checkbox_gamepad_enable_battery_report"
        private const val CHECKBOX_FORCE_QWERTY = "checkbox_force_qwerty"
        private const val CHECKBOX_BACK_AS_META = "checkbox_back_as_meta"
        private const val CHECKBOX_IGNORE_SYNTH_EVENTS = "checkbox_ignore_synth_events"
        private const val CHECKBOX_BACK_AS_GUIDE = "checkbox_back_as_guide"
        private const val CHECKBOX_SMART_CLIPBOARD_SYNC = "checkbox_smart_clipboard_sync"
        private const val CHECKBOX_SMART_CLIPBOARD_SYNC_TOAST = "checkbox_smart_clipboard_sync_toast"
        private const val CHECKBOX_HIDE_CLIPBOARD_CONTENT = "checkbox_hide_clipboard_content"
        private const val CHECKBOX_ENABLE_STICKY_MODIFIER_KEY_VIRTUAL_KEYBOARD = "checkbox_enable_sticky_modifier_key_virtual_keyboard"
        private const val CHECKBOX_ENABLE_QUIT_DIALOG = "checkbox_enable_quit_dialog"
        private const val CHECKBOX_ENABLE_FLOATING_BUTTON = "checkbox_enable_floating_button"
        private const val CHECKBOX_SHOW_OVERLAY_ZOOM_TOGGLE_BUTTON = "checkbox_show_overlay_zoom_toggle_button"
        private const val CHECKBOX_AUTO_ORIENTATION = "checkbox_auto_orientation"
        private const val CHECKBOX_ENABLE_KEYBOARD = "checkbox_enable_keyboard"
        private const val CHECKBOX_ENABLE_KEYBOARD_VIBRATE = "checkbox_vibrate_keyboard"
        private const val CHECKBOX_CHECKBOX_ENABLE_ANALOG_STICK_NEW = "checkbox_enable_analog_stick_new"
        private const val SEEKBAR_TOUCH_SENSITIVITY = "seekbar_touch_sensitivity_opacity_x"
        private const val SEEKBAR_TRACKPAD_SENSITIVITY_X = "seekbar_trackpad_sensitivity_x"
        private const val SEEKBAR_TRACKPAD_SENSITIVITY_Y = "seekbar_trackpad_sensitivity_y"
        private const val CHECKBOX_TRACKPAD_DRAG_DROP_VIBRATION = "checkbox_trackpad_drag_drop_vibration"
        private const val SEEKBAR_TRACKPAD_DRAG_DROP_THRESHOLD = "seekbar_trackpad_drag_drop_threshold"
        private const val CHECKBOX_TRACKPAD_SWAP_AXIS = "checkbox_trackpad_swap_axis"
        private const val CHECKBOX_ENABLE_COMMIT_TEXT = "checkbox_enable_commit_text"

        const val DEFAULT_RESOLUTION = "1280x720"
        const val DEFAULT_FPS = "60"
        private const val DEFAULT_ENABLE_ULTRA_LOW_LATENCY = false
        private const val DEFAULT_ENFORCE_DISPLAY_MODE = false
        private const val DEFAULT_USE_VIRTUAL_DISPLAY = false
        private const val DEFAULT_VIDEO_SCALE_MODE = "fit"
        private const val DEFAULT_AUTO_INVERT_VIDEO_RESOLUTION = true
        private const val DEFAULT_AUTO_RESOLUTION_SCALE_FACTOR = false
        private const val DEFAULT_RESOLUTION_SCALE_FACTOR = 100
        private const val DEFAULT_RESUME_WITHOUT_CONFIRM = false
        private const val DEFAULT_SOPS = true
        private const val DEFAULT_DISABLE_TOASTS = false
        private const val DEFAULT_HOST_AUDIO = false
        private const val DEFAULT_DEADZONE = 5
        private const val DEFAULT_OPACITY = 90
        const val DEFAULT_LANGUAGE = "default"
        private const val DEFAULT_MULTI_CONTROLLER = true
        private const val DEFAULT_USB_DRIVER = true
        private const val DEFAULT_VIDEO_FORMAT = "auto"

        private const val DEFAULT_ONSCREEN_CONTROLLER = false
        private const val DEFAULT_HIDE_OSC_WHEN_HAS_GAMEPAD = true
        private const val ONLY_L3_R3_DEFAULT = false
        private const val SHOW_GUIDE_BUTTON_DEFAULT = true
        private const val DEFAULT_SNAPPY_INPUT = false
        private const val DEFAULT_ENABLE_HDR = false
        private const val DEFAULT_ENABLE_PIP = false
        private const val DEFAULT_ENABLE_PERF_OVERLAY = false
        private const val DEFAULT_TV_COMPOSITOR_WORKAROUND = false
        private const val DEFAULT_PERF_OVERLAY_BOTTOM = false
        private const val DEFAULT_BIND_ALL_USB = false
        private const val DEFAULT_MOUSE_EMULATION = true
        private const val DEFAULT_REMEMBER_MOUSE_MODE = false
        private const val DEFAULT_ANALOG_STICK_FOR_SCROLLING = "right"
        private const val DEFAULT_MOUSE_NAV_BUTTONS = false
        private const val DEFAULT_UNLOCK_FPS = false
        private const val DEFAULT_VIBRATE_OSC = true
        private const val DEFAULT_VIBRATE_FALLBACK = false
        private const val DEFAULT_VIBRATE_FALLBACK_STRENGTH = 100
        private const val DEFAULT_FLIP_FACE_BUTTONS = false
        private const val DEFAULT_AUDIO_CONFIG = "2"
        private const val DEFAULT_LATENCY_TOAST = false
        private const val DEFAULT_FRAME_PACING = "latency"
        private const val DEFAULT_ABSOLUTE_MOUSE_MODE = false
        private const val DEFAULT_ENABLE_AUDIO_FX = false
        private const val DEFAULT_REDUCE_REFRESH_RATE = false
        private const val DEFAULT_FULL_RANGE = false
        private const val DEFAULT_GAMEPAD_TOUCHPAD_AS_MOUSE = false
        private const val DEFAULT_GAMEPAD_MOTION_SENSORS = true
        private const val DEFAULT_GAMEPAD_MOTION_FALLBACK = false
        private const val DEFAULT_FORCE_MOTION_SENSORS_FALLBACK = false
        private const val DEFAULT_ENABLE_RUMBLE = true
        private const val DEFAULT_PREVENT_PACKET_LOSS = false
        private const val DEFAULT_GAMEPAD_ENABLE_BATTERY_REPORT = true
        private const val DEFAULT_FORCE_QWERTY = true
        private const val DEFAULT_SEND_META_ON_PHYSICAL_BACK = false
        private const val DEFAULT_IGNORE_SYNTH_EVENTS = false
        private const val DEFAULT_ENABLE_FLOATING_BUTTON = false
        private const val DEFAULT_BACK_AS_GUIDE = false
        private const val DEFAULT_SMART_CLIPBOARD_SYNC = false
        private const val DEFAULT_SMART_CLIPBOARD_SYNC_TOAST = true
        private const val DEFAULT_HIDE_CLIPBOARD_CONTENT = true
        private const val DEFAULT_ENABLE_STICKY_MODIFIER_KEY_VIRTUAL_KEYBOARD = true
        private const val DEFAULT_TRACKPAD_SENSITIVITY_X = 100
        private const val DEFAULT_TRACKPAD_SENSITIVITY_Y = 100
        private const val DEFAULT_TRACKPAD_DRAG_DROP_VIBRATION = false
        private const val DEFAULT_TRACKPAD_DRAG_DROP_THRESHOLD = 250
        private const val DEFAULT_TRACKPAD_SWAP_AXIS = false
        private const val DEFAULT_ENABLE_COMMIT_TEXT = false
        private const val DEFAULT_ONSCREEN_KEYBOARD_ALIGN_MODE = "center"
        private const val DEFAULT_SHOW_OVERLAY_TOGGLE_BUTTON = false
        private const val DEFAULT_REMEMBER_ZOOM_PAN = false
        private const val DEFAULT_ZOOM_SCALE = 1.0f
        private const val DEFAULT_PAN_OFFSET = 0.0f
        private const val DEFAULT_FULL_SCREEN = true

        const val FRAME_PACING_MIN_LATENCY = 0
        const val FRAME_PACING_BALANCED = 1
        const val FRAME_PACING_CAP_FPS = 2
        const val FRAME_PACING_MAX_SMOOTHNESS = 3
        const val FRAME_PACING_GPU_RAW = 4
        const val FRAME_PACING_WARP = 5
        const val FRAME_PACING_WARP2 = 6

        const val RES_360P = "640x360"
        const val RES_480P = "854x480"
        const val RES_720P = "1280x720"
        const val RES_1080P = "1920x1080"
        const val RES_1440P = "2560x1440"
        const val RES_4K = "3840x2160"
        const val RES_NATIVE = "Native"

        private const val CHECKBOX_REMEMBER_ZOOM_PAN = "checkbox_remember_zoom_pan"
        private const val NUMBER_ZOOM_SCALE = "number_zoom_scale"
        private const val NUMBER_PAN_OFFSET_X = "number_pan_offset_x"
        private const val NUMBER_PAN_OFFSET_Y = "number_pan_offset_y"

        private const val PREF_VSYNC = "checkbox_Vsync"
        private const val PREF_FASTVSYNC = "checkbox_fastVsync"

        private fun clampInt(v: Int, minVal: Int, maxVal: Int): Int = v.coerceIn(minVal, maxVal)

        @JvmStatic
        fun calculateAutoResolutionScaleFactor(
            streamWidth: Int,
            streamHeight: Int,
            targetWidth: Int,
            targetHeight: Int
        ): Int {
            if (streamWidth <= 0 || streamHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
                return DEFAULT_RESOLUTION_SCALE_FACTOR
            }
            var tw = targetWidth
            var th = targetHeight
            if ((streamWidth > streamHeight) != (tw > th)) {
                val swap = tw
                tw = th
                th = swap
            }
            val widthFactor = tw / streamWidth.toDouble()
            val heightFactor = th / streamHeight.toDouble()
            val scaleFactor = (max(widthFactor, heightFactor) * 100.0).roundToInt()
            return max(DEFAULT_RESOLUTION_SCALE_FACTOR, scaleFactor)
        }

        @JvmStatic
        fun computeAutoScaleFactor(
            streamWidth: Int,
            streamHeight: Int,
            targetWidth: Int,
            targetHeight: Int
        ): Int = calculateAutoResolutionScaleFactor(streamWidth, streamHeight, targetWidth, targetHeight)

        @JvmStatic
        fun framePacingFromString(value: String?): Int = when (value) {
            "latency" -> FRAME_PACING_MIN_LATENCY
            "balanced" -> FRAME_PACING_BALANCED
            "cap-fps" -> FRAME_PACING_CAP_FPS
            "smoothness" -> FRAME_PACING_MAX_SMOOTHNESS
            "gpu-raw" -> FRAME_PACING_GPU_RAW
            "warp" -> FRAME_PACING_WARP
            "warp2" -> FRAME_PACING_WARP2
            else -> FRAME_PACING_MIN_LATENCY
        }

        private fun getIntOrStringInt(prefs: SharedPreferences, key: String, defaultValue: Int): Int {
            return try {
                prefs.getInt(key, defaultValue)
            } catch (_: ClassCastException) {
                prefs.getString(key, defaultValue.toString())?.toIntOrNull() ?: defaultValue
            }
        }

        @JvmStatic
        fun isNativeResolution(width: Int, height: Int): Boolean {
            return !(
                (width == 640 && height == 360) ||
                (width == 854 && height == 480) ||
                (width == 1280 && height == 720) ||
                (width == 1920 && height == 1080) ||
                (width == 2560 && height == 1440) ||
                (width == 3840 && height == 2160)
            )
        }

        @JvmStatic
        fun isSquarishScreen(width: Int, height: Int): Boolean {
            val longDim = max(width, height).toFloat()
            val shortDim = min(width, height).toFloat()
            return longDim / shortDim < 1.3f
        }

        @JvmStatic
        fun isSquarishScreen(display: Display): Boolean {
            val mode = display.mode
            return isSquarishScreen(mode.physicalWidth, mode.physicalHeight)
        }

        private fun convertFromLegacyResolutionString(resString: String): String {
            return when {
                resString.equals("360p", ignoreCase = true) -> RES_360P
                resString.equals("480p", ignoreCase = true) -> RES_480P
                resString.equals("720p", ignoreCase = true) -> RES_720P
                resString.equals("1080p", ignoreCase = true) -> RES_1080P
                resString.equals("1440p", ignoreCase = true) -> RES_1440P
                resString.equals("4K", ignoreCase = true) -> RES_4K
                else -> RES_720P
            }
        }

        private fun getWidthFromResolutionString(resString: String): Int =
            resString.split("x")[0].toInt()

        private fun getHeightFromResolutionString(resString: String): Int =
            resString.split("x")[1].toInt()

        private fun getResolutionString(width: Int, height: Int): String {
            return when (height) {
                360 -> RES_360P
                480 -> RES_480P
                1080 -> RES_1080P
                1440 -> RES_1440P
                2160 -> RES_4K
                else -> RES_720P
            }
        }

        @JvmStatic
        fun getDefaultBitrate(resString: String, fpsString: String): Int {
            val width = getWidthFromResolutionString(resString)
            val height = getHeightFromResolutionString(resString)
            val fps = fpsString.toFloat().roundToInt()

            val frameRateFactor = (if (fps <= 60) fps.toDouble() else sqrt(fps / 60.0) * 60.0) / 30.0

            val pixelVals = intArrayOf(
                640 * 360,
                854 * 480,
                1280 * 720,
                1920 * 1080,
                2560 * 1440,
                3840 * 2160,
                -1
            )
            val factorVals = intArrayOf(1, 2, 5, 10, 20, 40, -1)

            var resolutionFactor = 1f
            val pixels = width * height
            var i = 0
            while (true) {
                if (pixels == pixelVals[i]) {
                    resolutionFactor = factorVals[i].toFloat()
                    break
                } else if (pixels < pixelVals[i]) {
                    resolutionFactor = if (i == 0) {
                        factorVals[i].toFloat()
                    } else {
                        ((pixels - pixelVals[i - 1]).toFloat() / (pixelVals[i] - pixelVals[i - 1])) *
                            (factorVals[i] - factorVals[i - 1]) + factorVals[i - 1]
                    }
                    break
                } else if (pixelVals[i] == -1) {
                    resolutionFactor = factorVals[i - 1].toFloat()
                    break
                }
                i++
            }

            return (resolutionFactor * frameRateFactor).roundToInt() * 1000
        }

        @JvmStatic
        fun getDefaultSmallMode(context: Context): Boolean {
            val manager = context.packageManager
            if (manager != null) {
                if (manager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
                    return false
                }
            }
            return context.resources.configuration.smallestScreenWidthDp < 500
        }

        @JvmStatic
        fun getDefaultBitrate(context: Context): Int {
            val prefs = ProfilesManager.getInstance().getOverlayingSharedPreferences(context)
            return getDefaultBitrate(
                prefs.getString(RESOLUTION_PREF_STRING, DEFAULT_RESOLUTION) ?: DEFAULT_RESOLUTION,
                prefs.getString(FPS_PREF_STRING, DEFAULT_FPS) ?: DEFAULT_FPS
            )
        }

        private fun getVideoFormatValue(context: Context): FormatOption {
            val prefs = ProfilesManager.getInstance().getOverlayingSharedPreferences(context)
            return when (prefs.getString(VIDEO_FORMAT_PREF_STRING, DEFAULT_VIDEO_FORMAT)) {
                "auto" -> FormatOption.AUTO
                "forceav1" -> FormatOption.FORCE_AV1
                "forceh265" -> FormatOption.FORCE_HEVC
                "neverh265" -> FormatOption.FORCE_H264
                else -> FormatOption.AUTO
            }
        }

        private fun getVideoScaleMode(context: Context): ScaleMode {
            val prefs = ProfilesManager.getInstance().getOverlayingSharedPreferences(context)
            return when (prefs.getString(VIDEO_SCALE_MODE_PREF_STRING, DEFAULT_VIDEO_SCALE_MODE)) {
                "fit" -> ScaleMode.FIT
                "fill" -> ScaleMode.FILL
                "stretch" -> ScaleMode.STRETCH
                else -> ScaleMode.FIT
            }
        }

        @JvmStatic
        fun getDefaultSharedPreferences(context: Context): SharedPreferences =
            context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)

        private fun getUiSharedPreferences(context: Context): SharedPreferences =
            getDefaultSharedPreferences(context)

        private fun getStringPrefOverlayFirst(context: Context, key: String, def: String): String {
            val overlay = ProfilesManager.getInstance().getOverlayingSharedPreferences(context)
            if (overlay.contains(key)) {
                return overlay.getString(key, def) ?: def
            }
            val ui = getUiSharedPreferences(context)
            return ui.getString(key, def) ?: def
        }

        private fun getBooleanPrefOverlayFirst(context: Context, key: String, def: Boolean): Boolean {
            val overlay = ProfilesManager.getInstance().getOverlayingSharedPreferences(context)
            if (overlay.contains(key)) {
                return overlay.getBoolean(key, def)
            }
            val ui = getUiSharedPreferences(context)
            return ui.getBoolean(key, def)
        }

        @JvmStatic
        fun getSelectedFramePacingName(context: Context): String =
            getStringPrefOverlayFirst(context, FRAME_PACING_PREF_STRING, DEFAULT_FRAME_PACING)

        private fun getFramePacingValue(context: Context): Int {
            val overlay = ProfilesManager.getInstance().getOverlayingSharedPreferences(context)
            val ui = getUiSharedPreferences(context)

            if (overlay.contains(LEGACY_DISABLE_FRAME_DROP_PREF_STRING)) {
                val legacyNeverDropFrames = overlay.getBoolean(LEGACY_DISABLE_FRAME_DROP_PREF_STRING, false)
                overlay.edit()
                    .remove(LEGACY_DISABLE_FRAME_DROP_PREF_STRING)
                    .putString(FRAME_PACING_PREF_STRING, if (legacyNeverDropFrames) "balanced" else "latency")
                    .apply()
            } else if (ui.contains(LEGACY_DISABLE_FRAME_DROP_PREF_STRING)) {
                val legacyNeverDropFrames = ui.getBoolean(LEGACY_DISABLE_FRAME_DROP_PREF_STRING, false)
                ui.edit()
                    .remove(LEGACY_DISABLE_FRAME_DROP_PREF_STRING)
                    .putString(FRAME_PACING_PREF_STRING, if (legacyNeverDropFrames) "balanced" else "latency")
                    .apply()
            }

            val src = if (!overlay.contains(FRAME_PACING_PREF_STRING) && ui.contains(FRAME_PACING_PREF_STRING)) {
                ui
            } else {
                overlay
            }

            return framePacingFromString(src.getString(FRAME_PACING_PREF_STRING, DEFAULT_FRAME_PACING))
        }

        private fun getAnalogStickForScrollingValue(context: Context): AnalogStickForScrolling {
            val prefs = ProfilesManager.getInstance().getOverlayingSharedPreferences(context)
            return when (prefs.getString(ANALOG_SCROLLING_PREF_STRING, DEFAULT_ANALOG_STICK_FOR_SCROLLING)) {
                "right" -> AnalogStickForScrolling.RIGHT
                "left" -> AnalogStickForScrolling.LEFT
                else -> AnalogStickForScrolling.NONE
            }
        }

        @JvmStatic
        fun resetStreamingSettings(context: Context) {
            val prefs = ProfilesManager.getInstance().getOverlayingSharedPreferences(context)
            prefs.edit()
                .remove(BITRATE_PREF_STRING)
                .remove(BITRATE_PREF_OLD_STRING)
                .remove(LEGACY_RES_FPS_PREF_STRING)
                .remove(RESOLUTION_PREF_STRING)
                .remove(FPS_PREF_STRING)
                .remove(VIDEO_FORMAT_PREF_STRING)
                .remove(ENABLE_HDR_PREF_STRING)
                .remove(UNLOCK_FPS_STRING)
                .remove(FULL_RANGE_PREF_STRING)
                .apply()
        }

        @JvmStatic
        fun isShieldAtvFirmwareWithBrokenHdr(): Boolean {
            return Build.MANUFACTURER.equals("NVIDIA", ignoreCase = true) &&
                Build.FINGERPRINT.contains("PPR1.180610.011/4079208_2235.1395")
        }

        @JvmStatic
        fun readPreferences(context: Context): PreferenceConfiguration =
            readPreferences(context, null)

        @JvmStatic
        fun readPreferences(context: Context, inputPrefs: SharedPreferences?): PreferenceConfiguration {
            val prefs = inputPrefs ?: ProfilesManager.getInstance().getOverlayingSharedPreferences(context)
            val config = PreferenceConfiguration()

            if (prefs.contains(LEGACY_ENABLE_51_SURROUND_PREF_STRING)) {
                if (prefs.getBoolean(LEGACY_ENABLE_51_SURROUND_PREF_STRING, false)) {
                    prefs.edit()
                        .remove(LEGACY_ENABLE_51_SURROUND_PREF_STRING)
                        .putString(AUDIO_CONFIG_PREF_STRING, "51")
                        .apply()
                }
            }

            val str = prefs.getString(LEGACY_RES_FPS_PREF_STRING, null)
            if (str != null) {
                when (str) {
                    "360p30" -> { config.width = 640; config.height = 360; config.fps = 30f }
                    "360p60" -> { config.width = 640; config.height = 360; config.fps = 60f }
                    "720p30" -> { config.width = 1280; config.height = 720; config.fps = 30f }
                    "720p60" -> { config.width = 1280; config.height = 720; config.fps = 60f }
                    "1080p30" -> { config.width = 1920; config.height = 1080; config.fps = 30f }
                    "1080p60" -> { config.width = 1920; config.height = 1080; config.fps = 60f }
                    "4K30" -> { config.width = 3840; config.height = 2160; config.fps = 30f }
                    "4K60" -> { config.width = 3840; config.height = 2160; config.fps = 60f }
                    else -> { config.width = 1280; config.height = 720; config.fps = 60f }
                }
                prefs.edit()
                    .remove(LEGACY_RES_FPS_PREF_STRING)
                    .putString(RESOLUTION_PREF_STRING, getResolutionString(config.width, config.height))
                    .putString(FPS_PREF_STRING, config.fps.toString())
                    .apply()
            } else {
                var resStr = prefs.getString(RESOLUTION_PREF_STRING, DEFAULT_RESOLUTION) ?: DEFAULT_RESOLUTION
                if (!resStr.contains("x")) {
                    resStr = convertFromLegacyResolutionString(resStr)
                    prefs.edit().putString(RESOLUTION_PREF_STRING, resStr).apply()
                }
                config.width = getWidthFromResolutionString(resStr)
                config.height = getHeightFromResolutionString(resStr)
                config.fps = (prefs.getString(FPS_PREF_STRING, DEFAULT_FPS) ?: DEFAULT_FPS).toFloat()
            }

            if (prefs.contains(LEGACY_STRETCH_PREF_STRING)) {
                val stretch = prefs.getBoolean(LEGACY_STRETCH_PREF_STRING, false)
                prefs.edit()
                    .remove(LEGACY_STRETCH_PREF_STRING)
                    .putString(VIDEO_SCALE_MODE_PREF_STRING, if (stretch) "stretch" else "fit")
                    .apply()
            }

            if (prefs.contains(LEGACY_ENFORCE_REFRESH_RATE_STRING)) {
                val enforce = prefs.getBoolean(LEGACY_ENFORCE_REFRESH_RATE_STRING, false)
                prefs.edit()
                    .remove(LEGACY_ENFORCE_REFRESH_RATE_STRING)
                    .putBoolean(ENFORCE_DISPLAY_MODE_PREF_STRING, enforce)
                    .apply()
            }

            if (!prefs.contains(SMALL_ICONS_PREF_STRING)) {
                prefs.edit().putBoolean(SMALL_ICONS_PREF_STRING, getDefaultSmallMode(context)).apply()
            }

            if (!prefs.contains(GAMEPAD_MOTION_SENSORS_PREF_STRING) && Build.VERSION.SDK_INT == Build.VERSION_CODES.S) {
                prefs.edit().putBoolean(GAMEPAD_MOTION_SENSORS_PREF_STRING, false).apply()
            }

            config.bitrate = prefs.getInt(BITRATE_PREF_STRING, prefs.getInt(BITRATE_PREF_OLD_STRING, 0) * 1000)
            if (config.bitrate == 0) {
                config.bitrate = getDefaultBitrate(context)
            }

            config.meteredBitrate = prefs.getInt(METERED_BITRATE_PREF_STRING, 0)
            if (config.meteredBitrate == 0) {
                config.meteredBitrate = config.bitrate / 4
                prefs.edit().putInt(METERED_BITRATE_PREF_STRING, 0).apply()
            }

            config.audioConfiguration = when (prefs.getString(AUDIO_CONFIG_PREF_STRING, DEFAULT_AUDIO_CONFIG)) {
                "71" -> VibemisBridge.AUDIO_CONFIGURATION_71_SURROUND
                "51" -> VibemisBridge.AUDIO_CONFIGURATION_51_SURROUND
                else -> VibemisBridge.AUDIO_CONFIGURATION_STEREO
            }

            config.videoScaleMode = getVideoScaleMode(context)
            config.videoFormat = getVideoFormatValue(context)
            config.framePacing = getFramePacingValue(context)

            when (prefs.getString(FRAME_PACING_PREF_STRING, "")) {
                "warp" -> config.framePacingWarpFactor = 2
                "warp2" -> config.framePacingWarpFactor = 4
            }

            config.analogStickForScrolling = getAnalogStickForScrollingValue(context)
            config.deadzonePercentage = prefs.getInt(DEADZONE_PREF_STRING, DEFAULT_DEADZONE)
            config.oscOpacity = prefs.getInt(OSC_OPACITY_PREF_STRING, DEFAULT_OPACITY)
            config.language = prefs.getString(LANGUAGE_PREF_STRING, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE

            config.disableWarnings = prefs.getBoolean(DISABLE_TOASTS_PREF_STRING, DEFAULT_DISABLE_TOASTS)
            config.enforceDisplayMode = prefs.getBoolean(ENFORCE_DISPLAY_MODE_PREF_STRING, DEFAULT_ENFORCE_DISPLAY_MODE)
            config.useVirtualDisplay = prefs.getBoolean(USE_VIRTUAL_DISPLAY_PREF_STRING, DEFAULT_USE_VIRTUAL_DISPLAY)
            config.enableUltraLowLatency = prefs.getBoolean(ENABLE_ULTRA_LOW_LATENCY_PREF_STRING, DEFAULT_ENABLE_ULTRA_LOW_LATENCY)
            config.enableSops = prefs.getBoolean(SOPS_PREF_STRING, DEFAULT_SOPS)
            config.playHostAudio = prefs.getBoolean(HOST_AUDIO_PREF_STRING, DEFAULT_HOST_AUDIO)
            config.smallIconMode = prefs.getBoolean(SMALL_ICONS_PREF_STRING, getDefaultSmallMode(context))
            config.multiController = prefs.getBoolean(MULTI_CONTROLLER_PREF_STRING, DEFAULT_MULTI_CONTROLLER)
            config.usbDriver = prefs.getBoolean(USB_DRIVER_PREF_SRING, DEFAULT_USB_DRIVER)
            config.fullScreen = prefs.getBoolean(FULL_SCREEN_PREF_STRING, DEFAULT_FULL_SCREEN)

            val mouseMode = prefs.getString("mouse_mode_list", "0") ?: "0"
            when (mouseMode.toIntOrNull() ?: 0) {
                0 -> {
                    config.enableMultiTouchScreen = true
                    config.touchscreenTrackpad = false
                }
                1, 5 -> {
                    config.enableMultiTouchScreen = false
                    config.touchscreenTrackpad = false
                }
                2, 3 -> {
                    config.enableMultiTouchScreen = false
                    config.touchscreenTrackpad = true
                }
                4 -> {
                    config.enableMultiTouchScreen = false
                    config.touchscreenTrackpad = false
                }
            }

            config.onscreenController = prefs.getBoolean(ONSCREEN_CONTROLLER_PREF_STRING, DEFAULT_ONSCREEN_CONTROLLER)
            config.hideOSCWhenHasGamepad = prefs.getBoolean(CHECKBOX_HIDE_OSC_WHEN_HAS_GAMEPAD, DEFAULT_HIDE_OSC_WHEN_HAS_GAMEPAD)
            config.onlyL3R3 = prefs.getBoolean(ONLY_L3_R3_PREF_STRING, ONLY_L3_R3_DEFAULT)
            config.showGuideButton = prefs.getBoolean(SHOW_GUIDE_BUTTON_PREF_STRING, SHOW_GUIDE_BUTTON_DEFAULT)
            config.enableHdr = prefs.getBoolean(ENABLE_HDR_PREF_STRING, DEFAULT_ENABLE_HDR) && !isShieldAtvFirmwareWithBrokenHdr()
            config.enablePip = prefs.getBoolean(ENABLE_PIP_PREF_STRING, DEFAULT_ENABLE_PIP)
            config.enablePerfOverlay = prefs.getBoolean(ENABLE_PERF_OVERLAY_STRING, DEFAULT_ENABLE_PERF_OVERLAY)
            config.enableTvCompositorWorkaround = prefs.getBoolean(
                TV_COMPOSITOR_WORKAROUND_PREF_STRING,
                DEFAULT_TV_COMPOSITOR_WORKAROUND
            )
            config.enablePerfOverlayLite = prefs.getBoolean("checkbox_enable_perf_overlay_lite", DEFAULT_ENABLE_PERF_OVERLAY)
            config.enablePerfOverlayLiteAdvanced = prefs.getBoolean("checkbox_enable_perf_overlay_lite_advanced", false)
            config.enablePerfOverlayLiteOledShift = prefs.getBoolean("checkbox_enable_perf_overlay_lite_oledshift", true)
            config.enablePerfOverlayMini = prefs.getBoolean("checkbox_enable_perf_overlay_mini", DEFAULT_ENABLE_PERF_OVERLAY)
            config.enablePerfOverlayBottom = prefs.getBoolean("checkbox_enable_perf_overlay_bottom", DEFAULT_PERF_OVERLAY_BOTTOM)
            config.showServerStats = prefs.getBoolean("checkbox_show_server_stats", false)
            config.bindAllUsb = prefs.getBoolean(BIND_ALL_USB_STRING, DEFAULT_BIND_ALL_USB)
            config.mouseEmulation = prefs.getBoolean(MOUSE_EMULATION_STRING, DEFAULT_MOUSE_EMULATION)
            config.mouseNavButtons = prefs.getBoolean(MOUSE_NAV_BUTTONS_STRING, DEFAULT_MOUSE_NAV_BUTTONS)
            config.rememberMouseMode = prefs.getBoolean(REMEMBER_MOUSE_MODE_PREF_STRING, DEFAULT_REMEMBER_MOUSE_MODE)
            config.unlockFps = prefs.getBoolean(UNLOCK_FPS_STRING, DEFAULT_UNLOCK_FPS)
            config.vibrateOsc = prefs.getBoolean(VIBRATE_OSC_PREF_STRING, DEFAULT_VIBRATE_OSC)
            config.vibrateFallbackToDevice = prefs.getBoolean(VIBRATE_FALLBACK_PREF_STRING, DEFAULT_VIBRATE_FALLBACK)
            config.vibrateFallbackToDeviceStrength = prefs.getInt(VIBRATE_FALLBACK_STRENGTH_PREF_STRING, DEFAULT_VIBRATE_FALLBACK_STRENGTH)
            config.flipFaceButtons = prefs.getBoolean(FLIP_FACE_BUTTONS_PREF_STRING, DEFAULT_FLIP_FACE_BUTTONS)
            config.enableLatencyToast = prefs.getBoolean(LATENCY_TOAST_PREF_STRING, DEFAULT_LATENCY_TOAST)
            config.enableBackMenu = prefs.getBoolean(CHECKBOX_ENABLE_QUIT_DIALOG, true)
            config.enableFloatingButton = prefs.getBoolean(CHECKBOX_ENABLE_FLOATING_BUTTON, DEFAULT_ENABLE_FLOATING_BUTTON)
            config.showOverlayZoomToggleButton = prefs.getBoolean(CHECKBOX_SHOW_OVERLAY_ZOOM_TOGGLE_BUTTON, DEFAULT_SHOW_OVERLAY_TOGGLE_BUTTON)
            config.autoOrientation = prefs.getBoolean(CHECKBOX_AUTO_ORIENTATION, false)
            config.autoInvertVideoResolution = prefs.getBoolean(AUTO_INVERT_VIDEO_RESOLUTION_PREF_STRING, DEFAULT_AUTO_INVERT_VIDEO_RESOLUTION)
            config.autoResolutionScaleFactor = prefs.getBoolean(AUTO_RESOLUTION_SCALE_FACTOR_PREF_STRING, DEFAULT_AUTO_RESOLUTION_SCALE_FACTOR)
            config.resolutionScaleFactor = prefs.getInt(RESOLUTION_SCALE_FACTOR_PREF_STRING, DEFAULT_RESOLUTION_SCALE_FACTOR)
            config.resumeWithoutConfirm = prefs.getBoolean(RESUME_WITHOUT_CONFIRM_PREF_STRING, DEFAULT_RESUME_WITHOUT_CONFIRM)
            config.enableKeyboard = prefs.getBoolean(CHECKBOX_ENABLE_KEYBOARD, false)
            config.enableKeyboardVibrate = prefs.getBoolean(CHECKBOX_ENABLE_KEYBOARD_VIBRATE, false)
            config.enableJoyConFix = prefs.getBoolean("checkbox_enable_joyconfix", false)
            config.oscKeyboardOpacity = prefs.getInt("seekbar_keyboard_axi_opacity", DEFAULT_OPACITY)
            config.enableOnScreenStyleOfficial = prefs.getBoolean("checkbox_onscreen_style_official", false)
            config.enableNewAnalogStickOpacity = prefs.getInt("seekbar_osc_free_analog_stick_opacity", 20)
            config.onscreenKeyboardHeight = prefs.getInt("seekbar_onscreen_keyboard_height", 200)
            config.onscreenKeyboardAutoFitDisabled = prefs.getBoolean("onscreen_keyboard_autofit", false)
            config.onscreenKeyboardWidth = prefs.getInt("seekbar_onscreen_keyboard_width", 1000)
            config.onscreenKeyboardAlignMode = prefs.getString(LIST_ONSCREEN_KEYBOARD_ALIGN_MODE, DEFAULT_ONSCREEN_KEYBOARD_ALIGN_MODE)
                ?: DEFAULT_ONSCREEN_KEYBOARD_ALIGN_MODE
            config.enableNewAnalogStick = prefs.getBoolean(CHECKBOX_CHECKBOX_ENABLE_ANALOG_STICK_NEW, false)
            config.enableFullExDisplay = prefs.getBoolean("checkbox_enable_fullexdisplay", false)
            config.alignDisplayTopCenter = prefs.getBoolean("checkbox_enable_view_top_center", false)
            config.touchSensitivityX = prefs.getInt(SEEKBAR_TOUCH_SENSITIVITY, 100)
            config.touchSensitivityY = prefs.getInt("seekbar_touch_sensitivity_opacity_y", 100)
            config.touchSensitivityRotationAuto = prefs.getBoolean("checkbox_enable_touch_sensitivity_rotation_auto", true)
            config.touchSensitivityGlobal = prefs.getBoolean("checkbox_enable_global_touch_sensitivity", false)
            config.enableTouchSensitivity = prefs.getBoolean("checkbox_enable_touch_sensitivity", false)
            config.enableMouseLocalCursor = prefs.getBoolean("checkbox_mouse_local_cursor", false)
            config.enableMultiTouchGestures = prefs.getBoolean("checkbox_multi_touch_gestures", false)
            config.enablePerfOverlayLiteDialog = prefs.getBoolean("checkbox_enable_perf_overlay_lite_dialog", false)
            config.disableDefaultExtraKeys = prefs.getBoolean("checkbox_enable_clear_default_special_button", false)
            config.enableDeviceRumble = prefs.getBoolean("checkbox_enable_device_rumble", false)
            config.enableCommitText = prefs.getBoolean(CHECKBOX_ENABLE_COMMIT_TEXT, DEFAULT_ENABLE_COMMIT_TEXT)
            config.enableKeyboardSquare = prefs.getBoolean("checkbox_enable_keyboard_square", false)
            config.touchPadSensitivity = prefs.getInt("seekbar_touchpad_sensitivity_opacity", 100)
            config.touchPadYSensitity = prefs.getInt("seekbar_touchpad_sensitivity_y_opacity", 100)
            config.trackpadSensitivityX = prefs.getInt(SEEKBAR_TRACKPAD_SENSITIVITY_X, DEFAULT_TRACKPAD_SENSITIVITY_X)
            config.trackpadSensitivityY = prefs.getInt(SEEKBAR_TRACKPAD_SENSITIVITY_Y, DEFAULT_TRACKPAD_SENSITIVITY_Y)
            config.trackpadDragDropVibration = prefs.getBoolean(CHECKBOX_TRACKPAD_DRAG_DROP_VIBRATION, DEFAULT_TRACKPAD_DRAG_DROP_VIBRATION)
            config.trackpadDragDropThreshold = prefs.getInt(SEEKBAR_TRACKPAD_DRAG_DROP_THRESHOLD, DEFAULT_TRACKPAD_DRAG_DROP_THRESHOLD)
            config.trackpadSwapAxis = prefs.getBoolean(CHECKBOX_TRACKPAD_SWAP_AXIS, DEFAULT_TRACKPAD_SWAP_AXIS)

            config.absoluteMouseMode = prefs.getBoolean(ABSOLUTE_MOUSE_MODE_PREF_STRING, DEFAULT_ABSOLUTE_MOUSE_MODE)
            config.enableBatteryReport = prefs.getBoolean(CHECKBOX_ENABLE_BATTERY_REPORT, DEFAULT_GAMEPAD_ENABLE_BATTERY_REPORT)
            config.forceQwerty = prefs.getBoolean(CHECKBOX_FORCE_QWERTY, DEFAULT_FORCE_QWERTY)
            config.backAsMeta = prefs.getBoolean(CHECKBOX_BACK_AS_META, DEFAULT_SEND_META_ON_PHYSICAL_BACK)
            config.ignoreSynthEvents = prefs.getBoolean(CHECKBOX_IGNORE_SYNTH_EVENTS, DEFAULT_IGNORE_SYNTH_EVENTS)
            config.backAsGuide = prefs.getBoolean(CHECKBOX_BACK_AS_GUIDE, DEFAULT_BACK_AS_GUIDE)
            config.smartClipboardSync = prefs.getBoolean(CHECKBOX_SMART_CLIPBOARD_SYNC, DEFAULT_SMART_CLIPBOARD_SYNC)
            config.smartClipboardSyncToast = prefs.getBoolean(CHECKBOX_SMART_CLIPBOARD_SYNC_TOAST, DEFAULT_SMART_CLIPBOARD_SYNC_TOAST)
            config.hideClipboardContent = prefs.getBoolean(CHECKBOX_HIDE_CLIPBOARD_CONTENT, DEFAULT_HIDE_CLIPBOARD_CONTENT)
            config.stickyModifierKey = prefs.getBoolean(CHECKBOX_ENABLE_STICKY_MODIFIER_KEY_VIRTUAL_KEYBOARD, DEFAULT_ENABLE_STICKY_MODIFIER_KEY_VIRTUAL_KEYBOARD)
            config.enableAudioFx = prefs.getBoolean(ENABLE_AUDIO_FX_PREF_STRING, DEFAULT_ENABLE_AUDIO_FX)
            config.reduceRefreshRate = prefs.getBoolean(REDUCE_REFRESH_RATE_PREF_STRING, DEFAULT_REDUCE_REFRESH_RATE)
            config.fullRange = prefs.getBoolean(FULL_RANGE_PREF_STRING, DEFAULT_FULL_RANGE)
            config.gamepadTouchpadAsMouse = prefs.getBoolean(GAMEPAD_TOUCHPAD_AS_MOUSE_PREF_STRING, DEFAULT_GAMEPAD_TOUCHPAD_AS_MOUSE)
            config.gamepadMotionSensors = prefs.getBoolean(GAMEPAD_MOTION_SENSORS_PREF_STRING, DEFAULT_GAMEPAD_MOTION_SENSORS)
            config.gamepadMotionSensorsFallbackToDevice = prefs.getBoolean(GAMEPAD_MOTION_FALLBACK_PREF_STRING, DEFAULT_GAMEPAD_MOTION_FALLBACK)
            config.forceMotionSensorsFallbackToDevice = prefs.getBoolean(FORCE_MOTION_SENSORS_FALLBACK_PREF_STRING, DEFAULT_FORCE_MOTION_SENSORS_FALLBACK)
            config.enableRumble = prefs.getBoolean(ENABLE_RUMBLE_PREF_STRING, DEFAULT_ENABLE_RUMBLE)
            config.preventPacketLoss = prefs.getBoolean(PREVENT_PACKET_LOSS_PREF_STRING, DEFAULT_PREVENT_PACKET_LOSS)
            config.snappyInput = prefs.getBoolean(SNAPPY_INPUT_PREF_STRING, DEFAULT_SNAPPY_INPUT)
            config.immediateFrameDelivery = prefs.getBoolean(IMMEDIATE_FRAME_DELIVERY_PREF_STRING, DEFAULT_IMMEDIATE_FRAME_DELIVERY)
            config.asyncDecodeEnabled = prefs.getBoolean(ASYNC_DECODE_ENABLED_PREF_STRING, DEFAULT_ASYNC_DECODE_ENABLED)
            config.asyncPreferLowerDelays = prefs.getBoolean(
                ASYNC_PREFER_LOWER_DELAYS_PREF_STRING,
                DEFAULT_ASYNC_PREFER_LOWER_DELAYS
            )
            config.syncLfrEnabled = prefs.getBoolean(SYNC_LFR_ENABLED_PREF_STRING, DEFAULT_SYNC_LFR_ENABLED)
            config.nvidiaLatencyRecoveryEnabled = prefs.getBoolean(
                NVIDIA_LATENCY_RECOVERY_PREF_STRING,
                DEFAULT_NVIDIA_LATENCY_RECOVERY
            )
            config.cpuWarmupEnabled = prefs.getBoolean(CPU_WARMUP_ENABLED_PREF_STRING, DEFAULT_CPU_WARMUP_ENABLED)
            config.cpuWarmupMode = prefs.getString(CPU_WARMUP_MODE_PREF_STRING, DEFAULT_CPU_WARMUP_MODE)
                ?: DEFAULT_CPU_WARMUP_MODE
            config.cpuWarmupThreads = clampInt(
                getIntOrStringInt(prefs, CPU_WARMUP_THREADS_PREF_STRING, DEFAULT_CPU_WARMUP_THREADS),
                0,
                16
            )
            config.cpuWarmupDuty = clampInt(
                getIntOrStringInt(prefs, CPU_WARMUP_DUTY_PREF_STRING, DEFAULT_CPU_WARMUP_DUTY),
                1,
                95
            )
            config.decoderOutputDequeueTimeoutCustom = prefs.getBoolean(
                DECODER_OUTPUT_DEQUEUE_TIMEOUT_CUSTOM_PREF_STRING,
                DEFAULT_DECODER_OUTPUT_DEQUEUE_TIMEOUT_CUSTOM
            )
            config.decoderOutputDequeueTimeoutUs = clampInt(
                prefs.getInt(DECODER_OUTPUT_DEQUEUE_TIMEOUT_US_PREF_STRING, DEFAULT_DECODER_OUTPUT_DEQUEUE_TIMEOUT_US),
                0,
                50000
            )

            config.rememberZoomPan = prefs.getBoolean(CHECKBOX_REMEMBER_ZOOM_PAN, DEFAULT_REMEMBER_ZOOM_PAN)
            config.zoomScale = prefs.getFloat(NUMBER_ZOOM_SCALE, DEFAULT_ZOOM_SCALE)
            config.panOffsetX = prefs.getFloat(NUMBER_PAN_OFFSET_X, DEFAULT_PAN_OFFSET)
            config.panOffsetY = prefs.getFloat(NUMBER_PAN_OFFSET_Y, DEFAULT_PAN_OFFSET)

            config.videoUpscaleEnable = prefs.getBoolean(VIDEO_UPSCALE_ENABLE_PREF_STRING, false)
            config.videoUpscaleMode = prefs.getString(VIDEO_UPSCALE_MODE_PREF_STRING, DEFAULT_VIDEO_UPSCALE_MODE)
                ?: DEFAULT_VIDEO_UPSCALE_MODE
            config.videoUpscaleSharpness = try {
                prefs.getInt(VIDEO_UPSCALE_SHARP_PREF_STRING, DEFAULT_VIDEO_UPSCALE_SHARP)
            } catch (_: ClassCastException) {
                prefs.getString(VIDEO_UPSCALE_SHARP_PREF_STRING, DEFAULT_VIDEO_UPSCALE_SHARP.toString())
                    ?.toIntOrNull() ?: DEFAULT_VIDEO_UPSCALE_SHARP
            }
            config.videoUpscalePreset = try {
                clampInt(prefs.getInt(VIDEO_UPSCALE_PRESET_PREF_STRING, DEFAULT_VIDEO_UPSCALE_PRESET), 0, 2)
            } catch (_: ClassCastException) {
                clampInt(
                    prefs.getString(VIDEO_UPSCALE_PRESET_PREF_STRING, DEFAULT_VIDEO_UPSCALE_PRESET.toString())
                        ?.toIntOrNull() ?: DEFAULT_VIDEO_UPSCALE_PRESET,
                    0,
                    2
                )
            }

            config.gpuPathMode = try {
                prefs.getBoolean(CHECKBOX_GPU_PATH_MODE, false)
            } catch (_: Throwable) {
                false
            }

            if (config.gpuPathMode) {
                config.videoUpscaleMode = "none"
                val hdrEnabled = prefs.getBoolean(ENABLE_HDR_PREF_STRING, false)
                config.videoUpscaleEnable = !hdrEnabled
            }

            config.enableVsync = getBooleanPrefOverlayFirst(context, PREF_VSYNC, false)
            config.fastVsync = getBooleanPrefOverlayFirst(context, PREF_FASTVSYNC, false)
            if (config.fastVsync && config.enableVsync) {
                config.enableVsync = false
            }

            return config
        }
    }
}
