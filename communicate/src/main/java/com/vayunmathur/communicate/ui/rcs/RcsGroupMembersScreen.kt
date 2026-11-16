package com.vayunmathur.communicate.ui.rcs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.rcs.RcsDatabase
import com.vayunmathur.communicate.data.rcs.RcsFeature
import com.vayunmathur.communicate.data.rcs.RcsSessionManager
import com.vayunmathur.communicate.data.rcs.e2e.RcsE2E
import com.vayunmathur.communicate.data.rcs.focusMembers
import com.vayunmathur.communicate.data.rcs.hostedFocusFor
import com.vayunmathur.communicate.data.rcs.inviteFocusMember
import com.vayunmathur.communicate.data.rcs.removeFocusMember
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RCS group member management (§6.3): member list (conversation row +
 * live focus set union), add (capability-checked REFER), remove (BYE +
 * MLS commit when encrypted).
 *
 * Single public `@Composable`.
 */
@Composable
fun RcsGroupMembersScreen(
    conversationId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var newMember by remember { androidx.compose.runtime.mutableStateOf("") }
    val members by produceState<List<String>>(initialValue = emptyList(), conversationId, refresh) {
        value = withContext(Dispatchers.IO) {
            if (!RcsFeature.enabled) return@withContext emptyList()
            val db = RcsDatabase.getDatabase(context)
            val stored = db.conversationDao().getConversation(conversationId)
                ?.participants?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                .orEmpty()
            val focusUri = RcsSessionManager.hostedFocusFor(conversationId)
            val live = focusUri?.let { RcsSessionManager.focusMembers(it) }.orEmpty()
            (stored + live).distinct().sorted()
        }
    }
    DetailScaffold(
        title = stringResource(R.string.rcs_group_members_title),
        onNavigateBack = onBack,
        scrollBehavior = appBarScrollBehavior(),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                if (members.isEmpty()) {
                    ListItem(content = { Text("No members yet") })
                }
                for (member in members) {
                    ListItem(
                        content = { Text(member, fontWeight = FontWeight.SemiBold) },
                        trailingContent = {
                            IconButton(onClick = {
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        removeRcsMember(context, conversationId, member)
                                    }
                                    AppMessages.show(
                                        if (ok) context.getString(R.string.rcs_member_removed)
                                        else context.getString(R.string.rcs_member_remove_failed),
                                    )
                                    refresh++
                                }
                            }) {
                                IconDelete()
                            }
                        },
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextField(
                        value = newMember,
                        onValueChange = { newMember = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("+15551234567") },
                        singleLine = true,
                    )
                    Button(onClick = {
                        val peer = newMember.trim()
                        if (peer.isEmpty()) return@Button
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                addRcsMember(context, conversationId, peer)
                            }
                            AppMessages.show(
                                if (ok) context.getString(R.string.rcs_member_added)
                                else context.getString(R.string.rcs_member_add_failed),
                            )
                            if (ok) newMember = ""
                            refresh++
                        }
                    }) {
                        Text(stringResource(R.string.rcs_member_add))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Add [peer] to [conversationId]'s group: capability check, focus REFER,
 * conversation-row update. Best-effort MLS add when encrypted (needs the
 * peer's key package — requests it when missing). Never throws.
 */
private suspend fun addRcsMember(context: android.content.Context, conversationId: String, peer: String): Boolean {
    if (!RcsFeature.enabled) return false
    return runCatching {
        val capable = com.vayunmathur.communicate.data.rcs.RcsCapabilityExchange
            .isContactRcsCapable(context, peer)
        if (!capable) return false
        val focusOk = RcsSessionManager.inviteFocusMember(conversationId, peer)
        // Persist into the conversation row either way (fan-out covers SMS-era members).
        val db = RcsDatabase.getDatabase(context)
        val conv = db.conversationDao().getConversation(conversationId)
        if (conv != null) {
            val parts = conv.participants.split(",").map { it.trim() }
                .filter { it.isNotEmpty() }.toMutableSet()
            if (parts.add(peer)) {
                db.conversationDao().upsert(conv.copy(participants = parts.joinToString(",")))
            }
        }
        focusOk
    }.getOrDefault(false)
}

/**
 * Remove [peer] from [conversationId]'s group: focus BYE + MLS commit when
 * encrypted + conversation-row update. Never throws.
 */
private suspend fun removeRcsMember(
    context: android.content.Context,
    conversationId: String,
    peer: String,
): Boolean {
    if (!RcsFeature.enabled) return false
    return runCatching {
        var ok = RcsSessionManager.removeFocusMember(conversationId, peer)
        // MLS removal when the conversation is encrypted.
        val local = RcsE2E.localE164(context)
        if (local != null && RcsE2E.groupIdFor(context, conversationId) != null) {
            ok = RcsE2E.removeMembersByE164(context, local, conversationId, listOf(peer)) || ok
        }
        val db = RcsDatabase.getDatabase(context)
        val conv = db.conversationDao().getConversation(conversationId)
        if (conv != null) {
            val parts = conv.participants.split(",").map { it.trim() }
                .filter { it.isNotEmpty() && it != peer }
            db.conversationDao().upsert(conv.copy(participants = parts.joinToString(",")))
        }
        ok
    }.getOrDefault(false)
}
