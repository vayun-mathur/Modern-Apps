package com.vayunmathur.contacts.util

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

    /** Collator for the current locale, accent-insensitive but case-insensitive at PRIMARY level. */
    fun collator(): Collator = Collator.getInstance(Locale.getDefault()).apply {
        strength = Collator.PRIMARY
        decomposition = Collator.CANONICAL_DECOMPOSITION
    }

    /** Compare two display names using locale rules. */
    fun compareNames(a: String, b: String): Int = collator().compare(a, b)

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

    /** Comparator for contacts by display name, locale-aware. */
    val contactComparator: Comparator<Contact>
        get() = Comparator { a, b ->
            // Use a fresh collator per comparison chain to avoid threading issues;
            // Collator itself is not thread-safe, but compare within same chain reuses one.
            // For simplicity and small list sizes, create once per sort call via sortedWith.
            compareNames(a.name.value, b.name.value)
        }

    /** Sort a list of contacts locale-aware. */
    fun List<Contact>.sortedLocale(): List<Contact> {
        val col = collator()
        return sortedWith(compareBy(col) { it.name.value })
    }

    /** Sort a list of groups by name locale-aware. */
    fun <T> List<T>.sortedByNameLocale(selector: (T) -> String): List<T> {
        val col = collator()
        return sortedWith(compareBy(col, selector))
    }
}
