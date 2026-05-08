package com.vayunmathur.dialer

import android.content.Intent
import android.telecom.Call
import android.telecom.InCallService

class CallService : InCallService() {

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        CallManager.updateCall(call)
        
        // Launch our Active Call Activity when a call is added
        val intent = Intent(this, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        startActivity(intent)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        if (CallManager.activeCall.value == call) {
            CallManager.updateCall(null)
        }
    }
}
