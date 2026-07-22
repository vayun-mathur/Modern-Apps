package com.vayunmathur.calendar.ui

import android.net.Uri
import android.provider.CalendarContract
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import com.vayunmathur.library.ui.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.Route
import com.vayunmathur.calendar.data.Calendar
import com.vayunmathur.calendar.ui.dialogs.COLOR_SWATCHES
import com.vayunmathur.calendar.util.CalendarViewModel
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.launch

enum class ImportMode { Existing, New }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportIcsScreen(
    viewModel: CalendarViewModel,
    backStack: NavBackStack<Route>,
    uris: List<String>,
) {
    val uriList = remember(uris) { uris.map { Uri.parse(it) } }
    val parsedEvents by viewModel.parsedIcsEvents.collectAsStateWithLifecycle()
    val calendars by viewModel.calendars.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var importMode by remember { mutableStateOf(ImportMode.Existing) }
    var selectedCalendar by remember { mutableStateOf<Calendar?>(null) }
    var newCalendarName by remember { mutableStateOf("") }
    var newCalendarColor by remember { mutableIntStateOf(COLOR_SWATCHES[4]) } // default blue
    var isImporting by remember { mutableStateOf(false) }

    // Kick off parsing
    LaunchedEffect(uriList) {
        viewModel.parseIcsUris(uriList)
    }

    // Clear on dispose
    DisposableEffect(Unit) {
        onDispose { viewModel.clearParsedIcs() }
    }

    // Auto-select first editable calendar
    LaunchedEffect(calendars) {
        if (selectedCalendar == null) {
            selectedCalendar = calendars.filter { it.canModify }.firstOrNull()
        }
    }

    val events = parsedEvents
    val canImport = when (importMode) {
        ImportMode.Existing -> selectedCalendar != null && events?.isNotEmpty() == true && !isImporting
        ImportMode.New -> newCalendarName.isNotBlank() && events?.isNotEmpty() == true && !isImporting
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.import_events)) },
                navigationIcon = { IconNavigation(backStack) }
            )
        },
        floatingActionButton = {
            if (canImport) {
                FloatingActionButton(onClick = {
                    val evts = events ?: return@FloatingActionButton
                    isImporting = true
                    scope.launch {
                        when (importMode) {
                            ImportMode.Existing -> {
                                val calId = selectedCalendar?.id ?: return@launch
                                viewModel.importIcsEvents(evts, calId) {
                                    isImporting = false
                                    backStack.pop()
                                }
                            }
                            ImportMode.New -> {
                                // Create calendar, get ID, then import
                                viewModel.createLocalCalendar(
                                    accountName = "Offline Calendar",
                                    displayName = newCalendarName,
                                    colorInt = newCalendarColor,
                                    visible = true,
                                    accessLevel = CalendarContract.Calendars.CAL_ACCESS_EDITOR,
                                    onComplete = { newCalId ->
                                        if (newCalId != null) {
                                            viewModel.importIcsEvents(evts, newCalId) {
                                                isImporting = false
                                                backStack.pop()
                                            }
                                        } else {
                                            isImporting = false
                                        }
                                    }
                                )
                            }
                        }
                    }
                }) {
                    if (isImporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconSave()
                    }
                }
            }
        }
    ) { paddingValues ->
        when {
            events == null -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            events.isEmpty() -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.no_events_found))
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Text(
                            stringResource(R.string.import_summary, events.size),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    // Mode selector
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { importMode = ImportMode.Existing }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = importMode == ImportMode.Existing,
                                        onClick = { importMode = ImportMode.Existing }
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.import_to_existing_calendar))
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { importMode = ImportMode.New }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = importMode == ImportMode.New,
                                        onClick = { importMode = ImportMode.New }
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.create_new_calendar_option))
                                }
                            }
                        }
                    }

                    // Mode-specific inputs
                    item {
                        when (importMode) {
                            ImportMode.Existing -> {
                                CalendarSelectorDropdown(
                                    calendars = calendars,
                                    selectedCalendar = selectedCalendar,
                                    onSelect = { selectedCalendar = it },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            ImportMode.New -> {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp)) {
                                        OutlinedTextField(
                                            value = newCalendarName,
                                            onValueChange = { newCalendarName = it },
                                            label = { Text(stringResource(R.string.calendar_name)) },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Spacer(Modifier.height(16.dp))
                                        Text(stringResource(R.string.choose_color))
                                        Spacer(Modifier.height(8.dp))
                                        LazyRow {
                                            items(COLOR_SWATCHES, key = { it }) { c ->
                                                val selected = (newCalendarColor == c)
                                                Box(
                                                    modifier = Modifier
                                                        .padding(6.dp)
                                                        .size(40.dp)
                                                        .clip(CircleShape)
                                                        .background(Color(c))
                                                        .border(
                                                            width = if (selected) 3.dp else 1.dp,
                                                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                                            shape = CircleShape
                                                        )
                                                        .clickable { newCalendarColor = c }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Events list
                    items(events, key = { "${it.calendarID}|${it.start}|${it.title}" }) { event ->
                        EventCard(event = event)
                    }

                    item {
                        Spacer(Modifier.height(80.dp)) // FAB padding
                    }
                }
            }
        }
    }
}
