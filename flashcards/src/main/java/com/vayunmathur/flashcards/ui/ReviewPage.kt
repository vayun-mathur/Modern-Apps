package com.vayunmathur.flashcards.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.flashcards.R
import com.vayunmathur.flashcards.Route
import com.vayunmathur.flashcards.platform.FlashcardsViewModel
import com.vayunmathur.flashcards.platform.startSessionImpl
import com.vayunmathur.flashcards.platform.gradeCurrentImpl
import com.vayunmathur.flashcards.platform.undoReviewImpl
import com.vayunmathur.flashcards.platform.suspendCurrentCardImpl
import com.vayunmathur.flashcards.platform.speakImpl
import com.vayunmathur.flashcards.domain.Grade
import com.vayunmathur.flashcards.ui.ReviewActions
import com.vayunmathur.flashcards.ui.ReviewUiState
import com.vayunmathur.flashcards.ui.StudyMode
import com.vayunmathur.flashcards.ui.StudyParams
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ButtonDefaults
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconUndo
import com.vayunmathur.library.ui.IconVisibilityOff
import com.vayunmathur.library.ui.IconVolumeUp
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.animatedFloat
import com.vayunmathur.library.ui.Motion
import com.vayunmathur.library.util.NavBackStack
import kotlin.math.abs

/** Binds an in-memory review session over [deckId] to the stateless [ReviewScreen]. */
@Composable
fun ReviewPage(
    backStack: NavBackStack<Route>,
    viewModel: FlashcardsViewModel,
    deckId: Long,
    mode: Int = 0,
    count: Int = 20,
    daysAhead: Int = 3,
    tags: List<String> = emptyList(),
) {
    LaunchedEffect(deckId, mode, count, daysAhead, tags) {
        val params = StudyParams(
            mode = StudyMode.entries.getOrElse(mode) { StudyMode.DUE },
            count = count,
            daysAhead = daysAhead,
        )
        viewModel.startSessionImpl(deckId, params, tags.toSet())
    }
    val state by viewModel.review.collectAsStateWithLifecycle()

    ReviewScreen(
        state = state,
        actions = object : ReviewActions {
            override fun back() { backStack.pop() }
            override fun grade(grade: Grade) { viewModel.gradeCurrentImpl(grade) }
            override fun undo() { viewModel.undoReviewImpl() }
            override fun suspend() { viewModel.suspendCurrentCardImpl() }
            override fun speak(text: String) { viewModel.speakImpl(text) }
        },
    )
}
