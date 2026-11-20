package com.vayunmathur.calendar.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.platform.describe
import com.vayunmathur.calendar.data.Event
import com.vayunmathur.library.log.Log
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayInputStream
import java.io.InputStream

@Composable
fun EventCard(event: Event) {
    val context = LocalContext.current
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
                Text(dateRangeString(context, event.startDateTimeDisplay.date, event.endDateTimeDisplay.date, event.startDateTimeDisplay.time, event.endDateTimeDisplay.time, event.allDay))
                // RRULE text
                event.rrule?.let { Text(it.describe(context)) }
                if (event.rdate.isNotEmpty()) {
                    Text(
                        pluralStringResource(
                            R.plurals.repeat_dates_summary,
                            event.rdate.size + 1,
                            event.rdate.size + 1,
                        )
                    )
                }

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

// Thin UI-package entry point; implementation lives in IcsParser.kt.
fun parseICSFile(iS: InputStream): List<Event> = parseICSFileImpl(iS)

/** Largest shared ICS file the import path will read (10 MB). */
const val MAX_ICS_BYTES = 10L * 1024 * 1024

/**
 * Reads and parses the shared ICS file at [uri], bounded by [MAX_ICS_BYTES].
 *
 * The parser loads every line into memory, so an arbitrary shared file must
 * not be streamed into it unchecked. Oversized files yield an empty list,
 * which callers already surface as "no events to import". The declared size
 * is checked first; the read itself enforces the same cap because a provider
 * may omit or understate it.
 */
fun readSharedIcsEvents(resolver: ContentResolver, uri: Uri): List<Event> {
    declaredIcsSize(resolver, uri)?.let { size ->
        if (size > MAX_ICS_BYTES) {
            Log.status("IcsImport", "Skipping oversized ICS file ($size bytes): $uri")
            return emptyList()
        }
    }
    val bytes = resolver.openInputStream(uri)?.use { stream ->
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_ICS_BYTES) {
                Log.status("IcsImport", "Skipping oversized ICS file (> $MAX_ICS_BYTES bytes): $uri")
                return emptyList()
            }
            out.write(buffer, 0, read)
        }
        out.toByteArray()
    } ?: return emptyList()
    return parseICSFile(ByteArrayInputStream(bytes))
}

/** Declared size of a shared ICS [Uri] from its provider, or null when unknown. */
private fun declaredIcsSize(resolver: ContentResolver, uri: Uri): Long? {
    if (uri.scheme != "content") return null
    return try {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index != -1 && cursor.moveToFirst()) cursor.getLong(index).takeIf { it >= 0 } else null
        }
    } catch (_: Exception) {
        null
    }
}
