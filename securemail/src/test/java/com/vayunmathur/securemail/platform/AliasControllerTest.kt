package com.vayunmathur.securemail.platform

import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [AliasController] relay-failure branches (no live socket session in tests). */
@RunWith(RobolectricTestRunner::class)
class AliasControllerTest {

    @Test fun `createRandom refuses while the relay is offline`() = runBlocking {
        val store = FakeMailStore()
        val error = assertFailsWith<IllegalStateException> {
            AliasController(store).createRandom("passphrase-one", "Shopping")
        }
        assertEquals("Relay offline — try again when connected.", error.message)
        assertEquals(0, store.identities.count())
    }

    @Test fun `claim generates a key then fails when the relay does not respond`() = runBlocking {
        val store = FakeMailStore()
        val error = assertFailsWith<IllegalStateException> {
            AliasController(store).claim("alice@maemail.org", "passphrase-one", label = "", isPrimary = true)
        }
        assertEquals("Relay did not respond — try again.", error.message)
        // Nothing is stored unless the relay accepts the claim.
        assertEquals(0, store.identities.count())
        assertTrue(store.messages.snapshot().isEmpty())
    }
}
