package com.vayunmathur.education.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import com.vayunmathur.education.content.Band
import com.vayunmathur.education.util.EducationAchievements
import com.vayunmathur.education.util.EducationViewModel
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.AchievementStatus
import com.vayunmathur.library.util.NavBackStack

/** Badges (Explorer/Scholar) / stickers (K-2) gallery. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BadgesPage(backStack: NavBackStack<Route>, viewModel: EducationViewModel) {
    val learner by viewModel.learner.collectAsStateWithLifecycle()
    val band = viewModel.bandOf(learner)
    val statuses by remember { viewModel.badgeStatuses() }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (band == Band.K2) "My Stickers" else "Badges") },
                navigationIcon = { IconNavigation(backStack) },
            )
        },
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
