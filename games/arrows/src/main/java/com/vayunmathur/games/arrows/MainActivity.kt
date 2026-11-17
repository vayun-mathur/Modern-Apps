package com.vayunmathur.games.arrows

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.games.arrows.platform.ArrowsViewModel
import com.vayunmathur.library.ui.MaActivity

/** Entry activity for the Arrows app; hosts its navigation with the shared view model. */
class MainActivity : MaActivity() {
    @Composable
    override fun Content() {
        val viewModel: ArrowsViewModel = viewModel()
        Navigation(viewModel)
    }
}
