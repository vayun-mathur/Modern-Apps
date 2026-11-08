package com.vayunmathur.travel.ui

import java.util.Locale

/**
 * Short fare-condition chips for an offer, e.g. ["Refundable", "Changeable"].
 * Falls back to "Non-refundable" when a refund rule is explicitly disallowed.
 */
fun conditionsLabels(conditions: com.vayunmathur.travel.network.ConditionsDto): List<String> {
    val labels = mutableListOf<String>()
    val refund = conditions.refundBeforeDeparture
    val change = conditions.changeBeforeDeparture
    when {
        refund?.allowed == true -> labels.add("Refundable")
        refund != null -> labels.add("Non-refundable")
    }
    if (change?.allowed == true) labels.add("Changeable")
    return labels
}

/** A money label like "$412" / "€220.50" from a booking decimal-string amount. */
fun formatMoney(amount: String, currency: String): String {
    val value = amount.toDoubleOrNull() ?: return "$currency $amount"
    val symbol = when (currency.uppercase()) {
        "USD" -> "$"
        "EUR" -> "€"
        "GBP" -> "£"
        else -> currency.uppercase() + " "
    }
    // Pin the decimal separator: the device locale may use ',' (e.g. de-DE),
    // while booking amounts are always decimal-point strings.
    return symbol + (if (value % 1.0 == 0.0) value.toInt().toString() else "%.2f".format(Locale.US, value))
}

/** "510" minutes -> "8h 30m". */
fun formatDuration(minutes: Long): String {
    if (minutes <= 0) return ""
    val h = minutes / 60
    val m = minutes % 60
    return buildString {
        if (h > 0) append("${h}h")
        if (m > 0) {
            if (h > 0) append(" ")
            append("${m}m")
        }
    }
}

/** Stops label: "Nonstop" / "1 stop" / "N stops". */
fun stopsLabel(stops: Long): String = when (stops) {
    0L -> "Nonstop"
    1L -> "1 stop"
    else -> "$stops stops"
}

/** "125" -> "2:05" (mm:ss), clamped at zero. */
fun formatCountdown(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
