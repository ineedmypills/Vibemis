package com.ineedmypills.vibemis.preferences

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ineedmypills.vibemis.AppView
import com.ineedmypills.vibemis.Game
import com.ineedmypills.vibemis.PcView
import com.ineedmypills.vibemis.R
import com.ineedmypills.vibemis.ShortcutTrampoline
import com.ineedmypills.vibemis.computers.ComputerManagerService
import com.ineedmypills.vibemis.nvstream.http.ComputerDetails
import com.ineedmypills.vibemis.nvstream.http.NvHTTP
import com.ineedmypills.vibemis.nvstream.jni.VibemisBridge
import com.ineedmypills.vibemis.ui.components.VibemisShapeBadge
import com.ineedmypills.vibemis.ui.components.enableVibemisEdgeToEdge
import com.ineedmypills.vibemis.ui.theme.VibemisExpressiveTheme
import com.ineedmypills.vibemis.utils.Dialog
import com.ineedmypills.vibemis.utils.ServerHelper
import com.ineedmypills.vibemis.utils.SpinnerDialog
import com.ineedmypills.vibemis.utils.UiHelper
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue

class AddComputerManually : AppCompatActivity() {
    private var hostInputState by mutableStateOf("")
    private var isAddingState by mutableStateOf(false)
    private var managerBinder: ComputerManagerService.ComputerManagerBinder? = null
    private val computersToAdd = LinkedBlockingQueue<String>()
    private var addThread: Thread? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            managerBinder = binder as ComputerManagerService.ComputerManagerBinder
            startAddThread()
        }

        override fun onServiceDisconnected(className: ComponentName) {
            joinAddThread()
            managerBinder = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableVibemisEdgeToEdge()
        super.onCreate(savedInstanceState)

        val action = intent.action
        val data = intent.data
        var initialServer: String? = null
        var initialQuery: String? = null

        if (Intent.ACTION_VIEW == action && data != null) {
            val port = data.port
            if (port == -1 && data.host == "launch") {
                val hostUUID = data.getQueryParameter("host_uuid")
                val hostName = data.getQueryParameter("host_name")
                val appUUID = data.getQueryParameter("app_uuid")
                val appName = data.getQueryParameter("app_name")
                val appID = data.getQueryParameter("app_id")

                val trampolineIntent = Intent(this, ShortcutTrampoline::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra(AppView.UUID_EXTRA, hostUUID)
                    putExtra(AppView.NAME_EXTRA, hostName)
                    putExtra(Game.EXTRA_APP_UUID, appUUID)
                    putExtra(Game.EXTRA_APP_NAME, appName)
                    putExtra(Game.EXTRA_APP_ID, appID)
                }
                finish()
                startActivity(trampolineIntent)
                return
            }
            initialServer = data.authority
            initialQuery = data.query
        }

        UiHelper.setLocale(this)

        if (initialServer != null) {
            hostInputState = if (initialQuery != null) "$initialServer?$initialQuery" else initialServer
            computersToAdd.add(hostInputState)
        }

        bindService(
            Intent(this, ComputerManagerService::class.java),
            serviceConnection,
            Service.BIND_AUTO_CREATE
        )

        setContent {
            VibemisExpressiveTheme {
                AddComputerScreen()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        Dialog.closeDialogs()
        SpinnerDialog.closeDialogs(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (managerBinder != null) {
            joinAddThread()
            unbindService(serviceConnection)
        }
    }

    private fun submitHost() {
        val trimmed = hostInputState.trim()
        if (trimmed.isEmpty()) {
            Dialog.displayDialog(this, getString(R.string.title_add_pc), getString(R.string.addpc_enter_ip), false)
            return
        }
        computersToAdd.add(trimmed)
    }

    private fun startAddThread() {
        addThread = Thread({
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val computer = computersToAdd.take()
                    doAddPc(computer)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "UI - AddComputerManually").apply { start() }
    }

    private fun joinAddThread() {
        addThread?.let { thread ->
            thread.interrupt()
            try {
                thread.join()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            addThread = null
        }
    }

    private fun isWrongSubnetSiteLocalAddress(address: String): Boolean {
        return try {
            val targetAddress = InetAddress.getByName(address)
            if (targetAddress !is Inet4Address || !targetAddress.isSiteLocalAddress) {
                return false
            }
            for (iface in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (addr in iface.interfaceAddresses) {
                    val inet = addr.address
                    if (inet !is Inet4Address || !inet.isSiteLocalAddress) continue
                    val targetBytes = targetAddress.address
                    val ifaceBytes = inet.address
                    var matches = true
                    for (i in 0 until addr.networkPrefixLength) {
                        if ((ifaceBytes[i / 8].toInt() and (1 shl (i % 8))) !=
                            (targetBytes[i / 8].toInt() and (1 shl (i % 8)))
                        ) {
                            matches = false
                            break
                        }
                    }
                    if (matches) return false
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun parseRawUserInputToUri(rawUserInput: String): Uri? {
        var uri = Uri.parse("vibemis://$rawUserInput")
        if (!uri.host.isNullOrEmpty()) return uri
        uri = Uri.parse("vibemis://[$rawUserInput]")
        if (!uri.host.isNullOrEmpty()) return uri
        return null
    }

    @Throws(InterruptedException::class)
    private fun doAddPc(rawUserInput: String) {
        var wrongSiteLocal = false
        var invalidInput = false
        var success = false

        runOnUiThread { isAddingState = true }
        val dialog = SpinnerDialog.displayDialog(
            this,
            resources.getString(R.string.title_add_pc),
            resources.getString(R.string.msg_add_pc),
            false
        )

        val uri = parseRawUserInputToUri(rawUserInput)
        try {
            val details = ComputerDetails()
            val binder = managerBinder
            if (uri != null && !uri.host.isNullOrEmpty() && binder != null) {
                val host = uri.host!!
                val port = if (uri.port == -1) NvHTTP.DEFAULT_HTTP_PORT else uri.port
                details.manualAddress = ComputerDetails.AddressTuple(host, port)
                success = binder.addComputerBlocking(details)
                if (!success) {
                    wrongSiteLocal = isWrongSubnetSiteLocalAddress(host)
                }
            } else {
                invalidInput = true
            }
        } catch (e: InterruptedException) {
            dialog.dismiss()
            runOnUiThread { isAddingState = false }
            throw e
        } catch (_: IllegalArgumentException) {
            invalidInput = true
        }

        val portTestResult = if (!success && !wrongSiteLocal && !invalidInput && uri != null && !uri.host.isNullOrEmpty()) {
            VibemisBridge.testClientConnectivity(
                uri.host!!,
                if (uri.port == -1) NvHTTP.DEFAULT_HTTP_PORT else uri.port,
                VibemisBridge.VB_PORT_FLAG_TCP_47984 or VibemisBridge.VB_PORT_FLAG_TCP_47989
            )
        } else {
            VibemisBridge.VB_TEST_RESULT_INCONCLUSIVE
        }

        dialog.dismiss()
        runOnUiThread { isAddingState = false }

        when {
            invalidInput -> Dialog.displayDialog(
                this,
                resources.getString(R.string.conn_error_title),
                resources.getString(R.string.addpc_unknown_host),
                false
            )
            wrongSiteLocal -> Dialog.displayDialog(
                this,
                resources.getString(R.string.conn_error_title),
                resources.getString(R.string.addpc_wrong_sitelocal),
                false
            )
            !success -> {
                val dialogText = if (portTestResult != VibemisBridge.VB_TEST_RESULT_INCONCLUSIVE && portTestResult != 0) {
                    resources.getString(R.string.nettest_text_blocked)
                } else {
                    resources.getString(R.string.addpc_fail)
                }
                Dialog.displayDialog(
                    this,
                    resources.getString(R.string.conn_error_title),
                    dialogText,
                    false
                )
            }
            else -> runOnUiThread {
                if (!isFinishing) {
                    finish()
                }
                val pin = uri?.getQueryParameter("pin")?.trim()
                val passphrase = uri?.getQueryParameter("passphrase")
                val validPin = pin != null && pin.length == 4 && pin.all { it.isDigit() }
                val validPass = passphrase != null && passphrase.length in 4..128
                if (validPin && validPass && uri.host != null) {
                    val intent = Intent(this, PcView::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
                        putExtra("hostname", uri.host)
                        putExtra("port", if (uri.port > 0) uri.port else NvHTTP.DEFAULT_HTTP_PORT)
                        putExtra("pin", pin)
                        putExtra("passphrase", passphrase)
                    }
                    startActivity(intent)
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    private fun AddComputerScreen() {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                LargeFlexibleTopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.title_add_pc),
                            fontWeight = FontWeight.Bold
                        )
                    },
                    subtitle = {
                        Text(text = stringResource(R.string.connect_host_header))
                    },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 560.dp),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            VibemisShapeBadge(
                                polygon = MaterialShapes.SoftBurst,
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                size = 48.dp,
                            ) {
                                Icon(Icons.Default.Computer, contentDescription = null, modifier = Modifier.size(24.dp))
                            }
                            Text(
                                text = stringResource(R.string.connect_host_header),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.ip_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(
                            value = hostInputState,
                            onValueChange = { hostInputState = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.host_address_label)) },
                            placeholder = { Text("192.168.1.100:47989") },
                            leadingIcon = { Icon(Icons.Default.Computer, contentDescription = null) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { submitHost() })
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = { submitHost() },
                            enabled = !isAddingState,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            if (isAddingState) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.Add, contentDescription = null)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.title_add_pc))
                        }
                    }
                }
            }
        }
    }
}
