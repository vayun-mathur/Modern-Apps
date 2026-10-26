package com.vayunmathur.shop

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.shop.platform.AppInit
import com.vayunmathur.shop.platform.ShopViewModel

class MainActivity : MaActivity() {
    private val viewModel: ShopViewModel by viewModels()

    override fun onCreateBeforeContent() {
        // Trust-bundle warm-up off the main thread; screens await AppInit.awaitReady().
        AppInit.start(this)
    }

    @Composable
    override fun Content() {
        Navigation(viewModel)
    }
}
