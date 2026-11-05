# AGENTS.md — apple/FindFamily (FindFamily for iOS)

SwiftUI iPhone app, bundle `cc.findfamily.app.1`, team `M42H545N93`, iOS 18+. Imported with history from
`vayun-mathur/Find-Family-iOS`. Android counterpart: `findfamily/` (same relay protocol, same PQC formats).

## Layout
- `Package.swift`: SwiftPM library target `FindFamily` rooted at `FindFamilyiOS/` (xtool requires a library product).
- `xtool.yml`: bundle id, `infoPath`, and `assetCatalog.compiledDir` (only in the `vayun-mathur/xtool` fork).
- `FindFamilyiOS/Info.plist`: no `$(...)` build settings and no version keys; xtool fills in the bundle keys.
- `Resources/`: `Assets.car` + loose icon PNG + `assets-partial-Info.plist`, built from `FindFamilyiOS/Assets.xcassets`.
- `FindFamilyiOS/Crypto/PQCStubs.c` is excluded from the build (unreferenced; documents the future Rust PQC link).

## Rules
- Versions come only from root `version.txt`. Never hand-edit `CFBundleVersion` / `CFBundleShortVersionString`.
  `scripts/stamp-version.sh <plist>` writes code=line 1 and short=line 2 (`v2.6.11b` -> `2.6.11`) on the release builder.
- After any change to `Assets.xcassets`, run `scripts/assets.sh compile` on a Mac and commit `Resources/`.
  The release builder runs `scripts/assets.sh check` and fails if the catalog is stale.
- No `.xcodeproj` is committed. For Xcode, open `Package.swift` directly or run `xtool dev generate-xcode-project`
  (the generated project is gitignored).
- Never commit signing material or App Store Connect keys.

## Verify
```
xcodebuild -scheme FindFamily -destination 'generic/platform=iOS' build CODE_SIGNING_ALLOWED=NO   # Mac
xtool dev build                                                                                    # Linux / Mac with the fork
```

## Release
The maproj release pipeline (`../location_share_server/scripts/release-build-ios.sh`) runs after the Android release:
stamp version -> assets check -> `xtool dev build --configuration release --distribution app-store --ipa`
-> `xtool dist upload` -> `xtool dist submit` (creates the App Store version, attaches the build, submits for review).
