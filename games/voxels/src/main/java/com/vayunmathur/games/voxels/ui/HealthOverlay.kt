package com.vayunmathur.games.voxels.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.vayunmathur.games.voxels.R
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box

private val effectNames = mapOf(
    "regen" to R.string.effect_regen, "poison" to R.string.effect_poison, "resist" to R.string.effect_resist,
    "strength" to R.string.effect_strength, "speed" to R.string.effect_speed, "haste" to R.string.effect_haste,
    "absorb" to R.string.effect_absorb, "fireres" to R.string.effect_fireres, "night" to R.string.effect_night,
    "jump" to R.string.effect_jump, "slow" to R.string.effect_slow,
)
private val effectColors = mapOf(
    "regen" to Color(0xFFE573B5), "poison" to Color(0xFF6DA33A), "resist" to Color(0xFF9E9E9E),
    "strength" to Color(0xFF932423), "speed" to Color(0xFF7CAFC6), "haste" to Color(0xFFD9C043),
    "absorb" to Color(0xFFF2C15A), "fireres" to Color(0xFFE0913A),
    "night" to Color(0xFF3B3BA0), "jump" to Color(0xFF34A02C), "slow" to Color(0xFF5A6472)
)

private fun roman(n: Int) = when (n) { 0 -> ""; 1 -> " II"; 2 -> " III"; 3 -> " IV"; else -> " ${n + 1}" }

@Composable
fun HealthOverlay(healthJson: String, modifier: Modifier = Modifier) {
    val h = remember(healthJson) { parseHealth(healthJson) }
    Column(modifier, horizontalAlignment = Alignment.Start) {
        // Effects (above the hearts).
        if (h.effects.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                h.effects.forEach { e ->
                    val name = effectNames[e.k]?.let { stringResource(it) } ?: e.k
                    Box(Modifier.clip(RoundedCornerShape(4.dp)).background((effectColors[e.k] ?: Color.Gray).copy(alpha = 0.85f)).padding(horizontal = 5.dp, vertical = 1.dp)) {
                        Text(stringResource(R.string.effect_chip, name + roman(e.amp), e.t), style = MaterialTheme.typography.labelSmall, color = Color.White)
                    }
                }
            }
        }
        // Hearts (each = 2 HP); absorption shown as gold hearts appended.
        val totalHearts = (h.max / 2f).toInt().coerceIn(1, 30)
        Row {
            for (i in 0 until totalHearts) {
                val filled = h.hp / 2f - i
                val c = when { filled >= 1f -> Color(0xFFE23A45); filled >= 0.5f -> Color(0xFFEE8891); else -> Color(0xFF3A1516) }
                Text("♥", color = c, style = MaterialTheme.typography.bodyMedium)
            }
            val absHearts = (h.absorb / 2f).toInt().coerceIn(0, 10)
            for (i in 0 until absHearts) { Text("♥", color = Color(0xFFF2C15A), style = MaterialTheme.typography.bodyMedium) }
        }
        if (h.estus > 0) {
            Text(stringResource(R.string.estus, h.estus), color = Color(0xFFFFC957), style = MaterialTheme.typography.labelSmall)
        }
    }
}
