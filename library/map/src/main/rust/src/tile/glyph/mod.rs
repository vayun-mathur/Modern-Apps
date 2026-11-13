//! The SDF glyph atlas: Noto Sans rasterised once, sampled every frame.
//!
//! MapLibre renders text from a signed-distance-field atlas: each glyph is a small
//! bitmap whose texels store _distance to the glyph edge_, so the fragment shader
//! gets crisp edges at any size with `smoothstep`, plus a halo for free. This is
//! that atlas, built at first use from the bundled TTFs (task 54 staged
//! `library/map/src/main/rust/assets/fonts/NotoSans-Regular.ttf` and `-Medium.ttf`).
//!
//! # Why runtime, not build-time
//!
//! The TTFs are compiled in with `include_bytes!` rather than read through the
//! `AssetManager`, because an APK asset is not a file this renderer can open
//! without a JNI fd handoff. They therefore live under the **crate's** own
//! `assets/` (`library/map/src/main/rust/assets/fonts/`) and not under
//! `src/main/assets/`: anything in the latter is packaged into every consumer's
//! APK, which shipped these 1.5 MB a second time for bytes no code path reads.
//!
//! Rasterising at first use is paid once per process, and every caller (renderer creation,
//! the first tile a worker shapes) blocks on it: ~319 Latin codepoints × 2 weights, each a
//! 4x `ab_glyph` rasterise followed by an exact distance transform over a 64px cell. The
//! transform's search is mask- and summed-area-table-accelerated and glyphs are rendered on
//! several threads (`atlas.rs`, `sdf.rs`): 1350 ms → 17 ms on an 8-core host, bit-identical. The atlas is a single R8 image
//! (32×32 cells of 64px = 2048px) uploaded to Vulkan once.
//!
//! # Metrics live here, shaping in `tess::text`
//!
//! `ab_glyph`'s unscaled metrics are in font units (1/upem em); the atlas stores
//! them per (weight, char) so shaping needs no font handle. Kerning is
//! `kern_unscaled` between consecutive ids. UV rects address the R8 image.

pub mod atlas;
pub mod metrics;
pub mod placement;
pub mod sdf;

#[cfg(test)]
mod tests;

pub use atlas::{atlas, GlyphAtlas};
pub use metrics::{
    charset, fonts_staged, GlyphMetrics, UvRect, Weight, ATLAS_COLS, ATLAS_PX, CELL_PX,
    SDF_SPREAD_PX, UP_EM,
};
pub use sdf::expand_sdf_r8_to_rgba8;
