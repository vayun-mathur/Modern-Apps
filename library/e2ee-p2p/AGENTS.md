# AGENTS.md — library/e2ee-p2p/ (':library:e2ee-p2p')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `library/e2ee-p2p/` + allowed shared modules. Do not root-scan.

<!-- last-reviewed: 2026-10-16 -->
- Gradle: :library:e2ee-p2p / dir: library/e2ee-p2p/
- Package roots present: (E2EE + P2P primitives — `com.vayunmathur.e2ee` package)
- Entry files: E2ee.kt + PqcIdentity.kt (no MainActivity/Route — library)
- Deps: (none — Rust PQC core via JNI)
- Metadata: metadata_data/library-e2ee-p2p.md (absent — libraries have none by design)
- Rust: yes / screenshotTest: no

## Overview
**Purpose:** End-to-end encryption + peer-to-peer primitives — PQC identity keys, AES content keys, safety numbers, invite fingerprints. Powers everysync vaults, voxels multiplayer, backup crypto. Relay/server sees ciphertext only.
**How to use:** Identities via `PqcIdentity` (Rust `PqcNative`); content via `E2ee` + `E2eeKeyStore`; verify contacts with `SecurityCode`/`InviteFingerprint`.
**Entry points:** `E2ee.kt` → `PqcIdentity.kt` → `PqcNative.kt` (Rust bridge).
**Gotchas:** Crypto changes need adversarial review; identity/key loss is unrecoverable by design — recovery flows (BIP-39 in backup) live in consumers, not here.

## Key files
- com/vayunmathur/e2ee/E2ee.kt
- com/vayunmathur/e2ee/E2eeKeyStore.kt
- com/vayunmathur/e2ee/PqcIdentity.kt
- com/vayunmathur/e2ee/Pqc.kt
- com/vayunmathur/e2ee/PqcNative.kt — Rust bridge
- com/vayunmathur/e2ee/SecurityCode.kt
- com/vayunmathur/e2ee/InviteFingerprint.kt
- com/vayunmathur/e2ee/Hkdf.kt — HKDF-SHA256 key derivation (moved from `:library`, #884)

## Verify (this module only)
```
./gradlew :library:e2ee-p2p:compileDevKotlin
./gradlew :library:e2ee-p2p:lint
```
