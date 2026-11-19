package com.vayunmathur.education.ui

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.DetailLazyColumn
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconEmojiEvents
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.education.Route
import com.vayunmathur.education.platform.pushChain
import com.vayunmathur.education.domain.Course
import com.vayunmathur.education.domain.Subject
import com.vayunmathur.education.platform.EducationViewModel
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import androidx.compose.ui.res.stringResource
import com.vayunmathur.education.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorerHomePage(backStack: NavBackStack<Route>, viewModel: EducationViewModel) {
    val learner by viewModel.learner.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val content = viewModel.content
    val l = learner ?: return
    val band = viewModel.bandOf(l)

    var query by remember { mutableStateOf("") }

    // Band-appropriate courses, falling back to everything if none match.
    val bandCourses = remember(band) {
        content.coursesForBand(band).ifEmpty { content.courses }
    }
    val visibleCourses = remember(bandCourses, query) {
        if (query.isBlank()) bandCourses
        else bandCourses.filter { it.title.contains(query, ignoreCase = true) }
    }

    // Recommended "continue": first unit not yet fully mastered.
    val continueUnit = remember(bandCourses, progress) {
        bandCourses.firstNotNullOfOrNull { course ->
            course.units.firstOrNull { u ->
                averageStars(content.skillIdsOfUnit(u), progress) < 3
            }
        }
    }

    DetailLazyColumn(
        title = if (l.name.isBlank()) stringResource(R.string.explore) else stringResource(R.string.hi, l.name),
        actions = {
            IconButton(onClick = { backStack.add(Route.Badges) }) {
                IconEmojiEvents()
            }
            IconButton(onClick = { backStack.add(Route.ParentGate) }) { IconSettings() }
        },
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StreakChip(l.streakCount)
                StarsChip(l.totalStars)
            }
        }

        continueUnit?.let { unit ->
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { backStack.add(Route.UnitScreen(unit.id)) },
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.jump_back_in),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            unit.title,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.find_a_topic)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        content.subjects.forEach { subject ->
            val subjectCourses = visibleCourses.filter { it.subject == subject }
            if (subjectCourses.isNotEmpty()) {
                item { SectionHeader(stringResource(subject.displayNameRes)) }
                items(subjectCourses, key = { it.id }) { course ->
                    ExplorerCourseCard(course) { backStack.pushChain(Route.Course(course.id)) }
                }
            }
        }

        if (visibleCourses.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.nothing_here_yet_ask_a_grown_up_to_add_l),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ExplorerCourseCard(course: Course, onClick: () -> Unit) {
    val colors = subjectColors(course.subject)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(colors.container),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(course.subject.displayNameRes).first().toString(),
                    color = colors.onContainer,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(course.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    pluralStringResource(R.plurals.topics, course.units.size, course.units.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Accent pair per subject for the Explorer shell, drawn from the active theme. */
data class SubjectColors(val container: Color, val onContainer: Color)

