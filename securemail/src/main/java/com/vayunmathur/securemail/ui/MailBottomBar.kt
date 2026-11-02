package com.vayunmathur.securemail.ui

import androidx.compose.runtime.Composable
import com.vayunmathur.library.ui.Badge
import com.vayunmathur.library.ui.BadgedBox
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconInbox
import com.vayunmathur.library.ui.IconSend
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.IconWarning
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.BottomNavBarItem
import com.vayunmathur.securemail.data.MailFolder

/** Top-level destinations. OUT groups Drafts, the Outbox queue and Sent on one page. */
enum class MailTab { INBOX, SPAM, OUT, TRASH, SETTINGS }

/** Bottom navigation: Inbox, Spam, Out (badged with queued sends), Trash, Settings. */
@Composable
fun MailBottomBar(selected: MailTab, outboxCount: Int, onSelect: (MailTab) -> Unit) {
    BottomNavBar {
        BottomNavBarItem(selected == MailTab.INBOX, { onSelect(MailTab.INBOX) }, { IconInbox() }, "Inbox")
        BottomNavBarItem(selected == MailTab.SPAM, { onSelect(MailTab.SPAM) }, { IconWarning() }, "Spam")
        BottomNavBarItem(
            selected = selected == MailTab.OUT,
            onClick = { onSelect(MailTab.OUT) },
            icon = {
                BadgedBox(badge = { if (outboxCount > 0) Badge { Text("$outboxCount") } }) { IconSend() }
            },
            label = "Out",
        )
        BottomNavBarItem(selected == MailTab.TRASH, { onSelect(MailTab.TRASH) }, { IconDelete() }, "Trash")
        BottomNavBarItem(selected == MailTab.SETTINGS, { onSelect(MailTab.SETTINGS) }, { IconSettings() }, "Settings")
    }
}

/** The tab a vault folder belongs to (Sent and Drafts live on the Out page). */
fun MailFolder.tab(): MailTab = when (this) {
    MailFolder.INBOX -> MailTab.INBOX
    MailFolder.SPAM -> MailTab.SPAM
    MailFolder.TRASH -> MailTab.TRASH
    MailFolder.SENT, MailFolder.DRAFTS -> MailTab.OUT
}
