package com.vayunmathur.games.logicgate

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.games.logicgate.platform.LogicViewModel
import com.vayunmathur.games.logicgate.ui.LogicGateTheme
import com.vayunmathur.library.ui.MaActivity

/** Single activity hosting the level map and the editor. */
class MainActivity : MaActivity() {
    @Composable
    override fun AppTheme(content: @Composable () -> Unit) {
        LogicGateTheme(content = content)
    }

    @Composable
    override fun Content() {
        val vm: LogicViewModel = viewModel()
        Navigation(vm)
    }
}
