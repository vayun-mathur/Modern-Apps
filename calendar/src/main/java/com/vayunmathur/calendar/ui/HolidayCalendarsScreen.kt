package com.vayunmathur.calendar.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vayunmathur.calendar.Route
import com.vayunmathur.calendar.util.CalendarViewModel
import com.vayunmathur.calendar.util.HolidayCalendarManager
import com.vayunmathur.calendar.util.HolidayData
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HolidayCalendarsScreen(viewModel: CalendarViewModel, backStack: NavBackStack<Route>) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val countries = remember { HolidayData.countries(context) }
    var added by remember { mutableStateOf(emptySet<String>()) }
    var busy by remember { mutableStateOf(emptySet<String>()) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        added = withContext(Dispatchers.IO) { HolidayCalendarManager.addedCountryCodes(context) }
    }

    val filtered = remember(countries, query) {
        if (query.isBlank()) countries
        else countries.filter { it.name.contains(query, ignoreCase = true) }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Holiday calendars") },
            navigationIcon = { IconNavigation(backStack) },
        )
    }) { paddingValues ->
        Column(Modifier.padding(paddingValues).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                placeholder = { Text("Search countries") },
                singleLine = true,
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(filtered, key = { it.code }) { country ->
                    val isAdded = country.code in added
                    val isBusy = country.code in busy
                    val toggle = {
                        if (!isBusy) {
                            scope.launch {
                                busy = busy + country.code
                                withContext(Dispatchers.IO) {
                                    if (isAdded) HolidayCalendarManager.removeCountry(context, country.code)
                                    else HolidayCalendarManager.addCountry(context, country.code, country.name)
                                }
                                added = withContext(Dispatchers.IO) {
                                    HolidayCalendarManager.addedCountryCodes(context)
                                }
                                busy = busy - country.code
                                viewModel.reloadAll()
                            }
                        }
                    }
                    ListItem(
                        content = { Text(country.name) },
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !isBusy) { toggle() },
                        trailingContent = {
                            if (isBusy) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else {
                                Switch(checked = isAdded, onCheckedChange = { toggle() })
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
