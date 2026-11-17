//! Coverage tests for interpret.rs pure helpers: bezier flatness, shoelace, dash parsing.
use crate::*;

fn empty_doc() -> Document { Document::with_version("1.7") }
fn ints(v: &[i64]) -> Vec<Object> { v.iter().map(|n| Object::Integer(*n)).collect() }

#[test]
fn bezier_steps_degenerate_and_clamps() {
    assert_eq!(bezier_steps_for_flatness([(0.0, 0.0); 4], 0.25), 1, "zero-length hull");
    assert_eq!(bezier_steps_for_flatness([(f64::NAN, 0.0), (1.0, 1.0), (2.0, 2.0), (3.0, 3.0)], 0.25), 1);
    assert_eq!(bezier_steps_for_flatness([(f64::INFINITY, 0.0), (1.0, 1.0), (2.0, 2.0), (3.0, 3.0)], 0.25), 1);
    // Tiny curve clamps to minimum 4.
    assert_eq!(bezier_steps_for_flatness([(0.0, 0.0), (0.1, 0.0), (0.2, 0.0), (0.3, 0.0)], 0.25), 4);
    // Huge curve clamps to maximum 64.
    assert_eq!(bezier_steps_for_flatness([(0.0, 0.0), (10000.0, 0.0), (20000.0, 0.0), (30000.0, 0.0)], 0.25), 64);
    // flatness <=0 uses default 0.25; flatness >3 is capped at 3.
    let hull = [(0.0, 0.0), (10.0, 0.0), (20.0, 0.0), (30.0, 0.0)];
    assert_eq!(bezier_steps_for_flatness(hull, 0.0), bezier_steps_for_flatness(hull, 0.25));
    assert_eq!(bezier_steps_for_flatness(hull, -5.0), bezier_steps_for_flatness(hull, 0.25));
    assert_eq!(bezier_steps_for_flatness(hull, 100.0), bezier_steps_for_flatness(hull, 3.0));
    // Larger tolerance -> fewer steps.
    assert!(bezier_steps_for_flatness(hull, 3.0) <= bezier_steps_for_flatness(hull, 0.25));
}

#[test]
fn shoelace_signed_area() {
    assert_eq!(shoelace_area(&[]), 0.0);
    assert_eq!(shoelace_area(&[(0.0, 0.0), (1.0, 1.0)]), 0.0);
    let ccw = [(0.0, 0.0), (4.0, 0.0), (4.0, 3.0), (0.0, 3.0)];
    assert_eq!(shoelace_area(&ccw), 12.0);
    let mut cw = ccw; cw.reverse();
    assert_eq!(shoelace_area(&cw), -12.0);
    let tri = [(0.0, 0.0), (6.0, 0.0), (0.0, 4.0)];
    assert_eq!(shoelace_area(&tri), 12.0);
}

#[test]
fn dash_empty_and_pure_pair_is_not_phase() {
    let doc = empty_doc();
    assert_eq!(parse_dash_d_array(&doc, &[]), (vec![], 0.0));
    // Critical: [3 3] is a dash pattern, NOT [3] with phase 3.
    assert_eq!(parse_dash_d_array(&doc, &ints(&[3, 3])), (vec![3.0, 3.0], 0.0));
    assert_eq!(parse_dash_d_array(&doc, &ints(&[6])), (vec![6.0], 0.0));
}

#[test]
fn dash_flat_three_or_more_treats_last_as_phase() {
    let doc = empty_doc();
    assert_eq!(parse_dash_d_array(&doc, &ints(&[3, 2, 1])), (vec![3.0, 2.0], 1.0));
    assert_eq!(parse_dash_d_array(&doc, &ints(&[4, 2, 4, 2, 5])), (vec![4.0, 2.0, 4.0, 2.0], 5.0));
}

#[test]
fn dash_canonical_nested_form() {
    let doc = empty_doc();
    let arr = vec![Object::Array(ints(&[3, 2])), Object::Integer(1)];
    assert_eq!(parse_dash_d_array(&doc, &arr), (vec![3.0, 2.0], 1.0));
    // Nested with indirect phase/dashes via a real document.
    let mut doc = empty_doc();
    let inner_id = doc.add_object(Object::Array(ints(&[5, 5])));
    let phase_id = doc.add_object(Object::Integer(2));
    let arr = vec![Object::Reference(inner_id), Object::Reference(phase_id)];
    assert_eq!(parse_dash_d_array(&doc, &arr), (vec![5.0, 5.0], 2.0));
}

#[test]
fn dash_filters_negative_nan_and_infinite() {
    let doc = empty_doc();
    let arr = vec![Object::Integer(-3), Object::Integer(4), Object::Integer(2), Object::Integer(0)];
    let (d, p) = parse_dash_d_array(&doc, &arr);
    assert_eq!(d, vec![4.0, 2.0], "negative dash dropped; flat len>=3 last is phase");
    assert_eq!(p, 0.0);
    // Non-finite entries never reach the wire.
    let arr = vec![Object::Real(f32::INFINITY), Object::Real(f32::NAN), Object::Integer(3), Object::Integer(3)];
    let (d, _) = parse_dash_d_array(&doc, &arr);
    assert!(d.iter().all(|v| v.is_finite() && *v >= 0.0), "got {d:?}");
    assert!(d.contains(&3.0));
    // Non-numeric array yields empty.
    let arr = vec![Object::Name(b"none".to_vec()), Object::Boolean(true)];
    assert_eq!(parse_dash_d_array(&doc, &arr), (vec![], 0.0));
}

#[test]
fn dash_length_is_capped() {
    let doc = empty_doc();
    let long: Vec<Object> = (0..100).map(|_| Object::Integer(1)).collect();
    let (d, _) = parse_dash_d_array(&doc, &long);
    assert_eq!(d.len(), MAX_DASH_LEN);
}

#[test]
fn dash_extgstate_delegates_only_for_arrays() {
    let doc = empty_doc();
    assert_eq!(parse_dash_extgstate(&doc, &Object::Array(ints(&[2, 2]))), (vec![2.0, 2.0], 0.0));
    assert_eq!(parse_dash_extgstate(&doc, &Object::Integer(7)), (vec![], 0.0));
    assert_eq!(parse_dash_extgstate(&doc, &Object::Null), (vec![], 0.0));
    // Indirect array resolves.
    let mut doc = empty_doc();
    let id = doc.add_object(Object::Array(ints(&[7, 7])));
    assert_eq!(parse_dash_extgstate(&doc, &Object::Reference(id)), (vec![7.0, 7.0], 0.0));
}
