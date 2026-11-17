package com.vayunmathur.education.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedCard
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.education.Route
import com.vayunmathur.education.domain.VideoRef
import com.vayunmathur.education.platform.EducationViewModel
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.sharedText
import androidx.compose.ui.res.stringResource
import com.vayunmathur.education.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LessonPage(backStack: NavBackStack<Route>, viewModel: EducationViewModel, lessonId: String) {
    val content = viewModel.content
    val lesson = content.lesson(lessonId)

    AppScaffold(
        title = {
            Text(
                lesson?.title ?: stringResource(R.string.lesson),
                modifier = lesson
                    ?.let { Modifier.sharedText("education-lesson-title-${it.id}") }
                    ?: Modifier,
            )
        },
        onNavigateBack = { backStack.pop() },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        if (lesson == null) {
            MissingContent(padding, stringResource(R.string.this_lesson_is_unavailable))
            return@AppScaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (lesson.videos.isNotEmpty()) {
                Text(stringResource(R.string.watch), style = MaterialTheme.typography.titleMedium)
                lesson.videos.forEach { video ->
                    VideoRow(video) {
                        backStack.add(Route.VideoPlayer(video.videoUrl, video.title))
                    }
                }
            }
            lesson.exercise?.let { exercise ->
                Button(
                    onClick = { backStack.add(Route.Quiz(exercise.id)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(exercise.title.ifBlank { stringResource(R.string.start_exercise) })
                }
            }
        }
    }
}

@Composable
private fun VideoRow(video: VideoRef, onPlay: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(video.title, style = MaterialTheme.typography.bodyLarge)
                if (video.durationSeconds > 0) {
                    Text(
                        formatDuration(video.durationSeconds),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onPlay) {
                IconPlay()
            }
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}
