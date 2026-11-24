# AGENTS.md — library/wearlink/ (':library:wearlink')

> Scoped supplement. Global rules in root `AGENTS.md` apply.

- Gradle: :library:wearlink / dir: library/wearlink/
- Package: `com.vayunmathur.library.wearlink` (single package, like :library:log)
- Entry: `WearLinkNode.kt` (Android), `WearLinkProtocol.kt` (pure wire)

## Purpose
First-party replacement for the proprietary Play-services Wearable Data
Layer. RFCOMM over already-bonded Bluetooth, both sides listen + dial,
cert-bound handshake. No Play services, no BLE scan/advertise, no location.

## Verify
```
./gradlew :library:wearlink:testDebugUnitTest
./gradlew :library:wearlink:lint
```
