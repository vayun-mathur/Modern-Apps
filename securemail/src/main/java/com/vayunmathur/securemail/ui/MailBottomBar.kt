package com.vayunmathur.securemail.ui

import androidx.compose.runtime.Composable
import com.vayunmathur.library.ui.Badge
import com.vayunmathur.library.ui.BadgedBox
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconInbox
import com.vayunmathur.library.ui.IconMail
import com.vayunmathur.library.ui.IconSend
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.IconWarning
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.BottomNavBarItem
import com.vayunmathur.securemail.data.MailFolder

/** Top-level destinations: the four vault folders, the send queue, and settings. */
enum class MailTab { INBOX, SENT, SPAM, OUTBOX, TRASH, SETTINGS }

/** Bottom navigation across the vault folders, Outbox (badged with its queue) and Settings. */
@Composable
fun MailBottomBar(selected: MailTab, outboxCount: Int, onSelect: (MailTab) -> Unit) {
    BottomNavBar {
        BottomNavBarItem(selected == MailTab.INBOX, { onSelect(MailTab.INBOX) }, { IconInbox() }, "Inbox")
        BottomNavBarItem(selected == MailTab.SENT, { onSelect(MailTab.SENT) }, { IconSend() }, "Sent")
        BottomNavBarItem(selected == MailTab.SPAM, { onSelect(MailTab.SPAM) }, { IconWarning() }, "Spam")
        BottomNavBarItem(
            selected = selected == MailTab.OUTBOX,
            onClick = { onSelect(MailTab.OUTBOX) },
            icon = {
                BadgedBox(badge = { if (outboxCount > 0) Badge { Text("$outboxCount") } }) { IconMail() }
            },
            label = "Outbox",
        )
        BottomNavBarItem(selected == MailTab.TRASH, { onSelect(MailTab.TRASH) }, { IconDelete() }, "Trash")
        BottomNavBarItem(selected == MailTab.SETTINGS, { onSelect(MailTab.SETTINGS) }, { IconSettings() }, "Settings")
    }
}

/** The tab a vault folder belongs to. */
fun MailFolder.tab(): MailTab = when (this) {
    MailFolder.INBOX -> MailTab.INBOX
    MailFolder.SENT -> MailTab.SENT
    MailFolder.SPAM -> MailTab.SPAM
    MailFolder.TRASH -> MailTab.TRASH
}
