package com.vayunmathur.clock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Trigger a Notification or start a Ringtone activity here
        println("ALARM IS RINGING!")
    }
}