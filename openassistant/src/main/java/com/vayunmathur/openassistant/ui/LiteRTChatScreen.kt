package com.vayunmathur.openassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppBarAlignment
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconMenu
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.SnackbarHost
import com.vayunmathur.library.ui.SnackbarHostState
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.openassistant.R
import com.vayunmathur.openassistant.domain.a2uiSurfaceId
import com.vayunmathur.openassistant.util.ChatActions
import com.vayunmathur.openassistant.util.ChatUiState

/**
 * The chat itself, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 * Split from LiteRTChatUi.kt to satisfy the one-public-composable-per-file lint rule.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    actions: ChatActions,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
    }

    AppScaffold(
        title = {
            val newConv = stringResource(R.string.new_conversation)
            Text(state.title ?: newConv, fontWeight = FontWeight.Bold)
        },
        alignment = AppBarAlignment.Center,
        actions = {
            IconButton({ actions.openSettings() }) { IconSettings() }
            if (state.showNewChatButton) IconButton({ actions.newConversation() }) { IconAdd() }
        },
        navigationIcon = { if (state.showConversationsButton) IconButton({ actions.openConversations() }) { IconMenu() } },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            ChatInput(
                Modifier.padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                inputText = state.inputText,
                onTextChange = { actions.setInputText(it) },
                selectedImageUris = state.attachments,
                isRecording = state.isRecording,
                onAddImage = { actions.addImage() },
                onRecord = { actions.record() },
                onSend = { actions.send() },
                onCancelMedia = { actions.cancelMedia() },
                onRemoveImage = { actions.removeImage(it) }
            )
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        SelectionContainer {
            LazyColumn(state = listState, modifier = Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(state.messages, key = { it.id }) { ChatBubble(it, state.surfaces[a2uiSurfaceId(it.id)]) }
            }
        }
    }
}
