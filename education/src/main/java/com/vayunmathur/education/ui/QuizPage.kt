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

/** Binds [EducationViewModel] and the back stack to the stateless [QuizScreen]. */
@Composable
fun QuizPage(backStack: NavBackStack<Route>, viewModel: EducationViewModel, exerciseId: String) {
    val content = viewModel.content
    val exercise = content.exercise(exerciseId)
    val questions = remember(exerciseId) {
        exercise?.let { content.questionsFor(it) } ?: emptyList()
    }

    QuizScreen(
        state = QuizUiState(
            title = exercise?.title?.ifBlank { stringResource(R.string.practice) }
                ?: stringResource(R.string.practice),
            questions = questions,
        ),
        actions = object : QuizActions {
            override fun navigateUp() {
                backStack.pop()
            }

            override fun finish(questions: List<Question>, answers: Map<String, Answer?>) {
                val result = viewModel.grade(questions, answers)
                viewModel.commitResult(result)
                backStack.setLast(Route.Results(result.total, result.correct, result.stars))
            }
        },
    )
}


@Composable
internal fun FeedbackCard(correct: Boolean, explanation: String?) {
    val container =
        if (correct) MaterialTheme.colorScheme.tertiaryContainer
        else MaterialTheme.colorScheme.errorContainer
    val onContainer =
        if (correct) MaterialTheme.colorScheme.onTertiaryContainer
        else MaterialTheme.colorScheme.onErrorContainer
    Card(colors = CardDefaults.cardColors(containerColor = container), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (correct) stringResource(R.string.correct_1) else stringResource(R.string.not_quite),
                style = MaterialTheme.typography.titleMedium,
                color = onContainer,
            )
            if (!explanation.isNullOrBlank()) {
                Text(explanation, style = MaterialTheme.typography.bodyMedium, color = onContainer)
            }
        }
    }
}

internal fun mutableStateMapOfAnswers() = mutableStateMapOf<String, Answer?>()
