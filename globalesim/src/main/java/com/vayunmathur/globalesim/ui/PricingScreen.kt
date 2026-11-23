package com.vayunmathur.globalesim.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.globalesim.R
import com.vayunmathur.globalesim.domain.QuoteMath
import com.vayunmathur.globalesim.platform.EsimUiState
import com.vayunmathur.library.ui.ScaffoldSearch
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.ExpandVisibility
import com.vayunmathur.library.ui.IconSim
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.animatedColor
import com.vayunmathur.library.ui.animatedDp

/**
 * Pricing: searchable per-country rate list with the search field in the
 * app bar (no title text). Tapping a country expands it to show every
 * carrier and its per-GB price (cheapest first), in the same
 * expandable-group style as the contacts groups page. Display-only —
 * the wallet is charged the local rate automatically as you use data.
 */
@Composable
fun PricingScreen(
    state: EsimUiState,
    onRefresh: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val expanded = remember { mutableStateListOf<String>() }
    val filtered = remember(state.rates, query) {
        if (query.isBlank()) state.rates
        else state.rates.filter {
            it.countryName.contains(query, ignoreCase = true) ||
                it.countryCode.contains(query, ignoreCase = true)
        }
    }

    LazyListScaffold(
        search = ScaffoldSearch(
            query = query,
            onQueryChange = { query = it },
            placeholder = stringResource(R.string.search_countries),
        ),
        horizontalPadding = 8.dp,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (filtered.isEmpty()) {
            item(key = "pricing-empty") {
                if (query.isBlank()) {
                    EmptyState(
                        title = stringResource(R.string.no_rates_yet),
                        message = stringResource(R.string.rates_load_failed),
                        icon = { IconSim() },
                        action = { OutlinedButton(onClick = onRefresh) { Text(stringResource(UiR.string.retry)) } },
                    )
                } else {
                    EmptyState(
                        title = stringResource(R.string.no_matches),
                        message = stringResource(R.string.no_matches_hint),
                        icon = { IconSim() },
                    )
                }
            }
        }
        filtered.forEach { rate ->
            val isExpanded = rate.countryCode in expanded
            // Expand whenever tapped; the body shows carriers, or a fallback
            // line when the server didn't send a breakdown.
            item(key = "country-${rate.countryCode}") {
                val collapsedColor = MaterialTheme.colorScheme.surfaceVariant
                val expandedColor = MaterialTheme.colorScheme.secondaryContainer
                val headerColor = animatedColor(if (isExpanded) expandedColor else collapsedColor)
                val bottomRadius = animatedDp(if (isExpanded) 4.dp else 16.dp)
                Column {
                    Surface(
                        onClick = {
                            if (isExpanded) expanded.remove(rate.countryCode)
                            else expanded.add(rate.countryCode)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(
                            topStart = 16.dp, topEnd = 16.dp,
                            bottomStart = bottomRadius, bottomEnd = bottomRadius,
                        ),
                        color = headerColor,
                    ) {
                        ListItem(
                            content = {
                                Text(
                                    text = rate.countryName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                            },
                            supportingContent = {
                                Text(stringResource(R.string.price_from_per_gb, QuoteMath.formatCents(rate.pricePerGbUsdCents)))
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                    ExpandVisibility(visible = isExpanded) {
                        Column {
                            Spacer(Modifier.height(2.dp))
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            ) {
                                Column(Modifier.padding(vertical = 4.dp)) {
                                    if (rate.networks.isEmpty()) {
                                        ListItem(
                                            content = {
                                                Text(
                                                    stringResource(
                                                        R.string.carrier_breakdown_unavailable,
                                                        QuoteMath.formatCents(rate.pricePerGbUsdCents),
                                                    ),
                                                )
                                            },
                                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                        )
                                    } else {
                                        rate.networks.forEach { net ->
                                            ListItem(
                                                content = { Text(net.operator) },
                                                supportingContent = {
                                                    Text(stringResource(R.string.price_per_gb, QuoteMath.formatCents(net.pricePerGbUsdCents)))
                                                },
                                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
