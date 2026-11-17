package com.vayunmathur.games.unblockjam

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.games.unblockjam.data.LevelPack
import com.vayunmathur.games.unblockjam.platform.UnblockJamViewModel
import com.vayunmathur.games.unblockjam.ui.UnblockJamTheme
import com.vayunmathur.library.ui.MaActivity

/** Entry activity for Unblock Jam; loads level packs and hosts the game navigation. */
class MainActivity : MaActivity() {
    override fun onCreateBeforeContent() {
        LevelPack.init(this)
    }

    @Composable
    override fun AppTheme(content: @Composable () -> Unit) {
        UnblockJamTheme(content = content)
    }

    @Composable
    override fun Content() {
        val viewModel: UnblockJamViewModel = viewModel()
        Navigation(viewModel)
    }
}
