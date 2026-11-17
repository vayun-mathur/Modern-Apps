package com.vayunmathur.flashcards.platform
import com.vayunmathur.flashcards.domain.DeckIo

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.flashcards.data.Deck
import com.vayunmathur.flashcards.data.deleteCardsByDeckImpl
import com.vayunmathur.flashcards.data.deleteDeckImpl
import com.vayunmathur.flashcards.data.deleteNotesByDeckImpl
import com.vayunmathur.flashcards.data.deleteReviewLogsByDeckImpl
import com.vayunmathur.flashcards.data.getCardsByDeckImpl
import com.vayunmathur.flashcards.data.getDeckImpl
import com.vayunmathur.flashcards.data.getNotesByDeckImpl
import com.vayunmathur.flashcards.data.getReviewLogsByDeckOrderedImpl
import com.vayunmathur.flashcards.data.insertReviewLogImpl
import com.vayunmathur.flashcards.data.upsertCardsImpl
import com.vayunmathur.flashcards.data.upsertDeckImpl
import com.vayunmathur.flashcards.data.upsertNotesImpl
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.flashcards.R
import com.vayunmathur.library.ui.R as UiR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ------------------------------------------------------------------
// Deck writes + CSV export — moved from FlashcardsViewModel.kt
// (TooManyFunctions/LargeClass split); behavior identical.
// ------------------------------------------------------------------

internal fun FlashcardsViewModel.launchIoImpl(block: suspend () -> Unit): kotlinx.coroutines.Job =
    viewModelScope.launch(Dispatchers.IO) { block() }

internal fun FlashcardsViewModel.upsertDeckImpl(deck: Deck): kotlinx.coroutines.Job =
    launchIoImpl { repository.upsertDeckImpl(deck) }

internal fun FlashcardsViewModel.addDeckImpl(name: String) = launchIoImpl {
    val s = settings.value
    repository.upsertDeckImpl(
        Deck(
            name = name,
            newPerDay = s.newPerDay,
            maxReviewsPerDay = s.maxReviews,
            desiredRetention = s.desiredRetention,
        ),
    )
}

internal fun FlashcardsViewModel.deleteDeckImpl(deck: Deck) = launchIoImpl {
    val notes = repository.getNotesByDeckImpl(deck.id)
    val cards = repository.getCardsByDeckImpl(deck.id)
    val logs = repository.getReviewLogsByDeckOrderedImpl(deck.id)
    repository.deleteCardsByDeckImpl(deck.id)
    repository.deleteNotesByDeckImpl(deck.id)
    repository.deleteReviewLogsByDeckImpl(deck.id)
    repository.deleteDeckImpl(deck)
    AppMessages.show(
        getApplication<Application>().getString(R.string.deleted),
        actionLabel = getApplication<Application>().getString(UiR.string.undo),
        duration = AppMessages.Duration.Long,
    ) {
        launchIoImpl {
            repository.upsertDeckImpl(deck)
            if (notes.isNotEmpty()) repository.upsertNotesImpl(notes)
            if (cards.isNotEmpty()) repository.upsertCardsImpl(cards)
            logs.forEach { repository.insertReviewLogImpl(it) }
        }
    }
}

internal fun FlashcardsViewModel.reorderDecksImpl(decks: List<Deck>) =
    launchIoImpl { decks.forEach { repository.upsertDeckImpl(it) } }

/** Exports a deck's notes as `front,back` CSV and shares it. */
internal fun FlashcardsViewModel.exportCsvImpl(deckId: Long) = launchIoImpl {
    val context = getApplication<Application>()
    val notes = repository.getNotesByDeckImpl(deckId).sortedBy { it.position }
    val rows = notes.map { note ->
        val fields = note.fieldList
        stripTextImpl(fields.getOrNull(0).orEmpty()) to stripTextImpl(fields.getOrNull(1).orEmpty())
    }
    val uri = withContext(Dispatchers.IO) {
        val dir = java.io.File(context.cacheDir, "shared_decks").apply { mkdirs() }
        val deck = repository.getDeckImpl(deckId)
        val safe = (deck?.name ?: "deck").replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = java.io.File(dir, "$safe.csv")
        file.writeText(DeckIo.writeCsv(rows))
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
    shareRequestsFlow.emit(uri)
}

internal fun FlashcardsViewModel.stripTextImpl(md: String): String =
    Regex("<[^>]+>").replace(md, "").replace("\n", " ").trim()
