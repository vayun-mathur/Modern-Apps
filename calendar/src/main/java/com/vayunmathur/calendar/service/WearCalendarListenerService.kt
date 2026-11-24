package com.vayunmathur.calendar.service

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.vayunmathur.calendar.domain.WearCalendarProtocol
import com.vayunmathur.calendar.sync.WearCalendarSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Phone listener for watch-originated messages while the UI is not running.
 * The only one is [WearCalendarProtocol.REQUEST_SYNC_PATH]: the watch asks
 * for a fresh snapshot. There are no watch-originated event writes to handle.
 */
class WearCalendarListenerService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != WearCalendarProtocol.REQUEST_SYNC_PATH) return
        val sync = WearCalendarSync.get(this)
        sync.start()
        scope.launch { sync.publishSnapshot() }
    }
}
