# AGENTS.md — Modern-Apps

Monorepo: ~100 Gradle modules + Rust workspace. Do scoped work only.

## 1. Scope first (law)
1. Each module has its own `AGENTS.md` (e.g. `notes/AGENTS.md`, `games/voxels/AGENTS.md`, `library/ui/AGENTS.md`) — auto-loaded on read. Read it instead of listing the dir.
2. State `Scope: <dir/> + <shared>` before any scan. Stay inside those prefixes.
3. Expand only with one-line justification. Never root-recurse.
4. Don't read `CONTRIBUTING.md` whole (26KB). Pull a section only on demand.
5. Prefer 1 targeted read over 10 searches.

### How to pick scope
| Task | Scope |
| :--- | :--- |
| UI bug in one app | that app + `library/ui` |
| DB / Room | that app + `library/room` |
| Network / TLS / certs | that app + `library/network` |
| Icons, scaffold, theme, messenger | that app + `library/ui` |
| Cross-app share / assistant tool | both apps + `library` (DTO in `library.intents.<app>`) |
| New app module | `settings.gradle.kts` + `build-logic/` + reference app (`backup/`, else `notes/`) |
| Store listing / screenshots | that app + `metadata_data/` |
| Rust / JNI / native | that app's `src/main/rust/` + root `Cargo.toml` |
| Build convention change | `build-logic/` only |
| Lint rule change | `lint-rules/` only |
| Games hub achievement | game + `sdk/games` |

### Shared modules (only pull when row above says so)
| Module | Path | What it owns |
| :--- | :--- | :--- |
| base | `library/` | nav keys, `AssistantIntent`, IPC DTOs (`library.intents.<app>`), `RoomRepository` base |
| ui | `library/ui/` | `Icons.kt` (ONLY `Icon()`/`painterResource()` allowed), `AppScaffold` family, `DynamicTheme`, messenger/`AppMessages`, motion helpers |
| network | `library/network/` | `NetworkClient`, pinned CAs in `assets/ca/`, `TrustBundle` (FIRST_PARTY/STANDARD/EXTENDED/SYSTEM/ESIM) |
| room | `library/room/` | Room 3 + SQLCipher (`implementRoom(libs)`) |
| map / ml / ocr | `library/map/`, `library/ml/`, `library/ocr/` | vector maps (INTERNET itself) / inference / OCR |
| image/media | `library/image/`, `library/media/` | image loading / AV playback |
| e2ee-p2p | `library/e2ee-p2p/` | E2EE, p2p |
| widgets/work | `library/widgets/`, `library/work/` | Glance widgets / WorkManager |
| biometric/ink | `library/biometric/`, `library/ink/` | biometric / stylus ink |
| downloadservice | `library/downloadservice/` | foreground downloads (INTERNET itself) |
| locationprovider | `library/locationprovider/` | location abstraction |
| aosp-stubs | `library/aosp-stubs/` | compile-only hidden-API stubs |
| sdk:games/cast/openassistant | `sdk/games/`, `sdk/cast/`, `sdk/openassistant/` | achievements / casting / assistant tools |
| build-logic | `build-logic/` | `common-conventions-app`, `launcherIcon`, `rustNativeLib` |
| lint-rules | `lint-rules/` | ToastUsage + DirectComposeAnimation + FileLength + DirectAndroidLog (fatal) + 9 advisory |

### Apps (Gradle key → dir)
Reference: `backup/` satisfies every rule; `notes/` second + `intents/` demo. `camera` + `games/voxels` hand-commit screenshots.
Full canonical index (Gradle key → dir + group + notes): `.llms/repo-map.md`.
`appstore→appstore/` `astronomy→astronomy/` `auto→auto/,auto/protocol/` `backup→backup/` `calculator→calculator/` `calendar→calendar/` `camera→camera/` `cast→cast/,cast/protocol/,cast/tv/` `clock→clock/` `code→code/` `communicate→communicate/` `contacts→contacts/` `education→education/` `email→email/` `emergency→emergency/` `euicc→euicc/` `everysync→everysync/` `files→files/` `findfamily→findfamily/` `flashcards→flashcards/` `fooddelivery→fooddelivery/` `health→health/` `keyboard→keyboard/` `launcher→launcher/` `logviewer→logviewer/` `maps→maps/` `measure→measure/` `music→music/` `networklocation→networklocation/` `notes→notes/` `office→office/` `openassistant→openassistant/` `passwords→passwords/` `pdf→pdf/` `photos→photos/` `parentalcontrols→parentalcontrols/` `screentime→screentime/` `setupwizard→setupwizard/` `share→share/` `speech→speech/` `taxi→taxi/` `things→things/` `todo→todo/` `translate→translate/` `travel→travel/` `tuner→tuner/` `updater→updater/` `vpn→vpn/` `weather→weather/` `web→web/` `youpipe→youpipe/,youpipe/extractor/` `games-hub→games/hub/` `games-arrows→games/arrows/` `games-chess→games/chess/` `games-logicgate→games/logicgate/` `games-minesweeper→games/minesweeper/` `games-nonogram→games/nonogram/` `games-pipes→games/pipes/` `games-solitaire→games/solitaire/` `games-sudoku→games/sudoku/` `games-unblockjam→games/unblockjam/` `games-voxels→games/voxels/` `games-wordmaker→games/wordmaker/`
Slash == colon for Gradle (`games/voxels` == `games:voxels`).

### Never scan
`target/`, `scripts/`, `build/`, `.gradle/`, `.kotlin/`, `*/build/`, `*/schemas/` (single file ok), `metadata_data/photos/`, `third_party/`, `firmware/`, `*.onnx`, `*.log`, `personal/`.

### Backend server (outside this repo)
The backend (relay server for findfamily/everysync/office/voxels) lives outside this repo:

- Server checkout: `../location_share_server` (sibling of this repo root). On this dev machine: `C:\Users\Vayun\Documents\code\location_share_server`.
- Live server SSH: `ssh root@87.86.80.39`
- Server env template: `../location_share_server/.env.example` (copy to `../location_share_server/.env`, never commit secrets).
- The server is the `findfamily.cc` host; clients have been migrated to `api.maproj.org` (same Cloudflare FIRST_PARTY pins). `findfamily.cc` remains valid for backward compatibility during the transition.
- Cloudflare R2: see `../location_share_server/src/handlers/os_admin.rs` for `R2::from_env()`; required vars `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, plus `R2_ENDPOINT` or `R2_ACCOUNT_ID`.

## 2. Conventions (check every time)
- `Toast` banned (fatal lint). Use `rememberMessenger().show()` in composable, `AppMessages.show()` elsewhere.
- Never `Icon()`/`painterResource()`/`androidx.compose.material.icons.*` in app. Add `IconXxx()` to `library/ui/.../Icons.kt`.
- Never raw `Scaffold`. Use `AppScaffold` family. Never raw `androidx.compose.animation` at call site — use `library.ui` helpers. Never `imePadding()/navigationBarsPadding()/systemBarsPadding()` in `library.ui`.
- Room 3 only. Literal `androidx.room.` flagged even in comments. Never `buildDatabase()` at call site — subclass `library.room.RoomRepository`.
- Network only via `library.network.NetworkClient` + `TrustBundle` narrowest (`FIRST_PARTY/STANDARD/EXTENDED/SYSTEM/ESIM`).
- Package roots closed set: `ui data domain platform network intents service provider widget notifications auth sync telephony`. No `util/`. One public `@Composable` per file, named as file. `domain/` pure (no Android/Compose).
- Escape hatches verbatim: `// PACKAGE STRUCTURE EXCEPTION (JNI)`, `// RAW SCAFFOLD EXCEPTION: <reason>`, `// TOAST EXCEPTION: <reason>`, `// RAW ANIMATION EXCEPTION: <reason>`.
- detekt rules live in `detekt.yml` (ui/domain/odf/service scoping rationale inline). Fix code, not config, to green a module.

## 3. Verify (slice touched, never whole repo)
```
./gradlew :<module>:compileDevKotlin
./gradlew :<module>:lint
./gradlew :<module>:checkMetadata
```
Gradle defaults to small heap (`-Xmx8g`, 2 workers). Don't raise it. New build logic must be configuration-cache compatible. New/changed UI previews: `./gradlew :<module>:metadata`.

## 4. Tools — use these, not raw gradle/adb
Work in repo dir. Experiments/logs go in `analysis/` (gitignored).

`./install` — build + session-install (handles `.idsig`/fs-verity + `versionCodeOverride` automatically):
```
./install <module> [...]              # dev by default
./install [dev|release] <module> [...]
./install voxels                      # shorthand → games:voxels; dooraccess → personal:dooraccess
./install dev games/voxels            # slash == colon
./install contacts calendar           # multi-module, one gradlew invocation
./install 9 maps                      # 8 = Pixel 8, 9 = Pixel 9 (default: first non-emulator)
./install all / ./install dev all     # full repo (auto big-heap); release all caps R8 workers
./install --dry-run dev contacts
```
Rules: `dev` default, `release` only when asked, `debug` blocked. Never uninstall / clear data (emulator-only exception, never touch physical device). Never give placeholder commands — output copy-pasteable.

`./dhu` — Desktop Head Unit loopback for `auto/` (Pixel 8 default):
```
./dhu                                 # adb forward tcp:5277 + live logcat → analysis/dhu-<ts>.log + DHU window
./dhu -NoLog                          # DHU only
./dhu -DeviceId <serial>              # non-default device
```

`./search` — scoped string search (bash only, `rg` when present, grep fallback). Pattern + optional `-m` (module) / `-p` (root package) filters, composable:
```
./search DB_NAME -m notes -p data      # both: data/ package of notes/ only
./search RoomRepository -m notes       # module only
./search TrustBundle -m web -p network # module + package combined
./search "foo" -m voxels               # shorthand → games:voxels; slash == colon; -m all = everything
./search "bar" -m notes --files-only --max 20
```
Never grep repo-wide when a filter will do. Always excluded: `target/ build/ .gradle/ .kotlin/ .git/ .llms/ analysis/ *.log *.onnx metadata_data/photos/`.

`./check` — static verification without building: `:<module>:detekt` (rules in `detekt.yml`) + `:<module>:lint` + `cargo check/clippy/fmt/doc --locked` for Rust modules (workspace members via `-p`, standalone crates in-dir; fmt is advisory-only until the tree is fmt-clean):
```
./check <module> [...]   # e.g. ./check notes backup; ./check voxels
./check all              # every app + library module (pure-JVM modules excluded)
```
Findings fail the task. Only `notes/`, `backup/`, `library/ui/`, `games/voxels/` are detekt-clean today; other modules carry incremental backlog — fixing a module means `./check <module>` goes green, not config relaxation.

### On-device file map (per-module `AGENTS.md` carries concrete names)
- Room (`RoomRepository`, `DB_NAME` in `data/`): `/data/data/<pkg>/databases/<DB_NAME>` (+ `-wal`/`-shm` sidecars). SQLCipher-encrypted. `getDatabasePath`/`deleteDatabase`/`LEGACY_*` names are the same dir.
- DataStore: `preferencesDataStore(name=X)` → `/data/data/<pkg>/files/datastore/X.preferences_pb`; shared `DataStoreUtils` → `/data/data/<pkg>/files/datastore_default.preferences_pb`.
- Downloads (`InitialDownloadChecker`/`InitialModelDownloadChecker`, file lists in `platform/` or `data/`): each `fileName` → `/storage/emulated/0/Android/data/<pkg>/files/<fileName>` (`+.part` while downloading).
- Assets (`src/main/assets/`): APK-bundled, read via `AssetManager` — no on-device path. Library assets (e.g. `library/network` CAs) ship inside each dependent APK.
- Library modules have no `applicationId`: paths resolve under the hosting app's `<pkg>`.

## 5. Git / safety
- Other agents share the tree. Prohibited except reading state: `stash/revert/reset/checkout . /clean` etc. Only `add` + `commit` allowed, and only paths you changed — check `git status` first, never `add -A/.`, never stage without committing instantly.
- Commit: one line ≤80 chars always following the format: `appname: short description (#iss)`.
- LF only; never commit line-ending churn. `FAIL_ON_PROJECT_REPOS` — never add repos in modules. Read `SUPPLY_CHAIN_RISKS.md` before new third-party dep.
