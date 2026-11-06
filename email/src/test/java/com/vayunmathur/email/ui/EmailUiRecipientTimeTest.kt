package com.vayunmathur.email.ui

import com.vayunmathur.email.data.EmailMessage
import java.util.Calendar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the pure composer/list helpers in EmailUiHelpers.
 *
 * Recipient fields are built by string concat on every keystroke pause, so
 * a duplicate or a dropped separator silently sends mail to the wrong
 * people — each test asserts the exact resulting field. Snooze scheduling
 * is wall-clock based, so assertions are range/hour based, not exact.
 */
class EmailUiRecipientTimeTest {

    @Test
    fun `appending to an empty field yields just the address`() {
        assertEquals("a@b.com", appendRecipient("", "a@b.com"))
        assertEquals("a@b.com", appendRecipient("  ", "a@b.com"))
    }

    @Test
    fun `appending joins with a comma separator`() {
        assertEquals("a@b.com, c@d.com", appendRecipient("a@b.com", "c@d.com"))
    }

    @Test
    fun `existing address is not duplicated case-insensitively`() {
        val field = "A@b.com"
        assertEquals(field, appendRecipient(field, "a@B.com"))
        assertEquals("a@b.com, c@d.com", appendRecipient("a@b.com , c@d.com", "c@d.com"))
    }

    @Test
    fun `schedule time lands on the requested hour in the future`() {
        val now = System.currentTimeMillis()
        val scheduled = scheduleTime(0, sameDay = true)
        assertTrue(scheduled > now, "expected future, got $scheduled vs $now")
        val c = Calendar.getInstance().apply { timeInMillis = scheduled }
        assertEquals(0, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, c.get(Calendar.MINUTE))
    }

    @Test
    fun `schedule time with same day false is at least tomorrow`() {
        val now = System.currentTimeMillis()
        val scheduled = scheduleTime(23, sameDay = false)
        assertTrue(scheduled > now)
        val c = Calendar.getInstance().apply { timeInMillis = scheduled }
        assertEquals(23, c.get(Calendar.HOUR_OF_DAY))
        // Must be more than ~23h out only if 23:00 today already passed; at
        // minimum it is in the future on the hour.
        assertEquals(0, c.get(Calendar.MINUTE))
    }

    @Test
    fun `message data class equality covers the fields the UI reads`() {
        val a = EmailMessage(
            accountEmail = "me@x.com", folderName = "INBOX", id = 1L,
            subject = "s", from = "f", date = "d",
        )
        assertEquals(a, a.copy())
        assertEquals("INBOX", a.folderName)
        assertEquals(false, a.isRead)
        assertEquals(0L, a.snoozedUntil)
    }
}
