# AGENTS.md — speech/ (':speech')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `speech/` + allowed shared modules. Do not root-scan.

_Install: ./install speech (dev by default)._
- Gradle: :speech / dir: speech/
- Package roots present: domain, platform, service
- Entry files: MainActivity.kt (demo/settings UI) + service/WhisperRecognitionService.kt + service/SupertonicTtsService.kt (system service entries)
- Deps: :library:ml, :library:downloadservice
- Metadata: metadata_data/speech.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** Fully offline speech-to-text (Whisper) + text-to-speech (Supertonic) that also serves as the system `SpeechRecognizer` and TTS engine. All models ship in-app; works on clean install.
**Key features:**
- Whisper-base STT bundled, GPU-accelerated via the repo compute runtime (`:library:ml`); `WhisperRecognitionService` answers system recognition requests
- Supertonic 3 TTS, 10 voices × 31 languages (`SupertonicVoices` catalog); `SupertonicTtsService` answers system synthesis requests
- `MainActivity` is only a demo/settings surface — the services are the product
**Architecture / data-flow:** System binds `service/` endpoints → `util/WhisperEngine` (+ features/tokenizer/model) or `platform/SupertonicEngine` (+ `SupertonicModel`) → `:library:ml` GPU runtime; large assets ship bundled (`:library:downloadservice` only for updates/verification paths).
**Entry points + reading order:** `service/WhisperRecognitionService.kt` / `service/SupertonicTtsService.kt` → `util/WhisperEngine.kt` / `platform/SupertonicEngine.kt` → `domain/SupertonicVoices.kt` → `MainActivity.kt` (demo UI).
**Gotchas:**
- `tts/` and `util/` are NOT closed package roots — don't add more files there
- Model files are large — asset/download changes need size + first-install testing, not just unit tests
- System-service contracts (recognition/TTS callbacks) are version-sensitive; keep the service surface stable

## Key files
### service/ — system entries (the product)
- com/vayunmathur/speech/service/WhisperRecognitionService.kt
- com/vayunmathur/speech/service/SupertonicTtsService.kt
### util/ — Whisper stack
- com/vayunmathur/speech/util/WhisperEngine.kt
- com/vayunmathur/speech/util/WhisperModel.kt
- com/vayunmathur/speech/util/WhisperFeatures.kt
- com/vayunmathur/speech/util/WhisperTokenizer.kt
- com/vayunmathur/speech/util/SpeechUiContract.kt
### platform/ + domain/ — Supertonic stack
- com/vayunmathur/speech/platform/SupertonicEngine.kt
- com/vayunmathur/speech/platform/SupertonicModel.kt
- com/vayunmathur/speech/domain/SupertonicVoices.kt
### tts/ — voice-data check
- com/vayunmathur/speech/tts/CheckVoiceDataActivity.kt
### Root — demo UI only
- com/vayunmathur/speech/MainActivity.kt

## Verify (this module only)
```
./gradlew :speech:compileDevKotlin
./gradlew :speech:lint
./gradlew :speech:checkMetadata
```
