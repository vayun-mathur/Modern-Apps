package com.vayunmathur.education.ui
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.education.Route
import com.vayunmathur.education.domain.Band
import com.vayunmathur.education.platform.EducationViewModel
import com.vayunmathur.library.util.NavBackStack

@Composable
fun CoursePage(backStack: NavBackStack<Route>, viewModel: EducationViewModel, courseId: String) {
    when (activeBand(viewModel)) {
        Band.SCHOLAR -> ScholarCoursePage(backStack, viewModel, courseId)
        else -> ExplorerCoursePage(backStack, viewModel, courseId)
    }
}
