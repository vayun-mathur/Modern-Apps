# AGENTS.md — library/media/ (':library:media')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `library/media/` + allowed shared modules. Do not root-scan.

- Gradle: :library:media / dir: library/media/
- Package roots present: (audio codecs — one package, pure Kotlin/Java)
- Entry files: OpusTranscoder.kt (no MainActivity/Route — library)
- Deps: (none)
- Metadata: metadata_data/library-media.md (absent — libraries have none by design)
- Rust: no / screenshotTest: no

## Overview
**Purpose:** Pure-JVM audio plumbing — Opus encode/remux/tag (Ogg), PCM buffers, polyphase resampling, Vorbis comments. Used by music/cast/communicate recording and playback paths.
**How to use:** Transcode via `OpusTranscoder`, remux via `OpusRemuxer`, tag via `OggOpusTagger` + `VorbisComments`; resample with `PolyphaseResampler`.
**Entry points:** `OpusTranscoder.kt` → `OpusRemuxer.kt` → `OggOpusTagger.kt`.
**Gotchas:** Keep it dependency-free (no Android media APIs) so it stays unit-testable on JVM.

## Key files
- com/vayunmathur/library/media/OpusTranscoder.kt
- com/vayunmathur/library/media/OpusRemuxer.kt
- com/vayunmathur/library/media/OggOpusTagger.kt
- com/vayunmathur/library/media/OggStreamWriter.kt
- com/vayunmathur/library/media/OpusHead.kt
- com/vayunmathur/library/media/PcmBuffers.kt
- com/vayunmathur/library/media/PolyphaseResampler.kt
- com/vayunmathur/library/media/VorbisComments.kt
- com/vayunmathur/library/media/ByteArrayMediaDataSource.kt

## Verify (this module only)
```
./gradlew :library:media:compileDevKotlin
./gradlew :library:media:lint
```
