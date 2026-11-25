package com.vayunmathur.email

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.email.data.EmailAccount
import com.vayunmathur.email.data.EmailFolder
import com.vayunmathur.email.data.EmailMessage
import com.vayunmathur.email.data.EmailPreview
import com.vayunmathur.email.ui.AddAccountFormScreen
import com.vayunmathur.email.ui.AddAccountScreen
import com.vayunmathur.email.ui.EmailDrawerContent
import com.vayunmathur.email.ui.MessageListScreen
import com.vayunmathur.email.ui.MessageThreadScreen
import com.vayunmathur.email.platform.MessageListActions
import com.vayunmathur.email.platform.MessageListUiState
import com.vayunmathur.email.platform.MessageThreadActions
import com.vayunmathur.library.ui.DrawerValue
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.ModalNavigationDrawer
import com.vayunmathur.library.ui.rememberDrawerState

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/** Expanded desktop/tablet window — comfortably above the 840dp expanded-width threshold. */
private const val EXPANDED = "spec:width=1280dp,height=800dp,dpi=240"

/**
 * Store listing images for `:email`, rendered from Compose previews instead of from an
 * instrumented test on a device.
 *
 * `./gradlew :email:metadata` renders these and copies the PNGs into
 * `metadata_data/photos/email/`, where `release.sh` picks them up.
 *
 * Two things to keep in mind when editing:
 *
 *  - Order matters, and it comes from the function names. The generated PNG filenames
 *    embed the function name, so `Preview1Inbox`/`Preview2Conversation`/... sort into
 *    listing order no matter how the plugin formats the rest of the filename. Renumber the
 *    functions if you reorder the listing.
 *  - Everything must be a literal. These render with no ViewModel, no database and no mail
 *    server, so the messages below are the whole input — which is also what keeps the
 *    output reproducible from a clean checkout, and is why no real account is involved.
 *  - Each preview needs @PreviewTest as well as @Preview. @Preview alone renders in
 *    Studio but is not collected as a screenshot test, and the build fails with the
 *    unhelpful "did not discover any tests".
 *  - The previews must be members of a class, not top-level functions. Android Studio
 *    renders top-level previews happily, but the screenshot engine discovers previews as
 *    JUnit tests and needs a real class to attach them to — top-level functions land in a
 *    synthetic `…Kt` facade and are silently skipped.
 *
 * Coverage: `MessageList` (inbox/search/drawer), `MessageThread` (detail) and
 * `AddAccount` + `AddAccountForm` on phone, plus the thread on large-tablet.
 * Exceptions: `Composer` hosts an `AndroidView` HTML editor (WebView) which
 * Layoutlib cannot render; `Outbox`, `Drafts` and `Settings` have no stateless
 * slice (EmailViewModel + Room/DataStore), and `EmlViewer` reads a SAF URI's
 * bytes — none can be shown honestly from literals.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-inbox", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1Inbox() {
        DynamicTheme(darkTheme = true) {
            MessageListScreen(
                // selectedAccountEmail stays null so this is the unified inbox, which is
                // the arrangement the coloured per-account band exists for.
                state = MessageListUiState(messages = INBOX),
                actions = MessageListActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-conversation", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2Conversation() {
        DynamicTheme(darkTheme = true) {
            MessageThreadScreen(
                messages = THREAD,
                actions = MessageThreadActions.Noop,
                threadId = "thread-review",
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-search", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview3Search() {
        DynamicTheme(darkTheme = true) {
            MessageListScreen(
                state = MessageListUiState(
                    messages = SEARCH_HITS,
                    searchQuery = "invoice",
                    aiSummary = "Three invoices arrived this month: Fastmail and the " +
                        "domain renewal are both paid, and the studio invoice for £480 " +
                        "is still due on 8 August.",
                ),
                actions = MessageListActions.Noop,
                initialSearching = true,
            )
        }
    }

    @PreviewTest
    @Preview(name = "4-add-account", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview4AddAccount() {
        DynamicTheme(darkTheme = true) {
            AddAccountScreen(onBack = {}, onPickProvider = {})
        }
    }

    @PreviewTest
    @Preview(name = "8-inbox-expanded", device = EXPANDED, group = "large-tablet", showSystemUi = true)
    @Composable
    fun Preview8InboxExpanded() {
        DynamicTheme(darkTheme = true) {
            MessageListScreen(
                state = MessageListUiState(messages = INBOX),
                actions = MessageListActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "7-add-account-form", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview7AddAccountForm() {
        DynamicTheme(darkTheme = true) {
            AddAccountFormScreen(providerId = "gmail", onBack = {}, onAccountAdded = {})
        }
    }

    @PreviewTest
    @Preview(name = "5-conversation-expanded", device = EXPANDED, group = "large-tablet", showSystemUi = true)
    @Composable
    fun Preview5ConversationExpanded() {
        DynamicTheme(darkTheme = true) {
            MessageThreadScreen(
                messages = THREAD,
                actions = MessageThreadActions.Noop,
                threadId = "thread-review",
            )
        }
    }

    @PreviewTest
    @Preview(name = "6-drawer", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview6Drawer() {
        DynamicTheme(darkTheme = true) {
            ModalNavigationDrawer(
                drawerState = rememberDrawerState(DrawerValue.Open),
                drawerContent = {
                    EmailDrawerContent(
                        accounts = listOf(
                            EmailAccount(email = ME),
                            EmailAccount(email = WORK),
                        ),
                        selectedAccountEmail = WORK,
                        folders = listOf(
                            EmailFolder(accountEmail = WORK, fullName = "INBOX", name = "INBOX"),
                            EmailFolder(accountEmail = WORK, fullName = "Sent", name = "Sent"),
                            EmailFolder(accountEmail = WORK, fullName = "Drafts", name = "Drafts"),
                        ),
                        selectedFolderName = "INBOX",
                        outboxCount = 2,
                        onSelectAccount = {},
                        onSelectFolder = {},
                        onOpenOutbox = {},
                        onOpenSystem = {},
                        onLogout = {},
                        onAddAccount = {},
                    )
                },
            ) {
                MessageListScreen(
                    state = MessageListUiState(messages = INBOX),
                    actions = MessageListActions.Noop,
                )
            }
        }
    }
}

private const val ME = "sam.whitfield@fastmail.com"
private const val WORK = "s.whitfield@northgate.co.uk"

/**
 * Sample messages, hand-built rather than fetched.
 *
 * [EmailMessage.date] is a `java.util.Date.toString()`, because that is what the IMAP
 * fetch stores; the list shows its first word and the conversation view everything up to
 * the last colon, so the format matters to how the shots read.
 */
private fun message(
    account: String,
    id: Long,
    subject: String,
    from: String,
    date: String,
    body: String,
    isRead: Boolean = true,
    hasAttachments: Boolean = false,
    threadId: String? = null,
) = EmailMessage(
    accountEmail = account,
    folderName = "INBOX",
    id = id,
    threadId = threadId,
    subject = subject,
    from = from,
    to = account,
    date = date,
    body = body,
    isRead = isRead,
    hasAttachments = hasAttachments,
)

/** Same as [message] but as the light [EmailPreview] projection the list screen now
 *  consumes; [body] becomes the stored [EmailPreview.peekContent] snippet directly. */
private fun preview(
    account: String,
    id: Long,
    subject: String,
    from: String,
    date: String,
    body: String,
    isRead: Boolean = true,
    hasAttachments: Boolean = false,
    threadId: String? = null,
) = EmailPreview(
    accountEmail = account,
    folderName = "INBOX",
    id = id,
    threadId = threadId,
    subject = subject,
    from = from,
    date = date,
    isRead = isRead,
    hasAttachments = hasAttachments,
    peekContent = body,
)

private val INBOX = listOf(
    preview(
        account = WORK,
        id = 5121,
        subject = "Re: Northgate rebrand — final round",
        from = "Priya Raman <priya.raman@northgate.co.uk>",
        date = "Tue Jul 28 09:14:02 GMT 2026",
        body = "The board signed off on option B this morning. Can you get the " +
            "updated wordmark over to the print shop before Thursday?",
        isRead = false,
    ),
    preview(
        account = ME,
        id = 4407,
        subject = "Your invoice for July is ready",
        from = "Fastmail Billing <billing@fastmail.com>",
        date = "Tue Jul 28 07:02:40 GMT 2026",
        body = "Invoice FM-2026-07 for £4.20 has been paid with the card ending 4419. " +
            "No action is needed.",
        isRead = false,
        hasAttachments = true,
    ),
    preview(
        account = WORK,
        id = 5118,
        subject = "Standup notes, week 31",
        from = "Tomasz Nowak <tomasz@northgate.co.uk>",
        date = "Mon Jul 27 17:48:11 GMT 2026",
        body = "Shipping the caching change today. Blocked on the staging certificate, " +
            "which ops say lands tomorrow morning.",
    ),
    preview(
        account = ME,
        id = 4399,
        subject = "Trail run on Saturday?",
        from = "Ellie Grant <ellie.grant@gmail.com>",
        date = "Mon Jul 27 12:30:05 GMT 2026",
        body = "Thinking of doing the reservoir loop at eight before it gets hot. " +
            "Bring the good water bottle this time.",
    ),
    preview(
        account = WORK,
        id = 5109,
        subject = "Contract renewal — signed copy attached",
        from = "Hannah Boyce <h.boyce@ashgrovelegal.com>",
        date = "Mon Jul 27 10:05:52 GMT 2026",
        body = "Both parties have now signed. The countersigned PDF is attached for " +
            "your records.",
        hasAttachments = true,
    ),
    preview(
        account = ME,
        id = 4382,
        subject = "Your package is out for delivery",
        from = "Parcel Tracking <no-reply@parceltrack.example>",
        date = "Sun Jul 26 06:41:19 GMT 2026",
        body = "Your parcel is on the van and should arrive between 11:00 and 15:00.",
    ),
    preview(
        account = WORK,
        id = 5094,
        subject = "Q3 hiring plan",
        from = "Dev Chaudhary <dev@northgate.co.uk>",
        date = "Fri Jul 24 15:22:37 GMT 2026",
        body = "Two more engineers approved for Q3. I've put the draft role descriptions " +
            "in the shared drive if you want to redline them.",
    ),
)

/** A short reply chain, including quoted history so the collapse control is visible. */
private val THREAD = listOf(
    message(
        account = WORK,
        id = 5107,
        subject = "Northgate rebrand — final round",
        from = "Priya Raman <priya.raman@northgate.co.uk>",
        date = "Mon Jul 27 14:02:18 GMT 2026",
        body = "Sam — three options attached from the studio. My preference is B: it " +
            "holds up at favicon size and the counters don't fill in when it's " +
            "embroidered.\n\nWhat do you think?",
        threadId = "thread-review",
    ),
    message(
        account = WORK,
        id = 5119,
        subject = "Re: Northgate rebrand — final round",
        from = "Sam Whitfield <s.whitfield@northgate.co.uk>",
        date = "Tue Jul 28 09:14:02 GMT 2026",
        body = "Agreed on B. One change: the descender on the g is a hair too long at " +
            "small sizes, so I'd shorten it by about five percent before we send " +
            "anything to print.\n\n" +
            "On Mon Jul 27 Priya Raman wrote:\n" +
            "> Sam — three options attached from the studio. My preference is B: it\n" +
            "> holds up at favicon size and the counters don't fill in when it's\n" +
            "> embroidered.",
        threadId = "thread-review",
    ),
)

private val SEARCH_HITS = listOf(
    preview(
        account = ME,
        id = 4407,
        subject = "Your invoice for July is ready",
        from = "Fastmail Billing <billing@fastmail.com>",
        date = "Tue Jul 28 07:02:40 GMT 2026",
        body = "Invoice FM-2026-07 for £4.20 has been paid with the card ending 4419.",
        hasAttachments = true,
    ),
    preview(
        account = WORK,
        id = 5088,
        subject = "Invoice 2026-114 — studio, July retainer",
        from = "Marek Adamski <accounts@lowfieldstudio.com>",
        date = "Thu Jul 23 11:19:56 GMT 2026",
        body = "Attaching invoice 2026-114 for £480, due 8 August. Bank details are " +
            "unchanged from last month.",
        isRead = false,
        hasAttachments = true,
    ),
    preview(
        account = ME,
        id = 4361,
        subject = "Invoice paid: northgate.co.uk domain renewal",
        from = "Registrar Billing <invoices@registrar.example>",
        date = "Wed Jul 15 08:44:03 GMT 2026",
        body = "Your renewal invoice for northgate.co.uk has been paid. The domain is " +
            "now registered until July 2028.",
    ),
)
