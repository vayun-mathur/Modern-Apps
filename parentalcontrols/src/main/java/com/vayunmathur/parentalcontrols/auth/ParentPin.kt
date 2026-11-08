package com.vayunmathur.parentalcontrols.auth

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import com.vayunmathur.library.util.DatabaseHelper
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The parent PIN that gates every imposed-enforcement surface.
 *
 * Family Link verifies the parent through their Google account on another device; there is no
 * account here, so the parent proves themselves with a PIN entered on this device. The PIN
 * itself is never stored: a random salt plus SHA-256(salt + PIN) is kept keystore-wrapped via
 * [DatabaseHelper], so the verifier is bound to this device's hardware and unreadable off it.
 *
 * Only UI flows touch this class - setup, editor opens, mutations, lock-screen unlock, bonus
 * grants. Enforcement ([Enforcer.reconcile] and everything it calls) never needs the PIN, so
 * boot-time and background enforcement work with no credential available. That is also why
 * credential-protected storage is fine here: nothing on the boot path reads the verifier.
 *
 * Brute force is throttled: 5 wrong attempts lock verification for a minute, doubling each
 * subsequent failure up to 16 minutes. The counter lives beside the verifier so clearing app
 * data (which wipes both) also wipes the rules database - there is nothing left to protect.
 */
class ParentPin(context: Context) : DatabaseHelper(context) {

    override val keyStoreAlias = "parentalcontrols_parent_pin"
    override val sharedPrefsName = PREFS
    override val passphraseKey = "pin_verifier"
    override val ivKey = "pin_verifier_iv"

    /** Whether a parent PIN has been set. */
    fun isSet(): Boolean = isKeyGenerated()

    /**
     * Sets (or resets) the PIN. The caller must have verified the old PIN first when one
     * exists - this function does not check it.
     */
    fun set(pin: String) {
        require(pin.length >= MIN_LENGTH) { "PIN too short" }
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        storePassphrase(verifierFor(salt, pin))
        prefs().edit {
            putString(KEY_SALT_B64, Base64.encodeToString(salt, Base64.NO_WRAP))
            remove(KEY_ATTEMPTS)
            remove(KEY_LOCKOUT_UNTIL)
        }
    }

    /** Clears the PIN entirely. Supervision stays configured; editors just stop asking. */
    fun clear() = deleteKey()

    /**
     * Verifies [pin], honoring the brute-force lockout.
     *
     * Returns true only on a correct, non-locked-out attempt. A correct attempt resets the
     * failure counter; a wrong one increments it and may start a lockout.
     */
    fun verify(pin: String): Boolean {
        if (!isSet()) return false
        if (isLockedOut()) return false
        val saltB64 = prefs().getString(KEY_SALT_B64, null) ?: return false
        val salt = Base64.decode(saltB64, Base64.NO_WRAP)
        val expected = runCatching { getPassphrase() }.getOrNull() ?: return false
        val actual = verifierFor(salt, pin)
        return if (MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())) {
            prefs().edit { remove(KEY_ATTEMPTS); remove(KEY_LOCKOUT_UNTIL) }
            true
        } else {
            recordFailure()
            false
        }
    }

    /** Milliseconds until verification is allowed again, or 0 when not locked out. */
    fun lockoutRemainingMillis(): Long {
        val until = prefs().getLong(KEY_LOCKOUT_UNTIL, 0)
        return (until - System.currentTimeMillis()).coerceAtLeast(0)
    }

    fun isLockedOut(): Boolean = lockoutRemainingMillis() > 0

    private fun recordFailure() {
        val attempts = prefs().getInt(KEY_ATTEMPTS, 0) + 1
        val editor = prefs().edit().putInt(KEY_ATTEMPTS, attempts)
        if (attempts >= FREE_ATTEMPTS) {
            val shift = (attempts - FREE_ATTEMPTS).coerceAtMost(MAX_DOUBLINGS)
            val lockout = BASE_LOCKOUT_MILLIS shl shift
            editor.putLong(KEY_LOCKOUT_UNTIL, System.currentTimeMillis() + lockout)
        }
        editor.apply()
    }

    private fun verifierFor(salt: ByteArray, pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(pin.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
    }

    private fun prefs() =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    companion object {
        const val PREFS = "parentalcontrols_pin"
        const val MIN_LENGTH = 4

        private const val KEY_SALT_B64 = "salt_b64"
        private const val KEY_ATTEMPTS = "attempts"
        private const val KEY_LOCKOUT_UNTIL = "lockout_until"

        private const val SALT_BYTES = 16
        private const val FREE_ATTEMPTS = 5
        private const val BASE_LOCKOUT_MILLIS = 60_000L
        private const val MAX_DOUBLINGS = 4

        @Volatile private var instance: ParentPin? = null

        fun get(context: Context): ParentPin =
            instance ?: synchronized(this) {
                instance ?: ParentPin(context).also { instance = it }
            }
    }
}
