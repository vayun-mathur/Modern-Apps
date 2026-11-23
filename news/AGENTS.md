# AGENTS.md — news/ (':news')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `news/` + allowed shared modules. Do not root-scan.

_Install: ./install news_
- Gradle: :news / dir: news/
- Package roots present: ui, data, domain, platform, network
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :library:network, :library:room, :library:image
- Metadata: metadata_data/news.md (present)
- Rust: no / screenshotTest: no

## Overview
**Purpose:** Sitemap-driven news reader. Everything is on-device: feeds and
articles are fetched directly from publisher sites, parsed and extracted
locally. There is no backend and no account.
**Key features:**
- Built-in source catalog (`domain/SourceCatalog`) — Google News sitemaps
  where a verified one exists, RSS only where no sitemap verifies (NPR, DW,
  Ars, The Verge, Hacker News)
- Room-cached feed + extracted article bodies (offline reading), bookmarks
- Readability-style extraction in `domain/ArticleExtractor` over a small
  tolerant HTML DOM (`domain/HtmlDom`); reader with text size, serif/sans
  (extracted-text only — no original-page WebView or open-in-browser)

## Source verification (2026-11-23, via the dev box's proxied network)
Verified = sitemap/RSS fetched 200 and parsed + one article's raw HTML
contained the full body (paragraph text measured, tail checked):
- BBC `bbc.com/sitemaps/https-sitemap-com-news-1.xml` — full (no paywall)
- The Guardian `theguardian.com/sitemaps/news.xml` — sitemap verified;
  article pages returned 403 to the datacentre verification IP (no
  paywall exists), badged in the catalog
- Al Jazeera `aljazeera.com/news-sitemap.xml` — full
- TechCrunch `techcrunch.com/news-sitemap.xml` — full
- ProPublica `propublica.org/news-sitemap.xml` (index → daily urlsets) — full
- El País `elpais.com/sitemaps/news.xml` — full text present in raw HTML
  despite meter markers; badged as metered
- NPR (RSS), DW (RSS), Ars Technica (RSS), The Verge (RSS),
  Hacker News (RSS) — full article HTML verified on linked articles
Excluded after verification (body absent from raw HTML or fetch blocked):
Reuters (401), AP (403), NYT (bot wall + server-side paywall), WSJ/WaPo
(403), Axios (JS-only shell, no body in HTML), France 24 (403),
Japan Times (sitemap URL serves HTML, not XML).

## Architecture / data-flow
`ui/` feed/reader/sources/saved → `platform/NewsViewModel` →
`data/NewsRepository` (Room via `library/room`) + `data/NewsPrefs`
(DataStore owner) → `network/Networking` (the single networking entry
point, `NetworkClient` with `TrustBundle.SYSTEM` — publisher hosts are
arbitrary, so no reduced bundle can cover them) → `domain/` parsers and
extractor (pure, no Android imports).

## Entry points + reading order
`Route` (`Feed` / `Reader(url)` / `Sources` / `Saved`) → `MainActivity.kt`
→ `Navigation.kt` → `platform/NewsViewModel.kt` → `data/NewsRepository.kt`
→ `domain/SitemapParser.kt` + `domain/ArticleExtractor.kt`.

## Gotchas
- Selection is navigation: the article URL lives in `Route.Reader`, never
  in ViewModel state that swaps list→detail.
- A failing source never fails a refresh; the cached feed stays visible.
- Extraction quality is judged by `ExtractedArticle.bodyChars`; truncated
  extractions usually mean the source changed its markup or paywall.

## Verify (this module only)
```
./gradlew :news:compileDevKotlin
./gradlew :news:testDevUnitTest
./gradlew :news:lint
```
