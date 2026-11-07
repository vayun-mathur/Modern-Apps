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
import com.vayunmathur.library.ui.appBarScrollBehavior
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
import com.vayunmathur.education.content.Answer
import com.vayunmathur.education.content.ChoiceAnswer
import com.vayunmathur.education.content.MatchingAnswer
import com.vayunmathur.education.content.MatchingQuestion
import com.vayunmathur.education.content.MultipleChoiceQuestion
import com.vayunmathur.education.content.Question
import com.vayunmathur.education.content.TraceAnswer
import com.vayunmathur.education.content.TracingQuestion
import com.vayunmathur.education.util.EducationViewModel
import com.vayunmathur.education.util.LocalNarrator
import com.vayunmathur.education.util.QuizActions
import com.vayunmathur.education.util.QuizUiState
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

/**
 * K-2 quiz: audio-first, no-typing. Supports tap-the-tile multiple choice,
 * finger-tracing, and drag-and-drop matching. Correct actions celebrate and
 * advance; mistakes get a gentle "try again" with no penalty and no score.
 *
 * No dependency on the ViewModel or the back stack so it can be rendered from a `@Preview`
 * — see `src/screenshotTest`, which is where the store listing images come from. The
 * narrator is a composition local and is simply absent there, which makes every narrate
 * call a no-op.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun K2QuizScreen(state: QuizUiState, actions: QuizActions) {
    val narrator = LocalNarrator.current
    val questions = state.questions

    val answers = remember(questions) { mutableStateMapOf<String, Answer?>() }
    var index by remember(questions) { mutableIntStateOf(0) }
    var celebrating by remember(questions) { mutableStateOf(false) }
    var wrongIndex by remember(questions) { mutableIntStateOf(-1) }

    AppScaffold(
        title = "",
        onNavigateBack = { actions.navigateUp() },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        if (questions.isEmpty()) {
            MissingContent(padding, stringResource(R.string.let_s_go_back))
            return@AppScaffold
        }

        val question = questions[index]

        LaunchedEffect(question.id) {
            wrongIndex = -1
            narrator?.narrate(question.prompt.text, question.prompt.audioRef)
        }

        LaunchedEffect(celebrating) {
            if (celebrating) {
                delay(1400)
                celebrating = false
                if (index < questions.lastIndex) {
                    index++
                } else {
                    actions.finish(questions, answers)
                }
            }
        }

        fun complete(answer: Answer) {
            answers[question.id] = answer
            narrator?.speak(praise())
            celebrating = true
        }

        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        question.prompt.text,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { narrator?.narrate(question.prompt.text, question.prompt.audioRef) }) {
                        Text("🔊", fontSize = 28.sp)
                    }
                }

                // Per-question identity (as in the Scholar QuizScreen): the
                // input composables keep interaction state that must not leak
                // into the next question, or it can neither render fresh nor
                // ever complete.
                key(question.id) {
                when (question) {
                    is MultipleChoiceQuestion -> K2Choices(
                        question = question,
                        wrongIndex = wrongIndex,
                        enabled = !celebrating,
                        onChoose = { i ->
                            narrator?.speak(question.choices[i].text)
                            if (i == question.correctIndex) complete(ChoiceAnswer(i)) else wrongIndex = i
                        },
                    )
                    is TracingQuestion -> TracingCanvas(
                        glyph = question.glyph,
                        enabled = !celebrating,
                        onTraced = { complete(TraceAnswer) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp),
                    )
                    is MatchingQuestion -> K2DragMatch(
                        question = question,
                        enabled = !celebrating,
                        onSolved = { complete(MatchingAnswer(question.correctRightForLeft)) },
                    )
                    else -> {}
                }
                }
            }

            FadeVisibility(visible = celebrating) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.92f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🎉", fontSize = 96.sp)
                        Text(
                            stringResource(R.string.great_job),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun K2Choices(
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

private fun praise(): String = listOf("Great job!", "Awesome!", "You got it!", "Well done!").random()
