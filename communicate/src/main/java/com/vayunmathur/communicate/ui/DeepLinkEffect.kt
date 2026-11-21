package com.vayunmathur.communicate.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.Route
import com.vayunmathur.communicate.intents.DeepLink
import com.vayunmathur.communicate.intents.PendingShare
import com.vayunmathur.library.util.NavBackStack

/** Deep links open the conversation they name, including links arriving at a running instance. */
@Composable
fun DeepLinkEffect(
    backStack: NavBackStack<Route>,
    initialDeepLink: DeepLink?,
) {
    val groupUnsupportedMessage = stringResource(R.string.deep_link_group_unsupported)
    // Keyed on the link so a second link to a running instance navigates again rather than ignored.
    LaunchedEffect(initialDeepLink) {
        when (val link = initialDeepLink) {
            is DeepLink.Conversation -> backStack.add(
                Route.Conversation(
                    threadId = link.threadId,
                    address = link.address,
                    line = link.line,
                    remoteId = link.remoteId,
                    subscriptionId = link.subscriptionId,
                    isGroup = link.isGroup,
                    groupTitle = link.groupTitle,
                    sharedText = link.body,
                    sharedAttachmentUris = link.sharedAttachmentUris,
                ),
            )
            // No conversation chosen yet: stay on the list and seed whichever
            // thread the user opens next (consumed in CommunicateNavGraph).
            is DeepLink.SharePicker -> PendingShare.store(link)
            is DeepLink.UnsupportedGroupInvite ->
                com.vayunmathur.library.util.AppMessages.show(groupUnsupportedMessage)
            null -> Unit
        }
    }
}
