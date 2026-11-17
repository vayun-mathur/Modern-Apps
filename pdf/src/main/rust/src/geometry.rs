use crate::*;

/// A PDF transformation matrix `[a b c d e f]` representing
/// `[[a b 0] [c d 0] [e f 1]]`.
pub(crate) type Mat = [f64; 6];

pub(crate) const IDENTITY: Mat = [1.0, 0.0, 0.0, 1.0, 0.0, 0.0];

/// `m1 * m2` in PDF convention (m1 is applied first).
pub(crate) fn mat_mul(m1: &Mat, m2: &Mat) -> Mat {
    [
        m1[0] * m2[0] + m1[1] * m2[2],
        m1[0] * m2[1] + m1[1] * m2[3],
        m1[2] * m2[0] + m1[3] * m2[2],
        m1[2] * m2[1] + m1[3] * m2[3],
        m1[4] * m2[0] + m1[5] * m2[2] + m2[4],
        m1[4] * m2[1] + m1[5] * m2[3] + m2[5],
    ]
}

/// Transform point `(x, y)` by `m`.
pub(crate) fn transform(m: &Mat, x: f64, y: f64) -> (f64, f64) {
    (m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5])
}

pub(crate) fn translate(tx: f64, ty: f64) -> Mat {
    [1.0, 0.0, 0.0, 1.0, tx, ty]
}

// ---------------------------------------------------------------------------
// Primitives
// ---------------------------------------------------------------------------

/// Look up `key` on the page dict, walking up `/Parent` for inherited
/// attributes (`MediaBox`, `Resources`).
pub(crate) fn inherited<'a>(doc: &'a Document, page_id: ObjectId, key: &[u8]) -> Option<&'a Object> {
    let mut current = page_id;
    for _ in 0..32 {
        let dict = doc.get_dictionary(current).ok()?;
        if let Ok(obj) = dict.get(key) {
            return Some(obj);
        }
        {
            let parent = dict.get(b"Parent").ok().and_then(|o| o.as_reference().ok())?;
            current = parent
        }
    }
    None
}

/// Read a 4-element rectangle from an inherited page attribute, validating finiteness.
fn inherited_rect(doc: &Document, page_id: ObjectId, key: &[u8]) -> Option<[f64; 4]> {
    let obj = inherited(doc, page_id, key).and_then(|o| deref(doc, o))?;
    let arr = obj.as_array().ok()?;
    if arr.len() != 4 {
        return None;
    }
    let mut out = [0.0; 4];
    for (i, v) in arr.iter().enumerate() {
        let val = deref(doc, v).and_then(num)?;
        if !val.is_finite() {
            return None;
        }
        out[i] = val;
    }
    // Also validate rect finite (NaN/Inf guard for malformed PDF)
    if !out.iter().all(|x| x.is_finite()) {
        return None;
    }
    Some(out)
}

/// Page MediaBox as `[x0, y0, x1, y1]`, defaulting to US Letter.
pub(crate) fn media_box(doc: &Document, page_id: ObjectId) -> [f64; 4] {
    inherited_rect(doc, page_id, b"MediaBox").unwrap_or([0.0, 0.0, 612.0, 792.0])
}

/// `/UserUnit` inherited, default 1.0, clamped to valid range per spec.
///
/// Deliberately NOT used by the render path — see `page_visible_box`. Kept for a
/// future physical-sizing feature (true 100% zoom / print), which is the only
/// thing `/UserUnit` may legitimately affect.
#[allow(dead_code)]
pub(crate) fn user_unit(doc: &Document, page_id: ObjectId) -> f64 {
    let uu = inherited(doc, page_id, b"UserUnit")
        .and_then(|o| deref(doc, o))
        .and_then(num)
        .unwrap_or(1.0);
    uu.clamp(1.0, 75000.0)
}

/// Upper bound on either page dimension, in PDF units.
///
/// Matches the consumer's `coerceAtMost(20000f)` in `SafePdfParser.kt`. Without
/// a bound on this side the two describe DIFFERENT pages: every coordinate on
/// the wire is laid out against the unclamped box while the canvas is sized to
/// the clamped one. The contract mismatch is the defect — the value itself only
/// has to be the same value on both sides.
///
/// Deliberately NOT unified with `MAX_IMAGE_DIM` in `graphics_state.rs`, which
/// happens to be 20000 too. That one bounds an image edge in PIXELS against a
/// decode-memory budget; this one bounds a page box in PDF USER-SPACE UNITS
/// against the consumer's canvas arithmetic. Same number, unrelated policies —
/// folding them together would make tuning either silently move the other.
const MAX_PAGE_DIMENSION: f64 = 20000.0;

/// Visible page rectangle: `CropBox` intersected with `MediaBox` when present,
/// otherwise `MediaBox` (§14.11.2). Always normalized so `x0 < x1` and `y0 < y1`,
/// and never degenerate — a zero-size result falls back to US Letter.
///
/// `/UserUnit` is deliberately NOT applied here. Rendering is invariant under it:
/// the rasterizer fits the page to the viewport (`scale = viewport_width /
/// page_width`), so a factor applied to both the page size and every coordinate
/// divides straight back out. Applying it here previously desynchronized
/// `page_display_size` from `page_base_matrix` (which never applied it), leaving
/// content mis-scaled against the canvas, hit-targets from `list_annotations` /
/// `list_links` / `list_form_fields` offset by a UserUnit-scaled crop origin, and
/// newly authored annotations written at the wrong coordinates. Keeping geometry
/// in raw PDF units means there is exactly one definition of page space with
/// nothing to keep in sync. If physical sizing is ever needed, ship `/UserUnit`
/// as its own wire field feeding a DPI calculation; it must not affect layout.
pub(crate) fn page_visible_box(doc: &Document, page_id: ObjectId) -> [f64; 4] {
    let mb = normalize_rect(media_box(doc, page_id));
    let mut vb = mb;
    if let Some(cb) = inherited_rect(doc, page_id, b"CropBox").map(normalize_rect) {
        let x0 = mb[0].max(cb[0]);
        let y0 = mb[1].max(cb[1]);
        let x1 = mb[2].min(cb[2]);
        let y1 = mb[3].min(cb[3]);
        // An empty or degenerate intersection falls back to MediaBox rather than
        // producing a zero-size page.
        if x1 > x0 && y1 > y0 {
            vb = [x0, y0, x1, y1];
        }
    }
    // A degenerate box (e.g. `MediaBox [0 0 0 0]`, which is finite and so passes
    // validation) would otherwise ship a zero page dimension and divide by zero
    // in the rasterizer's fit-to-width scale.
    if vb[2] - vb[0] >= 1.0 && vb[3] - vb[1] >= 1.0 {
        // Clamped from the origin rather than falling back to US Letter: content
        // near the origin still lands where the file put it, which loses the
        // overflow rather than relocating all of the artwork.
        //
        // CROPPING, not scaling, and that is forced rather than preferred.
        // Scaling the page down to fit reads as the strictly better option —
        // nothing is lost — but the consumer CLAMPS (`coerceAtMost`, per axis)
        // and does not scale. A scaling producer against a clamping consumer
        // describes two different pages again, which is exactly the mismatch
        // this bound exists to close. Scaling only becomes available if both
        // sides change together. Reached for independently by `hunt-missing`,
        // so it is worth stating here rather than leaving to be re-derived.
        //
        // Unreachable for conforming input in any case: PDF 1.7 Appendix C.2
        // puts the architectural page-size limit at 14400 units, so this only
        // fires on malformed files, where a cropped renderable page beats the
        // permanent loading spinner the consumer used to produce.
        [
            vb[0],
            vb[1],
            vb[2].min(vb[0] + MAX_PAGE_DIMENSION),
            vb[3].min(vb[1] + MAX_PAGE_DIMENSION),
        ]
    } else {
        [0.0, 0.0, 612.0, 792.0]
    }
}

/// Normalized page rotation in {0,90,180,270}, inherited via `/Parent`.
pub(crate) fn page_rotation(doc: &Document, page_id: ObjectId) -> i64 {
    let r = inherited(doc, page_id, b"Rotate")
        .and_then(|o| deref(doc, o))
        .and_then(num)
        .unwrap_or(0.0) as i64;
    (((r % 360) + 360) % 360 / 90) * 90
}

/// Matrix mapping raw page space (visible rect origin, before rotation) into
/// displayed space: origin bottom-left, with dimensions swapped for 90/270.
/// Visible rect is CropBox intersected with MediaBox, in raw PDF units.
///
/// `/Rotate` is clockwise (ISO 32000 §7.7.3.3). With page width `w`, height `h`
/// and device y-up, the spec-correct page→display maps are:
///   90°  (x,y) -> (y,     w - x)  => [0,-1, 1,0, 0, w]
///   180° (x,y) -> (w - x, h - y)  => [-1,0, 0,-1, w, h]
///   270° (x,y) -> (h - y, x)      => [0, 1,-1,0, h, 0]
/// (The 90° and 270° arms were previously swapped, rotating such pages 180°
/// off — content upside-down and mirrored; see issue #321 pagerotationexample.)
pub(crate) fn page_base_matrix(doc: &Document, page_id: ObjectId) -> Mat {
    let vb = page_visible_box(doc, page_id);
    // Normalize to min/max to handle inverted boxes.
    let minx = vb[0].min(vb[2]);
    let miny = vb[1].min(vb[3]);
    let w = (vb[2] - vb[0]).abs();
    let h = (vb[3] - vb[1]).abs();
    let t = translate(-minx, -miny);
    let r: Mat = match page_rotation(doc, page_id) {
        90 => [0.0, -1.0, 1.0, 0.0, 0.0, w],
        180 => [-1.0, 0.0, 0.0, -1.0, w, h],
        270 => [0.0, 1.0, -1.0, 0.0, h, 0.0],
        _ => IDENTITY,
    };
    mat_mul(&t, &r)
}

/// Page dimensions as displayed (after `/Rotate`), from the same visible box as
/// `page_base_matrix` so the two can never disagree. `page_visible_box`
/// guarantees both dimensions are finite and >= 1.
///
/// This is the ONLY source of page dimensions for the rasterizer, which
/// constrains its canvas to exactly this size and clips to its own bounds — that
/// is what makes the CropBox clip (§14.11.2) implicit. A change that let content
/// draw outside the canvas would need an explicit clip to the visible box here.
pub(crate) fn page_display_size(doc: &Document, page_id: ObjectId) -> (f32, f32) {
    let vb = page_visible_box(doc, page_id);
    let w = (vb[2] - vb[0]).abs() as f32;
    let h = (vb[3] - vb[1]).abs() as f32;
    match page_rotation(doc, page_id) {
        90 | 270 => (h, w),
        _ => (w, h),
    }
}

/// Inverse with Option: None if singular (determinant < eps)
pub(crate) fn mat_inverse_checked(m: &Mat) -> Option<Mat> {
    let det = m[0] * m[3] - m[1] * m[2];
    if det.abs() < 1e-12 { return None; }
    let inv = 1.0 / det;
    let a = m[3] * inv;
    let b = -m[1] * inv;
    let c = -m[2] * inv;
    let d = m[0] * inv;
    let e = -(m[4] * a + m[5] * c);
    let f = -(m[4] * b + m[5] * d);
    Some([a, b, c, d, e, f])
}

/// Inverse returning IDENTITY fallback (previous behavior) — now wraps checked
pub(crate) fn mat_inverse(m: &Mat) -> Mat {
    mat_inverse_checked(m).unwrap_or(IDENTITY)
}

/// Inverse base matrix for a page index, mapping displayed (editor) coordinates
/// back into raw page space so stored annotations remain valid PDF.
pub(crate) fn page_base_inverse(doc: &Document, page_index: i32) -> Mat {
    match nth_page_id(doc, page_index) {
        Some(pid) => mat_inverse(&page_base_matrix(doc, pid)),
        None => IDENTITY,
    }
}

/// Convert an editor-space rect into a normalized raw-page-space rect.
pub(crate) fn page_rect(doc: &Document, page_index: i32, rect: [f64; 4]) -> [f64; 4] {
    let binv = page_base_inverse(doc, page_index);
    let (x0, y0) = transform(&binv, rect[0], rect[1]);
    let (x1, y1) = transform(&binv, rect[2], rect[3]);
    normalize_rect([x0, y0, x1, y1])
}

/// Convert editor-space flat x,y points into raw-page-space.
pub(crate) fn page_points(doc: &Document, page_index: i32, points: &[f32]) -> Vec<f32> {
    let binv = page_base_inverse(doc, page_index);
    let mut out = Vec::with_capacity(points.len());
    let mut i = 0;
    while i + 1 < points.len() {
        let (x, y) = transform(&binv, points[i] as f64, points[i + 1] as f64);
        out.push(x as f32);
        out.push(y as f32);
        i += 2;
    }
    out
}

include!("geometry_part1.rs");