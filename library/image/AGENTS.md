# AGENTS.md — library/image/ (':library:image')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `library/image/` + allowed shared modules. Do not root-scan.

<!-- last-reviewed: 2026-10-16 -->
- Gradle: :library:image / dir: library/image/
- Package roots present: (image loading pipeline — `image/` + `compose/`/`decoders/`/`fetchers/`/`util/` subpackages)
- Entry files: ImageLoader.kt + compose/AsyncImage.kt (no MainActivity/Route — library)
- Deps: (none — permission-free by design, #884)
- Network: this module loads local data only; network apps opt into HTTP(S) by
  handing `ImageLoader.Builder.remote()` a fetch function they build from
  `NetworkClient` — no library-to-library edge either way
- Metadata: metadata_data/library-image.md (absent — libraries have none by design)
- Rust: no / screenshotTest: no

## Overview
**Purpose:** Shared image pipeline — requests, memory/disk caches, fetchers (http/asset/file/content/bitmap/byte-array), bitmap/SVG/video-frame decoders, Compose `AsyncImage` + animated images, Coil compat shim.
**How to use:** `AsyncImage` in Compose with an `ImageRequest`; custom sources add a `fetchers/Fetcher`; transforms via `Transformation`; percent sizing via `util/PercentSizing`.
**Entry points:** `ImageLoader.kt` → `ImageRequest.kt` → `compose/AsyncImage.kt`.
**Gotchas:** `coilcompat/` is a migration shim, not a target — new code uses this pipeline directly. Cache keys must include transformations or stale variants render.

## Key files
- com/vayunmathur/library/image/ImageLoader.kt
- com/vayunmathur/library/image/ImageRequest.kt
- com/vayunmathur/library/image/ImageResult.kt
- com/vayunmathur/library/image/MemoryCache.kt
- com/vayunmathur/library/image/DiskCache.kt
- com/vayunmathur/library/image/Transformation.kt
- com/vayunmathur/library/image/Size.kt
- com/vayunmathur/library/image/compose/AsyncImage.kt
- com/vayunmathur/library/image/compose/AsyncImageState.kt
- com/vayunmathur/library/image/compose/AnimatedImage.kt
- com/vayunmathur/library/image/coilcompat/CoilCompat.kt — migration shim, not a target
- com/vayunmathur/library/image/decoders/BitmapDecoder.kt
- com/vayunmathur/library/image/decoders/SvgDecoder.kt
- com/vayunmathur/library/image/decoders/VideoFrameDecoder.kt
- com/vayunmathur/library/image/fetchers/Fetcher.kt
- com/vayunmathur/library/image/fetchers/AssetFetcher.kt
- com/vayunmathur/library/image/fetchers/FileFetcher.kt
- com/vayunmathur/library/image/fetchers/ContentResolverFetcher.kt
- com/vayunmathur/library/image/fetchers/BitmapFetcher.kt
- com/vayunmathur/library/image/fetchers/ByteArrayFetcher.kt
- com/vayunmathur/library/image/util/PercentSizing.kt
- com/vayunmathur/library/image/util/Sha256.kt

## Verify (this module only)
```
./gradlew :library:image:compileDevKotlin
./gradlew :library:image:lint
```
