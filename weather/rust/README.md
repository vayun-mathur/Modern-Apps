# weather_om — native `.om` decoder (Rust + JNI)

Decodes Open-Meteo binary `.om` spatial files directly from the keyless
`map-tiles.open-meteo.com` bucket (HTTP range reads → only the index + covering
chunks are transferred), for the Weather app's map overlay. Built into
`libweather_om.so` and called from Kotlin via `OmTilesNative`.

## Prerequisites (local + CI)

The `:weather` module cross-compiles this crate for Android using the NDK's
clang, driven directly from a Gradle task (`cargoNdkBuild` in
`weather/build.gradle.kts`). You need `rustup` with the Android target:

```sh
rustup target add aarch64-linux-android
```

- `aarch64-linux-android` → device / emulator (`arm64-v8a`), the only ABI the
  app ships (dev and release both filter to `arm64-v8a`). To also target
  Intel-host emulators, add `x86_64-linux-android` and re-enable it in
  `rustAbis` (see the comment in `weather/build.gradle.kts`).

The Android NDK is required (pinned via `ndkVersion` in the build convention).
Building the FFI dependency (`om-file-format-sys`) uses the NDK clang + sysroot,
wired via `CC`/`AR`/`SYSROOT`/`BINDGEN_EXTRA_CLANG_ARGS` in the Gradle task.

`./gradlew :weather:assembleDebug` (or `installDev`) runs `cargoNdkBuild`
automatically (wired into `preBuild`) and packages the `.so` from
`build/rustJniLibs/<abi>/`.

## Host tests

The decode path can be validated on the host (no Android) against the live
bucket:

```sh
cd weather/rust
cargo test -- --ignored --nocapture
```

This decodes a region of the current DWD ICON run and sanity-checks the sampled
values (and the derived `wind_speed_10m` from u/v components).
