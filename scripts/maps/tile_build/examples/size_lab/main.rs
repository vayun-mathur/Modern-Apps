//! `size_lab` — measured size experiments on a real `.mamaps` (planet-size study).
//!
//! Usage:
//!   size_lab bodies IN.mamaps [PER_ZOOM] [OUT_DIR]
//!   size_lab sidecars IN.mamaps [WINDOWS] [OUT_DIR]
//!
//! `bodies`: stratified sample of unique tile bodies per zoom (stride over the
//! deduplicated data section), every body re-encoded under each codec and each
//! data transform; per-zoom totals are scaled with a ratio estimator against
//! the zoom's exact stored total, so the planet projection is
//! `sum_z exact_stored_z * (variant_sample_z / stored_sample_z)`.
//! Dictionaries are trained on a disjoint sample (offset by half a stride).
//!
//! `sidecars`: per sidecar section, WINDOWS evenly spaced 1 MiB windows, each
//! cut into independent 64 KiB blocks (what a random-access mmap reader would
//! inflate), compressed with zstd 19 (C reference and the pure-Rust zstd-rs
//! the build would ship). ZBLK sections are decoded and recompressed.
//!
//! Experiment tooling only (examples/, dev-dependencies): nothing here is in a
//! shipping binary.
use std::collections::BTreeMap;
use std::fs::File;
use std::io::Write as _;
use std::sync::Mutex;
use std::time::Instant;

use rayon::prelude::*;
use tile_build::mamaps::body::{Body, Heightmap, Layer, Part, Scratch, GEOM_POINT, GEOM_POLYGON};
use tile_build::mamaps::dict::Dictionary;
use tile_build::mamaps::{header, index};
use tilecodec::pmtiles::tile_zxy;

const H: usize = tile_build::mamaps::body::BODY_HEADER_LEN;

#[derive(Clone, Copy)]
struct Ent {
    z: u8,
    off: u64,
    len: u32,
}

fn zstd_c(data: &[u8], level: i32) -> usize {
    zstd::bulk::compress(data, level).expect("zstd").len()
}

fn zstd_c_dict(c: &mut zstd::bulk::Compressor<'_>, data: &[u8]) -> Vec<u8> {
    c.compress(data).expect("zstd dict")
}

fn zstd_rs(data: &[u8], level: i32) -> Vec<u8> {
    let cfg = zstd_rs::CompressionConfig { level, ..zstd_rs::CompressionConfig::DEFAULT };
    let mut enc = zstd_rs::Compressor::new(cfg).expect("cfg");
    let mut out = Vec::new();
    enc.compress(data, None, &mut out).expect("zstd-rs");
    out
}

fn zstd_rs_dict(data: &[u8], level: i32, d: &zstd_rs::EncoderDictionary) -> Vec<u8> {
    let cfg = zstd_rs::CompressionConfig { level, ..zstd_rs::CompressionConfig::DEFAULT };
    let mut enc = zstd_rs::Compressor::new(cfg).expect("cfg");
    let mut out = Vec::new();
    enc.compress(data, Some(d), &mut out).expect("zstd-rs dict");
    out
}

fn deflate9(data: &[u8]) -> usize {
    miniz_oxide::deflate::compress_to_vec(data, 9).len()
}

fn band(z: u8) -> usize {
    match z {
        0..=9 => 0,
        10..=11 => 1,
        12 => 2,
        13 => 3,
        _ => 4,
    }
}
const BANDS: [&str; 5] = ["z0-9", "z10-11", "z12", "z13", "z14"];

// ---------------------------------------------------------------- transforms

fn ser(body: &Body) -> Vec<u8> {
    let mut s = Scratch::default();
    match tile_build::mamaps::body::serialize_into_v10(body, &mut s) {
        Ok(b) => b.to_vec(),
        Err(e) if e.0.contains("marking convention") => {
            // A transform dropped every feature with a non-default carriageway:
            // the sparse table is then empty and the convention must go too.
            let mut b = body.clone();
            b.convention = None;
            b.carriageways.clear();
            let mut s = Scratch::default();
            tile_build::mamaps::body::serialize_into_v10(&b, &mut s).expect("serialize").to_vec()
        }
        Err(e) => panic!("serialize: {e:?}"),
    }
}

/// Rebuild a layer keeping only features `keep` says yes to, with per-part
/// coordinate rewriting through `edit` (None = drop the part).
fn rebuild_layer(
    layer: &Layer,
    mut keep: impl FnMut(usize) -> bool,
    mut edit: impl FnMut(u8, &[(i16, i16)], u16) -> Option<Vec<(i16, i16)>>,
) -> (Layer, Vec<usize>) {
    let mut nl = Layer::new(layer.layer_id);
    let mut kept = Vec::new();
    for (i, f) in layer.features.iter().enumerate() {
        if !keep(i) {
            continue;
        }
        let start = nl.parts.len() as u32;
        let from = f.parts_offset as usize;
        let mut np = 0u32;
        for p in &layer.parts[from..from + f.part_count as usize] {
            let pts = layer.points(p);
            let Some(out) = edit(f.geom_type, pts, p.winding) else { continue };
            nl.parts.push(Part { coord_start: nl.coords.len() as u32, point_count: out.len() as u32, winding: p.winding });
            nl.coords.extend_from_slice(&out);
            np += 1;
        }
        if np == 0 {
            nl.parts.truncate(start as usize);
            continue;
        }
        let mut nf = *f;
        nf.parts_offset = start;
        nf.part_count = np;
        nl.features.push(nf);
        kept.push(i);
    }
    (nl, kept)
}

/// Filter every dense-parallel side table of `layer_id` down to `kept`.
fn filter_tables(body: &mut Body, layer_id: u8, kept: &[usize]) {
    fn f<T: Clone>(v: &mut Vec<(u8, Vec<T>)>, id: u8, kept: &[usize]) {
        for (lid, t) in v.iter_mut() {
            if *lid == id {
                *t = kept.iter().map(|&i| t[i].clone()).collect();
            }
        }
        v.retain(|(_, t)| !t.is_empty());
    }
    f(&mut body.ids, layer_id, kept);
    f(&mut body.turn_lanes, layer_id, kept);
    f(&mut body.buildings, layer_id, kept);
    f(&mut body.carriageways, layer_id, kept);
    f(&mut body.region_links, layer_id, kept);
    if body.carriageways.is_empty() {
        body.convention = None;
    }
}

fn apply_layer(
    body: &Body,
    layer_id: u8,
    keep: impl FnMut(&Layer, usize) -> bool,
    edit: impl FnMut(u8, &[(i16, i16)], u16) -> Option<Vec<(i16, i16)>>,
) -> Body {
    let mut b = body.clone();
    let Some(pos) = b.layers.iter().position(|l| l.layer_id == layer_id) else { return b };
    let layer = b.layers[pos].clone();
    let mut keep = keep;
    let (nl, kept) = rebuild_layer(&layer, |i| keep(&layer, i), edit);
    if nl.features.is_empty() {
        b.layers.remove(pos);
    } else {
        b.layers[pos] = nl;
    }
    filter_tables(&mut b, layer_id, &kept);
    // Names no longer referenced are kept (the name table is per tile); a
    // real build would drop them, so this slightly understates savings.
    b
}

fn ring_area2(pts: &[(i16, i16)]) -> i64 {
    let mut a = 0i64;
    for i in 0..pts.len() {
        let (x0, y0) = pts[i];
        let (x1, y1) = pts[(i + 1) % pts.len()];
        a += x0 as i64 * y1 as i64 - x1 as i64 * y0 as i64;
    }
    a.abs()
}

fn line_len(pts: &[(i16, i16)]) -> f64 {
    pts.windows(2)
        .map(|w| (((w[1].0 - w[0].0) as f64).powi(2) + ((w[1].1 - w[0].1) as f64).powi(2)).sqrt())
        .sum()
}

fn dp(pts: &[(i16, i16)], tol: f64, keep: &mut [bool], a: usize, b: usize) {
    if b <= a + 1 {
        return;
    }
    let (ax, ay) = (pts[a].0 as f64, pts[a].1 as f64);
    let (bx, by) = (pts[b].0 as f64, pts[b].1 as f64);
    let (dx, dy) = (bx - ax, by - ay);
    let l2 = dx * dx + dy * dy;
    let mut best = 0.0;
    let mut bi = a;
    for (i, p) in pts.iter().enumerate().take(b).skip(a + 1) {
        let (px, py) = (p.0 as f64, p.1 as f64);
        let d = if l2 == 0.0 {
            ((px - ax).powi(2) + (py - ay).powi(2)).sqrt()
        } else {
            ((dy * px - dx * py + bx * ay - by * ax).abs()) / l2.sqrt()
        };
        if d > best {
            best = d;
            bi = i;
        }
    }
    if best > tol {
        keep[bi] = true;
        dp(pts, tol, keep, a, bi);
        dp(pts, tol, keep, bi, b);
    }
}

fn simplify(geom: u8, pts: &[(i16, i16)], tol: f64) -> Vec<(i16, i16)> {
    if geom == GEOM_POINT || pts.len() < 4 {
        return pts.to_vec();
    }
    let n = pts.len();
    let mut keep = vec![false; n];
    keep[0] = true;
    keep[n - 1] = true;
    if geom == GEOM_POLYGON {
        // Closed ring: anchor at first point and the farthest point from it.
        let far = (1..n)
            .max_by_key(|&i| {
                let dx = (pts[i].0 - pts[0].0) as i64;
                let dy = (pts[i].1 - pts[0].1) as i64;
                dx * dx + dy * dy
            })
            .unwrap_or(n - 1);
        keep[far] = true;
        dp(pts, tol, &mut keep, 0, far);
        dp(pts, tol, &mut keep, far, n - 1);
    } else {
        dp(pts, tol, &mut keep, 0, n - 1);
    }
    let out: Vec<_> = pts.iter().zip(&keep).filter(|(_, k)| **k).map(|(p, _)| *p).collect();
    let min = if geom == GEOM_POLYGON { 4 } else { 2 };
    if out.len() < min { pts.to_vec() } else { out }
}

fn snap(geom: u8, pts: &[(i16, i16)], q: i16) -> Vec<(i16, i16)> {
    if geom == GEOM_POINT {
        return pts.to_vec();
    }
    let s = |v: i16| -> i16 {
        let r = ((v as i32 + (q as i32) / 2).div_euclid(q as i32)) * q as i32;
        r.clamp(i16::MIN as i32, i16::MAX as i32) as i16
    };
    let mut out: Vec<(i16, i16)> = Vec::with_capacity(pts.len());
    for &(x, y) in pts {
        let p = (s(x), s(y));
        if out.last() != Some(&p) {
            out.push(p);
        }
    }
    let min = if geom == GEOM_POLYGON { 4 } else { 2 };
    if out.len() < min { pts.to_vec() } else { out }
}

fn hm_quant(hm: &Heightmap, step: i32) -> Heightmap {
    Heightmap {
        dim: hm.dim,
        samples: hm
            .samples
            .iter()
            .map(|&s| {
                let m = s as i32 - 32768;
                let q = ((m as f64 / step as f64).round() as i32) * step;
                (q + 32768).clamp(0, 65535) as u16
            })
            .collect(),
    }
}

/// Subsample a dim-N grid to ceil(N/2)+? keeping corners (N odd -> (N+1)/2).
fn hm_half(hm: &Heightmap) -> Heightmap {
    let d = hm.dim as usize;
    if d < 3 {
        return hm.clone();
    }
    let idx: Vec<usize> = if d % 2 == 1 { (0..d).step_by(2).collect() } else {
        let mut v: Vec<usize> = (0..d).step_by(2).collect();
        if *v.last().unwrap() != d - 1 { v.push(d - 1); }
        v
    };
    let mut samples = Vec::with_capacity(idx.len() * idx.len());
    for &r in &idx {
        for &c in &idx {
            samples.push(hm.samples[r * d + c]);
        }
    }
    Heightmap { dim: idx.len() as u16, samples }
}

/// Format-change simulation: 2D predictive (left, or up for column 0) zigzag
/// residuals stored in the same u16 slots, so only entropy changes.
fn hm_delta(hm: &Heightmap) -> Heightmap {
    let d = hm.dim as usize;
    let mut out = vec![0u16; hm.samples.len()];
    for r in 0..d {
        for c in 0..d {
            let v = hm.samples[r * d + c] as i32;
            let pred = if c > 0 {
                let l = hm.samples[r * d + c - 1] as i32;
                if r > 0 {
                    let u = hm.samples[(r - 1) * d + c] as i32;
                    let ul = hm.samples[(r - 1) * d + c - 1] as i32;
                    (l + u - ul).clamp(l.min(u), l.max(u)) // MED/LOCO-I predictor
                } else {
                    l
                }
            } else if r > 0 {
                hm.samples[(r - 1) * d] as i32
            } else {
                32768
            };
            let e = v - pred;
            out[r * d + c] = ((e << 1) ^ (e >> 31)) as u16;
        }
    }
    Heightmap { dim: hm.dim, samples: out }
}

/// Bilinear reconstruction of grid `g` (dim `dg`) at fractional position (fr, fc) in [0,1].
fn bilin(g: &[u16], dg: usize, fr: f64, fc: f64) -> f64 {
    let gr = fr * (dg - 1) as f64;
    let gc = fc * (dg - 1) as f64;
    let r0 = (gr.floor() as usize).min(dg - 1);
    let c0 = (gc.floor() as usize).min(dg - 1);
    let r1 = (r0 + 1).min(dg - 1);
    let c1 = (c0 + 1).min(dg - 1);
    let (fy, fx) = (gr - r0 as f64, gc - c0 as f64);
    let at = |r: usize, c: usize| g[r * dg + c] as f64;
    let top = at(r0, c0) * (1.0 - fx) + at(r0, c1) * fx;
    let bot = at(r1, c0) * (1.0 - fx) + at(r1, c1) * fx;
    top * (1.0 - fy) + bot * fy
}

/// (max abs, sum sq, n) error of `new` against `orig`, evaluated at every original sample.
/// Both surfaces are bilinear and the coarse grid's nodes are a subset of the fine grid's,
/// so the difference is bilinear inside each fine cell: the max over these nodes IS the max
/// over the whole continuous surface.
fn hm_err(orig: &Heightmap, new: &Heightmap) -> (f64, f64, u64) {
    let d = orig.dim as usize;
    let dn = new.dim as usize;
    let mut mx = 0.0f64;
    let mut ss = 0.0f64;
    for r in 0..d {
        for c in 0..d {
            let fr = r as f64 / (d - 1) as f64;
            let fc = c as f64 / (d - 1) as f64;
            let e = (bilin(&new.samples, dn, fr, fc) - orig.samples[r * d + c] as f64).abs();
            mx = mx.max(e);
            ss += e * e;
        }
    }
    (mx, ss, (d * d) as u64)
}

fn subsample(hm: &Heightmap, dn: usize) -> Heightmap {
    let d = hm.dim as usize;
    let mut s = Vec::with_capacity(dn * dn);
    for r in 0..dn {
        for c in 0..dn {
            s.push(hm.samples[(r * (d - 1) / (dn - 1)) * d + c * (d - 1) / (dn - 1)]);
        }
    }
    Heightmap { dim: dn as u16, samples: s }
}

/// Error-bounded adaptive grid: the coarsest aligned sub-grid (dim-1 divisible by dn-1) whose
/// bilinear reconstruction stays within tol/2 of the full grid, then elevation quantised to a
/// step of floor(tol) metres (rounding error <= tol/2). Total max error <= tol by construction.
/// No format change: dim is per tile on the wire already.
fn hm_adapt(hm: &Heightmap, tol: f64) -> Heightmap {
    let d = hm.dim as usize;
    let mut cands: Vec<usize> = (2..=d).filter(|dn| (d - 1) % (dn - 1) == 0).collect();
    cands.sort();
    let mut pick = hm.clone();
    for dn in cands {
        let sub = subsample(hm, dn);
        if hm_err(hm, &sub).0 <= tol / 2.0 + 1e-9 {
            pick = sub;
            break;
        }
    }
    let q = tol.floor().max(1.0) as i32;
    if q > 1 { hm_quant(&pick, q) } else { pick }
}

struct Ids {
    landtype: u8,
    roads: u8,
    buildings: u8,
    junction: u8,
}

/// Named transforms; each returns the transformed body (or None = unchanged).
fn transforms() -> Vec<&'static str> {
    vec![
        "identity_v10",
        "hm_drop",
        "hm_q2m",
        "hm_q4m",
        "hm_half_dim",
        "hm_delta*",
        "hm_adapt1m",
        "hm_adapt2m",
        "hm_adapt5m",
        "hm_adapt2m_delta*",
        "hm_delta_q2m*",
        "coord_q2_le13",
        "coord_q4_le12_q2_13",
        "landtype_simp2_le13",
        "landtype_simp4_le13",
        "landtype_dropsmall_le13",
        "roads_simp2_le13",
        "junction_drop",
        "buildings_drop_attrs",
        "ids_drop",
        "names_drop",
        "combo_A",
        "combo_B*",
    ]
}

fn apply(name: &str, body: &Body, z: u8, ids: &Ids) -> Body {
    let mut b = body.clone();
    match name {
        "identity_v10" => {}
        "hm_drop" => b.heightmap = None,
        "hm_q2m" => b.heightmap = b.heightmap.as_ref().map(|h| hm_quant(h, 2)),
        "hm_q4m" => b.heightmap = b.heightmap.as_ref().map(|h| hm_quant(h, 4)),
        "hm_half_dim" => b.heightmap = b.heightmap.as_ref().map(hm_half),
        "hm_delta*" => b.heightmap = b.heightmap.as_ref().map(hm_delta),
        "hm_adapt1m" => b.heightmap = b.heightmap.as_ref().map(|h| hm_adapt(h, 1.0)),
        "hm_adapt2m" => b.heightmap = b.heightmap.as_ref().map(|h| hm_adapt(h, 2.0)),
        "hm_adapt5m" => b.heightmap = b.heightmap.as_ref().map(|h| hm_adapt(h, 5.0)),
        "hm_adapt2m_delta*" => b.heightmap = b.heightmap.as_ref().map(|h| hm_delta(&hm_adapt(h, 2.0))),
        "hm_delta_q2m*" => b.heightmap = b.heightmap.as_ref().map(|h| hm_delta(&hm_quant(h, 2))),
        "coord_q2_le13" | "coord_q4_le12_q2_13" => {
            if z <= 13 {
                let q = if name == "coord_q4_le12_q2_13" && z <= 12 { 4 } else { 2 };
                for l in b.layers.clone() {
                    b = apply_layer(&b, l.layer_id, |_, _| true, |g, p, _| Some(snap(g, p, q)));
                }
            }
        }
        "landtype_simp2_le13" | "landtype_simp4_le13" => {
            if z <= 13 {
                let tol = if name.contains("simp4") { 4.0 } else { 2.0 };
                b = apply_layer(&b, ids.landtype, |_, _| true, |g, p, _| Some(simplify(g, p, tol)));
            }
        }
        "roads_simp2_le13" => {
            if z <= 13 {
                b = apply_layer(&b, ids.roads, |_, _| true, |g, p, _| Some(simplify(g, p, 2.0)));
            }
        }
        "landtype_dropsmall_le13" => {
            if z <= 13 {
                // < 2x2 px of a 512 px tile (8 units/px): polygons under 256 units^2,
                // lines under 16 units. Holes under the same area are dropped too.
                b = apply_layer(
                    &b,
                    ids.landtype,
                    |l, i| {
                        let f = &l.features[i];
                        let from = f.parts_offset as usize;
                        let parts = &l.parts[from..from + f.part_count as usize];
                        if f.geom_type == GEOM_POLYGON {
                            let outer: i64 = parts.iter().filter(|p| !p.is_hole()).map(|p| ring_area2(l.points(p))).sum();
                            outer / 2 >= 256
                        } else if f.geom_type == GEOM_POINT {
                            true
                        } else {
                            parts.iter().map(|p| line_len(l.points(p))).sum::<f64>() >= 16.0
                        }
                    },
                    |g, p, w| {
                        if g == GEOM_POLYGON && w == tile_build::mamaps::body::WINDING_HOLE && ring_area2(p) / 2 < 256 {
                            None
                        } else {
                            Some(p.to_vec())
                        }
                    },
                );
            }
        }
        "junction_drop" => {
            b = apply_layer(&b, ids.junction, |_, _| false, |_, p, _| Some(p.to_vec()));
        }
        "buildings_drop_attrs" => b.buildings.clear(),
        "ids_drop" => b.ids.clear(),
        "names_drop" => {
            b.names.clear();
            for l in &mut b.layers {
                for f in &mut l.features {
                    f.name_idx = tile_build::mamaps::body::NAME_NONE;
                }
            }
        }
        "combo_A" => {
            // No format change: hm 2 m quant + half dim at z<=12, coord q2 at z<=13,
            // landtype simp2 + drop-small at z<=13.
            b.heightmap = b.heightmap.as_ref().map(|h| {
                let q = hm_quant(h, 2);
                if z <= 12 { hm_half(&q) } else { q }
            });
            if z <= 13 {
                b = apply(
                    "landtype_dropsmall_le13",
                    &apply("landtype_simp2_le13", &b, z, ids),
                    z,
                    ids,
                );
                b = apply("coord_q2_le13", &b, z, ids);
            }
        }
        "combo_B*" => {
            b = apply("combo_A", &b, z, ids);
            b.heightmap = b.heightmap.as_ref().map(hm_delta);
        }
        _ => unreachable!("{name}"),
    }
    let _ = ids.buildings;
    b
}

// ---------------------------------------------------------------- bodies

struct Arch {
    map: memmap2::Mmap,
    hdr: header::Header,
    dict: Dictionary,
    /// Planet5 archives' trained dictionaries.
    zd: Option<tile_build::mamaps::zdict::ZDicts>,
}

fn open(path: &str) -> Arch {
    let f = File::open(path).expect("open");
    let map = unsafe { memmap2::Mmap::map(&f).expect("mmap") };
    let hdr = header::Header::parse(&map[..tilecodec::stream::OPEN_PREFIX_BYTES as usize]).expect("header");
    let dict = Dictionary::parse(&map[hdr.dict_offset as usize..hdr.dict_offset as usize + hdr.dict_len as usize]).expect("dict");
    let zd = tile_build::mamaps::read::zdicts_in(&hdr, &map).expect("dictionaries");
    Arch { map, hdr, dict, zd }
}

fn entries(a: &Arch) -> Vec<Ent> {
    let hdr = &a.hdr;
    let root = index::parse_root(&a.map[hdr.root_offset as usize..(hdr.root_offset + hdr.root_len as u64) as usize]).expect("root");
    let per: Vec<Vec<Ent>> = root
        .par_iter()
        .map(|re| {
            let raw_len = re.leaf_entry_count as usize * index::LEAF_ENTRY_LEN;
            let at = (hdr.leaf_offset + re.leaf_offset) as usize;
            let raw = if re.stored_len == 0 {
                a.map[at..at + raw_len].to_vec()
            } else {
                index::decompress_leaf(&a.map[at..at + re.stored_len as usize], raw_len).expect("leaf")
            };
            index::parse_leaf(&raw)
                .expect("leaf")
                .iter()
                .map(|e| {
                    let (z, _, _) = tile_zxy(re.base_tile_id + e.tile_id_lo as u64);
                    Ent { z, off: hdr.data_offset + re.base_data_offset + e.offset_delta as u64, len: e.length }
                })
                .collect()
        })
        .collect();
    let mut all: Vec<Ent> = per.into_iter().flatten().collect();
    all.sort_by_key(|e| (e.off, e.z));
    all.dedup_by_key(|e| e.off);
    all
}

#[derive(Default, Clone)]
struct Acc {
    n: u64,
    stored: u64,
    raw: u64,
    v: BTreeMap<String, u64>,
    t_inflate_ns: u64,
    t_zstd_ns: u64,
    t_zstd_dict_ns: u64,
    t_parse_ns: u64,
    hm_dims: BTreeMap<u16, u64>,
    identical_v10: u64,
    hm_err: BTreeMap<String, (f64, f64, u64)>,
}

impl Acc {
    fn add(&mut self, k: &str, n: usize) {
        *self.v.entry(k.to_string()).or_default() += n as u64;
    }
    fn merge(&mut self, o: &Acc) {
        self.n += o.n;
        self.stored += o.stored;
        self.raw += o.raw;
        for (k, v) in &o.v {
            *self.v.entry(k.clone()).or_default() += v;
        }
        self.t_inflate_ns += o.t_inflate_ns;
        self.t_zstd_ns += o.t_zstd_ns;
        self.t_zstd_dict_ns += o.t_zstd_dict_ns;
        self.t_parse_ns += o.t_parse_ns;
        for (k, v) in &o.hm_dims {
            *self.hm_dims.entry(*k).or_default() += v;
        }
        self.identical_v10 += o.identical_v10;
        for (k, v) in &o.hm_err {
            let e = self.hm_err.entry(k.clone()).or_insert((0.0, 0.0, 0));
            e.0 = e.0.max(v.0);
            e.1 += v.1;
            e.2 += v.2;
        }
    }
}

fn raw_of(a: &Arch, e: &Ent) -> Vec<u8> {
    let stored = &a.map[e.off as usize..e.off as usize + e.len as usize];
    tile_build::mamaps::read::decompress_with(a.hdr.compression, stored, a.zd.as_ref()).expect("inflate")
}

fn cmd_bodies(path: &str, per_zoom: usize, out_dir: &str) {
    let a = open(path);
    let t0 = Instant::now();
    let ents = entries(&a);
    eprintln!("unique bodies {} in {:.1}s", ents.len(), t0.elapsed().as_secs_f64());
    let lid = |n: &str| a.dict.layers.iter().position(|l| l == n).expect(n) as u8;
    let ids = Ids { landtype: lid("landtype"), roads: lid("roads"), buildings: lid("buildings"), junction: lid("junction") };

    // Exact per-zoom totals and the stratified samples.
    let mut by_z: BTreeMap<u8, Vec<Ent>> = BTreeMap::new();
    for e in &ents {
        by_z.entry(e.z).or_default().push(*e);
    }
    let mut exact: BTreeMap<u8, (u64, u64)> = BTreeMap::new();
    let mut sample: Vec<Ent> = Vec::new();
    let mut train: Vec<Vec<Vec<u8>>> = vec![Vec::new(); 5];
    for (z, v) in &by_z {
        exact.insert(*z, (v.len() as u64, v.iter().map(|e| e.len as u64).sum()));
        let step = (v.len() / per_zoom.max(1)).max(1);
        let mut i = 0;
        while i < v.len() {
            sample.push(v[i]);
            // Disjoint training sample: half a stride later (only when the
            // stride leaves room; tiny zooms train on what there is).
            if step >= 2 && i + step / 2 < v.len() {
                train[band(*z)].push(v[i + step / 2].off.to_le_bytes().to_vec());
            }
            i += step;
        }
    }
    eprintln!("sample {} bodies; training dictionaries...", sample.len());
    // Training bodies: raw payload past the 16-byte header (what is compressed).
    let off_to_ent: std::collections::HashMap<u64, Ent> = ents.iter().map(|e| (e.off, *e)).collect();
    let dicts: Vec<Vec<u8>> = train
        .par_iter()
        .enumerate()
        .map(|(bi, offs)| {
            let mut samples: Vec<Vec<u8>> = offs
                .iter()
                .map(|o| {
                    let e = off_to_ent[&u64::from_le_bytes(o[..8].try_into().unwrap())];
                    raw_of(&a, &e)[H..].to_vec()
                })
                .collect();
            // zdict wants < 4 GB total and a bounded count; cap at 200k samples.
            samples.truncate(200_000);
            let d = zstd::dict::from_samples(&samples, 112 * 1024).unwrap_or_default();
            eprintln!("  dict {}: {} samples -> {} B", BANDS[bi], samples.len(), d.len());
            d
        })
        .collect();
    for (i, d) in dicts.iter().enumerate() {
        std::fs::write(format!("{out_dir}/dict_{}.zdict", BANDS[i]), d).expect("write dict");
    }
    let rs_dicts: Vec<Option<zstd_rs::EncoderDictionary>> = dicts
        .iter()
        .map(|d| {
            if d.is_empty() {
                return None;
            }
            let dd = zstd_rs::Dictionary::new(d, zstd_rs::DictionaryFormat::Zstd).ok()?;
            zstd_rs::EncoderDictionary::new(&dd, 19).ok()
        })
        .collect();

    let tnames = transforms();
    let acc: Mutex<BTreeMap<u8, Acc>> = Mutex::new(BTreeMap::new());
    let done = std::sync::atomic::AtomicUsize::new(0);
    sample.par_chunks(64).for_each(|chunk| {
        let mut local: BTreeMap<u8, Acc> = BTreeMap::new();
        let mut cdicts: Vec<Option<zstd::bulk::Compressor>> = dicts
            .iter()
            .map(|d| if d.is_empty() { None } else { zstd::bulk::Compressor::with_dictionary(19, d).ok() })
            .collect();
        for e in chunk {
            // Copied first so the timing below measures decode, not a page fault on the mmap.
            let stored = a.map[e.off as usize..e.off as usize + e.len as usize].to_vec();
            let ti = Instant::now();
            let raw = tile_build::mamaps::read::decompress_with(a.hdr.compression, &stored, a.zd.as_ref()).expect("inflate");
            let t_inf = ti.elapsed().as_nanos() as u64;
            let tp = Instant::now();
            let body = Body::parse(&raw).expect("parse");
            let t_parse = tp.elapsed().as_nanos() as u64;
            let payload = &raw[H..];
            let acc = local.entry(e.z).or_default();
            acc.n += 1;
            acc.stored += e.len as u64;
            acc.raw += raw.len() as u64;
            acc.t_inflate_ns += t_inf;
            acc.t_parse_ns += t_parse;
            if let Some(h) = &body.heightmap {
                *acc.hm_dims.entry(h.dim).or_default() += 1;
            }
            acc.add("codec:deflate9", H + deflate9(payload));
            acc.add("codec:zstdC_19", H + zstd_c(payload, 19));
            let rs19 = zstd_rs(payload, 19);
            acc.add("codec:zstdRS_19", H + rs19.len());
            // Decode timing of the shipping decoder (ruzstd) on the zstd-rs frame.
            let td = Instant::now();
            let mut out = vec![0u8; payload.len()];
            let mut dec = ruzstd::decoding::FrameDecoder::new();
            let n = dec.decode_all(&rs19, &mut out).expect("ruzstd");
            acc.t_zstd_ns += td.elapsed().as_nanos() as u64;
            assert_eq!(&out[..n], payload, "ruzstd round trip");
            let bi = band(e.z);
            if let Some(c) = cdicts[bi].as_mut() {
                let fr = zstd_c_dict(c, payload);
                acc.add("codec:zstdC_19_dict", H + fr.len());
            } else {
                acc.add("codec:zstdC_19_dict", H + zstd_c(payload, 19));
            }
            if let Some(d) = rs_dicts[bi].as_ref() {
                let fr = zstd_rs_dict(payload, 19, d);
                acc.add("codec:zstdRS_19_dict", H + fr.len());
                if let Some(rd) = ruzstd::decoding::Dictionary::decode_dict(&dicts[bi]).ok() {
                    let td = Instant::now();
                    let mut dec = ruzstd::decoding::FrameDecoder::new();
                    dec.add_dict(rd).expect("add dict");
                    let mut out = vec![0u8; payload.len()];
                    let n = dec.decode_all(&fr, &mut out).expect("ruzstd dict");
                    acc.t_zstd_dict_ns += td.elapsed().as_nanos() as u64;
                    assert_eq!(&out[..n], payload, "ruzstd dict round trip");
                }
            } else {
                acc.add("codec:zstdRS_19_dict", H + rs19.len());
            }
            for t in &tnames {
                let nb = apply(t, &body, e.z, &ids);
                if t.starts_with("hm_") && !t.contains("delta") {
                    if let (Some(o), Some(n)) = (&body.heightmap, &nb.heightmap) {
                        let (m, ss, c) = hm_err(o, n);
                        let ent = acc.hm_err.entry(t.to_string()).or_insert((0.0, 0.0, 0));
                        ent.0 = ent.0.max(m);
                        ent.1 += ss;
                        ent.2 += c;
                    }
                }
                let r = ser(&nb);
                if *t == "identity_v10" && r == raw {
                    acc.identical_v10 += 1;
                }
                acc.add(&format!("xf:{t}:raw"), r.len());
                acc.add(&format!("xf:{t}:zstdC19"), H + zstd_c(&r[H..], 19));
                if let Some(c) = cdicts[bi].as_mut() {
                    acc.add(&format!("xf:{t}:zstdC19dict"), H + zstd_c_dict(c, &r[H..]).len());
                }
            }
        }
        let mut g = acc.lock().unwrap();
        for (z, l) in local {
            g.entry(z).or_default().merge(&l);
        }
        let d = done.fetch_add(chunk.len(), std::sync::atomic::Ordering::Relaxed) + chunk.len();
        if d % 20_000 < 64 {
            eprintln!("  {d}/{} ({:.0}s)", sample.len(), t0.elapsed().as_secs_f64());
        }
    });
    let acc = acc.into_inner().unwrap();

    // Report: per-variant planet projection (ratio estimator per zoom).
    let mut out = String::new();
    let total_exact: u64 = exact.values().map(|v| v.1).sum();
    out += &format!("archive {path}\nunique bodies {}  exact stored {:.3} GB\n\n", ents.len(), total_exact as f64 / 1e9);
    out += "zoom  bodies  sampled  exact_GB  ratio_raw/stored  identical_v10  hm_dims\n";
    for (z, a) in &acc {
        let (c, s) = exact[z];
        out += &format!(
            "z{:<3} {:>9} {:>7} {:>8.3} {:>8.3} {:>8}/{:<8} {:?}\n",
            z, c, a.n, s as f64 / 1e9, a.raw as f64 / a.stored as f64, a.identical_v10, a.n, a.hm_dims
        );
    }
    let keys: Vec<String> = acc.values().flat_map(|a| a.v.keys().cloned()).collect::<std::collections::BTreeSet<_>>().into_iter().collect();
    out += "\nvariant                                   planet_GB   delta_GB   per-band GB (z0-9 z10-11 z12 z13 z14)\n";
    let mut tsv = String::from("variant\tplanet_gb\tdelta_gb\tz0_9\tz10_11\tz12\tz13\tz14\n");
    for k in &keys {
        let mut total = 0.0;
        let mut per_band = [0.0f64; 5];
        for (z, a) in &acc {
            let (_, s) = exact[z];
            let v = *a.v.get(k).unwrap_or(&0) as f64;
            let est = s as f64 * v / a.stored as f64;
            total += est;
            per_band[band(*z)] += est;
        }
        let delta = total - total_exact as f64;
        out += &format!(
            "{:<40} {:>10.3} {:>10.3}   {}\n",
            k,
            total / 1e9,
            delta / 1e9,
            per_band.iter().map(|g| format!("{:.2}", g / 1e9)).collect::<Vec<_>>().join(" ")
        );
        tsv += &format!(
            "{k}\t{:.4}\t{:.4}\t{}\n",
            total / 1e9,
            delta / 1e9,
            per_band.iter().map(|g| format!("{:.4}", g / 1e9)).collect::<Vec<_>>().join("\t")
        );
    }
    // Decode timing (host, per body, single thread inside a rayon worker).
    out += "\nhost decode time per body (us): inflate(DEFLATE, current) | ruzstd(zstd-rs 19) | ruzstd+dict | Body::parse\n";
    for (z, a) in &acc {
        let n = a.n.max(1) as f64;
        out += &format!(
            "z{:<3} {:>8.1} {:>8.1} {:>8.1} {:>8.1}   (avg raw {:.0} B)\n",
            z,
            a.t_inflate_ns as f64 / n / 1e3,
            a.t_zstd_ns as f64 / n / 1e3,
            a.t_zstd_dict_ns as f64 / n / 1e3,
            a.t_parse_ns as f64 / n / 1e3,
            a.raw as f64 / n
        );
    }
    let mut all = Acc::default();
    for a in acc.values() {
        all.merge(a);
    }
    let n = all.n as f64;
    out += &format!(
        "all  {:>8.1} {:>8.1} {:>8.1} {:>8.1}\n",
        all.t_inflate_ns as f64 / n / 1e3,
        all.t_zstd_ns as f64 / n / 1e3,
        all.t_zstd_dict_ns as f64 / n / 1e3,
        all.t_parse_ns as f64 / n / 1e3
    );
    out += "\nDEM error vs current archive grid (metres; max over the continuous bilinear surface):\n";
    for (k, (m, ss, n)) in &all.hm_err {
        out += &format!("  {:<14} max {:>6.2}  rms {:>6.3}\n", k, m, (ss / (*n).max(1) as f64).sqrt());
    }
    out += &format!("total {:.0}s\n", t0.elapsed().as_secs_f64());
    print!("{out}");
    std::fs::write(format!("{out_dir}/bodies.txt"), &out).unwrap();
    std::fs::write(format!("{out_dir}/bodies.tsv"), &tsv).unwrap();
}

// ---------------------------------------------------------------- sidecars

fn kind_name(k: u8) -> &'static str {
    match k {
        1 => "graph_meta",
        2 => "graph_nodes",
        3 => "graph_edges",
        4 => "graph_intermediate",
        5 => "graph_names",
        6 => "graph_lanes",
        7 => "graph_elevation",
        8 => "poi_index",
        9 => "poi_names",
        10 => "poi_attrs",
        11 => "poi_spatial",
        12 => "poi_words",
        13 => "transit",
        14 => "graph_overlay",
        15 => "poi_region",
        16 => "poi_admin",
        _ => "?",
    }
}

fn cmd_sidecars(path: &str, windows: usize, out_dir: &str) {
    let a = open(path);
    let view = tilecodec::mamaps::archive::ArchiveView::parse(&a.map, &a.hdr).expect("view");
    let mut out = String::from("section                 bytes_GB  zblk  sampled_MB  zstdC19_64K  zstdRS19_64K  zstdC19_1M  xz9e_1M(ref)  est_GB(C19_64K)\n");
    let mut tsv = String::from("section\tbytes\tzblk\tratio_c19_64k\tratio_rs19_64k\tratio_c19_1m\test_gb_c19_64k\n");
    for e in &view.entries {
        let sec = &a.map[e.offset as usize..(e.offset + e.len) as usize];
        let is_zblk = sec.len() > 4 && &sec[..4] == b"ZBLK";
        if is_zblk {
            // Decode the ZBLK and recompress its blocks at higher levels.
            let b = tilecodec::blocked::Blocked::parse(sec).expect("zblk");
            let nb = b.block_count();
            let step = (nb / windows.max(1)).max(1);
            let idxs: Vec<usize> = (0..nb).step_by(step).collect();
            let r: Vec<(u64, u64, u64, u64, u64)> = idxs
                .par_iter()
                .map(|&i| {
                    let raw = b.block(i).expect("block");
                    let stored = b.block_offset(i + 1).unwrap() - b.block_offset(i).unwrap();
                    (raw.len() as u64, stored, zstd_c(&raw, 19) as u64, zstd_c(&raw, 22) as u64, zstd_rs(&raw, 19).len() as u64)
                })
                .collect();
            let (raw, st, c19, c22, rs19) = r.iter().fold((0, 0, 0, 0, 0), |a, b| (a.0 + b.0, a.1 + b.1, a.2 + b.2, a.3 + b.3, a.4 + b.4));
            let unpacked = b.unpacked_len();
            out += &format!(
                "{:<22} {:>8.3}  ZBLK  unpacked {:.3} GB, sample {} blocks: current {:.3}x  C19 {:.3}x  C22 {:.3}x  RS19 {:.3}x  -> est C19 {:.3} GB\n",
                kind_name(e.kind),
                e.len as f64 / 1e9,
                unpacked as f64 / 1e9,
                idxs.len(),
                raw as f64 / st as f64,
                raw as f64 / c19 as f64,
                raw as f64 / c22 as f64,
                raw as f64 / rs19 as f64,
                e.len as f64 * c19 as f64 / st as f64 / 1e9
            );
            tsv += &format!("{}\t{}\t1\t{:.4}\t{:.4}\t\t{:.4}\n", kind_name(e.kind), e.len, raw as f64 / c19 as f64, raw as f64 / rs19 as f64, e.len as f64 * c19 as f64 / st as f64 / 1e9);
            continue;
        }
        let win = 1usize << 20;
        let blk = 64usize << 10;
        let n = windows.min((sec.len() / win).max(1));
        let starts: Vec<usize> = if sec.len() <= win * windows {
            (0..sec.len()).step_by(win).collect()
        } else {
            (0..n).map(|i| (sec.len() - win) / n.max(1) * i).collect()
        };
        let r: Vec<(u64, u64, u64, u64)> = starts
            .par_iter()
            .map(|&s| {
                let w = &sec[s..(s + win).min(sec.len())];
                let mut c19 = 0u64;
                let mut rs19 = 0u64;
                for b in w.chunks(blk) {
                    c19 += zstd_c(b, 19) as u64;
                    rs19 += zstd_rs(b, 19).len() as u64;
                }
                (w.len() as u64, c19, rs19, zstd_c(w, 19) as u64)
            })
            .collect();
        let (raw, c19, rs19, c1m) = r.iter().fold((0, 0, 0, 0), |a, b| (a.0 + b.0, a.1 + b.1, a.2 + b.2, a.3 + b.3));
        out += &format!(
            "{:<22} {:>8.3}  raw   {:>9.1}  {:>9.3}x  {:>9.3}x  {:>9.3}x  {:>12}  {:>8.3}\n",
            kind_name(e.kind),
            e.len as f64 / 1e9,
            raw as f64 / 1e6,
            raw as f64 / c19 as f64,
            raw as f64 / rs19 as f64,
            raw as f64 / c1m as f64,
            "-",
            e.len as f64 * c19 as f64 / raw as f64 / 1e9
        );
        tsv += &format!(
            "{}\t{}\t0\t{:.4}\t{:.4}\t{:.4}\t{:.4}\n",
            kind_name(e.kind),
            e.len,
            raw as f64 / c19 as f64,
            raw as f64 / rs19 as f64,
            raw as f64 / c1m as f64,
            e.len as f64 * c19 as f64 / raw as f64 / 1e9
        );
    }
    print!("{out}");
    std::fs::write(format!("{out_dir}/sidecars.txt"), &out).unwrap();
    std::fs::write(format!("{out_dir}/sidecars.tsv"), &tsv).unwrap();
    let _ = std::io::stdout().flush();
}

include!("more.rs");

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let mode = args.get(1).map(String::as_str).unwrap_or("");
    if mode == "render" {
        return cmd_render(&args);
    }
    if mode == "cmp" {
        // size_lab cmp BASE CAND PER_ZOOM OUT_DIR
        std::fs::create_dir_all(&args[5]).unwrap();
        return cmd_cmp(&args[2], &args[3], args[4].parse().unwrap(), &args[5]);
    }
    let path = args.get(2).expect("usage: size_lab bodies|sidecars IN.mamaps [N] [OUT_DIR]");
    let out_dir = args.get(4).cloned().unwrap_or_else(|| ".".into());
    std::fs::create_dir_all(&out_dir).unwrap();
    match mode {
        "bodies" => cmd_bodies(path, args.get(3).and_then(|s| s.parse().ok()).unwrap_or(2000), &out_dir),
        "sidecars" => cmd_sidecars(path, args.get(3).and_then(|s| s.parse().ok()).unwrap_or(64), &out_dir),
        "transit" => cmd_transit(path, &out_dir),
        "zblk" => cmd_zblk(path),
        "zooms" => cmd_zooms(path),
        "recompress" => cmd_recompress(path, args.get(3).map(String::as_str).unwrap_or(".")),
        "dem-pyramid" => cmd_dem_pyramid(path, args[3].parse().unwrap(), &out_dir),
        _ => eprintln!("usage: size_lab bodies|sidecars IN.mamaps [N] [OUT_DIR]"),
    }
}
