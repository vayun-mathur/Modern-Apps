package com.vayunmathur.games.voxels.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.voxels.R
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

// The engine names its bosses in English (engine_part1.rs publish_ui); show the localized name.
private const val ENGINE_WITHER = "The Wither"
private const val ENGINE_DRAGON = "Ender Dragon"

@Composable
fun BossBar(healthJson: String, modifier: Modifier = Modifier) {
    val hj = remember(healthJson) { parseHealth(healthJson) }
    if (hj.boss < 0f) return
    val wither = hj.bossName == ENGINE_WITHER
    val textColor = if (wither) Color(0xFFBDBDBD) else Color(0xFFB388FF)
    val barColor = if (wither) Color(0xFF4A4A55) else Color(0xFF9B30FF)
    val name = when (hj.bossName) {
        ENGINE_WITHER -> R.string.boss_wither
        ENGINE_DRAGON -> R.string.boss_dragon
        else -> R.string.boss_generic
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(name), color = textColor, style = MaterialTheme.typography.labelMedium)
        Box(Modifier.width(220.dp).height(7.dp).background(Color.Black.copy(0.6f), RoundedCornerShape(3.dp))) {
            Box(Modifier.fillMaxWidth(hj.boss.coerceIn(0f, 1f)).fillMaxHeight().background(barColor, RoundedCornerShape(3.dp)))
        }
    }
}
