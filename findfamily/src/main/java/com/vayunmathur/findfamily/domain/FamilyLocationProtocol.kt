package com.vayunmathur.findfamily.domain

/**
 * Wire contract for the cross-app live family-location channel exposed by
 * [FamilyLocationService].
 *
 * A Messenger-based protocol is used (rather than AIDL) so the whole contract is
 * plain Kotlin with no generated stubs or shared module: the maps app declares a
 * byte-for-byte copy of these constants and talks to the service over the same
 * action + message codes + Bundle keys. The service is guarded by the existing
 * signature permission [PERMISSION] (both MA apps share the signing key), so only
 * co-signed callers can bind.
 */
object FamilyLocationProtocol {
    /** Implicit action the maps client resolves + binds. */
    const val ACTION = "com.vayunmathur.findfamily.action.FAMILY_LOCATIONS"

    /** Signature-level permission required to bind the service. */
    const val PERMISSION = "com.vayunmathur.findfamily.permissions.ACCESS_FAMILY"

    /** client → service: register the sender's `replyTo` Messenger for pushes. */
    const val MSG_REGISTER = 1

    /** client → service: stop pushing to the sender's `replyTo` Messenger. */
    const val MSG_UNREGISTER = 2

    /** service → client: a fresh family-locations snapshot in [Message.data]. */
    const val MSG_LOCATIONS = 3

    // Bundle keys for the MSG_LOCATIONS payload (parallel arrays, one entry per
    // family member with a known location). Parallel primitive arrays avoid any
    // shared Parcelable/serialization dependency across the two apps.
    const val KEY_IDS = "ids"
    const val KEY_NAMES = "names"
    const val KEY_LATS = "lats"
    const val KEY_LNGS = "lngs"
    const val KEY_TIMESTAMPS = "timestamps"
    const val KEY_BATTERIES = "batteries"
}
