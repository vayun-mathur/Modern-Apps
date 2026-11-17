package com.vayunmathur.findfamily.ui

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.vayunmathur.findfamily.R
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.Text

@Composable
fun WaypointCard(waypoint: Waypoint, userNamesHere: List<String>, onSelect: () -> Unit) {
    val usersString = when (userNamesHere.size) {
        0 -> stringResource(R.string.nobody_here)
        1 -> stringResource(R.string.user_is_here, userNamesHere.first())
        else -> stringResource(R.string.users_are_here, userNamesHere.joinToString())
    }
    Card(Modifier.clickable(onClick = onSelect)) {
        ListItem(
            content = { Text(waypoint.name, fontWeight = FontWeight.Bold) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            supportingContent = { Text(usersString, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingContent = { IconEdit() }
        )
    }
}
