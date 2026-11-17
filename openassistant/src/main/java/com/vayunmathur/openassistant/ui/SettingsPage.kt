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

/** Binds [AssistantViewModel] and the nav back stack to the stateless [SettingsScreen]. */
@Composable
fun SettingsPage(backStack: NavBackStack<Route>, viewModel: AssistantViewModel) {
    val memories by viewModel.memories.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ds = remember(context) { DataStoreUtils.getInstance(context) }

    var systemPrompt by remember {
        mutableStateOf(
            ds.getString(InferenceService.KEY_SYSTEM_PROMPT)
                ?: InferenceService.DEFAULT_SYSTEM_PROMPT
        )
    }

    SettingsScreen(
        state = SettingsUiState(memories = memories, systemPrompt = systemPrompt),
        actions = object : SettingsActions {
            override fun back() = backStack.pop()
            override fun deleteMemory(memory: Memory) = viewModel.deleteMemory(memory)
            override fun setSystemPrompt(prompt: String) {
                systemPrompt = prompt
                scope.launch { ds.setString(InferenceService.KEY_SYSTEM_PROMPT, prompt) }
            }
            override fun resetSystemPrompt() {
                systemPrompt = InferenceService.DEFAULT_SYSTEM_PROMPT
                scope.launch {
                    ds.setString(
                        InferenceService.KEY_SYSTEM_PROMPT,
                        InferenceService.DEFAULT_SYSTEM_PROMPT
                    )
                }
            }
        },
    )
}


