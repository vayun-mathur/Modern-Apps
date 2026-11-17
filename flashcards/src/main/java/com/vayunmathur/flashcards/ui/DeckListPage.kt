package com.vayunmathur.flashcards.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.flashcards.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.flashcards.Route
import com.vayunmathur.flashcards.data.Deck
import com.vayunmathur.flashcards.ui.DeckListActions
import com.vayunmathur.flashcards.ui.DeckListUiState
import com.vayunmathur.flashcards.ui.DeckSummary
import com.vayunmathur.flashcards.platform.FlashcardsViewModel
import com.vayunmathur.flashcards.platform.addDeckImpl
import com.vayunmathur.flashcards.platform.deleteDeckImpl
import com.vayunmathur.flashcards.platform.reorderDecksImpl
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDragHandle
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.IconStyle
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ReorderableItem
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.library.ui.rememberReorderableLazyListState
import com.vayunmathur.library.ui.reorderDragHandle
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.animatedDp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.sharedText
import kotlin.math.roundToInt

/** Binds [FlashcardsViewModel] and the nav back stack to the stateless [DeckListScreen]. */
@Composable
fun DeckListPage(backStack: NavBackStack<Route>, viewModel: FlashcardsViewModel) {
    val decks by viewModel.decks.collectAsStateWithLifecycle()
    val cards by viewModel.cards.collectAsStateWithLifecycle()

    val now = System.currentTimeMillis()
    val summaries = decks.sortedBy { it.position }.map { deck ->
        val deckCards = cards.filter { it.deckId == deck.id }
        DeckSummary(
            deck = deck,
            dueCount = deckCards.count { !it.isNew && !it.isSuspended && it.dueDate <= now },
            newCount = deckCards.count { it.isNew && !it.isSuspended },
            totalCount = deckCards.size,
            mastery = FlashcardsViewModel.mastery(deckCards),
        )
    }

    val actions = remember(backStack, viewModel) {
        object : DeckListActions {
            override fun openDeck(id: Long) {
                val route = Route.CardList(id)
                // Re-tapping another deck replaces the detail instead of stacking
                // CardList on CardList.
                if (backStack.last() is Route.CardList) backStack.setLast(route) else backStack.add(route)
            }
            override fun addDeck(name: String) { viewModel.addDeckImpl(name) }
            override fun deleteDeck(deck: Deck) { viewModel.deleteDeckImpl(deck) }
            override fun startReview(deckId: Long) { backStack.add(Route.Review(deckId)) }
            override fun reorder(decks: List<Deck>) { viewModel.reorderDecksImpl(decks) }
        }
    }

    DeckListScreen(
        state = DeckListUiState(decks = summaries),
        actions = actions,
    )
}

@Composable
internal fun AddDeckDialog(onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    BackHandler { onDismiss() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_deck)) },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.deck_name)) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) onAdd(name.trim())
                onDismiss()
            }) { Text(stringResource(UiR.string.add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) }
        },
    )
}
