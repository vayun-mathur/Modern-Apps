package com.vayunmathur.games.solitaire

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.games.solitaire.platform.SolitaireViewModel
import com.vayunmathur.library.ui.MaActivity

/** Entry activity hosting the solitaire navigation graph. */
class MainActivity : MaActivity() {
    @Composable
    override fun Content() {
        val viewModel: SolitaireViewModel = viewModel()
        Navigation(viewModel)
    }
}
