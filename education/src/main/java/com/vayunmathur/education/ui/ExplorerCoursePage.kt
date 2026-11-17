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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.education.Route
import com.vayunmathur.education.platform.pushChain
import com.vayunmathur.education.domain.CourseUnit
import com.vayunmathur.education.domain.ModuleType
import com.vayunmathur.education.platform.EducationViewModel
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.sharedText
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorerCoursePage(backStack: NavBackStack<Route>, viewModel: EducationViewModel, courseId: String) {
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val content = viewModel.content
    val course = content.course(courseId)
    val accent = course?.let { subjectColors(it.subject) }
        ?: SubjectColors(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)

    AppScaffold(
        title = {
            Text(
                course?.title ?: stringResource(R.string.topic),
                modifier = course
                    ?.let { Modifier.sharedText("education-course-title-${it.id}") }
                    ?: Modifier,
            )
        },
        onNavigateBack = { backStack.pop() },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        if (course == null) {
            MissingContent(padding, stringResource(R.string.this_topic_is_unavailable))
            return@AppScaffold
        }

        // The first not-yet-mastered unit is the recommended next step.
        val nextUnitId = course.units.firstOrNull { u ->
            averageStars(content.skillIdsOfUnit(u), progress) < 3
        }?.id

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
        ) {
            itemsIndexed(course.units) { index, unit ->
                val stars = averageStars(content.skillIdsOfUnit(unit), progress)
                val deadline = viewModel.deadlineFor(ModuleType.UNIT, unit.id)
                PathNode(
                    number = index + 1,
                    unit = unit,
                    stars = stars,
                    accent = accent,
                    isNext = unit.id == nextUnitId,
                    dueEpochDay = deadline?.dueEpochDay,
                    onClick = { backStack.pushChain(Route.UnitScreen(unit.id)) },
                )
            }
            course.challenge?.let { challenge ->
                item {
                    FilledTonalButton(
                        onClick = { backStack.add(Route.Quiz(challenge.id)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) { Text(challenge.title.ifBlank { stringResource(R.string.big_challenge) }) }
                }
            }
        }
    }
}

@Composable
private fun PathNode(
    number: Int,
    unit: CourseUnit,
    stars: Int,
    accent: SubjectColors,
    isNext: Boolean,
    dueEpochDay: Long?,
    onClick: () -> Unit,
) {
    val mastered = stars >= 3
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (mastered) accent.container else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (mastered) "★" else "$number",
                color = if (mastered) accent.onContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Card(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(16.dp),
            colors = if (isNext) {
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            } else {
                CardDefaults.cardColors()
            },
        ) {
            Column(Modifier.padding(16.dp)) {
                if (isNext) {
                    Text(
                        stringResource(R.string.start_here),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    unit.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.sharedText("education-unit-title-${unit.id}"),
                )
                Row(
                    Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StarRow(stars)
                    dueEpochDay?.let { DeadlineChip(it) }
                }
            }
        }
    }
}
