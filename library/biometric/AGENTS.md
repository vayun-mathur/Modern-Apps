# AGENTS.md — library/biometric/ (':library:biometric')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `library/biometric/` + allowed shared modules. Do not root-scan.

<!-- last-reviewed: 2026-10-16 -->
- Gradle: :library:biometric / dir: library/biometric/
- Package roots present: (single-purpose crypto helper — one package)
- Entry files: BiometricDatabaseHelper.kt (no MainActivity/Route — library)
- Deps: :library (permission-free; extends base's DatabaseHelper keystore, #884)
- Metadata: metadata_data/library-biometric.md (absent — libraries have none by design)
- Rust: no / screenshotTest: no

## Overview
**Purpose:** Biometric-bound database keys — encrypts Room/SQLCipher passphrases behind the system biometric gate. Used by passwords (vault) and apps with locked databases.
**How to use:** Route database open through `BiometricDatabaseHelper`; pair with `MaFragmentActivity(autoSetContent=false)` + `showContent()` on unlock (passwords, photos pattern).
**Entry points:** `BiometricDatabaseHelper.kt`.
**Gotchas:** Biometric enrollment changes invalidate keys by design — handle the locked-out path, don't crash on it.

## Key files
- com/vayunmathur/library/biometric/BiometricDatabaseHelper.kt

## Verify (this module only)
```
./gradlew :library:biometric:compileDevKotlin
./gradlew :library:biometric:lint
```
