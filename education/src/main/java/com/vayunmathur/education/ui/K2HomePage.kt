package com.vayunmathur.education.ui
import com.vayunmathur.education.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.education.Route
import com.vayunmathur.education.domain.Band
import com.vayunmathur.education.domain.Lesson
import com.vayunmathur.education.platform.EducationViewModel
import com.vayunmathur.education.platform.LocalNarrator
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import androidx.compose.ui.res.stringResource

/**
 * K-2 "Guided Playground" home: audio-first, minimal text. Surfaces exactly one
 * "next thing to do" as a big tile, with a mascot, a row of suns for the streak,
 * and a jar of collected stars (no numeric stats). Settings sit behind the PIN
 * parent gate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun K2HomePage(backStack: NavBackStack<Route>, viewModel: EducationViewModel) {
    val learner by viewModel.learner.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val content = viewModel.content
    val narrator = LocalNarrator.current
    val l = learner ?: return

    val lessons = remember(content) {
        val course = content.coursesForBand(Band.K2).firstOrNull() ?: content.courses.firstOrNull()
        course?.units?.flatMap { it.lessons } ?: emptyList()
    }
    val nextLesson: Lesson? = lessons.firstOrNull { lesson ->
        val ex = lesson.exercise ?: return@firstOrNull true
        averageStars(content.skillIdsOf(ex), progress) < 3
    } ?: lessons.firstOrNull()

    LaunchedEffect(nextLesson?.id) {
        narrator?.speak("Hi ${l.name}! Let's learn. ${nextLesson?.title.orEmpty()}")
    }

    AppScaffold(
        title = { SunStreak(l.streakCount) },
        actions = {
            IconButton(onClick = { backStack.add(Route.Badges) }) {
                Text("\uD83C\uDF1F", fontSize = 24.sp)
            }
            IconButton(onClick = { backStack.add(Route.ParentGate) }) { IconSettings() }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        ) {
            Text(l.avatar, fontSize = 96.sp)

            if (nextLesson == null) {
                Text(
                    stringResource(R.string.all_done_great_job),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
            } else {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            narrator?.speak(nextLesson.title)
                            backStack.add(Route.K2Lesson(nextLesson.id))
                        },
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("▶", fontSize = 64.sp)
                        Text(
                            nextLesson.title,
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }

            StarJar(l.totalStars)
        }
    }
}

/** A row of suns representing the streak (visual only, capped). */
@Composable
private fun SunStreak(count: Int) {
    if (count <= 0) return
    Text("☀️".repeat(count.coerceAtMost(5)), fontSize = 20.sp)
}

/** Collected stars shown as a jar filling up — no numbers. */
@Composable
private fun StarJar(totalStars: Int) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🫙", fontSize = 40.sp)
        Text(
            "⭐".repeat(totalStars.coerceAtMost(6)),
            fontSize = 28.sp,
        )
    }
}
