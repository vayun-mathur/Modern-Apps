package com.vayunmathur.globalesim.domain

import java.util.Calendar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CardValidationTest {
    @Test
    fun luhnAcceptsTestCards() {
        // Stripe test PANs.
        assertEquals(true, CardValidation.passesLuhn("4242424242424242"))
        assertEquals(true, CardValidation.passesLuhn("4000000000003220"))
        assertEquals(true, CardValidation.passesLuhn("378282246310005"))
    }

    @Test
    fun luhnRejectsTyposAndShort() {
        assertEquals(false, CardValidation.passesLuhn("4242424242424241"))
        assertEquals(false, CardValidation.passesLuhn("123"))
        assertEquals(false, CardValidation.passesLuhn("12345678901234567890"))
    }

    @Test
    fun numberErrorMessages() {
        assertNull(CardValidation.numberError(""))
        assertNull(CardValidation.numberError("4242 4242 4242 4242"))
        assertNotNull(CardValidation.numberError("4242 4242 4242 4241"))
        assertNotNull(CardValidation.numberError("abcd"))
    }

    @Test
    fun expiryParsesBothYearForms() {
        assertEquals(8 to 2028, CardValidation.parseExpiry("08/28"))
        assertEquals(8 to 2028, CardValidation.parseExpiry("8/2028"))
        assertNull(CardValidation.parseExpiry("13/28"))
        assertNull(CardValidation.parseExpiry("08-28"))
        assertNull(CardValidation.parseExpiry("abc"))
    }

    @Test
    fun expiryRejectsPast() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.JUNE, 15) }
        assertNull(CardValidation.expiryError("07/26", now))
        assertNull(CardValidation.expiryError("06/26", now))
        assertNotNull(CardValidation.expiryError("05/26", now))
        assertNotNull(CardValidation.expiryError("01/25", now))
        assertNotNull(CardValidation.expiryError("13/28", now))
        assertNull(CardValidation.expiryError("", now))
    }

    @Test
    fun cvcLengths() {
        assertNull(CardValidation.cvcError(""))
        assertNull(CardValidation.cvcError("123"))
        assertNull(CardValidation.cvcError("1234"))
        assertNotNull(CardValidation.cvcError("12"))
        assertNotNull(CardValidation.cvcError("12345"))
        assertNotNull(CardValidation.cvcError("12a"))
    }

    @Test
    fun validCardRoundTrip() {
        val now = Calendar.getInstance()
        val futureYear = (now.get(Calendar.YEAR) + 2) % 100
        val expiry = "08/%02d".format(futureYear)
        val card = CardValidation.validCard("4242 4242 4242 4242", expiry, "123")
        assertNotNull(card)
        assertEquals("4242424242424242", card.number)
        assertNull(CardValidation.validCard("4242424242424241", "08/30", "123"))
    }
}
