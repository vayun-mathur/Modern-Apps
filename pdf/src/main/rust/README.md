# pdf_render — memory-safe PDF renderer (Rust + JNI)

Parses PDFs entirely in Rust with [`lopdf`] (no pdfium / no system PDF stack)
and reduces each page to plain drawing primitives — text runs, filled polygons
and stroked polylines — for the "Open PDF (safe)" viewer. Built into
`libpdf_render.so` and called from Kotlin via `PdfNative`.

The point of "safe": all untrusted binary parsing happens in memory-safe Rust,
and the JNI boundary only ever passes a flat little-endian buffer of geometry +
UTF-8 text, so Kotlin never touches the raw PDF bytes.

## Scope (v1)

- Text with `/ToUnicode` CMap decoding (1-byte simple fonts and 2-byte
  Identity-H / Type0), Latin-1 fallback.
- Vector paths: lines, rectangles, filled/stroked paths, flattened beziers,
  positioned via the content-stream graphics state (CTM + text matrix).
- Deferred: embedded raster images (XObjects), encryption/passwords, form
  fields, annotations, shadings/patterns.

Encrypted PDFs return a 0 handle so the viewer shows a clean error instead of
attempting decryption.

## Prerequisites (local + CI)

The `:pdf` module cross-compiles this crate for Android using the NDK's clang,
driven directly from a Gradle task (`cargoNdkBuild` in `pdf/build.gradle.kts`).
You need `rustup` with the Android target:

```sh
rustup target add aarch64-linux-android
```

`aarch64-linux-android` (`arm64-v8a`) is the only ABI the app ships (dev and
release both filter to `arm64-v8a`). The Android NDK is required (pinned via
`ndkVersion` in the build convention).

`./gradlew :pdf:assembleDebug` runs `cargoNdkBuild` automatically (wired into
`preBuild`) and packages the `.so` from `build/rustJniLibs/<abi>/`.

## Host tests

The interpreter, CMap parser and wire format are validated on the host (no
Android / NDK needed):

```sh
cd pdf/src/main/rust
cargo test
```

[`lopdf`]: https://crates.io/crates/lopdf
