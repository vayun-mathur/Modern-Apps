# AGENTS.md — library/ink/ (':library:ink')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `library/ink/` + allowed shared modules. Do not root-scan.

- Gradle: :library:ink / dir: library/ink/
- Package roots present: (stylus-ink view + serialization — one package)
- Entry files: InkCanvasView.kt (no MainActivity/Route — library)
- Deps: (none)
- Metadata: metadata_data/library-ink.md (absent — libraries have none by design)
- Rust: no / screenshotTest: no

## Overview
**Purpose:** Stylus/finger ink input — `InkCanvasView` capture + `InkSerialization` persistence. Used by notes (ink editor pages).
**How to use:** Embed `InkCanvasView`, persist strokes via `InkSerialization`; keep stroke data alongside the note model, not in the view.
**Entry points:** `InkCanvasView.kt` → `InkSerialization.kt`.
**Gotchas:** Palm rejection and stroke smoothing vary by device — test with hardware stylus, not mouse.

## Key files
- com/vayunmathur/library/ink/InkCanvasView.kt
- com/vayunmathur/library/ink/InkSerialization.kt

## Verify (this module only)
```
./gradlew :library:ink:compileDevKotlin
./gradlew :library:ink:lint
```
