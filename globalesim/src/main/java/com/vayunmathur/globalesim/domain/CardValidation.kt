package com.vayunmathur.globalesim.domain

import java.util.Calendar

/**
 * Pure card-field validation for the custom payment form. Integer/string
 * logic only — no Android, no Compose — so it unit-tests like [QuoteMath].
 *
 * The form never sends raw card data anywhere except into Stripe's
 * `PaymentMethodCreateParams`; these helpers only gate the Pay button and
 * render inline errors. Callers must still drop field references after
 * confirm (never log or persist them).
 */
object CardValidation {
    /** Parsed, validated card fields ready to build Stripe params. */
    data class ValidCard(
        val number: String,
        val expMonth: Int,
        val expYear: Int,
        val cvc: String,
    )

    private val DIGITS = Regex("""^\d+$""")
    private val EXPIRY = Regex("""^\s*(\d{1,2})\s*/\s*(\d{2}|\d{4})\s*$""")

    /** Card number with spaces/dashes stripped; null when not all digits. */
    fun cleanNumber(raw: String): String? {
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty() || digits.length > 19) return null
        if (!DIGITS.matches(digits)) return null
        return digits
    }

    /** Luhn checksum over a digit string. */
    fun passesLuhn(digits: String): Boolean {
        if (digits.length < 13 || digits.length > 19) return false
        var sum = 0
        var double = false
        for (i in digits.indices.reversed()) {
            var d = digits[i] - '0'
            if (double) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            double = !double
        }
        return sum % 10 == 0
    }

    fun numberError(raw: String): String? = when {
        raw.isBlank() -> null
        else -> {
            val digits = cleanNumber(raw)
            when {
                digits == null -> "Enter the card number without letters."
                !passesLuhn(digits) -> "That card number doesn't look right."
                else -> null
            }
        }
    }

    /** Parse "MM/YY" or "MM/YYYY" to (month, full year); null when malformed. */
    fun parseExpiry(raw: String): Pair<Int, Int>? {
        val match = EXPIRY.matchEntire(raw) ?: return null
        val month = match.groupValues[1].toIntOrNull() ?: return null
        if (month !in 1..12) return null
        val yearRaw = match.groupValues[2].toIntOrNull() ?: return null
        val year = if (match.groupValues[2].length == 2) 2000 + yearRaw else yearRaw
        if (year < 2000 || year > 2100) return null
        return month to year
    }

    fun expiryError(raw: String, now: Calendar = Calendar.getInstance()): String? {
        if (raw.isBlank()) return null
        val (month, year) = parseExpiry(raw) ?: return "Use MM/YY, like 08/28."
        val nowYear = now.get(Calendar.YEAR)
        val nowMonth = now.get(Calendar.MONTH) + 1
        if (year < nowYear || (year == nowYear && month < nowMonth)) {
            return "That card has expired."
        }
        return null
    }

    fun cvcError(raw: String): String? = when {
        raw.isBlank() -> null
        raw.length !in 3..4 || !DIGITS.matches(raw) -> "The 3–4 digit code on the card."
        else -> null
    }

    /** All three fields valid; returns the parsed card or null. */
    fun validCard(numberRaw: String, expiryRaw: String, cvcRaw: String): ValidCard? {
        if (numberError(numberRaw) != null) return null
        if (expiryError(expiryRaw) != null) return null
        if (cvcError(cvcRaw) != null) return null
        val number = cleanNumber(numberRaw) ?: return null
        val (month, year) = parseExpiry(expiryRaw) ?: return null
        return ValidCard(number, month, year, cvcRaw)
    }
}
