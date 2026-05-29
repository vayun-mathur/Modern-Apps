package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.util.AchievementsManager
import com.vayunmathur.library.util.BaseBackupAgent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameCenterScreen(
    backupAgent: BaseBackupAgent,
    manager: AchievementsManager,
    onBack: () -> Unit
) {
    val statuses by manager.getAchievementStatuses().collectAsState(initial = emptyList())
    val sortedStatuses = statuses.sortedByDescending { it.isUnlocked }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Game Center") },
                navigationIcon = {
                    IconNavigation(onBack)
                },
                actions = {
                    BackupButtons(
                        dbConfigs = backupAgent.dbConfigs,
                        datastoreNames = backupAgent.datastoreNames,
                        prefNames = backupAgent.prefNames,
                        extraFiles = backupAgent.extraFiles
                    )
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(sortedStatuses, key = { it.achievement.id }) { status ->
                AchievementItem(status)
            }
        }
    }
}

@Composable
fun AchievementItem(status: com.vayunmathur.library.util.AchievementStatus) {
    val achievement = status.achievement
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (status.isUnlocked) 
                MaterialTheme.colorScheme.secondaryContainer 
            else 
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(id = android.R.drawable.btn_star_big_on),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = if (status.isUnlocked) Color(0xFFFFD700) else Color.Gray
            )
            Column(
                modifier = Modifier
                    .padding(start = 16.dp)
                    .weight(1f)
            ) {
                Text(
                    text = if (achievement.isSecret && !status.isUnlocked) "???" else achievement.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (achievement.isSecret && !status.isUnlocked) "Keep playing to reveal this achievement" else achievement.description,
                    style = MaterialTheme.typography.bodySmall
                )
                
                if (achievement.targetProgress > 1) {
                    val displayProgress = if (status.isUnlocked) achievement.targetProgress else status.progress
                    val progressRatio = (displayProgress.toFloat() / achievement.targetProgress.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progressRatio },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Text(
                        text = if (status.isUnlocked) "Completed!" else "$displayProgress / ${achievement.targetProgress}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }
        }
    }
}
