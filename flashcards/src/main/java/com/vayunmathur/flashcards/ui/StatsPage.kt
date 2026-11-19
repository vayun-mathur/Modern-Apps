package com.vayunmathur.flashcards.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.flashcards.R
import com.vayunmathur.flashcards.Route
import com.vayunmathur.flashcards.data.CardState
import com.vayunmathur.flashcards.ui.DailyStat
import com.vayunmathur.flashcards.ui.DeckOption
import com.vayunmathur.flashcards.platform.FlashcardsViewModel
import com.vayunmathur.flashcards.ui.StatsActions
import com.vayunmathur.flashcards.platform.reviewLogsForImpl
import com.vayunmathur.flashcards.ui.StatsUiState
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SecondaryTabRow
import com.vayunmathur.library.ui.Tab
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack
import java.time.LocalDate

/** Binds review history for the selected deck to the stateless [StatsScreen]. */
@Composable
fun StatsPage(backStack: NavBackStack<Route>, viewModel: FlashcardsViewModel) {
    val decks by viewModel.decks.collectAsStateWithLifecycle()
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    var selectedDeckId by remember { mutableStateOf<Long?>(null) }
    val logs by remember(selectedDeckId) { viewModel.reviewLogsForImpl(selectedDeckId) }
        .collectAsStateWithLifecycle(emptyList())

    val deckOptions = buildList {
        add(DeckOption(null, stringResource(R.string.all_decks)))
        decks.sortedBy { it.position }.forEach { add(DeckOption(it.id, it.name)) }
    }

    val zone = java.time.ZoneId.systemDefault()
    val byDay = logs.groupBy {
        java.time.Instant.ofEpochMilli(it.reviewedAt).atZone(zone).toLocalDate().toEpochDay()
    }
    val daily = byDay.map { (day, entries) -> DailyStat(day, entries.size) }.sortedBy { it.epochDay }

    // Retention over genuine recall attempts (cards previously seen).
    val recalls = logs.filter { it.elapsedDays > 0 }
    val retentionPct = if (recalls.isEmpty()) 0 else {
        (recalls.count { it.grade > 1 } * 100) / recalls.size
    }

    val today = LocalDate.now().toEpochDay()
    var streak = 0
    while (byDay.containsKey(today - streak)) streak++

    val deckCards = if (selectedDeckId == null) cards else cards.filter { it.deckId == selectedDeckId }

    // Cards becoming due per day over the next 30 days (overdue lumped into today).
    val forecast = (0 until 30).map { offset ->
        val day = today + offset
        val count = deckCards.count { card ->
            if (card.isNew || card.isSuspended) return@count false
            val due = java.time.Instant.ofEpochMilli(card.dueDate).atZone(zone).toLocalDate().toEpochDay()
            if (offset == 0) due <= day else due == day
        }
        DailyStat(day, count)
    }

    // Pass rate bucketed by the elapsed interval at review time.
    val buckets = listOf(
        "≤1" to 1.0,
        "2–3" to 3.0,
        "4–7" to 7.0,
        "8–14" to 14.0,
        "15–30" to 30.0,
        "31+" to Double.MAX_VALUE,
    )
    var lower = 0.0
    val retentionBuckets = buckets.map { (label, upper) ->
        val inBucket = recalls.filter { it.elapsedDays > lower && it.elapsedDays <= upper }
        lower = upper
        val rate = if (inBucket.isEmpty()) 0f else inBucket.count { it.grade > 1 }.toFloat() / inBucket.size
        label to rate
    }

    StatsScreen(
        state = StatsUiState(
            deckOptions = deckOptions,
            selectedDeckId = selectedDeckId,
            daily = daily,
            totalReviews = logs.size,
            retentionPct = retentionPct,
            streakDays = streak,
            matureCards = deckCards.count { it.state == CardState.REVIEW },
            totalCards = deckCards.size,
            forecast = forecast,
            retentionBuckets = retentionBuckets,
            heatmap = daily,
        ),
        actions = object : StatsActions {
            override fun back() { backStack.pop() }
            override fun selectDeck(id: Long?) { selectedDeckId = id }
        },
    )
}
