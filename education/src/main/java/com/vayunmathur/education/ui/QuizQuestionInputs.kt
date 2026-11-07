package com.vayunmathur.education.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.education.R
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
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconKeyboardArrowDown
import com.vayunmathur.library.ui.IconKeyboardArrowUp
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedCard
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.RadioButton
import com.vayunmathur.library.ui.Text

/**
 * Renders the appropriate input for [question], reporting answers via [onAnswer].
 *
 * [answer] is what has already been recorded for this question; an input uses it to seed
 * its own selection state so a question that is being re-shown — or a preview of a quiz
 * mid-attempt — comes up with the learner's choice still highlighted.
 */
@Composable
internal fun QuestionInput(question: Question, answer: Answer?, enabled: Boolean, onAnswer: (Answer?) -> Unit) {
    when (question) {
        is MultipleChoiceQuestion -> MultipleChoiceInput(question, answer, enabled, onAnswer)
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
    answer: Answer?,
    enabled: Boolean,
    onAnswer: (Answer?) -> Unit,
) {
    var selected by remember { mutableIntStateOf((answer as? ChoiceAnswer)?.index ?: -1) }
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
        label = { Text(question.unit?.let { stringResource(R.string.answer_1, it) } ?: stringResource(R.string.answer)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
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
        label = { Text(stringResource(R.string.answer)) },
    )
}

/**
 * Initial display order for an ordering question: a shuffled permutation of
 * the item indices that is never the correct (identity) order, so a question
 * cannot start out already solved and score a free correct answer.
 */
internal fun shuffledOrdering(size: Int): List<Int> {
    val identity = (0 until size).toList()
    if (size <= 1) return identity
    var result = identity.shuffled()
    while (result == identity) result = identity.shuffled()
    return result
}

@Composable
private fun OrderingInput(question: OrderingQuestion, enabled: Boolean, onAnswer: (Answer?) -> Unit) {
    // Display order holds ORIGINAL item indices; start shuffled (stable per question).
    val order = remember { shuffledOrdering(question.items.size).toMutableStateList() }

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
                    ) { IconKeyboardArrowUp() }
                    IconButton(
                        enabled = enabled && pos < order.lastIndex,
                        onClick = {
                            val tmp = order[pos + 1]; order[pos + 1] = order[pos]; order[pos] = tmp
                            publish()
                        },
                    ) { IconKeyboardArrowDown() }
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
            Text(if (selectedIndex >= 0) options[selectedIndex] else stringResource(R.string.choose))
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
