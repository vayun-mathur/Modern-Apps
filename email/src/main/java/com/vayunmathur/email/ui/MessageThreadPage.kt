package com.vayunmathur.email.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.email.platform.EmailNotifications
import com.vayunmathur.email.platform.EmailViewModel

@Composable
fun MessageThreadPage(
    viewModel: EmailViewModel,
    accountEmail: String,
    threadId: String,
    onBack: () -> Unit,
    onReply: (String, String, String?) -> Unit,
    onForward: (String, String?) -> Unit,
    onCompose: (String, String) -> Unit
) {
    val messages by viewModel.getThread(accountEmail, threadId).collectAsStateWithLifecycle(emptyList())
    val context = LocalContext.current
    // Cancel-on-read: opening the thread clears its notifications from the shade.
    LaunchedEffect(messages) {
        if (messages.isNotEmpty()) EmailNotifications.cancelForMessages(context, messages)
    }
    MessageThreadScreen(messages = messages, actions = viewModel, threadId = threadId, onBack = onBack, onReply = onReply, onForward = onForward, onCompose = onCompose)
}
