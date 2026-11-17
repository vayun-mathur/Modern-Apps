use crate::*;

const WIRE_MAGIC: u32 = 0x50444657; // 'PDFW'
/// The version this serializer EMITS. V10 carries the per-image blend mode (V9 added
/// per-image alpha).
///
/// KEEP IN SYNC WITH `SafePdfParser.WIRE_VERSION` in
/// `pdf/src/main/java/com/vayunmathur/pdf/platform/SafePdfParser.kt`. That constant is the
/// HIGHEST version the parser understands, so it may legitimately run AHEAD of this one
/// (it gates the newer fields off) but must never fall behind it: the parser does not
/// reject a higher version, it forward-compat parses with those gates closed and so reads
/// too few bytes per primitive, desyncing the rest of the buffer.
/// `wire_version_is_not_ahead_of_the_kotlin_parser` asserts that by reading the Kotlin
/// file, and `wire_version_matches_the_image_payload_layout` pins this constant to the
/// fields the Image arm actually writes.
///
/// V11 means one thing only: a `u8 interpolate` in the Image arm, which the parser reads
/// behind its `isV11` gate. That gate is a `>=`, so the hazard is ANY bump above 10, not
/// just a bump to exactly 11: declaring 12 for some unrelated new field switches the
/// interpolate gate on too. Without the byte the parser eats the first byte of the image's
/// `u32 len` as interpolate and desyncs every primitive from there on, so the bump and the
/// field must land in the same change — and a jump straight from 10 to 12 must write BOTH
/// the interpolate byte and whatever v12 adds.
/// `images::image_should_interpolate` already computes the value, but `Prim::Image` does
/// not carry it yet, so nothing serializes it and the parser's pre-v11 default (smooth)
/// applies.
const WIRE_VERSION: u32 = 10;
#[allow(dead_code)]
const WIRE_VERSION_V2: u32 = 2;
#[allow(dead_code)]
const WIRE_VERSION_V7: u32 = 7;
#[allow(dead_code)]
const WIRE_VERSION_V8: u32 = 8;
#[allow(dead_code)]
const WIRE_VERSION_V9: u32 = 9;
const TAG_TEXT: u8 = 1;
const TAG_FILL: u8 = 2;
const TAG_STROKE: u8 = 3;
const TAG_IMAGE: u8 = 4;
const TAG_CLIP_PUSH: u8 = 5;
const TAG_CLIP_POP: u8 = 6;
const TAG_GROUP_PUSH: u8 = 7;
const TAG_GROUP_POP: u8 = 8;
const TAG_TEXT_CLIP_APPLY: u8 = 9;
const TAG_SMASK_PUSH: u8 = 10;
const TAG_SMASK_CONTENT: u8 = 11;
const TAG_SMASK_POP: u8 = 12;
/// Added alongside v11 but NOT version-gated: the /TR transfer function of the immediately
/// preceding SoftMaskPush, as 256 u8 samples of the mask value. Kotlin handles this tag
/// regardless of the declared
/// WIRE_VERSION, so emitting it needs no version bump and a stream without it is unaffected.
/// Emit ONLY for a non-identity /TR — absent means identity, which is cheaper and identical.
const TAG_SMASK_TRANSFER: u8 = 13;
/// Sample count of a serialized /TR LUT.
pub(crate) const TRANSFER_LUT_SIZE: usize = 256;
/// Tiling pattern as one cell bitmap plus a lattice (§8.7.3.3), rendered as a
/// `BitmapShader` with `TileMode.REPEAT`. Additive like tag 13: not emitting it is a
/// no-op, so it needs no WIRE_VERSION coupling.
///
/// Layout: 6xf32 ctm, u32 w, u32 h, f32 xstep, f32 ystep, i32 i0, i32 j0, u32 nx, u32 ny,
/// f32 alpha, u8 blend, u32 byte length, then `w*h*4` RGBA bytes.
///
/// The bitmap's dimensions ARE the repeat period, so `w`/`h` correspond to xstep/ystep and
/// NOT to the pattern /BBox — `xstep`/`ystep` travel alongside purely so the decoder can
/// assert that. Rust only emits this for non-overlapping patterns, because a periodic
/// repeat cannot express §8.7.3.1 overlap; see `images::rasterize_pattern_cell`.
const TAG_IMAGE_TILED: u8 = 14;

const PATHOP_MOVE: u8 = 0;
const PATHOP_LINE: u8 = 1;
const PATHOP_CUBIC: u8 = 2;
const PATHOP_CLOSE: u8 = 3;

/// Maximum byte length for a single text primitive (u16). Truncation must respect UTF-8 char boundaries
/// to avoid emitting invalid UTF-8 (audit #8). We use floor_char_boundary.
const MAX_TEXT_BYTES: usize = u16::MAX as usize;
/// Hard cap for image data length in wire format (u32). Crafted PDF claiming huge Dimensions could
/// otherwise truncate via `as u32` cast and cause Kotlin OOB read. Use try_from with checked truncation.
const MAX_IMAGE_DATA: u64 = u32::MAX as u64;

/// Safe truncation of a UTF-8 string to at most `max_bytes` bytes, cutting at a char boundary.
/// Returns the truncated &str slice.
fn truncate_str_safe(s: &str, max_bytes: usize) -> &str {
    if s.len() <= max_bytes {
        s
    } else {
        // floor_char_boundary is stable since 1.77 and available in 1.97 toolchain.
        let idx = s.floor_char_boundary(max_bytes);
        &s[..idx]
    }
}

/// Serialize a page into a compact little-endian buffer v10:
///
/// ```text
/// header: u32 MAGIC=0x50444657, u32 VERSION=10, f32 pageWidth, f32 pageHeight, u32 primitiveCount
/// per primitive: u8 tag, then payload
///   1 Text:   f32 x, f32 y, f32 size, u32 argb, u16 len, [utf8], u8 hasStroke, u32 strokeArgb, f32 strokeWidth, u8 renderMode (v4), u8 blend (v5), f32 advance (v7), u8 fontFlags (v8: bit0 bold bit1 italic, bits2-3 family 0=sans 1=serif 2=mono, bit4 outline-drawn), f32 hScale (v8)
///   2 Fill:   u32 argb, u8 evenOdd, u16 nContours, [u16 nPts, [f32 x,y]...]... (v6), u8 blend (v5)
///   3 Stroke: u32 argb, f32 width, u8 nDash, [f32 dash]..., f32 phase, u8 cap, u8 join, f32 miter, u16 nPts, [f32 x, f32 y]..., u8 blend (v5)
///   4 Image:  6×f32 ctm, u32 w, u32 h, u8 format, f32 alpha (v9), u8 blend (v10), u8 interpolate (v11, NOT emitted while VERSION is 10), u32 len, [bytes] (format 0=RGBA8888, 1=JPEG)
///   5 ClipPush: u8 evenOdd, u16 nPts, [f32 x,y]..., u16 nPathOps, [u8 kind, coords]...  (path-ops section is v4)
///              path-op kinds: 0 Move(2f32) 1 Line(2f32) 2 Cubic(6f32) 3 Close(0)
///
/// ROW ORDER CONTRACT, for every raster payload (tag 4 Image and tag 14 ImageTiled):
/// **row 0 is the TOP of the unit square, i.e. v = 1.** That is PDF 32000-1 8.9.5.2 for image
/// XObjects ("the first sample of the first row" is at the upper-left), and the Kotlin decoder
/// implements exactly that: it pairs bitmap pixel (0,0) with `ctm` applied to (u=0, v=1).
///
/// A SYNTHETIC raster must be written top-down too. A rasterizer whose pixel loop maps row 0 to
/// the LOW y of its bbox — `fy = bbox[1] + (y+0.5)/h * (bbox[3]-bbox[1])` — emits bottom-up
/// rows, and paired with a positive-`d` placement matrix like `[bw, 0, 0, bh, bbox[0], bbox[1]]`
/// that renders VERTICALLY FLIPPED. Fix it in the producer: write rows in reverse, or negate `d`
/// and offset `f` to `bbox[3]`. Do NOT change the decoder — its convention is correct for real
/// images, which are the common case, so "fixing" it there would flip those instead.
///   6 ClipPop: empty
///   7 GroupPush: u8 isolated, u8 knockout, f32 alpha, u8 blend
///   8 GroupPop: empty
///   9 TextClipApply: empty (v4)
///   10 SoftMaskPush: u8 maskType (0 alpha, 1 luminosity) (v5)
///   11 SoftMaskContent: empty (v5)
///   12 SoftMaskPop: empty (v5)
///   13 SoftMaskTransfer: 256×u8 LUT over the mask value (v11) — the /TR of the preceding
///      SoftMaskPush, `lut[i] = round(255 * clamp(TR(i/255), 0, 1))`. Emitted only for a
///      non-identity /TR. Tag-gated rather than version-gated, so it cannot desync a decoder
///      that predates it.
/// v1 legacy (no magic), v2..v9 remain backward compatible for cached pages (Kotlin should handle older versions).
///
/// The consumer is `SafePdfParser.kt`, which must read exactly these fields in exactly this
/// order. Its `MAX_PRIMITIVES` is only a backstop against a corrupt count field and sits well
/// above the real ceiling: `MAX_PRIMITIVES` here IS enforced at every content-emitting push,
/// and `MAX_CONTENT_OPS` truncates the operator stream before that even comes into play.
/// ```
pub fn serialize(page: &PageData) -> Vec<u8> {
    let mut buf = Vec::new();
    buf.extend_from_slice(&WIRE_MAGIC.to_le_bytes());
    buf.extend_from_slice(&WIRE_VERSION.to_le_bytes());
    buf.extend_from_slice(&page.width.to_le_bytes());
    buf.extend_from_slice(&page.height.to_le_bytes());
    // Primitive count truncated to u32 (page prims will never exceed u32::MAX in practice, but guard anyway)
    let prim_count = u32::try_from(page.prims.len()).unwrap_or(u32::MAX);
    buf.extend_from_slice(&prim_count.to_le_bytes());
    for prim in &page.prims {
        match prim {
            Prim::Text { x, y, size, argb, text, stroke_argb, stroke_width, advance, render_mode, blend, is_bold, is_italic, font_family, outline, h_scale } => {
                buf.push(TAG_TEXT);
                buf.extend_from_slice(&x.to_le_bytes());
                buf.extend_from_slice(&y.to_le_bytes());
                buf.extend_from_slice(&size.to_le_bytes());
                buf.extend_from_slice(&argb.to_le_bytes());
                // FIX #8: truncate at char boundary to avoid mid-codepoint cut -> invalid UTF-8 on Kotlin side
                let safe_text = truncate_str_safe(text, MAX_TEXT_BYTES);
                let bytes = safe_text.as_bytes();
                // bytes.len() is guaranteed <= u16::MAX and valid UTF-8 because truncated at char boundary
                let len = u16::try_from(bytes.len()).unwrap_or(u16::MAX);
                buf.extend_from_slice(&len.to_le_bytes());
                buf.extend_from_slice(bytes);
                if let (Some(sa), Some(sw)) = (stroke_argb, stroke_width) {
                    buf.push(1);
                    buf.extend_from_slice(&sa.to_le_bytes());
                    buf.extend_from_slice(&sw.to_le_bytes());
                } else {
                    buf.push(0);
                    buf.extend_from_slice(&0u32.to_le_bytes());
                    buf.extend_from_slice(&0f32.to_le_bytes());
                }
                buf.push(*render_mode); // v4
                buf.push(*blend as u8); // v5
                buf.extend_from_slice(&advance.to_le_bytes()); // v7: device-space glyph advance
                let mut font_flags = 0u8;
                if *is_bold { font_flags |= 1; }
                if *is_italic { font_flags |= 2; }
                // v8 bits 2-3 carry the generic family (0 sans, 1 serif, 2 mono);
                // bit 4 marks a glyph already drawn as outline fills (don't paint).
                font_flags |= (*font_family & 0x3) << 2;
                if *outline { font_flags |= 1 << 4; }
                buf.push(font_flags); // v8: bold/italic + family + outline
                buf.extend_from_slice(&h_scale.to_le_bytes()); // v8
            }
            Prim::Fill { argb, even_odd, contours, blend } => {
                buf.push(TAG_FILL);
                buf.extend_from_slice(&argb.to_le_bytes());
                buf.push(if *even_odd { 1 } else { 0 });
                // FIX #8: contours truncation silent but now capped with explicit u16::try_from; data loss is documented.
                // We keep min but using try_from to avoid usize->u16 overflow.
                let nc = contours.len().min(u16::MAX as usize);
                let nc_u16 = u16::try_from(nc).unwrap_or(u16::MAX);
                buf.extend_from_slice(&nc_u16.to_le_bytes()); // v6
                for c in &contours[..nc] {
                    write_points(&mut buf, c);
                }
                buf.push(*blend as u8); // v5
            }
            Prim::Stroke { argb, width, dash, dash_phase, cap, join, miter, pts, blend } => {
                buf.push(TAG_STROKE);
                buf.extend_from_slice(&argb.to_le_bytes());
                buf.extend_from_slice(&width.to_le_bytes());
                // Dash len capped to u8::MAX (spec limit, silent truncation documented; alternative would be split)
                let n = dash.len().min(u8::MAX as usize);
                let n_u8 = u8::try_from(n).unwrap_or(u8::MAX);
                buf.push(n_u8);
                for d in &dash[..n] {
                    buf.extend_from_slice(&d.to_le_bytes());
                }
                buf.extend_from_slice(&dash_phase.to_le_bytes());
                buf.push(*cap);
                buf.push(*join);
                buf.extend_from_slice(&miter.to_le_bytes());
                write_points(&mut buf, pts);
                buf.push(*blend as u8); // v5
            }
            Prim::Image { ctm, w, h, format, data, alpha, blend } => {
                buf.push(TAG_IMAGE);
                for v in ctm {
                    buf.extend_from_slice(&(*v as f32).to_le_bytes());
                }
                buf.extend_from_slice(&w.to_le_bytes());
                buf.extend_from_slice(&h.to_le_bytes());
                buf.push(*format);
                // FIX #10: previously `alpha: _` was ignored, causing opaque rendering for transparent images.
                // V9 now serializes per-image alpha (from SMask or alpha_fill).
                buf.extend_from_slice(&alpha.to_le_bytes()); // v9
                buf.push(*blend as u8); // v10: image blend mode
                // FIX #9: data.len() as u32 truncates >4GB crafted image -> Kotlin OOB read.
                // Use try_from and cap data to u32::MAX, ensuring length field matches actual bytes written.
                let (len_u32, data_slice) = match u32::try_from(data.len()) {
                    Ok(l) => (l, data.as_slice()),
                    Err(_) => {
                        // Data larger than 4GB (crafted PDF) – truncate to u32::MAX to avoid OOB, log via debug.
                        // This should never happen for legitimate PDFs (max image ~ hundreds MB).
                        (u32::MAX, &data[..(u32::MAX as usize).min(data.len())])
                    }
                };
                // Extra guard: ensure len doesn't exceed MAX_IMAGE_DATA (should be same as u32::MAX but explicit)
                let final_len = (len_u32 as u64).min(MAX_IMAGE_DATA) as u32;
                let final_slice = if (final_len as usize) < data_slice.len() {
                    &data_slice[..final_len as usize]
                } else {
                    data_slice
                };
                buf.extend_from_slice(&final_len.to_le_bytes());
                buf.extend_from_slice(final_slice);
            }
            Prim::ImageTiled { ctm, w, h, data, xstep, ystep, i0, j0, nx, ny, alpha, blend } => {
                buf.push(TAG_IMAGE_TILED);
                for v in ctm {
                    buf.extend_from_slice(&(*v as f32).to_le_bytes());
                }
                buf.extend_from_slice(&w.to_le_bytes());
                buf.extend_from_slice(&h.to_le_bytes());
                buf.extend_from_slice(&xstep.to_le_bytes());
                buf.extend_from_slice(&ystep.to_le_bytes());
                buf.extend_from_slice(&i0.to_le_bytes());
                buf.extend_from_slice(&j0.to_le_bytes());
                buf.extend_from_slice(&nx.to_le_bytes());
                buf.extend_from_slice(&ny.to_le_bytes());
                buf.extend_from_slice(&alpha.to_le_bytes());
                buf.push(*blend as u8);
                // Length-prefixed like TAG_IMAGE, and clamped the same way so the field
                // can never disagree with the bytes actually written.
                let (len_u32, data_slice) = match u32::try_from(data.len()) {
                    Ok(n) => (n, &data[..]),
                    Err(_) => (u32::MAX, &data[..u32::MAX as usize]),
                };
                buf.extend_from_slice(&len_u32.to_le_bytes());
                buf.extend_from_slice(data_slice);
            }
            Prim::ClipPush { even_odd, pts, path_ops } => {
                buf.push(TAG_CLIP_PUSH);
                buf.push(if *even_odd { 1 } else { 0 });
                write_points(&mut buf, pts);
                write_path_ops(&mut buf, path_ops.as_deref()); // v4
            }
            Prim::ClipPop => {
                buf.push(TAG_CLIP_POP);
            }
            Prim::TextClipApply => {
                buf.push(TAG_TEXT_CLIP_APPLY);
            }
            Prim::GroupPush { isolated, knockout, alpha, blend } => {
                buf.push(TAG_GROUP_PUSH);
                buf.push(if *isolated {1} else {0});
                buf.push(if *knockout {1} else {0});
                buf.extend_from_slice(&alpha.to_le_bytes());
                buf.push(*blend as u8);
            }
            Prim::GroupPop => {
                buf.push(TAG_GROUP_POP);
            }
            Prim::SoftMaskPush { mask_type } => {
                buf.push(TAG_SMASK_PUSH);
                buf.push(*mask_type);
            }
            // Exactly TRANSFER_LUT_SIZE raw bytes, no length prefix, per the agreed
            // format. `Prim` guarantees the array length, so the assert is a wire-format
            // invariant rather than a runtime check.
            Prim::SoftMaskTransfer(lut) => {
                debug_assert_eq!(lut.len(), TRANSFER_LUT_SIZE);
                buf.push(TAG_SMASK_TRANSFER);
                buf.extend_from_slice(&lut[..]);
            }
            Prim::SoftMaskContent => {
                buf.push(TAG_SMASK_CONTENT);
            }
            Prim::SoftMaskPop => {
                buf.push(TAG_SMASK_POP);
            }
        }
    }
    buf
}

/// Serialize an optional bezier-retentive clip path (v4): u16 count then
/// tagged Move/Line/Cubic/Close records. Empty count when absent.
fn write_path_ops(buf: &mut Vec<u8>, ops: Option<&[PathOp]>) {
    let ops = match ops {
        Some(o) if !o.is_empty() => o,
        _ => {
            buf.extend_from_slice(&0u16.to_le_bytes());
            return;
        }
    };
    let n = ops.len().min(u16::MAX as usize);
    let n_u16 = u16::try_from(n).unwrap_or(u16::MAX);
    buf.extend_from_slice(&n_u16.to_le_bytes());
    for op in &ops[..n] {
        match op {
            PathOp::Move(x, y) => {
                buf.push(PATHOP_MOVE);
                buf.extend_from_slice(&x.to_le_bytes());
                buf.extend_from_slice(&y.to_le_bytes());
            }
            PathOp::Line(x, y) => {
                buf.push(PATHOP_LINE);
                buf.extend_from_slice(&x.to_le_bytes());
                buf.extend_from_slice(&y.to_le_bytes());
            }
            PathOp::Cubic(x1, y1, x2, y2, x3, y3) => {
                buf.push(PATHOP_CUBIC);
                for v in [x1, y1, x2, y2, x3, y3] {
                    buf.extend_from_slice(&v.to_le_bytes());
                }
            }
            PathOp::Close => {
                buf.push(PATHOP_CLOSE);
            }
        }
    }
}

fn write_points(buf: &mut Vec<u8>, pts: &[(f32, f32)]) {
    let n = pts.len().min(u16::MAX as usize);
    let n_u16 = u16::try_from(n).unwrap_or(u16::MAX);
    buf.extend_from_slice(&n_u16.to_le_bytes());
    for &(x, y) in &pts[..n] {
        buf.extend_from_slice(&x.to_le_bytes());
        buf.extend_from_slice(&y.to_le_bytes());
    }
}

include!("wire_part1.rs");