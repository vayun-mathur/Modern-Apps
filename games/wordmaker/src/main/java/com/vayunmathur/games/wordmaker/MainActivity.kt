package com.vayunmathur.games.wordmaker

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.vayunmathur.games.wordmaker.platform.WordMakerViewModel
import com.vayunmathur.library.ui.MaActivity

/** Entry activity for Word Maker; hosts the game navigation. */
class MainActivity : MaActivity() {
    private val viewModel: WordMakerViewModel by viewModels()

    @Composable
    override fun Content() {
        Navigation(viewModel)
    }
}
