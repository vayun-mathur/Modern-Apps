package com.vayunmathur.crypto

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.round

@Composable
fun MaximizedRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        content()
    }
}

fun Double.displayAmount(): String {
    val absoluteValue = abs(this)
    if (absoluteValue == 0.0) return "0"

    return if (absoluteValue < 1.0) {
        // Calculate the number of decimals needed for 3 significant figures
        // log10(0.00123) is -3, so we need more precision for smaller numbers
        val magnitude = floor(log10(absoluteValue)).toInt()
        val decimals = (2 - magnitude).coerceAtLeast(0)

        // Use format string: "%.Nf" where N is the number of decimals
        "%.${decimals}f".format(this)
    } else {
        // Round to up to 2 decimal places
        val rounded = round(this * 100) / 100.0
        return rounded.toString()
    }.trimEnd('0').trimEnd('.')
}