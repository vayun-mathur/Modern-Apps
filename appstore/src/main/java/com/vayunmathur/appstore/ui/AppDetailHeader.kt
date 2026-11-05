package com.vayunmathur.appstore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.util.AppDetailUiState
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.image.compose.AsyncImageState
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.IconKeyboardArrowDown
import com.vayunmathur.library.ui.IconKeyboardArrowUp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.util.sharedCrop

@Composable
internal fun AppDetailHeader(state: AppDetailUiState) {
    val app = state.app ?: return
    Row(
        Modifier
            .sharedCrop("appstore-app-${app.packageName}")
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app, state.installedIcon, size = 80.dp, corner = 20.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                app.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            app.author?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SourceChip(app.source)
                if (state.isLoadingDetails) CircularProgressIndicator(Modifier.size(12.dp))
            }
        }
    }
}

/**
 * Rating, installs, size and age rating, in whatever subset the source published.
 *
 * F-Droid publishes none of the first three, so the row collapses to just the size for an
 * F-Droid app rather than showing four empty cells.
 */
@Composable
internal fun AppDetailFacts(app: UnifiedApp) {
    val cells = buildList<Pair<String, String>> {
        app.rating?.let {
            add(stringResource(R.string.fact_rating) to String.format(java.util.Locale.US, "%.1f★", it))
        }
        if (app.installs > 0) add(stringResource(R.string.fact_installs) to formatCount(app.installs))
        if (app.sizeBytes > 0) add(stringResource(R.string.fact_size) to formatSize(app.sizeBytes))
        app.contentRating?.let { add(stringResource(R.string.fact_rated) to it) }
        app.versionName?.let { add(stringResource(R.string.fact_version) to it) }
    }
    if (cells.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        cells.take(4).forEachIndexed { index, (label, value) ->
            if (index > 0) {
                Box(
                    Modifier
                        .width(1.dp)
                        .height(32.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
            StatCell(label, value, Modifier.weight(1f))
        }
    }
}

/** Screenshots, at a fixed height so mixed aspect ratios still scroll as one strip. */
@Composable
internal fun AppScreenshotStrip(urls: List<String>) {
    LazyRow(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(urls, key = { it }) { url ->
            // The width has to be stated. AsyncImage draws its bitmap with matchParentSize, which
            // contributes nothing to the measure pass, so an image given only a height resolves to
            // zero wide and the strip is 220dp of nothing. Start at a phone screenshot's shape and
            // switch to the real one once the bitmap has been decoded.
            var ratio by remember(url) { mutableFloatStateOf(PORTRAIT_SCREENSHOT_RATIO) }
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier
                    .height(220.dp)
                    .aspectRatio(ratio)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Fit,
                onState = { state ->
                    val size = (state as? AsyncImageState.Success)?.painter?.intrinsicSize
                    if (size != null && size.width > 0f && size.height > 0f) {
                        ratio = size.width / size.height
                    }
                },
            )
        }
    }
}

/** 9:16, the shape a phone screenshot is until its own is known. */
private const val PORTRAIT_SCREENSHOT_RATIO = 9f / 16f

/** Summary always, full description behind a toggle — most of them are very long. */
@Composable
internal fun AppDetailDescription(app: UnifiedApp) {
    if (app.summary.isBlank() && app.description.isBlank()) return
    var expanded by remember(app.packageName) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (app.summary.isNotBlank()) {
            Text(
                app.summary,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
        }
        if (app.description.isNotBlank() && app.description != app.summary) {
            Text(
                rememberDescriptionText(app.description, MaterialTheme.colorScheme.primary),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 5,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    stringResource(
                        if (expanded) R.string.action_show_less else R.string.action_read_more
                    )
                )
                Spacer(Modifier.width(4.dp))
                if (expanded) IconKeyboardArrowUp() else IconKeyboardArrowDown()
            }
        }
    }
}

@Composable
internal fun AppDetailWhatsNew(app: UnifiedApp) {
    val notes = app.whatsNew?.takeIf { it.isNotBlank() } ?: return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(R.string.detail_whats_new),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        app.updatedOn?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(notes, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun AppDetailChips(app: UnifiedApp) {
    if (app.categories.isEmpty() && app.antiFeatures.isEmpty() && !app.containsAds && !app.reproducible) return
    LazyRow(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // F-Droid versions the verification server rebuilt bit-for-bit earn a positive badge.
        if (app.reproducible) {
            item("reproducible") { InfoChip(stringResource(R.string.chip_reproducible)) }
        }
        items(app.categories, key = { "cat-$it" }) { InfoChip(it) }
        if (app.containsAds) {
            item("ads") { InfoChip(stringResource(R.string.chip_contains_ads), emphasise = true) }
        }
        // F-Droid's anti-feature labels are the honest part of its listings and there is
        // no reason to bury them; they are flagged rather than hidden.
        items(app.antiFeatures, key = { "af-$it" }) { InfoChip(it, emphasise = true) }
    }
}
