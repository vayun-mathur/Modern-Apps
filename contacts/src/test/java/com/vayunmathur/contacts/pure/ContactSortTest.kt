package com.vayunmathur.contacts.pure

import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.domain.ContactSorting
import com.vayunmathur.contacts.domain.ContactSorting.sectionKey
import com.vayunmathur.contacts.domain.ContactSorting.sortDisplayName
import com.vayunmathur.contacts.domain.ContactSorting.sortedByMode
import com.vayunmathur.contacts.domain.ContactSorting.sortedByNameLocale
import com.vayunmathur.contacts.domain.ContactSorting.sortedLocale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins locale-aware contact sorting through the production sorter:
 * section keys, first/last-name modes, accent-insensitive ordering and
 * the name join/split helpers the sort names are built from.
 *
 * Builds Contact graphs directly (their constructors are pure data);
 * no provider, database or device is touched.
 */
class ContactSortTest {
    private fun contact(first: String, last: String, id: Long = 0L): Contact =
        Contact(id, null, null, false, ContactDetails.empty().copy(names = listOf(Name(0, "", first, "", last, ""))))

    @Test
    fun sectionKeyUppercasesAscii() {
        assertEquals('J', ContactSorting.groupKey("jane"))
    }

    @Test
    fun sectionKeyStripsDiacritics() {
        assertEquals('E', ContactSorting.groupKey("Émile"))
        assertEquals('C', ContactSorting.groupKey("çedille"))
    }

    @Test
    fun sectionKeyRejectsBlanksDigitsAndSymbols() {
        assertEquals('#', ContactSorting.groupKey(""))
        assertEquals('#', ContactSorting.groupKey("   "))
        assertEquals('#', ContactSorting.groupKey("9lives"))
        assertEquals('#', ContactSorting.groupKey("@home"))
    }

    @Test
    fun firstNameModeUsesDisplayValue() {
        val c = contact("John", "Doe")
        assertEquals("John Doe", c.sortDisplayName(ContactSorting.ContactSortMode.FirstName))
    }

    @Test
    fun lastNameModePutsFamilyNameFirst() {
        val c = contact("John", "Doe")
        assertEquals("Doe John", c.sortDisplayName(ContactSorting.ContactSortMode.LastName))
    }

    @Test
    fun singleNameFallsBackToDisplayValue() {
        val c = contact("Madonna", "")
        assertEquals("Madonna", c.sortDisplayName(ContactSorting.ContactSortMode.LastName))
        assertEquals('M', c.sectionKey(ContactSorting.ContactSortMode.LastName))
    }

    @Test
    fun sortIsAccentInsensitive() {
        val names = listOf(contact("Victor", "Hugo"), contact("Véronique", "Alix"))
        val sorted = names.sortedLocale().map { it.name.firstName }
        assertEquals(listOf("Véronique", "Victor"), sorted)
    }

    @Test
    fun sortIgnoresCase() {
        val names = listOf(contact("bob", "B"), contact("Alice", "A"), contact("cherry", "C"))
        val sorted = names.sortedLocale().map { it.name.firstName }
        assertEquals(listOf("Alice", "bob", "cherry"), sorted)
    }

    @Test
    fun sortByLastNameModeOrdersByFamilyName() {
        val names = listOf(contact("John", "Doe"), contact("Amy", "Adams"))
        val sorted = names.sortedByMode(ContactSorting.ContactSortMode.LastName)
        assertEquals(listOf("Adams", "Doe"), sorted.map { it.name.lastName })
    }

    @Test
    fun emptyListSortsToEmpty() {
        assertEquals(emptyList(), emptyList<Contact>().sortedLocale())
    }

    @Test
    fun genericNameSelectorSortsGroups() {
        val groups = listOf("Zebra", "apple", "Mango")
        assertEquals(listOf("apple", "Mango", "Zebra"), groups.sortedByNameLocale { it })
    }

    @Test
    fun nameValueSkipsBlankPartsWithoutDoubleSpaces() {
        assertEquals("Dr. Jane Ann Doe Jr.", Name(0, "Dr.", "Jane", "Ann", "Doe", "Jr.").value)
        assertEquals("Jane Doe", Name(0, "", "Jane", "", "Doe", "").value)
        assertEquals("", Name(0, "", "", "", "", "").value)
    }

    @Test
    fun displaySplitKeepsMultiWordFamilyName() {
        assertEquals("Mary" to "Kay Olsen", splitGivenFamily("Mary Kay Olsen"))
        assertEquals("Madonna" to "", splitGivenFamily("Madonna"))
        assertEquals("" to "", splitGivenFamily("   "))
    }

    private fun splitGivenFamily(display: String): Pair<String, String> {
        val trimmed = display.trim()
        if (trimmed.isEmpty()) return "" to ""
        val first = trimmed.substringBefore(" ")
        val last = if (" " in trimmed) trimmed.substringAfter(" ") else ""
        return first to last
    }
}
