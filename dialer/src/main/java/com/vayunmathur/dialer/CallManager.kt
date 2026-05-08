package com.vayunmathur.dialer

import android.telecom.Call
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object CallManager {
    private val _activeCall = MutableStateFlow<Call?>(null)
    val activeCall: StateFlow<Call?> = _activeCall

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            // Trigger recomposition or state updates when call state changes
            _activeCall.value = null
            _activeCall.value = call
        }
    }

    fun updateCall(call: Call?) {
        _activeCall.value?.unregisterCallback(callback)
        call?.registerCallback(callback)
        _activeCall.value = call
    }

    fun acceptCall() {
        _activeCall.value?.answer(Call.STATE_RINGING)
    }

    fun rejectCall() {
        _activeCall.value?.let {
            if (it.state == Call.STATE_RINGING) {
                it.reject(false, null)
            } else {
                it.disconnect()
            }
        }
    }
}
