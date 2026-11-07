package com.vayunmathur.emergency.platform

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.emergency.receiver.SosActionReceiver
import com.vayunmathur.emergency.service.SosCountdownService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the SOS countdown screen's state and its handoff to the background.
 *
 * Mirrors `EmergencyActionFragment`'s half of the flow: the screen itself counts down (see
 * `ui/SosScreen`), and this coordinator resolves the number, gates on the gesture toggle,
 * and - when the screen is dismissed mid-countdown - continues via [SosCountdownService]
 * instead of dropping the call. Cancelling stops everything through [SosActionReceiver].
 */
class SosCoordinator(app: Application, private val openedWithMillis: Long) :
    AndroidViewModel(app), SosActions {

    private val mutable = MutableStateFlow(SosUiState())
    val state: StateFlow<SosUiState> = mutable.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val enabled = GestureProvider.isGestureEnabledStatic(app)
            val number = EmergencyNumberLookup(app)
                .policeNumber(GestureProvider.numberOverrideStatic(app))
            // -1 means "not specified" (use the default); 0 is a real value - a
            // deadline that already passed must fire at once, not restart at the
            // full default countdown.
            val total = if (openedWithMillis >= 0) {
                openedWithMillis
            } else {
                app.resources.getInteger(
                    com.vayunmathur.emergency.R.integer.sos_count_down_millis,
                ).toLong()
            }
            mutable.value = SosUiState(
                number = number,
                totalMillis = total,
                gestureEnabled = enabled,
                loaded = true,
            )
        }
    }

    override fun continueInBackground(remainingMillis: Long) {
        if (remainingMillis > 0) SosCountdownService.start(getApplication(), remainingMillis)
    }

    override fun cancel() {
        SosActionReceiver.stopCountdown(getApplication())
    }
}
