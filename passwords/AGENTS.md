# AGENTS.md — passwords/ (':passwords')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `passwords/` + allowed shared modules. Do not root-scan.

_Install: ./install passwords (dev by default)._
- Gradle: :passwords / dir: passwords/
- Package roots present: ui, data, domain, platform, sync
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :library:biometric, :library:room, :library:network
- Metadata: metadata_data/passwords.md (present)
- Rust: yes / screenshotTest: yes

## Overview
**Purpose:** Offline password manager + passkey provider + autofill service + FIDO caBLE security key, with KDBX-file sync. Vault encrypts behind biometric keys.
**Key features:**
- Passwords + TOTP with domain-match fill; imports from Bitwarden/Chrome/Firefox/LastPass/1Password/Dashlane
- Passkeys as an Android credential provider (`PasskeyCredentialService`, `PasskeyEntries`); autofill via `PasswordAutofillService`
- KDBX sync to a user-chosen file (`KdbxSyncEngine` + scheduler/worker over the Rust KDBX core); caBLE tunnel turns the phone into a desktop security key (only network use)
**Architecture / data-flow:** `ui/` vault + passkey + settings → `platform/PasswordsViewModel` → `data/PasswordRepository` over SQLCipher Room (`Password`/`Passkey`/`SyncSnapshot`); `domain/` holds pure credential logic (CBOR, merge, TOTP, domain-match); composition stays locked (`MaFragmentActivity`, `autoSetContent=false`) until the biometric gate passes; everysync relays vault snapshots via `sync/EntryMapper`.
**Entry points + reading order:** `Route` → `MainActivity.kt` (biometric gate first) → `Navigation.kt` → `platform/PasswordsViewModel.kt` → `data/PasswordRepository.kt` → `sync/KdbxSyncEngine.kt`.
**Gotchas:**
- Biometric-gated `MaFragmentActivity` pattern (shared with photos): never render vault content before unlock succeeds
- caBLE is a full FIDO stack (`platform/cable/` Noise/P-256/CTAP) — touch it only with the spec beside you
- KDBX crypto runs in Rust (`util/KdbxNative`); Kotlin side is orchestration only

## Key files
### Root — entry + navigation
- com/vayunmathur/passwords/MainActivity.kt — biometric gate before composition
- com/vayunmathur/passwords/Navigation.kt
- com/vayunmathur/passwords/Route.kt
### data/ — encrypted Room vault
- com/vayunmathur/passwords/data/PasswordDatabase.kt
- com/vayunmathur/passwords/data/PasswordRepository.kt
- com/vayunmathur/passwords/data/Password.kt
- com/vayunmathur/passwords/data/Passkey.kt
- com/vayunmathur/passwords/data/PasskeyStore.kt
- com/vayunmathur/passwords/data/Converters.kt
- com/vayunmathur/passwords/data/SyncSnapshot.kt
- com/vayunmathur/passwords/data/SyncSnapshotDao.kt
### domain/ — pure credential logic (no Android)
- com/vayunmathur/passwords/domain/CredentialMerge.kt
- com/vayunmathur/passwords/domain/DomainMatch.kt
- com/vayunmathur/passwords/domain/TOTP.kt
- com/vayunmathur/passwords/domain/Cbor.kt
- com/vayunmathur/passwords/domain/ImportSource.kt
- com/vayunmathur/passwords/domain/PasskeyLink.kt
### platform/ — ViewModel, autofill, passkeys, backup
- com/vayunmathur/passwords/platform/PasswordsViewModel.kt
- com/vayunmathur/passwords/platform/PasswordsUiContract.kt
- com/vayunmathur/passwords/platform/PasswordAutofillService.kt
- com/vayunmathur/passwords/platform/PasskeyCredentialService.kt
- com/vayunmathur/passwords/platform/PasskeyEntries.kt
- com/vayunmathur/passwords/platform/PasskeyUtils.kt
- com/vayunmathur/passwords/platform/AppBackupAgent.kt
- com/vayunmathur/passwords/platform/KdbxBackupFormat.kt
### platform/cable/ — FIDO caBLE security-key stack
- com/vayunmathur/passwords/platform/cable/CableActivity.kt
- com/vayunmathur/passwords/platform/cable/CableService.kt
- com/vayunmathur/passwords/platform/cable/CableSession.kt
- com/vayunmathur/passwords/platform/cable/CableTunnel.kt
- com/vayunmathur/passwords/platform/cable/CableAdvertiser.kt
- com/vayunmathur/passwords/platform/cable/CableQr.kt
- com/vayunmathur/passwords/platform/cable/CableEid.kt
- com/vayunmathur/passwords/platform/cable/CableKeys.kt
- com/vayunmathur/passwords/platform/cable/Crypter.kt
- com/vayunmathur/passwords/platform/cable/Noise.kt
- com/vayunmathur/passwords/platform/cable/P256.kt
- com/vayunmathur/passwords/platform/cable/Ctap.kt
- com/vayunmathur/passwords/platform/cable/CtapProcessor.kt
- com/vayunmathur/passwords/platform/cable/CborReader.kt
- com/vayunmathur/passwords/platform/cable/TunnelDomains.kt
- com/vayunmathur/passwords/platform/cable/WebAuthnAuthenticator.kt
### sync/ — KDBX file sync + relay mapping
- com/vayunmathur/passwords/sync/KdbxSyncEngine.kt
- com/vayunmathur/passwords/sync/KdbxSyncScheduler.kt
- com/vayunmathur/passwords/sync/KdbxSyncWorker.kt
- com/vayunmathur/passwords/sync/KdbxSyncSettings.kt
- com/vayunmathur/passwords/sync/KdbxPasswordHelper.kt
- com/vayunmathur/passwords/sync/EntryMapper.kt — everysync relay mapping
### util/ — Rust KDBX bridge
- com/vayunmathur/passwords/util/KdbxNative.kt — JNI bridge, orchestration only in Kotlin
### ui/ — vault, passkeys, settings
- com/vayunmathur/passwords/ui/PasswordScreen.kt
- com/vayunmathur/passwords/ui/PasswordPage.kt
- com/vayunmathur/passwords/ui/PasswordEditScreen.kt
- com/vayunmathur/passwords/ui/PasswordEditPage.kt
- com/vayunmathur/passwords/ui/PasswordScreenSection.kt
- com/vayunmathur/passwords/ui/PasskeyPage.kt
- com/vayunmathur/passwords/ui/PasskeyAuthActivity.kt
- com/vayunmathur/passwords/ui/PasskeyLinkDialog.kt
- com/vayunmathur/passwords/ui/MenuScreen.kt
- com/vayunmathur/passwords/ui/MenuPage.kt
- com/vayunmathur/passwords/ui/SettingsPage.kt
- com/vayunmathur/passwords/ui/AutofillSettingsActivity.kt

## Verify (this module only)
```
./gradlew :passwords:compileDevKotlin
./gradlew :passwords:lint
./gradlew :passwords:checkMetadata
```
