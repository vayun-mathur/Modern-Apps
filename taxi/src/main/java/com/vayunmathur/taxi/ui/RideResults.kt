package com.vayunmathur.taxi.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconLocationOn
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.taxi.R
import com.vayunmathur.taxi.data.Place
import com.vayunmathur.taxi.data.Provider
import com.vayunmathur.taxi.data.QuoteResult
import com.vayunmathur.taxi.data.RideQuote

@Composable
internal fun ResultsSection(
    results: Map<Provider, QuoteResult>,
    comparing: Boolean,
    hasRoute: Boolean,
    modifier: Modifier = Modifier,
    onBook: (Provider, RideQuote?) -> Unit,
) {
    when {
        comparing -> Column(
            modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.comparing_fares),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        !hasRoute -> EmptyPrompt(modifier)

        else -> {
            val quotes = results.values
                .filterIsInstance<QuoteResult.Success>()
                .flatMap { it.quotes }
                .sortedBy { it.fareLowMinor }
            val notes = results.entries
                .filter { it.value !is QuoteResult.Success }
                .map { it.key to it.value }
            val cheapest = quotes.firstOrNull()

            LazyColumn(
                modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (quotes.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.choose_ride)) }
                    items(quotes) { quote ->
                        RideOptionCard(
                            quote = quote,
                            isCheapest = quote === cheapest,
                            onBook = { onBook(quote.provider, quote) },
                        )
                    }
                }
                items(notes) { (provider, result) ->
                    ProviderNoteCard(provider, result) { onBook(provider, null) }
                }
                if (quotes.isEmpty() && notes.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.no_places_found),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RideOptionCard(quote: RideQuote, isCheapest: Boolean, onBook: () -> Unit) {
    Card(
        onClick = onBook,
        modifier = Modifier.fillMaxWidth(),
        border = if (isCheapest) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderBadge(quote.provider)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        quote.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isCheapest) {
                        Tag(
                            text = stringResource(R.string.best_price),
                            container = MaterialTheme.colorScheme.primaryContainer,
                            content = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                val sub = listOfNotNull(
                    quote.pickupEtaMinutes?.let { stringResource(R.string.eta_minutes, it) },
                    quote.capacity?.let { pluralStringResource(R.plurals.seats, it, it) },
                ).joinToString(" · ")
                if (sub.isNotEmpty()) {
                    Text(
                        sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                if (quote.hasDiscount) {
                    Text(
                        formatOriginalFare(quote),
                        style = MaterialTheme.typography.bodySmall.copy(
                            textDecoration = TextDecoration.LineThrough,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    formatFare(quote),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (quote.hasDiscount) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                quote.surgeMultiplier?.takeIf { it > 1.0 }?.let {
                    Text(
                        stringResource(R.string.surge_short, "%.1f".format(it)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderNoteCard(provider: Provider, result: QuoteResult, onBook: () -> Unit) {
    val message = when (result) {
        is QuoteResult.NotSignedIn -> stringResource(R.string.connect_prompt, provider.label)
        is QuoteResult.Failed -> result.message
        else -> ""
    }
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderBadge(provider)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    provider.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onBook) {
                Text(stringResource(R.string.book_with, provider.label))
            }
        }
    }
}

@Composable
internal fun ProviderBadge(provider: Provider) {
    Box(
        Modifier.size(42.dp).clip(CircleShape).background(providerColor(provider)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            provider.label.take(1),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun Tag(text: String, container: Color, content: Color) {
    Surface(shape = RoundedCornerShape(6.dp), color = container) {
        Text(
            text,
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = content,
        )
    }
}

@Composable
internal fun LoadingRow(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyPrompt(modifier: Modifier) {
    Column(
        modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconSearch(Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.enter_destination),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

internal fun formatFare(quote: RideQuote): String {
    fun money(minor: Long) = "$%.2f".format(minor / 100.0)
    return if (quote.isRange) {
        "${money(quote.fareLowMinor)} – ${money(quote.fareHighMinor)}"
    } else {
        money(quote.fareLowMinor)
    }
}

/** The pre-discount price, for a struck-through "was" label when a promotion applies. */
internal fun formatOriginalFare(quote: RideQuote): String {
    fun money(minor: Long) = "$%.2f".format(minor / 100.0)
    val low = quote.originalFareLowMinor ?: quote.fareLowMinor
    val high = quote.originalFareHighMinor ?: quote.fareHighMinor
    return if (low != high) "${money(low)} – ${money(high)}" else money(low)
}
