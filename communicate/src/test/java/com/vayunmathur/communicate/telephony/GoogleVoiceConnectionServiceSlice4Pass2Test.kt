package com.vayunmathur.communicate.telephony

import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.googlevoice.call.GoogleVoiceCallManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** Slice-4 pass-2: GV Telecom connection service creating self-managed connections. */
@RunWith(RobolectricTestRunner::class)
class GoogleVoiceConnectionServiceSlice4Pass2Test {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun handle() = PhoneAccountHandle(
        ComponentName(ctx, GoogleVoiceConnectionService::class.java), "gv",
    )

    @Test
    fun outgoingConnectionIsSelfManagedAndDialing() {
        val service = Robolectric.setupService(GoogleVoiceConnectionService::class.java)
        val request = ConnectionRequest(handle(), Uri.fromParts("tel", "+15559999", null), Bundle())
        val connection = service.onCreateOutgoingConnection(handle(), request)
        assertNotNull(connection)
        assertTrue(
            connection.connectionProperties and Connection.PROPERTY_SELF_MANAGED != 0,
            "GV connections must be self-managed",
        )
        assertEquals(Connection.STATE_DIALING, connection.state)
        assertTrue(connection === GoogleVoiceCallManager.connection)
        GoogleVoiceCallManager.stopRegistration()
        GoogleVoiceCallManager.connection = null
        service.onDestroy()
    }

    @Test
    fun incomingConnectionIsRingingWithCallerName() {
        val service = Robolectric.setupService(GoogleVoiceConnectionService::class.java)
        val extras = Bundle().apply {
            putParcelable(
                TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                Uri.fromParts("tel", "+15551234", null),
            )
        }
        val request = ConnectionRequest(handle(), Uri.fromParts("tel", "+15551234", null), extras)
        val connection = service.onCreateIncomingConnection(handle(), request)
        assertEquals(Connection.STATE_RINGING, connection.state)
        assertTrue(connection.connectionProperties and Connection.PROPERTY_SELF_MANAGED != 0)
        assertTrue(connection === GoogleVoiceCallManager.connection)
        GoogleVoiceCallManager.connection = null
        service.onDestroy()
    }

    @Test
    fun failedConnectionCallbacksAreSafe() {
        val service = Robolectric.setupService(GoogleVoiceConnectionService::class.java)
        service.onCreateOutgoingConnectionFailed(handle(), null)
        service.onCreateIncomingConnectionFailed(handle(), null)
        service.onDestroy()
    }

    @Test
    fun nullRequestConnectionsStillCreated() {
        val service = Robolectric.setupService(GoogleVoiceConnectionService::class.java)
        val outgoing = service.onCreateOutgoingConnection(null, null)
        assertNotNull(outgoing)
        val incoming = service.onCreateIncomingConnection(null, null)
        assertNotNull(incoming)
        GoogleVoiceCallManager.stopRegistration()
        GoogleVoiceCallManager.connection = null
        service.onDestroy()
    }
}
