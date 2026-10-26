package com.vayunmathur.shop.domain

import com.vayunmathur.shop.data.Money
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** Formats ISO 4217 minor units without floating-point rounding. */
object MoneyFormat {
    private fun currency(money: Money): Currency? =
        runCatching { Currency.getInstance(money.currency.uppercase(Locale.US)) }.getOrNull()

    private fun decimalAmount(money: Money): BigDecimal =
        BigDecimal.valueOf(money.amount).movePointLeft(
            currency(money)?.defaultFractionDigits?.takeIf { it >= 0 } ?: 2,
        )

    fun majorUnits(money: Money): Double = decimalAmount(money).toDouble()

    fun format(money: Money, locale: Locale = Locale.US): String {
        val currency = currency(money)
            ?: return "${money.currency.uppercase(Locale.US)} ${decimalAmount(money).toPlainString()}"
        val digits = currency.defaultFractionDigits.coerceAtLeast(0)
        return NumberFormat.getCurrencyInstance(locale).apply {
            this.currency = currency
            minimumFractionDigits = digits
            maximumFractionDigits = digits
        }.format(decimalAmount(money))
    }
}
