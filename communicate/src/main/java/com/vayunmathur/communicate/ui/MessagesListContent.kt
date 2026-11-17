package com.vayunmathur.communicate.ui

import android.Manifest
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.CommunicateRepository
import com.vayunmathur.communicate.data.SmsThread
import com.vayunmathur.communicate.data.createEncryptedRcsGroup
import com.vayunmathur.communicate.data.createSignalGroup
import com.vayunmathur.communicate.data.createWhatsAppGroup
import com.vayunmathur.communicate.data.deleteConversation
import com.vayunmathur.communicate.data.rcs.RcsSessionManager
import com.vayunmathur.communicate.data.rcs.hostGroupFocus
import com.vayunmathur.communicate.data.getOrCreateSmsGroupThreadId
import com.vayunmathur.communicate.data.getOrCreateSmsThreadId
import com.vayunmathur.communicate.data.isWhatsAppConnected
import com.vayunmathur.communicate.data.loadSmsThreadsMerged
import com.vayunmathur.communicate.data.stableThreadId
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.ui.IconSms
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The thread list, stateless: rows plus the delete-confirm dialog. Split out so
 * screenshot previews can render threads without the SMS provider, the polling
 * loop, or the permission gates above.
 */
@Composable
fun MessagesListContent(
    threads: List<SmsThread>,
    modifier: Modifier = Modifier,
    onOpenThread: (SmsThread) -> Unit,
    onDeleteThread: (SmsThread) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<SmsThread?>(null) }
    LazyColumn(
        modifier = modifier
            .fillMaxSize(),
        contentPadding = PaddingValues(bottom = 88.dp),
    ) {
        items(threads, key = { it.threadId }) { thread ->
            MessageThreadRow(
                thread = thread,
                onClick = { onOpenThread(thread) },
                onDelete = { pendingDelete = thread },
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        }
    }
    val toDelete = pendingDelete
    if (toDelete != null) {
        com.vayunmathur.library.ui.ConfirmDialog(
            title = stringResource(R.string.delete_conversation_title),
            message = stringResource(R.string.delete_conversation_message),
            confirmLabel = stringResource(com.vayunmathur.library.ui.R.string.delete),
            dismissLabel = stringResource(com.vayunmathur.library.ui.R.string.cancel),
            destructive = true,
            onConfirm = {
                pendingDelete = null
                onDeleteThread(toDelete)
            },
            onDismiss = { pendingDelete = null },
        )
    }
}
