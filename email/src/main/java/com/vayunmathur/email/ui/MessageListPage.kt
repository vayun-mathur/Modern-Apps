package com.vayunmathur.email.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.email.data.EmailPreview
import com.vayunmathur.email.platform.EmailViewModel
import com.vayunmathur.email.platform.MessageListUiState

@Composable
fun MessageListPage(
    viewModel: EmailViewModel,
    onMessageClick: (EmailPreview) -> Unit,
    onComposeClick: () -> Unit,
    onOpenDrawer: () -> Unit
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle(emptyList())
    val selectedAccountEmail by viewModel.selectedAccountEmail.collectAsStateWithLifecycle()
    val selectedFolderName by viewModel.selectedFolderName.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedUids by viewModel.selectedMessageUids.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val syncProgress by viewModel.syncProgress.collectAsStateWithLifecycle()
    val aiSummary by viewModel.aiSummary.collectAsStateWithLifecycle()
    val aiSummaryLoading by viewModel.aiSummaryLoading.collectAsStateWithLifecycle()

    MessageListScreen(
        state = MessageListUiState(
            messages = messages,
            selectedAccountEmail = selectedAccountEmail,
            selectedFolderName = selectedFolderName,
            searchQuery = searchQuery,
            selectedUids = selectedUids,
            isSyncing = isSyncing,
            syncProgress = syncProgress,
            aiSummary = aiSummary,
            aiSummaryLoading = aiSummaryLoading,
        ),
        actions = viewModel,
        onMessageClick = onMessageClick,
        onComposeClick = onComposeClick,
        onOpenDrawer = onOpenDrawer,
    )
}
