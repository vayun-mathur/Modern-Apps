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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.flashcards.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.flashcards.Route
import com.vayunmathur.flashcards.data.Deck
import com.vayunmathur.flashcards.util.DeckListActions
import com.vayunmathur.flashcards.util.DeckListUiState
import com.vayunmathur.flashcards.util.DeckSummary
import com.vayunmathur.flashcards.util.FlashcardsViewModel
import com.vayunmathur.flashcards.util.addDeckImpl
import com.vayunmathur.flashcards.util.deleteDeckImpl
import com.vayunmathur.flashcards.util.reorderDecksImpl
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

/**
 * The deck list, with no dependency on the ViewModel or the back stack so it can be
 * rendered from a `@Preview` — see `src/screenshotTest`, which is where the store listing
 * images come from.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeckListScreen(
    state: DeckListUiState,
    actions: DeckListActions,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Deck?>(null) }

    val listState = rememberLazyListState()
    var localDecks by remember { mutableStateOf(state.decks) }
    var hasDragged by remember { mutableStateOf(false) }
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        if (from.index in localDecks.indices && to.index in localDecks.indices) {
            localDecks = localDecks.toMutableList().apply { add(to.index, removeAt(from.index)) }
            hasDragged = true
        }
    }
    LaunchedEffect(state.decks) {
        if (!reorderState.isAnyItemDragging) localDecks = state.decks
    }
    LaunchedEffect(reorderState.isAnyItemDragging) {
        if (!reorderState.isAnyItemDragging && hasDragged) {
            actions.reorder(localDecks.mapIndexed { index, s -> s.deck.withPosition(index.toDouble()) })
            hasDragged = false
        }
    }

    AppScaffold(
        title = stringResource(R.string.app_name),
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) { IconAdd() }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { paddingValues ->
        if (localDecks.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.no_decks),
                message = stringResource(R.string.no_decks_hint),
                icon = { IconStyle() },
                modifier = Modifier.fillMaxSize().padding(paddingValues),
            )
            return@AppScaffold
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = paddingValues,
        ) {
            items(localDecks, key = { it.deck.id }) { summary ->
                val dragging = reorderState.draggingKey == summary.deck.id
                val itemModifier = if (dragging) {
                    Modifier.zIndex(1f).graphicsLayer { translationY = reorderState.draggingItemTranslation }
                } else {
                    Modifier.animateItem()
                }
                ReorderableItem(reorderState, key = summary.deck.id, modifier = itemModifier) { isDragging ->
                    val elevation = animatedDp(if (isDragging) 8.dp else 0.dp)
                    Surface(shadowElevation = elevation) {
                        DeckRow(
                            summary = summary,
                            onOpen = { actions.openDeck(summary.deck.id) },
                            onLongPress = { pendingDelete = summary.deck },
                            onReview = { actions.startReview(summary.deck.id) },
                            dragHandle = Modifier.reorderDragHandle(reorderState, key = summary.deck.id),
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddDeckDialog(
            onAdd = { actions.addDeck(it) },
            onDismiss = { showAddDialog = false },
        )
    }

    pendingDelete?.let { deck ->
        ConfirmDialog(
            title = stringResource(UiR.string.delete),
            message = stringResource(R.string.delete_deck_message, deck.name),
            confirmLabel = stringResource(UiR.string.delete),
            dismissLabel = stringResource(UiR.string.cancel),
            destructive = true,
            onConfirm = {
                actions.deleteDeck(deck)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeckRow(
    summary: DeckSummary,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onReview: () -> Unit,
    dragHandle: Modifier,
) {
    ListItem(
        leadingContent = {
            MetricRing(
                progress = summary.mastery,
                label = "",
                value = "${(summary.mastery * 100).roundToInt()}%",
                modifier = Modifier.size(48.dp),
            )
        },
        headlineContent = {
            Text(
                summary.deck.name,
                modifier = Modifier.sharedText("flashcards-deck-name-${summary.deck.id}"),
            )
        },
        supportingContent = {
            Text(
                stringResource(
                    R.string.deck_summary,
                    summary.dueCount,
                    summary.newCount,
                    summary.totalCount,
                ),
            )
        },
        trailingContent = {
            Row {
                if (summary.dueCount > 0 || summary.newCount > 0) {
                    IconButton(onClick = onReview) { IconPlay() }
                }
                IconButton(onClick = {}, modifier = dragHandle) { IconDragHandle() }
            }
        },
        modifier = Modifier.combinedClickable(onClick = onOpen, onLongClick = onLongPress),
    )
}

@Composable
private fun AddDeckDialog(onAdd: (String) -> Unit, onDismiss: () -> Unit) {
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
