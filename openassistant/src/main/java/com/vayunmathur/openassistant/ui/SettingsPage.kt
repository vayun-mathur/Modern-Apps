package com.vayunmathur.openassistant.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.vayunmathur.library.ui.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.BackupButtons
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.room.SqlCipherDbCodec
import com.vayunmathur.library.util.DatabaseHelper
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.openassistant.Route
import com.vayunmathur.openassistant.data.Memory
import com.vayunmathur.openassistant.util.AssistantViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(backStack: NavBackStack<Route>, viewModel: AssistantViewModel) {
    val memories by viewModel.memories.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconNavigation(backStack)
                },
                actions = {
                    val pass = remember { DatabaseHelper(context).getPassphrase() }
                    BackupButtons(
                        dbConfigs = listOf("passwords-db" to pass),
                        dbCodec = SqlCipherDbCodec,
                        extraFiles = emptyList()
                    )
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    text = "Memories",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            if (memories.isEmpty()) {
                item {
                    Text("No memories yet.", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                items(memories, key = { it.id }) { memory ->
                    MemoryItem(memory, onDelete = { viewModel.deleteMemory(memory) })
                }
            }
        }
    }
}

@Composable
fun MemoryItem(memory: Memory, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = memory.content,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium
            )
            IconButton(onClick = onDelete) {
                IconDelete()
            }
        }
    }
}
