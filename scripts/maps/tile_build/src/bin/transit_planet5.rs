//! `transit_planet5` — rewrite a TRIX v7 transit pack as planet5 TRIX v8.
//!
//! ```text
//! transit_planet5 IN.transit OUT.transit [--shape-tol-m 1.0]
//! ```
//!
//! * drops the host-only sections (27.., RAIL_LINES), as `mamaps_pack` already did;
//! * empties ROUTE_TRIPS (the varint trip stream): readers since TRX v6 take trips from
//!   ROUTE_TRIP_RECS/ROUTE_TRIP_OFF and fall back to the stream only for a route whose table
//!   disagrees with its `n_trips` — checked here for every route, and the stream is kept if any
//!   route would need it;
//! * thins SHAPE_COORDS with Douglas-Peucker at `--shape-tol-m` metres between protected
//!   vertices (every stop's vertex of every route sharing the blob, and both ends), remapping
//!   ROUTE_STOP_SHAPE to the kept vertices and rebuilding ROUTE_SHAPE_IDX; a ride leg still
//!   slices `shape[vertex(board)..=vertex(alight)]` exactly at its stops;
//! * stores the sections the planner only reads to describe a result (names, feed metadata,
//!   polylines, MOTIS ids) as 64 KiB zstd-19 ZSEC sections (`--zsec` overrides; see
//!   COLD_SECTIONS for the measured trade-off), flagged by bit 63 of their directory length,
//!   and stamps version 8.
//!
//! The device reader (maps `transit.rs`) reads v3..=v8; a v7-only reader refuses v8 cleanly.
use std::fs::File;
use std::process::ExitCode;

const HEADER_LEN: usize = 80;
const DEVICE_SECTIONS: usize = 27;
const SEC_ROUTES: usize = 2;
const SEC_ROUTE_STOPS: usize = 3;
const SEC_ROUTE_TRIPS: usize = 4;
const SEC_SHAPE_COORDS: usize = 20;
const SEC_ROUTE_SHAPE_IDX: usize = 21;
const SEC_ROUTE_STOP_SHAPE: usize = 22;
const SEC_ROUTE_TRIP_RECS: usize = 25;
const SEC_ROUTE_TRIP_OFF: usize = 26;
const ROUTE_REC: usize = 32;
/// Sections stored as ZSEC by default: the ones the planner reads only to describe a finished
/// journey or a stop (names 0, feed metadata 16/17/23, polylines and their indexes 20-22, MOTIS
/// ids 24). The RAPTOR tables stay raw. Plan time against raw v7 on the planet pack (block cache
/// 16 MiB/thread):
///   cold only           2.94 GB  1.01x (60 queries)  <- default
///   cold + exceptions   2.45 GB  1.30x (12 queries), 5.5x (60: long searches thrash the cache)
///   + stop routes 2.3x | + transfers 2.6x | + profiles 5.2x (12 queries)
///   everything          1.19 GB  ~35x
const COLD_SECTIONS: [usize; 8] = [0, 16, 17, 20, 21, 22, 23, 24];
const NONE: u32 = u32::MAX;

fn u32_at(b: &[u8], o: usize) -> u32 {
    u32::from_le_bytes(b[o..o + 4].try_into().unwrap())
}

fn uvar(b: &[u8], pos: &mut usize) -> u64 {
    let (mut r, mut s) = (0u64, 0);
    loop {
        let c = b[*pos];
        *pos += 1;
        r |= ((c & 0x7f) as u64) << s;
        if c < 0x80 {
            return r;
        }
        s += 7;
    }
}

fn put_uvar(out: &mut Vec<u8>, mut v: u64) {
    loop {
        let b = (v & 0x7f) as u8;
        v >>= 7;
        if v == 0 {
            out.push(b);
            return;
        }
        out.push(b | 0x80);
    }
}

fn seg_dist_sq(p: (f64, f64), a: (f64, f64), b: (f64, f64)) -> f64 {
    let (dx, dy) = (b.0 - a.0, b.1 - a.1);
    let l2 = dx * dx + dy * dy;
    let t = if l2 == 0.0 { 0.0 } else { (((p.0 - a.0) * dx + (p.1 - a.1) * dy) / l2).clamp(0.0, 1.0) };
    (p.0 - a.0 - t * dx).powi(2) + (p.1 - a.1 - t * dy).powi(2)
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    let mut tol = 1.0f64;
    let mut zsec: Vec<usize> = COLD_SECTIONS.to_vec();
    let mut pos = Vec::new();
    let mut i = 1;
    while i < args.len() {
        if args[i] == "--shape-tol-m" {
            tol = args[i + 1].parse().expect("--shape-tol-m M");
            i += 2;
            continue;
        }
        if args[i] == "--zsec" {
            zsec = match args[i + 1].as_str() {
                "all" => (0..DEVICE_SECTIONS).collect(),
                "cold" => COLD_SECTIONS.to_vec(),
                "none" => Vec::new(),
                list => list.split(',').map(|v| v.parse().expect("--zsec section list")).collect(),
            };
            i += 2;
            continue;
        }
        pos.push(args[i].clone());
        i += 1;
    }
    if pos.len() != 2 {
        eprintln!("usage: transit_planet5 IN.transit OUT.transit [--shape-tol-m M] [--zsec cold|all|none|i,j,..]");
        return ExitCode::from(2);
    }
    match run(&pos[0], &pos[1], tol, &zsec) {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("transit_planet5: {e}");
            ExitCode::FAILURE
        }
    }
}

fn run(input: &str, output: &str, tol: f64, zsec: &[usize]) -> Result<(), String> {
    let f = File::open(input).map_err(|e| format!("open {input}: {e}"))?;
    let m = unsafe { memmap2::Mmap::map(&f) }.map_err(|e| e.to_string())?;
    if m.len() < HEADER_LEN || u32_at(&m, 0) != tilecodec::mamaps::archive::TRANSIT_MAGIC {
        return Err("not a TRIX pack".into());
    }
    let version = u32_at(&m, 4);
    if version != 7 {
        return Err(format!("expected a v7 pack, got v{version}"));
    }
    let count = u32_at(&m, 8) as usize;
    let route_count = u32_at(&m, 16) as usize;
    let sec = |i: usize| -> &[u8] {
        if i >= count {
            return &[];
        }
        let at = HEADER_LEN + i * 16;
        let off = u64::from_le_bytes(m[at..at + 8].try_into().unwrap()) as usize;
        let len = u64::from_le_bytes(m[at + 8..at + 16].try_into().unwrap()) as usize;
        &m[off..off + len]
    };
    let mut sections: Vec<Vec<u8>> = (0..DEVICE_SECTIONS).map(|i| sec(i).to_vec()).collect();
    let routes = sec(SEC_ROUTES);

    // ROUTE_TRIPS: drop only when every route's strided table agrees with its record.
    let trip_off = sec(SEC_ROUTE_TRIP_OFF);
    let recs_len = sec(SEC_ROUTE_TRIP_RECS).len();
    let table_ok = trip_off.len() >= (route_count + 1) * 4
        && (0..route_count).all(|r| {
            let n_trips = u32_at(routes, r * ROUTE_REC + 24);
            let (b, e) = (u32_at(trip_off, r * 4), u32_at(trip_off, r * 4 + 4));
            e >= b && e - b == n_trips && (e as usize) * 16 <= recs_len
        });
    let dropped_trips = if table_ok { std::mem::take(&mut sections[SEC_ROUTE_TRIPS]).len() } else { 0 };

    // SHAPE_COORDS thinning with stop vertices protected.
    let shapes = sec(SEC_SHAPE_COORDS);
    let shape_idx = sec(SEC_ROUTE_SHAPE_IDX);
    let rss = sec(SEC_ROUTE_STOP_SHAPE);
    let (mut pts_in, mut pts_out) = (0u64, 0u64);
    if !shapes.is_empty() && shape_idx.len() >= (route_count + 1) * 4 && tol > 0.0 {
        let mut prot: std::collections::BTreeMap<u32, Vec<u32>> = Default::default();
        for r in 0..route_count {
            let off = u32_at(shape_idx, r * 4);
            if off == NONE {
                continue;
            }
            let n_stops = u32_at(routes, r * ROUTE_REC + 16) as usize;
            let first = u32_at(routes, r * ROUTE_REC + 20) as usize;
            let e = prot.entry(off).or_default();
            for k in 0..n_stops {
                if (first + k + 1) * 4 <= rss.len() {
                    let v = u32_at(rss, (first + k) * 4);
                    if v != NONE {
                        e.push(v);
                    }
                }
            }
        }
        let mut new_shapes = Vec::with_capacity(shapes.len() / 2);
        let mut new_off: std::collections::HashMap<u32, u32> = Default::default();
        let mut remap: std::collections::HashMap<u32, Vec<u32>> = Default::default();
        for (&off, protected) in &prot {
            let mut p = off as usize;
            let n = u32_at(shapes, p) as usize;
            p += 4;
            let (mut la, mut lo) = (0i64, 0i64);
            let mut pts = Vec::with_capacity(n);
            for _ in 0..n {
                let a = uvar(shapes, &mut p);
                let b = uvar(shapes, &mut p);
                la += ((a >> 1) as i64) ^ -((a & 1) as i64);
                lo += ((b >> 1) as i64) ^ -((b & 1) as i64);
                pts.push((la, lo));
            }
            pts_in += n as u64;
            let mut keep = vec![false; n];
            if n > 0 {
                keep[0] = true;
                keep[n - 1] = true;
            }
            for &v in protected {
                if (v as usize) < n {
                    keep[v as usize] = true;
                }
            }
            let lat0 = if n > 0 { pts[0].0 as f64 * 1e-7 } else { 0.0 };
            let kx = 0.011_131_949 * lat0.to_radians().cos();
            let xy: Vec<(f64, f64)> = pts.iter().map(|q| (q.1 as f64 * kx, q.0 as f64 * 0.011_131_949)).collect();
            let anchors: Vec<usize> = (0..n).filter(|&i| keep[i]).collect();
            for w in anchors.windows(2) {
                let mut stack = vec![(w[0], w[1])];
                while let Some((a, b)) = stack.pop() {
                    if b <= a + 1 {
                        continue;
                    }
                    let (mut worst, mut wi) = (tol * tol, usize::MAX);
                    for i in a + 1..b {
                        let d = seg_dist_sq(xy[i], xy[a], xy[b]);
                        if d > worst {
                            worst = d;
                            wi = i;
                        }
                    }
                    if wi != usize::MAX {
                        keep[wi] = true;
                        stack.push((a, wi));
                        stack.push((wi, b));
                    }
                }
            }
            let mut map = vec![NONE; n];
            let mut kept = 0u32;
            for i in 0..n {
                if keep[i] {
                    map[i] = kept;
                    kept += 1;
                }
            }
            new_off.insert(off, new_shapes.len() as u32);
            new_shapes.extend_from_slice(&kept.to_le_bytes());
            let (mut pl, mut pn) = (0i64, 0i64);
            for i in 0..n {
                if keep[i] {
                    let (a, b) = pts[i];
                    put_uvar(&mut new_shapes, (((a - pl) << 1) ^ ((a - pl) >> 63)) as u64);
                    put_uvar(&mut new_shapes, (((b - pn) << 1) ^ ((b - pn) >> 63)) as u64);
                    (pl, pn) = (a, b);
                }
            }
            pts_out += kept as u64;
            remap.insert(off, map);
        }
        let mut new_idx = Vec::with_capacity(shape_idx.len());
        let mut new_rss = rss.to_vec();
        for r in 0..route_count {
            let off = u32_at(shape_idx, r * 4);
            if off == NONE {
                new_idx.extend_from_slice(&NONE.to_le_bytes());
                continue;
            }
            new_idx.extend_from_slice(&new_off[&off].to_le_bytes());
            let map = &remap[&off];
            let n_stops = u32_at(routes, r * ROUTE_REC + 16) as usize;
            let first = u32_at(routes, r * ROUTE_REC + 20) as usize;
            for k in 0..n_stops {
                let at = (first + k) * 4;
                if at + 4 > new_rss.len() {
                    continue;
                }
                let v = u32_at(rss, at);
                if v != NONE {
                    let nv = map.get(v as usize).copied().unwrap_or(NONE);
                    if nv == NONE {
                        return Err(format!("route {r}: stop vertex {v} was not kept"));
                    }
                    new_rss[at..at + 4].copy_from_slice(&nv.to_le_bytes());
                }
            }
        }
        new_idx.extend_from_slice(&(new_shapes.len() as u32).to_le_bytes());
        sections[SEC_SHAPE_COORDS] = new_shapes;
        sections[SEC_ROUTE_SHAPE_IDX] = new_idx;
        sections[SEC_ROUTE_STOP_SHAPE] = new_rss;
    }

    // ZSEC the chosen non-empty sections (flagged in the directory); v8 header and directory.
    let raw_total: usize = sections.iter().map(|s| s.len()).sum();
    let stored: Vec<(Vec<u8>, bool)> = sections
        .iter()
        .enumerate()
        .map(|(i, s)| {
            if s.is_empty() || !zsec.contains(&i) {
                (s.clone(), false)
            } else {
                (tilecodec::zsec::write_zsec(s, tilecodec::zsec::ZSEC_BLOCK, 19), true)
            }
        })
        .collect();
    let mut out = m[..HEADER_LEN].to_vec();
    out[4..8].copy_from_slice(&tilecodec::mamaps::archive::TRANSIT_VERSION_ZSEC.to_le_bytes());
    out[8..12].copy_from_slice(&(DEVICE_SECTIONS as u32).to_le_bytes());
    let dir_at = out.len();
    out.resize(dir_at + DEVICE_SECTIONS * 16, 0);
    for (i, (s, z)) in stored.iter().enumerate() {
        while out.len() % 8 != 0 {
            out.push(0);
        }
        let off = out.len() as u64;
        out.extend_from_slice(s);
        let at = dir_at + i * 16;
        let flag = if *z { tilecodec::mamaps::archive::TRANSIT_ZSEC_FLAG } else { 0 };
        out[at..at + 8].copy_from_slice(&off.to_le_bytes());
        out[at + 8..at + 16].copy_from_slice(&(s.len() as u64 | flag).to_le_bytes());
    }
    std::fs::write(output, &out).map_err(|e| format!("write {output}: {e}"))?;
    println!(
        "transit_planet5: {} -> {} bytes (device sections raw {} B after thinning); ROUTE_TRIPS dropped {} B{}; shape points {} -> {} at {} m",
        m.len(),
        out.len(),
        raw_total,
        dropped_trips,
        if table_ok { "" } else { " (kept: a route's trip table disagrees)" },
        pts_in,
        pts_out,
        tol
    );
    Ok(())
}
