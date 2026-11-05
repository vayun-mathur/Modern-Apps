# AGENTS.md — library/e2ee-openpgp/ (':library:e2ee-openpgp')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `library/e2ee-openpgp/` + allowed shared modules. Do not root-scan.

<!-- last-reviewed: 2026-11-05 -->
- Gradle: :library:e2ee-openpgp / dir: library/e2ee-openpgp/
- Package roots present: (OpenPGP primitives — `com.vayunmathur.e2ee` package, shared with `:library:e2ee-p2p` by design so the JNI entry points stay stable)
- Entry files: Pgp.kt (no MainActivity/Route — library)
- Deps: (none — Rust rPGP core via JNI)
- Metadata: metadata_data/library-e2ee-openpgp.md (absent — libraries have none by design)
- Rust: yes / screenshotTest: no

## Overview
**Purpose:** OpenPGP (RFC 9580, Ed25519 primary + X25519 subkey) for SecureMail, backed by native Rust (rPGP) instead of BouncyCastle. Split out of `:library:e2ee-p2p`'s `e2ee_pqc` crate (#873): rPGP's cipher suite materially grows the `.so`, and only `:securemail` uses the PGP surface — every other e2ee-p2p consumer (office, cast, findfamily, everysync, backup, voxels, ...) links PQC only.
**How to use:** `Pgp.generateIdentity` / `encrypt` / `decrypt` / `decryptVerify` / `signDetached` (`Pgp.kt`); JNI via `PgpNative` (`libe2ee_pgp.so`). Keys cross as ASCII armor; the relay wire carries raw PGP bytes.
**Entry points:** `Pgp.kt` → `PgpNative.kt` (Rust bridge, `pgp_jni.rs`).
**Gotchas:** Crypto changes need adversarial review. Same `com.vayunmathur.e2ee` package as `:library:e2ee-p2p` is intentional (JNI names); keep it so. Phone-app only: arm64-v8a alone, no armeabi-v7a (unlike e2ee-p2p, which `:cast:tv` needs on 32-bit Google TV hardware).

## Key files
- com/vayunmathur/e2ee/Pgp.kt
- com/vayunmathur/e2ee/PgpNative.kt — Rust bridge (`// PACKAGE STRUCTURE EXCEPTION (JNI)`)

## Verify (this module only)
```
./gradlew :library:e2ee-openpgp:compileDevKotlin
./gradlew :library:e2ee-openpgp:lint
```
