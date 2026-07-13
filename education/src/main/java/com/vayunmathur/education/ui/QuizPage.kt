package com.vayunmathur.education.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.vayunmathur.education.content.Answer
import com.vayunmathur.education.content.ChoiceAnswer
import com.vayunmathur.education.content.MatchingAnswer
import com.vayunmathur.education.content.MatchingQuestion
import com.vayunmathur.education.content.MultiChoiceAnswer
import com.vayunmathur.education.content.MultipleChoiceQuestion
import com.vayunmathur.education.content.MultipleSelectQuestion
import com.vayunmathur.education.content.NumericAnswer
import com.vayunmathur.education.content.NumericQuestion
import com.vayunmathur.education.content.OrderingAnswer
import com.vayunmathur.education.content.OrderingQuestion
import com.vayunmathur.education.content.Question
import com.vayunmathur.education.content.ShortTextQuestion
import com.vayunmathur.education.content.TextAnswer
import com.vayunmathur.education.content.TraceAnswer
import com.vayunmathur.education.content.TracingQuestion
import com.vayunmathur.education.content.isCorrect
import com.vayunmathur.education.util.EducationViewModel
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.NavBackStack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuizPage(backStack: NavBackStack<Route>, viewModel: EducationViewModel, exerciseId: String) {
    val content = viewModel.content
    val exercise = content.exercise(exerciseId)
    val questions = remember(exerciseId) {
        exercise?.let { content.questionsFor(it) } ?: emptyList()
    }

    val answers = remember(exerciseId) { mutableStateMapOfAnswers() }
    var index by remember(exerciseId) { mutableIntStateOf(0) }
    var checked by remember(exerciseId) { mutableStateOf(false) }
    var hintsShown by remember(exerciseId) { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(exercise?.title?.ifBlank { "Practice" } ?: "Practice") },
                navigationIcon = { IconNavigation(backStack) },
            )
        },
    ) { padding ->
        if (exercise == null || questions.isEmpty()) {
            MissingContent(padding, "This exercise has no questions yet.")
            return@Scaffold
        }

        val question = questions[index]
        val currentAnswer = answers[question.id]

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LinearProgressIndicator(
                progress = { (index + 1f) / questions.size },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Question ${index + 1} of ${questions.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(question.prompt.text, style = MaterialTheme.typography.headlineSmall)

            key(question.id) {
                QuestionInput(
                    question = question,
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
                    TextButton(onClick = { hintsShown++ }) { Text("Show a hint") }
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
                ) { Text("Check") }
            } else if (index < questions.lastIndex) {
                Button(
                    onClick = {
                        index++
                        checked = false
                        hintsShown = 0
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Next") }
            } else {
                Button(
                    onClick = {
                        val result = viewModel.grade(questions, answers)
                        viewModel.commitResult(result)
                        backStack.setLast(Route.Results(result.total, result.correct, result.stars))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Finish") }
            }
        }
    }
}

@Composable
private fun FeedbackCard(correct: Boolean, explanation: String?) {
    val container =
        if (correct) MaterialTheme.colorScheme.tertiaryContainer
        else MaterialTheme.colorScheme.errorContainer
    val onContainer =
        if (correct) MaterialTheme.colorScheme.onTertiaryContainer
        else MaterialTheme.colorScheme.onErrorContainer
    Card(colors = CardDefaults.cardColors(containerColor = container), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (correct) "Correct!" else "Not quite.",
                style = MaterialTheme.typography.titleMedium,
                color = onContainer,
            )
            if (!explanation.isNullOrBlank()) {
                Text(explanation, style = MaterialTheme.typography.bodyMedium, color = onContainer)
            }
        }
    }
}

/** Renders the appropriate input for [question], reporting answers via [onAnswer]. */
@Composable
private fun QuestionInput(question: Question, enabled: Boolean, onAnswer: (Answer?) -> Unit) {
    when (question) {
        is MultipleChoiceQuestion -> MultipleChoiceInput(question, enabled, onAnswer)
        is MultipleSelectQuestion -> MultipleSelectInput(question, enabled, onAnswer)
        is NumericQuestion -> NumericInput(question, enabled, onAnswer)
        is ShortTextQuestion -> ShortTextInput(enabled, onAnswer)
        is OrderingQuestion -> OrderingInput(question, enabled, onAnswer)
        is MatchingQuestion -> MatchingInput(question, enabled, onAnswer)
        is TracingQuestion -> TracingCanvas(
            glyph = question.glyph,
            enabled = enabled,
            onTraced = { onAnswer(TraceAnswer) },
            modifier = Modifier.fillMaxWidth().height(240.dp),
        )
    }
}

@Composable
private fun MultipleChoiceInput(
    question: MultipleChoiceQuestion,
    enabled: Boolean,
    onAnswer: (Answer?) -> Unit,
) {
    var selected by remember { mutableIntStateOf(-1) }
    Column {
        question.choices.forEachIndexed { i, choice ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selected == i,
                        enabled = enabled,
                        onClick = { selected = i; onAnswer(ChoiceAnswer(i)) },
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == i, onClick = null, enabled = enabled)
                Text(choice.text, Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun MultipleSelectInput(
    question: MultipleSelectQuestion,
    enabled: Boolean,
    onAnswer: (Answer?) -> Unit,
) {
    val selected = remember { mutableStateListOf<Int>() }
    Column {
        question.choices.forEachIndexed { i, choice ->
            val isChecked = i in selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = isChecked,
                        enabled = enabled,
                        onValueChange = {
                            if (it) selected.add(i) else selected.remove(i)
                            onAnswer(if (selected.isEmpty()) null else MultiChoiceAnswer(selected.toSet()))
                        },
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = isChecked, onCheckedChange = null, enabled = enabled)
                Text(choice.text, Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun NumericInput(question: NumericQuestion, enabled: Boolean, onAnswer: (Answer?) -> Unit) {
    var text by remember { mutableStateOf("") }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onAnswer(it.toDoubleOrNull()?.let { v -> NumericAnswer(v) })
        },
        enabled = enabled,
        singleLine = true,
        label = { Text(question.unit?.let { "Answer ($it)" } ?: "Answer") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun ShortTextInput(enabled: Boolean, onAnswer: (Answer?) -> Unit) {
    var text by remember { mutableStateOf("") }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onAnswer(if (it.isBlank()) null else TextAnswer(it))
        },
        enabled = enabled,
        singleLine = true,
        label = { Text("Answer") },
    )
}

@Composable
private fun OrderingInput(question: OrderingQuestion, enabled: Boolean, onAnswer: (Answer?) -> Unit) {
    // Display order holds ORIGINAL item indices; start shuffled (stable per question).
    val order = remember { question.items.indices.shuffled().toMutableStateList() }

    fun publish() = onAnswer(OrderingAnswer(order.toList()))
    // Publish the initial arrangement so "Check" is enabled.
    LaunchedEffect(Unit) { publish() }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        order.forEachIndexed { pos, itemIndex ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(question.items[itemIndex], Modifier.weight(1f))
                    IconButton(
                        enabled = enabled && pos > 0,
                        onClick = {
                            val tmp = order[pos - 1]; order[pos - 1] = order[pos]; order[pos] = tmp
                            publish()
                        },
                    ) { Icon(Icons.Filled.KeyboardArrowUp, "Move up") }
                    IconButton(
                        enabled = enabled && pos < order.lastIndex,
                        onClick = {
                            val tmp = order[pos + 1]; order[pos + 1] = order[pos]; order[pos] = tmp
                            publish()
                        },
                    ) { Icon(Icons.Filled.KeyboardArrowDown, "Move down") }
                }
            }
        }
    }
}

@Composable
private fun MatchingInput(question: MatchingQuestion, enabled: Boolean, onAnswer: (Answer?) -> Unit) {
    val chosen = remember { List(question.left.size) { -1 }.toMutableStateList() }

    fun publish() {
        onAnswer(if (chosen.any { it < 0 }) null else MatchingAnswer(chosen.toList()))
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        question.left.forEachIndexed { i, leftText ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(leftText, Modifier.weight(1f))
                RightSelector(
                    options = question.right,
                    selectedIndex = chosen[i],
                    enabled = enabled,
                    onSelect = { chosen[i] = it; publish() },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun RightSelector(
    options: List<String>,
    selectedIndex: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (selectedIndex >= 0) options[selectedIndex] else "Choose…")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { i, opt ->
                DropdownMenuItem(
                    text = { Text(opt) },
                    onClick = { onSelect(i); expanded = false },
                )
            }
        }
    }
}

private fun mutableStateMapOfAnswers() = mutableStateMapOf<String, Answer?>()
