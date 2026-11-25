# AGENTS.md — share/ (':share')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `share/` + allowed shared modules. Do not root-scan.

_Install: ./install share (dev by default)._
- Gradle: :share / dir: share/
- Package roots present: ui, domain, platform, network
- Entry files: MainActivity.kt, Route.kt, Navigation.kt + SharePopupActivity.kt (share-sheet entry)
- Deps: (none declared — raw TCP/BLE transports, Rust protocol core)
- Metadata: metadata_data/share.md (present)
- Rust: yes / screenshotTest: yes

## Overview
**Purpose:** Offline Quick Share-compatible nearby file transfer — real protocol interop with stock Android, E2EE via UKEY2, device-to-device with no server.
**Key features:**
- Send from the system share sheet (`SharePopupActivity` → `SharePopupSheet`); discovery over BLE + NSD mDNS
- Encrypted TCP transport (`TcpTransport`, Rust `ShareNative` protocol core); receive with the app closed (Quick Settings tile + boot-gated controller)
- Received files stay app-private in `ReceivedFileStore` until the user saves/shares them
**Architecture / data-flow:** Send: share intent → popup → `ShareViewModel` → discovery managers → `ShareTransferService` → `TcpTransport`. Receive: tile/boot → `ShareReceiveController` → `PostGate` → notifier → `ShareSaveActivity`. `domain/protocol/ShareSession` models the handshake; `protocol/ShareNative` is the Rust JNI bridge.
**Entry points + reading order:** `SharePopupActivity.kt` (send) / `platform/receive/ShareReceiveController.kt` (receive) → `Route` → `Navigation.kt` → `platform/ShareViewModel.kt` → `domain/protocol/ShareSession.kt`.
**Gotchas:**
- `protocol/` is NOT a closed package root — don't add more files there
- Protocol interop with stock Android is version-sensitive — changes need cross-device testing, not just unit tests
- Receive path must work with the app closed; never assume an Activity context there

## Key files
### Root — UI entry + share-sheet entry
- com/vayunmathur/share/MainActivity.kt
- com/vayunmathur/share/Navigation.kt
- com/vayunmathur/share/Route.kt
- com/vayunmathur/share/SharePopupActivity.kt — share-sheet entry
### domain/protocol/ — session model
- com/vayunmathur/share/domain/protocol/ShareSession.kt
### network/transport/ — encrypted TCP
- com/vayunmathur/share/network/transport/TcpTransport.kt
### platform/ — ViewModel, permissions, receive pipeline
- com/vayunmathur/share/platform/ShareViewModel.kt
- com/vayunmathur/share/platform/ShareUiContract.kt
- com/vayunmathur/share/platform/SharePermissions.kt
- com/vayunmathur/share/platform/ReceivedFileStore.kt
- com/vayunmathur/share/platform/discovery/BleDiscoveryManager.kt
- com/vayunmathur/share/platform/discovery/NsdDiscoveryManager.kt
- com/vayunmathur/share/platform/discovery/NearbyDevice.kt
- com/vayunmathur/share/platform/receive/ShareReceiveController.kt
- com/vayunmathur/share/platform/receive/PostGate.kt
- com/vayunmathur/share/platform/receive/ShareReceiveNotifier.kt
- com/vayunmathur/share/platform/receive/ShareNotificationReceiver.kt
- com/vayunmathur/share/platform/receive/ShareSaveActivity.kt
- com/vayunmathur/share/platform/receive/ShareBootReceiver.kt
- com/vayunmathur/share/platform/receive/ShareReceiveTileService.kt
- com/vayunmathur/share/platform/transfer/ShareTransferService.kt
### protocol/ — Rust bridge
- com/vayunmathur/share/protocol/ShareNative.kt
### ui/ — popup sheet + send screen
- com/vayunmathur/share/ui/SharePopupSheet.kt
- com/vayunmathur/share/ui/ShareSendScreen.kt

## Verify (this module only)
```
./gradlew :share:compileDevKotlin
./gradlew :share:lint
./gradlew :share:checkMetadata
```
