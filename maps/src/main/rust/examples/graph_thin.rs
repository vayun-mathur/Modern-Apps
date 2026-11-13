//! `graph_thin` — planet-size prototype: error-bounded shape-point thinning of a routing graph's
//! `intermediate.bin`, keeping the on-disk layout (no reader change).
//!
//! ```text
//! cargo run --release --example graph_thin -- IN_GRAPH_DIR OUT_GRAPH_DIR TOL_CM
//! ```
//!
//! Every stored polyline (source node + interior points + target node) is Douglas-Peucker
//! simplified with tolerance `TOL_CM` (perpendicular distance in centimetres on a local
//! equirectangular projection), endpoints always kept — so straight runs collapse to their
//! endpoints and bends keep exactly the vertices that carry them. Interior points are re-added
//! wherever a gap would overflow the format's `i16` e7 delta (~365 m), so the result is always
//! encodable. `edges.bin` distances and times are untouched (route length / ETA cannot move);
//! only the decoded polyline does, by at most `TOL_CM` from the original.
//!
//! The presence bitmap and its rank index are copied verbatim (an edge whose interior empties
//! keeps an empty blob), so `G` and every edge's geometry rank are unchanged; only the blob and
//! the two-level offset table are rebuilt. Every other graph file is copied. Also reports the
//! size the thinned polylines would take under two candidate *format changes*: zigzag-varint
//! deltas, and the same at a 1e-6 degree (~11 cm) grid.
//!
//! Experiment tooling (examples/): never in the cdylib.

use offlinerouter::graph::{Graph, LatLon, INTERMEDIATE_BLOCK};

fn read_u64(b: &[u8], at: usize) -> u64 {
    u64::from_le_bytes(b[at..at + 8].try_into().unwrap())
}

fn seg_dist_sq(p: (f64, f64), a: (f64, f64), b: (f64, f64)) -> f64 {
    let (dx, dy) = (b.0 - a.0, b.1 - a.1);
    let l2 = dx * dx + dy * dy;
    if l2 == 0.0 {
        return (p.0 - a.0).powi(2) + (p.1 - a.1).powi(2);
    }
    let t = (((p.0 - a.0) * dx + (p.1 - a.1) * dy) / l2).clamp(0.0, 1.0);
    let (qx, qy) = (a.0 + t * dx, a.1 + t * dy);
    (p.0 - qx).powi(2) + (p.1 - qy).powi(2)
}

fn dp(pts: &[(f64, f64)], tol_sq: f64, keep: &mut [bool]) {
    let mut stack = vec![(0usize, pts.len() - 1)];
    while let Some((a, b)) = stack.pop() {
        if b <= a + 1 {
            continue;
        }
        let (mut worst, mut wi) = (0.0, a);
        for i in a + 1..b {
            let d = seg_dist_sq(pts[i], pts[a], pts[b]);
            if d > worst {
                worst = d;
                wi = i;
            }
        }
        if worst > tol_sq {
            keep[wi] = true;
            stack.push((a, wi));
            stack.push((wi, b));
        }
    }
}

fn push_uvarint(out: &mut usize, mut v: u64) {
    loop {
        *out += 1;
        v >>= 7;
        if v == 0 {
            break;
        }
    }
}

fn zz(v: i64) -> u64 {
    ((v << 1) ^ (v >> 63)) as u64
}

fn main() -> std::process::ExitCode {
    let a: Vec<String> = std::env::args().skip(1).collect();
    if a.len() < 3 {
        eprintln!("usage: graph_thin IN_GRAPH_DIR OUT_GRAPH_DIR TOL_CM");
        return std::process::ExitCode::from(2);
    }
    let (inp, outp) = (a[0].trim_end_matches('/'), a[1].trim_end_matches('/'));
    let tol_cm: f64 = a[2].parse().expect("TOL_CM");
    // Optional: keep every shape point within KEEP_END_M (along the line) of either node, so
    // the approach/exit bearings guidance derives maneuvers from are untouched ("full detail
    // at junctions").
    let keep_end_m: f64 = a.get(3).map(|v| v.parse().expect("KEEP_END_M")).unwrap_or(0.0);
    let g = Graph::load(&format!("{inp}/")).expect("load graph");
    let inter = std::fs::read(format!("{inp}/intermediate.bin")).expect("read intermediate");
    let e = g.edge_count;
    let gcount = read_u64(&inter, inter.len() - 8);
    let bitmap_bytes = (e.div_ceil(512) + 1) * 8 + e.div_ceil(8);
    let coarse_bytes = (gcount.div_ceil(INTERMEDIATE_BLOCK) + 1) * 8;
    let within_bytes = (gcount + 1) * 2;
    let trailer = bitmap_bytes + coarse_bytes + within_bytes + 8;
    let blob_len = inter.len() as u64 - trailer;
    let bitmap = &inter[blob_len as usize..(blob_len + bitmap_bytes) as usize];
    let present_off = ((e.div_ceil(512) + 1) * 8) as usize;
    let present = &bitmap[present_off..];

    let tol_m = tol_cm / 100.0;
    let mut blob: Vec<u8> = Vec::with_capacity(blob_len as usize);
    let mut coarse: Vec<u64> = Vec::with_capacity(coarse_bytes as usize / 8);
    let mut within: Vec<u16> = Vec::with_capacity(gcount as usize + 1);
    let mut buf = vec![LatLon { lat_e7: 0, lon_e7: 0 }; 256];
    let (mut pts_in, mut pts_out, mut readded) = (0u64, 0u64, 0u64);
    let (mut var_e7, mut var_e6) = (0usize, 0usize);
    let mut max_dev_m = 0.0f64;
    let mut rank = 0u64;
    let mut block_start = 0u64;
    for idx in 0..e {
        if present[(idx / 8) as usize] & (1 << (idx % 8)) == 0 {
            continue;
        }
        if rank % INTERMEDIATE_BLOCK == 0 {
            block_start = blob.len() as u64;
            coarse.push(block_start);
        }
        within.push((blob.len() as u64 - block_start) as u16);
        rank += 1;
        let src = g.find_node_idx_for_edge(idx);
        let Some((cnt, rev)) = g.get_edge_coordinates_from(src, idx, &mut buf) else {
            panic!("edge {idx} present in the bitmap but not decodable");
        };
        assert!(!rev, "a stored polyline decoded reversed");
        let cnt = cnt as usize;
        if cnt < 3 {
            continue;
        }
        let poly = &buf[..cnt];
        pts_in += (cnt - 2) as u64;
        // Local metres: equirectangular about the source.
        let lat0 = poly[0].lat_e7 as f64 * 1e-7;
        let kx = 0.011_131_949 * lat0.to_radians().cos(); // m per e7 unit of lon (×1e-7 deg × 111319.49 m)
        let ky = 0.011_131_949;
        let xy: Vec<(f64, f64)> = poly
            .iter()
            .map(|p| ((p.lon_e7 - poly[0].lon_e7) as f64 * kx, (p.lat_e7 - poly[0].lat_e7) as f64 * ky))
            .collect();
        let mut keep = vec![false; cnt];
        keep[0] = true;
        keep[cnt - 1] = true;
        if tol_m > 0.0 {
            dp(&xy, tol_m * tol_m, &mut keep);
            if keep_end_m > 0.0 {
                let mut acc = vec![0.0f64; cnt];
                for k in 1..cnt {
                    acc[k] = acc[k - 1] + ((xy[k].0 - xy[k - 1].0).powi(2) + (xy[k].1 - xy[k - 1].1).powi(2)).sqrt();
                }
                let total = acc[cnt - 1];
                for k in 1..cnt - 1 {
                    if acc[k] <= keep_end_m || total - acc[k] <= keep_end_m {
                        keep[k] = true;
                    }
                }
            }
        } else {
            keep.iter_mut().for_each(|k| *k = true);
        }
        // i16 delta bridging: walk kept points, re-adding the farthest in-range original point
        // whenever the next kept point is out of delta range of the previous one.
        let fits = |a: &LatLon, b: &LatLon| {
            (b.lat_e7 - a.lat_e7).abs() <= i16::MAX as i32 && (b.lon_e7 - a.lon_e7).abs() <= i16::MAX as i32
        };
        let mut out_idx: Vec<usize> = vec![0];
        let mut i = 1;
        while i < cnt - 1 {
            if !keep[i] {
                i += 1;
                continue;
            }
            let prev = *out_idx.last().unwrap();
            if fits(&poly[prev], &poly[i]) {
                out_idx.push(i);
                i += 1;
            } else {
                // Farthest original point after prev that still fits (always prev+1 at worst,
                // since the original encoded every consecutive pair).
                let mut j = prev + 1;
                while j + 1 < i && fits(&poly[prev], &poly[j + 1]) {
                    j += 1;
                }
                out_idx.push(j);
                readded += 1;
            }
        }
        // Bridging re-adds split DP's chords, which can leave a dropped vertex farther than the
        // tolerance from the new, shorter chord: restore the bound by re-inserting the worst
        // vertex of any span that breaks it, until none does (only ever adds points, so the
        // i16 fit established above still holds).
        let mut kept: Vec<usize> = out_idx.iter().copied().chain(std::iter::once(cnt - 1)).collect();
        if tol_m > 0.0 {
            loop {
                let mut changed = false;
                let mut next = Vec::with_capacity(kept.len() + 4);
                for w in kept.windows(2) {
                    next.push(w[0]);
                    let (mut worst, mut wi) = (tol_m * tol_m, usize::MAX);
                    for k in w[0] + 1..w[1] {
                        let d = seg_dist_sq(xy[k], xy[w[0]], xy[w[1]]);
                        if d > worst {
                            worst = d;
                            wi = k;
                        }
                    }
                    if wi != usize::MAX {
                        next.push(wi);
                        changed = true;
                    }
                }
                next.push(cnt - 1);
                kept = next;
                if !changed {
                    break;
                }
            }
            out_idx = kept[..kept.len() - 1].to_vec();
        }
        // Deviation of the thinned polyline from every dropped original vertex: the exact
        // max over the original vertices (straight original segments between them cannot
        // stray farther than their endpoints from a chord).

        for w in kept.windows(2) {
            for k in w[0] + 1..w[1] {
                max_dev_m = max_dev_m.max(seg_dist_sq(xy[k], xy[w[0]], xy[w[1]]).sqrt());
            }
        }
        // Encode interior points (out_idx[1..]) as i16 deltas, first from the source.
        let mut prev = poly[0];
        let mut prev6 = (poly[0].lat_e7 / 10, poly[0].lon_e7 / 10);
        for &k in &out_idx[1..] {
            let p = poly[k];
            let dl = (p.lat_e7 - prev.lat_e7) as i16;
            let dn = (p.lon_e7 - prev.lon_e7) as i16;
            blob.extend_from_slice(&dl.to_le_bytes());
            blob.extend_from_slice(&dn.to_le_bytes());
            push_uvarint(&mut var_e7, zz((p.lat_e7 - prev.lat_e7) as i64));
            push_uvarint(&mut var_e7, zz((p.lon_e7 - prev.lon_e7) as i64));
            let q = ((p.lat_e7 as f64 / 10.0).round() as i32, (p.lon_e7 as f64 / 10.0).round() as i32);
            push_uvarint(&mut var_e6, zz((q.0 - prev6.0) as i64));
            push_uvarint(&mut var_e6, zz((q.1 - prev6.1) as i64));
            prev = p;
            prev6 = q;
            pts_out += 1;
        }
    }
    assert_eq!(rank, gcount, "geometry rank count");
    // Sentinel: the blob length, in the final block's slot.
    if rank % INTERMEDIATE_BLOCK == 0 {
        coarse.push(blob.len() as u64);
        block_start = blob.len() as u64;
    }
    within.push((blob.len() as u64 - block_start) as u16);
    // The table always carries div_ceil(G, 32) + 1 entries; the generator's trailing slot is
    // the blob length.
    while (coarse.len() as u64) * 8 < coarse_bytes {
        coarse.push(blob.len() as u64);
    }
    assert_eq!(coarse.len() as u64 * 8, coarse_bytes, "coarse table size");
    assert_eq!(within.len() as u64 * 2, within_bytes, "within table size");

    std::fs::create_dir_all(outp).expect("mkdir");
    for f in std::fs::read_dir(inp).expect("readdir") {
        let f = f.unwrap();
        let name = f.file_name().into_string().unwrap();
        if name == "intermediate.bin" || !f.file_type().unwrap().is_file() {
            continue;
        }
        std::fs::copy(f.path(), format!("{outp}/{name}")).expect("copy");
    }
    let mut out = blob;
    let new_blob = out.len();
    out.extend_from_slice(bitmap);
    for c in &coarse {
        out.extend_from_slice(&c.to_le_bytes());
    }
    for w in &within {
        out.extend_from_slice(&w.to_le_bytes());
    }
    out.extend_from_slice(&gcount.to_le_bytes());
    std::fs::write(format!("{outp}/intermediate.bin"), &out).expect("write");
    // Validate by loading the result with the real reader.
    let g2 = Graph::load(&format!("{outp}/")).expect("thinned graph must load");
    assert_eq!(g2.edge_count, e);
    println!(
        "graph_thin tol_cm={tol_cm} keep_end_m={keep_end_m} interior_points {pts_in} -> {pts_out} ({:.1}%), readded_for_i16 {readded}, \
         max_dev_m {max_dev_m:.3}\n  intermediate.bin {} -> {} bytes (blob {} -> {}; trailer {})\n  \
         format-change estimates for the thinned blob: zigzag-varint e7 {} B, varint at 1e-6 deg {} B",
        100.0 * pts_out as f64 / pts_in.max(1) as f64,
        inter.len(),
        out.len(),
        blob_len,
        new_blob,
        trailer,
        var_e7,
        var_e6
    );
    std::process::ExitCode::SUCCESS
}
