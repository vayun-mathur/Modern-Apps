package com.vayunmathur.communicate.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.communicate.data.CommunicateLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * RCS encryption state banner: locked when the MLS group exists, pending while parked
 * awaiting member keys. No-op on non-RCS lines or when the feature is off. Extracted from
 * ConversationScreen to keep that file under the length limit.
 */
@Composable
internal fun RcsEncryptionBanner(
    line: CommunicateLine,
    remoteId: String?,
    address: String,
    refresh: Int,
    modifier: Modifier = Modifier,
) {
    if (line != CommunicateLine.Rcs || !com.vayunmathur.communicate.data.rcs.RcsFeature.enabled) return
    val context = LocalContext.current
    val convoId = remoteId?.takeIf { it.isNotBlank() } ?: address
    var encryptedState by remember(convoId, refresh) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(line, convoId, refresh) {
        encryptedState = try {
            withContext(Dispatchers.IO) {
                com.vayunmathur.communicate.data.rcs.e2e.RcsE2E.groupIdFor(context, convoId) != null
            }
        } catch (_: Throwable) {
            null
        }
    }
    when (encryptedState) {
        true -> EncryptedChatBanner(pending = false, modifier = modifier)
        false -> {
            val isPending = try {
                com.vayunmathur.communicate.data.rcs.e2e.RcsPendingGroups.isPending(convoId)
            } catch (_: Throwable) {
                false
            }
            if (isPending) EncryptedChatBanner(pending = true, modifier = modifier)
        }
        null -> {}
    }
}
