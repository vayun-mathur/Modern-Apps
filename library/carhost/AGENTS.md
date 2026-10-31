# AGENTS.md — library/carhost/ (':library:carhost')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `library/carhost/` + allowed shared modules. Do not root-scan.

<!-- last-reviewed: 2026-10-16 -->
- Gradle: :library:carhost / dir: library/carhost/
- Package roots present: (car template host — one package)
- Entry files: CarTemplateView.kt (no MainActivity/Route — library)
- Deps: :library:ui (permission-free since #884)
- Metadata: metadata_data/library-carhost.md (absent — libraries have none by design)
- Rust: no / screenshotTest: no

## Overview
**Purpose:** Car-template host views for `:auto` — `CarTemplateView` renders head-unit templates with `HostTemplateState`. Companion to `:auto:protocol` (wire) and the `auto/` app (session).
**How to use:** Host `CarTemplateView`, drive it from `HostTemplateState`; scope car UI work here, session work in `auto/`, wire work in `:auto:protocol`.
**Entry points:** `CarTemplateView.kt` → `HostTemplateState.kt`.
**Gotchas:** This card was missing entirely (no AGENTS.md existed) — created by the agent-docs redesign. Driver-distraction templating rules apply to anything rendered here.

## Key files
- com/vayunmathur/library/carhost/CarTemplateView.kt
- com/vayunmathur/library/carhost/HostTemplateState.kt

## Verify (this module only)
```
./gradlew :library:carhost:compileDevKotlin
./gradlew :library:carhost:lint
```
