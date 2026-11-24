package com.ineedmypills.vibemis

import android.content.Context
import android.hardware.Sensor
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ineedmypills.vibemis.ui.components.enableVibemisEdgeToEdge
import com.ineedmypills.vibemis.ui.theme.VibemisExpressiveTheme
import com.ineedmypills.vibemis.utils.DeviceUtils
import kotlin.math.roundToInt

class DebugInfoActivity : AppCompatActivity() {
    private var vibrator: Vibrator? = null
    private var vibratorOnline: Vibrator? = null
    private var simulatedAmplitude by mutableIntStateOf(220)
    private var gamepadReport by mutableStateOf("")
    private val gamepads = mutableListOf<InputDevice>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableVibemisEdgeToEdge()
        super.onCreate(savedInstanceState)
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateGamePad()

        setContent {
            VibemisExpressiveTheme {
                DebugInfoScreen()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelRumble()
    }

    private fun cancelRumble() {
        vibratorOnline?.cancel()
        vibrator?.cancel()
    }

    private fun rumble(target: Vibrator?) {
        target ?: return
        target.vibrate(
            VibrationEffect.createWaveform(longArrayOf(1000L), intArrayOf(simulatedAmplitude.coerceIn(1, 255)), 0)
        )
    }

    private fun updateGamePad() {
        gamepads.clear()
        val sb = StringBuilder()
        for (deviceId in InputDevice.getDeviceIds()) {
            val dev = InputDevice.getDevice(deviceId) ?: continue
            val sources = dev.sources
            val isGamepad = (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            if (isGamepad &&
                getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_X) != null &&
                getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_Y) != null
            ) {
                gamepads.add(dev)
                sb.append(getString(R.string.debug_info_name)).append(dev.name).append('\n')
                sb.append(getString(R.string.debug_info_sensors))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    var sensor = ""
                    if (dev.sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null) {
                        sensor += getString(R.string.debug_info_accelerometer)
                    }
                    if (dev.sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null) {
                        sensor += getString(R.string.debug_info_gyroscope)
                    }
                    sb.append(if (sensor.isEmpty()) getString(R.string.debug_info_no_relevant_driver) else sensor)
                } else {
                    sb.append(getString(R.string.debug_info_no_api_below_android12))
                }
                sb.append('\n')
                sb.append(getString(R.string.debug_info_vid_pid))
                    .append("${dev.vendorId}_${dev.productId} ")
                    .append(String.format("[%04x_%04x]\n", dev.vendorId, dev.productId))
                sb.append(getString(R.string.debug_info_vibration))
                    .append(
                        if (getDeviceVibrator(dev).hasVibrator()) {
                            getString(R.string.debug_info_supported)
                        } else {
                            getString(R.string.debug_info_not_supported)
                        }
                    ).append("\n\n")
            }
        }
        gamepadReport = "${getString(R.string.debug_info_number_of_gamepads)}${gamepads.size}\n\n$sb"
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun DebugInfoScreen() {
        val systemSummary = buildString {
            append(getString(R.string.debug_info_android_version)).append(DeviceUtils.getSDKVersionName())
            append("  ").append(getString(R.string.debug_info_api_version)).append(Build.VERSION.SDK_INT)
            append('\n').append(getString(R.string.debug_info_kernel_version)).append(System.getProperty("os.version"))
            append('\n').append(getString(R.string.debug_info_brand_model))
                .append(DeviceUtils.getManufacturer()).append(" - ").append(DeviceUtils.getModel())
        }

        @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text(stringResource(R.string.debug_diagnostics_title), fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                        }
                    },
                    actions = {
                        IconButton(onClick = { updateGamePad() }) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.debug_info_refresh_gamepad_list))
                        }
                    }
                )
            }
        ) { innerPadding ->
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Adaptive(minSize = 340.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalItemSpacing = 16.dp,
            ) {
                item {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(stringResource(R.string.debug_system_info), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(systemSummary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                item {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                text = getString(R.string.debug_info_vibration_amplitude, simulatedAmplitude),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            @Suppress("DEPRECATION")
                            Slider(
                                value = simulatedAmplitude.toFloat(),
                                onValueChange = { simulatedAmplitude = it.roundToInt() },
                                valueRange = 1f..255f
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(onClick = { rumble(vibrator) }) {
                                    Icon(Icons.Default.Vibration, contentDescription = null)
                                    Text(" ${stringResource(R.string.debug_device_rumble)}")
                                }
                                FilledTonalButton(onClick = {
                                    val firstPad = gamepads.firstOrNull { getDeviceVibrator(it).hasVibrator() }
                                    if (firstPad != null) {
                                        cancelRumble()
                                        vibratorOnline = getDeviceVibrator(firstPad)
                                        rumble(vibratorOnline)
                                    } else {
                                        com.ineedmypills.vibemis.utils.Dialog.displayDialog(
                                            this@DebugInfoActivity,
                                            getString(R.string.debug_gamepad_rumble),
                                            getString(R.string.debug_info_no_gamepad_detected),
                                            false
                                        )
                                    }
                                }) {
                                    Text(stringResource(R.string.debug_gamepad_rumble))
                                }
                                OutlinedButton(onClick = { cancelRumble() }) {
                                    Text(stringResource(R.string.debug_stop_rumble))
                                }
                            }
                        }
                    }
                }

                item {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(stringResource(R.string.debug_connected_controllers), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(gamepadReport, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    companion object {
        private fun getDeviceVibrator(dev: InputDevice): Vibrator {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dev.vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                dev.vibrator
            }
        }

        @JvmStatic
        fun getMotionRangeForJoystickAxis(dev: InputDevice, axis: Int): InputDevice.MotionRange? {
            return dev.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK)
                ?: dev.getMotionRange(axis, InputDevice.SOURCE_GAMEPAD)
        }
    }
}
