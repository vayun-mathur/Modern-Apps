# AGENTS.md — lint-rules/ (':lint-rules')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `lint-rules/` + allowed shared modules. Do not root-scan.

<!-- last-reviewed: 2026-10-16 -->
- Gradle: :lint-rules / dir: lint-rules/
- Package roots present: (custom lint detectors — `com.vayunmathur.lint` package)
- Entry files: ModernAppsIssueRegistry.kt (no MainActivity/Route — lint registry)
- Deps: (lint APIs only)
- Metadata: metadata_data/lint-rules.md (absent — infra has none by design)
- Rust: no / screenshotTest: no

## Overview
**Purpose:** Custom Android-lint detectors enforcing repo conventions at build time — the executable form of root `AGENTS.md` §2. Fatal detectors break the build; advisory ones warn.
**Blocking:** every Modern-Apps detector is fatal in the app and library conventions. That includes `ToastUsage` (no `Toast` — use messenger), `DirectComposeAnimation` (no raw animation at call sites), `FileLength`, `DirectAndroidLog` (no direct `android.util.Log`), `DirectBuildDatabase` (subclass `RoomRepository`), `OneComposablePerFile`, `PackageStructure` (closed roots and flat `widget`), `RawScaffoldInApp` (use `AppScaffold`), `ScaffoldLoading` (scaffold owns refresh indication; standalone loading icons in app UI flagged), `Room2Usage` (Room 3 only), `WindowInsetsInReusableComponent` (no padding in `library.ui`), and the comment-hygiene detectors (`CommentedOutCode`, `ExceptionMarkerFormat`, `TodoEvasion` with `CommentOnlyMatcher`).
**Entry points:** `ModernAppsIssueRegistry.kt` → the detector for your rule → `LintPackageExclusions.kt` for scope.
**Gotchas:** New detectors need registry entries + severity decisions (fatal vs advisory is a deliberate call). Fix code to green modules, never relax config (`detekt.yml` rationale is inline per the root card).

## Key files
- com/vayunmathur/lint/ModernAppsIssueRegistry.kt — registry (all detectors listed here)
- com/vayunmathur/lint/ToastDetector.kt — fatal Toast ban
- com/vayunmathur/lint/DirectComposeAnimationDetector.kt — fatal raw-animation ban
- com/vayunmathur/lint/FileLengthDetector.kt — fatal file length
- com/vayunmathur/lint/DirectBuildDatabaseDetector.kt — RoomRepository rule
- com/vayunmathur/lint/Room2UsageDetector.kt — Room 3 rule
- com/vayunmathur/lint/RawScaffoldInAppDetector.kt — AppScaffold rule
- com/vayunmathur/lint/ScaffoldLoadingDetector.kt — scaffold-owned loading rule
- com/vayunmathur/lint/PackageStructureDetector.kt — closed-roots rule
- com/vayunmathur/lint/OneComposablePerFileDetector.kt
- com/vayunmathur/lint/WindowInsetsInReusableComponentDetector.kt
- com/vayunmathur/lint/CommentedOutCodeDetector.kt — comment hygiene
- com/vayunmathur/lint/ExceptionMarkerFormatDetector.kt — escape-hatch marker format
- com/vayunmathur/lint/TodoEvasionDetector.kt — TODO discipline
- com/vayunmathur/lint/CommentOnlyMatcher.kt — shared matcher for comment detectors
- com/vayunmathur/lint/LintPackageExclusions.kt — detector scoping

## Verify (this module only)
```
./gradlew :lint-rules:compileDevKotlin
./gradlew :lint-rules:lint
```
