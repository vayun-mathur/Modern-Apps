# Contributing

Modern Apps is a monorepo of ~57 Android apps sharing one design system and a set of common
library modules. See [README.md](README.md) for what the project is and the list of apps.

This document is the rulebook: the conventions the code follows, which of them are enforced
mechanically, and how to run those checks yourself.

---

## Before you start

- **If you are implementing a feature, open an issue first** so you don't waste time on a
  feature that won't be included.
- **Include photos demonstrating your code works.** Not needed if the change is short
  (<10 lines) or common sense is proof enough.
- **AI code is allowed** generally, as long as you have demonstrated that your code works.
- **AI is not allowed for translations**, and neither is any translation software like
  Google Translate.

---

## Getting set up

| Requirement | Version | Notes |
| :--- | :--- | :--- |
| JDK | 21 | The PR check still runs 17; the release build runs 21 |
| Android SDK | compileSdk 37 | `minSdk 31`, `targetSdk 37` |
| NDK | 29.0.14206865 | Only needed for modules with Rust |
| Rust | 1.97.0 | Pinned in `rust-toolchain.toml`; target `aarch64-linux-android` |

Point `local.properties` at your SDK. Apps build **arm64-only** by default.

**Gradle memory.** `gradle.properties` defaults to a small daemon heap (`-Xmx8g`,
`-Xms512m`, 2 workers) sized for scoped `:module:` builds — the thing agents and
day-to-day dev actually run. `./install all` auto-escalates to the big heap for
the full ~100-module build, so don't raise the default. If your machine needs
different values, override them for your machine in
`~/.gradle/gradle.properties` (which takes precedence), the way CI does:

```properties
org.gradle.jvmargs=-Xmx6144m -XX:MaxMetaspaceSize=1024m -XX:+UseG1GC
org.gradle.workers.max=2
```

**The configuration cache is on repo-wide.** Any build logic you add must be
configuration-cache compatible — resolve `File`s at configuration time and don't capture
script objects in a task action.

### Building and installing

```bash
./install voxels               # ./gradlew :games:voxels:installDev
./install release calculator   # release variant
./install all                  # every app module
```

`./install` accepts slash or colon paths (`games/voxels` == `games:voxels`) and auto-prefixes
game shorthands. All repo scripts (`install`, `dhu`, `search`, `analyze`) are bash-only —
run them from git-bash on Windows.

**There is no `debug` build type.** Only `dev` and `release` exist — `debug` variants are
disabled in the app convention. `dev` is `release` with minification off and
`isDebuggable = true`, and `testBuildType = "dev"`. Every app gets `BuildConfig.DEV_BUILD`;
gate experimental work on it so R8 strips it from release builds.

---

## What is actually enforced

Mind the gap between intent and enforcement. Most rules below are declared `Severity.ERROR`,
but the app convention demotes them to `warning`, so they are **reported, not blocking**. Only
the two rules listed as `fatal` can fail a build.

| Check | Blocks? | Where |
| :--- | :--- | :--- |
| `./gradlew :compileDevKotlin` | **Yes** | The only automated PR gate |
| `./gradlew checkMetadata` | **Yes** | First step of the release workflow |
| `ToastUsage` lint | **Yes** (`fatal`) | Local `./gradlew lint` only — CI builds with `-x lint` |
| `DirectComposeAnimation` lint | **Yes** (`fatal`) | Local `./gradlew lint` only — CI builds with `-x lint` |
| The other 6 custom lint rules | No | Reported by `./gradlew lint` |
| `scripts/*.sh` audits | No | Manual only |
| Unit tests | No | CI builds with `-x test` |

**CI only checks that the code compiles.** Everything else is on you. Run this before pushing:

```bash
./gradlew :compileDevKotlin                  # what CI will run
./gradlew lint                               # ToastUsage is fatal, the rest advisory
./gradlew checkMetadata                      # enforced at release time
bash scripts/lint_hardcoded_strings.sh
bash scripts/check_preview_metadata.sh
bash scripts/lint_rust_length.sh             # Rust files over 500 lines, advisory
```

Treat the advisory rules as mandatory for code you write or touch. Older modules predate them
and are being migrated; a violation you can see in `maps/util/` is not permission to add
another.

---

## Adding a new app

1. Create the module directory (nested is fine: `games/<name>`).
2. `include(":<name>")` in `settings.gradle.kts`.
3. Apply the convention plugin and declare only what is yours:

```kotlin
plugins {
    id("common-conventions-app")
    id("common-conventions-preview-metadata")   // if the listing uses Compose previews
}

launcherIcon {
    symbol = "calculate"                        // a Material Symbols name; required
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.calculator"
    }
}

dependencies {
    implementation(project(":library:network"))
}
```

4. Add `metadata_data/<module-key>.md` (see [Store metadata](#store-metadata)).
5. Add the app to the three hardcoded lists that nothing validates:
   `.github/workflows/issue_labeler.yml` (`validApps`),
   `.github/ISSUE_TEMPLATE/bug_report.yml`, and `.github/ISSUE_TEMPLATE/feature_request.yml`.
6. Add it to the table in `README.md`.

**The module key** is the Gradle path with `/` replaced by `-`, so `games/voxels` →
`games-voxels`. It is used for the metadata filename and the screenshot directory.

**`applicationId` is the only identity you declare.** `namespace` is derived as
`com.vayunmathur<gradle path>`, so the package follows the module path and cannot be chosen
independently.

### What the convention already gives you

Don't redeclare: `compileSdk`, `minSdk`, `targetSdk`, `ndkVersion`, `namespace`,
`versionCode`/`versionName` (both read from `version.txt`), signing, build types, Compose
setup, locale config, or reproducible-archive settings. You automatically get `:library`,
`:library:ui`, `:lint-rules`, Compose BOM, lifecycle, serialization and `kotlin-test`.

**Material 3 is deliberately not a direct dependency.** Consume Material only through
`:library:ui`, which re-exports it via `api`. Do not add `androidx.compose.material3` to an
app module.

**Launcher icons are generated, not committed.** `launcherIcon { symbol = "..." }` downloads
the Material Symbol at a pinned commit and generates the foreground and adaptive-icon XML. Do
not add `ic_launcher_foreground.xml` or mipmap PNGs.

---

## Module layout

Every file's package must be `com.vayunmathur.<app>.<root>`, where `<root>` is one of exactly
these 13:

```
ui  data  domain  platform  network  intents  service
provider  widget  notifications  auth  sync  telephony
```

| Root | Holds |
| :--- | :--- |
| `ui/` | Screens and composables; `ui/components/`, `ui/dialogs/` |
| `data/` | Entities, Room databases, repositories, stores, preferences |
| `domain/` | Pure logic — no Android, no Compose |
| `platform/` | ViewModels, Workers, framework glue, backup agents |
| `network/` | HTTP and protocol clients |
| `service/` | `Service` subclasses |
| `provider/` | `ContentProvider`s |
| `intents/` | Cross-app IPC entry points |
| `widget/` | App widgets and Glance |
| `notifications/`, `auth/`, `sync/`, `telephony/` | Narrower, for the apps that need them |

**`util/` is not a root.** It is the thing being migrated away from — several older modules
still have one. Split it:

| Instead of | Use |
| :--- | :--- |
| `util` | `domain/` (pure logic) or `platform/` (Android glue) |
| `api` | `network/` |
| `model` | `data/` |
| `viewmodel` | `platform/` |
| `ipc` | `intents/` |
| `glance` | `widget/glance/` |
| `crypto`, `format`, `syntax` | `domain/…` |
| `imap`, `smtp`, `remote` | `network/…` |
| `saf`, `sink` | `data/…` |
| `ime`, `tts` | `platform/…` |
| `composer` | `ui/composer/` |

### The app root package is a closed set of four files

A file directly in `com.vayunmathur.<app>` may only be:

| File | Contract |
| :--- | :--- |
| `MainActivity.kt` | Only `class MainActivity : ComponentActivity()` — no top-level functions, vals, or composables |
| `Route.kt` | Only `sealed interface Route : NavKey` plus its nested route objects |
| `Navigation.kt` | Only `@Composable fun Navigation(...)` |
| `*Application.kt` | Only the `Application` subclass, named the same as the file |

Anything else belongs in a folder. `backup/` is the reference module — it satisfies every rule;
`notes/` is a close second and also demonstrates `intents/`.

Shared modules (`com.vayunmathur.library`, `.e2ee`, `.sdk`, `.cast.protocol`, `.games`,
`.tools`, `.personal`) are exempt from this rule and from one-composable-per-file.

**JNI escape hatch.** A class whose fully-qualified name is frozen by `RegisterNatives` can
opt out with this exact comment anywhere in the file:

```kotlin
// PACKAGE STRUCTURE EXCEPTION (JNI)
```

---

## Compose

### One public composable per file

Exactly one **public, top-level** `@Composable` per file, named the same as the file.
Overloads of that composable and any number of `private`/`internal` helpers may share it. A
second differently-named public composable goes in its own file under `ui/`,
`ui/components/`, or `ui/dialogs/`.

### Never use a raw `Scaffold`

Not `androidx.compose.material3.Scaffold`, and not the thin `com.vayunmathur.library.ui.Scaffold`
wrapper. Use one of the shared scaffolds from `:library:ui`, which own window insets and
content padding once so screens don't re-derive padding by hand:

`AppScaffold` · `DetailScaffold` · `DetailLazyColumn` · `ListPage` · `LazyListScaffold` ·
`TabbedPagerScaffold` · `TopAppBarOverlay` (full-bleed)

`AppScaffold` requires `scrollBehavior` rather than defaulting it, so every screen states how
its bar reacts to scrolling, and it hands `PaddingValues` to its content instead of applying
them, so lists can scroll under the bars.

Per-file opt-out, when a screen genuinely needs its own:

```kotlin
// RAW SCAFFOLD EXCEPTION: <reason>
```

### Insets belong to the host

Do not call `imePadding()`, `navigationBarsPadding()` or `systemBarsPadding()` in a reusable
component under `com.vayunmathur.library.ui`. `MainNavigation` applies the IME inset once for
every screen it hosts, and a standalone Activity applies it once at its root scaffold. A
component that also applies one makes it count twice. App screens are free to apply insets —
the rule is scoped to the shared UI library.

### Theme

Every app wraps its content in `DynamicTheme { }` from `:library:ui`. Material You is
unconditional — there is no static fallback palette and no per-app brand colour, which is what
`minSdk 31` buys. It uses `MaterialExpressiveTheme`, so **read corner radii from
`MaterialTheme.shapes`** rather than writing `RoundedCornerShape(12.dp)` at the call site.
Shapes, typography and motion are deliberately not overridden.

Pass `darkTheme` explicitly only if the app has its own light/dark/auto setting; `null` follows
the system.

### Icons

Every icon is a function in `library/ui/.../Icons.kt`. **Never import
`androidx.compose.material.icons.*`, and never call `Icon()` or `painterResource()` from an app
module** — `Icons.kt` is the one place allowed to.

```kotlin
@Composable
fun IconAdd(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Add, "Add", modifier, tint)
```

The indirection exists because it makes two correctness properties automatic: a non-null
`contentDescription` is baked in so accessibility can't be forgotten, and directional icons use
`Icons.AutoMirrored.*` so RTL is handled centrally.

To add one: check the ~450 that already exist, then add a three-line `IconXxx` in the right
`// --- section ---` with the identical signature. **Name it for what it means here, not for
the upstream glyph** — `IconMine` is `Brightness7`, `IconEraser` is `Backspace`. If Material has
no suitable glyph, put a 24dp vector in `library`'s res and wrap `painterResource` the same way.

### Never use `Toast`

This is one of two lint rules marked `fatal`. Toasts render outside the app's Material
surface, ignore the theme, can't carry an action, and are silently suppressed when the app is
backgrounded on Android 12+ — so the case people reach for a Toast in, reporting from
background work, is the case where it may never appear.

```kotlin
rememberMessenger().show(...)   // inside a composable
AppMessages.show(...)           // from a ViewModel, Worker, or Activity
```

Use a notification when the message has to outlive the current screen.

Per-file opt-out, for a UI-less component with no scaffold collecting `AppMessages` — a
notification trampoline that finishes immediately, for instance:

```kotlin
// TOAST EXCEPTION: <reason>
```

### Never animate directly with `androidx.compose.animation`

The other `fatal` rule, `DirectComposeAnimation`. Motion written at a call site carries no
intent: the same interaction picks up a different duration and curve on each screen, and a spec
written with no arguments silently takes Compose's own default instead of the motion scheme the
app is themed with. Reach for the helper that names the interaction:

| Instead of | Use | From |
| :--- | :--- | :--- |
| `animateDpAsState` / `animateFloatAsState` / `animateColorAsState` | `animatedDp` / `animatedFloat` / `animatedColor` | `library.ui` |
| `AnimatedVisibility` + `fadeIn`/`scaleIn`/`expandVertically` | `FadeVisibility` / `PopVisibility` / `ExpandVisibility` / `BannerVisibility` | `library.ui` |
| `AnimatedContent` | `SwappedContent` / `SwappedTopBar` | `library.ui` |
| `Modifier.animateItem(...)` | `itemMotion()` | `library.ui` |
| a press-shape `animateIntAsState` | `pressedShape(pressed)` | `library.ui` |
| a hand-rolled entrance | `Modifier.staggeredEntrance(index, arriving)` | `library.ui` |
| a shared element across a nav change | `sharedText` / `sharedContainer` / `sharedCrop` / `SharedEditableText` / `MorphPage()` | `library.util` |
| a card that becomes a much bigger thing | `sharedCrop` — crops its contents instead of reflowing (`sharedContainer`) or scaling (`sharedText`) them | `library.util` |
| a hand-rolled exit for chrome the user navigates away from | `Modifier.dropsAway()` / `Modifier.sinksBelow()` / `Modifier.expandFromLine()` | `library.util` |

Imperative one-off motion on `androidx.compose.animation.core.Animatable` is **not** flagged — a
game board wave or a camera capture flash is genuinely bespoke, and wrapping it would be a
passthrough that names nothing. `com.vayunmathur.library.*` is exempt because the helpers are
built there, and `com.vayunmathur.launcher.*` is exempt because its home screen is built on
`animateBounds` and `Animatable`. Anything else that genuinely needs the raw API opts out per
file with the exact marker:

```kotlin
// RAW ANIMATION EXCEPTION: <reason>
```

### Screens are stateless; a binder wires the ViewModel

A `*UiContract.kt` next to the ViewModel in `platform/` declares the state and callbacks. The
`*Screen` composable is stateless and takes that state. A thin `*Page` composable collects
from the ViewModel and calls the screen. This is what lets `intents/` entry points and
screenshot previews reuse a screen with literal state.

---

## Data and storage

**Room 3 only.** The literal string `androidx.room.` is flagged — including in comments and
KDoc — because Room 3 dropped the `SupportSQLite` API that Room 2's `openHelperFactory(...)`
depended on, and SQLCipher is wired through `setDriver(SQLCipherDriver(...))` instead. Three
renames you will hit:

- `@TypeConverter` / `@TypeConverters` → `@ColumnTypeConverter` / `@ColumnTypeConverters`
- `withTransaction { }` → `withWriteTransaction { }` or `withReadTransaction { }`
- `Migration.migrate` now takes a suspending `androidx.sqlite.SQLiteConnection`

Room 2 staying on the runtime classpath via `androidx.work:work-runtime` is expected and must
not be excluded — WorkManager needs it.

**Never call `buildDatabase(...)` directly.** Subclass
`com.vayunmathur.library.room.RoomRepository<DB>` and expose DAOs, Flows and suspend
operations from it, then get that repository as a process-wide singleton from Activities,
Services, Workers, Receivers, widgets and assistant intents. Building the database at each call
site scatters construction, duplicates DAO holders, and risks leaking a component `Context`
into a long-lived database.

Opting in:

```kotlin
plugins { alias(libs.plugins.ksp) }

dependencies {
    implementRoom(libs)
    implementation(project(":library:room"))
}
```

Schemas are committed under `<module>/schemas/`.

---

## Networking

Go through `com.vayunmathur.library.network.NetworkClient` rather than raw OkHttp, Retrofit or
`HttpURLConnection`. Depend on `:library:network`, declare `android.permission.INTERNET`, and
initialise once in `MainActivity`:

```kotlin
NetworkClient.init(this, TrustBundle.FIRST_PARTY)
```

TLS validates against a bundle of pinned roots shipped in `library/network/src/main/assets/ca/`,
not the system store. Pick the narrowest `TrustBundle` that covers your hosts —
`FIRST_PARTY`, `STANDARD`, `EXTENDED`, or `SYSTEM` for user-supplied hosts (VPN,
custom mail servers, the browser).

Note that `:library:map`, `:library:image` and `:library:downloadservice` declare `INTERNET`
themselves, so depending on one gives your app network access through manifest merge. Account
for that in your store metadata.

Per-app protocol code lives in `network/`; the transport comes from the library.

---

## Cross-app IPC

Apps expose data to their siblings through a signature-level permission and an invisible
activity, so only APKs signed with the same key can call in:

```xml
<permission
    android:name="com.vayunmathur.notes.permissions.ACCESS_NOTES"
    android:protectionLevel="signature" />

<activity android:name=".intents.GetIntent"
    android:exported="true"
    android:theme="@android:style/Theme.NoDisplay"
    android:permission="com.vayunmathur.notes.permissions.ACCESS_NOTES" />
```

The endpoint subclasses `AssistantIntent<In, Out>` from `:library` and overrides one method:

```kotlin
class GetIntent : AssistantIntent<Unit, List<NoteData>>(
    Unit.serializer(), ListSerializer(NoteData.serializer())
) {
    override suspend fun performCalculation(input: Unit): List<NoteData> =
        NotesRepository.get(this).getAll().map { NoteData(it.title, it.content) }
}
```

The wire DTO lives in `:library` (`com.vayunmathur.library.intents.<app>.<Dto>`) so both sides
compile against the same type and no one hand-parses anything. Serializers are passed
explicitly because generics are erased. On the consumer side the convention is
`<Thing>Client.kt` plus `<Thing>Protocol.kt`.

This is how MA OpenAssistant's tool calling reaches other apps. Games report achievements to
MA GameHub through a `ContentProvider` instead, via `:sdk:games`.

---

## Strings and translations

**No hardcoded user-visible text.** Everything goes in `res/values/strings.xml` and is read
with `stringResource(R.string.x)` or `getString(...)`. This covers `Text("…")`,
`label = { Text("…") }`, `placeholder = { Text("…") }`, and — despite a stale comment in the
script — `contentDescription`.

**Use `<plurals>`, never string-gluing.** `if (n == 1) "" else "s"` is a violation; the
one-versus-many split is language-specific.

Decorative single characters (`×`, `→`, `⌫`, …) and interpolation-only strings are exempt.
Adding a new symbol means editing the allow-list in `scripts/filter_text.py`.

Write a translator-facing comment above each string naming the screen, the widget and the
surrounding format.

**Translations happen on [Weblate](https://hosted.weblate.org/projects/modern-apps/), not in
PRs.** Add only `values/strings.xml`; Weblate owns every `values-<lang>/strings.xml`. Empty
per-locale skeletons are normal, which is why `MissingTranslation` is disabled. Translations
may not even be included in a PR that adds new strings, due to the way Weblate is set up.

After pulling Weblate translations, run `./gradlew fixStrings` — it repairs contributions where
`<xliff:g>` markup was escaped and would otherwise render literally in the UI.

Logging goes through `android.util.Log`. Timber is not a dependency and adding one to satisfy a
lint check would be the wrong way round.

---

## Store metadata

Every app module needs a `metadata_data/<module-key>.md`. Line 1 is the store short
description and the whole file is the full description, so the
format is strict and `./gradlew checkMetadata` enforces it both ways — a missing file and an
orphaned file are both errors.

```
A modern, open-source calculator with a graphing engine

Features:
- Scientific and basic calculator modes
- Function graphing with root, intercept, min/max, and intersection detection
- Material You design

Internet only used for: live currency exchange rates in the unit converter
```

1. Line 1 is the summary, **at most 80 characters** — Play and F-Droid cap it there.
2. Line 2 is blank.
3. Line 3 is exactly `Features:`.
4. Then one or more `- ` bullets, and nothing else.
5. Then a blank line, then a single connectivity line, exactly one of:
   - `100% offline`
   - `Requires internet`
   - `Internet required only for initial asset downloads`
   - `Internet only used for: <feature(s)>`

Use the last form only when the app still mostly works without a connection. Claims must match
the code: check the `INTERNET` permission and the hosts the app actually contacts, including
ones merged in from library modules.

### Listing screenshots

Apps render their store screenshots from Compose previews. Add
`id("common-conventions-preview-metadata")`, put previews in `src/screenshotTest/kotlin/`, and
run `./gradlew :<module>:metadata` to write numbered PNGs into per-form-factor
buckets under `metadata_data/photos/<module-key>/` — `phone/`, `tablet/`,
`large-tablet/`, `tv/`, `wear/`, using the Triple-T spellings verbatim. That
directory is wiped and rewritten each time.

Preview functions must:

- carry **both** `@PreviewTest` and `@Preview` — `@Preview` alone silently discovers no tests
- be **members of a class**, not top-level, or they land in a `…Kt` facade the engine skips
- be named `Preview1Foo`, `Preview2Bar`, … — **the number is the listing order
  within its bucket**, so renumber if you reorder
- carry a `group` naming the bucket (`phone`, `tablet`, `large-tablet`, `tv`,
  `wear`) — wide/expanded layouts go to `large-tablet`, car views fold into
  `phone` (Play has no car type)
- use only literal state; they render with no ViewModel, no database and no device
- use the shared device spec and `showSystemUi = true`

`calculator/src/screenshotTest/` is the worked example. Layoutlib has no wallpaper, so Material
You falls back to the default palette in previews.

Two apps opt out with an explanatory comment and commit images by hand, because Layoutlib
cannot render them: `camera` (needs a real camera) and `games/voxels` (a Vulkan surface).

Run `bash scripts/check_preview_metadata.sh` to verify all of the above.

---

## Native Rust

Put the crate at `<module>/src/main/rust/`, add it to the workspace `members` in the root
`Cargo.toml`, then:

```kotlin
androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

rustNativeLib("measure_vio", "measure")
```

Registering the `jniLibs` source directory is your job — the helper does not do it. Builds are
`--locked --release`, arm64 only unless you pass `extraAbis`, and serialized across modules
because implicit toolchain installs aren't concurrency-safe. Paths are remapped and
`SOURCE_DATE_EPOCH` honoured so builds stay reproducible.

The workspace denies `unsafe_code`, `unwrap_used`, `panic`, `indexing_slicing` and
`missing_docs`, so write fallible code with explicit error handling.

**Packaging 32-bit is two-sided:** the app opts in with `nativeAbis { armv7 = true }` *and* the
Rust module must opt in with `extraAbis`. CI currently installs only the aarch64 Rust target.

---

## Dependencies

`settings.gradle.kts` sets `FAIL_ON_PROJECT_REPOS`, so modules must never declare their own
repositories, and every third-party repo is content-scoped. Adding a dependency from a new host
is deliberately a visible, explicit change.

Prefer an existing shared module over a new dependency:

| Module | Purpose |
| :--- | :--- |
| `:library` | Base — nav keys, shared IPC DTOs, `AssistantIntent` |
| `:library:ui` | Compose layer — Material re-export, `Icons.kt`, scaffolds, `DynamicTheme`, messages |
| `:library:network` | `NetworkClient` and the pinned CA trust bundles |
| `:library:room` | Room 3 + SQLCipher |
| `:library:map` | Vector map rendering |
| `:library:ml` | On-device inference |
| `:library:ocr` | On-device OCR |
| `:library:image` / `:library:media` | Image loading / audio-video playback |
| `:library:e2ee-p2p` | End-to-end encryption and peer-to-peer |
| `:library:widgets` / `:library:work` | Glance widgets / WorkManager helpers |
| `:library:biometric` / `:library:ink` | Biometric prompts / stylus ink |
| `:library:downloadservice` | Foreground downloads for large assets and models |
| `:library:locationprovider` | Location provider abstraction |
| `:library:aosp-stubs` | Compile-only hidden-API stubs |
| `:sdk:games` / `:sdk:cast` / `:sdk:openassistant` | Games hub, casting, assistant tool calling |

See [SUPPLY_CHAIN_RISKS.md](SUPPLY_CHAIN_RISKS.md) before adding anything third-party.

---

## Tests

Unit tests go in `<module>/src/test/java/...`, mirroring the main package, using `kotlin.test`
and JUnit 4. `androidTest` is legacy here — the on-device screenshot generator is gone, and
`src/androidTest/**/MetadataScreenshots.kt` is treated as an error.

The `screenshotTest` source set exists to generate store images, not to catch regressions; no
baseline is committed and nothing diffs against one.

CI does not run tests. Run them locally.

---

## Git

**The first line of a commit message** is at most ~80 characters:

```
appname: feature description in a few words (#123)
```

Drop the issue number if there isn't one. Use the module or area as the prefix.

After the first line, leave a blank line and write a full body, wrapped at ~72 characters: what
changed and why, the context (the bug or symptom, root cause, constraints, alternatives you
rejected), notable implementation details, the user-visible effect, and how you verified it.
Release notes are generated from commit messages, so a complete body makes for better notes.

`.gitattributes` normalises the whole tree to LF, so you should never see a line-ending diff. If
`git diff --stat` reports far more changed lines than you actually touched, your editor rewrote
the file's line endings — fix that rather than committing the churn.
