# AGENTS.md — weather-wear/ (':weather:wear')
> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `weather-wear/` + `weather/`.
- Gradle: :weather:wear (dir weather-wear/)
- Entry: ui/WearMainActivity.kt
## Why
Wear OS companion of :weather. Same applicationId + signing (WearLink handshake requires same cert). Sideload once via ADB.
## Protocol
`domain/WearWeatherProtocol.kt` is a copy of phone's file. Change both; golden tests pin same JSON.
- Snapshot/watch-state: WearLink data items; request-sync: message watch->phone only.
- Offline: renders locally stored snapshot; no network on watch.
## Verify
./gradlew :weather:wear:assembleDev
./gradlew :weather:wear:testDebugUnitTest
