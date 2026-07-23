package com.vayunmathur.games.hub.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.games.hub.ui.components.LevelBadge
import com.vayunmathur.games.hub.ui.components.StatCard
import com.vayunmathur.games.hub.ui.components.XpProgressBar
import com.vayunmathur.games.hub.util.XpLevelCalculator
import com.vayunmathur.games.hub.util.formatPlaytime
import com.vayunmathur.games.hub.viewmodel.GameHubViewModel
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TopAppBar

private val avatarOptions = listOf(
    "person", "stadia_controller", "sports_esports", "emoji_events", "military_tech",
    "star", "bolt", "local_fire_department", "rocket", "diamond",
    "psychology", "lightbulb", "school", "workspace_premium", "king_bed"
)

@Composable
fun ProfileScreen(viewModel: GameHubViewModel, modifier: Modifier = Modifier) {
    val profile by viewModel.profileFlow.collectAsStateWithLifecycle()
    val xp by viewModel.totalXpFlow.collectAsStateWithLifecycle()
    val level by viewModel.levelFlow.collectAsStateWithLifecycle()
    val title by viewModel.titleFlow.collectAsStateWithLifecycle()
    val crossStats by viewModel.statsFlow.collectAsStateWithLifecycle()

    var showEditDialog by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf("") }
    var selectedAvatar by remember { mutableStateOf<String?>(null) }

    if (showEditDialog && editName.isEmpty() && profile != null) {
        editName = profile?.displayName ?: ""
        selectedAvatar = profile?.avatarSymbol
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Profile") },
            actions = {
                androidx.compose.material3.IconButton(onClick = {
                    editName = profile?.displayName ?: ""
                    selectedAvatar = profile?.avatarSymbol
                    showEditDialog = true
                }) { M3Icon(Icons.Filled.Person, contentDescription = "Edit") }
            }
        )
    }) { padding ->
        LazyColumn(modifier = modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(24.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        LevelBadge(level = level, large = true)
                        Text(text = profile?.displayName ?: "Player", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(text = title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text(text = "Level $level", style = MaterialTheme.typography.labelLarge)
                        XpProgressBar(totalXp = xp, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            item { Text(text = "Stats", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard(label = "Playtime", value = formatPlaytime(crossStats.totalPlaytimeMs), modifier = Modifier.weight(1f))
                    StatCard(label = "Sessions", value = "${crossStats.totalSessions}", modifier = Modifier.weight(1f))
                }
            }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard(label = "Achievements", value = "${crossStats.totalAchievementsUnlocked}/${crossStats.totalAchievements}", modifier = Modifier.weight(1f))
                    StatCard(label = "Games", value = "${crossStats.totalGames}", modifier = Modifier.weight(1f))
                }
            }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard(label = "XP", value = "$xp", modifier = Modifier.weight(1f))
                    StatCard(label = "Best streak", value = "${crossStats.longestStreak}d", modifier = Modifier.weight(1f))
                }
            }
            item { Text(text = "Level table", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
            items((1..maxOf(level + 2, 10)).toList(), key = { it }) { lvl ->
                val lvlXp = XpLevelCalculator.xpForLevel(lvl)
                val isCurrent = lvl == level
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (isCurrent) androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    else androidx.compose.material3.CardDefaults.cardColors()
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(text = "Level $lvl — ${XpLevelCalculator.title(lvl)}${if (isCurrent) " (you)" else ""}", fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal)
                        Text(text = "$lvlXp XP", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        if (showEditDialog) {
            AlertDialog(
                onDismissRequest = { showEditDialog = false; editName = "" },
                title = { Text("Edit profile") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(value = editName, onValueChange = { editName = it }, label = { Text("Display name") }, singleLine = true)
                        Text(text = "Avatar", style = MaterialTheme.typography.labelMedium)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(avatarOptions) { sym ->
                                FilterChip(selected = selectedAvatar == sym, onClick = { selectedAvatar = if (selectedAvatar == sym) null else sym }, label = { Text(sym) })
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        if (editName.isNotBlank()) viewModel.updateDisplayName(editName.trim())
                        viewModel.updateAvatarSymbol(selectedAvatar)
                        showEditDialog = false; editName = ""
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { showEditDialog = false; editName = "" }) { Text("Cancel") } }
            )
        }
    }
}
