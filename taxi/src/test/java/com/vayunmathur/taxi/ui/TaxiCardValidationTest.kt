package com.vayunmathur.taxi.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.taxi.R
import com.vayunmathur.taxi.data.NewCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Card validation (Luhn/expiry/CVC), expiry parsing and the add-card dialog. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaxiCardValidationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int): String = context.getString(id)

    @Test
    fun luhnAcceptsTestPanRejectsCorrupted() {
        assertTrue(luhnValid("4111111111111111"))
        assertTrue(luhnValid("4242424242424242"))
        assertFalse(luhnValid("4111111111111112"))
        assertFalse(luhnValid("1234567812345678"))
    }

    @Test
    fun parseExpiryValidAndMalformed() {
        assertEquals(12 to 2030, parseExpiry("12/30"))
        assertEquals(1 to 2028, parseExpiry("0128"))
        assertNull(parseExpiry("13/30"))
        assertNull(parseExpiry("00/30"))
        assertNull(parseExpiry("1/30"))
        assertNull(parseExpiry("abcd"))
        assertNull(parseExpiry(""))
    }

    @Test
    fun validateCardSuccessNormalisesDigits() {
        val result = validateCard(context, "4111 1111 1111 1111", "12/99", "123", " 94101 ")
        assertTrue(result.isSuccess)
        val card = result.getOrThrow()
        assertEquals("4111111111111111", card.number)
        assertEquals(12, card.expMonth)
        assertEquals(2099, card.expYear)
        assertEquals("94101", card.postalCode)
        assertEquals("1111", card.last4)
    }

    @Test
    fun validateCardRejectsBadNumberExpiryAndCvc() {
        assertEquals(
            str(R.string.card_invalid_number),
            validateCard(context, "4111111111111112", "12/99", "123", "94101").exceptionOrNull()?.message,
        )
        assertEquals(
            str(R.string.card_invalid_number),
            validateCard(context, "411", "12/99", "123", "94101").exceptionOrNull()?.message,
        )
        assertEquals(
            str(R.string.card_invalid_expiry),
            validateCard(context, "4111111111111111", "13/99", "123", "94101").exceptionOrNull()?.message,
        )
        assertEquals(
            str(R.string.card_expired),
            validateCard(context, "4111111111111111", "01/20", "123", "94101").exceptionOrNull()?.message,
        )
        assertEquals(
            str(R.string.card_invalid_cvc),
            validateCard(context, "4111111111111111", "12/99", "12", "94101").exceptionOrNull()?.message,
        )
        assertEquals(
            str(R.string.card_invalid_cvc),
            validateCard(context, "4111111111111111", "12/99", "12345", "94101").exceptionOrNull()?.message,
        )
    }

    @Test
    fun addCardDialogSubmitsTypedCard() {
        var submitted: Pair<NewCard, Boolean>? = null
        composeRule.setContent {
            AddCardDialog(adding = false, error = null, onDismiss = {}, onSubmit = { c, d -> submitted = c to d })
        }
        composeRule.onNodeWithText(str(R.string.card_number)).performTextInput("4111111111111111")
        composeRule.onNodeWithText(str(R.string.card_expiry)).performTextInput("1299")
        composeRule.onNodeWithText(str(R.string.card_cvc)).performTextInput("123")
        composeRule.onNodeWithText(str(R.string.card_zip)).performTextInput("94101")
        assertEquals(null, submitted)
    }
}
