# AGENTS.md — library/tv/ (':library:tv')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `library/tv/` + allowed shared modules. Do not root-scan.

<!-- last-reviewed: 2026-11-21 -->
- Gradle: :library:tv / dir: library/tv/
- Package roots present: (TV Compose components — one package)
- Entry files: TvAppFrame.kt + TvTheme.kt (no MainActivity/Route — library)
- Deps: :library, :library:ui
- Metadata: metadata_data/library-tv.md (absent — libraries have none by design)
- Rust: no / screenshotTest: no

## Overview
**Purpose:** Shared ten-foot TV Compose components — always-dark themed app frame with a navigation rail, D-pad-first cards/grids/controls/inputs, states and player transport, built on `androidx.tv.material`.
**How to use:** Host app wraps screens in `TvAppFrame` (or the route-backed overload with `TvRouteDestination`s), lays bodies out in `TvGrid`, and renders items with `TvCard`/`TvInfoCard`; accent per app via `TvAccent`, spacing via `TvDimens`.
**Entry points:** `TvAppFrame.kt` → `TvTheme.kt` → `TvGrid.kt` → `TvCards.kt`.
**Gotchas:**
- Consumers are TV surfaces in several apps — `cast/tv` (the receiver), plus the TV UIs in `appstore`, `uniflix` and `youpipe` (each declares `implementation(project(":library:tv"))`). Keep components app-agnostic: no app models, routes or stores in here.
- No INTERNET and no `:library:image` by design: this module must not inherit network/image loading, so the offline cast receiver stays offline. Image-bearing components take a caller-provided image slot/composable instead of a URL — never add `:library:image` or a URL loader here.
- Icons come only from `:library:ui` (`IconXxx()`); a destination's icon is an `IconXxx()` composable, never a raw resource in this module.
- D-pad first: focus must always have a starting target (`TvInitialFocus`), focused surfaces flip near-white with dark content, and `TvTextField` raises the IME only on an explicit CENTER press.
- Draw-only transport: `TvSeekBar` renders progress/markers only — the caller owns LEFT/RIGHT scrubbing and playback state.

## Key files
- com/vayunmathur/library/tv/TvAppFrame.kt — rail app frame (`TvDestination` / route-backed `TvRouteDestination`)
- com/vayunmathur/library/tv/TvTheme.kt — always-dark theme, `TvAccent`, `TvDimens`
- com/vayunmathur/library/tv/TvLayout.kt — content insets/padding for overscan-safe screens
- com/vayunmathur/library/tv/TvGrid.kt — scrolling screen grid + full-width rows
- com/vayunmathur/library/tv/TvCards.kt — media/catalog card (`TvAspect` ratios)
- com/vayunmathur/library/tv/TvInfoCard.kt — full-information list-row card
- com/vayunmathur/library/tv/TvControls.kt — `TvButton` / `TvIconButton`
- com/vayunmathur/library/tv/TvInput.kt — D-pad text field (IME on CENTER only)
- com/vayunmathur/library/tv/TvStates.kt — initial-focus helper + empty/error states
- com/vayunmathur/library/tv/TvTransport.kt — draw-only player seek bar

## Verify (this module only)
```
./gradlew :library:tv:compileDevKotlin
./gradlew :library:tv:lint
```
