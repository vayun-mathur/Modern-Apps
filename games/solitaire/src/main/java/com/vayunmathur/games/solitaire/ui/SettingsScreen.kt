package com.vayunmathur.games.solitaire.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.games.solitaire.R
import com.vayunmathur.games.solitaire.data.CardColorScheme
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSelectRow
import com.vayunmathur.library.ui.R as UiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    cardColorScheme: CardColorScheme,
    onCardColorSchemeChange: (CardColorScheme) -> Unit,
    onBack: () -> Unit
) {
    DetailScaffold(
        title = stringResource(UiR.string.settings),
        onNavigateBack = onBack,
    ) {
        SettingsSection {
            // Pre-resolved because SettingsSelectRow's `label` is a plain lambda, not a
            // composable one, so stringResource cannot be called inside it.
            val schemeLabels = mapOf(
                CardColorScheme.TWO_COLOUR to stringResource(R.string.card_colors_two),
                CardColorScheme.FOUR_COLOUR to stringResource(R.string.card_colors_four),
            )
            SettingsSelectRow(
                title = stringResource(R.string.card_colors),
                selected = cardColorScheme,
                options = CardColorScheme.entries,
                label = { schemeLabels.getValue(it) },
                onSelect = onCardColorSchemeChange,
            )
        }
    }
}
