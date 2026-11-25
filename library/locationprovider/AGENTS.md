# AGENTS.md — library/locationprovider/ (':library:locationprovider')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `library/locationprovider/` + allowed shared modules. Do not root-scan.

- Gradle: :library:locationprovider / dir: library/locationprovider/
- Package roots present: provider
- Entry files: (see sources — location abstraction consumed by networklocation + apps)
- Deps: (verify in build.gradle.kts before adding)
- Metadata: metadata_data/library-locationprovider.md (absent — libraries have none by design)
- Rust: no / screenshotTest: no

## Overview
**Purpose:** Location abstraction over GPS/network providers — the interface `networklocation/` implements and apps consume, so location callers never touch providers directly.
**How to use:** Depend on the abstraction, not the implementation; `networklocation/` is the system-backed provider.
**Entry points:** Read the `provider/` sources first (add key files from `ls` when touching this module).
**Gotchas:** Verify coverage against `ls` before trusting this card.

## Key files
- (No key files listed — verify via scoped `ls` when touching this module.)

## Verify (this module only)
```
./gradlew :library:locationprovider:compileDevKotlin
./gradlew :library:locationprovider:lint
```
