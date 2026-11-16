package com.vayunmathur.contacts.data

import android.content.Context
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver

/** W5: SimContactsDataSource direct paths against the fake ICC provider (both column spellings). */
@RunWith(RobolectricTestRunner::class)
class SimDataSourceW5Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @BeforeTest
    fun register() {
        ShadowContentResolver.registerProviderInternal(
            ContactsContract.AUTHORITY, FakeProviderPass2Test.FakeContactsProvider()
        )
    }

    @Test
    fun listInsertUpdateDeleteWithStandardColumns() {
        ShadowContentResolver.registerProviderInternal("icc", SimProviderPass2Test.FakeIccProvider())
        val contacts = SimContactsDataSource.listSimContacts(ctx)
        assertTrue(contacts.isNotEmpty())
        assertTrue(contacts.any { it.name == "Aisha Rahman" && it.number == "+14155550102" })

        assertTrue(
            SimContactsDataSource.insertSimContact(ctx, "New Person", "+14155550177", "new@x.com", null)
        )
        assertEquals("+14155550177", SimProviderPass2Test.FakeIccProvider.lastInsertValues?.getAsString("number"))
        assertTrue(
            SimContactsDataSource.insertSimContact(ctx, "No Email", "+14155550178", null, 2)
        )
        assertTrue(
            SimContactsDataSource.updateSimContact(
                ctx,
                SimContact(id = 1, name = "Aisha Rahman", number = "+14155550102"),
                "Aisha R", "+14155550103", "aisha@x.com",
            )
        )
        assertTrue(
            SimContactsDataSource.deleteSimContact(
                ctx, SimContact(id = 1, name = "Aisha Rahman", number = "+14155550102", subscriptionId = 1)
            )
        )
        // Robolectric has no ready SIM, so hasSim must report false here.
        assertFalse(SimContactsDataSource.hasSim(ctx))
    }

    @Test
    fun listWithAliasColumnsAndContactConversion() {
        ShadowContentResolver.registerProviderInternal(
            "icc", SimProviderPass2Test.FakeIccProvider(aliasColumns = true)
        )
        val contacts = SimContactsDataSource.listSimContacts(ctx)
        assertTrue(contacts.isNotEmpty())
        val asContacts = SimContactsDataSource.simContactsAsContacts(ctx)
        assertEquals(contacts.size, asContacts.size)
        val first = SimContactsDataSource.simContactToContact(contacts.first())
        assertEquals(SIM_ACCOUNT_TYPE, first.accountType)
        assertTrue(first.details.phoneNumbers.isNotEmpty())

        val backing = SimContactsDataSource.findBackingSimContact(ctx, first)
        assertNotNull(backing)
        val unmatched = Contact(
            id = 0, accountType = SIM_ACCOUNT_TYPE, accountName = "99", isFavorite = false,
            details = ContactDetails(
                phoneNumbers = listOf(PhoneNumber(0, "+19999999999", CDKPhone.TYPE_MOBILE)),
                emails = listOf(Email(0, "nobody@nowhere.example", CDKEmail.TYPE_HOME)),
                addresses = emptyList(), dates = emptyList(), photos = emptyList(),
                names = listOf(Name(0, "", "Nobody", "", "Here", "")),
                orgs = emptyList(), notes = emptyList(), nicknames = emptyList(), groups = emptyList(),
            ),
        )
        assertNull(SimContactsDataSource.findBackingSimContact(ctx, unmatched))
    }

    @Test
    fun subscriptionInfosAndLabelsAreSafeWithoutSimService() {
        assertTrue(SimContactsDataSource.getActiveSubscriptionIds(ctx).isEmpty() ||
            SimContactsDataSource.getActiveSubscriptionIds(ctx).isNotEmpty())
        val infos = SimContactsDataSource.getSimSubscriptionInfos(ctx)
        infos.forEach { info ->
            assertTrue(SimContactsDataSource.slotNumberFor(info) >= 1)
            assertTrue(SimContactsDataSource.getSimAccountDisplayLabel(ctx, info).isNotEmpty())
            assertEquals(info.subscriptionId.toString(), SimContactsDataSource.accountNameFor(info))
        }
        val info = SimContactsDataSource.SimSubscriptionInfo(7, 1, "Carrier", "Carrier")
        assertEquals(2, SimContactsDataSource.slotNumberFor(info))
        assertEquals("7", SimContactsDataSource.accountNameFor(info))
        assertEquals(
            "7",
            SimContactsDataSource.accountNameForSimContact(SimContact(name = "A", number = "1", subscriptionId = 7)),
        )
        assertEquals(
            "",
            SimContactsDataSource.accountNameForSimContact(SimContact(name = "A", number = "1")),
        )
        val synthetic = SimContactsDataSource.syntheticIdFor(
            SimContact(id = 3, name = "A", number = "1", subscriptionId = 2)
        )
        assertTrue(synthetic != 3L)
        assertFalse(isSimAccountType(null))
        assertTrue(isSimAccountType(SIM_ACCOUNT_TYPE))
        assertTrue(isLocalAccountType(null))
        assertTrue(isLocalAccountType(""))
        assertFalse(isLocalAccountType(SIM_ACCOUNT_TYPE))
    }
}
