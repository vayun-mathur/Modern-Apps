# AGENTS.md — sdk/games/ (':sdk:games')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `sdk/games/` + allowed shared modules. Do not root-scan.

- Gradle: :sdk:games / dir: sdk/games/
- Package roots present: (public games SDK — one package)
- Entry files: GameHubClient.kt + GameHubContract.kt (no MainActivity/Route — SDK)
- Deps: (none)
- Metadata: metadata_data/sdk-games.md (absent — SDKs have none by design)
- Rust: no / screenshotTest: no

## Overview
**Purpose:** Public SDK games use to report achievements/progress to `games/hub` over local IPC — the contract side; the hub's `provider/GamesHubProvider` is the sink side.
**Consumers:** Every `games/*` module (`*AchievementsManager` → `GameHubClient`); hub reads via the contract.
**Entry points:** `GameHubContract.kt` → `GameHubClient.kt` → `GameCenter.kt`.
**Gotchas:** Contract changes must stay compatible with all 11 games + hub simultaneously — version the contract, never break it silently.

## Key files
- com/vayunmathur/sdk/games/GameHubContract.kt — the IPC contract (stability matters)
- com/vayunmathur/sdk/games/GameHubClient.kt
- com/vayunmathur/sdk/games/GameCenter.kt
- com/vayunmathur/sdk/games/Models.kt

## Verify (this module only)
```
./gradlew :sdk:games:compileDevKotlin
./gradlew :sdk:games:lint
```
