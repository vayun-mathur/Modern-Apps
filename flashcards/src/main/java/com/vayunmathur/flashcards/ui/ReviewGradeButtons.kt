package com.vayunmathur.flashcards.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.flashcards.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.flashcards.domain.Grade
import com.vayunmathur.flashcards.ui.ReviewUiState
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ButtonDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

@Composable
internal fun GradeButtons(state: ReviewUiState, onGrade: (Grade) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GradeButton(R.string.grade_again, GRADE_AGAIN_COLOR, state.label(Grade.AGAIN), Grade.AGAIN, onGrade, Modifier.weight(1f))
        GradeButton(UiR.string.hard, GRADE_HARD_COLOR, state.label(Grade.HARD), Grade.HARD, onGrade, Modifier.weight(1f))
        GradeButton(R.string.grade_good, GRADE_GOOD_COLOR, state.label(Grade.GOOD), Grade.GOOD, onGrade, Modifier.weight(1f))
        GradeButton(UiR.string.easy, GRADE_EASY_COLOR, state.label(Grade.EASY), Grade.EASY, onGrade, Modifier.weight(1f))
    }
}

@Composable
private fun GradeButton(
    labelRes: Int,
    color: Color,
    interval: String,
    grade: Grade,
    onGrade: (Grade) -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = { onGrade(grade) },
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(labelRes), style = MaterialTheme.typography.labelLarge)
            if (interval.isNotEmpty()) {
                Text(interval, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private val GRADE_AGAIN_COLOR = Color(0xFFD32F2F)
private val GRADE_HARD_COLOR = Color(0xFFF57C00)
private val GRADE_GOOD_COLOR = Color(0xFF388E3C)
private val GRADE_EASY_COLOR = Color(0xFF1976D2)
