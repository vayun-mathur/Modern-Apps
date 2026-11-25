# AGENTS.md — library/aosp-stubs/ (':library:aosp-stubs')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `library/aosp-stubs/` + allowed shared modules. Do not root-scan.

- Gradle: :library:aosp-stubs / dir: library/aosp-stubs/
- Package roots present: (compile-only hidden-API stubs)
- Entry files: (none — stubs only, no runtime code)
- Deps: (none)
- Metadata: metadata_data/library-aosp-stubs.md (absent — libraries have none by design)
- Rust: no / screenshotTest: no

## Overview
**Purpose:** Compile-only stubs for Android hidden APIs in one module. Lets privileged modules (appstore, cast, updater, parentalcontrols, euicc) reference system APIs without the SDK exposing them.
**How to use:** Depend on it, call the hidden API, guard with runtime availability checks — stubs satisfy the compiler, the OS provides the implementation.
**Entry points:** The stub matching your hidden API (verify via scoped `ls`).
**Gotchas:** Stubs are signatures only — calling one on an OS build that lacks the API crashes at runtime. Verify stub coverage against `ls` before relying on it.

## Key files
- (No key files listed — verify via scoped `ls` when touching this module.)

## Verify (this module only)
```
./gradlew :library:aosp-stubs:compileDevKotlin
./gradlew :library:aosp-stubs:lint
```
