package com.vayunmathur.library.util

import android.os.Bundle
import android.os.Handler
import android.os.ResultReceiver

/** ResultReceiver that forwards results to a callback without exposing a subclass to IPC. */
class SecureResultReceiver(
    handler: Handler?,
    private val onResult: (resultCode: Int, resultData: Bundle?) -> Unit
) : ResultReceiver(handler) {
    override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
        onResult(resultCode, resultData)
    }
}
