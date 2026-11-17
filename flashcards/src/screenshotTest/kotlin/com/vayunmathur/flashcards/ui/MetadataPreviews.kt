package com.vayunmathur.flashcards.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.flashcards.data.FIELD_SEPARATOR
import com.vayunmathur.flashcards.data.Deck
import com.vayunmathur.flashcards.data.Note
import com.vayunmathur.flashcards.ui.DailyStat
import com.vayunmathur.flashcards.ui.DeckListActions
import com.vayunmathur.flashcards.ui.DeckListUiState
import com.vayunmathur.flashcards.ui.DeckOption
import com.vayunmathur.flashcards.ui.DeckSummary
import com.vayunmathur.flashcards.domain.Grade
import com.vayunmathur.flashcards.ui.NoteEditActions
import com.vayunmathur.flashcards.ui.NoteEditUiState
import com.vayunmathur.flashcards.ui.NoteListActions
import com.vayunmathur.flashcards.ui.NoteListUiState
import com.vayunmathur.flashcards.ui.NoteRow
import com.vayunmathur.flashcards.ui.NoteTypeConfig
import com.vayunmathur.flashcards.ui.ReviewActions
import com.vayunmathur.flashcards.ui.ReviewUiState
import com.vayunmathur.flashcards.ui.StatsActions
import com.vayunmathur.flashcards.ui.StatsUiState
import com.vayunmathur.library.ui.DynamicTheme
import java.time.LocalDate

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/** Expanded desktop/tablet window — comfortably above the 840dp expanded-width threshold. */
private const val EXPANDED = "spec:width=1280dp,height=800dp,dpi=240"

/**
 * Store listing images for `:flashcards`. See `common-conventions-preview-metadata`.
 *
 * Each preview needs @PreviewTest as well as @Preview: @Preview alone renders in Studio but
 * is not collected as a screenshot test. Previews must also be class members, not top-level
 * functions. Order comes from the function names (Preview1…, Preview2…).
 *
 * Everything here is a literal — no ViewModel, no database, no device — which is also what
 * makes the images reproducible from a clean checkout.
 */
class MetadataPreviews {

    private val deckSamples = listOf(
        DeckSummary(Deck(id = 1, name = "Spanish Vocabulary", position = 0.0), dueCount = 12, newCount = 8, totalCount = 84, mastery = 0.62f),
        DeckSummary(Deck(id = 2, name = "World Capitals", position = 1.0), dueCount = 5, newCount = 0, totalCount = 50, mastery = 0.80f),
        DeckSummary(Deck(id = 3, name = "Kotlin Idioms", position = 2.0), dueCount = 0, newCount = 0, totalCount = 30, mastery = 1.0f),
        DeckSummary(Deck(id = 4, name = "Anatomy 101", position = 3.0), dueCount = 23, newCount = 15, totalCount = 120, mastery = 0.35f),
        DeckSummary(Deck(id = 5, name = "Guitar Chords", position = 4.0), dueCount = 3, newCount = 2, totalCount = 18, mastery = 0.55f),
    )

    private fun note(id: Long, front: String, back: String, cards: Int = 1) = NoteRow(
        note = Note(
            id = id,
            noteTypeId = 1,
            deckId = 1,
            guid = "g$id",
            flds = "$front$FIELD_SEPARATOR$back",
            sortField = front,
            position = id.toDouble(),
        ),
        cardCount = cards,
    )

    private val noteSamples = listOf(
        note(1, "la **manzana**", "the apple"),
        note(2, "el perro", "the dog", cards = 2),
        note(3, "la biblioteca", "the library"),
        note(4, "el aeropuerto", "the airport"),
        note(5, "la playa", "the beach"),
    )

    @PreviewTest
    @Preview(name = "1-decks", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1Decks() {
        DynamicTheme(darkTheme = true) {
            DeckListScreen(
                state = DeckListUiState(decks = deckSamples),
                actions = DeckListActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-review", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2Review() {
        DynamicTheme(darkTheme = true) {
            ReviewScreen(
                state = ReviewUiState(
                    front = "la biblioteca",
                    back = "the library",
                    remaining = 12,
                    done = false,
                    newCount = 4,
                    learningCount = 2,
                    reviewCount = 6,
                    progress = 0.4f,
                    intervalLabels = mapOf(
                        Grade.AGAIN to "1m",
                        Grade.HARD to "10m",
                        Grade.GOOD to "4d",
                        Grade.EASY to "9d",
                    ),
                ),
                actions = ReviewActions.Noop,
                initialRevealed = true,
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-cards", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview3Cards() {
        DynamicTheme(darkTheme = true) {
            NoteListScreen(
                state = NoteListUiState(
                    deckName = "Spanish Vocabulary",
                    notes = noteSamples,
                    dueCount = 12,
                ),
                actions = NoteListActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "4-stats", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview4Stats() {
        val today = LocalDate.now().toEpochDay()
        val counts = listOf(6, 14, 9, 21, 3, 17, 11, 25, 8, 19, 13, 22, 7, 16)
        val daily = counts.mapIndexed { i, c -> DailyStat(today - (counts.size - 1 - i), c) }
        val forecast = (0 until 30).map { DailyStat(today + it, (it * 7 + 3) % 18) }
        val retention = listOf(
            "≤1" to 0.72f, "2–3" to 0.81f, "4–7" to 0.88f,
            "8–14" to 0.91f, "15–30" to 0.86f, "31+" to 0.79f,
        )
        DynamicTheme(darkTheme = true) {
            StatsScreen(
                state = StatsUiState(
                    deckOptions = listOf(
                        DeckOption(null, "All decks"),
                        DeckOption(1, "Spanish Vocabulary"),
                    ),
                    selectedDeckId = null,
                    daily = daily,
                    totalReviews = 191,
                    retentionPct = 91,
                    streakDays = 14,
                    matureCards = 52,
                    totalCards = 84,
                    forecast = forecast,
                    retentionBuckets = retention,
                    heatmap = daily,
                ),
                actions = StatsActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "5-note-edit", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview5NoteEdit() {
        DynamicTheme(darkTheme = true) {
            NoteEditScreen(
                state = NoteEditUiState(
                    initialNoteTypeId = 1,
                    initialDeckId = 1,
                    initialFieldValues = listOf("la biblioteca", "the library"),
                    initialTags = "spanish nouns",
                    isNew = false,
                    noteTypes = listOf(
                        NoteTypeConfig(1, "Basic", listOf("Front", "Back")),
                        NoteTypeConfig(3, "Cloze", listOf("Text", "Back Extra")),
                    ),
                    decks = listOf(DeckOption(1, "Spanish Vocabulary")),
                ),
                actions = NoteEditActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "6-expanded", device = EXPANDED, group = "large-tablet", showSystemUi = true)
    @Composable
    fun Preview6Expanded() {
        // Medium widths pair the deck list with its cards: the detail no longer covers
        // the list, so the listing captures both side by side.
        DynamicTheme(darkTheme = true) {
            Row(Modifier.fillMaxSize()) {
                androidx.compose.foundation.layout.Box(Modifier.weight(0.45f)) {
                    DeckListScreen(
                        state = DeckListUiState(decks = deckSamples),
                        actions = DeckListActions.Noop,
                    )
                }
                androidx.compose.foundation.layout.Box(Modifier.weight(0.55f)) {
                    NoteListScreen(
                        state = NoteListUiState(
                            deckName = "Spanish Vocabulary",
                            notes = noteSamples,
                            dueCount = 12,
                        ),
                        actions = NoteListActions.Noop,
                    )
                }
            }
        }
    }
}
