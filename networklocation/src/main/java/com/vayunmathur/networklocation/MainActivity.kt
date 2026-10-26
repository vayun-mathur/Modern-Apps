package com.vayunmathur.networklocation

import androidx.compose.runtime.Composable
import com.vayunmathur.library.ui.MaActivity

/**
 * Status screen for the system network-location + geocoder provider. This app is primarily
 * headless (two bound framework services); the UI only downloads the offline databases and then
 * shows a live check. It has no launcher entry — Settings links to it from
 * Location -> Location services.
 */
class MainActivity : MaActivity() {
    @Composable
    override fun Content() {
        Navigation()
    }
}
