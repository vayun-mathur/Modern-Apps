package com.vayunmathur.contacts.pure

import com.vayunmathur.contacts.data.DefaultAccountKind
import com.vayunmathur.contacts.data.DefaultAccountSnapshot
import com.vayunmathur.contacts.data.DefaultAccountSource
import com.vayunmathur.contacts.data.LOCAL_ACCOUNT_TYPE
import com.vayunmathur.contacts.data.NewContactAccountPolicy
import com.vayunmathur.contacts.data.ProviderAccount
import com.vayunmathur.contacts.data.SIM_ACCOUNT_TYPE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #916: on Android 16+ the contacts provider refuses new contacts in the device-local
 * and provider SIM accounts while the default account for new contacts is a cloud
 * account. Pins which accounts the policy disables, that any other default state
 * changes nothing, and which provider errors map to the clear message.
 */
class NewContactAccountPolicyTest {

    private class FakeSource(
        private val snapshot: DefaultAccountSnapshot?,
        private val local: ProviderAccount? = null,
        private val sims: List<ProviderAccount> = emptyList(),
    ) : DefaultAccountSource {
        override fun defaultAccountForNewContacts() = snapshot
        override fun customLocalAccount() = local
        override fun providerSimAccounts() = sims
    }

    private val google = ProviderAccount("me@gmail.com", "com.google")
    private val oemLocal = ProviderAccount("Phone", "com.oem.local")
    private val oemSim = ProviderAccount("SIM1", "com.oem.sim")

    @Test
    fun cloudDefaultBlocksDeviceAndProviderSimTargets() {
        val restriction = NewContactAccountPolicy.resolve(
            FakeSource(DefaultAccountSnapshot(DefaultAccountKind.CLOUD, google), oemLocal, listOf(oemSim))
        )
        assertNotNull(restriction)
        assertEquals(google, restriction.cloudAccount)
        // The NULL device account, however the app spells it.
        assertTrue(restriction.blocks("", ""))
        assertTrue(restriction.blocks(null, null))
        // OEM-customised local account and SIM accounts registered with the provider.
        assertTrue(restriction.blocks(oemLocal.name, oemLocal.type))
        assertTrue(restriction.blocks(oemSim.name, oemSim.type))
    }

    @Test
    fun cloudDefaultLeavesOtherTargetsEnabled() {
        val restriction = NewContactAccountPolicy.resolve(
            FakeSource(DefaultAccountSnapshot(DefaultAccountKind.CLOUD, google), oemLocal, listOf(oemSim))
        )
        assertNotNull(restriction)
        assertFalse(restriction.blocks(google.name, google.type))
        // DAVx5 is not eligible as a default account and keeps working (matches the report).
        assertFalse(restriction.blocks("me@nextcloud", "bitfire.at.davdroid"))
        // The app's SIM targets write the card via content://icc/adn, outside the provider check.
        assertFalse(restriction.blocks("1", SIM_ACCOUNT_TYPE))
        // The app's named local accounts are ordinary accounts to the provider.
        assertFalse(restriction.blocks("Family", LOCAL_ACCOUNT_TYPE))
    }

    @Test
    fun nonCloudDefaultChangesNothing() {
        val states = listOf(
            null,
            DefaultAccountSnapshot(DefaultAccountKind.NOT_SET, null),
            DefaultAccountSnapshot(DefaultAccountKind.LOCAL, null),
            DefaultAccountSnapshot(DefaultAccountKind.SIM, oemSim),
        )
        for (state in states) {
            assertNull(
                NewContactAccountPolicy.resolve(FakeSource(state, oemLocal, listOf(oemSim))),
                "default state $state must not restrict any target",
            )
        }
    }

    @Test
    fun cloudStateWithoutUsableAccountChangesNothing() {
        val blankAccount = DefaultAccountSnapshot(DefaultAccountKind.CLOUD, ProviderAccount("", "com.google"))
        assertNull(NewContactAccountPolicy.resolve(FakeSource(blankAccount)))
        assertNull(NewContactAccountPolicy.resolve(FakeSource(DefaultAccountSnapshot(DefaultAccountKind.CLOUD, null))))
    }

    @Test
    fun providerRejectionMapsToCloudDefaultError() {
        val providerError = IllegalArgumentException(
            "Cannot add contacts to local or SIM accounts when default account is set to cloud"
        )
        assertTrue(NewContactAccountPolicy.isCloudDefaultRejection(providerError))
        assertTrue(NewContactAccountPolicy.isCloudDefaultRejection(IllegalStateException("batch", providerError)))
    }

    @Test
    fun otherProviderErrorsDoNotMap() {
        assertFalse(
            NewContactAccountPolicy.isCloudDefaultRejection(
                IllegalArgumentException("Must specify both or neither of ACCOUNT_NAME and ACCOUNT_TYPE")
            )
        )
        assertFalse(NewContactAccountPolicy.isCloudDefaultRejection(IllegalArgumentException()))
        assertFalse(
            NewContactAccountPolicy.isCloudDefaultRejection(
                SecurityException("Cannot add contacts to local or SIM accounts when default account is set to cloud")
            )
        )
    }
}
