# AGENTS.md — translate/ (':translate')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `translate/` + allowed shared modules. Do not root-scan.

_Install: ./install translate (dev by default)._
- Gradle: :translate / dir: translate/
- Package roots present: ui, data, domain, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :library:ml, :library:downloadservice, :library:ocr, :library:network
- Metadata: metadata_data/translate.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** Fully offline translator (NLLB-200, 202 languages) with camera OCR overlay, speech input, TTS output, and selection-menu translation from any app. Internet only for one-time SHA-256-verified model downloads.
**Key features:**
- Text translation over the on-device NLLB engine (`NllbTranslator` + `NllbModel`); language catalog + recents in `domain/`
- Camera mode: `CameraPreviewLayer` + `CameraOverlay` over `:library:ocr`; speech in via `SpeechRecognizerEngine`, spoken output via `TtsSpeaker`
- History + persistent settings (`data/TranslateSettings`); translation available from the system selection menu
**Architecture / data-flow:** `ui/` text/camera/language pages → `platform/TranslateViewModel` (+ `TranslateUiContract`) → `domain/TranslationEngine` → `platform/NllbTranslator` on the `:library:ml` runtime; models fetched once via `:library:downloadservice` and hash-verified before first use.
**Entry points + reading order:** `Route` → `MainActivity.kt` → `Navigation.kt` → `ui/TextTranslateScreen.kt` → `platform/TranslateViewModel.kt` → `domain/TranslationEngine.kt` → `platform/NllbTranslator.kt`.
**Gotchas:**
- First run without downloaded models must degrade gracefully — never assume the NLLB weights exist
- Camera overlay coordinates must track preview rotation; test on rotation, not just portrait

## Key files
### Root — entry + navigation
- com/vayunmathur/translate/MainActivity.kt
- com/vayunmathur/translate/Navigation.kt
- com/vayunmathur/translate/Route.kt
### data/ + domain/ — settings, languages, engine contract
- com/vayunmathur/translate/data/TranslateSettings.kt
- com/vayunmathur/translate/domain/TranslationEngine.kt
- com/vayunmathur/translate/domain/Languages.kt
- com/vayunmathur/translate/domain/RecentLanguages.kt
### platform/ — ViewModel, NLLB, speech, TTS
- com/vayunmathur/translate/platform/TranslateViewModel.kt
- com/vayunmathur/translate/platform/TranslateUiContract.kt
- com/vayunmathur/translate/platform/NllbTranslator.kt
- com/vayunmathur/translate/platform/NllbModel.kt
- com/vayunmathur/translate/platform/SpeechRecognizerEngine.kt
- com/vayunmathur/translate/platform/TtsSpeaker.kt
### ui/ — text, camera, image, picker
- com/vayunmathur/translate/ui/TextTranslateScreen.kt
- com/vayunmathur/translate/ui/TextTranslatePage.kt
- com/vayunmathur/translate/ui/CameraTranslateScreen.kt
- com/vayunmathur/translate/ui/CameraPreviewLayer.kt
- com/vayunmathur/translate/ui/CameraOverlay.kt
- com/vayunmathur/translate/ui/CameraBinding.kt
- com/vayunmathur/translate/ui/CameraFrameUtils.kt
- com/vayunmathur/translate/ui/ImageTranslateScreen.kt
- com/vayunmathur/translate/ui/ImageTranslatePage.kt
- com/vayunmathur/translate/ui/ImagePicker.kt
- com/vayunmathur/translate/ui/LanguagePickerScreen.kt
- com/vayunmathur/translate/ui/LanguagePickerPage.kt

## Verify (this module only)
```
./gradlew :translate:compileDevKotlin
./gradlew :translate:lint
./gradlew :translate:checkMetadata
```
