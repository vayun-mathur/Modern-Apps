package com.vayunmathur.games.logicgate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.logicgate.R
import com.vayunmathur.games.logicgate.domain.levels.Chapter
import com.vayunmathur.games.logicgate.platform.LevelCard
import com.vayunmathur.games.logicgate.platform.ProgressionUiState
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.IconLock
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.game.GameTopBarActions

/** The level map: chapters in order, each level with its lock, stars and best cost. */
@Composable
fun ProgressionScreen(
    state: ProgressionUiState,
    onOpenLevel: (String) -> Unit,
    onOpenGameCenter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppScaffold(
        title = stringResource(R.string.app_name),
        modifier = modifier,
        actions = { GameTopBarActions(onOpenGameCenter = onOpenGameCenter) },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.lg,
                end = Spacing.lg,
                top = padding.calculateTopPadding() + Spacing.sm,
                bottom = padding.calculateBottomPadding() + Spacing.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item(key = "header") { Header(state) }
            for (chapter in Chapter.entries) {
                val cards = state.levels.filter { it.chapter == chapter }
                if (cards.isEmpty()) continue
                item(key = chapter.name) { ChapterHeader(chapter) }
                items(cards, key = { it.id }) { card -> LevelRow(card, onOpenLevel) }
            }
        }
    }
}

@Composable
private fun Header(state: ProgressionUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(stringResource(R.string.lg_tagline), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.lg_solved_count, state.solved, state.levels.size),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { if (state.levels.isEmpty()) 0f else state.solved / state.levels.size.toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ChapterHeader(chapter: Chapter) {
    val (title, desc) = when (chapter) {
        Chapter.BASICS -> R.string.lg_chapter_basics to R.string.lg_chapter_basics_desc
        Chapter.ROUTING -> R.string.lg_chapter_routing to R.string.lg_chapter_routing_desc
        Chapter.ARITHMETIC -> R.string.lg_chapter_arithmetic to R.string.lg_chapter_arithmetic_desc
        Chapter.MEMORY -> R.string.lg_chapter_memory to R.string.lg_chapter_memory_desc
        Chapter.CPU -> R.string.lg_chapter_cpu to R.string.lg_chapter_cpu_desc
    }
    Column(Modifier.padding(top = Spacing.lg)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LevelRow(card: LevelCard, onOpenLevel: (String) -> Unit) {
    val locked = stringResource(R.string.lg_cd_locked)
    Card(
        onClick = { onOpenLevel(card.id) },
        enabled = card.unlocked,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { if (!card.unlocked) contentDescription = "${card.title}, $locked" },
    ) {
        Row(
            Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Surface(
                shape = CircleShape,
                color = if (card.completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(NUMBER_SIZE),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(card.number.toString(), style = MaterialTheme.typography.labelLarge)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(card.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    card.bestCost?.let { stringResource(R.string.lg_best_cost, it, card.par) }
                        ?: stringResource(R.string.lg_par, card.par),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (card.unlocked) StarRating(card.stars) else IconLock(tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val NUMBER_SIZE = 36.dp
