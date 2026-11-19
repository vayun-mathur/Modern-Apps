package com.vayunmathur.education.ui
import com.vayunmathur.education.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.FadeVisibility
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.education.Route
import com.vayunmathur.education.domain.Answer
import com.vayunmathur.education.domain.ChoiceAnswer
import com.vayunmathur.education.domain.MatchingAnswer
import com.vayunmathur.education.domain.MatchingQuestion
import com.vayunmathur.education.domain.MultipleChoiceQuestion
import com.vayunmathur.education.domain.Question
import com.vayunmathur.education.domain.TraceAnswer
import com.vayunmathur.education.domain.TracingQuestion
import com.vayunmathur.education.platform.EducationViewModel
import com.vayunmathur.education.platform.LocalNarrator
import com.vayunmathur.education.platform.QuizActions
import com.vayunmathur.education.platform.QuizUiState
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource

/** Binds [EducationViewModel] and the back stack to the stateless [K2QuizScreen]. */
@Composable
fun K2QuizPage(backStack: NavBackStack<Route>, viewModel: EducationViewModel, exerciseId: String) {
    val content = viewModel.content
    val questions = remember(exerciseId) {
        content.exercise(exerciseId)
            ?.let { content.questionsFor(it) }
            ?.filter { it is MultipleChoiceQuestion || it is TracingQuestion || it is MatchingQuestion }
            ?: emptyList()
    }

    K2QuizScreen(
        state = QuizUiState(questions = questions),
        actions = object : QuizActions {
            override fun navigateUp() {
                backStack.pop()
            }

            override fun finish(questions: List<Question>, answers: Map<String, Answer?>) {
                val result = viewModel.grade(questions, answers)
                viewModel.commitResult(result)
                backStack.setLast(Route.K2Reward(result.stars))
            }
        },
    )
}


@Composable
internal fun K2Choices(
    question: MultipleChoiceQuestion,
    wrongIndex: Int,
    enabled: Boolean,
    onChoose: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        question.choices.withIndex().toList().chunked(2).forEach { rowChoices ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                rowChoices.forEach { (i, choice) ->
                    ChoiceTile(
                        text = choice.text,
                        isWrong = wrongIndex == i,
                        modifier = Modifier.weight(1f),
                        onClick = { if (enabled) onChoose(i) },
                    )
                }
                if (rowChoices.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ChoiceTile(text: String, isWrong: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val container =
        if (isWrong) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.secondaryContainer
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, fontSize = 40.sp, textAlign = TextAlign.Center)
        }
    }
}

internal fun praise(): String = listOf("Great job!", "Awesome!", "You got it!", "Well done!").random()
