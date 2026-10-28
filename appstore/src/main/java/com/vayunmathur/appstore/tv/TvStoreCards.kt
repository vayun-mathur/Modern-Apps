package com.vayunmathur.appstore.tv

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallStage
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.tv.TvBadge
import com.vayunmathur.library.tv.TvIcon
import com.vayunmathur.library.tv.TvInfoCard
import com.vayunmathur.library.ui.IconCheck
import java.util.Locale

/**
 * The one app card for every TV grid: icon, name, developer/source byline, the
 * summary as a two-line description, source/verified/installed badges, and the
 * install progress strip. Everything a phone search result shows.
 *
 * One card everywhere — home, browse, search, updates, library — so the whole TV
 * store reads the same from the couch.
 */
@Composable
fun TvStoreAppCard(
    app: UnifiedApp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    installedIcon: Drawable? = null,
    isInstalled: Boolean = false,
    hasUpdate: Boolean = false,
    stage: InstallStage? = null,
    versionLabel: String? = null,
    /** e.g. the Updates screen's per-app Update button. */
    trailing: (@Composable () -> Unit)? = null,
    onFocus: (() -> Unit)? = null,
) {
    TvInfoCard(
        title = app.name,
        onClick = onClick,
        modifier = modifier,
        subtitle = (app.author ?: app.tvSourceLabel()),
        description = versionLabel ?: app.summary.ifBlank { null },
        leading = { TvStoreAppArt(app = app, installedIcon = installedIcon) },
        badges = {
            TvStoreCardBadges(
                app = app,
                isInstalled = isInstalled,
                hasUpdate = hasUpdate,
                stage = stage,
            )
        },
        trailing = trailing,
        progress = stage?.downloadFraction(),
        onFocus = onFocus,
    )
}

/** Source/verified/installed pills under the card text. Update outranks installed. */
@Composable
private fun TvStoreCardBadges(
    app: UnifiedApp,
    isInstalled: Boolean,
    hasUpdate: Boolean,
    stage: InstallStage?,
) {
    TvBadge(text = app.tvSourceLabel())
    if (app.rating != null) {
        TvBadge(text = String.format(Locale.US, "%.1f ★", app.rating))
    }
    if (app.reproducible) {
        TvBadge(text = stringResource(R.string.chip_reproducible))
    }
    if (stage is InstallStage.Failed) {
        Text(
            text = stringResource(R.string.stage_failed, stage.reason),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    if (hasUpdate) {
        TvBadge(text = stringResource(R.string.action_update))
        return
    }
    if (isInstalled) {
        TvIcon { IconCheck() }
        Text(
            text = stringResource(R.string.installed),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Icon art with the phone's fallback chain: the installed launcher icon beats
 * re-downloading the listing's copy, then the listing URL, then an initial.
 */
@Composable
internal fun BoxScope.TvStoreAppArt(
    app: UnifiedApp,
    installedIcon: Drawable?,
    size: Dp = 64.dp,
) {
    val px = with(LocalDensity.current) { size.roundToPx() * 2 }.coerceAtLeast(1)
    val bitmap = remember(installedIcon, px) {
        installedIcon?.let {
            runCatching {
                it.toBitmap(width = px, height = px).asImageBitmap()
            }.getOrNull()
        }
    }
    when {
        bitmap != null -> Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.size(size).clip(MaterialTheme.shapes.small),
            contentScale = ContentScale.Crop,
        )
        app.iconUrl != null -> AsyncImage(
            model = app.iconUrl,
            contentDescription = null,
            modifier = Modifier.size(size).clip(MaterialTheme.shapes.small),
            contentScale = ContentScale.Crop,
        )
        else -> Box(
            Modifier.size(size)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = app.name.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Where the app came from. Purely factual — the sources are not ranked. */
@Composable
internal fun UnifiedApp.tvSourceLabel(): String = stringResource(
    when (source) {
        AppSource.MODERN_APPS -> R.string.source_chip_modern_apps
        AppSource.FDROID -> R.string.source_chip_fdroid
        AppSource.IZZY -> R.string.source_chip_izzy
        AppSource.PLAYSTORE -> R.string.source_chip_play
        AppSource.ACCRESCENT -> R.string.source_chip_accrescent
        AppSource.PROPRIETARY -> R.string.source_chip_proprietary
    },
)

/** The install stage as a one-line label, mirroring the phone's stageLabel. */
@Composable
internal fun InstallStage.tvStageLabel(): String = when (this) {
    InstallStage.Preparing -> stringResource(R.string.stage_preparing)
    is InstallStage.Downloading -> stringResource(R.string.stage_downloading, (fraction * 100).toInt())
    InstallStage.Verifying -> stringResource(R.string.stage_verifying)
    InstallStage.Installing -> stringResource(R.string.stage_installing)
    is InstallStage.Failed -> stringResource(R.string.stage_failed, reason)
}

/** 0..1 download fraction for card progress strips, null for indeterminate stages. */
internal fun InstallStage.downloadFraction(): Float? =
    (this as? InstallStage.Downloading)?.fraction?.coerceIn(0f, 1f)
