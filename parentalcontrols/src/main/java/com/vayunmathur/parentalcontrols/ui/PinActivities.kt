package com.vayunmathur.parentalcontrols.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.parentalcontrols.auth.ParentPin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The parent-PIN challenge, reached before any imposed-enforcement surface.
 *
 * Started for a result: the caller passes what unlocking means via the result code -
 * [RESULT_VERIFIED] when the entered PIN checks out, [RESULT_CANCELED] otherwise. The PIN
 * itself never leaves this activity.
 */
class PinGateActivity : ComponentActivity() {

    private var error by mutableStateOf<String?>(null)
    private var lockedOut by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pin = ParentPin.get(this)
        lockedOut = pin.isLockedOut()
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                PinGateScreen(
                    error = error,
                    lockedOut = lockedOut,
                    actions = PinGateActions(
                        onVerify = { entry -> verify(pin, entry) },
                        onCancel = { finish() },
                    ),
                )
            }
        }
    }

    private fun verify(pin: ParentPin, entry: String): Boolean {
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = pin.verify(entry)
            launch(Dispatchers.Main) {
                if (ok) {
                    setResult(RESULT_VERIFIED)
                    finish()
                } else {
                    lockedOut = pin.isLockedOut()
                    error = if (lockedOut) {
                        getString(
                            com.vayunmathur.parentalcontrols.R.string.pin_locked_out,
                        )
                    } else {
                        getString(com.vayunmathur.parentalcontrols.R.string.pin_wrong)
                    }
                }
            }
        }
        // The real answer arrives asynchronously; returning false keeps the entry until then.
        return false
    }

    companion object {
        const val RESULT_VERIFIED = RESULT_FIRST_USER + 1
    }
}
