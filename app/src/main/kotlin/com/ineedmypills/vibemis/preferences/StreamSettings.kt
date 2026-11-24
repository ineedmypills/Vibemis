package com.ineedmypills.vibemis.preferences

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.ineedmypills.vibemis.PcView
import com.ineedmypills.vibemis.R
import com.ineedmypills.vibemis.ui.components.enableVibemisEdgeToEdge
import com.ineedmypills.vibemis.ui.theme.VibemisExpressiveTheme
import com.ineedmypills.vibemis.utils.UiHelper

class StreamSettings : AppCompatActivity() {
    private lateinit var previousPrefs: PreferenceConfiguration

    fun reloadSettings() {}

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableVibemisEdgeToEdge()
        super.onCreate(savedInstanceState)
        previousPrefs = PreferenceConfiguration.readPreferences(this)
        UiHelper.setLocale(this)

        val sharedPrefs = PreferenceConfiguration.getDefaultSharedPreferences(this)

        setContent {
            VibemisExpressiveTheme {
                Scaffold(
                    contentWindowInsets = WindowInsets.safeDrawing,
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    text = stringResource(R.string.category_general_settings),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            navigationIcon = {
                                IconButton(onClick = { finishWithCheck() }) {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.ArrowBack,
                                        contentDescription = stringResource(R.string.wizard_btn_back)
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        )
                    }
                ) { innerPadding ->
                    VibemisSettingsContent(
                        prefs = sharedPrefs,
                        context = this@StreamSettings,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
    }

    private fun finishWithCheck() {
        val newPrefs = PreferenceConfiguration.readPreferences(this)
        if (newPrefs.language != previousPrefs.language) {
            val intent = Intent(this, PcView::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            finish()
            return
        }
        finish()
    }
}
