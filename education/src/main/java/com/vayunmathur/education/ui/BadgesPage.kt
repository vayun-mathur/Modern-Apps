package com.vayunmathur.education.ui
import com.vayunmathur.education.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
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
import com.vayunmathur.education.platform.EducationAchievements
import com.vayunmathur.education.platform.EducationViewModel
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.AchievementStatus
import com.vayunmathur.library.util.NavBackStack
import androidx.compose.ui.res.stringResource

/** Badges (Explorer/Scholar) / stickers (K-2) gallery. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BadgesPage(backStack: NavBackStack<Route>, viewModel: EducationViewModel) {
    val learner by viewModel.learner.collectAsStateWithLifecycle()
    val band = viewModel.bandOf(learner)
    val statuses by remember { viewModel.badgeStatuses() }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    AppScaffold(
        title = if (band == Band.K2) stringResource(R.string.my_stickers) else stringResource(R.string.badges),
        backStack = backStack,
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        ) {
            items(statuses, key = { it.achievement.id }) { status ->
                BadgeRow(status)
            }
        }
    }
}

@Composable
private fun BadgeRow(status: AchievementStatus) {
    val sticker = EducationAchievements.STICKERS[status.achievement.id] ?: "🏅"
    val unlocked = status.isUnlocked
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = if (unlocked) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                if (unlocked) sticker else "🔒",
                fontSize = 40.sp,
                modifier = Modifier.size(48.dp),
                textAlign = TextAlign.Center,
            )
            Column(Modifier.weight(1f)) {
                Text(status.achievement.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    status.achievement.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (unlocked) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val target = status.achievement.targetProgress
                if (!unlocked && target > 1) {
                    LinearProgressIndicator(
                        progress = { (status.progress.toFloat() / target).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    )
                    Text(
                        "${status.progress.coerceAtMost(target)} / $target",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
