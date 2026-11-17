package com.vayunmathur.findfamily.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.vayunmathur.findfamily.R
import com.vayunmathur.findfamily.ui.dialogs.encodeBase26
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.Text

@Composable
fun AwaitingRequestCard(id: Long, onAccept: () -> Unit) {
    Card {
        ListItem(
            { Text(stringResource(R.string.request_from, id.encodeBase26())) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            trailingContent = {
                IconButton(onAccept) {
                    IconAdd()
                }
            }
        )
    }
}
