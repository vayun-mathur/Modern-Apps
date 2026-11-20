package com.vayunmathur.code.data

/**
 * Host-test [TokenSealer]: Robolectric has no AndroidKeyStore, so tests seal with a
 * reversible marker transform instead of AES-GCM. Mirrors the real sealer's contract —
 * sealed values carry the `v1:` prefix, legacy values pass through, foreign sealed
 * values fail (null) rather than passing through.
 */
class FakeTokenSealer : TokenSealer {
    override fun seal(plain: String): String = SEALED_PREFIX + plain

    override fun unseal(stored: String): String? = when {
        stored.startsWith(SEALED_PREFIX) -> stored.removePrefix(SEALED_PREFIX)
        isSealed(stored) -> null
        else -> stored
    }

    override fun isSealed(stored: String): Boolean = stored.startsWith(KeystoreTokenSealer.PREFIX)

    private companion object {
        private const val SEALED_PREFIX = "v1:fake:"
    }
}
