# AGENTS.md — library/log/ (':library:log')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `library/log/` + allowed shared modules. Do not root-scan.

<!-- last-reviewed: 2026-10-16 -->
- Gradle: :library:log / dir: library/log/
- Package roots present: (logging facade — one package)
- Entry files: Log.kt + MaInitProvider.kt (no MainActivity/Route — library)
- Deps: (none)
- Metadata: metadata_data/library-log.md (absent — libraries have none by design)
- Rust: no / screenshotTest: no

## Overview
**Purpose:** Repo-wide logging facade — `Log` replaces `android.util.Log` everywhere (the literal `android.util.Log` import is lint-flagged). `MaInitProvider` (a `ContentProvider` merged from this module's manifest) inits the dev gate from the `debuggable` flag, which is why per-app `XxxApplication` classes were deleted.
**How to use:** `Log.d/tag` etc. everywhere; never `android.util.Log`. Dev-vs-release follows the manifest `debuggable` flag automatically — no per-app init code.
**Entry points:** `Log.kt` → `MaInitProvider.kt`.
**Gotchas:** This card was missing entirely (no AGENTS.md existed) — it was created by the agent-docs redesign. If an app still has an `Application` subclass doing only `Log.init`, delete it (everysync's is the exception — it schedules sync).

## Key files
- com/vayunmathur/library/log/Log.kt — the facade (use this, never `android.util.Log`); `newErrorId()`/`errorWithId()` mint + log a short error-correlation id
- com/vayunmathur/library/log/NativeLoader.kt — the one guarded `System.loadLibrary` caller; every `<Feature>Native` wrapper loads through it and exposes the returned availability flag
- com/vayunmathur/library/log/CrashCapture.kt — default uncaught-exception handler: persists last 3 crashes to `filesDir/crashes/last_crash.txt` (rotation, never throws), then chains to the previous handler
- com/vayunmathur/library/log/MaInitProvider.kt — dev-gate init + `CrashCapture.install` via manifest merger

## Verify (this module only)
```
./gradlew :library:log:compileDevKotlin
./gradlew :library:log:lint
```
