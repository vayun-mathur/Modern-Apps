//! DBNet post-processing: a probability map in, oriented text boxes out.
//!
//! # The pipeline
//!
//! 1. Threshold the map at [`THRESHOLD`] into a binary mask.
//! 2. Find its 8-connected components, scoring each by the **mean probability over its
//!    pixels** — the "box score" — and dropping any below [`BOX_THRESHOLD`].
//! 3. Fit a minimum-area rectangle to each surviving component.
//! 4. Put it in a canonical form, decide whether it reads horizontally or vertically,
//!    enlarge it (DBNet is trained to predict a *shrunken* box), and undo the letterbox.
//!
//! # Ported from `ppocrv5.cpp`, which is not PaddleOCR's own post-processing
//!
//! Worth being explicit about, because the two differ and the export ships config for the
//! other one. PaddleOCR's `DBPostProcess` traces contours and unclips polygons with
//! `unclip_ratio: 1.5`; `ppocrv5.cpp:259-366` instead takes 8-connected components, hulls
//! their boundary pixels, and enlarges by [`ENLARGE_RATIO`] = 1.95. This port follows
//! `ppocrv5.cpp`, so `:library:ocr` keeps the behaviour it has today rather than gaining
//! upstream's — a change of boxes would change what text the recogniser is handed.
//!
//! # Only boundary pixels are hulled
//!
//! A component's minimum-area rectangle is determined by its convex hull, and the hull of
//! a filled region is the hull of its boundary. So the flood fill collects only pixels
//! with an unset 4-neighbour, which is `O(perimeter)` points instead of `O(area)` — on a
//! full-page scan the difference is a few thousand points against a few hundred thousand.
//!
//! # The angle convention is load-bearing
//!
//! [`Rotated::angle`] is normalised to `[-90, 0)`, matching `native_cv.h`'s
//! `normalizeRotatedRectAngle`. The triples `(a, w, h)` and `(a + 90, h, w)` describe the
//! same rectangle and exactly one lands in that range. The canonical form and the
//! vertical-script test below both branch on it, so a different range silently produces
//! wrongly rotated crops for the recogniser.

use crate::preprocess::Letterbox;
use super::dbnet_extra::convex_hull;

/// Probability at or above which a pixel is text.
pub const THRESHOLD: f32 = 0.3;
/// Mean probability a component must reach to survive.
pub const BOX_THRESHOLD: f32 = 0.6;
/// How much to grow the box DBNet was trained to shrink.
pub const ENLARGE_RATIO: f32 = 1.95;
/// Rectangles whose long axis is shorter than this are noise.
pub const MIN_SIZE: f32 = 3.0;
/// Components examined before giving up, as `max_candidates` in the export's config.
pub const MAX_CANDIDATES: usize = 1000;
/// A run this many times its own height is elongated enough to be read as vertical.
const ELONGATED: f32 = 2.7;

/// A rotated rectangle, in the convention described in the module docs.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Rotated {
    /// Centre x.
    pub cx: f32,
    /// Centre y.
    pub cy: f32,
    /// The **short** axis, which for a text box is the glyph height.
    pub width: f32,
    /// The **long** axis, which is the run length.
    pub height: f32,
    /// Degrees, normalised to `[-90, 0)` before the canonical form is applied and then
    /// rotated into `(0, 180]` by it.
    pub angle: f32,
}

impl Rotated {
    /// Normalise into `[-90, 0)` by swapping the axes as needed.
    fn normalise(&mut self) {
        while self.angle >= 0.0 {
            self.angle -= 90.0;
            std::mem::swap(&mut self.width, &mut self.height);
        }
        while self.angle < -90.0 {
            self.angle += 90.0;
            std::mem::swap(&mut self.width, &mut self.height);
        }
    }
}

/// One detected text region, in source-image pixels.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Region {
    /// Where and how it is oriented.
    pub rect: Rotated,
    /// Mean probability over the component, `0..1`.
    pub score: f32,
    /// True when the run is within 30 degrees of vertical *and* elongated, so the crop
    /// has to rotate stacked glyphs back before recognition.
    pub vertical: bool,
}

/// Threshold, label, fit and enlarge — the whole of detection's post-processing.
///
/// `probability` is the net's single-channel output, `width` x `height`, row-major, in
/// `0..1`. `fit` is how the source was letterboxed into it, and `source` is the original
/// `(width, height)` the boxes come back in.
pub fn regions(
    probability: &[f32],
    width: u32,
    height: u32,
    fit: &Letterbox,
    source: (u32, u32),
) -> Result<Vec<Region>, String> {
    let cells = (width as usize)
        .checked_mul(height as usize)
        .ok_or("a probability map that overflows")?;
    if probability.len() != cells {
        return Err(format!(
            "{} probabilities for a {width}x{height} map",
            probability.len()
        ));
    }
    if width == 0 || height == 0 {
        return Err("a zero-sized probability map".into());
    }

    let text: Vec<bool> = probability.iter().map(|&p| p >= THRESHOLD).collect();
    let mut labelled = vec![false; cells];
    let mut stack: Vec<usize> = Vec::new();
    let mut boundary: Vec<(f32, f32)> = Vec::new();
    let mut found = Vec::new();

    let (w, h) = (width as usize, height as usize);
    for start in 0..cells {
        if found.len() >= MAX_CANDIDATES {
            break;
        }
        if !text[start] || labelled[start] {
            continue;
        }
        labelled[start] = true;
        stack.clear();
        stack.push(start);
        boundary.clear();
        let mut total = 0.0f64;
        let mut count = 0u32;

        while let Some(at) = stack.pop() {
            let (x, y) = (at % w, at / w);
            total += probability.get(at).copied().unwrap_or(0.0) as f64;
            count += 1;

            // On the boundary if any 4-neighbour is unset or off the edge. That is the
            // set whose hull equals the whole component's.
            let edge = x == 0
                || y == 0
                || x + 1 == w
                || y + 1 == h
                || !text[at - 1]
                || !text[at + 1]
                || !text[at - w]
                || !text[at + w];
            if edge {
                boundary.push((x as f32, y as f32));
            }

            for dy in -1i64..=1 {
                let ny = y as i64 + dy;
                if ny < 0 || ny as usize >= h {
                    continue;
                }
                for dx in -1i64..=1 {
                    let nx = x as i64 + dx;
                    if nx < 0 || nx as usize >= w {
                        continue;
                    }
                    let next = ny as usize * w + nx as usize;
                    if text[next] && !labelled[next] {
                        labelled[next] = true;
                        stack.push(next);
                    }
                }
            }
        }

        let score = if count > 0 { (total / count as f64) as f32 } else { 0.0 };
        if score < BOX_THRESHOLD {
            // Still counts against `MAX_CANDIDATES`, as in the reference: the budget is
            // on components examined, not on boxes kept.
            found.push(None);
            continue;
        }
        let Some(mut rect) = min_area_rect(&boundary) else {
            found.push(None);
            continue;
        };
        if rect.width.max(rect.height) < MIN_SIZE {
            found.push(None);
            continue;
        }

        // Canonical form: `width` is the short axis. Keying off which axis is longer
        // rather than off angle ranges is deliberate — see `ppocrv5.cpp:338-345`, where
        // the OpenCV-style branches never swap for angles in (-60, -30), so text tilted
        // up-right by 30 to 60 degrees got a crop with an inverted aspect ratio and was
        // never recognised.
        if rect.width > rect.height {
            std::mem::swap(&mut rect.width, &mut rect.height);
            rect.angle += 90.0;
        }
        let vertical =
            rect.height > rect.width * ELONGATED && (-30.0..=30.0).contains(&rect.angle);
        // Choose between the two representations 180 degrees apart so the advance runs
        // left-to-right for horizontal script and top-to-bottom for vertical.
        if vertical || rect.angle <= 0.0 {
            rect.angle += 180.0;
        }

        // DBNet predicts a shrunken box, so grow it back. The long axis grows by the
        // short axis's share, which keeps the enlargement roughly isotropic in pixels
        // rather than proportional to a run length that may be very long.
        rect.height += rect.width * (ENLARGE_RATIO - 1.0);
        rect.width *= ENLARGE_RATIO;

        // Out of the letterbox and back onto the source image.
        let scale = if fit.scale == 0.0 { 1.0 } else { fit.scale };
        rect.cx = (rect.cx - fit.offset.0 as f32) / scale;
        rect.cy = (rect.cy - fit.offset.1 as f32) / scale;
        rect.width /= scale;
        rect.height /= scale;

        let _ = source;
        found.push(Some(Region { rect, score, vertical }));
    }
    Ok(found.into_iter().flatten().collect())
}

/// The minimum-area enclosing rectangle
///
/// The optimal rectangle always has a side collinear with a hull edge, so this sweeps the
/// edges and keeps the best. Hulls here have a few dozen vertices, so the quadratic sweep
/// is cheaper than being clever.
fn min_area_rect(points: &[(f32, f32)]) -> Option<Rotated> {
    let hull = convex_hull(points);
    let (&first, rest) = hull.split_first()?;
    if rest.is_empty() {
        let mut rect = Rotated { cx: first.0, cy: first.1, width: 0.0, height: 0.0, angle: 0.0 };
        rect.normalise();
        return Some(rect);
    }

    let mut best: Option<(f32, Rotated)> = None;
    for index in 0..hull.len() {
        let a = hull.get(index).copied()?;
        let b = hull.get((index + 1) % hull.len()).copied()?;
        let (dx, dy) = (b.0 - a.0, b.1 - a.1);
        let length = (dx * dx + dy * dy).sqrt();
        if length < f32::EPSILON {
            continue;
        }
        // The edge's unit direction and its normal.
        let (ux, uy) = (dx / length, dy / length);
        let (mut low_u, mut high_u) = (f32::MAX, f32::MIN);
        let (mut low_v, mut high_v) = (f32::MAX, f32::MIN);
        for &(x, y) in &hull {
            let u = x * ux + y * uy;
            let v = -x * uy + y * ux;
            low_u = low_u.min(u);
            high_u = high_u.max(u);
            low_v = low_v.min(v);
            high_v = high_v.max(v);
        }
        let (extent_u, extent_v) = (high_u - low_u, high_v - low_v);
        let area = extent_u * extent_v;
        if best.as_ref().is_some_and(|(seen, _)| *seen <= area) {
            continue;
        }
        // Rotate the box centre back into image space.
        let (mid_u, mid_v) = ((low_u + high_u) * 0.5, (low_v + high_v) * 0.5);
        let mut rect = Rotated {
            cx: mid_u * ux - mid_v * uy,
            cy: mid_u * uy + mid_v * ux,
            width: extent_u,
            height: extent_v,
            angle: dy.atan2(dx).to_degrees(),
        };
        rect.normalise();
        best = Some((area, rect));
    }
    best.map(|(_, rect)| rect).or_else(|| {
        let mut rect = Rotated { cx: first.0, cy: first.1, width: 0.0, height: 0.0, angle: 0.0 };
        rect.normalise();
        Some(rect)
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A map that is `value` inside `rect` and 0 elsewhere.
    fn filled(width: u32, height: u32, rect: (u32, u32, u32, u32), value: f32) -> Vec<f32> {
        let mut map = vec![0.0; (width * height) as usize];
        let (x0, y0, x1, y1) = rect;
        for y in y0..y1 {
            for x in x0..x1 {
                map[(y * width + x) as usize] = value;
            }
        }
        map
    }

    fn identity() -> Letterbox {
        Letterbox::new(64, 64, 64, 32).expect("fits")
    }

    #[test]
    fn a_solid_block_becomes_one_region() {
        let map = filled(64, 64, (10, 20, 30, 26), 0.9);
        let got = regions(&map, 64, 64, &identity(), (64, 64)).expect("post-processes");
        assert_eq!(got.len(), 1);
        let region = got.first().copied().expect("one region");
        assert!((region.score - 0.9).abs() < 1e-5, "{}", region.score);
        // The block is 20 wide and 6 tall, so before enlargement the short axis is 5 and
        // the long axis 19 — boundary pixels are inclusive of both edges.
        assert!(region.rect.height > region.rect.width, "{:?}", region.rect);
        // Centre of a 10..30 x 20..26 block.
        assert!((region.rect.cx - 19.5).abs() < 1.0, "{:?}", region.rect);
        assert!((region.rect.cy - 22.5).abs() < 1.0, "{:?}", region.rect);
    }

    #[test]
    fn two_separated_blocks_become_two_regions() {
        let mut map = filled(64, 64, (4, 4, 24, 12), 0.9);
        for (i, v) in filled(64, 64, (34, 40, 60, 50), 0.8).iter().enumerate() {
            if *v > 0.0 {
                map[i] = *v;
            }
        }
        let got = regions(&map, 64, 64, &identity(), (64, 64)).expect("post-processes");
        assert_eq!(got.len(), 2);
    }

    #[test]
    fn diagonally_touching_pixels_are_one_component() {
        // 8-connectivity, not 4: the reference's flood fill walks all eight neighbours,
        // and a diagonal join is common where two glyphs nearly touch.
        let mut map = vec![0.0f32; 64 * 64];
        for y in 10..16 {
            for x in 10..16 {
                map[y * 64 + x] = 0.9;
            }
        }
        for y in 16..22 {
            for x in 16..22 {
                map[y * 64 + x] = 0.9;
            }
        }
        let got = regions(&map, 64, 64, &identity(), (64, 64)).expect("post-processes");
        assert_eq!(got.len(), 1, "a diagonal join must not split the component");
    }

    #[test]
    fn a_low_scoring_component_is_dropped() {
        // Above the 0.3 pixel threshold but below the 0.6 mean-probability floor.
        let map = filled(64, 64, (10, 10, 40, 20), 0.45);
        let got = regions(&map, 64, 64, &identity(), (64, 64)).expect("post-processes");
        assert!(got.is_empty(), "{got:?}");
    }

    #[test]
    fn a_component_below_the_pixel_threshold_is_invisible() {
        let map = filled(64, 64, (10, 10, 40, 20), 0.2);
        let got = regions(&map, 64, 64, &identity(), (64, 64)).expect("post-processes");
        assert!(got.is_empty(), "{got:?}");
    }

    #[test]
    fn a_tiny_speck_is_dropped_by_the_minimum_size() {
        // A 2x2 block: the long axis is 1, under `MIN_SIZE` of 3.
        let map = filled(64, 64, (10, 10, 12, 12), 0.9);
        let got = regions(&map, 64, 64, &identity(), (64, 64)).expect("post-processes");
        assert!(got.is_empty(), "{got:?}");
    }

    #[test]
    fn the_box_is_enlarged_by_the_ratio() {
        // A 20x6 block. Boundary pixels span 19 x 5, so before enlargement width is 5 and
        // height 19; after, width is 5 * 1.95 and height 19 + 5 * 0.95.
        let map = filled(64, 64, (10, 20, 30, 26), 0.9);
        let got = regions(&map, 64, 64, &identity(), (64, 64)).expect("post-processes");
        let rect = got.first().copied().expect("one region").rect;
        assert!((rect.width - 5.0 * ENLARGE_RATIO).abs() < 0.5, "{rect:?}");
        assert!((rect.height - (19.0 + 5.0 * 0.95)).abs() < 0.5, "{rect:?}");
    }

    #[test]
    fn a_wide_block_reads_horizontally_and_a_tall_one_vertically() {
        // The vertical test is `height > width * 2.7` *and* the run within 30 degrees of
        // vertical. A 6x30 upright block satisfies both; a 30x6 one satisfies neither.
        let wide = filled(64, 64, (10, 28, 40, 34), 0.9);
        let tall = filled(64, 64, (28, 10, 34, 40), 0.9);
        let horizontal = regions(&wide, 64, 64, &identity(), (64, 64)).expect("wide");
        let upright = regions(&tall, 64, 64, &identity(), (64, 64)).expect("tall");
        assert_eq!(horizontal.first().map(|r| r.vertical), Some(false));
        assert_eq!(upright.first().map(|r| r.vertical), Some(true));
    }

    #[test]
    fn the_letterbox_is_undone_back_onto_the_source() {
        // A 128x72 source at a long side of 64 scales by 0.5 to 64x36, and 36 pads up to
        // 64 — so 14 rows above. `Letterbox::new` is the tight fit `ppocrv5.cpp` uses,
        // not a square.
        let fit = Letterbox::new(128, 72, 64, 32).expect("fits");
        assert_eq!(fit.scale, 0.5);
        assert_eq!(fit.resized, (64, 36));
        assert_eq!(fit.padded, (64, 64));
        assert_eq!(fit.offset, (0, 14));

        let map = filled(64, 64, (10, 30, 50, 34), 0.9);
        let got = regions(&map, 64, 64, &fit, (128, 72)).expect("post-processes");
        let rect = got.first().copied().expect("one region").rect;
        // Map centre x about 29.5, at scale 0.5 -> 59 in the source.
        assert!((rect.cx - 59.0).abs() < 2.0, "{rect:?}");
        // Map centre y about 31.5, minus 14 of padding, over 0.5 -> 35.
        assert!((rect.cy - 35.0).abs() < 2.0, "{rect:?}");
    }

    #[test]
    fn a_rotated_bar_gets_a_rectangle_of_the_right_aspect() {
        // A bar at 45 degrees. The minimum-area rectangle must follow it rather than
        // being the axis-aligned bounding box, which would be nearly square.
        let mut map = vec![0.0f32; 64 * 64];
        for step in 0..30 {
            for thickness in -1i64..=1 {
                let x = 15 + step;
                let y = 15 + step + thickness;
                if (0..64).contains(&x) && (0..64).contains(&y) {
                    map[(y as usize) * 64 + x as usize] = 0.9;
                }
            }
        }
        let got = regions(&map, 64, 64, &identity(), (64, 64)).expect("post-processes");
        let rect = got.first().copied().expect("one region").rect;
        // The run is about 30*sqrt(2) = 42 long and a few pixels thick, so the aspect
        // ratio is far from 1. An axis-aligned box would be about 30x30.
        assert!(rect.height > rect.width * 2.0, "{rect:?}");
    }

    #[test]
    fn the_hull_of_a_square_is_its_four_corners() {
        let points = vec![
            (0.0, 0.0),
            (1.0, 0.0),
            (2.0, 0.0),
            (2.0, 2.0),
            (0.0, 2.0),
            (1.0, 1.0),
        ];
        let hull = convex_hull(&points);
        assert_eq!(hull.len(), 4, "{hull:?}");
        // The interior point must not survive.
        assert!(!hull.contains(&(1.0, 1.0)), "{hull:?}");
    }

    #[test]
    fn the_minimum_area_rectangle_of_an_axis_aligned_square_is_that_square() {
        let points = vec![(0.0, 0.0), (4.0, 0.0), (4.0, 4.0), (0.0, 4.0)];
        let rect = min_area_rect(&points).expect("a rectangle");
        assert!((rect.width - 4.0).abs() < 1e-3, "{rect:?}");
        assert!((rect.height - 4.0).abs() < 1e-3, "{rect:?}");
        assert!((rect.cx - 2.0).abs() < 1e-3, "{rect:?}");
        assert!((rect.cy - 2.0).abs() < 1e-3, "{rect:?}");
    }

    #[test]
    fn the_angle_always_normalises_into_minus_ninety_to_zero() {
        // The convention the canonical form branches on. Every edge direction a hull can
        // produce has to land in the range.
        for degrees in (-350..350).step_by(7) {
            let radians = (degrees as f32).to_radians();
            let (dx, dy) = (radians.cos() * 10.0, radians.sin() * 10.0);
            let points = vec![
                (0.0, 0.0),
                (dx, dy),
                (dx - dy * 0.2, dy + dx * 0.2),
                (-dy * 0.2, dx * 0.2),
            ];
            let rect = min_area_rect(&points).expect("a rectangle");
            assert!(
                (-90.0..0.0).contains(&rect.angle),
                "{degrees} degrees gave {}",
                rect.angle
            );
        }
    }

    #[test]
    fn a_mismatched_map_length_is_refused() {
        let error = regions(&[0.0; 10], 64, 64, &identity(), (64, 64)).expect_err("short map");
        assert!(error.contains("10 probabilities"), "{error}");
    }
}

#[cfg(test)]
#[path = "dbnet_regions_tests.rs"]
mod dbnet_regions_tests;
