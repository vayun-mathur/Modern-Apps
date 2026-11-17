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

@Composable
fun TemporaryLinkCard(temporaryLink: TemporaryLink, onCopy: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    Card {
        ListItem(
            { Text(temporaryLink.name, fontWeight = FontWeight.Bold) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            supportingContent = {
                Text(stringResource(R.string.expires, timestring(temporaryLink.deleteAt, true, context)))
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
