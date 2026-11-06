package com.vayunmathur.contacts.pure

import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.data.ContactPrefill
import com.vayunmathur.contacts.data.PrefillValue
import com.vayunmathur.contacts.ui.calculateAge
import com.vayunmathur.contacts.ui.formatPhoneNumber
import com.vayunmathur.contacts.util.PackageUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pure + Robolectric coverage for small contact helpers: phone formatting
 * ([formatPhoneNumber]), birthday age ([calculateAge]), intent prefill
 * ([ContactPrefill]), and app-presence checks ([PackageUtils]).
 *
 * libphonenumber runs on the JVM; the package-manager checks run under
 * Robolectric where unknown packages are simply absent.
 */
@RunWith(RobolectricTestRunner::class)
class ContactSmallHelpersTest {

    // -- formatPhoneNumber -----------------------------------------------------

    @Test
    fun `us number formats nationally by default`() {
        assertEquals("(415) 555-0111", formatPhoneNumber("+14155550111"))
    }

    @Test
    fun `foreign number formats internationally`() {
        assertEquals("+44 20 7946 0018", formatPhoneNumber("+442079460018"))
    }

    @Test
    fun `garbage passes through unchanged`() {
        assertEquals("not-a-number", formatPhoneNumber("not-a-number"))
        assertEquals("", formatPhoneNumber(""))
        assertEquals("123", formatPhoneNumber("123"))
    }

    // -- calculateAge -----------------------------------------------------------

    @Test
    fun `age counts completed years`() {
        val today = LocalDate(2026, 11, 6)
        assertEquals(36, calculateAge(LocalDate(1990, 5, 6), today))
        // Birthday later this year: not yet.
        assertEquals(35, calculateAge(LocalDate(1990, 11, 7), today))
        assertEquals(36, calculateAge(LocalDate(1990, 11, 6), today))
    }

    @Test
    fun `age is null without a year or in the future`() {
        val today = LocalDate(2026, 11, 6)
        assertNull(calculateAge(LocalDate(1899, 1, 1), today))
        assertNull(calculateAge(LocalDate(2030, 1, 1), today))
    }

    // -- ContactPrefill ----------------------------------------------------------

    @Test
    fun `prefill defaults to empty and exposes primaries`() {
        val empty = ContactPrefill()
        assertNull(empty.name)
        assertNull(empty.primaryPhone)
        assertNull(empty.primaryEmail)
        val full = ContactPrefill(
            name = "Ada",
            company = "Acme",
            notes = "n",
            nickname = "Al",
            phones = listOf(PrefillValue("+1555", 2, "Work"), PrefillValue("+1666")),
            emails = listOf(PrefillValue("a@b.c")),
            postals = listOf(PrefillValue("123 Main")),
        )
        assertEquals("+1555", full.primaryPhone)
        assertEquals("a@b.c", full.primaryEmail)
        assertEquals(full, full.copy())
    }

    // -- PackageUtils -------------------------------------------------------------

    @Test
    fun `unknown packages are not installed`() {
        val context: android.content.Context = ApplicationProvider.getApplicationContext()
        assertFalse(PackageUtils.isAppInstalled(context, "com.example.nope"))
        assertFalse(PackageUtils.isSignalInstalled(context))
        assertFalse(PackageUtils.isWhatsAppInstalled(context))
        assertFalse(PackageUtils.isTelegramInstalled(context))
        assertFalse(PackageUtils.isGoogleMeetInstalled(context))
    }

    @Test
    fun `installed package reports true`() {
        val context: android.content.Context = ApplicationProvider.getApplicationContext()
        // The test package itself is always installed.
        assertTrue(PackageUtils.isAppInstalled(context, context.packageName))
    }
}
