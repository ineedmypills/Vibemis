package com.ineedmypills.vibemis

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ineedmypills.vibemis.ui.components.LocalReducedMotion
import com.ineedmypills.vibemis.ui.components.VibemisShapeBadge
import com.ineedmypills.vibemis.ui.components.enableVibemisEdgeToEdge
import com.ineedmypills.vibemis.ui.theme.VibemisExpressiveTheme

class HelpActivity : AppCompatActivity() {
    companion object {
        private const val DEFAULT_HELP_URL = "https://github.com/ineedmypills/Vibemis"
        private val ALLOWED_HOSTS = setOf(
            "github.com",
            "raw.githubusercontent.com",
            "docs.lizardbyte.dev"
        )

        internal fun sanitizeHelpUrl(rawUrl: String?): String {
            val trimmed = rawUrl?.trim()
            if (trimmed.isNullOrEmpty()) return DEFAULT_HELP_URL
            val uri = try {
                Uri.parse(trimmed)
            } catch (_: Exception) {
                return DEFAULT_HELP_URL
            }
            if (!"https".equals(uri.scheme, ignoreCase = true)) {
                return DEFAULT_HELP_URL
            }
            val host = uri.host?.lowercase() ?: return DEFAULT_HELP_URL
            val allowed = host in ALLOWED_HOSTS || host.endsWith(".github.io")
            return if (allowed) trimmed else DEFAULT_HELP_URL
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableVibemisEdgeToEdge()
        super.onCreate(savedInstanceState)
        val initialUrl = sanitizeHelpUrl(intent.data?.toString())

        setContent {
            VibemisExpressiveTheme {
                val reducedMotion = LocalReducedMotion.current
                var isLoading by remember { mutableStateOf(true) }
                var hasError by remember { mutableStateOf(false) }
                var canGoBack by remember { mutableStateOf(false) }
                var lastRequestedUrl by remember { mutableStateOf(initialUrl) }
                var webViewRef by remember { mutableStateOf<WebView?>(null) }

                BackHandler(enabled = canGoBack) {
                    webViewRef?.goBack()
                }

                Scaffold(
                    contentWindowInsets = WindowInsets.safeDrawing,
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(R.string.help_loading_title)) },
                            navigationIcon = {
                                IconButton(onClick = {
                                    val wv = webViewRef
                                    if (wv != null && wv.canGoBack()) {
                                        wv.goBack()
                                    } else {
                                        finish()
                                    }
                                }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = stringResource(R.string.wizard_btn_back)
                                    )
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        if (isLoading && !hasError) {
                            LinearWavyProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                amplitude = if (reducedMotion) 0f else 1f,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    WebView(ctx).apply {
                                        settings.builtInZoomControls = true
                                        settings.displayZoomControls = false
                                        settings.useWideViewPort = true
                                        settings.loadWithOverviewMode = true
                                        settings.javaScriptEnabled = true
                                        settings.allowFileAccess = false
                                        settings.allowContentAccess = false
                                        @Suppress("DEPRECATION")
                                        settings.allowFileAccessFromFileURLs = false
                                        @Suppress("DEPRECATION")
                                        settings.allowUniversalAccessFromFileURLs = false
                                        webViewClient = object : WebViewClient() {
                                            override fun shouldOverrideUrlLoading(
                                                view: WebView,
                                                request: WebResourceRequest
                                            ): Boolean {
                                                val target = request.url.toString()
                                                val allowed = sanitizeHelpUrl(target) == target.trim()
                                                if (allowed) {
                                                    lastRequestedUrl = target.trim()
                                                }
                                                return !allowed
                                            }

                                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                                if (!url.isNullOrBlank()) {
                                                    lastRequestedUrl = sanitizeHelpUrl(url)
                                                }
                                                isLoading = true
                                                hasError = false
                                                canGoBack = view.canGoBack()
                                            }

                                            override fun onPageFinished(view: WebView, url: String?) {
                                                isLoading = false
                                                canGoBack = view.canGoBack()
                                            }

                                            override fun onReceivedError(
                                                view: WebView,
                                                request: WebResourceRequest,
                                                error: WebResourceError
                                            ) {
                                                if (request.isForMainFrame) {
                                                    request.url?.toString()?.let {
                                                        lastRequestedUrl = sanitizeHelpUrl(it)
                                                    }
                                                    isLoading = false
                                                    hasError = true
                                                }
                                            }

                                            override fun onReceivedHttpError(
                                                view: WebView,
                                                request: WebResourceRequest,
                                                errorResponse: WebResourceResponse
                                            ) {
                                                if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                                                    request.url?.toString()?.let {
                                                        lastRequestedUrl = sanitizeHelpUrl(it)
                                                    }
                                                    isLoading = false
                                                    hasError = true
                                                }
                                            }
                                        }
                                        loadUrl(initialUrl)
                                        webViewRef = this
                                    }
                                }
                            )
                            if (hasError) {
                                Surface(
                                    modifier = Modifier.fillMaxSize(),
                                    color = MaterialTheme.colorScheme.background
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(32.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        VibemisShapeBadge(
                                            polygon = MaterialShapes.Cookie6Sided,
                                            containerColor = MaterialTheme.colorScheme.errorContainer,
                                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                            size = 72.dp,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Warning,
                                                contentDescription = null,
                                                modifier = Modifier.size(32.dp),
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text(
                                            text = stringResource(R.string.help_load_error_title),
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            textAlign = TextAlign.Center,
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = stringResource(R.string.help_load_error_desc),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center,
                                        )
                                        Spacer(modifier = Modifier.height(20.dp))
                                        Button(onClick = {
                                            hasError = false
                                            isLoading = true
                                            webViewRef?.loadUrl(sanitizeHelpUrl(lastRequestedUrl))
                                        }) {
                                            Text(stringResource(R.string.action_retry))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
