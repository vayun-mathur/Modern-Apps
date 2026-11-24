package com.vayunmathur.library.wearlink

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** A bonded peer candidate, as the platform Bluetooth stack reports it. */
data class BondedPeer(val address: String, val label: String)

/**
 * One connected byte stream. Behind this seam the Android implementation is
 * a BluetoothSocket; unit tests use in-memory pipes. All IO on these streams
 * is blocking and must run off the main thread (the node enforces that).
 */
interface WearLinkSocket : Closeable {
    val input: InputStream
    val output: OutputStream
    val peerLabel: String
}

interface WearLinkServerSocket : Closeable {
    /** Blocking accept; throws/returns via close from another thread. */
    fun accept(): WearLinkSocket
}

/**
 * Platform Bluetooth seam. Implementations must only ever talk to bonded
 * devices (no discovery): the Wear OS companion is bonded by the system.
 */
interface WearLinkTransport {
    /** True when a Bluetooth adapter exists and is powered on. */
    fun isAvailable(): Boolean

    /** Currently bonded devices; empty when permission is missing. */
    fun bondedPeers(): List<BondedPeer>

    /** Blocking RFCOMM connect to [peer] for [serviceUuid]. */
    fun connect(peer: BondedPeer, serviceUuid: UUID): WearLinkSocket

    /** Blocking-accept RFCOMM server for [serviceUuid]. */
    fun listen(serviceUuid: UUID, serviceName: String): WearLinkServerSocket
}
