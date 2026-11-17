package com.vayunmathur.contacts.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.Route
import com.vayunmathur.contacts.data.CDKEvent
import com.vayunmathur.contacts.data.ContactDetail
import com.vayunmathur.contacts.data.Event
import com.vayunmathur.contacts.data.formatDisplay
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FilledTonalButton
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconRemoveCircle
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.SelectableDropdownMenuItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.ResultEffect
import java.util.Locale
import kotlinx.datetime.LocalDate

/** Width of a field's trailing [IconButton], kept clear of any full-row tap overlay. */
internal val RemoveButtonWidth = 48.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColumnScope.EditContactDateDetails(
    backStack: NavBackStack<Route>,
    details: List<Event>,
    onDetailsChange: (List<Event>) -> Unit,
    icon: @Composable () -> Unit,
    options: List<Int>
) {
    val detailType = stringResource(R.string.dates)
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    details.forEachIndexed { index, detail ->
        if(detail.type == CDKEvent.TYPE_BIRTHDAY) return@forEachIndexed
        val isCustom = detail.type == CDKEvent.TYPE_CUSTOM
        Box {
            ResultEffect<LocalDate>(detail.id.toString()) { newDate ->
                onDetailsChange(details.toMutableList().also { list -> list[index] = detail.withValue(newDate.toString()) })
            }
            OutlinedTextField(
                value = detail.startDate.formatDisplay(locale),
                onValueChange = { },
                readOnly = true,
                label = { Text(detailType) },
                trailingIcon = {
                    Row {
                        var dropdownExpanded by remember { mutableStateOf(false) }
                        TextButton({ dropdownExpanded = true }) {
                            Text(detail.typeString(context))
                            IconArrowDropDown()
                        }
                        DropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { dropdownExpanded = false }) {
                            options.forEach { option ->
                                SelectableDropdownMenuItem(
                                    selected = detail.type == option,
                                    onClick = {
                                        onDetailsChange(details.toMutableList().also { it[index] = detail.withType(option) })
                                        dropdownExpanded = false
                                    },
                                    text = { Text(ContactDetail.default<Event>().withType(option).typeString(context)) },
                                    selectedLeadingIcon = { IconCheck() },
                                )
                            }
                        }
                        IconButton(onClick = {
                            onDetailsChange(details.toMutableList().also { it.removeAt(index) })
                        }) {
                            IconRemoveCircle()
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
            ) {
                Box(Modifier.fillMaxWidth(0.6f).fillMaxHeight()
                    .clickable { backStack.add(Route.EventDatePickerDialog(detail.id.toString(),detail.startDate)) }) {}
            }
        }
        if (isCustom) {
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = detail.label,
                onValueChange = { newLabel ->
                    onDetailsChange(details.toMutableList().also { it[index] = detail.withLabel(newLabel) })
                },
                label = { Text(stringResource(R.string.custom_label)) },
                placeholder = { Text(stringResource(R.string.enter_custom_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(8.dp))
    }
    FilledTonalButton(
        onClick = { onDetailsChange(details + ContactDetail.default<Event>()) },
        modifier = Modifier.fillMaxWidth()
    ) {
        icon()
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.add_date))
    }
}
