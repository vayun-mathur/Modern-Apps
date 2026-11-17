package com.vayunmathur.keyboard

import androidx.compose.runtime.Composable
import com.vayunmathur.keyboard.ui.SetupScreen
import com.vayunmathur.library.ui.MaActivity

/**
 * Setup + settings entry point. The IME itself lives in [com.vayunmathur.keyboard.platform.ime.KeyboardService];
 * this activity only helps the user enable/select the keyboard and tune its preferences.
 */
class MainActivity : MaActivity() {
    @Composable
    override fun Content() {
        SetupScreen()
    }
}
