package com.vayunmathur.launcher

import android.appwidget.AppWidgetHost
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.launcher.ui.LauncherScreen
import com.vayunmathur.library.ui.DynamicTheme

class MainActivity : ComponentActivity() {
    private var widgetHost: AppWidgetHost? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        widgetHost = AppWidgetHost(this, 1024)

        setContent {
            DynamicTheme {
                val viewModel: LauncherViewModel = viewModel()
                LauncherScreen(viewModel, widgetHost)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        widgetHost?.startListening()
    }

    override fun onStop() {
        super.onStop()
        widgetHost?.stopListening()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
    }

    @Deprecated("Use OnBackPressedCallback")
    override fun onBackPressed() {
        // Launcher home — do nothing on back press
    }
}
