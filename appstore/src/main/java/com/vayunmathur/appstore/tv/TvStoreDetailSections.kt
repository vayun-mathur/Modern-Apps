package com.vayunmathur.appstore.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.security.TrustProfile
import com.vayunmathur.appstore.ui.formatCount
import com.vayunmathur.appstore.ui.rememberDescriptionText
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.image.compose.AsyncImageState
import com.vayunmathur.library.tv.TvAspect
import com.vayunmathur.library.tv.TvBadge
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvMediaCard
import com.vayunmathur.library.tv.TvShelf
import com.vayunmathur.library.ui.IconGlobe

/**
 * The right pane of the TV detail page: the scrolling body. Same order and same
 * strings as the phone's detail page — summary headline, description, What's new,
 * category chips, the security/source card with its verification line, the
 * screenshots shelf, links, and the remaining facts last.
 *
 * Split from TvStoreDetail.kt to keep both files under the 350-line limit.
 */
@Composable
internal fun TvStoreDetailBody(
    state: TvDetailUiState,
    onOpenInBrowser: (String) -> Unit,
    onOpenInPlayStore: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = state.app ?: return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        // Summary headline always; full description behind a toggle — most are long.
        if (app.summary.isNotBlank()) {
            Text(
                text = app.summary,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (app.description.isNotBlank() && app.description != app.summary) {
            var expanded by remember(app.packageName) { mutableStateOf(false) }
            Text(
                text = rememberDescriptionText(app.description, MaterialTheme.colorScheme.primary),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                maxLines = if (expanded) Int.MAX_VALUE else 5,
                overflow = TextOverflow.Ellipsis,
            )
            TvButton(
                text = stringResource(
                    if (expanded) R.string.action_show_less else R.string.action_read_more,
                ),
                onClick = { expanded = !expanded },
            )
        }
        if (app.whatsNew?.isNotBlank() == true) {
            TvStoreDetailBlock(title = stringResource(R.string.detail_whats_new)) {
                app.updatedOn?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = app.whatsNew,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                )
            }
        }
        TvStoreDetailChips(app = app)
        TvStoreTrustCard(state = state)
        val shots = app.tvScreenshots.ifEmpty { app.screenshots }
        if (shots.isNotEmpty()) {
            TvStoreScreenshotShelf(urls = shots)
        }
        TvStoreDetailLinks(
            app = app,
            onOpenInBrowser = onOpenInBrowser,
            onOpenInPlayStore = onOpenInPlayStore,
        )
        TvStoreDetailFacts(app = app)
    }
}

@Composable
private fun TvStoreDetailBlock(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        content()
    }
}

/** Categories, reproducible badge, ads and anti-features. Like the phone's chips row. */
@Composable
private fun TvStoreDetailChips(app: UnifiedApp, modifier: Modifier = Modifier) {
    if (app.categories.isEmpty() && app.antiFeatures.isEmpty() &&
        !app.containsAds && !app.reproducible
    ) {
        return
    }
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (app.reproducible) {
            TvBadge(text = stringResource(R.string.chip_reproducible))
        }
        app.categories.take(CHIP_LIMIT).forEach { TvBadge(text = it) }
        if (app.containsAds) {
            TvBadge(text = stringResource(R.string.chip_contains_ads))
        }
        // F-Droid's anti-feature labels are the honest part of its listings and
        // there is no reason to bury them; they are flagged rather than hidden.
        app.antiFeatures.take(CHIP_LIMIT).forEach { TvBadge(text = it) }
    }
}

/**
 * What can be checked about this app's download, and what the last install proved.
 * The phone's "F-Droid — Built from public source code…" card with the verification
 * line, neutral by design: it describes the source's practices, not a ranking.
 */
@Composable
private fun TvStoreTrustCard(state: TvDetailUiState, modifier: Modifier = Modifier) {
    val app = state.app ?: return
    val profile = TrustProfile.of(app.source)
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(scheme.surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(profile.title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurface,
        )
        Text(
            text = stringResource(profile.summary),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
        val message = state.verification.toTvCardMessage()
        Text(
            text = message ?: stringResource(R.string.detail_tap_for_trust),
            style = MaterialTheme.typography.bodyMedium,
            color = if (message == null) {
                scheme.onSurfaceVariant
            } else if (state.verification is com.vayunmathur.appstore.data.security.VerificationResult.Verified) {
                scheme.primary
            } else {
                scheme.error
            },
        )
    }
}

@Composable
private fun com.vayunmathur.appstore.data.security.VerificationResult?.toTvCardMessage(): String? =
    when (this) {
        is com.vayunmathur.appstore.data.security.VerificationResult.Rejected ->
            stringResource(R.string.detail_install_blocked, reason)
        is com.vayunmathur.appstore.data.security.VerificationResult.Verified ->
            stringResource(R.string.detail_checked_on_install, detail)
        is com.vayunmathur.appstore.data.security.VerificationResult.Unverified ->
            stringResource(R.string.detail_installed_unverified, reason)
        null -> null
    }

/** TV screenshots where the source publishes a set; landscape cards in a shelf. */
@Composable
private fun TvStoreScreenshotShelf(urls: List<String>, modifier: Modifier = Modifier) {
    TvShelf(title = stringResource(R.string.tv_detail_screenshots), modifier = modifier) {
        items(urls, key = { it }) { url ->
            // The width has to be stated: AsyncImage draws its bitmap with matchParentSize,
            // which contributes nothing to the measure pass, so an image given only a
            // height resolves to zero wide. Start at 16:9 and switch to the real ratio
            // once the bitmap has been decoded.
            var ratio by remember(url) { mutableFloatStateOf(TvAspect.Landscape) }
            TvMediaCard(
                onClick = {},
                width = 320.dp,
                aspectRatio = ratio,
                image = {
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().aspectRatio(ratio),
                        contentScale = ContentScale.Fit,
                        onState = { state ->
                            val size = (state as? AsyncImageState.Success)?.painter?.intrinsicSize
                            if (size != null && size.width > 0f && size.height > 0f) {
                                ratio = size.width / size.height
                            }
                        },
                    )
                },
            )
        }
    }
}

/** Website / source / privacy links as buttons, plus the Play fallback. Like the phone. */
@Composable
private fun TvStoreDetailLinks(
    app: UnifiedApp,
    onOpenInBrowser: (String) -> Unit,
    onOpenInPlayStore: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val links = buildList<Pair<Int, String>> {
        app.website?.let { add(R.string.link_website to it) }
        app.sourceCode?.let { add(R.string.link_source to it) }
        app.privacyPolicyUrl?.let { add(R.string.link_privacy to it) }
    }
    if (links.isEmpty() && app.source != AppSource.PLAYSTORE) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        links.forEach { (label, url) ->
            TvButton(
                text = stringResource(label),
                onClick = { onOpenInBrowser(url) },
                icon = { IconGlobe() },
            )
        }
        if (app.source == AppSource.PLAYSTORE) {
            TvButton(
                text = stringResource(R.string.view_in_play_store),
                onClick = { onOpenInPlayStore(app.packageName) },
            )
        }
    }
}

/** The remaining facts, as a plain table — deliberately last, like the phone. */
@Composable
private fun TvStoreDetailFacts(app: UnifiedApp, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (app.ratingCount > 0) {
            TvStoreFact(stringResource(R.string.detail_rating_count), formatCount(app.ratingCount))
        }
        if (app.permissions.isNotEmpty()) {
            TvStoreFact(
                stringResource(R.string.detail_permissions),
                app.permissions.joinToString("\n") { it.substringAfterLast('.') },
            )
        }
        app.author?.let { TvStoreFact(stringResource(R.string.tv_detail_author), it) }
        app.license?.let { TvStoreFact(stringResource(R.string.detail_license), it) }
        app.targetSdk?.let {
            TvStoreFact(stringResource(R.string.detail_target_sdk), it.toString())
        }
        TvStoreFact(stringResource(R.string.detail_package), app.packageName)
    }
}

@Composable
private fun TvStoreFact(label: String, value: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private const val CHIP_LIMIT = 6
