# AGENTS.md — securemail/ (':securemail')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `securemail/` + allowed shared modules. Do not root-scan.

_Install: ./install securemail (dev by default)._
<!-- last-reviewed: 2026-11-21 -->
- Gradle: :securemail / dir: securemail/
- Package roots present: ui, data, domain, platform, network, service
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :library:network, :library:biometric, :library:work, :library:room, :library:e2ee-openpgp
- Metadata: metadata_data/securemail.md (present)
- Rust: no (native PGP via :library:e2ee-openpgp) / screenshotTest: yes

## Overview
**Purpose:** End-to-end-encrypted mail over the `mail.maemail.org` relay — OpenPGP identity per user, aliases, live WebSocket inbox with a periodic poll backstop. Requires internet.
**Key features:**
- PGP key generation/encrypt/decrypt/sign via the native Rust core (`Pgp` in `:library:e2ee-openpgp`, rPGP in `libe2ee_pgp.so`) — no BouncyCastle
- Biometric-gated unlock: the Keystore-wrapped private key is unwrapped before any mail composes
- Aliases, drafts, outbox retry, and WKD/VKS key lookup for external recipients
**Architecture / data-flow:** `ui/` Compose screens → `platform/MailViewModel` (+ `AliasController`, `DraftsController`) → `data/` Room stores (`MailRepository`, `MailStore`, `InboundStore`) fed by `service/MailSocketService` over `network/MailSocket` (REST + WebSocket) and `platform/MailSyncWorker`; crypto in `domain/pgp/PgpCrypto` + `platform/crypto/KeyManager`. Unlike `email/`, there is no raw IMAP/SMTP and no `network.Networking` object — `network/MailSocket` is the sole network entry point.
**Entry points + reading order:** `Route` (`Onboarding` / `Lock` / `Inbox(folder)` / `Composer` / `Reader(messageId)` / `Out` / `Settings`) → `MainActivity.kt` → `Navigation.kt` → `platform/MailViewModel.kt` → `data/MailRepository.kt` → `network/MailSocket.kt`.
**Gotchas:**
- `NetworkClient.init(this, TrustBundle.FIRST_PARTY)` must stay first in `onCreateBeforeContent` — `mail.maemail.org` is a pinned first-party host; WKD/VKS lookups for arbitrary external domains ride the platform trust store instead.
- `MainActivity` is a `MaFragmentActivity` with `autoSetContent = false`: composition is deferred until `unlockIfReady` succeeds — don't set content before the key gate.
- `:library:e2ee-openpgp` was split out of `:library:e2ee-p2p` so non-mail apps don't link rPGP (#873); securemail is its only consumer — keep the PGP surface here, not in shared code.
- `data/MailConfig` is the prefs owner (address token + onboarded flag, non-secret fields only, one process-wide DataStore); secrets live only in `platform/crypto/KeyManager`'s Keystore-wrapped prefs — never add secrets to `MailConfig`.
- `MailSocketService` owns the live socket lifetime; `MailSyncWorker` is only a backstop poll — don't move delivery onto the worker.

## Key files
### Root — entry + navigation
- com/vayunmathur/securemail/MainActivity.kt
- com/vayunmathur/securemail/Navigation.kt
- com/vayunmathur/securemail/Route.kt
### data/ — Room stores + config
- com/vayunmathur/securemail/data/MailDatabase.kt
- com/vayunmathur/securemail/data/MailRepository.kt
- com/vayunmathur/securemail/data/MailStore.kt
- com/vayunmathur/securemail/data/InboundStore.kt
- com/vayunmathur/securemail/data/Identities.kt
- com/vayunmathur/securemail/data/SenderKeys.kt
- com/vayunmathur/securemail/data/MailConfig.kt — sole prefs owner (non-secret)
### domain/ — pure mail + PGP policy
- com/vayunmathur/securemail/domain/mail/MailPolicy.kt
- com/vayunmathur/securemail/domain/mail/AliasGenerator.kt
- com/vayunmathur/securemail/domain/mail/KeyLookupPolicy.kt
- com/vayunmathur/securemail/domain/mail/MimeCodec.kt
- com/vayunmathur/securemail/domain/pgp/PgpCrypto.kt — `Pgp` (rPGP) wrapper surface
### network/ — relay REST + WebSocket (sole network entry)
- com/vayunmathur/securemail/network/MailSocket.kt
### platform/ — ViewModel, controllers, key gate, sync
- com/vayunmathur/securemail/platform/MailViewModel.kt
- com/vayunmathur/securemail/platform/AliasController.kt
- com/vayunmathur/securemail/platform/DraftsController.kt
- com/vayunmathur/securemail/platform/OutboxRetry.kt
- com/vayunmathur/securemail/platform/MailSyncWorker.kt
- com/vayunmathur/securemail/platform/BootReceiver.kt
- com/vayunmathur/securemail/platform/IntentState.kt
- com/vayunmathur/securemail/platform/crypto/KeyManager.kt — Keystore-wrapped PGP private key
### service/ — live socket lifetime
- com/vayunmathur/securemail/service/MailSocketService.kt
### ui/ — inbox, reader, composer, onboarding
- com/vayunmathur/securemail/ui/InboxScreen.kt
- com/vayunmathur/securemail/ui/ReaderScreen.kt
- com/vayunmathur/securemail/ui/ComposeScreen.kt
- com/vayunmathur/securemail/ui/OnboardingScreen.kt
- com/vayunmathur/securemail/ui/LockScreen.kt
- com/vayunmathur/securemail/ui/OutScreen.kt
- com/vayunmathur/securemail/ui/SettingsScreen.kt
- com/vayunmathur/securemail/ui/NavigationEntries.kt

## Verify (this module only)
```
./gradlew :securemail:compileDevKotlin
./gradlew :securemail:lint
./gradlew :securemail:checkMetadata
```
