package com.vayunmathur.securemail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.securemail.data.MailFolder
import com.vayunmathur.securemail.platform.MailViewModel
import com.vayunmathur.securemail.platform.setRead
import com.vayunmathur.securemail.platform.restoreFromTrash
import com.vayunmathur.securemail.platform.moveToTrash
import com.vayunmathur.securemail.platform.moveTo
import com.vayunmathur.securemail.platform.markRead
import com.vayunmathur.securemail.platform.deleteForever
import com.vayunmathur.securemail.ui.FolderActions
import com.vayunmathur.securemail.ui.MailBottomBar
import com.vayunmathur.securemail.ui.selectTab
import com.vayunmathur.securemail.ui.outEntry
import com.vayunmathur.securemail.ui.mainEntries
import com.vayunmathur.securemail.ui.currentTab
import com.vayunmathur.securemail.ui.composerEntry
import com.vayunmathur.securemail.ui.MailTab

@Composable
fun Navigation(viewModel: MailViewModel, initialRoute: Route? = null) {
    val state = viewModel.state
    val context = LocalContext.current
    val outbox by viewModel.outboxCount.collectAsState(0)
    // The stack follows the account flag, not navigation events: once an address
    // is claimed there is no going back to onboarding, and a locked key gates
    // everything. reset() rather than add() for the same reason as backup/.
    val start = when {
        !state.onboarded -> Route.Onboarding
        state.locked -> Route.Lock
        else -> Route.Inbox(MailFolder.INBOX)
    }
    val backStack = rememberNavBackStack<Route>(start)

    LaunchedEffect(state.onboarded, state.locked) {
        val target = when {
            !state.onboarded -> Route.Onboarding
            state.locked -> Route.Lock
            backStack.last() == Route.Onboarding || backStack.last() == Route.Lock -> Route.Inbox(MailFolder.INBOX)
            else -> return@LaunchedEffect
        }
        if (backStack.last() != target) backStack.reset(target)
    }

    LaunchedEffect(initialRoute) {
        if (initialRoute != null) backStack.add(initialRoute)
    }

    // Keyed on backStack too: the stack is keyed on the start route, which moves
    // Onboarding -> Lock -> Inbox while settings load, so it is replaced during
    // startup. Callbacks holding the first stack would push onto a stack that
    // is no longer displayed (taps on a message silently did nothing).
    val folderActions = remember(viewModel, backStack) {
        FolderActions(
            onOpen = { backStack.add(Route.Reader(it)) },
            onCompose = { backStack.add(Route.Composer()) },
            onSetRead = viewModel::setRead,
            onTrash = viewModel::moveToTrash,
            onMoveTo = viewModel::moveTo,
            onRestore = viewModel::restoreFromTrash,
            onDeleteForever = viewModel::deleteForever,
            onEmptyTrash = viewModel::emptyTrash,
            onConsumeMessage = viewModel::dismissMessages,
            onRetry = viewModel::refresh,
        )
    }

    MainNavigation(
        backStack,
        bottomBar = {
            currentTab(backStack.last())?.let { tab ->
                MailBottomBar(selected = tab, outboxCount = outbox) { selectTab(it, backStack) }
            }
        },
    ) {
        mainEntries(viewModel, backStack, state, folderActions, context)
        composerEntry(viewModel, backStack)
        outEntry(viewModel, backStack)
    }
}
