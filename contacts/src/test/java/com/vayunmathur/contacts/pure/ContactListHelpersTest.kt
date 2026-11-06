package com.vayunmathur.contacts.pure

import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.ContactGroup
import com.vayunmathur.contacts.data.GroupMembership
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.data.Nickname
import com.vayunmathur.contacts.ui.contactGroupsOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-JVM coverage for contact list helpers ([contactGroupsOf]): the
 * group chips on a contact row show exactly the non-blank groups the
 * contact belongs to, matched by group id.
 */
class ContactListHelpersTest {

    private fun contact(groupIds: List<Long> = emptyList(), name: String = "Ada"): Contact {
        val details = ContactDetails.empty().copy(
            names = listOf(Name(1, "", name, "", "", "")),
            nicknames = listOf(Nickname(1, "", 1)),
            groups = groupIds.mapIndexed { i, gid -> GroupMembership(i.toLong(), gid) },
        )
        return Contact(id = 3L, accountType = null, accountName = null, isFavorite = false, details = details)
    }

    @Test
    fun `membership resolves to named groups only`() {
        val c = contact(listOf(7L, 9L))
        val groups = listOf(
            ContactGroup(id = 7L, name = "Family"),
            ContactGroup(id = 8L, name = "Work"),
            ContactGroup(id = 9L, name = "   "),
        )
        assertEquals(listOf(ContactGroup(id = 7L, name = "Family")), contactGroupsOf(c, groups))
    }

    @Test
    fun `no memberships yields no groups`() {
        val groups = listOf(ContactGroup(id = 7L, name = "Family"))
        assertTrue(contactGroupsOf(contact(), groups).isEmpty())
        assertTrue(contactGroupsOf(contact(listOf(7L)), emptyList()).isEmpty())
    }

    @Test
    fun `unknown group ids are ignored`() {
        val groups = listOf(ContactGroup(id = 7L, name = "Family"))
        assertTrue(contactGroupsOf(contact(listOf(42L)), groups).isEmpty())
    }
}
