# AGENTS.md — tuner/ (':tuner')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `tuner/` + allowed shared modules. Do not root-scan.

_Install: ./install tuner (dev by default)._
<!-- last-reviewed: 2026-10-16 -->
- Gradle: :tuner / dir: tuner/
- Package roots present: ui, data, domain, platform
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: (none declared — raw mic feed, no network permission)
- Metadata: metadata_data/tuner.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** Precise offline chromatic tuner (guitar/ukulele/piano) — sub-cent meter, 415–466 Hz reference, wrong-string warnings, honest uncertainty. No network permission.
**Key features:** Raw-mic `AudioCapture` → `domain/` DSP chain (YIN + phase-slope for notes; Constant-Q → chroma + voicing templates → HMM for chords, harmonic sieve for the displayed note set) → meter/note/chord UI with fret diagrams + piano keyboard; instrument profiles in `data/`.
**Entry points:** `Route` → `MainActivity.kt` → `Navigation.kt` → `ui/NoteScreen.kt` → `platform/TunerViewModel.kt` → `domain/PitchDetector.kt`.
**Gotchas:** `ChordDetector` defaults to `ChordExtractor.CHROMA_HMM`; SIEVE/NNLS stay selectable only for `RealAudioHarnessTest` comparisons. Chord accuracy is measured by `ChordProgressionEvalTest` (synthetic, `build/chord-eval-report.txt`) and `RealAudioHarnessTest` (Record-3, `build/real-audio-report.txt`) — re-run both when touching chord constants. Mic latency varies by device, keep the capture path lean.

## Key files
### Root — entry + navigation
- com/vayunmathur/tuner/MainActivity.kt
- com/vayunmathur/tuner/Navigation.kt
- com/vayunmathur/tuner/Route.kt
### data/ — instruments
- com/vayunmathur/tuner/data/Instruments.kt
### domain/ — pure DSP (no Android)
- com/vayunmathur/tuner/domain/PitchDetector.kt
- com/vayunmathur/tuner/domain/Yin.kt
- com/vayunmathur/tuner/domain/Fft.kt
- com/vayunmathur/tuner/domain/ConstantQ.kt
- com/vayunmathur/tuner/domain/HarmonicSieve.kt
- com/vayunmathur/tuner/domain/Nnls.kt
- com/vayunmathur/tuner/domain/PhaseSlope.kt
- com/vayunmathur/tuner/domain/Cents.kt
- com/vayunmathur/tuner/domain/TuningOffset.kt
- com/vayunmathur/tuner/domain/PresentationHold.kt
- com/vayunmathur/tuner/domain/Tonality.kt
- com/vayunmathur/tuner/domain/ChordDetector.kt
- com/vayunmathur/tuner/domain/Chroma.kt
- com/vayunmathur/tuner/domain/ChordTemplates.kt
- com/vayunmathur/tuner/domain/ChordHmm.kt
- com/vayunmathur/tuner/domain/ChordNaming.kt
- com/vayunmathur/tuner/domain/ChordPresenter.kt
- com/vayunmathur/tuner/domain/VoicingGenerator.kt
### platform/ — capture + ViewModel
- com/vayunmathur/tuner/platform/AudioCapture.kt
- com/vayunmathur/tuner/platform/TunerViewModel.kt
- com/vayunmathur/tuner/platform/TunerUiContract.kt
### ui/ — meter, notes, chords
- com/vayunmathur/tuner/ui/TunerTabs.kt
- com/vayunmathur/tuner/ui/NoteScreen.kt
- com/vayunmathur/tuner/ui/NotePage.kt
- com/vayunmathur/tuner/ui/TuningMeter.kt
- com/vayunmathur/tuner/ui/ChordScreen.kt
- com/vayunmathur/tuner/ui/ChordPage.kt
- com/vayunmathur/tuner/ui/FretDiagram.kt
- com/vayunmathur/tuner/ui/PianoKeyboard.kt
- com/vayunmathur/tuner/ui/InstrumentMenu.kt

## Verify (this module only)
```
./gradlew :tuner:compileDevKotlin
./gradlew :tuner:lint
./gradlew :tuner:checkMetadata
```
