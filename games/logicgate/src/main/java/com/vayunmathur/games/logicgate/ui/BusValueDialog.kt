package com.vayunmathur.games.logicgate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.logicgate.R
import com.vayunmathur.games.logicgate.domain.model.Pin
import com.vayunmathur.games.logicgate.domain.model.formatValue
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconKeyboardArrowLeft
import com.vayunmathur.library.ui.IconKeyboardArrowRight
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

/** Sets a bus input: a −/+ stepper plus one toggle per bit. Changes apply live. */
@Composable
fun BusValueDialog(pin: Pin, value: Int, onValue: (Int) -> Unit, onDismiss: () -> Unit) {
    var current by remember { mutableIntStateOf(value) }
    fun set(v: Int) {
        current = v and pin.mask
        onValue(current)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.lg_done)) } },
        title = { Text(stringResource(R.string.lg_set_value, pin.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { set(current - 1) }) { IconKeyboardArrowLeft() }
                    Text(
                        "${formatValue(current, pin.width)}  ·  $current",
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    IconButton(onClick = { set(current + 1) }) { IconKeyboardArrowRight() }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    for (bit in pin.width - 1 downTo 0) {
                        val on = (current shr bit) and 1 == 1
                        Surface(
                            onClick = { set(current xor (1 shl bit)) },
                            modifier = Modifier.size(BIT_SIZE),
                            shape = RoundedCornerShape(Spacing.xs),
                            color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Text(if (on) "1" else "0", fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        },
    )
}

private val BIT_SIZE = 32.dp
