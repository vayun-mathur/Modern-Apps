package com.vayunmathur.messages.gvoice.voice

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle
import android.util.Log

/**
 * Android Telecom ConnectionService for Google Voice calling.
 * Handles outgoing and incoming call requests from the Telecom framework.
 */
class GVoiceConnectionService : ConnectionService() {

    companion object {
        private const val TAG = "GVoiceConnectionService"
    }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest
    ): Connection {
        Log.i(TAG, "onCreateOutgoingConnection: ${request.address}")

        val phoneNumber = request.address?.schemeSpecificPart ?: run {
            Log.e(TAG, "No phone number in request")
            return Connection.createFailedConnection(
                android.telecom.DisconnectCause(android.telecom.DisconnectCause.ERROR, "No phone number")
            )
        }

        val connection = GVoiceConnection(phoneNumber)
        connection.placeOutgoingCall()
        return connection
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest
    ): Connection {
        Log.i(TAG, "onCreateIncomingConnection: ${request.address}")
        // TODO: Handle incoming SIP calls
        // For now, return a failed connection
        return Connection.createFailedConnection(
            android.telecom.DisconnectCause(android.telecom.DisconnectCause.ERROR, "Incoming calls not yet implemented")
        )
    }

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest
    ) {
        Log.e(TAG, "onCreateOutgoingConnectionFailed: ${request.address}")
        super.onCreateOutgoingConnectionFailed(connectionManagerPhoneAccount, request)
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest
    ) {
        Log.e(TAG, "onCreateIncomingConnectionFailed: ${request.address}")
        super.onCreateIncomingConnectionFailed(connectionManagerPhoneAccount, request)
    }
}
