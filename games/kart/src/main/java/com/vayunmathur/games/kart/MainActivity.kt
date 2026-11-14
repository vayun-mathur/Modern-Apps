package com.vayunmathur.games.kart

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.games.kart.platform.GamepadDirect
import com.vayunmathur.games.kart.platform.KartViewModel
import com.vayunmathur.library.ui.MaActivity

/** Main Activity: a ma activity implementation for main activity. */
class MainActivity : MaActivity() {
    private var gamepad: GamepadDirect? = null

    override fun onCreateBeforeContent() {
        val vm = ViewModelProvider(this)[KartViewModel::class.java]
        gamepad = GamepadDirect(
            context = this,
            onSeat = { playerId, name, present -> vm.onGamepadSeat(playerId, name, present) },
            onInput = { input -> vm.onGamepadInput(input) },
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        gamepad?.onKeyEvent(event) == true || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        gamepad?.onKeyEvent(event) == true || super.onKeyUp(keyCode, event)

    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
        gamepad?.onGenericMotionEvent(event) == true || super.onGenericMotionEvent(event)

    override fun onDestroy() {
        gamepad?.close()
        gamepad = null
        super.onDestroy()
    }

    @Composable
    override fun Content() {
        val viewModel: KartViewModel = viewModel()
        Navigation(viewModel)
    }
}
