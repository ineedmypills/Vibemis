package com.ineedmypills.vibemis.ui.wizard

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.ineedmypills.vibemis.preferences.AddComputerManually
import com.ineedmypills.vibemis.ui.components.enableVibemisEdgeToEdge
import com.ineedmypills.vibemis.ui.theme.VibemisExpressiveTheme

class SetupWizardActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableVibemisEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            VibemisExpressiveTheme {
                SetupWizardScreen(
                    onFinish = { finish() },
                    onAddHost = {
                        startActivity(Intent(this@SetupWizardActivity, AddComputerManually::class.java))
                        finish()
                    }
                )
            }
        }
    }
}
