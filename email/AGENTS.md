# AGENTS.md — email/ (':email')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `email/` + allowed shared modules. Do not root-scan.

_Install: ./install email (dev by default)._
<!-- last-reviewed: 2026-10-16 -->
- Gradle: :email / dir: email/
- Package roots present: ui, data, platform, network, intents, widget
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :library:room, :library:widgets, :library:network
- Metadata: metadata_data/email.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** A modern open-source email client with a unified inbox across multiple accounts. Requires internet.
**Key features:**
- Unified inbox over any IMAP/SMTP provider, with presets + OAuth for Gmail/Outlook/Yahoo/iCloud/Fastmail
- Push mail over IMAP IDLE plus hourly poll for the rest; outbox retry, drafts, and snooze via WorkManager
- Composer with inline images (HTML editor + CID spans) and an EML file viewer
- Home-screen widget, rich notifications with actions, and assistant read/search intents
**Architecture / data-flow:** `ui/` Compose screens → `platform/EmailViewModel` (+ `EmailManager`, send/draft action helpers) → `data/` Room stores (`*Dao`/`*Store` pairs + `EmailRepository`) fed by workers (`EmailSyncWorker`, `ImapIdleService`, `OutboxSendWorker`, `SnoozeWorker`) → `network/` raw IMAP/SMTP clients. `MainActivity.onCreateBeforeContent`: `NetworkClient.init(SYSTEM)` first, then widget previews, intent handling, hourly-sync schedule, outbox flush, IDLE start, and two one-time backfills.
**Entry points + reading order:** `Route` (`MessageList` / `MessageThread` / `Composer` / `Outbox` / `Drafts` / `AddAccount` / `Settings` / `EmlViewer`) → `MainActivity.kt` → `Navigation.kt` → `ui/MainContent.kt` → `platform/EmailViewModel.kt` → `data/EmailRepository.kt`.
**Gotchas:**
- `NetworkClient.init(this, TrustBundle.SYSTEM)` must stay first in `onCreateBeforeContent` — mail servers need system CAs, not the first-party bundle
- `DateMillisBackfill`/`PeekContentBackfill` are one-time migrations gated on `runIfNeeded`; skipping them breaks sort order and snippet previews
- IDLE push is foreground-gated via `AppLifecycleTracker` — background delivery goes through the hourly worker + notifications, not the socket
- `network/imap/TrustAll.kt` exists for debug only; never widen its use

## Key files
### Root — entry + navigation
- com/vayunmathur/email/MainActivity.kt
- com/vayunmathur/email/Navigation.kt
- com/vayunmathur/email/Route.kt
### data/ — Room stores + sync workers
- com/vayunmathur/email/data/EmailModels.kt
- com/vayunmathur/email/data/EmailDatabase.kt
- com/vayunmathur/email/data/EmailAccountDao.kt
- com/vayunmathur/email/data/EmailAccountStore.kt
- com/vayunmathur/email/data/EmailMessageDao.kt
- com/vayunmathur/email/data/EmailMessageStore.kt
- com/vayunmathur/email/data/EmailOutboxDao.kt
- com/vayunmathur/email/data/EmailOutboxStore.kt
- com/vayunmathur/email/data/EmailQueryDao.kt
- com/vayunmathur/email/data/EmailQueryStore.kt
- com/vayunmathur/email/data/EmailRepository.kt
- com/vayunmathur/email/data/EmailSettings.kt
- com/vayunmathur/email/data/EmailSyncState.kt
- com/vayunmathur/email/data/EmailSyncWorker.kt
- com/vayunmathur/email/data/ImapIdleService.kt
- com/vayunmathur/email/data/ImapIdleRetryWorker.kt
- com/vayunmathur/email/data/OutboxSendWorker.kt
- com/vayunmathur/email/data/SnoozeWorker.kt
- com/vayunmathur/email/data/OutlookOAuth.kt
- com/vayunmathur/email/data/ProviderPresets.kt
- com/vayunmathur/email/data/CredentialCrypto.kt
- com/vayunmathur/email/data/DateMillisBackfill.kt
- com/vayunmathur/email/data/PeekContentBackfill.kt
### network/ — raw IMAP + SMTP clients
- com/vayunmathur/email/network/imap/ImapClient.kt
- com/vayunmathur/email/network/imap/RawImapConnection.kt
- com/vayunmathur/email/network/imap/ImapWireIo.kt
- com/vayunmathur/email/network/imap/ImapParser.kt
- com/vayunmathur/email/network/imap/ImapFetchPipeline.kt
- com/vayunmathur/email/network/imap/MimeParser.kt
- com/vayunmathur/email/network/imap/MimeParserParts.kt
- com/vayunmathur/email/network/imap/ImapAuthException.kt
- com/vayunmathur/email/network/imap/TrustAll.kt — debug only
- com/vayunmathur/email/network/smtp/SmtpClient.kt
- com/vayunmathur/email/network/smtp/RawSmtpConnection.kt
- com/vayunmathur/email/network/smtp/MimeBuilder.kt
### platform/ — ViewModel, actions, notifications, lifecycle
- com/vayunmathur/email/platform/EmailViewModel.kt
- com/vayunmathur/email/platform/EmailManager.kt
- com/vayunmathur/email/platform/EmailUiContract.kt
- com/vayunmathur/email/platform/EmailClientFlag.kt
- com/vayunmathur/email/platform/EmailSendActions.kt
- com/vayunmathur/email/platform/EmailDraftActions.kt
- com/vayunmathur/email/platform/AiSummaryHelper.kt
- com/vayunmathur/email/platform/EmailNotifications.kt
- com/vayunmathur/email/platform/EmailNotificationActionReceiver.kt
- com/vayunmathur/email/platform/AppLifecycleTracker.kt
- com/vayunmathur/email/platform/BootReceiver.kt
- com/vayunmathur/email/platform/EmlUtils.kt
- com/vayunmathur/email/platform/IntentState.kt
### ui/ — list, thread, composer, account flows
- com/vayunmathur/email/ui/MainContent.kt
- com/vayunmathur/email/ui/MessageListScreen.kt
- com/vayunmathur/email/ui/MessageThreadScreen.kt — thread pane: strip + one open message, no outer scroller (#900)
- com/vayunmathur/email/ui/ThreadMessageStrip.kt
- com/vayunmathur/email/ui/MessageItem.kt — header / self-scrolling body / footer
- com/vayunmathur/email/ui/MessageFooter.kt
- com/vayunmathur/email/ui/FolderList.kt
- com/vayunmathur/email/ui/DetailItem.kt
- com/vayunmathur/email/ui/ComposerScreen.kt
- com/vayunmathur/email/ui/ComposerHeader.kt
- com/vayunmathur/email/ui/ComposerAttachments.kt
- com/vayunmathur/email/ui/ComposerHelpers.kt
- com/vayunmathur/email/ui/AttachmentItem.kt
- com/vayunmathur/email/ui/composer/EmailHtmlEditor.kt
- com/vayunmathur/email/ui/composer/EmailHtmlEditorController.kt
- com/vayunmathur/email/ui/composer/EmailHtmlSerializer.kt
- com/vayunmathur/email/ui/composer/EmailHtmlTagHandler.kt
- com/vayunmathur/email/ui/composer/EmailHtmlInline.kt
- com/vayunmathur/email/ui/composer/InlineImage.kt
- com/vayunmathur/email/ui/composer/CidImageSpan.kt
- com/vayunmathur/email/ui/DraftsScreen.kt
- com/vayunmathur/email/ui/OutboxScreen.kt
- com/vayunmathur/email/ui/EmlViewerScreen.kt
- com/vayunmathur/email/ui/AddAccountScreen.kt
- com/vayunmathur/email/ui/AddAccountSection.kt
- com/vayunmathur/email/ui/AddAccountInstructionsCard.kt
- com/vayunmathur/email/ui/OAuthActivity.kt
- com/vayunmathur/email/ui/SettingsScreen.kt
- com/vayunmathur/email/ui/EmailUiHelpers.kt
### intents/ — assistant IPC
- com/vayunmathur/email/intents/GetRecentIntent.kt
- com/vayunmathur/email/intents/SearchIntent.kt
- com/vayunmathur/email/intents/EmailDataMapper.kt
### widget/ — unified-inbox home widget
- com/vayunmathur/email/widget/EmailWidget.kt
- com/vayunmathur/email/widget/EmailWidgetReceiver.kt

## Verify (this module only)
```
./gradlew :email:compileDevKotlin
./gradlew :email:lint
./gradlew :email:checkMetadata
```
