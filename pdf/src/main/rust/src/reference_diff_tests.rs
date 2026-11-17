//! Differential rasterisation against an INDEPENDENT reference renderer.
//!
//! # Why this module exists
//!
//! Every other test in this crate is self-referential. `tests.rs`,
//! `golden_tests.rs`, `robustness_tests.rs` and `differential_tests.rs` all
//! assert that the renderer does what *this team* decided the spec means. That
//! catches regressions and crashes, and `differential_tests.rs` additionally
//! catches non-determinism and broken metamorphic relations — but none of them
//! can catch the failure mode where a clause was misread the same way by the
//! implementer and by every reviewer. In that case the code and the test agree
//! by construction, the suite is green, and the page renders confidently wrong.
//!
//! The only cure is a second implementation of the same specification, written
//! by people who have never seen this one, compared on the one artefact both
//! produce: pixels.
//!
//! # The reference: `hayro` 0.7.1
//!
//! Chosen after evaluating the pure-Rust PDF-rasteriser field. Criteria and how
//! it scored:
//!
//! * **Actually rasterises** (not merely parses). Yes — `hayro::render` returns
//!   a `vello_cpu::Pixmap` of premultiplied RGBA8.
//! * **Pure Rust, no system libraries.** Yes — CPU-only through `vello_cpu`;
//!   the crate is `#![forbid(unsafe_code)]`. No C, no `build.rs` linking, no
//!   pkg-config. (`pdfium-render` and the other pdfium bindings were rejected
//!   on exactly this point: a native binary blob in a crate that cross-compiles
//!   to `aarch64-linux-android` is a real cost even confined to dev-deps, since
//!   it has to be fetched and matched per host and per CI image.)
//! * **Builds on this Windows host.** Yes, verified: clean `cargo build` of the
//!   whole 40-crate subtree in ~35 s, no toolchain beyond stable rustc. It
//!   declares `rust-version = 1.92`; this tree is on 1.97.
//! * **Licence.** `Apache-2.0 OR MIT`. The MIT arm is compatible with this
//!   crate's `GPL-2.0-only`, and a dev-dependency is not distributed regardless.
//! * **API usable from a test.** Yes: `Pdf::new(Vec<u8>)` → `pages()[i]` →
//!   `render(...)`. No filesystem fixtures required; `embed-fonts` (on by
//!   default) removes the need to ship the standard-14 substitutes.
//! * **Independent.** Separate authorship, separate lineage (`hayro-syntax` /
//!   `hayro-interpret`, not `lopdf`), and its regression corpus is ~1000 files
//!   scraped from the `pdf.js` and PDFBox suites — i.e. calibrated against two
//!   *further* independent implementations.
//!
//! ## The one shared component — and why it does not compromise the result
//!
//! `hayro-jbig2` is already a NORMAL dependency of this crate (`Cargo.toml`),
//! and `hayro` pulls it too. JBIG2 decoding is therefore NOT independently
//! verified by this module, and JBIG2 is deliberately absent from the corpus
//! below. Everything else — the object model, the content-stream interpreter,
//! colour spaces, functions, shadings, image decoding, the rasteriser — is
//! disjoint between the two.
//!
//! ## Proof that the dev-dependency cannot reach the shipped `cdylib`
//!
//! Three independent checks, all reproducible:
//!
//! 1. `cargo tree -p pdf_render --edges normal --target aarch64-linux-android`
//!    lists `hayro-jbig2` under `pdf_render` and does NOT list `hayro`,
//!    `hayro-interpret`, `hayro-syntax` or `vello_cpu` anywhere.
//! 2. Adding the entry changed the workspace `Cargo.lock` by **437 insertions
//!    and 0 deletions**. Zero deletions is the load-bearing half: no existing
//!    package's resolved version moved, so the graph reachable from the
//!    `cdylib` is byte-identical to what it was before.
//! 3. `cargo build --lib` does not compile `hayro`. Cargo only builds
//!    dev-dependencies for test/bench/example targets; the `cdylib` is a `lib`
//!    target. With `resolver = "2"` (set at the workspace root) features
//!    enabled by dev-dependencies are also not unified into non-dev builds.
//!
//! Check 2 is the one worth repeating after any dependency change here, because
//! it is the only one that would catch the subtle failure: a dev-dependency
//! that does not itself enter the build but *bumps a shared normal dependency*.
//!
//! # Harness design
//!
//! Both renderers are handed the SAME bytes (`Document::save_to` output), so no
//! difference can come from serialisation.
//!
//! * `hayro` rasterises directly to a pixmap.
//! * This crate emits `Prim`s, not pixels, so [`rasterize`] below turns them
//!   into a bitmap. **Everything that rasteriser approximates is a potential
//!   false positive**, so it is enumerated exhaustively in the next section.
//! * The two bitmaps are compared by [`fuzzy_diff`], a neighbourhood-tolerant
//!   perceptual metric (see below). Exact equality between two independent
//!   renderers is 100% false positives and is never used.
//!
//! ## EXACTLY what this module's rasteriser approximates
//!
//! Read this before believing any failure. In rough order of how likely each is
//! to masquerade as a renderer bug:
//!
//! 1. **Antialiasing model.** 4 sub-scanlines per pixel row with exact analytic
//!    x-coverage; `hayro`/`vello_cpu` use their own (different) analytic
//!    coverage. Edge pixels will differ by tens of levels. This is the entire
//!    reason for the neighbourhood-tolerant metric.
//! 2. **Stroke geometry is approximate.** Segments become quads; round joins and
//!    round caps become 24-gon disks; **miter joins are drawn as BEVEL joins**
//!    (`/MiterLimit` is ignored); projecting-square caps extend by half-width.
//!    All quads for one stroke are unioned as a single nonzero path so joins do
//!    not double-composite. Corpus strokes are therefore kept thin, or use round
//!    joins, or are compared only where the approximation is exact.
//! 3. **Image sampling is NEAREST-NEIGHBOUR**, with 2x2 subsampling per device
//!    pixel for edge coverage only. `/Interpolate` is ignored. Any test that
//!    magnifies a small image will disagree on interior gradients unless the
//!    image is a flat-block design — the corpus images are flat blocks for
//!    exactly this reason.
//!
//! 3a. **Clip-path beziers are flattened SCALE-AWARELY** by [`clip_contours`],
//!    at roughly one segment per 3 device px. That deliberately mirrors the
//!    consumer rather than the renderer: a clip curve crosses the wire as a real
//!    `PathOp::Cubic` and Skia re-flattens it at device resolution every frame,
//!    so a clip boundary does not facet at zoom. Fills and strokes are the
//!    opposite — they arrive already flattened by `interpret.rs` at page-point
//!    resolution — so a disagreement on a filled or stroked curve is the
//!    renderer's flattener, and one on a clip boundary would be this file's.
//!    Pinned by [`refdiff_curved_clip_is_zoom_correct`].
//! 4. **Blend modes are ignored** (everything composites Normal, source-over,
//!    in non-linear sRGB u8 space). No corpus entry sets `/BM`.
//! 5. **Transparency groups**: `GroupPush`/`GroupPop` become a plain isolated
//!    layer composited with the group alpha. `isolated`/`knockout` are ignored.
//!    (`hayro` documents that it does not implement knockout/isolation either,
//!    so this is not a comparison anyone should trust; no corpus entry uses it.)
//! 6. **Soft masks** are implemented (content layer, mask layer, luminosity or
//!    alpha, `/TR` LUT). This rasteriser seeds the luminosity mask canvas with
//!    opaque black and does NOT itself read the group's `/BC` — but it does not
//!    need to: `interpret.rs` emits the §11.6.5.2 backdrop as an ordinary `Fill`
//!    covering the group `/BBox` before the mask content, so a non-default
//!    `/BC` arrives as a primitive and is honoured here for free. The black seed
//!    and the emitted default-black backdrop simply coincide. Pinned by
//!    [`refdiff_luminosity_soft_mask_backdrop_colour`]; if the backdrop ever
//!    stops being a prim and becomes a field on `SoftMaskPush`, that test starts
//!    failing and this note has to change with it.
//! 7. **`Prim::Text` with `outline == false` is NOT DRAWN.** That is the
//!    substitute-typeface fallback, where our renderer hands Kotlin a font name
//!    instead of contours; there is nothing to rasterise here. Rather than
//!    silently comparing a page with missing text, [`rasterize`] COUNTS these
//!    and [`compare_page`] hard-fails if the count is non-zero. Text is
//!    therefore tested through **Type 3 fonts**, whose glyphs are content
//!    streams both renderers must draw identically — which tests the text
//!    positioning machinery (`Tf`/`Td`/`TJ`/`Tz`/`Tc`/`Tw`/`TL`/`Tm`/
//!    `/FontMatrix`) without needing a font file or a glyph rasteriser.
//! 8. **`Prim::TextClipApply` is ignored** (no text-clip corpus entry).
//! 9. Colour is composited in 8-bit-ish sRGB with no gamma correction, matching
//!    what the Kotlin `Canvas` does but not necessarily what `vello_cpu` does
//!    internally for partial coverage.
//!
//! ## The comparison metric
//!
//! [`fuzzy_diff`] flags a pixel only when it differs from EVERY pixel in the
//! 3x3 neighbourhood of the other image by more than `CHANNEL_TOLERANCE` on
//! some channel. That makes it blind to antialiasing and to sub-pixel placement
//! differences, which are guaranteed and uninteresting, while remaining
//! sensitive to the things that matter: a wrong colour anywhere, ink present in
//! one render and absent in the other, or a shape displaced by more than a
//! pixel. A page passes when fewer than `DIFF_BUDGET` of its pixels are flagged.
//!
//! Several tests additionally use [`interior_mean`], which averages a rectangle
//! well inside a shape's boundary. That number is free of every antialiasing
//! concern above, so where a test can be phrased as "what colour is this
//! region", it is — and the tolerance can then be tightened to a couple of
//! levels, which is where the colour-space and function findings came from.
//!
//! # What it found
//!
//! 24 test functions covering 26 graded page comparisons at [`SCALE`], plus a
//! zoom-ceiling pair at 10.6 px/pt and one that deliberately checks the harness
//! REFUSES to grade (see [`refdiff_harness_refuses_a_page_it_cannot_draw`]).
//! **22 of the 26 agree at 0.0000% flagged pixels**
//! 0.0000% flagged pixels** — an exact match on shadings (axial, radial and
//! function-based), all four function types, Indexed and Separation, CalRGB,
//! image placement and `/Decode`, image masks and stencil polarity, nested and
//! even-odd clipping, `/Rotate` and `/CropBox`, form XObject `/Matrix` and
//! `/BBox`, dashes and phase, constant alpha, luminosity soft masks including a
//! non-default `/BC` backdrop, tiling patterns, and bezier flattening. Of the
//! remaining four, two are curve rims inside the noise floor (see
//! [`DIFF_BUDGET`]) and two are the findings below. That level of agreement is
//! itself the main evidence that the harness is measuring the renderers and not
//! itself.
//!
//! The three that do not agree, with verdicts:
//!
//! 1. **OUR BUG — Type 3 glyph descriptions are corrupted.** `d0`/`d1` are
//!    mis-tokenised by lopdf 0.36 and the damage lands on the glyph's first
//!    painting operator. See
//!    [`refdiff_root_cause_d0_mangles_the_next_operator`] for the isolated
//!    repro, the spec citation (§9.6.5, Table 113) and the proposed patch in
//!    `content.rs`. Both that test and
//!    [`refdiff_type3_font_text_positioning`] fail today because of it; they
//!    are the only two failures and they are the same bug.
//! 2. **Neither is wrong — `DeviceCMYK`, up to 109/255.** We convert
//!    arithmetically, `hayro` runs a CGATS TR 001 ICC profile. §8.6.4 makes the
//!    device spaces device-dependent and §8.6.5.6 provides `/DefaultCMYK` for
//!    documents that need a defined colorimetry. Recorded and bounded by
//!    [`refdiff_devicecmyk_diverges_because_the_reference_is_colour_managed`].
//! 3. **OURS IS RIGHT — `Lab`, up to 61/255.** [`spec_lab_to_srgb`], a third
//!    implementation written straight from §8.6.5.4, matches this crate at all
//!    six test patches to 0/255 and `hayro` to as much as 61/255. `hayro`'s
//!    numbers are consistent with a D50-referenced ICC Lab PCS that discards
//!    the declared `/WhitePoint`. No patch proposed.
//!
//! # Running
//!
//! Every test here runs in the normal suite (they compare against the
//! independent `hayro` renderer, so the assertions stay meaningful without
//! any stored reference images):
//!
//! ```text
//! cargo test -p pdf_render reference_diff -- --nocapture
//! ```

use crate::*;
use lopdf::content::{Content, Operation};
use lopdf::{dictionary, Object, Stream};

// ---------------------------------------------------------------------------
// Comparison thresholds
// ---------------------------------------------------------------------------

/// Per-channel 0-255 difference below which two pixels are "the same colour".
/// 30 absorbs sRGB rounding and the two renderers' differing partial-coverage
/// arithmetic; it is far below the distance between any two colours the corpus
/// deliberately puts next to each other.
const CHANNEL_TOLERANCE: i32 = 30;

/// Fraction of flagged pixels a page may have and still pass.
///
/// Calibrated against measured clean renders, not estimated. Of the 26 page
/// comparisons taken at [`SCALE`], 22 flag exactly 0.0000%. The four that do
/// not, in order:
///
/// ```text
///   bezier_stroke_vy  0.1356%   curve rim + this file's stroke expansion
///   bezier_fill       0.0425%   curve rim, antialiasing only
///   star_nonzero      0.0013%   two pixels at the spike tips
///   (all others)      0.0000%
/// ```
///
/// So the observed ceiling for a clean page is ~0.14% and 1% leaves roughly a
/// 7x margin. The two defects this harness actually caught were nowhere near
/// the line — the Type 3 `d0` corruption flagged 4.41% and the DeviceCMYK
/// colour-management divergence 49.28% — so the gap between "clean" and
/// "broken" is two to three orders of magnitude, not a judgement call.
///
/// [`refdiff_curved_clip_is_zoom_correct`] runs at 10.6 px/pt rather than
/// [`SCALE`] and is deliberately NOT folded into the figures above: flagged
/// fractions are not comparable across scales, since the rim is a different
/// share of the canvas. It grades itself against its own paired baseline.
const DIFF_BUDGET: f64 = 0.01;

/// Device pixels per PDF unit for the comparison. 2x keeps edge pixels a small
/// fraction of the total without making the pages slow.
const SCALE: f32 = 2.0;

// ---------------------------------------------------------------------------
// A premultiplied-RGBA float canvas
// ---------------------------------------------------------------------------

#[derive(Clone)]
struct Canvas {
    w: usize,
    h: usize,
    /// Premultiplied RGBA, row-major, row 0 = top.
    px: Vec<[f32; 4]>,
}

impl Canvas {
    fn new(w: usize, h: usize, fill: [f32; 4]) -> Self {
        Canvas { w, h, px: vec![fill; w * h] }
    }

    fn opaque_white(w: usize, h: usize) -> Self {
        Canvas::new(w, h, [1.0, 1.0, 1.0, 1.0])
    }

    fn transparent(w: usize, h: usize) -> Self {
        Canvas::new(w, h, [0.0, 0.0, 0.0, 0.0])
    }

    /// Source-over a straight-alpha colour through a coverage/clip product.
    fn blend_px(&mut self, i: usize, rgb: [f32; 3], a: f32) {
        if a <= 0.0 {
            return;
        }
        let a = a.min(1.0);
        let d = &mut self.px[i];
        d[0] = rgb[0] * a + d[0] * (1.0 - a);
        d[1] = rgb[1] * a + d[1] * (1.0 - a);
        d[2] = rgb[2] * a + d[2] * (1.0 - a);
        d[3] = a + d[3] * (1.0 - a);
    }

    /// Composite another (premultiplied) layer over this one, scaled by a
    /// constant alpha and an optional per-pixel mask.
    fn composite_layer(&mut self, src: &Canvas, alpha: f32, mask: Option<&[f32]>) {
        for i in 0..self.px.len() {
            let m = mask.map_or(1.0, |m| m[i]) * alpha;
            if m <= 0.0 {
                continue;
            }
            let s = src.px[i];
            let sa = s[3] * m;
            if sa <= 0.0 {
                continue;
            }
            let d = &mut self.px[i];
            d[0] = s[0] * m + d[0] * (1.0 - sa);
            d[1] = s[1] * m + d[1] * (1.0 - sa);
            d[2] = s[2] * m + d[2] * (1.0 - sa);
            d[3] = sa + d[3] * (1.0 - sa);
        }
    }

    /// Flatten to straight 8-bit RGB over an opaque white page.
    fn to_rgb8(&self) -> Vec<[u8; 3]> {
        self.px
            .iter()
            .map(|p| {
                let a = p[3];
                let f = |c: f32| {
                    let v = c + (1.0 - a); // composite over white
                    (v.clamp(0.0, 1.0) * 255.0 + 0.5) as u8
                };
                [f(p[0]), f(p[1]), f(p[2])]
            })
            .collect()
    }
}

// ---------------------------------------------------------------------------
// Scanline polygon coverage
// ---------------------------------------------------------------------------

/// Sub-scanlines per pixel row. 4 is enough that the metric's neighbourhood
/// tolerance absorbs the rest.
const SUBSAMPLES: usize = 4;

/// Analytic-in-x, supersampled-in-y coverage of a set of closed contours given
/// in DEVICE pixel coordinates (y down). Returns `w * h` values in `0..=1`.
fn poly_coverage(w: usize, h: usize, contours: &[Vec<(f64, f64)>], even_odd: bool) -> Vec<f32> {
    let mut cov = vec![0f32; w * h];
    let mut edges: Vec<(f64, f64, f64, f64)> = Vec::new();
    for c in contours {
        if c.len() < 3 {
            continue;
        }
        for i in 0..c.len() {
            let a = c[i];
            let b = c[(i + 1) % c.len()];
            if (a.1 - b.1).abs() > 1e-12 {
                edges.push((a.0, a.1, b.0, b.1));
            }
        }
    }
    if edges.is_empty() {
        return cov;
    }
    let wgt = 1.0 / SUBSAMPLES as f32;
    let mut xs: Vec<(f64, i32)> = Vec::new();
    for py in 0..h {
        let row = &mut cov[py * w..(py + 1) * w];
        for s in 0..SUBSAMPLES {
            let yc = py as f64 + (s as f64 + 0.5) / SUBSAMPLES as f64;
            xs.clear();
            for &(ax, ay, bx, by) in &edges {
                let (lo, hi) = if ay < by { (ay, by) } else { (by, ay) };
                if yc < lo || yc >= hi {
                    continue;
                }
                let t = (yc - ay) / (by - ay);
                xs.push((ax + t * (bx - ax), if by > ay { 1 } else { -1 }));
            }
            if xs.len() < 2 {
                continue;
            }
            xs.sort_by(|a, b| a.0.partial_cmp(&b.0).unwrap_or(std::cmp::Ordering::Equal));
            let mut wind = 0i32;
            for i in 0..xs.len() - 1 {
                wind += xs[i].1;
                let inside = if even_odd { (i as i32 + 1) % 2 != 0 } else { wind != 0 };
                if inside {
                    add_span(row, w, xs[i].0, xs[i + 1].0, wgt);
                }
            }
        }
        for v in row.iter_mut() {
            *v = v.min(1.0);
        }
    }
    cov
}

fn add_span(row: &mut [f32], w: usize, xa: f64, xb: f64, wgt: f32) {
    let xa = xa.max(0.0);
    let xb = xb.min(w as f64);
    if xb.partial_cmp(&xa) != Some(std::cmp::Ordering::Greater) {
        return;
    }
    let ia = xa.floor() as usize;
    let ib = (xb.ceil() as usize).min(w);
    if ia >= w {
        return;
    }
    for (i, row_i) in row.iter_mut().enumerate().take(ib).skip(ia) {
        let l = (i as f64).max(xa);
        let r = ((i + 1) as f64).min(xb);
        if r > l {
            *row_i += wgt * (r - l) as f32;
        }
    }
}

// ---------------------------------------------------------------------------
// Stroke expansion (approximate — see the module header, item 2)
// ---------------------------------------------------------------------------

fn disk(cx: f64, cy: f64, r: f64) -> Vec<(f64, f64)> {
    (0..24)
        .map(|i| {
            let t = i as f64 / 24.0 * std::f64::consts::TAU;
            (cx + r * t.cos(), cy + r * t.sin())
        })
        .collect()
}

fn orient_ccw(mut q: Vec<(f64, f64)>) -> Vec<(f64, f64)> {
    let area: f64 = (0..q.len())
        .map(|i| {
            let a = q[i];
            let b = q[(i + 1) % q.len()];
            a.0 * b.1 - b.0 * a.1
        })
        .sum();
    if area < 0.0 {
        q.reverse();
    }
    q
}

include!("reference_diff_tests_part1.rs");
include!("reference_diff_tests_part2.rs");
include!("reference_diff_tests_part3.rs");
include!("reference_diff_tests_part4.rs");
include!("reference_diff_tests_part5.rs");
include!("reference_diff_tests_part6.rs");
include!("reference_diff_tests_part7.rs");