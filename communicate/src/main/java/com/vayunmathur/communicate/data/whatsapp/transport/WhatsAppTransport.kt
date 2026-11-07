package com.vayunmathur.communicate.data.whatsapp.transport

import kotlinx.coroutines.flow.SharedFlow

/**
 * The surface [com.vayunmathur.communicate.data.whatsapp.WhatsAppClient] uses of
 * [WhatsAppSocket]: connect/disconnect/send plus the decrypted-frame and
 * connection-state flows. Extracted so tests can drive the real client through a
 * fake transport (no TCP, no Noise) while production keeps using [WhatsAppSocket]
 * unchanged via the client's default `transportFactory`.
 */
internal interface WhatsAppTransport {
    fun connect()
    fun send(data: ByteArray): Boolean
    fun disconnect()

    /** Decrypted inbound frames (already Noise-decoded by the socket). */
    val messages: SharedFlow<ByteArray>

    val connectionState: SharedFlow<WhatsAppSocket.ConnectionState>
}
