package com.vayunmathur.education

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.vayunmathur.education.content.ContentRepository
import com.vayunmathur.education.data.DB_NAME
import com.vayunmathur.education.data.DeadlineDao
import com.vayunmathur.education.data.EducationDatabase
import com.vayunmathur.education.data.Learner
import com.vayunmathur.education.data.LearnerDao
import com.vayunmathur.education.data.SkillProgressDao
import com.vayunmathur.education.ui.CoursePage
import com.vayunmathur.education.ui.BadgesPage
import com.vayunmathur.education.ui.HomePage
import com.vayunmathur.education.ui.K2LessonPage
import com.vayunmathur.education.ui.K2QuizPage
import com.vayunmathur.education.ui.K2RewardPage
import com.vayunmathur.education.ui.LessonPage
import com.vayunmathur.education.ui.OnboardingPage
import com.vayunmathur.education.ui.ParentGatePage
import com.vayunmathur.education.ui.ParentPage
import com.vayunmathur.education.ui.QuizPage
import com.vayunmathur.education.ui.ResultsPage
import com.vayunmathur.education.ui.UnitPage
import com.vayunmathur.education.ui.VideoPlayerPage
import com.vayunmathur.education.util.EducationViewModel
import com.vayunmathur.education.util.EducationViewModelFactory
import com.vayunmathur.education.util.LocalNarrator
import com.vayunmathur.education.util.rememberNarrator
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.AchievementNotification
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    private lateinit var content: ContentRepository
    private lateinit var learnerDao: LearnerDao
    private lateinit var skillProgressDao: SkillProgressDao
    private lateinit var deadlineDao: DeadlineDao

    private val viewModel: EducationViewModel by viewModels {
        EducationViewModelFactory(application, content, learnerDao, skillProgressDao, deadlineDao)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val ready = mutableStateOf(false)
        lifecycleScope.launch(Dispatchers.IO) {
            content = ContentRepository.load(this@MainActivity)
            val db = buildDatabase<EducationDatabase>(dbName = DB_NAME)
            learnerDao = db.learnerDao()
            skillProgressDao = db.skillProgressDao()
            deadlineDao = db.deadlineDao()
            // Seed the single learner row so onboarding can observe it.
            if (learnerDao.get() == null) learnerDao.upsert(Learner())
            withContext(Dispatchers.Main) { ready.value = true }
        }

        setContent {
            DynamicTheme {
                CompositionLocalProvider(LocalNarrator provides rememberNarrator()) {
                    if (ready.value) RootNavigation(viewModel)
                }
            }
        }
    }
}

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Home : Route
    @Serializable
    data class Course(val courseId: String) : Route
    @Serializable
    data class UnitScreen(val unitId: String) : Route
    @Serializable
    data class LessonScreen(val lessonId: String) : Route
    @Serializable
    data class Quiz(val exerciseId: String) : Route
    @Serializable
    data class VideoPlayer(val youtubeId: String, val title: String) : Route
    @Serializable
    data class K2Lesson(val lessonId: String) : Route
    @Serializable
    data class K2Quiz(val exerciseId: String) : Route
    @Serializable
    data class K2Reward(val stars: Int) : Route
    @Serializable
    data class Results(val total: Int, val correct: Int, val stars: Int) : Route
    @Serializable
    data object ParentGate : Route
    @Serializable
    data object Parent : Route
    @Serializable
    data object Badges : Route
}

/** Chooses onboarding vs. the main graph based on the (single) learner's state. */
@Composable
fun RootNavigation(viewModel: EducationViewModel) {
    val learner by viewModel.learner.collectAsStateWithLifecycle()
    val l = learner ?: return
    if (!l.onboarded) {
        OnboardingPage(viewModel)
    } else {
        MainGraph(viewModel)
    }
}

@Composable
fun MainGraph(viewModel: EducationViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Home)
    val badge by viewModel.newBadge.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        MainNavigation(backStack) {
            entry<Route.Home> { HomePage(backStack, viewModel) }
            entry<Route.Course> { CoursePage(backStack, viewModel, it.courseId) }
            entry<Route.UnitScreen> { UnitPage(backStack, viewModel, it.unitId) }
            entry<Route.LessonScreen> { LessonPage(backStack, viewModel, it.lessonId) }
            entry<Route.Quiz> { QuizPage(backStack, viewModel, it.exerciseId) }
            entry<Route.VideoPlayer> { VideoPlayerPage(backStack, it.youtubeId, it.title) }
            entry<Route.K2Lesson> { K2LessonPage(backStack, viewModel, it.lessonId) }
            entry<Route.K2Quiz> { K2QuizPage(backStack, viewModel, it.exerciseId) }
            entry<Route.K2Reward> { K2RewardPage(backStack, viewModel, it.stars) }
            entry<Route.Results> { ResultsPage(backStack, viewModel, it.total, it.correct, it.stars) }
            entry<Route.ParentGate>(metadata = DialogPage()) { ParentGatePage(backStack, viewModel) }
            entry<Route.Parent> { ParentPage(backStack, viewModel) }
            entry<Route.Badges> { BadgesPage(backStack, viewModel) }
        }
        badge?.let { AchievementNotification(it) { viewModel.dismissBadge() } }
    }
}
