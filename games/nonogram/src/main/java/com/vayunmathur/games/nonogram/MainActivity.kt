package com.vayunmathur.games.nonogram

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.games.nonogram.platform.NonogramViewModel
import com.vayunmathur.library.ui.MaActivity

/** Entry activity for the Nonogram app; hosts its navigation with the shared view model. */
class MainActivity : MaActivity() {
    @Composable
    override fun Content() {
        val viewModel: NonogramViewModel = viewModel()
        Navigation(viewModel)
    }
}
