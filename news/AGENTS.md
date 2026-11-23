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
**Purpose:** News reader. Primary feed is our server's `GET /news.json`
(`https://api.maproj.org/news.json`, see `domain/ServerFeedParser` for the
contract + assumptions), merged into Room. Every source is always in the
corpus (no per-source toggle); category + search are the filters, and the persisted
Top stories / Latest sort (`domain/FeedRanking`) orders the feed
client-side. If the server fetch fails/times out/parses empty, refresh
falls back to the on-device per-source sitemap/RSS crawl of all sources
(cached feed stays visible either way). Article reading is always on-device:
publisher HTML is fetched directly and extracted locally. No account.
On-device search (feed screen) runs over the local corpus only — titles,
source names, sitemap keywords, downloaded bodies (`domain/NewsSearch`).
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
`ui/` feed/reader/saved → `platform/NewsViewModel` →
`data/NewsRepository` (Room via `library/room`) + `data/NewsPrefs`
(DataStore owner) → `network/Networking` (the single networking entry
point, `NetworkClient` with `TrustBundle.SYSTEM` — publisher hosts are
arbitrary, so no reduced bundle can cover them; SYSTEM also covers
api.maproj.org, whereas FIRST_PARTY would break publisher fetches) →
`domain/` parsers and extractor (pure, no Android imports).

## Entry points + reading order
`Route` (`Feed` / `Reader(url)` / `Saved`) → `MainActivity.kt`
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
