package com.vayunmathur.launcher.ui

import android.Manifest
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

@Composable
fun AtAGlanceWidget(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val now = remember { Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()) }

    val dayOfWeek = remember {
        now.dayOfWeek.getDisplayName(JavaTextStyle.FULL, Locale.getDefault())
    }
    val dateString = remember {
        val month = now.month.getDisplayName(JavaTextStyle.FULL, Locale.getDefault())
        "$dayOfWeek, $month ${now.dayOfMonth}"
    }

    val nextEvent = remember {
        try {
            if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
                val nowMillis = System.currentTimeMillis()
                val cursor = context.contentResolver.query(
                    CalendarContract.Events.CONTENT_URI,
                    arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART),
                    "${CalendarContract.Events.DTSTART} >= ?",
                    arrayOf(nowMillis.toString()),
                    "${CalendarContract.Events.DTSTART} ASC LIMIT 1"
                )
                cursor?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            } else null
        } catch (_: Exception) { null }
    }

    val shadowStyle = TextStyle(
        shadow = Shadow(
            color = Color.Black.copy(alpha = 0.6f),
            offset = Offset(1f, 1f),
            blurRadius = 4f
        )
    )

    Column(modifier = modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(
            text = dateString,
            style = MaterialTheme.typography.headlineSmall.merge(shadowStyle),
            color = Color.White,
            fontWeight = FontWeight.Medium
        )
        nextEvent?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium.merge(shadowStyle),
                color = Color.White.copy(alpha = 0.9f),
                maxLines = 1
            )
        }
    }
}
