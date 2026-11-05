package com.vayunmathur.communicate.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.VoicemailEntry
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.CommunicateRepository
import com.vayunmathur.communicate.data.deleteVoicemailEntry
import com.vayunmathur.communicate.data.loadVoicemailsMerged
import com.vayunmathur.communicate.data.markVoicemailEntryRead
import com.vayunmathur.communicate.data.markVoicemailRead
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.IconVoicemail
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.rememberPermissionRequest
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Carrier visual-voicemail inbox tab.
 *
 * Same slice as [CallLogsScreen]: [AppScaffold] + [DefaultDialerGate] (the
 * provider enforces default-dialer for reads) + [PermissionGate] on
 * READ_CALL_LOG + [produceState] loader + stateless [VoicemailContent].
 *
 * WRITE_CALL_LOG is requested lazily — only when the user expands a row
 * (mark-read) or deletes — so the tab stays fully usable read-only without it.
 * Google Voice voicemails join this list in Phase 2; until then the tab is
 * carrier rows only.
 */
@Composable
fun VoicemailScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    var expandedId by remember { mutableLongStateOf(-1L) }
    // #735 (same as MessagesScreen): PermissionGate reads checkSelfPermission
    // during composition with no resume trigger, so granting from Settings
    // never recomposes the gate. resumeRevision forces a re-check + reload.
    val resumeRevision = rememberResumeRevision()
    val markReadFailed = stringResource(R.string.voicemail_mark_read_failed)
    val writeNeeded = stringResource(R.string.permission_write_call_log_message)

    val requestWrite = rememberPermissionRequest(Manifest.permission.WRITE_CALL_LOG) {}
    fun hasWrite(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.WRITE_CALL_LOG,
    ) == PackageManager.PERMISSION_GRANTED

    fun markRead(entry: VoicemailEntry) {
        if (entry.isRead) return
        if (!hasWrite()) {
            AppMessages.show(writeNeeded)
            requestWrite()
            return
        }
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                CommunicateRepository.markVoicemailRead(context, entry, read = true)
            }
            if (ok) tick++ else AppMessages.show(markReadFailed)
        }
    }

    AppScaffold(
        title = stringResource(R.string.voicemail_title),
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        DefaultDialerGate(modifier = Modifier.padding(padding)) { roleRevision ->
            PermissionGate(
                permission = Manifest.permission.READ_CALL_LOG,
                message = stringResource(R.string.permission_voicemail_message),
                modifier = Modifier.padding(padding),
            ) { permissionRevision ->
                val voicemails = produceState<List<VoicemailEntry>?>(
                    initialValue = null,
                    roleRevision,
                    permissionRevision,
                    resumeRevision,
                    tick,
                ) {
                    value = CommunicateRepository.loadVoicemailsMerged(context)
                }
                when (val rows = voicemails.value) {
                    null -> com.vayunmathur.library.ui.LoadingState(Modifier.padding(padding))
                    emptyList<VoicemailEntry>() -> EmptyState(
                        title = stringResource(R.string.empty_voicemails),
                        message = stringResource(R.string.empty_voicemails_setup),
                        icon = { IconVoicemail() },
                        modifier = Modifier.padding(padding),
                    )
                    else -> {
                        VoicemailContent(
                            entries = rows,
                            expandedId = expandedId.takeIf { it >= 0 },
                            onToggleExpand = { entry ->
                                expandedId = if (expandedId == entry.id) -1L else entry.id
                                if (expandedId >= 0) markRead(entry)
                            },
                            onPlayed = ::markRead,
                            onDelete = { toDelete ->
                                // GV rows archive through the GV API — no call-log write needed.
                                if (toDelete.line == CommunicateLine.GoogleVoice) {
                                    scope.launch {
                                        val ok = CommunicateRepository.deleteVoicemailEntry(
                                            context,
                                            toDelete,
                                        )
                                        if (ok) {
                                            if (expandedId == toDelete.id) expandedId = -1L
                                            tick++
                                        } else {
                                            AppMessages.show(
                                                context.getString(R.string.delete_failed),
                                            )
                                        }
                                    }
                                } else if (!hasWrite()) {
                                    AppMessages.show(writeNeeded)
                                    requestWrite()
                                } else {
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) {
                                            CommunicateRepository.deleteVoicemailEntry(
                                                context,
                                                toDelete,
                                            )
                                        }
                                        if (ok) {
                                            if (expandedId == toDelete.id) expandedId = -1L
                                            tick++
                                        } else {
                                            AppMessages.show(
                                                context.getString(R.string.delete_failed),
                                            )
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.padding(padding),
                        )
                    }
                }
            }
        }
    }
}
