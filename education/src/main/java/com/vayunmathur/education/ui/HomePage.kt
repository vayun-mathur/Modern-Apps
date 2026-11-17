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

/** Binds [EducationViewModel] and the back stack to the stateless [ScholarHomeScreen]. */
@Composable
fun ScholarHomePage(backStack: NavBackStack<Route>, viewModel: EducationViewModel) {
    val learner by viewModel.learner.collectAsStateWithLifecycle()
    val deadlines by viewModel.deadlines.collectAsStateWithLifecycle()
    val l = learner ?: return
    val content = viewModel.content
    // Bound out here so the override below is unambiguously the free function, not itself.
    val onParentArea = { openParentArea(backStack, viewModel) }

    ScholarHomeScreen(
        state = HomeUiState(
            learnerName = l.name,
            streakCount = l.streakCount,
            totalStars = l.totalStars,
            deadlines = deadlines.map { d ->
                val type = runCatching { ModuleType.valueOf(d.moduleType) }.getOrNull()
                HomeDeadline(
                    id = d.id,
                    title = type?.let { content.moduleTitle(it, d.moduleId) } ?: "Assignment",
                    dueEpochDay = d.dueEpochDay,
                    moduleType = type,
                    moduleId = d.moduleId,
                )
            },
            sections = content.subjects.map { subject ->
                HomeSection(
                    subject = subject,
                    courses = content.coursesForSubject(subject).map {
                        HomeCourse(it.id, it.title, it.units.size)
                    },
                )
            },
        ),
        actions = object : HomeActions {
            override fun openBadges() {
                backStack.add(Route.Badges)
            }

            override fun openParentArea() {
                onParentArea()
            }

            override fun openCourse(courseId: String) {
                backStack.pushChain(Route.Course(courseId))
            }

            override fun openDeadline(deadline: HomeDeadline) {
                val route = when (deadline.moduleType) {
                    ModuleType.COURSE -> Route.Course(deadline.moduleId)
                    ModuleType.UNIT -> Route.UnitScreen(deadline.moduleId)
                    ModuleType.LESSON -> Route.LessonScreen(deadline.moduleId)
                    null -> return
                }
                backStack.pushChain(route)
            }
        },
    )
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

/**
 * Opens the parent area. Until a PIN has been set, this goes straight to the
 * parent settings (where the PIN can be created); once a PIN exists the entry
 * is protected by the PIN gate.
 */
fun openParentArea(backStack: NavBackStack<Route>, viewModel: EducationViewModel) {
    val route = if (viewModel.learner.value?.pinHash == null) Route.Parent else Route.ParentGate
    backStack.add(route)
}
