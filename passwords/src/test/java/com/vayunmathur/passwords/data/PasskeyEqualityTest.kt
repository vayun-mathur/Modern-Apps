package com.vayunmathur.passwords.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * `viewModel.passkeys` is a StateFlow, which conflates by equality. If the link column were left
 * out of [Passkey.equals] a link-only change would produce an equal list and the emission would be
 * dropped, so linking and unlinking would silently do nothing in the UI.
 */
class PasskeyEqualityTest {

    @Test fun aLinkOnlyChangeIsNotEqual() {
        val pk = Passkey(id = 1, rpId = "example.com", credentialId = "cred", userId = "user")
        val linked = pk.copy(linkedPasswordSyncId = "0123456789abcdef0123456789abcdef")
        val detached = pk.copy(linkedPasswordSyncId = PASSKEY_LINK_DETACHED)

        assertNotEquals(pk, linked)
        assertNotEquals(pk, detached)
        assertNotEquals(linked, detached)
        assertNotEquals(listOf(pk), listOf(linked))
        assertNotEquals(pk.hashCode(), linked.hashCode())
    }

    /**
     * `WebAuthnAuthenticator.signAssertion` persists only a `signCount`/`lastUsedTime`
     * bump. If those fields stay out of [Passkey.equals] the re-emitted row compares
     * equal to the previous one, the `passkeys` StateFlow drops the emission, and the
     * "last used" card in the UI never updates.
     */
    @Test fun aSignCountOnlyChangeIsNotEqual() {
        val pk = Passkey(id = 1, rpId = "example.com", credentialId = "cred", userId = "user")
        val used = pk.copy(signCount = pk.signCount + 1, lastUsedTime = pk.lastUsedTime + 1)

        assertNotEquals(pk, used)
        assertNotEquals(listOf(pk), listOf(used))
        assertNotEquals(pk.hashCode(), used.hashCode())
    }

    @Test fun aDisplayFieldChangeIsNotEqual() {
        val pk = Passkey(id = 1, rpId = "example.com", credentialId = "cred", userId = "user")

        assertNotEquals(pk, pk.copy(rpName = "Example"))
        assertNotEquals(pk, pk.copy(userName = "alice"))
        assertNotEquals(pk, pk.copy(userDisplayName = "Alice"))
    }

    @Test fun equalKeyBytesContentIsEqual() {
        val a = Passkey(
            id = 1,
            rpId = "example.com",
            credentialId = "cred",
            userId = "user",
            privateKeyBytes = byteArrayOf(1, 2, 3),
        )
        val b = a.copy(privateKeyBytes = byteArrayOf(1, 2, 3))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, a.copy(privateKeyBytes = byteArrayOf(4, 5, 6)))
    }
}
