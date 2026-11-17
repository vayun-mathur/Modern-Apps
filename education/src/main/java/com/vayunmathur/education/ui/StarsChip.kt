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

/** Total stars indicator ("⭐ N"). */
@Composable
fun StarsChip(count: Int) {
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(count.toString()) },
        leadingIcon = { IconStar(tint = MaterialTheme.colorScheme.tertiary) },
        colors = AssistChipDefaults.assistChipColors(
            disabledLabelColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}
