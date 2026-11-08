package com.vayunmathur.games.sudoku.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SudokuDataStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun answerModeRoundTrips() = runBlocking {
        val store = SudokuDataStore(context)
        store.setAnswerMode(false)
        assertFalse(store.answerMode.first(), "explicit off must read back off")

        store.setAnswerMode(true)
        assertTrue(SudokuDataStore(context).answerMode.first(), "on must persist across instances")

        store.setAnswerMode(false)
        assertFalse(store.answerMode.first())
    }

    @Test
    fun highlightMistakesRoundTrips() = runBlocking {
        val store = SudokuDataStore(context)
        store.setHighlightMistakes(false)
        assertFalse(store.highlightMistakes.first(), "explicit off must read back off")

        store.setHighlightMistakes(true)
        assertTrue(
            SudokuDataStore(context).highlightMistakes.first(),
            "on must persist across instances",
        )

        store.setHighlightMistakes(false)
        assertFalse(store.highlightMistakes.first())
    }
}
