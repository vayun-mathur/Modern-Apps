package com.vayunmathur.education.ui
import com.vayunmathur.education.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FilledTonalButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.education.Route
import com.vayunmathur.education.platform.pushChain
import com.vayunmathur.education.domain.ModuleType
import com.vayunmathur.education.platform.EducationViewModel
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.sharedText
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnitPage(backStack: NavBackStack<Route>, viewModel: EducationViewModel, unitId: String) {
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val content = viewModel.content
    val unit = content.unit(unitId)

    AppScaffold(
        title = {
            Text(
                unit?.title ?: stringResource(R.string.unit),
                modifier = unit
                    ?.let { Modifier.sharedText("education-unit-title-${it.id}") }
                    ?: Modifier,
            )
        },
        onNavigateBack = { backStack.pop() },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        if (unit == null) {
            MissingContent(padding, stringResource(R.string.this_unit_is_unavailable))
            return@AppScaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            val deadline = viewModel.deadlineFor(ModuleType.UNIT, unit.id)
            deadline?.let {
                item {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        DeadlineChip(it.dueEpochDay)
                    }
                }
            }
            items(unit.lessons, key = { it.id }) { lesson ->
                val skills = lesson.exercise?.let { content.skillIdsOf(it) } ?: emptyList()
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clickable { backStack.pushChain(Route.LessonScreen(lesson.id)) },
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                lesson.title,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.sharedText("education-lesson-title-${lesson.id}"),
                            )
                            val videoText = pluralStringResource(R.plurals.video_count, lesson.videos.size, lesson.videos.size)
                            val exerciseSuffix = if (lesson.exercise != null) stringResource(R.string.exercise) else ""
                            Text(
                                videoText + exerciseSuffix,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        StarRow(averageStars(skills, progress))
                    }
                }
            }
            unit.quiz?.let { quiz ->
                item {
                    FilledTonalButton(
                        onClick = { backStack.add(Route.Quiz(quiz.id)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        Text(quiz.title.ifBlank { stringResource(R.string.unit_quiz) })
                    }
                }
            }
        }
    }
}
