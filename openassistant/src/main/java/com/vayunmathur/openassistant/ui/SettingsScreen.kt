package com.vayunmathur.openassistant.ui
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.DetailLazyColumn
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TopAppBarScrollBehavior
import com.vayunmathur.library.ui.appBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.openassistant.R
import com.vayunmathur.openassistant.Route
import com.vayunmathur.openassistant.data.Memory
import com.vayunmathur.openassistant.platform.AssistantViewModel
import com.vayunmathur.openassistant.util.InferenceService
import com.vayunmathur.openassistant.ui.SettingsActions
import com.vayunmathur.openassistant.ui.SettingsUiState
import kotlinx.coroutines.launch

/**
 * The memories screen, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    val memories = state.memories

    DetailLazyColumn(
        title = stringResource(R.string.settings_title),
        onNavigateBack = { actions.back() },
        scrollBehavior = appBarScrollBehavior(),
    ) {
            item {
                Text(
                    text = stringResource(R.string.system_prompt),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            item {
                OutlinedTextField(
                    value = state.systemPrompt,
                    onValueChange = { actions.setSystemPrompt(it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.system_prompt)) },
                    minLines = 5,
                    maxLines = 12,
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = { actions.resetSystemPrompt() },
                        enabled = state.systemPrompt != InferenceService.DEFAULT_SYSTEM_PROMPT
                    ) {
                        Text(stringResource(R.string.reset_to_default))
                    }
                }
            }
            item {
                Text(
                    text = stringResource(R.string.memories),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
            }
            if (memories.isEmpty()) {
                item {
                    Text(stringResource(R.string.no_memories_yet), style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                items(memories, key = { it.id }) { memory ->
                    MemoryItem(memory, onDelete = { actions.deleteMemory(memory) })
                }
            }
    }
}
