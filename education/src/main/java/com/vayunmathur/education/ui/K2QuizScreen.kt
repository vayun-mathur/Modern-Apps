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
