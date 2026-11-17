package com.vayunmathur.education.ui
import com.vayunmathur.education.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.education.Route
import com.vayunmathur.education.platform.EducationViewModel
import com.vayunmathur.education.platform.LocalNarrator
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import androidx.compose.ui.res.stringResource

/** K-2 lesson: two big, narrated tiles — Watch a video, then Play the questions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun K2LessonPage(backStack: NavBackStack<Route>, viewModel: EducationViewModel, lessonId: String) {
    val content = viewModel.content
    val lesson = content.lesson(lessonId)
    val narrator = LocalNarrator.current

    LaunchedEffect(lessonId) { narrator?.speak(lesson?.title.orEmpty()) }

    AppScaffold(
        title = lesson?.title ?: stringResource(R.string.lesson),
        backStack = backStack,
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        if (lesson == null) {
            MissingContent(padding, stringResource(R.string.let_s_go_back))
            return@AppScaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        ) {
            lesson.videos.firstOrNull()?.let { video ->
                K2BigTile(
                    emoji = "📺",
                    label = stringResource(com.vayunmathur.education.R.string.watch),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    onClick = {
                        narrator?.stop()
                        backStack.add(Route.VideoPlayer(video.videoUrl, video.title))
                    },
                )
            }
            lesson.exercise?.let { exercise ->
                K2BigTile(
                    emoji = "🎮",
                    label = stringResource(com.vayunmathur.education.R.string.play),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    onClick = {
                        narrator?.stop()
                        backStack.add(Route.K2Quiz(exercise.id))
                    },
                )
            }
        }
    }
}

