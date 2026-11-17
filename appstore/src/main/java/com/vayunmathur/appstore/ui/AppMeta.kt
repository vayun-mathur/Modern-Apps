package com.vayunmathur.appstore.ui
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import java.util.Locale

/** A labelled value in the facts grid on the detail page. */
@Composable
fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            // A value wider than the cell (long version names) fills it, so the column's
            // alignment no longer applies and the text would sit at the start edge.
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
        )
    }
}


/** "12 MB", "980 kB" — SI units, because that is what stores quote. */
fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> ""
    bytes < 1_000L -> "$bytes B"
    bytes < 1_000_000L -> String.format(Locale.US, "%.0f kB", bytes / 1_000.0)
    bytes < 1_000_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    else -> String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0)
}

/** "1.2M+", "50K+" — the rounded form an install count is meaningful at. */
fun formatCount(count: Long): String = when {
    count <= 0L -> ""
    count < 1_000L -> count.toString()
    count < 1_000_000L -> "${count / 1_000}K+"
    count < 1_000_000_000L -> String.format(Locale.US, "%.1fM+", count / 1_000_000.0)
    else -> String.format(Locale.US, "%.1fB+", count / 1_000_000_000.0)
}
