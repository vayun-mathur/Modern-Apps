# Wear e2e (androidTest) — why there is no FullE2eTest yet

Investigated 2026-11-25 for clock-wear and notes-wear. A wear
`FullE2eTest` was **not** added, because it could not compile or run
honestly today:

1. **No wear AVD.** `./test` hardcodes `EMULATOR_AVD=Medium_Phone`
   (phone form factor, `hw.device.name=medium_phone`) and
   `ensure_emulator_running` boots only that AVD. The SDK has one
   system image (`android-36/google_apis/x86_64`) and no wear image,
   and no `Wear_*`/round AVD exists in `~/.android/avd/`.
2. **Wear APKs cannot install on the phone AVD as a watch app.**
   Wear modules declare
   `<uses-feature android:name="android.hardware.type.watch" android:required="true"/>`
   (see clock-wear's manifest); the phone AVD does not report the
   watch feature, and Wear Compose UI assumes a round/square watch
   viewport, so a phone run would not be a wear run.
3. **No androidTest wiring in the wear convention.**
   `build-logic/.../common-conventions-wear.gradle.kts` declares no
   `testInstrumentationRunner` and no `androidTestImplementation`
   (uiautomator/test runner) dependencies, unlike
   `common-conventions-app` (lines ~547-563). An androidTest source set
   in a wear module would not compile as-is.
4. **The runner misclassifies wear modules.** `./test`'s
   `is_app_module` only greps for `common-conventions-app`, so wear
   modules fall into the library branch (`assembleDebug` /
   library test-package naming) — wrong APK/test-APK handling for a
   wear app. Wear modules also reuse the phone app's
   `applicationId` (e.g. clock-wear = `com.vayunmathur.clock`) for the
   WearLink signing handshake, so installing a wear APK onto the phone
   AVD would collide with/replace the phone app.

## What would be needed

- A wear system image + a round Wear AVD (e.g. `Wear_Small_Round`)
  in the SDK/`~/.android/avd/`, and `./test` support for selecting it
  per-module (wear modules → wear AVD, never Medium_Phone).
- `testInstrumentationRunner` + the same androidTest dependencies as
  `common-conventions-app` in `common-conventions-wear`.
- `./test` treating `common-conventions-wear` as an app module with
  the wear AVD (and not session-installing over the phone package on
  a phone device).

With those three, the representative journeys are ready to write in
the repo's UiAutomator-polling pattern: clock-wear launch → home
(alarms/timers/world-clock/stopwatch entries, empty-synced state
"No world clocks synced yet…") and notes-wear launch →
"No notes synced yet…" empty list → New note editor. Until then the
wear modules are covered by their JVM unit tests
(`:clock:wear:testDebugUnitTest`, notes-wear `src/test`) and Compose
screenshot tests, not by a faked phone-AVD run.
