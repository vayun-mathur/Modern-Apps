package com.vayunmathur.findfamily.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.vayunmathur.findfamily.R
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconCopy
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.Text
import kotlin.time.Clock

@Composable
fun TemporaryLinkCard(temporaryLink: TemporaryLink, onCopy: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    Card {
        ListItem(
            { Text(temporaryLink.name, fontWeight = FontWeight.Bold) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            supportingContent = {
                // The label follows the timestamp, not the card's assumption:
                // once deleteAt passes the link is expired, and timestring
                // itself now renders the past direction ("3 days ago").
                val label = if (temporaryLink.deleteAt > Clock.System.now()) R.string.expires else R.string.expired
                Text(stringResource(label, timestring(temporaryLink.deleteAt, context)))
            },
            trailingContent = {
                Row {
                    IconButton(onCopy) {
                        IconCopy()
                    }
                    IconButton(onDelete) {
                        IconDelete()
                    }
                }
            }
        )
    }
}
