# AGENTS.md — everysync/ (':everysync')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `everysync/` + allowed shared modules. Do not root-scan.

_Install: ./install everysync (dev by default)._
<!-- last-reviewed: 2026-10-16 -->
- Gradle: :everysync / dir: everysync/
- Package roots present: ui, data, domain, platform, network, provider, auth, sync
- Entry files: MainActivity.kt, Route.kt, Navigation.kt, EverySyncApplication.kt
- Deps: :library:network, :library:e2ee-p2p (verified in build.gradle.kts)
- Metadata: metadata_data/everysync.md (present but stale — omits vaults/E2EE relay)
- Rust: no / screenshotTest: yes

### Relay server (backend, outside this repo)
Host: `api.maproj.org` (Cloudflare FIRST_PARTY pins). Server checkout: `../location_share_server` (sibling of this repo root, `C:\Users\Vayun\Documents\code\location_share_server`). Live SSH: `ssh root@87.86.80.39`. Env template: `../location_share_server/.env.example` (copy to `.env`, never commit secrets). Full details: root `AGENTS.md` → "Backend server (outside this repo)".

## Overview
**Purpose:** Universal sync hub — multi-provider account sync (contacts/calendars/health) plus E2EE vault relay syncing notes/passwords/todos between a user's own devices and shared vaults.
**Key features:**
- CardDAV/CalDAV account sync with OAuth2 PKCE via Custom Tabs (no shipped secrets)
- E2EE vaults + `everysync://join/<vaultId>?owner=&type=` sharing links (sealed join requests, public ids only)
- Five sink types: Calendar / Contacts / Note / Password / Todo (incl. IPC sinks into sibling apps)
- WorkManager periodic sync + daily snapshots, rescheduled on boot/update via `BootReceiver`
**Architecture / data-flow:** `auth/` (accounts/tokens) → `provider/` registry + `SyncProvider` impls (incl. `E2eeProvider`) → `sync/` engine/scheduler/worker + relay blob store/merge + vault keys/roster/owner-transfer → `data/sink/` writers + `domain/format/` iCal/VCard. `EverySyncApplication.onCreate`: `NetworkClient.init(FIRST_PARTY)` → schedule periodic + daily snapshot → one-time Keystore key migration. `MainActivity`: join-link parse → `requestToJoin` + snackbar confirm.
**Entry points + reading order:** `EverySyncApplication.kt` → `MainActivity.kt` (`parseJoinLink`/`handleJoinLink`) → `Route` (`Accounts` / `AddAccount` / `AccountDetail` / `Settings`) → `sync/SyncEngine.kt` → `provider/ProviderRegistry.kt`.
**Gotchas:**
- KEEP `EverySyncApplication` — it does real work (network init, scheduler, key migration), not boilerplate; do NOT delete it as a "shared-base" cleanup. Manifest `android:name` must stay.
- `singleTop` warm-start join links arrive via `onNewIntent`, not a fresh `onCreate`
- Creds live in encrypted `TokenStore`/`VaultStore`; `Log.init` comes from the shared `MaInitProvider`, don't re-add it here

## Key files
### Root — app init, entry, navigation
- com/vayunmathur/everysync/EverySyncApplication.kt — KEEP: sync scheduling + network init + key migration
- com/vayunmathur/everysync/MainActivity.kt — join-link entry
- com/vayunmathur/everysync/Navigation.kt
- com/vayunmathur/everysync/Route.kt
### auth/ — accounts, OAuth PKCE, token storage
- com/vayunmathur/everysync/auth/AccountConfig.kt
- com/vayunmathur/everysync/auth/AccountStore.kt
- com/vayunmathur/everysync/auth/Authenticator.kt
- com/vayunmathur/everysync/auth/TokenStore.kt — encrypted
### provider/ — sync-provider registry + impls
- com/vayunmathur/everysync/provider/ProviderRegistry.kt
- com/vayunmathur/everysync/provider/SyncProvider.kt
- com/vayunmathur/everysync/provider/SyncState.kt
- com/vayunmathur/everysync/provider/DataType.kt
- com/vayunmathur/everysync/provider/impl/E2eeProvider.kt
### sync/ — engine, scheduling, relay merge, vault crypto
- com/vayunmathur/everysync/sync/SyncEngine.kt
- com/vayunmathur/everysync/sync/SyncScheduler.kt
- com/vayunmathur/everysync/sync/SyncWorker.kt
- com/vayunmathur/everysync/sync/SyncStatus.kt
- com/vayunmathur/everysync/sync/RelayBlobStore.kt
- com/vayunmathur/everysync/sync/RelayMerge.kt
- com/vayunmathur/everysync/sync/VaultKeys.kt
- com/vayunmathur/everysync/sync/VaultRoster.kt
- com/vayunmathur/everysync/sync/OwnerTransfer.kt
- com/vayunmathur/everysync/sync/adapters/CalendarKind.kt
- com/vayunmathur/everysync/sync/adapters/ContactKind.kt
- com/vayunmathur/everysync/sync/adapters/NoteKind.kt
- com/vayunmathur/everysync/sync/adapters/PasswordKind.kt
- com/vayunmathur/everysync/sync/adapters/TodoKind.kt
### data/ + domain/ — sinks and iCal/VCard formats (pure domain, no Android)
- com/vayunmathur/everysync/data/VaultStore.kt — encrypted, keystore migration
- com/vayunmathur/everysync/data/Settings.kt
- com/vayunmathur/everysync/data/RemoteModels.kt
- com/vayunmathur/everysync/data/sink/CalendarSink.kt
- com/vayunmathur/everysync/data/sink/ContactsSink.kt
- com/vayunmathur/everysync/data/sink/NoteSink.kt
- com/vayunmathur/everysync/data/sink/PasswordSink.kt
- com/vayunmathur/everysync/data/sink/TodoSink.kt
- com/vayunmathur/everysync/data/sink/TodoIpcSink.kt
- com/vayunmathur/everysync/domain/format/ICalendar.kt
- com/vayunmathur/everysync/domain/format/VCard.kt
### network/ + platform/ + ui/ — relay transport, ViewModel, screens
- com/vayunmathur/everysync/network/remote/RelaySync.kt
- com/vayunmathur/everysync/network/remote/RelayCursorStore.kt
- com/vayunmathur/everysync/platform/BootReceiver.kt — reschedule on boot/update
- com/vayunmathur/everysync/platform/EverySyncViewModel.kt
- com/vayunmathur/everysync/platform/EverySyncVaults.kt
- com/vayunmathur/everysync/platform/EverySyncUiContract.kt
- com/vayunmathur/everysync/ui/AccountsScreen.kt
- com/vayunmathur/everysync/ui/AddAccountScreen.kt
- com/vayunmathur/everysync/ui/AccountDetailScreen.kt
- com/vayunmathur/everysync/ui/SettingsScreen.kt
- com/vayunmathur/everysync/ui/VaultSharingSection.kt

## Verify (this module only)
```
./gradlew :everysync:compileDevKotlin
./gradlew :everysync:lint
./gradlew :everysync:checkMetadata
```
