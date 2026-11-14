package com.vayunmathur.communicate.telephony

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Answers the systems respond-via-message request; this app handles it in-call, so binding is a no-op. */
class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
