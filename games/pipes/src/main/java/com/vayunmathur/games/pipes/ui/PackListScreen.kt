package com.vayunmathur.games.pipes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.pipes.R
import com.vayunmathur.games.pipes.platform.DailyProgress
import com.vayunmathur.games.pipes.platform.PackProgress
import com.vayunmathur.library.ui.game.DailyChallengeCard
import com.vayunmathur.library.ui.game.GameTopBarActions
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.DetailLazyColumn
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackListScreen(packs: List<PackProgress>, onOpenPack: (Int) -> Unit, onOpenSettings: () -> Unit, onOpenGameCenter: () -> Unit, daily: DailyProgress? = null, onOpenDaily: () -> Unit = {}) {
    DetailLazyColumn(title = stringResource(R.string.pack_selector), actions = { GameTopBarActions(onOpenGameCenter = onOpenGameCenter, onOpenSettings = onOpenSettings) }) {
        daily?.let { item { DailyChallengeCard(day = it.day, completed = it.completed, total = it.total, streak = it.streak, onOpen = onOpenDaily) } }
        itemsIndexed(packs) { index, pack -> Card(Modifier.clickable { onOpenPack(index) }, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) { Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column { Text(pack.name, style = MaterialTheme.typography.headlineMedium); Text(pack.shape.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) }; Text("${pack.completed}/${pack.total}", style = MaterialTheme.typography.titleMedium) } } }
    }
}
