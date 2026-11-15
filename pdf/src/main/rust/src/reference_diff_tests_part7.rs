/// FINDING — the `i` (flatness) operator makes curve faceting VISIBLE, and
/// §10.6.2 says we are the ones out of conformance. **OUR BUG, one-line fix.**
///
/// §10.6.2 defines flatness as "the maximum permitted distance IN DEVICE PIXELS
/// between the mathematically correct path and an approximation constructed
/// from straight line segments". `interpret.rs:200-201` consumes it as a
/// tolerance in the units of `gs.ctm`, which `page_base_matrix` leaves as page
/// POINTS at 1:1 — the same units confusion that produced the S6 revert, but
/// here it is amplified by an operator the document controls.
///
/// Measured at 10.6 px/pt (a 612pt page at max zoom on a 1080px viewport).
/// NOTE that 10.6 is NOT the worst case — `maxZoomFor` floors the zoom at
/// `MAX_ZOOM = 6`, so effective resolution is `6 * viewportPx / pageWidthPts`
/// whenever the page is narrower than ~1.5x the viewport, which grows without
/// bound as the page narrows: a 200pt receipt on a tablet reaches 43.2 px/pt
/// and a 100pt label 86.4. Scale the figures below linearly for those. At
/// 43.2 px/pt, `i 3` is ~17.7 device px of chord error.
///
/// ```text
///   default (no `i`)   93 pts   per rim-px 0.175   sagitta 0.42 device px
///   i 1                49 pts   per rim-px 0.308   sagitta 1.52 device px
///   i 3                29 pts   per rim-px 2.419   sagitta 4.35 device px
///   i 10 (-> 3)        29 pts   per rim-px 2.419   sagitta 4.35 device px
/// ```
///
/// So a document asking for 3 device pixels of tolerance is given 4.35, and one
/// asking for 1 is given 1.52 — over budget in both cases, and 10x worse than
/// the default the same renderer uses when the document says nothing. The
/// `flatness.min(3.0)` clamp at `:200` is what stops it getting worse still.
///
/// THE INTERIM FIX IS `min`, NOT ASSIGNMENT — take the FINER of the two:
///
/// ```ignore
/// // interpret.rs:200 — currently:
/// let tol = if flatness > 0.0 { flatness.min(3.0) } else { 0.25 };
/// // proposed interim:
/// let tol = if flatness > 0.0 { flatness.min(0.25) } else { 0.25 };
/// ```
///
/// An earlier version of this note proposed `let tol = 0.25;` unconditionally.
/// That is WRONG and `hunt-wrong2` caught it: a fine `i` asks for MORE accuracy
/// than our default, and flattening it to 0.25 would coarsen it. Measured:
///
/// ```text
///   i 0.05  -> 201 pts, 0.09 device px    finer than default
///   i 0.1   -> 141 pts, 0.18 device px    finer than default
///   (none)  ->  93 pts, 0.42 device px
///   i 1     ->  49 pts, 1.52 device px
///   i 3     ->  29 pts, 4.35 device px
/// ```
///
/// So unconditional assignment would have taken `i 0.05` from 0.09 to 0.42 —
/// a 4.7x regression on a document explicitly requesting precision, to fix the
/// coarse case. `min` improves the coarse case identically and leaves the fine
/// case untouched.
///
/// WHY THE VIOLATION RATIO IS INDEPENDENT OF WHAT THE DOCUMENT ASKS. Since
/// `n = sqrt(len/tol)` and a cubic's deviation goes as `C·len/n²`, the delivered
/// sagitta is `C·tol` with `C ≈ 0.15` — proportional to the tolerance, not to
/// the curve. The bug is that `tol` is read as points when §10.6.2 denominates
/// it in device pixels, so `requested_pt = tol/scale` and
/// `violation = C·tol / (tol/scale) = C·scale`. The tolerance cancels: the
/// over-permission depends ONLY on the on-screen scale, which is why it is
/// ~1.6x at 10.6 px/pt whatever `i` says, and why break-even is at
/// `scale = 1/C ≈ 6.7 px/pt` — below that the formula's own conservatism more
/// than covers the unit error, above it does not. That near-cancellation at
/// ordinary scales is why this was never noticed.
///
/// The interim does NOT fix fine `i` — `i 0.1` stays at ~1.6x over-permitted,
/// because the unit error is untouched. It declines to make things worse. The
/// real fix is to send curves and let the consumer flatten at device
/// resolution, which is the same change the clip path already uses.
///
/// Not applied here: `interpret.rs` belongs to `fix-interp`. This test RECORDS
/// the numbers and bounds them loosely so a change in either direction shows up.
#[test]
fn refdiff_flatness_operator_amplifies_faceting() {
    let (cx, cy, r) = (100.0f64, 100.0f64, 70.0);
    let k = 0.552_284_749_8 * r;
    let circle = |flatness: Option<f64>| {
        let mut v = vec![op("rg", vec![n(0.15), n(0.35), n(0.8)])];
        if let Some(f) = flatness {
            v.push(op("i", vec![n(f)]));
        }
        v.extend(vec![
            op("m", vec![n(cx + r), n(cy)]),
            op("c", vec![n(cx + r), n(cy + k), n(cx + k), n(cy + r), n(cx), n(cy + r)]),
            op("c", vec![n(cx - k), n(cy + r), n(cx - r), n(cy + k), n(cx - r), n(cy)]),
            op("c", vec![n(cx - r), n(cy - k), n(cx - k), n(cy - r), n(cx), n(cy - r)]),
            op("c", vec![n(cx + k), n(cy - r), n(cx + r), n(cy - k), n(cx + r), n(cy)]),
            op("h", vec![]),
            op("f", vec![]),
        ]);
        v
    };
    const ZOOM: f32 = 10.6;
    let mut worst_sagitta = 0.0f64;
    for (label, f) in [("default", None), ("i 1", Some(1.0)), ("i 3", Some(3.0))] {
        let bytes = pdf_bytes(circle(f), dictionary! {});
        let doc = load_document_lenient(&bytes).expect("load");
        let pid = nth_page_id(&doc, 0).expect("page");
        let page = interpret_page(&doc, pid).expect("interpret");
        let pts = page
            .prims
            .iter()
            .find_map(|p| match p {
                Prim::Fill { contours, .. } => Some(contours.iter().map(|c| c.len()).sum::<usize>()),
                _ => None,
            })
            .unwrap_or(0);
        let (canvas, skipped) = rasterize(&page, ZOOM);
        assert_eq!(skipped, Skipped::default());
        let ours = canvas.to_rgb8();
        let (w, h, theirs) = hayro_rgb8(&bytes, ZOOM);
        assert_eq!((canvas.w, canvas.h), (w, h));
        let rep = fuzzy_diff(&ours, &theirs, w, h);
        let perim = 2.0 * std::f64::consts::PI * r * ZOOM as f64;
        let theta = std::f64::consts::TAU / (pts.max(3) as f64);
        let sag = r * (1.0 - (theta / 2.0).cos()) * ZOOM as f64;
        worst_sagitta = worst_sagitta.max(sag);
        println!(
            "  {label:8}: {pts:3} pts  flagged {:6} ({:.4}%)  per rim-px {:.3}  sagitta {sag:.2} device px",
            rep.flagged,
            rep.fraction * 100.0,
            rep.flagged as f64 / perim
        );
    }
    println!("  worst sagitta across flatness settings: {worst_sagitta:.2} device px at {ZOOM} px/pt");
    // Loose, and deliberately two-sided: it should FALL if `i` is ignored as
    // proposed, and must not rise.
    assert!(
        worst_sagitta <= 6.0,
        "flatness handling got worse: {worst_sagitta:.2} device px of chord error"
    );
}


