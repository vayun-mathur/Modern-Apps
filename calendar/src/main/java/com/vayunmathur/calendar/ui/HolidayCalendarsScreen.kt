package com.vayunmathur.calendar.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
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
    val allLanguages = remember { HolidayData.languages(context) }
    var added by remember { mutableStateOf(emptyMap<String, String>()) } // code -> lang
    var busy by remember { mutableStateOf(emptySet<String>()) }
    var query by remember { mutableStateOf("") }
    var showLangPickerFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        added = withContext(Dispatchers.IO) {
            HolidayCalendarManager.addedCalendars(context).associate { it.first to it.second }
        }
    }

    val filtered = remember(countries, query) {
        if (query.isBlank()) countries
        else countries.filter { it.name.contains(query, ignoreCase = true) }
    }

    fun refreshAdded() {
        scope.launch {
            added = withContext(Dispatchers.IO) {
                HolidayCalendarManager.addedCalendars(context).associate { it.first to it.second }
            }
        }
    }

    fun addWithLanguage(code: String, displayName: String, langCode: String, langName: String) {
        scope.launch {
            busy = busy + code
            withContext(Dispatchers.IO) {
                // Remove existing if changing language
                added[code]?.let { oldLang ->
                    if (oldLang != langCode) HolidayCalendarManager.removeCountry(context, code, oldLang)
                }
                HolidayCalendarManager.addCountry(context, code, displayName, langCode, langName)
            }
            refreshAdded()
            busy = busy - code
            viewModel.reloadAll()
        }
    }

    fun removeCountry(code: String) {
        scope.launch {
            busy = busy + code
            withContext(Dispatchers.IO) {
                added[code]?.let { lang -> HolidayCalendarManager.removeCountry(context, code, lang) }
            }
            refreshAdded()
            busy = busy - code
            viewModel.reloadAll()
        }
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
                    val langCode = added[country.code]
                    val isAdded = langCode != null
                    val isBusy = country.code in busy
                    val langName = allLanguages.find { it.code == langCode }?.name ?: "English"

                    ListItem(
                        content = { Text(country.name) },
                        supportingContent = if (isAdded) {{ Text(langName) }} else null,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !isBusy) {
                            if (isAdded) removeCountry(country.code)
                            else showLangPickerFor = country.code
                        },
                        trailingContent = {
                            if (isBusy) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else if (isAdded) {
                                Switch(checked = true, onCheckedChange = { removeCountry(country.code) })
                            } else {
                                TextButton(onClick = { showLangPickerFor = country.code }) { Text("Add") }
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    // Language picker dialog
    showLangPickerFor?.let { countryCode ->
        val country = countries.find { it.code == countryCode } ?: return@let
        val languages = remember(countryCode) { HolidayData.languagesForCountry(context, countryCode) }
        var langQuery by remember { mutableStateOf("") }
        val filteredLangs = remember(languages, langQuery) {
            if (langQuery.isBlank()) languages
            else languages.filter { it.name.contains(langQuery, ignoreCase = true) || it.code.contains(langQuery, ignoreCase = true) }
        }

        AlertDialog(
            onDismissRequest = { showLangPickerFor = null },
            title = { Text("Choose language") },
            text = {
                Column {
                    OutlinedTextField(
                        value = langQuery,
                        onValueChange = { langQuery = it },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        label = { Text("Search") },
                        singleLine = true,
                    )
                    LazyColumn(modifier = Modifier.height(360.dp)) {
                        items(filteredLangs, key = { it.code }) { lang ->
                            val isSelected = added[countryCode] == lang.code
                            Row(modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    addWithLanguage(countryCode, country.name, lang.code, lang.name)
                                    showLangPickerFor = null
                                }
                                .padding(12.dp)) {
                                Column(modifier = Modifier.fillMaxWidth().padding(end = 8.dp)) {
                                    Text(text = lang.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(text = lang.code, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (isSelected) {
                                    Text("✓")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showLangPickerFor = null }) { Text("Close") }
            }
        )
    }
}
