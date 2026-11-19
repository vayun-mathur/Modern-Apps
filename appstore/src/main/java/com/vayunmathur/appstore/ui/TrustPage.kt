package com.vayunmathur.appstore.ui
import com.vayunmathur.appstore.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.appstore.data.security.ApkCertificates
import com.vayunmathur.appstore.data.security.StoreGuarantees
import com.vayunmathur.appstore.data.security.TrustProfile
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CenterAlignedTopAppBar
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior

/**
 * How each source is checked, side by side and deliberately not ranked.
 *
 * The page this replaced was a 1-2-3 "security tier" ladder with Play at the bottom. That
 * scored a single property — whether this phone can check the download against a
 * publisher key — and then presented it as overall safety, which understates Play badly:
 * verified developer identity, upload and on-device scanning, HSM-held signing keys and
 * fleet-wide takedown are real protections that F-Droid has no equivalent of. Each card
 * below therefore states what the source itself does, what this app checks on top, and
 * where that source is weaker, and the reader draws their own conclusion.
 */
@Composable
fun TrustPage(
    ownSigningCertificates: Set<String>,
    onBack: () -> Unit,
    /**
     * Seed for the list's own scroll position. The app always takes the default; the store
     * listing previews set it so a source card can be captured without driving a scroll
     * gesture, which a `@Preview` cannot do.
     */
    initialFirstVisibleItem: Int = 0,
) {
    LazyListScaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.trust_page_title)) },
                navigationIcon = { IconButton(onClick = onBack) { IconBack() } },
            )
        },
        state = rememberLazyListState(initialFirstVisibleItemIndex = initialFirstVisibleItem),
        horizontalPadding = 16.dp,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
            item {
                Text(
                    stringResource(R.string.trust_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            stringResource(R.string.trust_every_app),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            stringResource(R.string.trust_every_app_detail),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        StoreGuarantees.rules.forEach { rule ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    stringResource(rule.title),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    stringResource(rule.detail, *rule.detailArgs.toTypedArray()),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }

            items(TrustProfile.entries.toList()) { profile -> ProfileCard(profile) }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.trust_own_key_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            stringResource(R.string.trust_own_key_detail),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (ownSigningCertificates.isEmpty()) {
                            Text(
                                stringResource(R.string.trust_own_key_unreadable),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else {
                            ownSigningCertificates.forEach {
                                Text(
                                    ApkCertificates.abbreviate(it),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

@Composable
private fun ProfileCard(profile: TrustProfile) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(profile.title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(stringResource(profile.summary), style = MaterialTheme.typography.bodyMedium)

            HorizontalDivider()
            Heading(R.string.trust_heading_source_does)
            profile.sourcePractices.forEach { Bullet(it) }

            Heading(R.string.trust_heading_we_check)
            profile.ourChecks.forEach { Bullet(it) }

            Heading(R.string.trust_heading_weaker)
            Text(
                stringResource(profile.limits),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (profile != TrustProfile.PLAY) {
                Text(
                    stringResource(R.string.trust_no_takedown),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Heading(res: Int) {
    Text(
        stringResource(res),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Bullet(res: Int) {
    Text(
        stringResource(R.string.trust_bullet, stringResource(res)),
        style = MaterialTheme.typography.bodySmall,
    )
}
