use super::metrics::{MEDIUM_TTF, REGULAR_TTF};
use super::placement::place_in_cell;
use super::*;
use crate::tile::glyph::fonts_staged;
use ab_glyph::Font as _;

/// The staged TTFs are GitHub 404 pages, not fonts (task 54 re-fetch pending).
/// These tests need real Noto Sans bytes; they run again once they land.
fn real_fonts_staged() -> bool {
    fonts_staged()
}

#[test]
fn both_bundled_fonts_parse_and_cover_ascii() {
    if !real_fonts_staged() {
        eprintln!("SKIP: staged TTFs are 404 pages, not fonts");
        return;
    }
    let atlas = GlyphAtlas::build();
    for ch in ['A', 'a', '0', ' ', '-', '\''] {
        assert!(
            atlas.metrics(Weight::Regular, ch).is_some() || ch == ' ',
            "{ch:?} missing from Regular"
        );
        assert!(
            atlas.metrics(Weight::Medium, ch).is_some() || ch == ' ',
            "{ch:?} missing"
        );
    }
}

#[test]
fn the_atlas_is_a_full_r8_image() {
    if !real_fonts_staged() {
        eprintln!("SKIP: staged TTFs are 404 pages, not fonts");
        return;
    }
    let atlas = GlyphAtlas::build();
    assert_eq!(atlas.pixels.len(), (ATLAS_PX * ATLAS_PX) as usize);
    // An SDF atlas is mostly edge gradient: with spread 8 the wells are narrow,
    // so assert the gradient band is well populated and both extremes exist.
    // (A fully-flat 127 image means the transform wrote nothing.)
    let min = *atlas.pixels.iter().min().unwrap();
    let max = *atlas.pixels.iter().max().unwrap();
    let mid = atlas
        .pixels
        .iter()
        .filter(|&&v| (64..=192).contains(&v))
        .count();
    assert!(mid > 10_000, "mid {mid}");
    assert!(min < 64, "min {min}");
    assert!(max > 192, "max {max}");
}

/// The task-1 regression: the RGBA8 upload must carry the SDF in every
/// channel — the exact bytes `vulkan::images` hands the driver and
/// `symbol.frag` samples as `.r`. Needs no fonts: pure byte order.
#[test]
fn the_rgba8_expansion_carries_sdf_in_every_channel() {
    assert_eq!(
        expand_sdf_r8_to_rgba8(&[0, 127, 255]),
        vec![0, 0, 0, 0, 127, 127, 127, 127, 255, 255, 255, 255],
    );
    assert!(expand_sdf_r8_to_rgba8(&[]).is_empty());
}

/// P1 gibberish guard: insertion key and lookup key are the same char.
/// Every ASCII codepoint the atlas claims must round-trip: metrics found
/// under `(weight, ch)` must carry the cell assigned at insertion, and the
/// UV rect must lie inside that cell. A mismatch here (codepoint vs
/// glyph-id vs cluster indexing) renders the wrong glyph per quad —
/// legible boxes, wrong letters.
#[test]
fn every_char_looks_up_the_cell_it_was_inserted_in() {
    if !real_fonts_staged() {
        eprintln!("SKIP: staged TTFs are 404 pages, not fonts");
        return;
    }
    let atlas = GlyphAtlas::build();
    let chars = charset();
    for weight in [Weight::Regular, Weight::Medium] {
        let base = if weight == Weight::Regular {
            0
        } else {
            chars.len() as u32
        };
        for (i, &ch) in chars.iter().enumerate() {
            let Some(m) = atlas.metrics(weight, ch) else {
                continue;
            };
            // Insertion assigned Regular -> i, Medium -> len + i.
            assert_eq!(m.cell, base + i as u32, "{weight:?} {ch:?}");
            let Some(uv) = atlas.uv(weight, ch) else {
                panic!("{weight:?} {ch:?} has metrics but no UV");
            };
            if m.w == 0.0 {
                continue; // space: no ink, no rect
            }
            // The rect addresses this glyph's own cell and no neighbour's.
            let col = m.cell % ATLAS_COLS;
            let row = m.cell / ATLAS_COLS;
            let n = ATLAS_PX as f32;
            let (lo_u, lo_v) = ((col * CELL_PX) as f32 / n, (row * CELL_PX) as f32 / n);
            let (hi_u, hi_v) = (
                ((col + 1) * CELL_PX) as f32 / n,
                ((row + 1) * CELL_PX) as f32 / n,
            );
            assert!(
                uv.u0 >= lo_u && uv.u1 <= hi_u,
                "{weight:?} {ch:?} u escapes its cell"
            );
            assert!(
                uv.v0 >= lo_v && uv.v1 <= hi_v,
                "{weight:?} {ch:?} v escapes its cell"
            );
        }
    }
}

/// THE regression this module was rewritten for. The UV rect and the quad
/// have to describe the same region, or every glyph draws at a fraction of
/// its size inside a correctly-spaced advance — tiny, letter-spaced text.
///
/// Checked as an aspect-ratio identity, which is the part that cannot be
/// fixed by scaling `text_size`: the rect's width:height must equal the
/// quad's width:height. Addressing the whole 64px cell (the old bug) makes
/// every rect square while the quads keep the glyphs' own proportions.
#[test]
fn a_glyphs_uv_rect_has_the_same_aspect_ratio_as_its_quad() {
    if !real_fonts_staged() {
        eprintln!("SKIP: staged TTFs are 404 pages, not fonts");
        return;
    }
    let atlas = GlyphAtlas::build();
    let mut checked = 0;
    for ch in charset() {
        let Some(m) = atlas.metrics(Weight::Regular, ch) else {
            continue;
        };
        if m.w == 0.0 || m.h == 0.0 {
            continue;
        }
        let uv = atlas.uv(Weight::Regular, ch).expect("metrics imply UV");
        let uv_aspect = (uv.u1 - uv.u0) / (uv.v1 - uv.v0);
        let quad_aspect = m.w / m.h;
        // A texel of rounding in the cell placement is the only slack here.
        assert!(
            (uv_aspect - quad_aspect).abs() < 0.06 * quad_aspect.max(1.0),
            "{ch:?}: rect aspect {uv_aspect:.4} vs quad aspect {quad_aspect:.4}",
        );
        checked += 1;
    }
    assert!(checked > 80, "only {checked} glyphs checked");
}

/// The quad is the ink grown by the spread, so it is always a little wider
/// and taller than the ink itself — and a capital still has to occupy most
/// of its advance. The old whole-cell UV made the drawn ink roughly half
/// size, which this bounds from both sides.
#[test]
fn a_capitals_quad_is_the_right_size_against_its_advance() {
    if !real_fonts_staged() {
        eprintln!("SKIP: staged TTFs are 404 pages, not fonts");
        return;
    }
    let atlas = GlyphAtlas::build();
    let m = atlas
        .metrics(Weight::Regular, 'H')
        .expect("H is in the charset");
    // Cap height is ~0.714 em; the quad adds spread on both sides, so it
    // lands above that and well under a whole em and a half.
    let em = UP_EM as f32;
    assert!(
        m.h / em > 0.71,
        "H quad {:.3} em is shorter than its cap height",
        m.h / em
    );
    assert!(
        m.h / em < 1.3,
        "H quad {:.3} em is implausibly tall",
        m.h / em
    );
    // And it fills its advance rather than rattling around inside it.
    assert!(
        m.w > m.advance * 0.8,
        "H quad {} narrow against advance {}",
        m.w,
        m.advance
    );
}

/// `UP_EM` is the denominator for every font-unit metric, so a font whose real
/// unitsPerEm differs draws all text at the wrong size — silently, because
/// nothing else in the pipeline knows the em is wrong. The bundled faces
/// declare 1000; a 2048 assumption shrank every label to 49% of its size.
#[test]
fn the_bundled_fonts_use_the_declared_upem() {
    if !real_fonts_staged() {
        eprintln!("SKIP: staged TTFs are 404 pages, not fonts");
        return;
    }
    for (name, bytes) in [("Regular", REGULAR_TTF), ("Medium", MEDIUM_TTF)] {
        let font = ab_glyph::FontRef::try_from_slice(bytes).expect("parses");
        assert_eq!(
            font.units_per_em(),
            Some(UP_EM as f32),
            "{name} declares a different em to UP_EM",
        );
    }
}

#[test]
fn a_glyph_is_centred_in_its_cell_with_the_spread_reserved_all_round() {
    // ox/oy are what let `ink_uv` pad outwards without leaving the cell.
    for (w, h) in [(10, 34), (34, 34), (60, 20), (200, 200), (1, 1)] {
        let p = place_in_cell(w, h);
        assert!(
            p.ox >= SDF_SPREAD_PX,
            "{w}x{h}: ox {} under the margin",
            p.ox
        );
        assert!(
            p.oy >= SDF_SPREAD_PX,
            "{w}x{h}: oy {} under the margin",
            p.oy
        );
        assert!(
            p.ox + p.dw + SDF_SPREAD_PX <= CELL_PX,
            "{w}x{h}: overruns right"
        );
        assert!(
            p.oy + p.dh + SDF_SPREAD_PX <= CELL_PX,
            "{w}x{h}: overruns bottom"
        );
        assert!(
            p.scale > 0.0 && p.scale <= 1.0,
            "{w}x{h}: scale {}",
            p.scale
        );
    }
}

#[test]
fn uv_rects_stay_inside_the_image() {
    if !real_fonts_staged() {
        eprintln!("SKIP: staged TTFs are 404 pages, not fonts");
        return;
    }
    let atlas = GlyphAtlas::build();
    for ch in charset() {
        if let Some(uv) = atlas.uv(Weight::Regular, ch) {
            assert!(uv.u0 <= uv.u1 && uv.v0 <= uv.v1, "{ch:?}");
            assert!(uv.u1 <= 1.0 && uv.v1 <= 1.0, "{ch:?}");
        }
    }
}

/// Issue 821: a Budapest street name must round-trip every character through the
/// atlas — diacritics included. `Erzsébet híd` carries `é` (Latin-1) and `í`;
/// `Árpád híd` adds `Á`. A missing entry here is a letter dropped from the map.
#[test]
fn budapest_street_names_shape_every_character() {
    if !real_fonts_staged() {
        eprintln!("SKIP: staged TTFs are 404 pages, not fonts");
        return;
    }
    let atlas = GlyphAtlas::build();
    for name in ["Erzsébet híd", "Árpád híd", "Dózsa György út", "Kossuth Lajos utca"] {
        for ch in name.chars().filter(|&c| c != ' ') {
            assert!(
                atlas.metrics(Weight::Regular, ch).is_some(),
                "{name:?}: {ch:?} (U+{:04X}) has no atlas entry",
                ch as u32,
            );
        }
    }
}

#[test]
fn the_fast_distance_transform_matches_the_square_scan_on_every_glyph() {
    // The nearest-first search with a precomputed mask must be bit-identical to the original
    // square scan, or every label on the map changes weight. Checked over the real charset in
    // both weights, which covers both placement branches (scale 1.0 and scaled-to-fit).
    if !real_fonts_staged() {
        eprintln!("SKIP: staged TTFs are 404 pages, not fonts");
        return;
    }
    use super::sdf::{sdf_from_coverage, sdf_from_coverage_reference};
    use ab_glyph::FontRef;
    let mut checked = 0;
    for ttf in [REGULAR_TTF, MEDIUM_TTF] {
        let font = FontRef::try_from_slice(ttf).unwrap();
        for ch in super::metrics::charset() {
            let id = font.glyph_id(ch);
            let Some(outlined) = font.outline_glyph(id.with_scale(48.0)) else { continue };
            let bounds = outlined.px_bounds();
            let (w, h) = (bounds.width().ceil() as u32, bounds.height().ceil() as u32);
            if w == 0 || h == 0 {
                continue;
            }
            let mut coverage = vec![0u8; (w * h) as usize];
            outlined.draw(|x, y, v| {
                if x < w && y < h {
                    coverage[(y * w + x) as usize] = (v * 255.0) as u8;
                }
            });
            let placement = place_in_cell(w, h);
            assert_eq!(
                sdf_from_coverage(&coverage, w, h, placement),
                sdf_from_coverage_reference(&coverage, w, h, placement),
                "SDF of {ch:?} differs from the reference"
            );
            checked += 1;
        }
    }
    assert!(checked > 400, "only {checked} glyphs checked");
}
