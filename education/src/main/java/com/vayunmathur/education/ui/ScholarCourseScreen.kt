package com.vayunmathur.education.ui
import androidx.compose.ui.res.pluralStringResource
import com.vayunmathur.education.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.DetailLazyColumn
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FilledTonalButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.education.Route
import com.vayunmathur.education.platform.pushChain
import com.vayunmathur.education.domain.ModuleType
import com.vayunmathur.education.platform.CourseActions
import com.vayunmathur.education.platform.CourseUiState
import com.vayunmathur.education.platform.CourseUnitRow
import com.vayunmathur.education.platform.EducationViewModel
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.sharedText
import androidx.compose.ui.res.stringResource

/**
 * A course's units, with no dependency on the ViewModel or the back stack so it can be
 * rendered from a `@Preview` — see `src/screenshotTest`, which is where the store listing
 * images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScholarCourseScreen(state: CourseUiState, actions: CourseActions) {
    if (!state.available) {
        AppScaffold(
            title = state.title,
            onNavigateBack = { actions.navigateUp() },
        ) { padding ->
            MissingContent(padding, stringResource(R.string.this_course_is_unavailable))
        }
        return
    }
    DetailLazyColumn(
        title = {
            Text(
                state.title,
                modifier = state.titleSharedKey
                    ?.let { Modifier.sharedText(it) }
                    ?: Modifier,
            )
        },
        onNavigateBack = { actions.navigateUp() },
    ) {
        if (state.description.isNotBlank()) {
            item {
                Text(
                    state.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(state.units, key = { it.id }) { unit ->
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { actions.openUnit(unit.id) },
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            unit.title,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.sharedText("education-unit-title-${unit.id}"),
                        )
                        StarRow(unit.stars)
                    }
                    Text(
                        pluralStringResource(R.plurals.lessons, unit.lessonCount, unit.lessonCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    unit.dueEpochDay?.let {
                        Row(Modifier.padding(top = 8.dp)) { DeadlineChip(it) }
                    }
                }
            }
        }
        state.challenge?.let { challenge ->
            item {
                FilledTonalButton(
                    onClick = { actions.openExercise(challenge.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(challenge.title.ifBlank { stringResource(R.string.course_challenge) })
                }
            }
        }
    }
}
