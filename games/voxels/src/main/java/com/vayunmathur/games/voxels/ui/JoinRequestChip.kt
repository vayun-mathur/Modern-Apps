package com.vayunmathur.games.voxels.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.voxels.R
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text

/** In-game prompt for the host: "<player> wants to join" with Approve / Deny. */
@Composable
fun JoinRequestChip(player: String, onApprove: () -> Unit, onDeny: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.widthIn(max = 320.dp).background(Color(0xCC1A1E1A), RoundedCornerShape(10.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.player_wants_to_join, player), color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDeny) { Text(stringResource(R.string.deny)) }
            Button(onClick = onApprove) { Text(stringResource(R.string.approve)) }
        }
    }
}
