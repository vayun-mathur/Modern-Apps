package com.vayunmathur.games.voxels.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.voxels.R
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

// Small chip shown while an elytra is equipped: prompts to deploy, or confirms gliding.
@Composable
fun GlideIndicator(healthJson: String, modifier: Modifier = Modifier) {
    val hj = remember(healthJson) { parseHealth(healthJson) }
    if (!hj.elytra) return
    val (label, tint) = if (hj.gliding) R.string.glide_active to Color(0xFF7CE0FF) else R.string.glide_hint to Color.White.copy(0.75f)
    Box(modifier.background(Color.Black.copy(0.45f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(stringResource(label), color = tint, style = MaterialTheme.typography.labelMedium)
    }
}
