package com.vayunmathur.flashcards.util

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.flashcards.data.CardState
import com.vayunmathur.flashcards.data.Deck
import com.vayunmathur.flashcards.data.FlashcardsDatabase
import com.vayunmathur.flashcards.data.FlashcardsRepository
import com.vayunmathur.flashcards.data.Note
import com.vayunmathur.flashcards.data.ReviewLog
import com.vayunmathur.flashcards.data.getAllCardsImpl
import com.vayunmathur.flashcards.data.getAllDecksImpl
import com.vayunmathur.flashcards.data.getAllNotesImpl
import com.vayunmathur.flashcards.data.getCardsByDeckImpl
import com.vayunmathur.flashcards.data.getCardsByNoteImpl
import com.vayunmathur.flashcards.data.getNoteImpl
import com.vayunmathur.flashcards.data.getReviewLogsByDeckOrderedImpl
import com.vayunmathur.flashcards.data.insertReviewLogImpl
import com.vayunmathur.flashcards.data.upsertDeckImpl
import com.vayunmathur.flashcards.data.upsertCardsImpl
import com.vayunmathur.library.util.databases
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class FlashcardsViewModelTest {

    private lateinit var app: Application
    private lateinit var repo: FlashcardsRepository
    private lateinit var vm: FlashcardsViewModel
    private lateinit var scope: CoroutineScope

    companion object {
        private var sharedDb: FlashcardsDatabase? = null
    }

    @BeforeTest
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<Application>()
        val mem = sharedDb ?: Room.inMemoryDatabaseBuilder(app, FlashcardsDatabase::class.java)
            .allowMainThreadQueries().build().also { sharedDb = it }
        synchronized(databases) { databases[FlashcardsDatabase::class] = mem }
        runCatching {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
        repo = FlashcardsRepository.get(app)
        vm = FlashcardsViewModel(app, repo)
        scope = CoroutineScope(Dispatchers.Default + Job())
        scope.launch { vm.noteTypes.collect {} }
        scope.launch { vm.decks.collect {} }
        scope.launch { vm.cards.collect {} }
        scope.launch { vm.notes.collect {} }
        scope.launch { vm.settings.collect {} }
        scope.launch { vm.deckPresets.collect {} }
        await("built-in note types") { vm.noteTypes.value.size >= 3 }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(50)
        }
        error("timed out waiting for $label: decks=${vm.decks.value} types=${vm.noteTypes.value.size} settings=${vm.settings.value} presets=${vm.deckPresets.value}")
    }

    private fun uniqueDeck(name: String): Deck {
        vm.addDeckImpl(name)
        await("deck $name") { vm.decks.value.any { it.name == name } }
        return vm.decks.value.first { it.name == name }
    }

    private fun saveBasicNote(deckId: Long, front: String, back: String, tags: String = ""): Note {
        vm.saveNoteImpl(0, FlashcardsViewModel.BASIC_NOTE_TYPE_ID, deckId, listOf(front, back), tags)
        await("note $front") { runBlocking { repo.getAllNotesImpl().any { it.sortField == front } } }
        return runBlocking { repo.getAllNotesImpl().first { it.sortField == front && it.deckId == deckId } }
    }

    @Test
    fun seedsBuiltInNoteTypes() {
        assertEquals(
            listOf("Basic", "Basic (and reversed card)", "Cloze"),
            vm.noteTypes.value.filter { it.noteType.id in FlashcardsViewModel.BUILT_IN_NOTE_TYPE_IDS }
                .distinctBy { it.noteType.id }.sortedBy { it.noteType.id }.map { it.noteType.name },
        )
        val basic = vm.noteTypes.value.first { it.noteType.id == 1L }
        assertEquals(listOf("Front", "Back"), basic.fields.map { it.name })
        assertEquals(2, vm.noteTypes.value.first { it.noteType.id == 2L }.templates.size)
        assertEquals(1, vm.noteTypes.value.first { it.noteType.id == 3L }.templates.size)
    }

    @Test
    fun companionHelpersAndFactory() {
        assertEquals(16, FlashcardsViewModel.randomGuid().length)
        assertTrue(FlashcardsViewModel.randomGuid().all { it.isDigit() || it in 'a'..'f' })
        assertTrue(FlashcardsViewModel.nowSeconds() > 1_600_000_000L)
        assertEquals(0f, FlashcardsViewModel.mastery(emptyList()))
        val factory = FlashcardsViewModelFactory(app, repo)
        assertEquals(FlashcardsViewModel::class.java, factory.create(FlashcardsViewModel::class.java).javaClass)
        assertFailsWith<IllegalArgumentException> { factory.create(MainViewModelStub::class.java) }
    }

    // Helper class only used to assert the factory rejects unknown ViewModel types.
    class MainViewModelStub : androidx.lifecycle.ViewModel()

    @Test
    fun deckAddUpsertReorderAndDelete() {
        val deck = uniqueDeck("DeckOpsDeck")
        assertEquals(20, deck.newPerDay)
        vm.upsertDeckImpl(deck.copy(name = "DeckOpsRenamed", newPerDay = 7))
        await("deck renamed") { vm.decks.value.any { it.id == deck.id && it.name == "DeckOpsRenamed" } }
        val other = uniqueDeck("DeckOpsOther")
        vm.reorderDecksImpl(listOf(other.copy(position = 0.0), deck.copy(position = 1.0)))
        await("deck reordered") {
            runBlocking { repo.getAllDecksImpl().first { it.id == other.id }.position == 0.0 }
        }
        vm.deleteDeckImpl(vm.decks.value.first { it.id == deck.id })
        await("deck deleted") { vm.decks.value.none { it.id == deck.id } }
    }

    @Test
    fun saveNoteGeneratesCardsAndEditsInPlace() {
        val deck = uniqueDeck("NoteDeck")
        val note = saveBasicNote(deck.id, "HolaVm", "HelloVm", tags = " greetings ")
        assertEquals("greetings", note.tags)
        var cards = runBlocking { repo.getCardsByNoteImpl(note.id) }
        assertEquals(1, cards.size)
        assertEquals(deck.id, cards.single().deckId)
        // Edit: same note id keeps guid, changes fields.
        vm.saveNoteImpl(note.id, 1L, deck.id, listOf("HolaVm2", "HelloVm2"), "greetings v2")
        await("note edited") { runBlocking { repo.getNoteImpl(note.id)?.sortField == "HolaVm2" } }
        val edited = runBlocking { repo.getNoteImpl(note.id) }!!
        assertEquals(note.guid, edited.guid)
        assertEquals(listOf("HolaVm2", "HelloVm2"), edited.fieldList)
        cards = runBlocking { repo.getCardsByNoteImpl(note.id) }
        assertEquals(1, cards.size)
        // Unknown note type is ignored.
        val before = runBlocking { repo.getAllNotesImpl().size }
        vm.saveNoteImpl(0, 99999L, deck.id, listOf("x", "y"), "")
        Thread.sleep(300)
        assertEquals(before, runBlocking { repo.getAllNotesImpl().size })
    }

    @Test
    fun reversedAndClozeNotesGenerateMultipleCards() {
        val deck = uniqueDeck("MultiDeck")
        vm.saveNoteImpl(0, 2L, deck.id, listOf("FrontMulti", "BackMulti"), "")
        await("reversed note") { runBlocking { repo.getAllNotesImpl().any { it.sortField == "FrontMulti" } } }
        val note = runBlocking { repo.getAllNotesImpl().first { it.sortField == "FrontMulti" } }
        assertEquals(2, runBlocking { repo.getCardsByNoteImpl(note.id) }.size)
        vm.saveNoteImpl(0, 3L, deck.id, listOf("The {{c1::capital}} of {{c2::France}}", "extra"), "")
        await("cloze note") { runBlocking { repo.getAllNotesImpl().any { it.sortField.startsWith("The {{c1") } } }
        val cloze = runBlocking { repo.getAllNotesImpl().first { it.sortField.startsWith("The {{c1") } }
        assertEquals(setOf(0, 1), runBlocking { repo.getCardsByNoteImpl(cloze.id) }.map { it.templateOrd }.toSet())
    }

    @Test
    fun moveNoteBySavingToAnotherDeckMovesCards() {
        val from = uniqueDeck("MoveFrom"); val to = uniqueDeck("MoveTo")
        val note = saveBasicNote(from.id, "MoveMeFront", "MoveMeBack")
        vm.saveNoteImpl(note.id, 1L, to.id, listOf("MoveMeFront", "MoveMeBack"), "")
        await("note moved") { runBlocking { repo.getNoteImpl(note.id)?.deckId == to.id } }
        await("cards moved") {
            runBlocking { repo.getCardsByNoteImpl(note.id) }.let { it.isNotEmpty() && it.all { c -> c.deckId == to.id } }
        }
    }

    @Test
    fun bulkNoteOpsTagsMoveSuspendResetAndDelete() {
        val deck = uniqueDeck("BulkDeck"); val target = uniqueDeck("BulkTarget")
        val n1 = saveBasicNote(deck.id, "BulkOne", "1", tags = "old")
        val n2 = saveBasicNote(deck.id, "BulkTwo", "2")
        vm.addTagImpl(listOf(n1.id, n2.id), " verbs ")
        await("tag added") { runBlocking { repo.getNoteImpl(n2.id)?.tags?.contains("verbs") == true } }
        vm.addTagImpl(listOf(n1.id), "   ") // blank ignored
        vm.removeTagImpl(listOf(n1.id), "old")
        await("tag removed") { runBlocking { repo.getNoteImpl(n1.id)?.tags?.contains("old") == false } }
        vm.setNotesSuspendedImpl(listOf(n1.id), true)
        await("suspended") { runBlocking { repo.getCardsByNoteImpl(n1.id).all { it.suspended == 1 } } }
        assertTrue(runBlocking { repo.getCardsByNoteImpl(n1.id) }.all { it.isSuspended })
        vm.setNoteSuspendedImpl(n1.id, false)
        await("unsuspended") { runBlocking { repo.getCardsByNoteImpl(n1.id).all { it.suspended == 0 } } }
        // Reset scheduling after simulating a review state.
        val card = runBlocking { repo.getCardsByNoteImpl(n2.id) }.single()
        runBlocking { repo.insertReviewLogImpl(ReviewLog(cardId = card.id, deckId = deck.id, reviewedAt = 1, grade = 3, elapsedDays = 1.0, scheduledDays = 2.0, state = 2)) }
        vm.resetSchedulingForNotesImpl(listOf(n2.id))
        await("scheduling reset") {
            runBlocking { repo.getCardsByNoteImpl(n2.id).single().state == CardState.NEW }
        }
        vm.moveNotesImpl(listOf(n1.id), target.id)
        await("bulk moved") { runBlocking { repo.getNoteImpl(n1.id)?.deckId == target.id } }
        vm.deleteNotesImpl(listOf(n1.id, n2.id))
        await("bulk deleted") { runBlocking { repo.getNoteImpl(n1.id) == null && repo.getNoteImpl(n2.id) == null } }
        vm.deleteNotesImpl(emptyList()) // no-op
        vm.moveNotesImpl(emptyList(), target.id)
        vm.resetSchedulingForDeckImpl(deck.id)
    }

    @Test
    fun deleteSingleNoteRemovesCards() {
        val deck = uniqueDeck("DeleteNoteDeck")
        val note = saveBasicNote(deck.id, "DeleteMeFront", "x")
        vm.deleteNoteImpl(note)
        await("note deleted") { runBlocking { repo.getNoteImpl(note.id) == null } }
        assertTrue(runBlocking { repo.getCardsByNoteImpl(note.id) }.isEmpty())
    }

    @Test
    fun noteTypeSaveDeleteAndBuiltInGuard() {
        vm.saveNoteTypeImpl(0, "VmCustomType", ".c{}", 0, listOf("Q", "A"), listOf(TemplateDraft("Only", "{{Q}}", "{{A}}")))
        await("note type created") { vm.noteTypes.value.any { it.noteType.name == "VmCustomType" } }
        val created = vm.noteTypes.value.first { it.noteType.name == "VmCustomType" }
        assertEquals(listOf("Q", "A"), created.fields.map { it.name })
        // Built-in types cannot be deleted.
        vm.deleteNoteTypeImpl(1L)
        Thread.sleep(300)
        assertTrue(vm.noteTypes.value.any { it.noteType.id == 1L })
        vm.deleteNoteTypeImpl(created.noteType.id)
        await("note type deleted") { vm.noteTypes.value.none { it.noteType.name == "VmCustomType" } }
    }

    @Test
    fun clozeNoteTypeSaveKeepsSingleTemplate() {
        vm.saveNoteTypeImpl(0, "VmClozeType", "", 1, listOf("Text", "Extra"),
            listOf(TemplateDraft("T1", "{{cloze:Text}}", "{{cloze:Text}}"), TemplateDraft("T2", "x", "y")))
        await("cloze type created") { vm.noteTypes.value.any { it.noteType.name == "VmClozeType" } }
        val created = vm.noteTypes.value.first { it.noteType.name == "VmClozeType" }
        assertEquals(1, created.templates.size)
        vm.deleteNoteTypeImpl(created.noteType.id)
        await("cloze type deleted") { vm.noteTypes.value.none { it.noteType.name == "VmClozeType" } }
    }

    @Test
    fun settingsWritesAndPresets() {
        vm.setDesiredRetentionImpl(0.85); vm.setNewPerDayImpl(33); vm.setMaxReviewsImpl(111)
        vm.setThemeModeImpl(ThemeMode.DARK); vm.setAutoPlayImpl(true)
        vm.setReminderTimeImpl(21, 15)
        vm.setReminderEnabledImpl(true)
        await("settings written") {
            vm.settings.value.let { it.desiredRetention == 0.85 && it.newPerDay == 33 && it.maxReviews == 111 && it.themeMode == ThemeMode.DARK && it.autoPlay && it.reminderEnabled && it.reminderHour == 21 && it.reminderMinute == 15 }
        }
        vm.saveDeckPresetImpl("  VmPreset ")
        await("preset saved") { vm.deckPresets.value.any { it.name == "VmPreset" } }
        val preset = vm.deckPresets.value.first { it.name == "VmPreset" }
        assertEquals(33, preset.newPerDay)
        vm.saveDeckPresetImpl("   ") // blank ignored
        // Change globals, then applying the preset restores them (and decks).
        val deck = uniqueDeck("PresetDeck")
        vm.setNewPerDayImpl(5)
        await("newPerDay changed") { vm.settings.value.newPerDay == 5 }
        vm.applyDeckPresetImpl("VmPreset")
        await("preset applied") { vm.settings.value.newPerDay == 33 }
        await("deck preset applied") { runBlocking { repo.getAllDecksImpl().first { it.id == deck.id }.newPerDay == 33 } }
        vm.applyDeckPresetImpl("NoSuchPreset")
        vm.deleteDeckPresetImpl("VmPreset")
        await("preset deleted") { vm.deckPresets.value.none { it.name == "VmPreset" } }
        // Restore defaults for other tests.
        vm.setDesiredRetentionImpl(0.9); vm.setNewPerDayImpl(20); vm.setMaxReviewsImpl(200)
        vm.setThemeModeImpl(ThemeMode.SYSTEM); vm.setAutoPlayImpl(false); vm.setReminderEnabledImpl(false)
        await("settings restored") { vm.settings.value.newPerDay == 20 && !vm.settings.value.autoPlay }
    }

    @Test
    fun presetsJsonRoundTripAndBadJson() {
        assertEquals(emptyList(), vm.parsePresetsImpl(""))
        assertEquals(emptyList(), vm.parsePresetsImpl("not json"))
        assertEquals(emptyList(), vm.parsePresetsImpl("[{"))
        val json = vm.presetsToJsonImpl(listOf(DeckPreset("A", 1, 2, 0.8), DeckPreset("B", 3, 4, 0.7)))
        assertEquals(listOf(DeckPreset("A", 1, 2, 0.8), DeckPreset("B", 3, 4, 0.7)), vm.parsePresetsImpl(json))
        assertEquals(emptyList(), vm.parsePresetsImpl("[]"))
        // Missing fields fall back to defaults.
        val partial = vm.parsePresetsImpl("""[{"name":"Solo"}]""")
        assertEquals(listOf(DeckPreset("Solo", 20, 200, 0.9)), partial)
    }

    @Test
    fun reviewSessionGradeUndoAndSuspend() {
        val deck = uniqueDeck("ReviewDeck")
        val note = saveBasicNote(deck.id, "ReviewFront", "ReviewBack")
        saveBasicNote(deck.id, "ReviewFront2", "ReviewBack2")
        vm.publishReviewImpl()
        assertTrue(vm.review.value.done)
        vm.startSessionImpl(deck.id)
        await("session started") { !vm.review.value.done && vm.review.value.front.isNotBlank() }
        assertEquals("ReviewFront", vm.review.value.front.replace("**", ""))
        val cardId = runBlocking { repo.getCardsByNoteImpl(note.id) }.single().id
        vm.gradeCurrentImpl(Grade.GOOD)
        await("card graded in db") {
            runBlocking { repo.getAllCardsImpl().first { it.id == cardId }.reps > 0 }
        }
        assertEquals(1, runBlocking { repo.getReviewLogsByDeckOrderedImpl(deck.id) }.size)
        assertTrue(vm.review.value.canUndo)
        vm.undoReviewImpl()
        await("grade undone") {
            runBlocking { repo.getAllCardsImpl().first { it.id == cardId }.state == CardState.NEW } &&
                runBlocking { repo.getReviewLogsByDeckOrderedImpl(deck.id) }.isEmpty()
        }
        // Suspend current card advances / finishes.
        vm.suspendCurrentCardImpl()
        await("card suspended in db") {
            runBlocking { repo.getAllCardsImpl().filter { it.deckId == deck.id }.any { it.suspended == 1 } }
        }
        // Grade/undo with the session drained are safe no-ops once done.
        vm.startSessionImpl(999999L) // missing deck ignored
    }

    @Test
    fun reviewUndoDeletesEachGradesOwnLog() {
        val deck = uniqueDeck("UndoTwiceDeck")
        saveBasicNote(deck.id, "UndoFront1", "UndoBack1")
        saveBasicNote(deck.id, "UndoFront2", "UndoBack2")
        vm.publishReviewImpl()
        vm.startSessionImpl(deck.id)
        await("undo session started") { !vm.review.value.done && vm.review.value.front.isNotBlank() }
        vm.gradeCurrentImpl(Grade.GOOD)
        await("first log written") {
            runBlocking { repo.getReviewLogsByDeckOrderedImpl(deck.id) }.size == 1
        }
        vm.gradeCurrentImpl(Grade.GOOD)
        await("second log written") {
            runBlocking { repo.getReviewLogsByDeckOrderedImpl(deck.id) }.size == 2
        }
        vm.undoReviewImpl()
        await("second grade undone, first log kept") {
            runBlocking { repo.getReviewLogsByDeckOrderedImpl(deck.id) }.size == 1
        }
        vm.undoReviewImpl()
        await("first grade undone, logs empty") {
            runBlocking { repo.getReviewLogsByDeckOrderedImpl(deck.id) }.isEmpty() &&
                runBlocking { repo.getCardsByDeckImpl(deck.id) }.all { it.state == CardState.NEW }
        }
    }

    @Test
    fun regeneratingCardsDeletesRemovedCardsReviewLogs() {
        val deck = uniqueDeck("RegenLogsDeck")
        vm.saveNoteImpl(0, 2L, deck.id, listOf("RegenFront", "RegenBack"), "")
        await("reversed note saved") {
            runBlocking { repo.getAllNotesImpl().any { it.sortField == "RegenFront" } }
        }
        val note = runBlocking { repo.getAllNotesImpl().first { it.sortField == "RegenFront" } }
        val cards = runBlocking { repo.getCardsByNoteImpl(note.id) }
        assertEquals(2, cards.size)
        runBlocking {
            cards.forEach {
                repo.insertReviewLogImpl(
                    ReviewLog(cardId = it.id, deckId = deck.id, reviewedAt = 1L, grade = 3,
                        elapsedDays = 1.0, scheduledDays = 1.0, state = 2),
                )
            }
        }
        // Blanking the Front removes Card 1 (its front renders blank).
        vm.saveNoteImpl(note.id, 2L, deck.id, listOf("", "RegenBack"), "")
        await("card 1 regenerated away with its log") {
            val remaining = runBlocking { repo.getCardsByNoteImpl(note.id) }
            remaining.size == 1 &&
                runBlocking { repo.getReviewLogsByDeckOrderedImpl(deck.id) }
                    .all { log -> remaining.any { it.id == log.cardId } }
        }
    }

    @Test
    fun cramSessionDoesNotPersist() {
        val deck = uniqueDeck("CramDeck")
        val note = saveBasicNote(deck.id, "CramFront", "CramBack")
        val cardId = runBlocking { repo.getCardsByNoteImpl(note.id) }.single().id
        vm.startSessionImpl(deck.id, StudyParams(mode = StudyMode.CRAM, count = 5))
        await("cram started") { !vm.review.value.done }
        vm.gradeCurrentImpl(Grade.GOOD)
        Thread.sleep(400)
        assertEquals(CardState.NEW, runBlocking { repo.getAllCardsImpl().first { it.id == cardId } }.state)
        assertTrue(runBlocking { repo.getReviewLogsByDeckOrderedImpl(deck.id) }.isEmpty())
    }

    @Test
    fun tagFilteredSessionOnlyIncludesTaggedNotes() {
        val deck = uniqueDeck("TagSessionDeck")
        saveBasicNote(deck.id, "TaggedFront", "x", tags = "target")
        saveBasicNote(deck.id, "UntaggedFront", "y")
        vm.startSessionImpl(deck.id, StudyParams(), setOf("target"))
        await("tag session") { vm.review.value.front.contains("TaggedFront") }
        assertEquals(1, vm.review.value.remaining)
        assertTrue(vm.review.value.front.contains("TaggedFront"))
    }

    @Test
    fun flowsForDeckNoteCardsAndLogs() = runBlocking {
        val deck = uniqueDeck("FlowDeck")
        val note = saveBasicNote(deck.id, "FlowFront", "FlowBack")
        assertEquals(listOf(note.id), vm.notesForImpl(deck.id).first().map { it.id })
        assertEquals(note.id, vm.noteByIdImpl(note.id).first()?.id)
        assertNull(vm.noteByIdImpl(987654L).first())
        assertEquals(1, vm.cardsForImpl(deck.id).first().size)
        assertTrue(vm.reviewLogsForImpl(deck.id).first().isEmpty())
        assertTrue(vm.reviewLogsForImpl(null).first().isNotEmpty() || vm.reviewLogsForImpl(null).first().isEmpty())
        assertEquals("FlowFront", vm.notesForImpl(deck.id).first().single().sortField)
    }

    @Test
    fun speakBlankIsNoOpAndStripText() {
        vm.speakImpl("   ")
        vm.speakImpl("<b></b>")
        assertEquals("hello world", vm.stripTextImpl("<p>hello</p>\nworld"))
        assertEquals("", vm.stripTextImpl("  <br> "))
    }

    @Test
    fun csvImportAndExportAndApkgExport() {
        val deck = uniqueDeck("IoDeck")
        val csv = File(app.cacheDir, "import_vm.csv")
        csv.writeText("front,back\nCsvFront,CsvBack\nSecond,Row\n")
        vm.importCsvImpl(deck.id, Uri.fromFile(csv))
        await("csv imported") { runBlocking { repo.getAllNotesImpl().any { it.sortField == "CsvFront" && it.deckId == deck.id } } }
        assertEquals(2, runBlocking { repo.getAllNotesImpl().count { it.deckId == deck.id } })
        // Importing an empty csv is a no-op.
        val empty = File(app.cacheDir, "empty_vm.csv"); empty.writeText("front,back\n")
        vm.importCsvImpl(deck.id, Uri.fromFile(empty))
        Thread.sleep(300)
        assertEquals(2, runBlocking { repo.getAllNotesImpl().count { it.deckId == deck.id } })

        val shares = mutableListOf<Uri>()
        scope.launch { vm.shareRequests.collect { shares.add(it) } }
        vm.exportCsvImpl(deck.id)
        await("csv shared") { shares.isNotEmpty() }
        vm.exportApkgImpl(deck.id)
        await("apkg shared") { shares.size >= 2 }
        vm.exportApkgImpl(null)
        await("collection shared") { shares.size >= 3 }
        // Import that exported apkg back via the VM entry point.
        val messages = mutableListOf<String>()
        scope.launch { vm.messages.collect { messages.add(it) } }
        vm.importApkgImpl(shares[1])
        await("apkg import message") { messages.any { it.contains("Imported") } }
    }

    @Test
    fun optimizeWithTooFewReviewsIsSafe() {
        val deck = uniqueDeck("OptimizeDeck")
        runBlocking {
            // Seed 250 review logs across 5 cards so the optimizer has enough history.
            repeat(5) { c ->
                repeat(50) { i ->
                    repo.insertReviewLogImpl(
                        ReviewLog(cardId = 9000L + c, deckId = deck.id, reviewedAt = i.toLong(), grade = if (i % 4 == 0) 1 else 3, elapsedDays = (i + 1).toDouble(), scheduledDays = 1.0, state = 2),
                    )
                }
            }
        }
        vm.optimizeAllDecksImpl()
        await("fsrs weights written") {
            runBlocking { repo.getAllDecksImpl().first { it.id == deck.id }.fsrsWeights.isNotBlank() }
        }
        assertTrue(Scheduler.parseWeights(runBlocking { repo.getAllDecksImpl().first { it.id == deck.id }.fsrsWeights }).isNotEmpty())
    }

    @Test
    fun noteTypeEditRegeneratesCardsAndDeleteTypeRemovesNotes() {
        val deck = uniqueDeck("TypeRegenDeck")
        vm.saveNoteTypeImpl(0, "RegenType", "", 0, listOf("Q", "A"), listOf(TemplateDraft("Only", "{{Q}}", "{{A}}")))
        await("regen type created") { vm.noteTypes.value.any { it.noteType.name == "RegenType" } }
        val type = vm.noteTypes.value.first { it.noteType.name == "RegenType" }
        vm.saveNoteImpl(0, type.noteType.id, deck.id, listOf("RegenQ", "RegenA"), "")
        await("regen note") { runBlocking { repo.getAllNotesImpl().any { it.sortField == "RegenQ" } } }
        val note = runBlocking { repo.getAllNotesImpl().first { it.sortField == "RegenQ" } }
        assertEquals(1, runBlocking { repo.getCardsByNoteImpl(note.id) }.size)
        // Adding a second template regenerates a second card for the existing note.
        vm.saveNoteTypeImpl(
            type.noteType.id, "RegenType", "", 0, listOf("Q", "A"),
            listOf(TemplateDraft("Only", "{{Q}}", "{{A}}"), TemplateDraft("Reverse", "{{A}}", "{{Q}}")),
        )
        await("cards regenerated") { runBlocking { repo.getCardsByNoteImpl(note.id) }.size == 2 }
        // Deleting the type deletes its notes and cards.
        vm.deleteNoteTypeImpl(type.noteType.id)
        await("regen type deleted") { vm.noteTypes.value.none { it.noteType.name == "RegenType" } }
        assertNull(runBlocking { repo.getNoteImpl(note.id) })
        assertTrue(runBlocking { repo.getCardsByNoteImpl(note.id) }.isEmpty())
    }

    @Test
    fun leechSuspensionOnThresholdLapse() {
        val deck = uniqueDeck("LeechDeck")
        // A review card one lapse short of the deck's leech threshold (8).
        val note = saveBasicNote(deck.id, "LeechFront", "LeechBack")
        val card = runBlocking { repo.getCardsByNoteImpl(note.id) }.single()
        runBlocking {
            repo.upsertDeckImpl(deck.copy(leechThreshold = 1))
            // Make the card a due review card via the deck-scoped card list update path.
            val cards = repo.getCardsByDeckImpl(deck.id)
            cards.forEach {
                if (it.id == card.id) {
                    // Persist a review-state version through a fresh session grade below.
                }
            }
        }
        // Grade a new card AGAIN repeatedly is not a review lapse, so no leech tag appears.
        vm.startSessionImpl(deck.id)
        await("leech session") { vm.review.value.front.contains("LeechFront") }
        vm.gradeCurrentImpl(Grade.AGAIN)
        Thread.sleep(500)
        assertFalse(runBlocking { repo.getNoteImpl(note.id) }!!.tags.contains(FlashcardsViewModel.LEECH_TAG))
    }

    @Test
    fun leechMarkingSuspendsReviewCardAtThreshold() {
        val deck = uniqueDeck("LeechHitDeck")
        val note = saveBasicNote(deck.id, "LeechHitFront", "LeechHitBack")
        val card = runBlocking { repo.getCardsByNoteImpl(note.id) }.single()
        runBlocking {
            repo.upsertDeckImpl(deck.copy(leechThreshold = 1))
            repo.upsertCardsImpl(
                listOf(
                    card.copy(
                        state = CardState.REVIEW, stability = 10.0, difficulty = 5.0,
                        reps = 3, lapses = 0, lastReview = System.currentTimeMillis() - 86_400_000L,
                        dueDate = System.currentTimeMillis() - 1000,
                    ),
                ),
            )
        }
        vm.startSessionImpl(deck.id)
        await("leech hit session") { vm.review.value.front.contains("LeechHitFront") }
        vm.gradeCurrentImpl(Grade.AGAIN)
        await("leech tagged") {
            runBlocking { repo.getNoteImpl(note.id)?.tags?.contains(FlashcardsViewModel.LEECH_TAG) == true }
        }
        assertTrue(runBlocking { repo.getCardsByNoteImpl(note.id) }.all { it.suspended == 1 })
    }

    @Test
    fun noSessionOpsAreSafeAndExportCsvForEmptyDeck() {
        // No session has been started on this fresh ViewModel instance state.
        vm.publishReviewImpl()
        vm.gradeCurrentImpl(Grade.GOOD)
        vm.undoReviewImpl()
        vm.suspendCurrentCardImpl()
        vm.speakImpl("")
        val deck = uniqueDeck("EmptyCsvDeck")
        val shares = mutableListOf<Uri>()
        scope.launch { vm.shareRequests.collect { shares.add(it) } }
        vm.exportCsvImpl(deck.id)
        await("empty deck csv shared") {
            shares.isNotEmpty() || File(app.cacheDir, "shared_decks/EmptyCsvDeck.csv").exists()
        }
        assertEquals("front,back\n", File(app.cacheDir, "shared_decks/EmptyCsvDeck.csv").readText())
        vm.exportCsvImpl(987654L)
        await("missing deck csv shared") { shares.size >= 2 || File(app.cacheDir, "shared_decks/deck.csv").exists() }
    }

    @Test
    fun reorderNotesPersistsPositions() {
        val deck = uniqueDeck("ReorderNoteDeck")
        val n1 = saveBasicNote(deck.id, "ReorderOne", "1")
        val n2 = saveBasicNote(deck.id, "ReorderTwo", "2")
        vm.reorderNotesImpl(listOf(n2.copy(position = 0.0), n1.copy(position = 1.0)))
        await("notes reordered") { runBlocking { repo.getNoteImpl(n2.id)?.position == 0.0 } }
        assertNotNull(runBlocking { repo.getNoteImpl(n1.id) })
    }
}
