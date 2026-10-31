# AGENTS.md — library/ocr/ (':library:ocr')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `library/ocr/` + allowed shared modules. Do not root-scan.

<!-- last-reviewed: 2026-10-16 -->
- Gradle: :library:ocr / dir: library/ocr/
- Package roots present: (on-device OCR — one package)
- Entry files: OcrEngine.kt (no MainActivity/Route — library)
- Deps: :library:ml (both permission-free; the OCR pipeline runs on the ml runtime, #884)
- Metadata: metadata_data/library-ocr.md (absent — libraries have none by design)
- Rust: no / screenshotTest: no

## Overview
**Purpose:** On-device OCR engine over `:library:ml` runtimes. Used by translate (camera overlay) and any image-text extraction.
**How to use:** `OcrEngine` in, text + boxes out; post-processing via `:library:ml` `OcrPost`/`TextRecognizer`.
**Entry points:** `OcrEngine.kt`.
**Gotchas:** Model files are large — first-run gating via `:library:downloadservice`, never block the camera path on a missing model.

## Key files
- com/vayunmathur/library/ocr/OcrEngine.kt

## Verify (this module only)
```
./gradlew :library:ocr:compileDevKotlin
./gradlew :library:ocr:lint
```
