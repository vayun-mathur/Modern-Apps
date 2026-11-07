package com.vayunmathur.securemail.platform

import com.vayunmathur.securemail.data.Identities
import com.vayunmathur.securemail.data.MailIdentity
import com.vayunmathur.securemail.data.MailSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AliasAndIdentitiesTest {

    @Test fun claimWhileOfflineReportsRelayDidNotRespond() = runBlocking {
        // Key generation runs (native), then the socket claim returns null
        // with no session: the controller must surface that, store nothing.
        val store = FakeMailStore()
        val failing = AliasController(store)
        assertEquals(0, store.identityList.value.size)
        val error = assertFailsWith<IllegalStateException> {
            failing.claim("me@maemail.org", "passphrase-1", label = "", isPrimary = true)
        }
        assertEquals("Relay did not respond — try again.", error.message)
        assertEquals(0, store.identityList.value.size)
    }

    @Test fun createRandomWhileOfflineReportsRelayOffline() = runBlocking {
        val controller = AliasController(FakeMailStore())
        val error = assertFailsWith<IllegalStateException> {
            controller.createRandom("passphrase-1", "label")
        }
        assertEquals("Relay offline — try again when connected.", error.message)
    }

    @Test fun ensureMigratedBackfillsPrimaryOnce() = runBlocking {
        val store = FakeMailStore()
        val settings = MailSettings(address = "me@maemail.org", publicArmor = "PUB", fingerprintHex = "FP")
        Identities.ensureMigrated(store.identityDao(), settings, secretArmor = "SECRET")
        assertEquals(1, store.identityList.value.size)
        val identity = store.identityList.value.single()
        assertEquals(true, identity.isPrimary)
        assertEquals("SECRET", identity.secretArmor)
        // Idempotent: a second run with different data changes nothing.
        Identities.ensureMigrated(store.identityDao(), settings.copy(address = "other@maemail.org"), "X")
        assertEquals("me@maemail.org", store.identityList.value.single().address)
    }

    @Test fun ensureMigratedSkipsWhenUnconfiguredOrSecretMissing() = runBlocking {
        val store = FakeMailStore()
        Identities.ensureMigrated(store.identityDao(), MailSettings(), secretArmor = "S")
        assertEquals(0, store.identityList.value.size)
        Identities.ensureMigrated(
            store.identityDao(),
            MailSettings(address = "me@maemail.org"),
            secretArmor = null,
        )
        assertEquals(0, store.identityList.value.size)
    }

    @Test fun forDeliveryPrefersDeliveredToThenPrimary() = runBlocking {
        val primary = MailIdentity("me@maemail.org", "", "P", "F", "S", isPrimary = true, createdMillis = 1)
        val alias = MailIdentity("alias@maemail.org", "", "P2", "F2", "S2", isPrimary = false, createdMillis = 2)
        val store = FakeMailStore(identities = listOf(primary, alias))
        assertEquals(alias, Identities.forDelivery(store.identityDao(), "Alias@MaeMail.org"))
        assertEquals(primary, Identities.forDelivery(store.identityDao(), null))
        assertNull(Identities.forDelivery(FakeMailStore().identityDao(), null))
    }
}
