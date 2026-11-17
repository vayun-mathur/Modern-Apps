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

/** A row of up to [max] stars, [stars] of them filled. */
@Composable
fun StarRow(stars: Int, modifier: Modifier = Modifier, max: Int = 3) {
    val filled = MaterialTheme.colorScheme.tertiary
    val empty = MaterialTheme.colorScheme.outlineVariant
    Row(modifier) {
        repeat(max) { i ->
            IconStar(tint = if (i < stars) filled else empty)
        }
    }
}





fun bandLabel(band: Band): String = when (band) {
    Band.K2 -> "K-2 · Guided Playground"
    Band.ELEMENTARY -> "3-5 · Explorer"
    Band.SCHOLAR -> "6-12 · Scholar"
}

fun formatEpochDay(context: Context, day: Long): String {
    val millis = LocalDate.fromEpochDays(day)
        .atStartOfDayIn(TimeZone.currentSystemDefault())
        .toEpochMilliseconds()
    return DateFormat.getMediumDateFormat(context).format(Date(millis))
}

/** Integer-average stars (0-3) across a set of skills, unpracticed == 0. */
fun averageStars(
    skillIds: Collection<String>,
    progress: Map<String, com.vayunmathur.education.data.SkillProgress>,
): Int {
    if (skillIds.isEmpty()) return 0
    return skillIds.sumOf { progress[it]?.stars ?: 0 } / skillIds.size
}
