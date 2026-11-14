/// Where this renderer reads its tiles from: the planet-scale single-file full `.mamaps`
/// archive. planet5 (v10 header: dictionary-zstd v11 bodies, the dictionaries between the root
/// and the leaves; graph v8 / TRIX v8 sections behind the `MAMA8` footer). The reader still
/// opens planet4 (v9) archives, which stay published for older app versions.
///
/// A new URL is a new cache origin: [`basemap_origin`] carries the URL, so the first open
/// against this URL wipes every range cached for the previous archive (planet4) instead of
/// keeping both — the cache directory is shared, not keyed per URL.
pub const BASEMAP_ARCHIVE_URL: &str = "https://data.maproj.org/planet5.mamaps";

/// Bumped when the on-disk cache layout changes, so old entries are dropped rather than
/// misread. The URL is in the marker too, because the archive is republished under the
/// same name and a cached directory chunk addresses the byte offsets of the build it came
/// from.
///
/// v2: v8 merges the four wash layers into `landtype` and renumbers every other layer, so
/// any cached v7 body would decode its features into the wrong layers. The `build_id`
/// would catch this anyway on a republish, but a sideloaded `basemap.mamaps` copied over
/// an old cache without a fresh header fetch would not — the layout bump makes the
/// refusal unconditional.
pub const CACHE_FORMAT: &str = "v2";

/// The cache's origin marker: the layout version, the URL, and the archive's own `build_id`.
///
/// `build_id` is what makes republishing under a stable name safe. The URL alone is not enough — it
/// is deliberately the *same* URL every build, so a cached leaf index would otherwise keep
/// addressing byte offsets from the build it came from, and a user would sit on a stale map forever
/// with no way to notice.
///
/// The marker is computed **before** the cache opens from a header prefix fetch, and the cache is
/// opened once with the full marker: one-shot open, no two-step reset.
pub fn basemap_origin(url: &str, build_id: u64) -> String {
    format!("{CACHE_FORMAT}|{url}|{build_id:#018x}")
}
