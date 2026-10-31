package com.vayunmathur.sdk.openassistant

import android.os.Bundle
import android.os.Handler
import android.os.ResultReceiver

/**
 * Thin [ResultReceiver] that forwards results to a lambda.
 *
 * Lives in this SDK (rather than `:library`) so the SDK does not depend on
 * the whole base library for one 14-line class (#884).
 */
class SecureResultReceiver(
    handler: Handler?,
    private val onResult: (resultCode: Int, resultData: Bundle?) -> Unit
) : ResultReceiver(handler) {
    override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
        onResult(resultCode, resultData)
    }
}
