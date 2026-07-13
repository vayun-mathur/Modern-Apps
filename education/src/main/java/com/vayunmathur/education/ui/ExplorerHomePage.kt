package com.vayunmathur.education.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import com.vayunmathur.education.content.Course
import com.vayunmathur.education.content.Subject
import com.vayunmathur.education.util.EducationViewModel
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.util.NavBackStack

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (l.name.isBlank()) "Explore" else "Hi, ${l.name}! 👋") },
                actions = {
                    IconButton(onClick = { backStack.add(Route.Badges) }) {
                        Icon(Icons.Filled.EmojiEvents, "Badges")
                    }
                    IconButton(onClick = { backStack.add(Route.ParentGate) }) { IconSettings() }
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

            continueUnit?.let { unit ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .clickable { backStack.add(Route.UnitScreen(unit.id)) },
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "Jump back in",
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
                    label = { Text("Find a topic") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            content.subjects.forEach { subject ->
                val subjectCourses = visibleCourses.filter { it.subject == subject }
                if (subjectCourses.isNotEmpty()) {
                    item { SectionHeader(subject.displayName) }
                    items(subjectCourses, key = { it.id }) { course ->
                        ExplorerCourseCard(course) { backStack.add(Route.Course(course.id)) }
                    }
                }
            }

            if (visibleCourses.isEmpty()) {
                item {
                    Text(
                        "Nothing here yet — ask a grown-up to add lessons.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExplorerCourseCard(course: Course, onClick: () -> Unit) {
    val color = subjectColor(course.subject)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
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
                    .background(color),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    course.subject.displayName.first().toString(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(course.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${course.units.size} topics",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Playful, stable accent color per subject for the Explorer shell. */
fun subjectColor(subject: Subject): Color = when (subject) {
    Subject.MATH -> Color(0xFF3B82F6)
    Subject.SCIENCE -> Color(0xFF22C55E)
    Subject.READING -> Color(0xFFF97316)
    Subject.SOCIAL_STUDIES -> Color(0xFFA855F7)
    Subject.COMPUTING -> Color(0xFF14B8A6)
}
