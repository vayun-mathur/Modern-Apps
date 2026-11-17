package com.vayunmathur.cast.protocol

import java.security.SecureRandom

/** Whether a pair attempt was accepted, and what the user should be told if it was not. */
sealed interface PairResult {

    /** Paired. [deviceKey] is non-null when this was a code pairing and is what both ends persist. */
    data class Ok(val deviceKey: ByteArray?) : PairResult

    /**
     * Wrong proof.
     *
     * [codeChanged] means the attempt limit was reached and the code on screen has been replaced, so
     * the phone has to say "look at the TV again" rather than "try again".
     */
    data class Wrong(val attemptsLeft: Int, val codeChanged: Boolean) : PairResult
}

/**
 * The receiver's side of trust: the code on screen, the attempt limit, and proof verification.
 *
 * The attempt limit is what makes six digits defensible. Bound to the transcript, a proof cannot be
 * produced by a man in the middle at all (see [SessionKeys.pairProof]); the only attack left is
 * guessing, and one in a million per attempt with three attempts and then a fresh code is not a
 * guessing attack worth mounting. Deliberately **not** a PAKE: a PAKE would let the limit go away,
 * nothing in this repo has one, and SPAKE2 for this threat model is not worth its own sub-project.
 *
 * Pure, so all of it - correct code, wrong code, lockout, and a remembered device - is a JVM test.
 * [newCode] is injectable for exactly that reason.
 */
class PairingGate(private val newCode: () -> String = { PairCode.random() }) {

    /** The six digits currently on screen. */
    var code: String = newCode()
        private set

    var attemptsLeft: Int = PairCode.MAX_ATTEMPTS
        private set

    /**
     * Verify a proof of the displayed code.
     *
     * On success a fresh device key is minted and returned, so later sessions can skip the code
     * entirely. On the third failure the code is thrown away and a new one generated; anything the
     * attacker learned about the old one is worth nothing.
     */
    fun verifyCode(keys: SessionKeys, transcript: ByteArray, proof: ByteArray): PairResult {
        val expected = keys.pairProof(code, transcript)
        if (proofsMatch(expected, proof)) {
            reset()
            return PairResult.Ok(newDeviceKey())
        }
        attemptsLeft--
        if (attemptsLeft > 0) return PairResult.Wrong(attemptsLeft, codeChanged = false)
        reset()
        return PairResult.Wrong(attemptsLeft = PairCode.MAX_ATTEMPTS, codeChanged = true)
    }

    /**
     * Verify a proof of possession of a remembered [deviceKey].
     *
     * No attempt limit and no lockout: [deviceKey] is 32 random bytes, so there is nothing here worth
     * rate-limiting, and locking out on failure would let anyone on the LAN deny a paired phone
     * access by sending rubbish. A failure just means this phone is not the one we remember, and it
     * falls back to the code.
     */
    fun verifyDevice(
        keys: SessionKeys,
        transcript: ByteArray,
        deviceKey: ByteArray,
        proof: ByteArray,
    ): PairResult =
        if (proofsMatch(keys.deviceProof(deviceKey, transcript), proof)) {
            // Nothing new to persist - the phone already holds the key it just proved.
            PairResult.Ok(deviceKey = null)
        } else {
            PairResult.Wrong(attemptsLeft = attemptsLeft, codeChanged = false)
        }

    /** Put a new code on screen, e.g. when a session ends without pairing. */
    fun reset() {
        code = newCode()
        attemptsLeft = PairCode.MAX_ATTEMPTS
    }

    /** Device-key constants and minting for [PairingGate]. */
    companion object {
        const val DEVICE_KEY_BYTES = 32

        /** The long-term secret a paired phone keeps. Random, never derived from the code. */
        fun newDeviceKey(random: SecureRandom = SecureRandom()): ByteArray =
            ByteArray(DEVICE_KEY_BYTES).also { random.nextBytes(it) }
    }
}
