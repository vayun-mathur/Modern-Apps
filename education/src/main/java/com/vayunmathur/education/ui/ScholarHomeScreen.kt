package com.vayunmathur.education.ui
import androidx.compose.ui.res.pluralStringResource
import com.vayunmathur.education.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import com.vayunmathur.library.ui.DetailLazyColumn
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedCard
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.education.Route
import com.vayunmathur.education.domain.ModuleType
import com.vayunmathur.education.platform.pushChain
import com.vayunmathur.education.platform.EducationViewModel
import com.vayunmathur.education.platform.HomeActions
import com.vayunmathur.education.platform.HomeCourse
import com.vayunmathur.education.platform.HomeDeadline
import com.vayunmathur.education.platform.HomeSection
import com.vayunmathur.education.platform.HomeUiState
import com.vayunmathur.library.ui.IconChevronRight
import com.vayunmathur.library.ui.IconEmojiEvents
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.sharedText
import androidx.compose.ui.res.stringResource

/**
 * The catalog, with no dependency on the ViewModel or the back stack so it can be rendered
 * from a `@Preview` — see `src/screenshotTest`, which is where the store listing images
 * come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScholarHomeScreen(state: HomeUiState, actions: HomeActions) {
    DetailLazyColumn(
        title = if (state.learnerName.isBlank()) stringResource(R.string.learn)
        else stringResource(R.string.hi_1, state.learnerName),
        actions = {
            IconButton(onClick = { actions.openBadges() }) {
                IconEmojiEvents()
            }
            IconButton(onClick = { actions.openParentArea() }) {
                IconSettings()
            }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StreakChip(state.streakCount)
                StarsChip(state.totalStars)
            }
        }

        if (state.deadlines.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.due_soon)) }
            items(state.deadlines, key = { it.id }) { d ->
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { actions.openDeadline(d) },
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(d.title, style = MaterialTheme.typography.titleMedium)
                        DeadlineChip(d.dueEpochDay)
                    }
                }
            }
        }

        state.sections.forEach { section ->
            item { SectionHeader(stringResource(section.subject.displayNameRes)) }
            items(section.courses, key = { it.id }) { course ->
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { actions.openCourse(course.id) },
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                course.title,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.sharedText("education-course-title-${course.id}"),
                            )
                            Text(
                                pluralStringResource(R.plurals.units, course.unitCount, course.unitCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconChevronRight()
                    }
                }
            }
        }

        if (state.sections.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.no_content_packs_are_installed_yet),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
