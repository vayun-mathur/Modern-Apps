# AGENTS.md — youpipe/extractor/ (':youpipe:extractor')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `youpipe/extractor/` + allowed shared modules. Do not root-scan.

_Install: ./install youpipe (dev by default)._
- Gradle: :youpipe:extractor / dir: youpipe/extractor/
- Package roots present: org.schabi.newpipe.extractor (upstream NewPipe API, not the closed set — third-party fork)
- Entry files: NewPipe.kt (`init` + service registry)
- Deps: protobuf-javalite, brotli-dec, jsr305 (no Modern-Apps modules)
- Metadata: absent (libraries none by design)
- Rust: no / screenshotTest: no

## Overview
**Purpose:** Pure-JVM YouTube/service extraction library — the NewPipe extractor fork that `:youpipe` uses to resolve streams, channels, playlists, search, and comments without the official API.
**Key features:**
- Per-service extractors (YouTube plus other services under `services/`) for streams, channels, playlists, search, suggestions, kiosks, and comments
- Localisation/time-ago parsing and pagination (`Page`, `ListExtractor`, `InfoItemsCollector`) for feed-style crawling
- Downloader abstraction — the host app injects its HTTP stack via `NewPipe.init(downloader)`
**Architecture / data-flow:** Host calls `NewPipe.init(downloader, localization)` once, resolves a `StreamingService` from `ServiceList`, then pulls typed `Info`/`ListInfo` objects through `Extractor` subclasses per entity; YouTube session PO-tokens plug in via `YoutubeSessionPoTokenProvider`. No Android, no Room, no network of its own — protobuf schemas under `src/main/proto` feed the YouTube player-response parsing.
**Entry points + reading order:** `NewPipe.kt` (`init`, `getService`) → `StreamingService.kt` → `Extractor.kt` / `ListExtractor.kt` → `services/youtube/` extractors → `downloader/Downloader.kt`.
**Gotchas:**
- This is a vendored upstream fork — prefer minimal surgical patches; large refactors will collide with upstream merges
- Protobuf `lite` java builtin must stay `named("java")`, not `create` — the JVM path pre-registers it and a second create fails the build

## Key files
### Root — registry + base types
- org/schabi/newpipe/extractor/NewPipe.kt — init + service registry
- org/schabi/newpipe/extractor/ServiceList.kt
- org/schabi/newpipe/extractor/StreamingService.kt
- org/schabi/newpipe/extractor/Extractor.kt
- org/schabi/newpipe/extractor/ListExtractor.kt
- org/schabi/newpipe/extractor/Info.kt
- org/schabi/newpipe/extractor/ListInfo.kt
- org/schabi/newpipe/extractor/InfoItem.kt
- org/schabi/newpipe/extractor/InfoItemExtractor.kt
- org/schabi/newpipe/extractor/InfoItemsCollector.kt
- org/schabi/newpipe/extractor/MultiInfoItemsCollector.kt
- org/schabi/newpipe/extractor/Collector.kt
- org/schabi/newpipe/extractor/Page.kt
- org/schabi/newpipe/extractor/MediaFormat.kt
- org/schabi/newpipe/extractor/Image.kt
- org/schabi/newpipe/extractor/MetaInfo.kt
### services/ + stream/ + channel/ + playlist/ + search/ — per-entity extractors
- org/schabi/newpipe/extractor/services/ — per-service implementations (incl. youtube)
- org/schabi/newpipe/extractor/stream/ — stream info + items
- org/schabi/newpipe/extractor/channel/ — channel info + items
- org/schabi/newpipe/extractor/playlist/ — playlist info + items
- org/schabi/newpipe/extractor/search/ — searcher + results
- org/schabi/newpipe/extractor/suggestion/ — query suggestions
- org/schabi/newpipe/extractor/kiosk/ — trending-style feeds
- org/schabi/newpipe/extractor/comments/ — comment threads
- org/schabi/newpipe/extractor/subscription/ — subscription extractors
### linkhandler/ + localization/ + timeago/ + utils/ + downloader/ + exceptions/ — plumbing
- org/schabi/newpipe/extractor/linkhandler/ — URL ↔ id routing
- org/schabi/newpipe/extractor/localization/ — Localization + ContentCountry
- org/schabi/newpipe/extractor/timeago/ — locale-aware relative-time parsing
- org/schabi/newpipe/extractor/utils/ — shared parsing helpers
- org/schabi/newpipe/extractor/downloader/ — Downloader abstraction the host injects
- org/schabi/newpipe/extractor/exceptions/ — extraction error hierarchy

## Verify (this module only)
```
./gradlew :youpipe:extractor:compileDevKotlin
./gradlew :youpipe:extractor:lint
```
