package com.vayunmathur.education.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.education.Route
import com.vayunmathur.education.domain.Band
import com.vayunmathur.education.platform.EducationViewModel
import com.vayunmathur.library.util.NavBackStack

/**
 * Band dispatchers. The catalog (Home) is a single, unified list of every
 * course and is shown to everyone. Deeper screens (Course/Unit/Lesson/Quiz/
 * Results) still adapt per band: Course diverges into Explorer/Scholar shells.
 */
@Composable
fun HomePage(backStack: NavBackStack<Route>, viewModel: EducationViewModel) {
    ScholarHomePage(backStack, viewModel)
}


@Composable
internal fun activeBand(viewModel: EducationViewModel): Band {
    val learner by viewModel.learner.collectAsStateWithLifecycle()
    return viewModel.bandOf(learner)
}
