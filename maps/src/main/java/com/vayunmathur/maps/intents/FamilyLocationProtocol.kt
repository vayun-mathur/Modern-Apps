package com.vayunmathur.maps.intents
import com.vayunmathur.maps.data.string

/**
 * Byte-for-byte mirror of findfamily's `FamilyLocationProtocol` wire contract.
 *
 * The two apps deliberately do NOT share a module: the contract is just an
 * action string, three message codes and the Bundle keys for the parallel-array
 * payload, so duplicating it here keeps maps decoupled from findfamily's source
 * while still speaking the same Messenger protocol. Binding is gated by the
 * signature permission [PERMISSION] (both MA apps share the signing key).
 */
object FamilyLocationProtocol {
    /** Implicit action resolved + bound on the findfamily service. */
    const val ACTION = "com.vayunmathur.findfamily.action.FAMILY_LOCATIONS"

    /** Signature-level permission required to bind the service. */
    const val PERMISSION = "com.vayunmathur.findfamily.permissions.ACCESS_FAMILY"

    /** client → service: register the sender's `replyTo` Messenger for pushes. */
    const val MSG_REGISTER = 1

    /** client → service: stop pushing to the sender's `replyTo` Messenger. */
    const val MSG_UNREGISTER = 2

    /** service → client: a fresh family-locations snapshot in `Message.data`. */
    const val MSG_LOCATIONS = 3

    const val KEY_IDS = "ids"
    const val KEY_NAMES = "names"
    const val KEY_LATS = "lats"
    const val KEY_LNGS = "lngs"
    const val KEY_TIMESTAMPS = "timestamps"
    const val KEY_BATTERIES = "batteries"
}

/**
 * One family member's live present-location, decoded from a snapshot push.
 *
 * Never this device's own user: findfamily filters it out of the snapshot before sending, because
 * maps already draws the local position as the location puck from its own fix. A self entry here
 * would be a second, staler marker sitting under the puck.
 *
 * @param battery last reported battery level; negative when unknown/omitted.
 */
data class FamilyMember(
    val id: Long,
    val name: String,
    val lat: Double,
    val lng: Double,
    val timestamp: Long,
    val battery: Float,
)
