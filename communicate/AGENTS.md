# AGENTS.md — communicate/ (':communicate')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `communicate/` + allowed shared modules. Do not root-scan.

_Install: ./install communicate (dev by default)._
<!-- last-reviewed: 2026-11-21 -->
- Gradle: :communicate / dir: communicate/
- Package roots present: ui, data, domain, intents, service, telephony, notifications
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :library:network, :library:image, :library:room, :library:aosp-stubs (compileOnly)
- Metadata: metadata_data/communicate.md (present)
- Rust: yes (two crates) / screenshotTest: yes

## Overview
**Purpose:** Multi-line messenger + dialer: SIM (SMS/MMS/calls), Google Voice, WhatsApp, Signal and RCS lines unified in one conversation list, with E2EE on the closed-loop lines. Requires internet + telephony permissions.
**Key features:**
- Five lines behind one `CommunicateLine` model: SIM via framework telephony, Google Voice (protojson RPC + SIP-over-WSS), WhatsApp primary client (Noise + Double Ratchet), Signal (libsignal + Rust protocol), RCS (SIP/MSRP + MLS E2EE)
- In-app calling over WebRTC/RingRTC for the internet lines; framework `InCallService`/`ConnectionService` for SIM + Google Voice telecom integration
- Android Auto car templates (`service/car/`), assistant send/read intents, voicemail tab with plain ExoPlayer playback
**Architecture / data-flow:** `ui/` tabs + conversation screens → `data/CommunicateRepository*` (per-line splits: `Sim`, `Send`, `Rcs`, `RcsGroup`, `Signal`, `WhatsApp`, `WhatsAppCalls`, `Merged`) over Room (`data/signal/SignalDatabase`, `data/rcs/RcsDatabase`, WhatsApp Room DB) and per-line transports in `data/<line>/`; `telephony/` owns framework call/SMS services, `service/car/` the car host. There is no `platform/` or `network/` root in this module — line transports live under `data/<line>/transport/` and framework glue under `telephony/`; don't create those roots here.
**Entry points + reading order:** `Route` (`Messages` / `Dialer` / `CallLogs` / `Accounts` / `Conversation(threadId, …)` / `Call` / registration wizards) → `MainActivity.kt` → `Navigation.kt` → `ui/MessagesScreen.kt` → `data/CommunicateRepository.kt`.
**Gotchas:**
- Two Rust crates, two JNI libs: `communicate_signal` (`src/main/rust/`, Signal protocol v3) via `data/signal/e2e/RustSignalCrypto`, and `communicate_mls` (`src/main/rust-mls/`, OpenMLS) via `data/rcs/e2e/RustMlsCrypto` — both guarded by `NativeLoader` + `isAvailable`; never call them unguarded and never merge the crates (own dependency trees).
- Protobuf is hand-rolled: AGP 9 breaks both Wire and protobuf Gradle plugins, so `build.gradle.kts` resolves `protoc` from Maven and runs `GenerateProtoTask` over `src/main/proto/` (11 Signal/WhatsApp protos) — don't add a proto plugin.
- RingRTC is the ONLY WebRTC distribution allowed on the classpath (it bundles `org.webrtc` and binds it via JNI names); never add another WebRTC dep. libsignal desktop natives (`*.dylib`/`*.dll`) and `libsignal_jni_testing.so` are stripped in packaging — keep those excludes.
- `:library:aosp-stubs` is `compileOnly` only (framework `@SystemApi` RCS/backup transport stubs) — it must never be packaged.
- Registration wizards are route sub-stacks (`Route.WhatsAppRegistration`, `Route.SignalRegistration`) carrying cc/number in arguments; reaching `Done` replaces the sub-stack — keep step state in route args, not in held selection state.
- Car code (`service/car/`) is the only car-app-library consumer here; phone UI, sync services and crypto paths must stay car-free.

## Key files
### Root — entry + navigation
- com/vayunmathur/communicate/MainActivity.kt
- com/vayunmathur/communicate/Navigation.kt
- com/vayunmathur/communicate/Route.kt
### data/ — unified repository (per-line splits)
- com/vayunmathur/communicate/data/CommunicateModels.kt
- com/vayunmathur/communicate/data/CommunicateRepository.kt
- com/vayunmathur/communicate/data/CommunicateRepositoryMerged.kt — plus per-line splits (`Sim`, `Send`, `Rcs`, `Signal`, `WhatsApp`)
- com/vayunmathur/communicate/data/SimManager.kt
- com/vayunmathur/communicate/data/SecretSealer.kt
### data/googlevoice/ — Voice line (RPC + SIP calls)
- com/vayunmathur/communicate/data/googlevoice/GoogleVoiceClient.kt
- com/vayunmathur/communicate/data/googlevoice/GoogleVoiceSession.kt
- com/vayunmathur/communicate/data/googlevoice/call/SipClient.kt
- com/vayunmathur/communicate/data/googlevoice/call/GoogleVoiceCallManager.kt
### data/whatsapp/ — WhatsApp primary client
- com/vayunmathur/communicate/data/whatsapp/WhatsAppClient.kt
- com/vayunmathur/communicate/data/whatsapp/WhatsAppDatabase.kt
- com/vayunmathur/communicate/data/whatsapp/WhatsAppRepository.kt
- com/vayunmathur/communicate/data/whatsapp/transport/WhatsAppSocket.kt
- com/vayunmathur/communicate/data/whatsapp/e2e/WhatsAppE2E.kt
### data/signal/ — Signal line (libsignal + Rust)
- com/vayunmathur/communicate/data/signal/SignalClient.kt
- com/vayunmathur/communicate/data/signal/SignalDatabase.kt
- com/vayunmathur/communicate/data/signal/SignalRepository.kt
- com/vayunmathur/communicate/data/signal/e2e/RustSignalCrypto.kt — guarded `communicate_signal` wrapper
- com/vayunmathur/communicate/data/signal/transport/SignalSocket.kt
### data/rcs/ — RCS line (SIP/MSRP + MLS)
- com/vayunmathur/communicate/data/rcs/RcsDatabase.kt
- com/vayunmathur/communicate/data/rcs/RcsSessionManager.kt
- com/vayunmathur/communicate/data/rcs/RcsMsrp.kt
- com/vayunmathur/communicate/data/rcs/e2e/RustMlsCrypto.kt — guarded `communicate_mls` wrapper
### telephony/ — framework call/SMS services
- com/vayunmathur/communicate/telephony/CommunicateInCallService.kt
- com/vayunmathur/communicate/telephony/GoogleVoiceConnectionService.kt
- com/vayunmathur/communicate/telephony/SmsDeliverReceiver.kt
- com/vayunmathur/communicate/telephony/RcsSyncService.kt
### service/car/ + intents/ + ui/
- com/vayunmathur/communicate/service/car/CommunicateCarAppService.kt
- com/vayunmathur/communicate/intents/SendMessageIntent.kt
- com/vayunmathur/communicate/intents/GetMessagesIntent.kt
- com/vayunmathur/communicate/ui/MessagesScreen.kt
- com/vayunmathur/communicate/ui/ConversationScreen.kt
- com/vayunmathur/communicate/ui/DialerScreen.kt

## Verify (this module only)
```
./gradlew :communicate:compileDevKotlin
./gradlew :communicate:lint
./gradlew :communicate:checkMetadata
```
