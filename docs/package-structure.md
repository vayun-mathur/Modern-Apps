# Package and code-structure mandate

This document is normative. “Must” and “must not” are merge requirements,
not preferences. The root `AGENTS.md` §2 is the short form; this file is the
complete rulebook. A change that needs a new package root, package segment,
or structural exception must update this document, `AGENTS.md`, and the
relevant lint detector in the same change. A suppression, lint baseline, or
detekt/lint config relaxation is not an exception mechanism.

## 1. Applicability and profiles

### 1.1 App profile

The app profile applies to every shipped Android app whose base package is
`com.vayunmathur.<app>`, including the nested `com.vayunmathur.cast.tv`
app. The Gradle module layout must mirror the package ownership below.

### 1.2 Shared-library and SDK profile

`com.vayunmathur.library.*`, `com.vayunmathur.sdk.*`, and the two wire-format
packages `com.vayunmathur.cast.protocol` and `com.vayunmathur.auto.protocol`
are shared-library packages, not app packages. Their first segment names the
library or wire module. Files inside them still follow the responsibility
rules in §4–§10: transport code in the networking library, persistence code
in the persistence library, native bridges behind guarded wrappers, and no
generic dumping-ground packages.

### 1.3 Games profile

Game packages under `com.vayunmathur.games.<game>` are not silently exempt
from architecture review. They use the same responsibility vocabulary
(`ui`, `data`, `domain`, `platform`, `network`, `intents`, `service`,
`provider`, `widget`, `notifications`, `auth`, `sync`, `telephony`) below
the game segment. A game may keep an existing game-specific layout only as
migration debt; it must not add a new top-level game root or a generic
package segment.

## 2. Package grammar

An app package has this shape:

```text
com.vayunmathur.<app>[.<root>[.<feature>[.<subfeature>]]]
```

1. Every segment is lowercase ASCII and uses a singular responsibility noun
   (for example `widget`, not `widgets`).
2. The first segment after the app base is called the **root**. Roots are a
   closed set (§3). No other root may be introduced without amending this
   document, `AGENTS.md`, and `PackageStructureDetector` together.
3. A segment below the root names a feature, capability, provider, protocol,
   or data boundary. It must not name a programming-language construct, UI
   toolkit, generic class type, or vague bucket.
4. The following segments are forbidden at every depth:
   `api`, `apis`, `base`, `common`, `core`, `feature`, `features`, `helper`,
   `helpers`, `impl`, `manager`, `managers`, `misc`, `presentation`,
   `repository`, `repositories`, `screen`, `screens`, `util`, `utils`,
   `view`, `views`, `viewmodel`, and `viewmodels`.
5. The canonical class-shape exceptions are `ui/components`, `ui/dialogs`,
   and `data/model`. They are allowed only with those exact meanings:
   reusable UI pieces, modal UI, and persisted/wire data shapes. They do not
   authorize synonyms elsewhere.
6. A package must not be created for one trivial file unless it is a root or
   one of the explicit structural owners in §§5–10.
7. The directory path and the Kotlin `package` declaration must agree.

## 3. Closed app roots and root files

### 3.1 Allowed roots

An app may use only these roots:

| Root | Sole responsibility |
|---|---|
| `ui` | Screens, screen content, dialogs, and reusable app UI components. |
| `data` | Room, DataStore, files, repositories, persistence/wire DTO mapping, and the single preferences owner. |
| `domain` | Pure models, algorithms, parsers, validators, and business rules with no Android or Compose dependency. |
| `platform` | ViewModels, Android-framework adapters, sensors, system-service integration, and guarded native bridges. |
| `network` | The app’s single public `Networking` entry point and its private endpoint/protocol implementation. |
| `intents` | Assistant/cross-app IPC contracts and exported-intent parsing/validation. |
| `service` | Android `Service` implementations and service-owned helpers. |
| `provider` | Android `ContentProvider` implementations. |
| `widget` | All home-screen widget code, flat: Glance widgets, legacy widget providers, receivers, actions, and widget state. |
| `notifications` | Notification channels, builders, actions, and presentation. |
| `auth` | Sign-in, credentials, account/session state, and authentication flows. |
| `sync` | Sync adapters, sync workers, and reconciliation. |
| `telephony` | Carrier, SIM, telephony, and messaging-transport adapters. |

### 3.2 Widget package rule

Widget code must be directly under `.<app>.widget`. The following are
forbidden:

- `.<app>.widgets`
- `.<app>.widget.glance`
- `.<app>.glance`
- any other descendant of `.<app>.widget`

`Glance` is an implementation detail of the widget surface, not a package
boundary. A widget receiver, widget class, action callback, and immutable
widget snapshot all belong directly in `widget`.

### 3.3 App-root files

When the base package `com.vayunmathur.<app>` is not empty, it may contain
only:

- `MainActivity.kt`, containing only `class MainActivity : MaActivity`;
- `Route.kt`, containing only `sealed interface Route : NavKey` and nested
  route declarations;
- `Navigation.kt`, containing only `@Composable fun Navigation(...)`;
- `*Application.kt`, containing only the matching `Application` subclass.

No other declaration may live in the app-root package.

## 4. Root ownership rules

### 4.1 `ui`

- A screen or content composable lives in `ui`; a ViewModel never lives in
  `ui`.
- There is one public `@Composable` per file, and the file is named for it.
- `ui` must not construct HTTP requests, open databases, access raw
  preferences, load native libraries, or parse exported intents.
- Feature subpackages are allowed. `ui/components` and `ui/dialogs` are the
  only class-shape subpackages.

### 4.2 `data`

- Room databases, DAOs, repositories, DataStore owners, files, and DTO
  mapping live here.
- Every app has exactly one preferences owner in `data`, named
  `<App>Prefs` (for example `CalendarPrefs`). It is the only app code that
  opens that app’s DataStore or SharedPreferences. It must expose typed
  values/flows and must recover from `CorruptionException` by returning the
  documented default state instead of crashing.
- Persisted or external data must be decoded through non-throwing helpers
  in `data` or `domain`. A Room type converter must never throw; corrupt
  input maps to null, an empty value, or a clamped valid value.

### 4.3 `domain`

- `domain` is pure Kotlin: no `android.*`, `androidx.*`, Compose, Android
  resources, Context, or file/network I/O.
- Models, algorithms, parsers, validators, recurrence/date logic, and
  safe-decoding primitives belong here when they do not need Android.
- A parser for untrusted or persisted input must return a result/null (or a
  sealed failure), not throw for malformed data.

### 4.4 `platform`

- ViewModels, Android-framework adapters, permission/system-service glue,
  sensors, workers that are not sync-owned, and native bridges live here.
- A native library has exactly one Kotlin wrapper in `platform`, named
  `<Feature>Native`. That wrapper is the only app file that may call
  `System.loadLibrary` for that library. It must expose an `available`
  result and must never let a load failure poison class initialization.
- JNI-frozen fully-qualified names are the only exception, and each one must
  carry `// PACKAGE STRUCTURE EXCEPTION (JNI)`.
- Exported-intent parsing does not belong in an Activity. It belongs in an
  `intents` contract (§4.6); `platform` may consume the validated result.

### 4.5 `network`

- Every app that talks to a network has exactly one public networking entry
  point in `network`, named `Networking` (an `object`, or a single
  injectable class with that name). Other packages call only `Networking`.
- `Networking` owns the `NetworkClient`, the narrowest applicable
  `TrustBundle`, base URLs, endpoint paths, HTTP methods, authentication
  headers, retries, cancellation, and response-status mapping.
- Endpoint-specific clients, DTOs, and protocol helpers inside `network`
  are implementation details. They must not be called from `ui`, `data`,
  `domain`, `platform`, `service`, `provider`, `widget`, or `intents`.
- No code outside `network` and `library.network` may construct a raw HTTP
  request or open a raw network socket for an API call. This includes
  `NetworkClient`, `HttpURLConnection`, `URL.openConnection`, OkHttp, and
  Retrofit.
- Protocol libraries that are inherently connection-oriented (for example
  IMAP/SMTP) live under `network` by protocol; their public surface is still
  mediated by `Networking`.

### 4.6 `intents`

- Every exported Activity, Service, Receiver, provider, or assistant action
  has exactly one contract type here, named `<Feature>IntentContract` or
  the existing app-specific contract name ending in `Intent`.
- The contract owns extra keys, type checks, numeric ranges, enum parsing,
  defaults, and malformed-input rejection. Entry points consume only the
  validated result.
- Cross-app IPC payload types live here, not in `ui` or `platform`.

### 4.7 `service`, `provider`, `notifications`, `auth`, `sync`, `telephony`

- `service` contains Android Service implementations; a Service’s parsing
  and business rules belong in `intents`, `domain`, or `data` as above.
- `provider` contains ContentProvider implementations and stays flat.
- `notifications` owns notification construction; it does not own the work
  that produces the notified event.
- `auth` owns authentication and session state; credential persistence goes
  through the single `data` preferences/database owner.
- `sync` owns sync orchestration and reconciliation; remote calls go
  through `network.Networking`.
- `telephony` owns carrier/SIM/telephony adapters; message business rules
  belong in `domain` and network transport in `network`.

## 5. Initialization and parsing safety

1. A companion object, object initializer, top-level property, or class
   initializer must not perform unguarded I/O, parsing, database access, or
   native-library loading.
2. A throwing initializer is a crash bug even if the input that triggers it
   is rare. Initializers use defaults, lazy initialization, or an explicit
   guarded `available`/failure state.
3. `Json.decodeFromString`, `Base64.decode`, `UUID.fromString`, enum
   `valueOf`, and date/time `parse` calls on persisted, IPC, file, or network
   data must go through a non-throwing helper in `data` or `domain`.
4. Room converters and widget state loaders must be total functions: bad
   persisted data produces a documented fallback, never an exception.

## 6. Regex rule

Regex patterns that can receive process death, restore, IPC, file, or user
input must be valid for Android’s ICU-backed `java.util.regex`, not merely
for the desktop JVM. In particular, a literal `{` or `}` outside a valid
quantifier or character class must be escaped. Regex constants belong in
`domain` when pure, or beside the single parser that owns them in `data`.

## 7. Widget state rule

A widget must render from one immutable snapshot loaded for that render.
It must not combine a newly computed date/index/key with a stale map or
list without a fallback. Indexing widget state uses `getOrNull`, an empty
default, or an explicit loading state.

## 8. Enforcement

1. `PackageStructure` lint is blocking. It enforces the closed roots, the
   app-root file contracts, and the flat `widget` rule.
2. Every Modern-Apps lint issue is blocking. An issue may not be made
   advisory to land a change.
3. A violation is fixed by moving/factoring the code, not by adding a
   suppression, lint baseline, detekt baseline, or config relaxation.
4. The only package exception is a JNI-frozen fully-qualified name, marked
   verbatim with `// PACKAGE STRUCTURE EXCEPTION (JNI)`.
5. Existing violations are migration debt, not precedents. Touching a file
   in a violating package requires either migrating that package or leaving
   the code in a package that complies with this document.
