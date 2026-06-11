package com.vayunmathur.games

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.data.AchievementEntity
import com.vayunmathur.games.data.GameHubDatabase
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.buildDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GameInfo(
    val id: String,
    val name: String,
    val packageName: String
)

val ALL_GAMES = listOf(
    GameInfo("chess", "Chess", "com.vayunmathur.games.chess"),
    GameInfo("solitaire", "Solitaire", "com.vayunmathur.games.solitaire"),
    GameInfo("pipes", "Pipes", "com.vayunmathur.games.pipes"),
    GameInfo("unblockjam", "Unblock Jam", "com.vayunmathur.games.unblockjam"),
    GameInfo("alchemist", "Alchemist", "com.vayunmathur.games.alchemist"),
    GameInfo("wordmaker", "Word Maker", "com.vayunmathur.games.wordmaker")
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                GameHubApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameHubApp() {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Games", "Achievements", "Stats")

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Games Hub") })
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, title ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = {
                            Icon(
                                painterResource(
                                    when (index) {
                                        0 -> android.R.drawable.ic_menu_slideshow
                                        1 -> android.R.drawable.btn_star_big_on
                                        else -> android.R.drawable.ic_menu_sort_by_size
                                    }
                                ),
                                contentDescription = title
                            )
                        },
                        label = { Text(title) }
                    )
                }
            }
        }
    ) { padding ->
        when (selectedTab) {
            0 -> GamesGrid(Modifier.padding(padding))
            1 -> AchievementsView(Modifier.padding(padding))
            2 -> StatsView(Modifier.padding(padding))
        }
    }
}

@Composable
fun GamesGrid(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(ALL_GAMES) { game ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val intent = context.packageManager.getLaunchIntentForPackage(game.packageName)
                        if (intent != null) {
                            context.startActivity(intent)
                        }
                    },
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        painterResource(android.R.drawable.ic_menu_slideshow),
                        contentDescription = game.name,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        game.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun AchievementsView(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var achievements by remember { mutableStateOf<List<AchievementEntity>>(emptyList()) }
    var selectedGame by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedGame) {
        withContext(Dispatchers.IO) {
            val db = buildDatabase<GameHubDatabase>(context)
            achievements = if (selectedGame != null) {
                db.achievementDao().getByGame(selectedGame!!)
            } else {
                db.achievementDao().getAll()
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Card(
                modifier = Modifier.clickable { selectedGame = null },
                colors = CardDefaults.cardColors(
                    containerColor = if (selectedGame == null) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text("All", Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
            ALL_GAMES.forEach { game ->
                Card(
                    modifier = Modifier.clickable { selectedGame = game.id },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selectedGame == game.id) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(game.name, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(achievements) { entity ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (entity.unlocked) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painterResource(android.R.drawable.btn_star_big_on),
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = if (entity.unlocked) Color(0xFFFFD700) else Color.Gray
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entity.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(entity.description, style = MaterialTheme.typography.bodySmall)
                            val gameName = ALL_GAMES.find { it.id == entity.gameId }?.name ?: entity.gameId
                            Text(gameName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatsView(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var totalAchievements by remember { mutableIntStateOf(0) }
    var unlockedAchievements by remember { mutableIntStateOf(0) }
    var gameStats by remember { mutableStateOf<Map<String, Pair<Int, Int>>>(emptyMap()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val db = buildDatabase<GameHubDatabase>(context)
            val all = db.achievementDao().getAll()
            totalAchievements = all.size
            unlockedAchievements = all.count { it.unlocked }
            gameStats = all.groupBy { it.gameId }.mapValues { (_, list) ->
                Pair(list.size, list.count { it.unlocked })
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(24.dp)) {
                    Text("Overall", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("$unlockedAchievements / $totalAchievements achievements unlocked")
                }
            }
        }

        items(ALL_GAMES) { game ->
            val stats = gameStats[game.id]
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(game.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (stats != null) {
                            Text("${stats.second} / ${stats.first} achievements", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text("No data yet", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}
