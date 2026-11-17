package com.vayunmathur.findfamily.domain

/**
 * Apple's "UWB Specification for Third-Party Accessories" protocol — the bytes
 * that flow between an iPhone (running `NINearbyAccessoryConfiguration`) and a
 * UWB accessory. In the FindFamily cross-platform pairing, the Android device
 * plays the accessory role.
 *
 * The byte format is specified in:
 *   - Apple's PDF "Implementing Spatial Interactions with Third-Party Devices"
 *     (developer.apple.com/nearby-interaction → "Specification")
 *   - FiRa MAC Technical Requirements (TLV-encoded MAC params)
 *
 * The encode/parse functions below are stubbed: filling them in accurately
 * requires both specs in hand and is a multi-day task. The scaffolding
 * around them (transport, envelope routing, controlee session setup via
 * `android.ranging.RangingManager`) is complete.
 */
object UwbAccessoryProtocol {

    /**
     * Generate the "accessory configuration data" blob to send to iOS at
     * session start. Must encode this device's UWB MAC parameters per
     * Apple's accessory spec.
     */
    fun encodeAccessoryConfigurationData(localAddress: ByteArray): ByteArray {
        // Not yet implemented: encode per Apple's spec (cross-platform UWB work).
        // The blob is roughly: [protocol-version u8][profile-id u8][TLV...].
        return byteArrayOf(0x01, 0x00) + localAddress
    }

    /**
     * Parse the "shareableConfigurationData" blob received from iOS into the
     * concrete FiRa parameters this device should use as controlee.
     *
     * The parameter is unused until the byte parsing is implemented (see class KDoc);
     * it is kept so the call sites already pass the blob and the signature won't churn.
     */
    @Suppress("UNUSED_PARAMETER")
    fun parseShareableConfigurationData(shareableConfigData: ByteArray): Parsed {
        throw NotImplementedError(
            "Apple accessory-protocol byte parsing not yet implemented. " +
                "See UwbAccessoryProtocol.kt and Apple's UWB Third-Party Accessory spec."
        )
    }

    /** FiRa parameters parsed from a peer's shareable configuration data. */
    data class Parsed(
        val peerAddress: ByteArray,
        val sessionId: Int,
        val sessionKey: ByteArray,
        val channelNumber: Int,
        val preambleIndex: Int,
    )
}
