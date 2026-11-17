package com.vayunmathur.education

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.education.platform.EducationViewModel
import com.vayunmathur.education.ui.BadgesPage
import com.vayunmathur.education.ui.CoursePage
import com.vayunmathur.education.ui.HomePage
import com.vayunmathur.education.ui.K2LessonPage
import com.vayunmathur.education.ui.K2QuizPage
import com.vayunmathur.education.ui.K2RewardPage
import com.vayunmathur.education.ui.LessonPage
import com.vayunmathur.education.ui.ParentGatePage
import com.vayunmathur.education.ui.ParentPage
import com.vayunmathur.education.ui.QuizPage
import com.vayunmathur.education.ui.ResultsPage
import com.vayunmathur.education.ui.UnitPage
import com.vayunmathur.education.ui.VideoPlayerPage
import com.vayunmathur.library.ui.AchievementNotification
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.FullscreenPage
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.rememberNavBackStack

@Composable
fun Navigation(viewModel: EducationViewModel) {
    val learnerState by viewModel.learner.collectAsStateWithLifecycle()
    learnerState ?: return
    val backStack = rememberNavBackStack<Route>(Route.Home)
    val badge by viewModel.newBadge.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        MainNavigation(backStack) {
            entry<Route.Home>(metadata = ListPage()) { HomePage(backStack, viewModel) }
            // Morph on the three levels of the title chain: a course card's title carries into the
            // course bar, a unit card's into the unit bar, a lesson card's into the lesson bar.
            entry<Route.Course>(metadata = ListDetailPage() + MorphPage()) {
                CoursePage(backStack, viewModel, it.courseId)
            }
            entry<Route.UnitScreen>(metadata = ListDetailPage() + MorphPage()) {
                UnitPage(backStack, viewModel, it.unitId)
            }
            entry<Route.LessonScreen>(metadata = ListDetailPage() + MorphPage()) {
                LessonPage(backStack, viewModel, it.lessonId)
            }
            // Quiz/Results are exercises off a lesson, not catalog details.
            entry<Route.Quiz>(metadata = ListDetailPage()) { QuizPage(backStack, viewModel, it.exerciseId) }
            entry<Route.VideoPlayer>(metadata = FullscreenPage()) { VideoPlayerPage(backStack, it.videoUrl, it.title) }
            entry<Route.K2Lesson>(metadata = ListDetailPage()) { K2LessonPage(backStack, viewModel, it.lessonId) }
            entry<Route.K2Quiz>(metadata = ListDetailPage()) { K2QuizPage(backStack, viewModel, it.exerciseId) }
            entry<Route.K2Reward>(metadata = ListDetailPage()) { K2RewardPage(backStack, it.stars) }
            entry<Route.Results>(metadata = ListDetailPage()) {
                ResultsPage(backStack, viewModel, it.total, it.correct, it.stars)
            }
            entry<Route.ParentGate>(metadata = DialogPage()) { ParentGatePage(backStack, viewModel) }
            entry<Route.Parent>(metadata = ListDetailPage()) { ParentPage(backStack, viewModel) }
            entry<Route.Badges>(metadata = ListDetailPage()) { BadgesPage(backStack, viewModel) }
        }
        badge?.let { AchievementNotification(it) { viewModel.dismissBadge() } }
    }
}
