package com.vayunmathur.education.ui
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedCard
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.RadioButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.vayunmathur.education.Route
import com.vayunmathur.education.domain.Answer
import com.vayunmathur.education.domain.ChoiceAnswer
import com.vayunmathur.education.domain.MatchingAnswer
import com.vayunmathur.education.domain.MatchingQuestion
import com.vayunmathur.education.domain.MultiChoiceAnswer
import com.vayunmathur.education.domain.MultipleChoiceQuestion
import com.vayunmathur.education.domain.MultipleSelectQuestion
import com.vayunmathur.education.domain.NumericAnswer
import com.vayunmathur.education.domain.NumericQuestion
import com.vayunmathur.education.domain.OrderingAnswer
import com.vayunmathur.education.domain.OrderingQuestion
import com.vayunmathur.education.domain.Question
import com.vayunmathur.education.domain.ShortTextQuestion
import com.vayunmathur.education.domain.TextAnswer
import com.vayunmathur.education.domain.TraceAnswer
import com.vayunmathur.education.domain.TracingQuestion
import com.vayunmathur.education.domain.isCorrect
import com.vayunmathur.education.platform.EducationViewModel
import com.vayunmathur.education.platform.QuizActions
import com.vayunmathur.education.platform.QuizUiState
import com.vayunmathur.library.ui.IconKeyboardArrowDown
import com.vayunmathur.library.ui.IconKeyboardArrowUp
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import androidx.compose.ui.res.stringResource
import com.vayunmathur.education.R

/**
 * The Scholar quiz, with no dependency on the ViewModel or the back stack so it can be
 * rendered from a `@Preview` — see `src/screenshotTest`, which is where the store listing
 * images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuizScreen(
    state: QuizUiState,
    actions: QuizActions,
    /**
     * Seeds for the screen's own UI-only state (which question is showing, whether it has
     * been checked, what has been answered so far). The app always takes the defaults;
     * previews set them so a mid-quiz moment can be captured without driving the UI there.
     */
    initialIndex: Int = 0,
    initialChecked: Boolean = false,
    initialAnswers: Map<String, Answer?> = emptyMap(),
) {
    val questions = state.questions

    val answers = remember(questions) { mutableStateMapOfAnswers().apply { putAll(initialAnswers) } }
    var index by remember(questions) { mutableIntStateOf(initialIndex) }
    var checked by remember(questions) { mutableStateOf(initialChecked) }
    var hintsShown by remember(questions) { mutableIntStateOf(0) }

    if (questions.isEmpty()) {
        AppScaffold(
            title = state.title,
            onNavigateBack = { actions.navigateUp() },
            scrollBehavior = appBarScrollBehavior(),
        ) { padding ->
            MissingContent(padding, stringResource(R.string.this_exercise_has_no_questions_yet))
        }
        return
    }

    val question = questions[index]
    val currentAnswer = answers[question.id]

    DetailScaffold(
        title = state.title,
        onNavigateBack = { actions.navigateUp() },
        scrollBehavior = appBarScrollBehavior(),
    ) {
        LinearProgressIndicator(
            progress = { (index + 1f) / questions.size },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.question_of, index + 1, questions.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(question.prompt.text, style = MaterialTheme.typography.headlineSmall)

        key(question.id) {
            QuestionInput(
                question = question,
                answer = currentAnswer,
                enabled = !checked,
                onAnswer = { answers[question.id] = it },
            )
        }

        // Hints (progressive)
        if (!checked && question.hints.isNotEmpty()) {
            question.hints.take(hintsShown).forEach { hint ->
                Text("💡 $hint", style = MaterialTheme.typography.bodyMedium)
            }
            if (hintsShown < question.hints.size) {
                TextButton(onClick = { hintsShown++ }) { Text(stringResource(R.string.show_a_hint)) }
            }
        }

        if (checked) {
            FeedbackCard(
                correct = question.isCorrect(currentAnswer),
                explanation = question.explanation,
            )
        }

        if (!checked) {
            Button(
                onClick = { checked = true },
                enabled = currentAnswer != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.check)) }
        } else if (index < questions.lastIndex) {
            Button(
                onClick = {
                    index++
                    checked = false
                    hintsShown = 0
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.next)) }
        } else {
            Button(
                onClick = { actions.finish(questions, answers) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.finish)) }
        }
    }
}
