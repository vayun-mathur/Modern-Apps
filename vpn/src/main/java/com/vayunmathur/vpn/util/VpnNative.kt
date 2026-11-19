// PACKAGE STRUCTURE EXCEPTION (JNI): FQN frozen for native RegisterNatives/symbol mangling
package com.vayunmathur.vpn.util
import com.vayunmathur.library.log.NativeLoader

/**
 * JNI bridge to the native `vpn_wireguard` Rust library.
 * The Rust side wraps Mullvad's gotatun (BoringTun fork) Tunn — i.e. the WireGuard
 * Noise handshake + ChaCha20-Poly1305 data path. Key generation, tunnel lifecycle,
 * encaps/decaps are all done in Rust.
 *
 * Android TUN fd <-> UDP socket bridging lives in [com.vayunmathur.vpn.service.VpnTunnelService].
 */
object VpnNative {
    /**
     * False when the native library could not be loaded (missing split /
     * wrong ABI). The load failure is recorded instead of rethrown from
     * the class initializer: rethrowing poisons the class, so every later
     * touch (including the service's "native missing" handling) would die
     * with NoClassDefFoundError/ExceptionInInitializerError instead.
     * Callers that need a tunnel must check this before calling natives.
     */
    val isLoaded: Boolean = NativeLoader.load("vpn_wireguard", "VpnNative")

    /** Dummy init so we force class load on cold start */
    @JvmStatic external fun init()

    /** Returns base64-encoded random 32-byte X25519 static private key */
    @JvmStatic external fun generatePrivateKey(): String

    /** Derives base64 public key from private key (both base64) */
    @JvmStatic external fun derivePublicKey(privateKeyBase64: String): String

    /**
     * Creates a new tunnel and returns a handle (positive) or negative error code.
     * - privateKey, peerPublicKey: base64 standard
     * - presharedKey: base64 or empty
     * - persistentKeepalive: seconds (0 or <=0 means disabled)
     */
    @JvmStatic external fun newTunnel(
        privateKeyBase64: String,
        peerPublicKeyBase64: String,
        presharedKeyBase64: String,
        persistentKeepalive: Int,
    ): Long

    @JvmStatic external fun freeTunnel(handle: Long)

    /** Produces a WireGuard HandshakeInit packet to send over UDP (null if not needed) */
    @JvmStatic external fun formatHandshakeInit(handle: Long): ByteArray?

    /**
     * Consumes one inbound UDP WireGuard packet.
     * Returns [tag || payload] where tag 1=send payload over UDP, 2=plain IP to inject into TUN, 3=keepalive absorbed.
     * Returns null if packet is invalid / needs no action.
     */
    @JvmStatic external fun consumeIncomingPacketDetailed(handle: Long, packet: ByteArray): ByteArray?

    /** Encapsulates a plaintext IP packet (from TUN) into an encrypted WG packet to send over UDP */
    @JvmStatic external fun encapsulate(handle: Long, ipPacket: ByteArray): ByteArray?

    /** Called every ~100 ms to fire keepalives/rekeys. Returns [tag||payload] or null */
    @JvmStatic external fun tickTimersDetailed(handle: Long): ByteArray?

    /** JSON stats {"handshakeMs":..,"tx":..,"rx":..,"loss":..,"rtt":..} */
    @JvmStatic external fun getStats(handle: Long): String?
}
