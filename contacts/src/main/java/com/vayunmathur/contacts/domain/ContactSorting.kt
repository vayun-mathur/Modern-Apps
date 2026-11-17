package com.vayunmathur.contacts.domain

import com.vayunmathur.contacts.data.Contact
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/**
 * Locale-aware sorting utilities for contacts.
 *
 * The previous implementation used `sortedBy { it.name.value }` which sorts by
 * Unicode code-point order. Accented characters like É (U+00C9 / 233) then sort
 * after all ASCII letters, placing "Véronique" after "Victor", instead of treating
 * É as E.
 *
 * Using [Collator] with the device default locale provides proper linguistic ordering:
 *  - Base letters are compared first, accents second (with PRIMARY/SECONDARY strength).
 *  - So "Véronique" (Ve…) correctly sorts before "Victor" (Vi…).
 *
 * For section headers we also strip diacritics so that "Émile" groups under 'E',
 * not under a separate 'É' section.
 */
object ContactSorting {

    /** Which name field leads the list ordering. Persisted; defaults to [FirstName]. */
    enum class ContactSortMode { FirstName, LastName }

    /** Collator for the current locale, accent-insensitive but case-insensitive at PRIMARY level. */
    fun collator(): Collator = Collator.getInstance(Locale.getDefault()).apply {
        strength = Collator.PRIMARY
        decomposition = Collator.CANONICAL_DECOMPOSITION
    }

    private val DIACRITICS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")

    /**
     * Returns a normalized section header Char for [displayName].
     * Strips accents and uppercases: "é" → 'E', "ç" → 'C', etc.
     * Non-letters → '#'.
     */
    fun groupKey(displayName: String): Char {
        val first = displayName.trim().firstOrNull() ?: return '#'
        // Decompose and strip combining marks: É -> E + ´ -> E
        val decomposed = Normalizer.normalize(first.toString(), Normalizer.Form.NFD)
        val stripped = decomposed.replace(DIACRITICS_REGEX, "")
        val base = stripped.firstOrNull() ?: first
        val upper = base.uppercaseChar()
        return if (upper.isLetter()) upper else '#'
    }

    /** Sort a list of contacts locale-aware. */
    fun List<Contact>.sortedLocale(): List<Contact> {
        val col = collator()
        // Collation keys, not repeated compares. Collator.compare re-analyses both strings on
        // every comparison, so an n-element sort pays O(n log n) analyses; generating a key per
        // contact makes it O(n), and the sort itself then compares cheap byte arrays. This runs
        // on the UI thread from the contact list's grouping, on every change to the list.
        return map { it to col.getCollationKey(it.name.value) }
            .sortedWith(compareBy { it.second })
            .map { it.first }
    }

    /**
     * The name [contact] sorts and groups under in [mode].
     *
     * Single-name contacts (blank last name) fall back to the full display value so they
     * group under their actual name instead of '#'/empty — e.g. "Madonna" stays under 'M'.
     */
    fun Contact.sortDisplayName(mode: ContactSortMode): String {
        if (mode == ContactSortMode.FirstName) return name.value
        val last = name.lastName.trim()
        val first = name.firstName.trim()
        return when {
            last.isNotEmpty() && first.isNotEmpty() -> "$last $first"
            last.isNotEmpty() -> last
            name.value.isNotBlank() -> name.value
            else -> first
        }
    }

    /** Section header for [contact] in [mode]: normalized first letter of its sort name. */
    fun Contact.sectionKey(mode: ContactSortMode): Char = groupKey(sortDisplayName(mode))

    /** Locale-aware sort by [mode]'s sort name, same collation-key approach as [sortedLocale]. */
    fun List<Contact>.sortedByMode(mode: ContactSortMode): List<Contact> {
        val col = collator()
        return map { it to col.getCollationKey(it.sortDisplayName(mode)) }
            .sortedWith(compareBy { it.second })
            .map { it.first }
    }
    /** Sort a list of groups by name locale-aware. */
    fun <T> List<T>.sortedByNameLocale(selector: (T) -> String): List<T> {
        val col = collator()
        return map { it to col.getCollationKey(selector(it)) }
            .sortedWith(compareBy { it.second })
            .map { it.first }
    }
}
