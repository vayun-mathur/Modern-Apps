package com.vayunmathur.appstore.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallStage
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.util.sharedCrop
import java.util.Locale

/**
 * The icon, from whichever of the places actually has one.
 *
 * A locally installed app has a real launcher icon in PackageManager, which beats
 * re-downloading the listing's copy; a catalogue entry has a URL; a Play cluster row
 * sometimes has neither. Every screen needs the same fallback chain, so it lives here
 * rather than being written out again per call site.
 */
@Composable
fun AppIcon(
    app: UnifiedApp,
    installedIcon: Drawable?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    corner: Dp = 12.dp,
) {
    val shape = RoundedCornerShape(corner)
    val box = modifier.size(size).clip(shape)
    val bitmap = installedIcon?.let {
        runCatching {
            val px = (size.value * 2).toInt().coerceAtLeast(1)
            it.toBitmap(width = px, height = px).asImageBitmap()
        }.getOrNull()
    }
    when {
        bitmap != null -> Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = box,
            contentScale = ContentScale.Crop,
        )
        app.iconUrl != null -> AsyncImage(
            model = app.iconUrl,
            contentDescription = null,
            modifier = box,
            contentScale = ContentScale.Crop,
        )
        // No icon anywhere: an initial on a tinted tile reads better than a blank hole,
        // and keeps rows the same height whether or not the artwork loaded.
        else -> Box(
            box.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                app.name.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Where an app came from. Purely factual — the sources are not ranked. */
@Composable
fun SourceChip(source: AppSource, modifier: Modifier = Modifier) {
    val label = stringResource(
        when (source) {
            AppSource.MODERN_APPS -> R.string.source_chip_modern_apps
            AppSource.FDROID -> R.string.source_chip_fdroid
            AppSource.IZZY -> R.string.source_chip_izzy
            AppSource.PLAYSTORE -> R.string.source_chip_play
            AppSource.ACCRESCENT -> R.string.source_chip_accrescent
            AppSource.PROPRIETARY -> R.string.source_chip_proprietary
        }
    )
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** "4.6 ★" plus the rating count, when the source published one. */
@Composable
fun RatingLabel(app: UnifiedApp, modifier: Modifier = Modifier) {
    val rating = app.rating ?: return
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            String.format(Locale.US, "%.1f", rating),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(2.dp))
        IconStar(Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A full-width list row: icon, name, summary, and whatever the caller puts on the right.
 *
 * [stage] draws its own progress bar underneath, so a row that is mid-install says which
 * part of the install it is in rather than showing a bar that sits at 100% through
 * verification and commit.
 */
@Composable
fun AppRow(
    app: UnifiedApp,
    modifier: Modifier = Modifier,
    isInstalled: Boolean = false,
    stage: InstallStage? = null,
    installedIcon: Drawable? = null,
    versionLabel: String? = null,
    /**
     * Non-null makes this row the origin of the [sharedCrop] container transform into the app's
     * detail page. Null for a row that is a second copy of an app already shown elsewhere on
     * screen - the morph cannot choose between two origins for one destination.
     */
    sharedKey: Any? = null,
    onClick: () -> Unit = {},
    trailing: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier
            .then(if (sharedKey == null) Modifier else Modifier.sharedCrop(sharedKey))
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app, installedIcon, size = 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    app.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = app.summary.ifBlank { app.author.orEmpty() }
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SourceChip(app.source)
                    RatingLabel(app)
                    if (isInstalled) {
                        IconCheck(Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                if (versionLabel != null) {
                    Text(
                        versionLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // Old and new versions share one "a (1) → b (2)" line, and long
                        // version names ellipsized the tail — the new code — away (#813).
                        // Two lines keeps both codes visible.
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
        StageProgress(stage, Modifier.padding(top = 8.dp))
    }
}

/** Heading above a section of the home screen, with an optional trailing action. */
@Composable
fun SectionHeader(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    /** Label for the header's action button; null draws no button (e.g. in a preview). */
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Row(
        modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (actionLabel != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

/** One horizontally scrolling row of [AppTile]s. */
@Composable
fun AppCarousel(
    apps: List<UnifiedApp>,
    installedPackages: Set<String>,
    installedIcons: Map<String, Drawable>,
    onAppClick: (UnifiedApp) -> Unit,
    modifier: Modifier = Modifier,
    stages: Map<String, InstallStage> = emptyMap(),
    /** Per-app, because only the caller knows which copy of an app owns the morph. */
    sharedKey: (UnifiedApp) -> Any? = { null },
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(apps, key = { it.packageName }) { app ->
            AppTile(
                app = app,
                isInstalled = app.packageName in installedPackages,
                stage = stages[app.packageName],
                installedIcon = installedIcons[app.packageName],
                onClick = { onAppClick(app) },
                sharedKey = sharedKey(app),
            )
        }
    }
}

/**
 * The progress line for an in-flight install, or nothing when there isn't one.
 *
 * Download is determinate; verification and the PackageInstaller commit are not, and an
 * indeterminate bar is the honest way to say "still working, no idea how long".
 */
@Composable
fun StageProgress(stage: InstallStage?, modifier: Modifier = Modifier) {
    if (stage == null) return
    Column(modifier.fillMaxWidth()) {
        when (stage) {
            is InstallStage.Downloading -> LinearProgressIndicator(
                progress = { stage.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            is InstallStage.Failed -> Unit
            else -> LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Text(
            stageLabel(stage),
            style = MaterialTheme.typography.labelSmall,
            color = if (stage is InstallStage.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
fun stageLabel(stage: InstallStage): String = when (stage) {
    InstallStage.Preparing -> stringResource(R.string.stage_preparing)
    is InstallStage.Downloading ->
        stringResource(R.string.stage_downloading, (stage.fraction * 100).toInt())
    InstallStage.Verifying -> stringResource(R.string.stage_verifying)
    InstallStage.Installing -> stringResource(R.string.stage_installing)
    is InstallStage.Failed -> stringResource(R.string.stage_failed, stage.reason)
}
