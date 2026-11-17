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
import com.vayunmathur.library.ui.appBarScrollBehavior
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

/** Binds [EducationViewModel] and the back stack to the stateless [ScholarCourseScreen]. */
@Composable
fun ScholarCoursePage(backStack: NavBackStack<Route>, viewModel: EducationViewModel, courseId: String) {
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val content = viewModel.content
    val course = content.course(courseId)

    ScholarCourseScreen(
        state = CourseUiState(
            title = course?.title ?: stringResource(R.string.course),
            description = course?.description.orEmpty(),
            available = course != null,
            units = course?.units.orEmpty().map { unit ->
                CourseUnitRow(
                    id = unit.id,
                    title = unit.title,
                    lessonCount = unit.lessons.size,
                    stars = averageStars(content.skillIdsOfUnit(unit), progress),
                    dueEpochDay = viewModel.deadlineFor(ModuleType.UNIT, unit.id)?.dueEpochDay,
                )
            },
            challenge = course?.challenge,
            titleSharedKey = course?.let { "education-course-title-${it.id}" },
        ),
        actions = object : CourseActions {
            override fun navigateUp() {
                backStack.pop()
            }

            override fun openUnit(unitId: String) {
                backStack.pushChain(Route.UnitScreen(unitId))
            }

            override fun openExercise(exerciseId: String) {
                backStack.add(Route.Quiz(exerciseId))
            }
        },
    )
}


