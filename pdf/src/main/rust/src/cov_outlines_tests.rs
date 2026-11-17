//! Coverage tests for ContourBuilder flattening/closure and GlyphProgram UPM.
use crate::outlines::{ContourBuilder, GlyphProgram};
use crate::type1::Type1Font;
use std::collections::HashMap;

#[test]
fn line_contour_is_closed_by_duplicating_first_point() {
    let mut cb = ContourBuilder::new();
    cb.move_to(0.0, 0.0);
    cb.line_to(10.0, 0.0);
    cb.line_to(10.0, 10.0);
    let out = cb.finish();
    assert_eq!(out.len(), 1);
    assert_eq!(out[0].first(), out[0].last(), "contour must be explicitly closed");
    assert_eq!(out[0].len(), 4, "3 corners + duplicated start");
}

#[test]
fn already_closed_contour_is_not_double_closed() {
    let mut cb = ContourBuilder::new();
    cb.move_to(0.0, 0.0);
    cb.line_to(10.0, 0.0);
    cb.line_to(0.0, 0.0);
    let out = cb.finish();
    assert_eq!(out[0].len(), 3);
}

#[test]
fn degenerate_contours_are_discarded() {
    let mut cb = ContourBuilder::new();
    cb.move_to(1.0, 1.0);
    cb.line_to(2.0, 2.0); // only 2 points
    cb.move_to(0.0, 0.0);
    cb.line_to(5.0, 0.0);
    cb.line_to(5.0, 5.0);
    let out = cb.finish();
    assert_eq!(out.len(), 1, "2-point subpath dropped, triangle kept");
    // add_contour with <3 points is ignored, >=3 kept verbatim.
    let mut cb = ContourBuilder::new();
    cb.add_contour(vec![(0.0, 0.0), (1.0, 1.0)]);
    assert!(cb.finish().is_empty());
    let mut cb = ContourBuilder::new();
    cb.add_contour(vec![(0.0, 0.0), (1.0, 0.0), (1.0, 1.0)]);
    assert_eq!(cb.finish().len(), 1);
}

#[test]
fn quad_and_cubic_end_at_their_endpoint_with_fixed_steps() {
    let mut cb = ContourBuilder::new();
    cb.move_to(0.0, 0.0);
    cb.quad_to(5.0, 10.0, 10.0, 0.0);
    cb.line_to(20.0, 0.0);
    let out = cb.finish();
    // move(1) + 10 quad steps + 1 line + duplicated close = 13
    assert_eq!(out[0].len(), 13);
    assert_eq!(out[0][10], (10.0, 0.0), "quad endpoint exact");

    let mut cb = ContourBuilder::new();
    cb.move_to(0.0, 0.0);
    cb.curve_to(0.0, 10.0, 10.0, 10.0, 10.0, 0.0);
    cb.line_to(20.0, 0.0);
    let out = cb.finish();
    assert_eq!(out[0][10], (10.0, 0.0), "cubic endpoint exact");
    // Midpoint of that cubic at t=0.5: 0.125*0+0.375*0+0.375*10+0.125*10 = 5 x.
    assert!((out[0][5].0 - 5.0).abs() < 1e-9);
}

#[test]
fn close_flushes_and_next_move_starts_new_contour() {
    let mut cb = ContourBuilder::new();
    cb.move_to(0.0, 0.0); cb.line_to(4.0, 0.0); cb.line_to(4.0, 4.0); cb.close();
    cb.move_to(20.0, 20.0); cb.line_to(24.0, 20.0); cb.line_to(24.0, 24.0);
    let out = cb.finish();
    assert_eq!(out.len(), 2);
}

#[test]
fn glyph_program_units_per_em() {
    let sfnt = GlyphProgram::Sfnt { data: vec![], upm: 2048.0 };
    assert_eq!(sfnt.units_per_em(), 2048.0);
    let cff = GlyphProgram::Cff { data: vec![], upm: 1000.0, cid_to_gid: None };
    assert_eq!(cff.units_per_em(), 1000.0);
    let t1 = Type1Font { glyphs: HashMap::new(), encoding: HashMap::new(),
        font_matrix: [0.001, 0.0, 0.0, 0.001, 0.0, 0.0] };
    assert_eq!(GlyphProgram::Type1(t1).units_per_em(), 1000.0);
    let t1z = Type1Font { glyphs: HashMap::new(), encoding: HashMap::new(),
        font_matrix: [0.0; 6] };
    assert_eq!(GlyphProgram::Type1(t1z).units_per_em(), 1000.0, "zero matrix -> default 1000");
}
