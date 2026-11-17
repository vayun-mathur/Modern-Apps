package com.vayunmathur.maps.ui

import com.vayunmathur.maps.data.string
import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.IconLocationOn
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.ui.IconStarBorder
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.maps.R
import kotlinx.coroutines.launch

@Composable
internal fun AddressRow(address: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    RestaurantItem({ IconLocationOn() }, address) {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("address", address)))
        }
    }
}

/** The archive's short AI summary of the place's reviews, available offline. */
@Composable
internal fun ReviewSummaryRow(summary: String) {
    Card {
        ListItem({
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.review_summary_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(summary, style = MaterialTheme.typography.bodyMedium)
            }
        }, leadingContent = {
            IconStar(tint = MaterialTheme.colorScheme.tertiary)
        }, colors = ListItemDefaults.colors(Color.Transparent))
    }
}

/**
 * Five stars, filled up to [rating].
 *
 * `Double` rather than `Int` because the archive's place rating is fractional; a review's own
 * rating is a whole number and converts. There used to be one of these per file, differing
 * only in that parameter type.
 */
@Composable
internal fun RatingStars(rating: Double, modifier: Modifier = Modifier) {
    Row(modifier) {
        repeat(5) { i ->
            if (i < rating.toInt()) IconStar(Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
            else IconStarBorder(Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
