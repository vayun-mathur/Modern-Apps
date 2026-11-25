# AGENTS.md — Modern-Apps

Monorepo: ~100 Gradle modules + Rust workspace. Do scoped work only.

## 1. Scope first (law)
1. Each module has its own `AGENTS.md` (e.g. `notes/AGENTS.md`, `games/voxels/AGENTS.md`, `library/ui/AGENTS.md`) — auto-loaded on read. Read it instead of listing the dir.
2. State `Scope: <dir/> + <shared>` before any scan. Stay inside those prefixes.
3. Expand only with one-line justification. Never root-recurse.
4. Don't read `CONTRIBUTING.md` whole (26KB). Pull a section only on demand.
5. Prefer 1 targeted read over 10 searches.
6. **Keep `AGENTS.md` current (mandatory).** If your change adds, removes, renames, or alters anything a module's `AGENTS.md` documents — entry files, key files, architecture/data-flow, features, gotchas, verification commands, module purpose — update that module's `AGENTS.md` (and this root file, when the change is repo-wide: conventions, shared-module ownership, app index) **in the same change**. Facts that stopped being true (removed deps, renamed files, dead behavior) must be corrected or deleted, not left standing. A change that makes an `AGENTS.md` claim false is incomplete. Conversely, don't churn `AGENTS.md` prose for changes it doesn't document.
7. **`AGENTS.md` describes the current state only.** No history: no changelogs, "previously/formerly/used to" narratives, migration stories, commit references, or dated change notes. Write present-tense descriptions of how the module works now; when you touch an `AGENTS.md`, strip historical content you find (keep any present-tense lesson, drop its narrative).

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
| network | `library/network/` | `NetworkClient`, pinned CAs in `assets/ca/`, `TrustBundle` (FIRST_PARTY/STANDARD/EXTENDED/SYSTEM/ESIM), `OfflineAware` banner |
| room | `library/room/` | Room 3 + SQLCipher (`implementRoom(libs)`) |
| map / ml / ocr | `library/map/`, `library/ml/`, `library/ocr/` | vector maps (INTERNET itself) / inference / OCR |
| image/media | `library/image/`, `library/media/` | image loading (offline-local; network apps opt into HTTP via `Builder.remote()` with a `NetworkClient` fetch fn) / AV playback |
| e2ee-p2p | `library/e2ee-p2p/` | E2EE, p2p, `Hkdf` |
| widgets/work | `library/widgets/`, `library/work/` | Glance widgets / WorkManager |
| biometric/ink | `library/biometric/`, `library/ink/` | biometric / stylus ink |
| downloadservice | `library/downloadservice/` | foreground downloads (INTERNET itself) |
| format | `library/format/` | relative-age bucketing + `"<amount>m ago"` wording (opt-in; calendar dates stay in `library/ui` `DateString`) |
| locationprovider | `library/locationprovider/` | location abstraction |
| aosp-stubs | `library/aosp-stubs/` | compile-only hidden-API stubs |
| sdk:games/cast/openassistant | `sdk/games/`, `sdk/cast/`, `sdk/openassistant/` | achievements / casting / assistant tools |
| build-logic | `build-logic/` | `common-conventions-app`, `launcherIcon`, `rustNativeLib` |
| lint-rules | `lint-rules/` | ToastUsage + DirectComposeAnimation + FileLength + DirectAndroidLog (fatal) + 9 advisory |

### Apps (Gradle key → dir)
Reference: `backup/` satisfies every rule; `notes/` second + `intents/` demo. `camera` + `games/voxels` hand-commit screenshots.
Full canonical index (Gradle key → dir + group + notes): `.llms/repo-map.md`.
`appstore→appstore/` `astronomy→astronomy/` `auto→auto/,auto/protocol/` `backup→backup/` `calculator→calculator/` `calendar→calendar/` `camera→camera/` `cast→cast/,cast/protocol/,cast/tv/` `clock→clock/` `code→code/` `communicate→communicate/` `contacts→contacts/` `education→education/` `email→email/` `emergency→emergency/` `euicc→euicc/` `everysync→everysync/` `files→files/` `findfamily→findfamily/` `findfamily-ios→apple/FindFamily/` (SwiftPM/xtool, not Gradle) `flashcards→flashcards/` `fooddelivery→fooddelivery/` `health→health/` `keyboard→keyboard/` `launcher→launcher/` `logviewer→logviewer/` `maps→maps/` `measure→measure/` `music→music/` `networklocation→networklocation/` `news→news/` `notes→notes/` `office→office/` `openassistant→openassistant/` `passwords→passwords/` `pdf→pdf/` `photos→photos/` `parentalcontrols→parentalcontrols/` `screentime→screentime/` `setupwizard→setupwizard/` `share→share/` `speech→speech/` `taxi→taxi/` `things→things/` `todo→todo/` `translate→translate/` `travel→travel/` `tuner→tuner/` `updater→updater/` `vpn→vpn/` `weather→weather/` `web→web/` `youpipe→youpipe/,youpipe/extractor/` `games-hub→games/hub/` `games-arrows→games/arrows/` `games-chess→games/chess/` `games-logicgate→games/logicgate/` `games-minesweeper→games/minesweeper/` `games-nonogram→games/nonogram/` `games-pipes→games/pipes/` `games-solitaire→games/solitaire/` `games-sudoku→games/sudoku/` `games-unblockjam→games/unblockjam/` `games-voxels→games/voxels/` `games-wordmaker→games/wordmaker/`
Slash == colon for Gradle (`games/voxels` == `games:voxels`).

### Never scan
`target/`, `scripts/`, `build/`, `.gradle/`, `.kotlin/`, `*/build/`, `*/schemas/` (single file ok), `metadata_data/photos/`, `third_party/`, `firmware/`, `*.onnx`, `*.log`, `personal/`.

### Backend server (outside this repo)
The backend (relay server for findfamily/everysync/office/voxels) lives outside this repo:

- Server checkout: `../location_share_server` (sibling of this repo root). On this dev machine: `C:\Users\Vayun\Documents\code\location_share_server`.
- Live server SSH: `ssh root@87.86.80.39`
- Server env template: `../location_share_server/.env.example` (copy to `../location_share_server/.env`, never commit secrets).
- The server's canonical API host is `api.maproj.org` (same Cloudflare FIRST_PARTY pins). Legacy `findfamily.cc` / `api.findfamily.cc` still serve every API path identically so older app versions keep working.
- Cloudflare R2: see `../location_share_server/src/handlers/os_admin.rs` for `R2::from_env()`; required vars `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, plus `R2_ENDPOINT` or `R2_ACCOUNT_ID`.

## 2. Conventions (check every time)
- `Toast` banned (fatal lint). Use `rememberMessenger().show()` in composable, `AppMessages.show()` elsewhere.
- Never `Icon()`/`painterResource()`/`androidx.compose.material.icons.*` in app. Add `IconXxx()` to `library/ui/.../Icons.kt`.
- Never raw `Scaffold`. Use `AppScaffold` family. Refresh/loading indication goes through the scaffold's pull-to-refresh box (`isRefreshing`/`onRefresh`/`refreshEnabled` on `AppScaffold` family; TV: `TvAppFrame.isRefreshing`/`TvLoading` top indicator), never a standalone `LoadingState`/`LoadingIndicator`/`CircularProgressIndicator`/`ContainedLoadingIndicator` or hand-rolled pull-to-refresh in app UI (fatal `ScaffoldLoading` lint). Truly local progress is a non-icon affordance (disabled state, progress text); real determinate progress is a `LinearProgressIndicator(progress=...)` bar. Exception marker only for library-internal helpers and genuinely non-screen contexts. Never raw `androidx.compose.animation` at call site — use `library.ui` helpers. Never `imePadding()/navigationBarsPadding()/systemBarsPadding()` in `library.ui`.
- Screen-level search goes through the scaffold's `search` parameter (`search = ScaffoldSearch(query, onQueryChange, placeholder)` on `AppScaffold`/`DetailScaffold`/`DetailLazyColumn`/`LazyListScaffold`; the field renders AS the top app bar in its title slot, title replaced, above the refresh box), never a standalone `CommonSearchBar`/`SearchBar`/`SearchBarInputField` or search-identified `TextField`/`OutlinedTextField` in app UI (fatal `ScaffoldSearch` lint). The `AppScaffold(topBar=...)` overload, `SetupScaffold`, and `TabbedPagerScaffold` (pages own their scaffolds) have no search slot by design; TV uses its own text field. Dialog/sheet/drawer-local filters and expandable overlay search with no scaffold host use the exception marker only.
- Room 3 only. Literal `androidx.room.` flagged even in comments. Never `buildDatabase()` at call site — subclass `library.room.RoomRepository`.
- Network only via `library.network.NetworkClient` + `TrustBundle` narrowest (`FIRST_PARTY/STANDARD/EXTENDED/SYSTEM/ESIM`).
- Package roots closed set: `ui data domain platform network intents service provider widget notifications auth sync telephony`. No `util/`. One public `@Composable` per file, named as file. `domain/` pure (no Android/Compose).
- Package grammar is normative in `docs/package-structure.md`: package segments name responsibilities/features, never generic buckets (`util`, `common`, `helper`, `manager`, `api`, `viewmodel`, …) or UI toolkits. Widget code is flat in `widget/` — never `widgets/`, `widget/glance/`, `glance/`, or any `widget.<child>/`.
- Every app with networking has exactly one public `network.Networking` entry point. It owns `NetworkClient`, the narrowest `TrustBundle`, URLs, endpoint paths, auth headers, retries, and status mapping. No package outside `network/` (and `library.network`) may construct raw API requests.
- Every native library has exactly one guarded wrapper in `platform/` named `<Feature>Native`; it is the only `System.loadLibrary` caller for that library and exposes availability instead of letting initialization fail. JNI-frozen FQNs are the only package exceptions and must carry the verbatim JNI marker.
- Every app has exactly one preferences owner in `data/` named `<App>Prefs`; only it opens the app’s DataStore/SharedPreferences, and it recovers from corruption with documented defaults. Persisted/external decoding goes through non-throwing helpers in `data/` or `domain/`; Room converters never throw.
- Every exported entry point has one validated contract in `intents/`; entry points consume validated values only. Regexes must be Android-ICU-valid (escape literal braces outside quantifiers/character classes). Widgets render from one immutable snapshot and use fallbacks for missing keys/items. One widget, one file: every file in `widget/` declares a `MaWidget` subclass at top level together with its receiver; no helper-only files in `widget/` (helpers nest inside the widget; non-Glance app helpers belong to their own root).
- Escape hatches verbatim: `// PACKAGE STRUCTURE EXCEPTION (JNI)`, `// RAW SCAFFOLD EXCEPTION: <reason>`, `// TOAST EXCEPTION: <reason>`, `// RAW ANIMATION EXCEPTION: <reason>`, `// SCAFFOLD LOADING EXCEPTION: <reason>`, `// SCAFFOLD SEARCH EXCEPTION: <reason>`.
- detekt rules live in `detekt.yml` (ui/domain/odf/service scoping rationale inline). Fix code, not config, to green a module.

## 3. Verify (slice touched, never whole repo)
```
./gradlew :<module>:compileDevKotlin
./gradlew :<module>:lint
./gradlew :<module>:checkMetadata
```
Gradle defaults to small heap (`-Xmx8g`, 2 workers). Don't raise it. New build logic must be configuration-cache compatible. New/changed UI previews: `./gradlew :<module>:metadata`.
Rust builds use the fast cargo profile for dev tasks and the release profile for release/ambiguous tasks, overridable with `-Pmodernapps.rustProfile=release|fast`.

## 4. Tools — use these, not raw gradle/adb
Work in repo dir. Experiments/logs go in `analysis/` (gitignored).

`./install` — build + session-install (handles `.idsig`/fs-verity + `versionCodeOverride` automatically):
```
./install <module> [...]              # release by default
./install [dev|release] <module> [...]
./install voxels                      # shorthand → games:voxels; dooraccess → personal:dooraccess
./install dev games/voxels            # slash == colon
./install contacts calendar           # multi-module, one gradlew invocation
./install 9 maps                      # 8 = Pixel 8, 9 = Pixel 9 (default: first non-emulator)
./install all / ./install release all # full repo (auto big-heap); release all caps R8 workers
./install --dry-run contacts
```
Rules: `release` default (dev is almost never the right install), `dev` only when asked, `debug` blocked. Never uninstall / clear data (emulator-only exception, never touch physical device). Never give placeholder commands — output copy-pasteable.

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

`./check` — static verification without building: `:<module>:detekt` (rules in `detekt.yml`) + `:<module>:lint` + `cargo check/clippy/fmt/doc --locked` for Rust modules (workspace members via `-p`, standalone crates in-dir; fmt is advisory-only until the tree is fmt-clean) + structure ratchets (`tools/structure_check.py`, manifests in `docs/structure/` — Wear protocol-pair drift, generated-source guard, exception-marker inventory; runs on every invocation):
```
./check <module> [...]   # e.g. ./check notes backup; ./check voxels
./check all              # every app + library module (pure-JVM modules excluded)
./check structure        # only the structure ratchets
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
- Commit message: line 1 is ≤80 chars, always in the format `appname: short description (#iss)`. Then a blank line and a full body: what changed and why, the context (bug/symptom, root cause, constraints, alternatives rejected), notable implementation details, user-visible effect, and how it was verified. Write in plain prose/bullets, wrapped at ~72 chars. The body feeds AI-written release notes, so make it complete; never stop at one line.
- LF only; never commit line-ending churn. `FAIL_ON_PROJECT_REPOS` — never add repos in modules. Read `SUPPLY_CHAIN_RISKS.md` before new third-party dep.
