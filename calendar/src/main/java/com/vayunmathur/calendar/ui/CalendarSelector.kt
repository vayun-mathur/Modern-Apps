package com.vayunmathur.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.data.Calendar

/**
 * Shared dropdown for picking an editable calendar, grouped by account. Used by the
 * ICS import screen and the in-app import dialog. The selection state is owned by the
 * caller; [onCreateNew], when provided, adds a "create new calendar" entry.
 */
@Composable
fun CalendarSelectorDropdown(
    calendars: List<Calendar>,
    selectedCalendar: Calendar?,
    modifier: Modifier = Modifier,
    onCreateNew: (() -> Unit)? = null,
    onSelect: (Calendar) -> Unit,
) {
    var showDropdown by remember { mutableStateOf(false) }
    val editable = calendars.filter(Calendar::canModify)
    val localLabel = stringResource(R.string.local_account)
    val grouped = editable.groupBy { it.accountName.ifEmpty { localLabel } }

    Box(modifier) {
        ListItem(
            content = { androidx.compose.material3.Text(selectedCalendar?.displayName ?: stringResource(R.string.select_calendar)) },
            leadingContent = {
                selectedCalendar?.color?.let { Box(Modifier.size(24.dp).background(Color(it), RectangleShape)) }
            },
            trailingContent = {
                Icon(painterResource(R.drawable.arrow_drop_down_24px), contentDescription = null)
            },
            modifier = Modifier.clickable { showDropdown = true },
        )
        DropdownMenu(expanded = showDropdown, onDismissRequest = { showDropdown = false }) {
            grouped.forEach { (account, cals) ->
                DropdownMenuItem(text = { androidx.compose.material3.Text(account) }, onClick = {}, enabled = false)
                cals.forEach { cal ->
                    DropdownMenuItem(
                        text = { androidx.compose.material3.Text(cal.displayName) },
                        leadingIcon = { Box(Modifier.size(16.dp).background(Color(cal.color), RectangleShape)) },
                        onClick = {
                            onSelect(cal)
                            showDropdown = false
                        },
                    )
                }
            }
            if (onCreateNew != null) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { androidx.compose.material3.Text(stringResource(R.string.create_new_calendar)) },
                    onClick = {
                        showDropdown = false
                        onCreateNew()
                    },
                )
            }
        }
    }
}
