# AGENTS.md — openassistant/ (':openassistant')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `openassistant/` + allowed shared modules. Do not root-scan.

_Install: ./install openassistant (dev by default)._
<!-- last-reviewed: 2026-10-16 -->
- Gradle: :openassistant / dir: openassistant/
- Package roots present: ui, data, domain, platform
- Entry files: MainActivity.kt (no Route.kt — `Route` + `Navigation` are inline)
- Deps: :library:room, :library:image, :library:ml, :library:downloadservice
- Metadata: metadata_data/openassistant.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** A simple open-source local AI app — Gemma 4 E2B runs fully on device (~3.5GB RAM when active). Internet only for initial asset downloads.
**Key features:**
- Multiple chats with streaming responses and markdown rendering, persisted in Room
- Multimodal input: photo upload and audio recording, read by Gemma 4's vision and audio towers
- Tool calling into other Modern Apps over local IPC, with JSON-schema validation
- A model-managed memory plus an editable system prompt; can be set as the device's default digital assistant (Assist API + voice interaction service)
**Architecture / data-flow:** `ui/LiteRTChatUi` → `util/AssistantViewModel` → `data/OpenAssistantRepository` (Room) + `util/InferenceService` → `platform/MamlChatEngine` (Gemma 4 on `:library:ml`'s `Gemma4Handle`; own tool loop, max 6 rounds) with `platform/AssistantTools` (explicit registry over `domain/AssistantToolSpecs`) dispatching IPC tool calls. Pure logic (tool-call parsing/markup stripping, tool loop, WAV decode, history fitting) lives in `domain/` with unit tests. `onCreateBeforeContent` only wires the `IntentLauncher` and picks up Assist-API screen context; `Content()` gates on the initial model download before the chat renders.
**Entry points + reading order:** `Route` (`ConversationPage(id)` / `SettingsPage`, inline) → `MainActivity.kt` → `ui/LiteRTChatUi.kt` → `util/AssistantViewModel.kt` → `util/InferenceService.kt` → `platform/MamlChatEngine.kt` → `data/OpenAssistantRepository.kt`.
**Gotchas:**
- Card drift fixed: `ui/` files were renamed `Assistant*` → `LiteRT*` and `util/` gained `AssistantDeviceTools`, `AssistantToolIntents`, `JsonSchemaValidator`; `Gemma4Engine`/`Gemma4EtEngine` no longer exist
- `MainActivity.intentLauncher` is a shared static other apps mirror (maps/taxi pattern) — keep it initialized in `onCreateBeforeContent`
- `StubRecognitionService` is a placeholder voice-recognition hook, not a real recognizer
- Tool names are snake_case (`get_notes`, `add_to_memory`), matching what LiteRT-LM exposed; the system prompt references them. Add a tool in `domain/AssistantToolSpecs.kt` AND `platform/AssistantTools.kt` (an init check fails if they disagree)
- Model files live in `getExternalFilesDir(null)/gemma4/` (text, embed, head, vision, audio, tokenizer); the vision/audio towers load lazily and unload after 3 idle minutes
- Extra non-conforming dirs (`assist/`, `util/`) hold real code — closed-roots migration pending

## Key files
### Root — entry + inline navigation
- com/vayunmathur/openassistant/MainActivity.kt — Route, Navigation, assist screen-context
### assist/ — system assistant surfaces
- com/vayunmathur/openassistant/assist/OpenAssistantSession.kt — Assist API screen-text capture
- com/vayunmathur/openassistant/assist/OpenAssistantSessionService.kt
- com/vayunmathur/openassistant/assist/OpenAssistantVoiceInteractionService.kt
- com/vayunmathur/openassistant/assist/StubRecognitionService.kt — placeholder recognizer hook
### data/ — chats + repository
- com/vayunmathur/openassistant/data/Database.kt
- com/vayunmathur/openassistant/data/OpenAssistantRepository.kt
### ui/ — chat + settings
- com/vayunmathur/openassistant/ui/LiteRTChatUi.kt
- com/vayunmathur/openassistant/ui/LiteRTChatScreen.kt
- com/vayunmathur/openassistant/ui/LiteRTChatBubble.kt
- com/vayunmathur/openassistant/ui/LiteRTChatInput.kt
- com/vayunmathur/openassistant/ui/SettingsPage.kt
### domain/ — pure, unit-tested
- com/vayunmathur/openassistant/domain/AssistantToolSpecs.kt — tool names/descriptions/params
- com/vayunmathur/openassistant/domain/ToolCalls.kt — call parsing, markup stripping, arg coercion
- com/vayunmathur/openassistant/domain/ToolLoop.kt — generate/call/respond loop
- com/vayunmathur/openassistant/domain/WavDecoder.kt
- com/vayunmathur/openassistant/domain/HistoryFit.kt
### platform/ — engine + tool registry
- com/vayunmathur/openassistant/platform/MamlChatEngine.kt
- com/vayunmathur/openassistant/platform/AssistantTools.kt
### util/ — ViewModel, inference, tools, audio (migration pending)
- com/vayunmathur/openassistant/util/AssistantViewModel.kt
- com/vayunmathur/openassistant/util/AssistantUiContract.kt
- com/vayunmathur/openassistant/util/InferenceService.kt
- com/vayunmathur/openassistant/util/AssistantToolIntents.kt
- com/vayunmathur/openassistant/util/AssistantDeviceTools.kt
- com/vayunmathur/openassistant/util/JsonSchemaValidator.kt
- com/vayunmathur/openassistant/util/AudioRecorder.kt
- com/vayunmathur/openassistant/util/AppBackupAgent.kt

## Verify (this module only)
```
./gradlew :openassistant:compileDevKotlin
./gradlew :openassistant:testDevUnitTest
./gradlew :openassistant:lint
./gradlew :openassistant:checkMetadata
```
