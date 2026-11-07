package com.vayunmathur.communicate.telephony

import android.app.Service
import android.content.Intent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** Slice-4 pass-2: sync services' stop/start contract under Robolectric (no live credentials). */
@RunWith(RobolectricTestRunner::class)
class SyncServicesSlice4Pass2Test {

    @Test
    fun googleVoiceSyncStopActionIsNotStickyAndBindsNothing() {
        val service = Robolectric.setupService(GoogleVoiceSyncService::class.java)
        assertNull(service.onBind(Intent()))
        val result = service.onStartCommand(
            Intent().apply { action = "com.vayunmathur.communicate.googlevoice.STOP_SYNC" }, 0, 1,
        )
        assertEquals(Service.START_NOT_STICKY, result)
        service.onDestroy()
    }

    @Test
    fun signalSyncStopActionIsNotSticky() {
        val service = Robolectric.setupService(SignalSyncService::class.java)
        assertNull(service.onBind(Intent()))
        val result = service.onStartCommand(
            Intent().apply { action = "com.vayunmathur.communicate.signal.STOP_SYNC" }, 0, 1,
        )
        assertEquals(Service.START_NOT_STICKY, result)
        service.onDestroy()
    }

    @Test
    fun whatsAppSyncStopActionIsNotSticky() {
        val service = Robolectric.setupService(WhatsAppSyncService::class.java)
        assertNull(service.onBind(Intent()))
        val result = service.onStartCommand(
            Intent().apply { action = "com.vayunmathur.communicate.whatsapp.STOP_SYNC" }, 0, 1,
        )
        assertEquals(Service.START_NOT_STICKY, result)
        service.onDestroy()
    }

    @Test
    fun syncServicesNullIntentStartStickyThenShutdownWithoutCredentials() {
        // No credentials in the Robolectric store, so each service's launched check
        // shuts it down again; the synchronous contract is START_STICKY.
        val gv = Robolectric.setupService(GoogleVoiceSyncService::class.java)
        assertEquals(Service.START_STICKY, gv.onStartCommand(null, 0, 1))
        gv.onDestroy()
        val signal = Robolectric.setupService(SignalSyncService::class.java)
        assertEquals(Service.START_STICKY, signal.onStartCommand(null, 0, 1))
        signal.onDestroy()
        val wa = Robolectric.setupService(WhatsAppSyncService::class.java)
        assertEquals(Service.START_STICKY, wa.onStartCommand(null, 0, 1))
        wa.onDestroy()
    }

    @Test
    fun syncServicesTimeoutCallbackDoesNotCrash() {
        val gv = Robolectric.setupService(GoogleVoiceSyncService::class.java)
        gv.onTimeout(1, 0)
        gv.onDestroy()
        val signal = Robolectric.setupService(SignalSyncService::class.java)
        signal.onTimeout(1, 0)
        signal.onDestroy()
        val wa = Robolectric.setupService(WhatsAppSyncService::class.java)
        wa.onTimeout(1, 0)
        wa.onDestroy()
    }
}
