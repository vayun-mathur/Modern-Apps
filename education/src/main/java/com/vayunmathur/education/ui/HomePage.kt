package com.vayunmathur.education.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.education.Route
import com.vayunmathur.education.content.ModuleType
import com.vayunmathur.education.util.EducationViewModel
import com.vayunmathur.library.ui.IconChevronRight
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.util.NavBackStack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScholarHomePage(backStack: NavBackStack<Route>, viewModel: EducationViewModel) {
    val learner by viewModel.learner.collectAsStateWithLifecycle()
    val deadlines by viewModel.deadlines.collectAsStateWithLifecycle()
    val l = learner ?: return
    val content = viewModel.content

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (l.name.isBlank()) "Learn" else "Hi, ${l.name}") },
                actions = {
                    IconButton(onClick = { backStack.add(Route.Badges) }) {
                        Icon(Icons.Filled.EmojiEvents, "Badges")
                    }
                    IconButton(onClick = { backStack.add(Route.ParentGate) }) {
                        IconSettings()
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        ) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StreakChip(l.streakCount)
                    StarsChip(l.totalStars)
                }
            }

            if (deadlines.isNotEmpty()) {
                item { SectionHeader("Due soon") }
                items(deadlines, key = { it.id }) { d ->
                    val type = runCatching { ModuleType.valueOf(d.moduleType) }.getOrNull()
                    val title = type?.let { content.moduleTitle(it, d.moduleId) } ?: "Assignment"
                    OutlinedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clickable { navigateToModule(backStack, type, d.moduleId) },
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            DeadlineChip(d.dueEpochDay)
                        }
                    }
                }
            }

            content.subjects.forEach { subject ->
                item { SectionHeader(subject.displayName) }
                items(content.coursesForSubject(subject), key = { it.id }) { course ->
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clickable { backStack.add(Route.Course(course.id)) },
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(course.title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${course.units.size} units",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconChevronRight()
                        }
                    }
                }
            }

            if (content.courses.isEmpty()) {
                item {
                    Text(
                        "No content packs are installed yet.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Navigates to the screen for a module referenced by a deadline. */
fun navigateToModule(backStack: NavBackStack<Route>, type: ModuleType?, moduleId: String) {
    when (type) {
        ModuleType.COURSE -> backStack.add(Route.Course(moduleId))
        ModuleType.UNIT -> backStack.add(Route.UnitScreen(moduleId))
        ModuleType.LESSON -> backStack.add(Route.LessonScreen(moduleId))
        null -> {}
    }
}
