//! Mesh shading rasterizers for Type 4-7 shadings (free-form/lattice Gouraud,
//! Coons and tensor-product patch meshes). Types 1-3 (function-based, axial,
//! radial) live in `images.rs::rasterize_shading`.
//!
//! Each shading is decoded into an RGBA image plus a placement CTM. Colors are
//! evaluated through the shared colorspace machinery (`crate::eval_cs_to_rgb`,
//! which understands Separation/DeviceN/Lab/ICC via `crate::PdfFunction`), so
//! mesh colors are as faithful as flat fills.

use crate::*;

/// Big-endian bit reader over a packed mesh data stream (PDF 7.10.5: all values
/// are packed high-order-bit first with no inter-record padding).
struct BitReader<'a> {
    data: &'a [u8],
    bitpos: usize,
}

impl<'a> BitReader<'a> {
    fn new(data: &'a [u8]) -> Self {
        BitReader { data, bitpos: 0 }
    }
    fn remaining_bits(&self) -> usize {
        (self.data.len() * 8).saturating_sub(self.bitpos)
    }
    /// Skip to the next byte boundary, discarding any padding bits. Free-form
    /// (Type 4) meshes pad each *vertex* and Coons/tensor (Type 6/7) meshes pad
    /// each *patch* to a whole number of bytes; without this every record after
    /// the first non-byte-aligned one decodes as garbage.
    fn align(&mut self) {
        self.bitpos = (self.bitpos + 7) & !7;
    }
    /// Read `bits` (<=64) as an unsigned integer, or `None` if exhausted.
    fn read(&mut self, bits: u32) -> Option<u64> {
        if bits == 0 {
            return Some(0);
        }
        if self.remaining_bits() < bits as usize {
            return None;
        }
        let mut v: u64 = 0;
        for _ in 0..bits {
            let byte = self.data[self.bitpos / 8];
            let bit = 7 - (self.bitpos % 8) as u32;
            v = (v << 1) | ((byte >> bit) & 1) as u64;
            self.bitpos += 1;
        }
        Some(v)
    }
}

fn max_for_bits(bits: u32) -> f64 {
    if bits >= 64 {
        u64::MAX as f64
    } else {
        ((1u64 << bits) - 1) as f64
    }
}

fn map_val(raw: u64, dmin: f64, dmax: f64, bits: u32) -> f64 {
    let m = max_for_bits(bits);
    if m == 0.0 {
        dmin
    } else {
        dmin + (raw as f64 / m) * (dmax - dmin)
    }
}

#[derive(Clone)]
struct Vertex {
    x: f64,
    y: f64,
    color: Vec<f64>,
}

/// Rasterize a Type 4-7 shading into an RGBA image + placement CTM.
/// `mesh_bytes` is the decoded shading-stream body (the mesh data source).
pub fn rasterize_shading_mesh(
    doc: &Document,
    dict: &Dictionary,
    mesh_bytes: Option<&[u8]>,
    base_ctm: &Mat,
    cs_resources: &HashMap<Vec<u8>, ObjectId>,
    size: u32,
) -> Option<(Mat, u32, u32, Vec<u8>)> {
    let shading_type = dict.get(b"ShadingType").ok().and_then(num).unwrap_or(0.0) as i64;
    if ![4, 5, 6, 7].contains(&shading_type) {
        return None;
    }
    if size == 0 || size > 1024 {
        return None;
    }

    let cs_kind = dict
        .get(b"ColorSpace")
        .ok()
        .and_then(|o| parse_cs_kind(doc, Some(o), cs_resources))
        .unwrap_or(CsKind::DeviceRGB);
    // If the shading has a /Function, colors are 1-in scalars mapped through it.
    let func = dict.get(b"Function").ok().and_then(|o| PdfFunction::parse(doc, o));
    // Otherwise each vertex carries one value per component of the colour space —
    // except for Indexed, where §8.6.6.3 makes a colour value a SINGLE palette index
    // and `eval_cs_to_rgb`'s Indexed arm reads exactly one. `cs_kind_ncomp` reports
    // the BASE arity (3 for an RGB base), so reading that many values per vertex
    // consumed 3x the bits, desynchronising the packed stream from the second vertex
    // onward — every later vertex decodes from the middle of its predecessor and the
    // mesh becomes spectacular noise, on top of every colour resolving to index ~0.
    let ncomp = if func.is_some() || matches!(cs_kind, CsKind::Indexed { .. }) {
        1
    } else {
        cs_kind_ncomp(&cs_kind) as usize
    };
    if ncomp == 0 {
        return None;
    }

    let bps_coord = dict.get(b"BitsPerCoordinate").ok().and_then(num).unwrap_or(16.0) as u32;
    let bps_comp = dict.get(b"BitsPerComponent").ok().and_then(num).unwrap_or(8.0) as u32;
    let bps_flag = dict.get(b"BitsPerFlag").ok().and_then(num).unwrap_or(8.0) as u32;
    if bps_coord == 0 || bps_coord > 32 || bps_comp == 0 || bps_comp > 16 || bps_flag > 8 {
        return None;
    }

    let decode: Vec<f64> = dict
        .get(b"Decode")
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_array().ok())
        .map(|a| a.iter().filter_map(|o| deref(doc, o).and_then(num)).collect())
        .unwrap_or_default();
    if decode.len() < 4 {
        return None;
    }
    // Coordinate bounds from Decode: [xmin xmax ymin ymax ...].
    let (xmin, xmax, ymin, ymax) = (decode[0], decode[1], decode[2], decode[3]);
    if (xmax - xmin).abs() < 1e-9 || (ymax - ymin).abs() < 1e-9 {
        return None;
    }
    // Every vertex coordinate is `dmin + (raw/max)*(dmax-dmin)`, so an infinite
    // /Decode extent makes `0 * inf` = NaN for raw == 0. NaN then defeats
    // `fill_tri`'s barycentric rejection (every `< -1e-6` test is false for NaN),
    // the colour cast saturates to 0, and the shading paints an OPAQUE BLACK
    // rectangle over its whole area — covering page content that should show
    // through. Reject the shading instead: with no usable geometry the only
    // correct answer is to paint nothing, the same conclusion the empty-mesh
    // branch below reaches.
    if ![xmin, xmax, ymin, ymax].iter().all(|v| v.is_finite()) {
        return None;
    }
    let bounds = {
        let mut b = [xmin, ymin, xmax, ymax];
        // The shading shall be painted only inside /BBox (Table 78), which is
        // expressed in the shading's own target coordinate space. Intersecting it
        // with the /Decode extent means geometry outside the box lands outside the
        // raster and is never drawn, and the raster resolution is spent only on
        // the visible region. /BBox may be given unnormalised, so order it first.
        if let Some(bb) = dict.get(b"BBox").ok().and_then(|o| read_rect(doc, o)) {
            let (bx0, bx1) = (bb[0].min(bb[2]), bb[0].max(bb[2]));
            let (by0, by1) = (bb[1].min(bb[3]), bb[1].max(bb[3]));
            b = [b[0].max(bx0), b[1].max(by0), b[2].min(bx1), b[3].min(by1)];
            if b[2] - b[0] < 1e-9 || b[3] - b[1] < 1e-9 {
                return None;
            }
        }
        b
    };

    // Prefer the stream body; fall back to a /DataSource stream/string.
    let owned_ds;
    let data: &[u8] = match mesh_bytes {
        Some(b) if !b.is_empty() => b,
        _ => {
            owned_ds = dict
                .get(b"DataSource")
                .ok()
                .and_then(|o| deref(doc, o))
                .and_then(|o| match o {
                    Object::Stream(s) => Some(stream_data_with_doc(doc, s)),
                    Object::String(bytes, _) => Some(bytes.clone()),
                    _ => None,
                })?;
            &owned_ds
        }
    };

    let color_of = |comps: &[f64]| -> u32 {
        let mapped;
        let use_comps: &[f64] = if let Some(f) = &func {
            mapped = f.eval(&[comps.first().copied().unwrap_or(0.0)]);
            &mapped
        } else {
            comps
        };
        eval_cs_to_rgb(doc, &cs_kind, use_comps, cs_resources).unwrap_or(0xFF80_8080)
    };

    // `/VerticesPerRow` is read before the raster is allocated so a degenerate value
    // still short-circuits without any allocation (§8.7.4.5.6 requires >= 2).
    let vpr = if shading_type == 5 {
        let v = dict.get(b"VerticesPerRow").ok().and_then(num).unwrap_or(0.0) as usize;
        if v < 2 {
            return None;
        }
        v
    } else {
        0
    };

    // Size the raster to the shading's aspect ratio instead of forcing a square.
    // A wide, shallow gradient band used to allocate size*size regardless (up to
    // 4 MB for a few-point-tall bar), and a page with many shadings multiplied that
    // waste through `prims`, the wire buffer and again as Kotlin bitmaps. The
    // placement CTM maps the unit square, so a non-square raster is geometrically
    // identical. Both extents are already known non-degenerate.
    let (w, h) = {
        let bw = bounds[2] - bounds[0];
        let bh = bounds[3] - bounds[1];
        let long = size as usize;
        let short = |r: f64| (((size as f64) * r).round() as usize).clamp(1, long);
        if bh <= bw { (long, short(bh / bw)) } else { (short(bw / bh), long) }
    };
    // Bound this single raster's bytes as well as its shape — see
    // MAX_SHADING_RASTER_BYTES for why per-page peak residency, not per-shading
    // size, is the binding constraint. Scaling both axes by sqrt keeps the aspect.
    let (w, h) = {
        let bytes = w.saturating_mul(h).saturating_mul(4);
        if bytes > MAX_SHADING_RASTER_BYTES {
            let s = (MAX_SHADING_RASTER_BYTES as f64 / bytes as f64).sqrt();
            (
                ((w as f64 * s).round() as usize).max(1),
                ((h as f64 * s).round() as usize).max(1),
            )
        } else {
            (w, h)
        }
    };
    let mut rgba = vec![0u8; w * h * 4];

    // Rasterize each triangle as it is decoded rather than materialising the whole
    // mesh first. The mesh types are the one place in this file where an allocation
    // is sized purely from file values AND amplified: MAX_SHADING_PATCHES is 8000 and
    // each Coons patch tessellates to 2*8*8 = 128 triangles, so 8000 patches * 29
    // bytes = 232 KB of stream (a couple of KB once Flate-compressed) expanded into a
    // MAX_SHADING_TRIANGLES-long `Vec<(Vertex, Vertex, Vertex)>` — ~120 bytes inline
    // plus three heap `Vec<f64>` colour tuples per element, so hundreds of MB and
    // millions of allocations. A Rust OOM is an uncatchable process abort, so this
    // cannot be recovered from downstream. Streaming holds one patch at a time and
    // paints in exactly the same order, so the raster is byte-identical.
    let mut painted = 0usize;
    {
        let mut emit = |v0: &Vertex, v1: &Vertex, v2: &Vertex| {
            let c0 = color_of(&v0.color);
            let c1 = color_of(&v1.color);
            let c2 = color_of(&v2.color);
            fill_tri(&mut rgba, w, h, &bounds, (v0, v1, v2), (c0, c1, c2));
            painted += 1;
        };
        match shading_type {
            4 => parse_type4(data, bps_flag, bps_coord, bps_comp, ncomp, &decode, &mut emit),
            5 => parse_type5(data, vpr, bps_coord, bps_comp, ncomp, &decode, &mut emit),
            6 | 7 => parse_type6_7(
                data, shading_type, (bps_flag, bps_coord, bps_comp), ncomp, &decode, &mut emit,
            ),
            _ => {}
        }
    }

    if painted == 0 {
        // Nothing parseable. Do NOT fall back to flooding the area with
        // /Background: per Table 78 /Background fills only the portions that lie
        // OUTSIDE the shading's own extent and "shall be ignored by the sh
        // operator", so painting it over the whole Decode box turned a failed
        // mesh into an opaque rectangle covering the entire clip — hiding page
        // content. With no triangles we cannot know the shading's extent, so the
        // only correct answer is to paint nothing at all.
        return None;
    }

    let ctm = placement_ctm(&bounds, base_ctm);
    Some((ctm, w as u32, h as u32, rgba))
}

fn placement_ctm(bounds: &[f64; 4], base_ctm: &Mat) -> Mat {
    let bw = bounds[2] - bounds[0];
    let bh = bounds[3] - bounds[1];
    let shading_mat: Mat = [bw, 0.0, 0.0, bh, bounds[0], bounds[1]];
    mat_mul(&shading_mat, base_ctm)
}

/// Read one vertex (x, y, color components) from the bit stream.
fn read_vertex(
    br: &mut BitReader,
    bps_coord: u32,
    bps_comp: u32,
    ncomp: usize,
    decode: &[f64],
) -> Option<Vertex> {
    let rx = br.read(bps_coord)?;
    let ry = br.read(bps_coord)?;
    let x = map_val(rx, decode[0], decode[1], bps_coord);
    let y = map_val(ry, decode[2], decode[3], bps_coord);
    let mut color = Vec::with_capacity(ncomp);
    for c in 0..ncomp {
        let cmin = decode.get(4 + c * 2).copied().unwrap_or(0.0);
        let cmax = decode.get(4 + c * 2 + 1).copied().unwrap_or(1.0);
        let raw = br.read(bps_comp)?;
        color.push(map_val(raw, cmin, cmax, bps_comp));
    }
    Some(Vertex { x, y, color })
}

/// A sink for decoded mesh triangles. Triangles are handed to the caller one at a
/// time instead of being collected, so peak memory stays proportional to a single
/// patch rather than to `MAX_SHADING_TRIANGLES`.
type Emit<'a> = &'a mut dyn FnMut(&Vertex, &Vertex, &Vertex);

/// Type 4: free-form Gouraud-shaded triangle mesh (flag-driven strips/fans).
fn parse_type4(
    data: &[u8],
    bps_flag: u32,
    bps_coord: u32,
    bps_comp: u32,
    ncomp: usize,
    decode: &[f64],
    emit: Emit,
) {
    let mut br = BitReader::new(data);
    let mut emitted = 0usize;
    // `last` holds the most recently completed triangle so flag 1/2 continuation
    // vertices can form strips/fans; `pending` accumulates the three flag-0
    // vertices that begin a new independent triangle (PDF 8.7.4.5.5).
    let mut last: Option<(Vertex, Vertex, Vertex)> = None;
    let mut pending: Vec<Vertex> = Vec::new();
    let mut guard = 0usize;
    while br.remaining_bits() >= (bps_flag + 2 * bps_coord + ncomp as u32 * bps_comp) as usize {
        guard += 1;
        if guard > 2_000_000 || emitted >= MAX_SHADING_TRIANGLES { break; }
        let flag = br.read(bps_flag).unwrap_or(0);
        let v = match read_vertex(&mut br, bps_coord, bps_comp, ncomp, decode) { Some(v) => v, None => break };
        // Each vertex's data occupies a whole number of bytes; trailing padding
        // bits in the last byte are ignored (ISO 32000-1 8.7.4.5.5).
        br.align();
        match flag {
            0 => {
                // Start (or continue accumulating) a new independent triangle: its
                // three vertices all carry flag 0.
                pending.push(v);
                if pending.len() == 3 {
                    let t = (pending[0].clone(), pending[1].clone(), pending[2].clone());
                    emit(&t.0, &t.1, &t.2);
                    emitted += 1;
                    last = Some(t);
                    pending.clear();
                }
            }
            1 => {
                // Continuation: new triangle = (vb, vc, v) of the previous triangle.
                pending.clear();
                if let Some((_a, b, c)) = last.clone() {
                    let t = (b, c, v);
                    emit(&t.0, &t.1, &t.2);
                    emitted += 1;
                    last = Some(t);
                }
            }
            2 => {
                // Continuation: new triangle = (va, vc, v) of the previous triangle.
                pending.clear();
                if let Some((a, _b, c)) = last.clone() {
                    let t = (a, c, v);
                    emit(&t.0, &t.1, &t.2);
                    emitted += 1;
                    last = Some(t);
                }
            }
            _ => break,
        }
    }
}

/// Type 5: lattice-form Gouraud mesh (row-major, `VerticesPerRow`).
fn parse_type5(
    data: &[u8],
    vpr: usize,
    bps_coord: u32,
    bps_comp: u32,
    ncomp: usize,
    decode: &[f64],
    emit: Emit,
) {
    let mut br = BitReader::new(data);
    // Only two rows are ever live at once, so the whole lattice never has to be
    // resident even when the stream declares millions of vertices.
    let read_row = |br: &mut BitReader| -> Option<Vec<Vertex>> {
        let mut row = Vec::with_capacity(vpr.min(4096));
        for _ in 0..vpr {
            row.push(read_vertex(br, bps_coord, bps_comp, ncomp, decode)?);
        }
        Some(row)
    };
    let mut prev = match read_row(&mut br) {
        Some(r) => r,
        None => return,
    };
    let mut emitted = 0usize;
    while let Some(cur) = read_row(&mut br) {
        for c in 0..vpr - 1 {
            if emitted >= MAX_SHADING_TRIANGLES {
                return;
            }
            emit(&prev[c], &prev[c + 1], &cur[c]);
            emit(&cur[c], &prev[c + 1], &cur[c + 1]);
            emitted += 2;
        }
        prev = cur;
    }
}

fn bezier(p: [(f64, f64); 4], t: f64) -> (f64, f64) {
    let mt = 1.0 - t;
    let a = mt * mt * mt;
    let b = 3.0 * mt * mt * t;
    let c = 3.0 * mt * t * t;
    let d = t * t * t;
    (
        a * p[0].0 + b * p[1].0 + c * p[2].0 + d * p[3].0,
        a * p[0].1 + b * p[1].1 + c * p[2].1 + d * p[3].1,
    )
}

include!("shading_part1.rs");
include!("shading_part2.rs");