package com.vayunmathur.education.ui
import android.content.Context
import android.text.format.DateFormat
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import java.util.Date
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.ui.AssistChip
import com.vayunmathur.library.ui.AssistChipDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.education.domain.Band
import com.vayunmathur.library.ui.IconFire
import com.vayunmathur.library.ui.IconStar
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.vayunmathur.education.R

/**
 * A parent-set deadline surfaced as a gentle, non-punitive chip. Overdue is
 * shown in the error color but phrased softly by callers.
 */
@Composable
fun DeadlineChip(dueEpochDay: Long, modifier: Modifier = Modifier) {
    // kotlinx-datetime's LocalDate has no now()/toEpochDay() — today comes from the clock,
    // and the epoch-day accessor is toEpochDays() (Long).
    val today = Clock.System.todayIn(TimeZone.currentSystemDefault()).toEpochDays()
    val diff = dueEpochDay - today
    val (label, color) = when {
        diff < 0 -> "Past due" to MaterialTheme.colorScheme.error
        diff == 0L -> "Due today" to MaterialTheme.colorScheme.tertiary
        diff == 1L -> "Due tomorrow" to MaterialTheme.colorScheme.primary
        else -> "Due in $diff days" to MaterialTheme.colorScheme.primary
    }
    AssistChip(
        onClick = {},
        enabled = false,
        modifier = modifier,
        label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(disabledLabelColor = color),
    )
}
