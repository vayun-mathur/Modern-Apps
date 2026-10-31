# AGENTS.md — games/hub/ (':games:hub')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `games/hub/` + allowed shared modules. Do not root-scan.

_Install: ./install games/hub (dev by default)._
<!-- last-reviewed: 2026-10-16 -->
- Gradle: :games:hub / dir: games/hub/
- Package roots present: ui, data, provider
- Entry files: MainActivity.kt (no Route.kt — dashboard/list/detail/profile flow)
- Deps: :library, :library:ui, :library:room, :sdk:games, :sdk:controller, :library:e2ee-p2p
- Play on TV: the `:sdk:controller` pad screen lives here (`ui/screens/ControllerPad.kt`), not in the SDK (#884)
- Metadata: metadata_data/games-hub.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** Achievements sink for all MA games — profile, XP levels, daily streaks, activity feed, per-game detail. Games report in over local `:sdk:games` IPC; nothing leaves the device.
**Key features:** Dashboard + games list/detail, achievements + XP/level progression, streaks (`StreakCalculator`), local-IPC ingestion via `provider/GamesHubProvider`.
**Architecture:** `ui/screens/` + `ui/components/` (shared `library/ui/game/` chrome) → `viewmodel/GameHubViewModel` → `data/GamesHubRepository` over Room (achievements, activity, sessions, streaks, profiles); `util/` holds pure calculators + icon resolution.
**Entry points:** `MainActivity.kt` → `ui/screens/DashboardScreen.kt` → `viewmodel/GameHubViewModel.kt` → `data/GamesHubRepository.kt`.
**Gotchas:**
- `util/` + `viewmodel/` are NOT closed package roots — pending migration; don't add more files there
- Games report via `:sdk:games` contract — hub schema changes must stay compatible with every game's reporter

## Key files
### Root — entry (no Route.kt)
- com/vayunmathur/games/hub/MainActivity.kt
### data/ + provider/ — Room + IPC sink
- com/vayunmathur/games/hub/data/GamesHubDatabase.kt
- com/vayunmathur/games/hub/data/GamesHubRepository.kt
- com/vayunmathur/games/hub/data/dao/AchievementDao.kt
- com/vayunmathur/games/hub/data/dao/ActivityDao.kt
- com/vayunmathur/games/hub/data/dao/GameDao.kt
- com/vayunmathur/games/hub/data/dao/ProfileDao.kt
- com/vayunmathur/games/hub/data/dao/SessionDao.kt
- com/vayunmathur/games/hub/data/dao/StreakDao.kt
- com/vayunmathur/games/hub/data/entities/AchievementDefEntity.kt
- com/vayunmathur/games/hub/data/entities/AchievementProgressEntity.kt
- com/vayunmathur/games/hub/data/entities/ActivityEventEntity.kt
- com/vayunmathur/games/hub/data/entities/DailyStreakEntity.kt
- com/vayunmathur/games/hub/data/entities/HubGameEntity.kt
- com/vayunmathur/games/hub/data/entities/PlayerProfileEntity.kt
- com/vayunmathur/games/hub/data/entities/PlaySessionEntity.kt
- com/vayunmathur/games/hub/provider/GamesHubProvider.kt — `:sdk:games` sink
### viewmodel/ + util/ — state + pure calculators
- com/vayunmathur/games/hub/viewmodel/GameHubViewModel.kt
- com/vayunmathur/games/hub/util/HubUiContract.kt
- com/vayunmathur/games/hub/util/StreakCalculator.kt
- com/vayunmathur/games/hub/util/XpLevelCalculator.kt
- com/vayunmathur/games/hub/util/GameIconResolver.kt
- com/vayunmathur/games/hub/util/FormatUtils.kt
- com/vayunmathur/games/hub/util/AppBackupAgent.kt
### ui/ — dashboard, lists, profile (shared game chrome from library/ui/game/)
- com/vayunmathur/games/hub/ui/screens/DashboardScreen.kt
- com/vayunmathur/games/hub/ui/screens/DashboardPage.kt
- com/vayunmathur/games/hub/ui/screens/GamesListScreen.kt
- com/vayunmathur/games/hub/ui/screens/GamesListPage.kt
- com/vayunmathur/games/hub/ui/screens/GameDetailScreen.kt
- com/vayunmathur/games/hub/ui/screens/AchievementsScreen.kt
- com/vayunmathur/games/hub/ui/screens/ActivityFeedScreen.kt
- com/vayunmathur/games/hub/ui/screens/ProfileScreen.kt
- com/vayunmathur/games/hub/ui/screens/ProfilePage.kt
- com/vayunmathur/games/hub/ui/screens/SettingsScreen.kt
- com/vayunmathur/games/hub/ui/screens/ControllerPad.kt — phone-as-controller pad (moved from `:sdk:controller`, #884)
- com/vayunmathur/games/hub/ui/components/GameCard.kt
- com/vayunmathur/games/hub/ui/components/AchievementRow.kt
- com/vayunmathur/games/hub/ui/components/ActivityItem.kt
- com/vayunmathur/games/hub/ui/components/XpProgressBar.kt
- com/vayunmathur/games/hub/ui/components/StreakIndicator.kt
- com/vayunmathur/games/hub/ui/components/LevelBadge.kt
- com/vayunmathur/games/hub/ui/components/StatCard.kt
- com/vayunmathur/games/hub/ui/components/AvatarBadge.kt
- com/vayunmathur/games/hub/ui/components/AvatarIcon.kt

## Verify (this module only)
```
./gradlew :games:hub:compileDevKotlin
./gradlew :games:hub:lint
./gradlew :games:hub:checkMetadata
```
