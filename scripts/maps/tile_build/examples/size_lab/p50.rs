// Included into size_lab (main.rs): the planet-50 GB candidate measurements.
//
//   size_lab p50-layers    IN STRIDE          marginal dictionary-zstd bytes per layer / table
//   size_lab p50-pyramid   IN STRIDE          z14 (z13) tiles reproducible by overzooming the parent
//   size_lab p50-buildings IN STRIDE          rectangle / shared-edge / height stats for buildings
//   size_lab p50-super     IN STRIDE G        2^G x 2^G superblocks vs per-tile dictionary frames
//   size_lab p50-arcs      IN STRIDE          shared polygon edges per tile (topology encoding)
//
// STRIDE samples every STRIDE-th unique body (1 = all). Sizes are zstd level 19 against the
// archive's own band dictionary, which is how planet5 stores bodies.

use std::cell::RefCell;
use std::collections::HashMap;
use tile_build::mamaps::dict::{
    LAYER_BOUNDARIES, LAYER_BUILDINGS, LAYER_JUNCTION, LAYER_LANDTYPE, LAYER_PLACES, LAYER_POI, LAYER_ROADS,
    LAYER_SAFETY, LAYER_TRANSIT,
};

fn layer_name(id: u8) -> &'static str {
    match id {
        x if x == LAYER_LANDTYPE => "landtype",
        x if x == LAYER_ROADS => "roads",
        x if x == LAYER_BOUNDARIES => "boundaries",
        x if x == LAYER_BUILDINGS => "buildings",
        x if x == LAYER_PLACES => "places",
        x if x == LAYER_POI => "poi",
        x if x == LAYER_TRANSIT => "transit",
        x if x == LAYER_JUNCTION => "junction",
        x if x == LAYER_SAFETY => "safety",
        _ => "other",
    }
}

fn ser11(body: &Body) -> Vec<u8> {
    let mut s = Scratch::default();
    match tile_build::mamaps::body::serialize_into_v11(body, &mut s) {
        Ok(b) => b.to_vec(),
        Err(_) => {
            let mut b = body.clone();
            b.convention = None;
            b.carriageways.clear();
            let mut s = Scratch::default();
            tile_build::mamaps::body::serialize_into_v11(&b, &mut s).expect("serialize v11").to_vec()
        }
    }
}

thread_local! {
    static CDICT: RefCell<Vec<Option<zstd::bulk::Compressor<'static>>>> = RefCell::new(Vec::new());
}

/// zstd-19 size of `raw` against the archive's dictionary for `z`'s band (C reference encoder).
fn dsize(a: &Arch, z: u8, raw: &[u8]) -> usize {
    let dicts = a.zd.as_ref().expect("a planet5 (dictionary) archive").dictionaries();
    let b = band(z).min(dicts.len() - 1);
    CDICT.with(|c| {
        let mut c = c.borrow_mut();
        if c.len() < dicts.len() {
            c.resize_with(dicts.len(), || None);
        }
        if c[b].is_none() {
            let d: &'static [u8] = Box::leak(dicts[b].clone().into_boxed_slice());
            c[b] = Some(zstd::bulk::Compressor::with_dictionary(19, d).expect("dict"));
        }
        c[b].as_mut().unwrap().compress(raw).expect("zstd").len()
    })
}

fn sample(ents: &[Ent], stride: usize, keep: impl Fn(&Ent) -> bool) -> Vec<Ent> {
    ents.iter().filter(|e| keep(e)).step_by(stride.max(1)).copied().collect()
}

// ------------------------------------------------------------------ layers (marginal bytes)

fn cmd_p50_layers(path: &str, stride: usize) {
    let a = open(path);
    let ents = entries(&a);
    let mut exact = [0u64; 5];
    for e in &ents {
        exact[band(e.z)] += e.len as u64;
    }
    let s = sample(&ents, stride, |_| true);
    let acc: Mutex<BTreeMap<(usize, String), (u64, u64)>> = Mutex::new(BTreeMap::new());
    s.par_iter().for_each(|e| {
        let raw = raw_of(&a, e);
        let body = Body::parse(&raw).expect("parse");
        let full = dsize(&a, e.z, &ser11(&body));
        let mut local: Vec<(String, u64, u64)> = vec![("_full".into(), full as u64, e.len as u64)];
        let mut drop = |k: String, b: Body| {
            let m = full.saturating_sub(dsize(&a, e.z, &ser11(&b)));
            local.push((k, m as u64, 0));
        };
        for l in &body.layers {
            let mut b = body.clone();
            b.layers.retain(|x| x.layer_id != l.layer_id);
            b.names.clear();
            let lid = l.layer_id;
            b.ids.retain(|t| t.0 != lid);
            b.turn_lanes.retain(|t| t.0 != lid);
            b.buildings.retain(|t| t.0 != lid);
            b.carriageways.retain(|t| t.0 != lid);
            b.region_links.retain(|t| t.0 != lid);
            if b.carriageways.is_empty() {
                b.convention = None;
            }
            // names stay (shared): measure geometry+records of the layer, not the name pool
            b.names = body.names.clone();
            drop(format!("layer:{}", layer_name(lid)), b);
        }
        let tables: [(&str, fn(&mut Body)); 7] = [
            ("table:names", |b| {
                b.names.clear();
                for l in &mut b.layers {
                    for f in &mut l.features {
                        f.name_idx = tile_build::mamaps::body::NAME_NONE;
                    }
                }
            }),
            ("table:ids", |b| b.ids.clear()),
            ("table:turn_lanes", |b| b.turn_lanes.clear()),
            ("table:buildings", |b| b.buildings.clear()),
            ("table:heightmap", |b| b.heightmap = None),
            ("table:carriageways", |b| {
                b.carriageways.clear();
                b.convention = None
            }),
            ("table:region_links", |b| b.region_links.clear()),
        ];
        for (k, f) in tables {
            let mut b = body.clone();
            f(&mut b);
            if std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| ser11(&b))).is_ok() {
                drop(k.to_string(), b);
            }
        }
        let mut g = acc.lock().unwrap();
        for (k, m, st) in local {
            let v = g.entry((band(e.z), k)).or_default();
            v.0 += m;
            v.1 += st;
        }
    });
    let g = acc.into_inner().unwrap();
    println!("band\tcomponent\tsample_marginal_B\tshare_of_full\tprojected_GB(archive)");
    for b in 0..5 {
        let full = g.get(&(b, "_full".to_string())).map(|v| v.0).unwrap_or(0);
        let stored = g.get(&(b, "_full".to_string())).map(|v| v.1).unwrap_or(0);
        if full == 0 {
            continue;
        }
        println!("{}\t_exact_stored\t{}\t-\t{:.3}", BANDS[b], stored, exact[b] as f64 / 1e9);
        for ((bb, k), v) in &g {
            if *bb != b || k == "_full" {
                continue;
            }
            let share = v.0 as f64 / full as f64;
            println!("{}\t{}\t{}\t{:.4}\t{:.3}", BANDS[b], k, v.0, share, share * exact[b] as f64 / 1e9);
        }
    }
}

// ------------------------------------------------------------------ pyramid (parent overzoom)

/// Geometry of every layer of `body` mapped into child (quadrant qx,qy) coordinates.
fn quad_layer(l: &Layer, qx: u64, qy: u64, ext: f64) -> (Vec<((f64, f64), (f64, f64))>, Vec<(f64, f64)>) {
    let (segs, pts) = layer_segs_pts(l);
    let ox = qx as f64 * ext / 2.0;
    let oy = qy as f64 * ext / 2.0;
    let m = |p: (f64, f64)| ((p.0 - ox) * 2.0, (p.1 - oy) * 2.0);
    (segs.into_iter().map(|(a, b)| (m(a), m(b))).collect(), pts.into_iter().map(m).collect())
}

fn inside(p: (f64, f64), ext: f64) -> bool {
    p.0 >= 0.0 && p.1 >= 0.0 && p.0 <= ext && p.1 <= ext
}

/// Max deviation (child units) between the child's features and the parent's quadrant, both
/// ways, for one layer; f64::INFINITY when something has no counterpart within 64 units.
fn layer_dev(child: &Layer, parent: Option<&Layer>, qx: u64, qy: u64, ext: f64) -> f64 {
    let (cs, cp) = layer_segs_pts(child);
    let (ps, pp) = match parent {
        Some(p) => quad_layer(p, qx, qy, ext),
        None => (Vec::new(), Vec::new()),
    };
    let cp: Vec<_> = cp.into_iter().filter(|p| inside(*p, ext)).collect();
    let pp: Vec<_> = pp.into_iter().filter(|p| inside(*p, ext)).collect();
    if cp.is_empty() && pp.is_empty() {
        return 0.0;
    }
    if cs.is_empty() || ps.is_empty() {
        return f64::INFINITY;
    }
    let gp = SegGrid::new(ps);
    let gc = SegGrid::new(cs);
    let mut worst = 0.0f64;
    for p in &cp {
        worst = worst.max(gp.nearest(*p, 64.0).unwrap_or(f64::INFINITY));
        if worst.is_infinite() {
            return worst;
        }
    }
    for p in &pp {
        worst = worst.max(gc.nearest(*p, 64.0).unwrap_or(f64::INFINITY));
        if worst.is_infinite() {
            return worst;
        }
    }
    worst
}

/// Max |child - bilinear(parent quadrant)| over the child's heightmap samples, metres.
fn dem_dev(child: &Heightmap, parent: &Heightmap, qx: u64, qy: u64) -> f64 {
    let cd = child.dim as usize;
    let pd = parent.dim as usize;
    if cd < 2 || pd < 2 {
        return 0.0;
    }
    let mut worst = 0.0f64;
    for r in 0..cd {
        for c in 0..cd {
            let fr = (qy as f64 + r as f64 / (cd - 1) as f64) / 2.0 * (pd - 1) as f64;
            let fc = (qx as f64 + c as f64 / (cd - 1) as f64) / 2.0 * (pd - 1) as f64;
            let v = bilin(&parent.samples, pd, fr, fc);
            worst = worst.max((child.samples[r * cd + c] as f64 - v).abs());
        }
    }
    worst
}

fn cmd_p50_pyramid(path: &str, stride: usize) {
    let a = open(path);
    let ti = TileIndex::build(&a);
    // Every addressed z13 and z14 tile (runs expanded), one sample per STRIDE.
    let mut tiles: Vec<(u64, u64, u32)> = Vec::new();
    for &(id, run, off, len) in &ti.ents {
        for k in 0..run as u64 {
            let (z, _, _) = tile_zxy(id + k);
            if z >= 13 {
                tiles.push((id + k, off, len));
            }
        }
    }
    let mut seen: HashMap<u64, u32> = HashMap::new();
    for t in &tiles {
        *seen.entry(t.1).or_default() += 1;
    }
    let tiles: Vec<_> = tiles.into_iter().step_by(stride.max(1)).collect();
    let tols = [0.5f64, 1.0, 2.0, 4.0, 8.0];
    // per zoom: tiles, unique-body bytes (each body's bytes divided by its references), and
    // per tolerance: qualifying tiles + bytes; plus reasons.
    #[derive(Default)]
    struct Z {
        tiles: u64,
        bytes: f64,
        q_tiles: [u64; 5],
        q_bytes: [f64; 5],
        reasons: BTreeMap<String, (u64, f64)>,
    }
    let acc: Mutex<[Z; 2]> = Mutex::new([Z::default(), Z::default()]);
    tiles.par_iter().for_each(|&(id, off, len)| {
        let (z, x, y) = tile_zxy(id);
        let share = len as f64 / *seen.get(&off).unwrap_or(&1) as f64;
        let child = body_at(&a, off, len);
        let pid = tilecodec::pmtiles::tile_id(z - 1, x / 2, y / 2);
        let parent = ti.find(pid).map(|(po, pl)| body_at(&a, po, pl));
        let ext = child.extent as f64;
        let (qx, qy) = (x % 2, y % 2);
        let mut reason: Option<String> = None;
        let mut worst = 0.0f64;
        let mut dem_ok = true;
        let mut z14only = false;
        match &parent {
            None => reason = Some("no parent".into()),
            Some(p) => {
                for l in &child.layers {
                    let lid = l.layer_id;
                    if l.features.is_empty() {
                        continue;
                    }
                    let in_parent = p.layer(lid).is_some_and(|pl| !pl.features.is_empty());
                    if !in_parent && [LAYER_BUILDINGS, LAYER_JUNCTION, LAYER_SAFETY, LAYER_POI].contains(&lid) {
                        z14only = true;
                    }
                }
                if child.carriageways.iter().any(|t| !t.1.is_empty()) {
                    z14only = true;
                }
                for l in &child.layers {
                    worst = worst.max(layer_dev(l, p.layer(l.layer_id), qx, qy, ext));
                }
                for l in &p.layers {
                    if child.layer(l.layer_id).is_none() {
                        worst = worst.max(layer_dev(&Layer::new(l.layer_id), Some(l), qx, qy, ext));
                    }
                }
                if let (Some(ch), Some(ph)) = (&child.heightmap, &p.heightmap) {
                    let px = m_per_unit(z, y) * 4096.0 / 256.0;
                    dem_ok = dem_dev(ch, ph, qx, qy) <= 0.5 * px;
                } else if child.heightmap.is_some() != p.heightmap.is_some() {
                    dem_ok = false;
                }
            }
        }
        // heightmap bytes of this tile if the DEM alone fell back to the parent
        let hm_share = if dem_ok && child.heightmap.is_some() {
            let mut nb = child.clone();
            nb.heightmap = None;
            let full = dsize(&a, z, &ser11(&child));
            (full.saturating_sub(dsize(&a, z, &ser11(&nb)))) as f64 / full.max(1) as f64 * share
        } else {
            0.0
        };
        let geo_key = if worst <= 2.0 { "geom<=2u" } else if worst <= 8.0 { "geom<=8u" } else { "geom>8u" };
        let k = format!("{}|dem_{}|{}", if z14only { "zonly" } else { "nozonly" }, if dem_ok { "ok" } else { "bad" }, geo_key);
        if reason.is_none() {
            reason = Some(k);
        }
        if !(reason.as_deref().unwrap_or("").starts_with("nozonly|dem_ok|geom<=2u")) {
            worst = f64::INFINITY;
        }
        {
            let mut g = acc.lock().unwrap();
            let e = g[(z - 13) as usize].reasons.entry("_dem_only_droppable_hm_bytes".into()).or_default();
            e.1 += hm_share;
        }
        let zi = (z - 13) as usize;
        let mut g = acc.lock().unwrap();
        let zz = &mut g[zi];
        zz.tiles += 1;
        zz.bytes += share;
        match reason {
            Some(r) => {
                let e = zz.reasons.entry(r).or_default();
                e.0 += 1;
                e.1 += share;
            }
            None => {
                for (i, t) in tols.iter().enumerate() {
                    if worst <= *t {
                        zz.q_tiles[i] += 1;
                        zz.q_bytes[i] += share;
                    }
                }

            }
        }
    });
    let g = acc.into_inner().unwrap();
    for (zi, zz) in g.iter().enumerate() {
        println!("z{}: sampled tiles {} body-bytes {:.0}", 13 + zi, zz.tiles, zz.bytes);
        for (i, t) in tols.iter().enumerate() {
            println!(
                "  qualify at <= {t} child units ({:.3} px at z{}): tiles {:.2}%  bytes {:.2}%",
                t * 256.0 / 4096.0 * 4.0,
                14 + zi + 2 - 1,
                100.0 * zz.q_tiles[i] as f64 / zz.tiles.max(1) as f64,
                100.0 * zz.q_bytes[i] / zz.bytes.max(1.0)
            );
        }
        for (r, (n, b)) in &zz.reasons {
            println!("  reason {r}: tiles {:.2}% bytes {:.2}%", 100.0 * *n as f64 / zz.tiles.max(1) as f64, 100.0 * b / zz.bytes.max(1.0));
        }
    }
}

// ------------------------------------------------------------------ buildings

fn cmd_p50_buildings(path: &str, stride: usize) {
    let a = open(path);
    let ents = entries(&a);
    let s = sample(&ents, stride, |e| e.z >= 13);
    #[derive(Default)]
    struct B {
        polys: u64,
        verts: u64,
        rects: u64,
        rect_verts: u64,
        shared_edges: u64,
        edges: u64,
        old_stream: u64,
        new_stream: u64,
        tab_old: u64,
        tab_q: u64,
    }
    let acc = Mutex::new(B::default());
    s.par_iter().for_each(|e| {
        let body = Body::parse(&raw_of(&a, e)).expect("parse");
        let Some(l) = body.layer(LAYER_BUILDINGS) else { return };
        let mut b = B::default();
        let mut old = Vec::new();
        let mut new = Vec::new();
        let zz = |v: i64| ((v << 1) ^ (v >> 63)) as u64;
        let put = |o: &mut Vec<u8>, mut v: u64| loop {
            let byte = (v & 0x7f) as u8;
            v >>= 7;
            if v == 0 {
                o.push(byte);
                break;
            }
            o.push(byte | 0x80);
        };
        let mut edges: HashMap<((i16, i16), (i16, i16)), u32> = HashMap::new();
        let (mut pcx, mut pcy) = (0i64, 0i64);
        for f in &l.features {
            let from = f.parts_offset as usize;
            for p in &l.parts[from..from + f.part_count as usize] {
                let mut v: Vec<(i16, i16)> = l.points(p).to_vec();
                if v.len() > 1 && v.first() == v.last() {
                    v.pop();
                }
                b.polys += 1;
                b.verts += v.len() as u64;
                // old stream: zigzag varint deltas from the part's first point
                let (mut px, mut py) = (0i64, 0i64);
                for q in &v {
                    put(&mut old, zz(q.0 as i64 - px));
                    put(&mut old, zz(q.1 as i64 - py));
                    px = q.0 as i64;
                    py = q.1 as i64;
                }
                for i in 0..v.len() {
                    let (p0, p1) = (v[i], v[(i + 1) % v.len()]);
                    let k = if p0 <= p1 { (p0, p1) } else { (p1, p0) };
                    *edges.entry(k).or_default() += 1;
                }
                // rectangle test: 4 corners, reconstructable from centre, w, h, angle within 1 unit
                let mut is_rect = false;
                if v.len() == 4 {
                    let f64p = |q: (i16, i16)| (q.0 as f64, q.1 as f64);
                    let (a0, a1, a2, a3) = (f64p(v[0]), f64p(v[1]), f64p(v[2]), f64p(v[3]));
                    let cx = (a0.0 + a1.0 + a2.0 + a3.0) / 4.0;
                    let cy = (a0.1 + a1.1 + a2.1 + a3.1) / 4.0;
                    let ang = (a1.1 - a0.1).atan2(a1.0 - a0.0);
                    let w = ((a1.0 - a0.0).hypot(a1.1 - a0.1) + (a2.0 - a3.0).hypot(a2.1 - a3.1)) / 2.0;
                    let h = ((a2.0 - a1.0).hypot(a2.1 - a1.1) + (a3.0 - a0.0).hypot(a3.1 - a0.1)) / 2.0;
                    // quantize: centre to 0.5 unit, w/h to 0.5 unit, angle to 1/4096 turn
                    let q = |x: f64, s: f64| (x / s).round() * s;
                    let (cx, cy, w, h) = (q(cx, 0.5), q(cy, 0.5), q(w, 0.5), q(h, 0.5));
                    let ang = q(ang, std::f64::consts::TAU / 4096.0);
                    let (c, s_) = (ang.cos(), ang.sin());
                    let corner = |sx: f64, sy: f64| (cx + (sx * w * c - sy * h * s_) / 2.0, cy + (sx * w * s_ + sy * h * c) / 2.0);
                    let rec = [corner(-1.0, -1.0), corner(1.0, -1.0), corner(1.0, 1.0), corner(-1.0, 1.0)];
                    let orig = [a0, a1, a2, a3];
                    let err = rec.iter().zip(orig.iter()).map(|(r, o)| (r.0 - o.0).hypot(r.1 - o.1)).fold(0.0, f64::max);
                    if err <= 1.0 {
                        is_rect = true;
                        b.rects += 1;
                        b.rect_verts += 4;
                        new.push(1);
                        put(&mut new, zz((cx * 2.0) as i64 - pcx));
                        put(&mut new, zz((cy * 2.0) as i64 - pcy));
                        pcx = (cx * 2.0) as i64;
                        pcy = (cy * 2.0) as i64;
                        put(&mut new, (w * 2.0) as u64);
                        put(&mut new, (h * 2.0) as u64);
                        let ai = ((ang / (std::f64::consts::TAU / 4096.0)).round() as i64).rem_euclid(4096) as u16;
                        new.extend_from_slice(&ai.to_le_bytes());
                    }
                }
                if !is_rect {
                    new.push(0);
                    let (mut px, mut py) = (0i64, 0i64);
                    for q in &v {
                        put(&mut new, zz(q.0 as i64 - px));
                        put(&mut new, zz(q.1 as i64 - py));
                        px = q.0 as i64;
                        py = q.1 as i64;
                    }
                }
            }
        }
        b.edges = edges.values().map(|&n| n as u64).sum();
        b.shared_edges = edges.values().filter(|&&n| n >= 2).map(|&n| n as u64).sum();
        b.old_stream = zstd_c(&old, 19) as u64;
        b.new_stream = zstd_c(&new, 19) as u64;
        // building attribute table: heights as stored vs quantized to 1 m (values are decimetres)
        if let Some((_, rows)) = body.buildings.iter().find(|t| t.0 == LAYER_BUILDINGS) {
            let mut t0 = Vec::new();
            let mut t1 = Vec::new();
            for r in rows {
                for v in [r.height, r.min_height, r.roof_height] {
                    t0.extend_from_slice(&v.to_le_bytes());
                    t1.extend_from_slice(&((v + 5) / 10 * 10).to_le_bytes());
                }
                t0.extend_from_slice(&[r.roof_shape, r.roof_direction, r.roof_orientation]);
                t1.extend_from_slice(&[r.roof_shape, r.roof_direction, r.roof_orientation]);
                t0.extend_from_slice(&r.building_colour.to_le_bytes());
                t0.extend_from_slice(&r.roof_colour.to_le_bytes());
                t1.extend_from_slice(&r.building_colour.to_le_bytes());
                t1.extend_from_slice(&r.roof_colour.to_le_bytes());
            }
            b.tab_old = zstd_c(&t0, 19) as u64;
            b.tab_q = zstd_c(&t1, 19) as u64;
        }
        let mut g = acc.lock().unwrap();
        g.polys += b.polys;
        g.verts += b.verts;
        g.rects += b.rects;
        g.rect_verts += b.rect_verts;
        g.shared_edges += b.shared_edges;
        g.edges += b.edges;
        g.old_stream += b.old_stream;
        g.new_stream += b.new_stream;
        g.tab_old += b.tab_old;
        g.tab_q += b.tab_q;
    });
    let g = acc.into_inner().unwrap();
    println!(
        "buildings: polygons {} vertices {} | rectangles (<=1 unit after centre/w/h/angle quantization) {:.1}% of polygons, {:.1}% of vertices",
        g.polys,
        g.verts,
        100.0 * g.rects as f64 / g.polys.max(1) as f64,
        100.0 * g.rect_verts as f64 / g.verts.max(1) as f64
    );
    println!(
        "  shared edges (terraces): {:.1}% of polygon edges appear in two buildings",
        100.0 * g.shared_edges as f64 / g.edges.max(1) as f64
    );
    println!(
        "  coordinate stream zstd-19 per tile: current {} B, rect-coded {} B ({:+.1}%)",
        g.old_stream,
        g.new_stream,
        100.0 * (g.new_stream as f64 / g.old_stream.max(1) as f64 - 1.0)
    );
    println!(
        "  attribute table zstd-19: as stored {} B, heights to 1 m {} B ({:+.1}%)",
        g.tab_old,
        g.tab_q,
        100.0 * (g.tab_q as f64 / g.tab_old.max(1) as f64 - 1.0)
    );
}

// ------------------------------------------------------------------ superblocks

fn cmd_p50_super(path: &str, stride: usize, g: u32) {
    let a = open(path);
    let ti = TileIndex::build(&a);
    // group key -> member (off, len) of unique bodies referenced once (shared bodies stay apart)
    let mut refs: HashMap<u64, u32> = HashMap::new();
    for &(_, run, off, _) in &ti.ents {
        *refs.entry(off).or_default() += run;
    }
    let mut groups: BTreeMap<(u8, u64, u64), Vec<(u64, u32)>> = BTreeMap::new();
    for &(id, run, off, len) in &ti.ents {
        if run != 1 || refs[&off] != 1 {
            continue;
        }
        let (z, x, y) = tile_zxy(id);
        if z < 10 {
            continue;
        }
        groups.entry((z, x >> g, y >> g)).or_default().push((off, len));
    }
    let keys: Vec<_> = groups.keys().copied().step_by(stride.max(1)).collect();
    #[derive(Default)]
    struct S {
        groups: u64,
        tiles: u64,
        stored: u64,
        separate: u64,
        together: u64,
        dec_ns: u64,
        dec_tile_ns: u64,
    }
    let acc: Mutex<BTreeMap<u8, S>> = Mutex::new(BTreeMap::new());
    keys.par_iter().for_each(|k| {
        let members = &groups[k];
        let raws: Vec<Vec<u8>> = members
            .iter()
            .map(|&(off, len)| {
                let e = Ent { z: k.0, off, len };
                raw_of(&a, &e)
            })
            .collect();
        let sep: usize = raws.iter().map(|r| dsize(&a, k.0, r)).sum();
        let cat: Vec<u8> = raws.concat();
        let dicts = a.zd.as_ref().unwrap().dictionaries();
        let d = &dicts[band(k.0).min(dicts.len() - 1)];
        let mut c = zstd::bulk::Compressor::with_dictionary(19, d).expect("dict");
        c.set_parameter(zstd::stream::raw::CParameter::WindowLog(22)).ok();
        let frame = c.compress(&cat).expect("zstd");
        let t = Instant::now();
        let mut dctx = zstd::bulk::Decompressor::with_dictionary(d).expect("d");
        let back = dctx.decompress(&frame, cat.len()).expect("dec");
        let dec = t.elapsed().as_nanos() as u64;
        assert_eq!(back.len(), cat.len());
        let t = Instant::now();
        let mut dctx2 = zstd::bulk::Decompressor::with_dictionary(d).expect("d");
        let one = c.compress(&raws[0]).expect("z");
        let _ = dctx2.decompress(&one, raws[0].len()).expect("dec1");
        let dec1 = t.elapsed().as_nanos() as u64;
        let mut g = acc.lock().unwrap();
        let s = g.entry(k.0).or_default();
        s.groups += 1;
        s.tiles += members.len() as u64;
        s.stored += members.iter().map(|m| m.1 as u64).sum::<u64>();
        s.separate += sep as u64;
        s.together += frame.len() as u64 + 8 * members.len() as u64;
        s.dec_ns += dec;
        s.dec_tile_ns += dec1;
    });
    for (z, s) in acc.into_inner().unwrap() {
        println!(
            "z{z} {}x{} superblocks: groups {} tiles {} | separate {} B together {} B ({:+.1}%) | decode per block {:.0} us vs one tile {:.0} us",
            1 << g,
            1 << g,
            s.groups,
            s.tiles,
            s.separate,
            s.together,
            100.0 * (s.together as f64 / s.separate.max(1) as f64 - 1.0),
            s.dec_ns as f64 / s.groups.max(1) as f64 / 1e3,
            s.dec_tile_ns as f64 / s.groups.max(1) as f64 / 1e3
        );
    }
}

// ------------------------------------------------------------------ shared arcs

fn cmd_p50_arcs(path: &str, stride: usize) {
    let a = open(path);
    let ents = entries(&a);
    let s = sample(&ents, stride, |e| e.z >= 10);
    let acc: Mutex<BTreeMap<(u8, String), (u64, u64, u64, u64)>> = Mutex::new(BTreeMap::new());
    s.par_iter().for_each(|e| {
        let body = Body::parse(&raw_of(&a, e)).expect("parse");
        let mut local: Vec<((u8, String), (u64, u64, u64, u64))> = Vec::new();
        // all polygon edges of the tile, any layer: an edge used twice is a shared arc
        let mut edges: HashMap<((i16, i16), (i16, i16)), u32> = HashMap::new();
        let mut rings: Vec<(u8, Vec<(i16, i16)>)> = Vec::new();
        for l in &body.layers {
            for f in &l.features {
                if f.geom_type != GEOM_POLYGON {
                    continue;
                }
                let from = f.parts_offset as usize;
                for p in &l.parts[from..from + f.part_count as usize] {
                    let mut v = l.points(p).to_vec();
                    if v.len() > 1 && v.first() == v.last() {
                        v.pop();
                    }
                    for i in 0..v.len() {
                        let (p0, p1) = (v[i], v[(i + 1) % v.len()]);
                        let k = if p0 <= p1 { (p0, p1) } else { (p1, p0) };
                        *edges.entry(k).or_default() += 1;
                    }
                    rings.push((l.layer_id, v));
                }
            }
        }
        for (lid, v) in &rings {
            let n = v.len() as u64;
            // a vertex is "on a shared arc" when both incident edges are shared
            let mut on = 0u64;
            for i in 0..v.len() {
                let prev = v[(i + v.len() - 1) % v.len()];
                let next = v[(i + 1) % v.len()];
                let k1 = if prev <= v[i] { (prev, v[i]) } else { (v[i], prev) };
                let k2 = if v[i] <= next { (v[i], next) } else { (next, v[i]) };
                if edges.get(&k1).copied().unwrap_or(0) >= 2 && edges.get(&k2).copied().unwrap_or(0) >= 2 {
                    on += 1;
                }
            }
            local.push(((band(e.z) as u8, layer_name(*lid).to_string()), (1, n, on, 0)));
        }
        let mut g = acc.lock().unwrap();
        for (k, v) in local {
            let x = g.entry(k).or_default();
            x.0 += v.0;
            x.1 += v.1;
            x.2 += v.2;
        }
    });
    println!("band\tlayer\trings\tvertices\ton_shared_arc\tshare");
    for ((b, l), v) in acc.into_inner().unwrap() {
        println!("{}\t{}\t{}\t{}\t{}\t{:.1}%", BANDS[b as usize], l, v.0, v.1, v.2, 100.0 * v.2 as f64 / v.1.max(1) as f64);
    }
}
