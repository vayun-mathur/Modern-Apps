package com.vayunmathur.calendar

import android.content.ContentValues
import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.calendar.ui.dateRangeString
import com.vayunmathur.library.ui.DynamicTheme
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import java.io.BufferedInputStream
import java.io.InputStream
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds

class ImportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var events by remember { mutableStateOf(listOf<Event>()) }
            var calendars by remember {mutableStateOf(listOf<Calendar>())}

            LaunchedEffect(Unit) {
                calendars = Calendar.getAllCalendars(this@ImportActivity)
                val uri = intent.data
                val iS = uri?.let { contentResolver.openInputStream(it) }
                if (iS != null) {
                    events = parseICSFile(iS)
                    println(events)
                }
            }

            DynamicTheme {
                ImportScreen(events, calendars) { selectedCalendarID ->
                    val valuesList = events.map { event ->
                        ContentValues().apply {
                            put(CalendarContract.Events.TITLE, event.title)
                            put(CalendarContract.Events.DESCRIPTION, event.description)
                            put(CalendarContract.Events.EVENT_LOCATION, event.location)
                            put(CalendarContract.Events.CALENDAR_ID, selectedCalendarID)
                            val startDate = event.startDateTimeDisplay.date
                            val startTime = event.startDateTimeDisplay.time
                            val endDate = event.endDateTimeDisplay.date
                            val endTime = event.endDateTimeDisplay.time
                            val tz = if (event.allDay) "UTC" else event.timezone
                            val dtstart = startDate.atTime(startTime).toInstant(TimeZone.of(tz))
                                .toEpochMilliseconds()
                            val dtendActual = endDate.atTime(endTime).toInstant(TimeZone.of(tz))
                                .toEpochMilliseconds()
                            put(CalendarContract.Events.DTSTART, dtstart)
                            if (event.rrule != null) {
                                // For recurring events, DTEND must be 0 and DURATION set to the event length
                                put(CalendarContract.Events.DTEND, null as Long?)
                                var duration = (dtendActual - dtstart).milliseconds
                                if (event.allDay) duration += 1.days
                                put(CalendarContract.Events.DURATION, duration.toIsoString())
                                put(CalendarContract.Events.RRULE, event.rrule.asString(startDate, TimeZone.of(tz)))
                            } else {
                                put(CalendarContract.Events.DTEND, dtendActual)
                                // clear DURATION and RRULE if present
                                put(CalendarContract.Events.DURATION, null as String?)
                                put(CalendarContract.Events.RRULE, null as String?)
                            }
                            put(CalendarContract.Events.ALL_DAY, if (event.allDay) 1 else 0)
                            put(CalendarContract.Events.EVENT_TIMEZONE, tz)
                        }
                    }
                    contentResolver.bulkInsert(CalendarContract.Events.CONTENT_URI, valuesList.toTypedArray())
                    val intent = Intent(this@ImportActivity, MainActivity::class.java)
                    startActivity(intent)
                    finish()
                }
            }
        }
    }
}

@Composable
fun ImportScreen(events: List<Event>, calendars: List<Calendar>, onImportClick: (Long) -> Unit) {
    var selectedCalendar by remember { mutableStateOf<Calendar?>(null) }
    var showDropdown by remember { mutableStateOf(false) }
    val editable = calendars.filter { it.canModify }
    val grouped = editable.groupBy { it.accountName.ifEmpty { "(Local)" } }
    Scaffold(
        floatingActionButton = {
            if(selectedCalendar != null) {
                FloatingActionButton(onClick = {onImportClick(selectedCalendar!!.id)}) {
                    Icon(painterResource(R.drawable.save_24px), null)
                }
            }
        }
    ) { paddingValues ->
        Column(Modifier.padding(paddingValues)) {
            Column {
                ListItem({
                    Text(selectedCalendar?.displayName ?: "Select Calendar")
                }, Modifier.clickable {
                    showDropdown = true
                }, trailingContent = {
                    Icon(painterResource(R.drawable.arrow_drop_down_24px), contentDescription = null)
                }, leadingContent = {
                    selectedCalendar?.color?.let { Box(Modifier.size(24.dp).background(Color(it), RectangleShape)) }
                })
                if(showDropdown) {
                    DropdownMenu(expanded = true, onDismissRequest = { showDropdown = false }) {
                        grouped.forEach { (account, cals) ->
                            DropdownMenuItem(
                                { Text(account) },
                                {}, enabled = false)
                            cals.forEach { cal ->
                                DropdownMenuItem(
                                    { Text(cal.displayName) },
                                    {
                                        selectedCalendar = cal
                                        showDropdown = false
                                    }, leadingIcon = {
                                        Box(
                                            Modifier.size(16.dp)
                                                .background(Color(cal.color), RectangleShape)
                                        )
                                    })
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            LazyColumn {
                items(events) { event ->
                    EventCard(event = event)
                }
            }
        }
    }
}

@Composable
fun EventCard(event: Event) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        ListItem({
            Text(event.title)
        }, supportingContent = {
            Column {
                // Format date range using the shared helper
                Text(dateRangeString(event.startDateTimeDisplay.date, event.endDateTimeDisplay.date, event.startDateTimeDisplay.time, event.endDateTimeDisplay.time, event.allDay))
                // RRULE text
                event.rrule?.let { Text(it.toString(event.startDateTimeDisplay.date)) }

                if (event.description.isNotBlank()) {
                    Text(event.description)
                }
                if (event.location.isNotBlank()) {
                    Text(event.location)
                }
            }
        }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
    }
}

// Simple ICS parser that returns a list of Event (uses the app's Event class)
fun parseICSFile(iS: InputStream): List<Event> {
    val events = mutableListOf<Event>()

    // Read and unfold lines (lines that start with space or tab are continuations)
    val rawLines = BufferedInputStream(iS).bufferedReader().readLines()
    val lines = mutableListOf<String>()
    for (line in rawLines) {
        if (line.startsWith(" ") || line.startsWith('\t')) {
            if (lines.isNotEmpty()) {
                val prev = lines.removeAt(lines.size - 1)
                lines.add(prev + line.trimStart())
            } else {
                lines.add(line.trimStart())
            }
        } else {
            lines.add(line)
        }
    }
    println(lines)

    var current = mutableMapOf<String, String>()
    var inEvent = false

    for (raw in lines) {
        val line = raw.trimEnd()
        if (line.equals("BEGIN:VEVENT", ignoreCase = true)) {
            inEvent = true
            current = mutableMapOf()
            continue
        }
        if (line.equals("END:VEVENT", ignoreCase = true)) {
            // finalize event
            try {
                val uid = current["UID"] ?: current["ID"] ?: ""
                val id = if (uid.isNotBlank()) uid.hashCode().toLong() else null
                val title = current["SUMMARY"] ?: "Untitled"
                val description = current["DESCRIPTION"] ?: ""
                val location = current["LOCATION"] ?: ""

                val (startMillis, startAllDay, startTz) = parseICSTime(current["DTSTART_PROP"], current["DTSTART"])
                val (endMillisRaw, _, endTzRaw) = parseICSTime(current["DTEND_PROP"], current["DTEND"])

                var endMillis = endMillisRaw
                val allDay = startAllDay
                if (endMillis == null) {
                    // try DURATION
                    val duration = current["DURATION"]
                    if (duration != null) {
                        endMillis = tryParseDurationMillis(duration, startMillis ?: 0L)
                    }
                }

                if (endMillis == null && startMillis != null) {
                    // as fallback, set end = start
                    endMillis = startMillis
                }

                val timezone = startTz ?: endTzRaw ?: "UTC"

                val rrule = current["RRULE"]?.let { RRule.parse(it, TimeZone.of(timezone)) }

                // If event was all-day but end time is same-day start, adjust end to next day
                if (allDay && startMillis != null && endMillis == startMillis) {
                    endMillis = startMillis + Duration.ofDays(1).toMillis()
                }

                val evt = Event(id, -1, title, description, location, null, startMillis ?: 0L, endMillis ?: (startMillis ?: 0L), timezone, allDay, rrule)
                events.add(evt)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            inEvent = false
            current = mutableMapOf()
            continue
        }

        if (!inEvent) continue

        // Split property into name;params:value
        val colonIndex = line.indexOf(':')
        if (colonIndex <= 0) continue
        val left = line.substring(0, colonIndex)
        val value = line.substring(colonIndex + 1)

        // Extract property name and keep full left for param-aware keys
        val semicolonIndex = left.indexOf(';')
        val propName = if (semicolonIndex > 0) left.substring(0, semicolonIndex).uppercase(Locale.getDefault()) else left.uppercase(Locale.getDefault())

        // Store value; also keep property with params for DTSTART/DTEND
        when (propName) {
            "DTSTART" -> {
                current["DTSTART"] = value
                current["DTSTART_PROP"] = left // keep params
            }
            "DTEND" -> {
                current["DTEND"] = value
                current["DTEND_PROP"] = left
            }
            else -> current[propName] = value
        }
    }

    return events
}

// Parse ICS time value with optional params-left (like DTSTART;TZID=America/Los_Angeles)
// Returns Triple(startMillisOrNull, isAllDay, timezoneOrNull)
private fun parseICSTime(propLeft: String?, value: String?): Triple<Long?, Boolean, String?> {
    if (value == null) return Triple(null, false, null)

    val left = propLeft ?: ""
    val up = left.uppercase(Locale.getDefault())

    // all-day if VALUE=DATE or value is 8 chars
    val isAllDay = up.contains("VALUE=DATE") || value.length == 8 && value.all { it.isDigit() }

    return try {
        if (isAllDay) {
            val dt = LocalDate.parse(value, DateTimeFormatter.ofPattern("yyyyMMdd"))
            val start = dt.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            Triple(start, true, "UTC")
        } else {
            // Check for UTC 'Z' suffix
            if (value.endsWith("Z")) {
                val fmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssX")
                val instant = Instant.from(fmt.parse(value))
                Triple(instant.toEpochMilli(), false, "UTC")
            } else {
                // look for TZID in left params
                val tzid = extractTZID(left)
                val patternCandidates = listOf("yyyyMMdd'T'HHmmss", "yyyyMMdd'T'HHmm")
                var parsedInstant: Instant? = null
                for (pat in patternCandidates) {
                    try {
                        val fmt = DateTimeFormatter.ofPattern(pat)
                        val ldt = LocalDateTime.parse(value, fmt)
                        val z = if (tzid != null) ZoneId.of(tzid) else ZoneOffset.UTC
                        parsedInstant = ldt.atZone(z).toInstant()
                        break
                    } catch (_: DateTimeParseException) {
                        // try next
                    }
                }
                if (parsedInstant != null) Triple(parsedInstant.toEpochMilli(), false, tzid ?: "UTC") else Triple(null, false, tzid)
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        Triple(null, false, null)
    }
}

private fun extractTZID(left: String): String? {
    // left examples: DTSTART;TZID=America/Los_Angeles or DTSTART;VALUE=DATE
    val parts = left.split(';')
    for (p in parts) {
        val idx = p.indexOf('=')
        if (idx > 0) {
            val k = p.substring(0, idx).uppercase(Locale.getDefault())
            if (k == "TZID") {
                return p.substring(idx + 1)
            }
        }
    }
    return null
}

private fun tryParseDurationMillis(duration: String, startMillis: Long): Long? {
    // very small support for ISO 8601 durations like P1D, PT1H30M, etc.
    try {
        val d = Duration.parse(duration)
        return startMillis + d.toMillis()
    } catch (_: Exception) {
        return null
    }
}
