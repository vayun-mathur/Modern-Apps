package com.vayunmathur.crypto

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import java.text.DecimalFormat
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10

@Composable
fun MaximizedRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        content()
    }
}

fun Double.displayAmount(): String {
    return if (abs(this) < 1) {
        // Up to 3 significant figures (no scientific notation)
        val ax = abs(this)
        if (ax == 0.0) return "0"

        // number of decimals needed so total sig figs = 3
        val digitsBeforeDecimal = floor(log10(ax)).toInt()  // negative for < 1
        val decimals = 3 - (digitsBeforeDecimal + 1)

        val df = DecimalFormat("#.${"#".repeat(decimals)}")
        df.format(this)
    } else {
        // Up to 2 decimals
        val df = DecimalFormat("#.##")
        df.format(this)
    }
}