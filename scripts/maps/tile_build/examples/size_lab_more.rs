// Included into size_lab.rs: recompress / cmp / render / dem-pyramid modes.

/// Every leaf entry with its first tile id, sorted by tile id (not deduplicated).
struct TileIndex {
    ents: Vec<(u64, u32, u64, u32)>, // (tile_id, run_length, abs offset, len)
}

impl TileIndex {
    fn build(a: &Arch) -> TileIndex {
        let hdr = &a.hdr;
        let root = index::parse_root(&a.map[hdr.root_offset as usize..(hdr.root_offset + hdr.root_len as u64) as usize]).expect("root");
        let per: Vec<Vec<(u64, u32, u64, u32)>> = root
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
                    .map(|e| (re.base_tile_id + e.tile_id_lo as u64, e.run_length, hdr.data_offset + re.base_data_offset + e.offset_delta as u64, e.length))
                    .collect()
            })
            .collect();
        let mut ents: Vec<_> = per.into_iter().flatten().collect();
        ents.sort_by_key(|e| e.0);
        TileIndex { ents }
    }
    fn find(&self, id: u64) -> Option<(u64, u32)> {
        let i = self.ents.partition_point(|e| e.0 <= id);
        if i == 0 {
            return None;
        }
        let e = self.ents[i - 1];
        (id < e.0 + e.1 as u64).then_some((e.2, e.3))
    }
}

fn body_at(a: &Arch, off: u64, len: u32) -> Body {
    let stored = &a.map[off as usize..off as usize + len as usize];
    let raw = tile_build::mamaps::read::decompress(a.hdr.compression, stored).expect("inflate");
    Body::parse(&raw).expect("parse")
}

// ------------------------------------------------------------ recompress (exact, whole archive)

fn cmd_recompress(path: &str, out_dir: &str) {
    let a = open(path);
    let t0 = Instant::now();
    let ents = entries(&a);
    let total_now: u64 = ents.iter().map(|e| e.len as u64).sum();
    // Dictionaries per zoom band from a 1-in-N sample (<= 20k bodies per band).
    let mut by_band: Vec<Vec<Ent>> = vec![Vec::new(); 5];
    for e in &ents {
        by_band[band(e.z)].push(*e);
    }
    let dicts: Vec<Vec<u8>> = by_band
        .par_iter()
        .map(|v| {
            if v.len() < 64 {
                return Vec::new();
            }
            let step = (v.len() / 20_000).max(1);
            let samples: Vec<Vec<u8>> = v.iter().step_by(step).map(|e| raw_of(&a, e)[H..].to_vec()).collect();
            zstd::dict::from_samples(&samples, 112 * 1024).unwrap_or_default()
        })
        .collect();
    let dict_bytes: usize = dicts.iter().map(|d| d.len()).sum();
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
    #[derive(Default, Clone, Copy)]
    struct S {
        n: u64,
        now: u64,
        raw: u64,
        rs19: u64,
        rs19d: u64,
        c19: u64,
        c22: u64,
        t_now: u64,
        t_zstd: u64,
    }
    let acc: Mutex<[S; 15]> = Mutex::new([S::default(); 15]);
    ents.par_chunks(256).for_each(|chunk| {
        let mut loc = [S::default(); 15];
        let mut dec = ruzstd::decoding::FrameDecoder::new();
        for e in chunk {
            let stored = a.map[e.off as usize..e.off as usize + e.len as usize].to_vec();
            let t = Instant::now();
            let raw = tile_build::mamaps::read::decompress(a.hdr.compression, &stored).expect("inflate");
            let _ = Body::parse(&raw).expect("parse");
            let t_now = t.elapsed().as_nanos() as u64;
            let p = &raw[H..];
            let rs = zstd_rs(p, 19);
            let t = Instant::now();
            let mut out = vec![0u8; p.len()];
            dec.decode_all(&rs, &mut out).expect("ruzstd");
            let mut full = raw[..H].to_vec();
            full.extend_from_slice(&out);
            let _ = Body::parse(&full).expect("parse zstd");
            let t_z = t.elapsed().as_nanos() as u64;
            let s = &mut loc[e.z.min(14) as usize];
            s.n += 1;
            s.now += e.len as u64;
            s.raw += raw.len() as u64;
            s.rs19 += (H + rs.len()) as u64;
            s.rs19d += (H + match &rs_dicts[band(e.z)] {
                Some(d) => zstd_rs_dict(p, 19, d).len(),
                None => rs.len(),
            }) as u64;
            s.c19 += (H + zstd_c(p, 19)) as u64;
            s.c22 += (H + zstd_c(p, 22)) as u64;
            s.t_now += t_now;
            s.t_zstd += t_z;
        }
        let mut g = acc.lock().unwrap();
        for z in 0..15 {
            let (o, l) = (&mut g[z], loc[z]);
            o.n += l.n;
            o.now += l.now;
            o.raw += l.raw;
            o.rs19 += l.rs19;
            o.rs19d += l.rs19d;
            o.c19 += l.c19;
            o.c22 += l.c22;
            o.t_now += l.t_now;
            o.t_zstd += l.t_zstd;
        }
    });
    let g = acc.into_inner().unwrap();
    let mut out = format!(
        "archive {path}\nunique bodies {}  tile data now {:.4} GB (all, every body recompressed; exact)\ndictionaries: {} B total (5 zoom bands, 112 KiB each)\n",
        ents.len(),
        total_now as f64 / 1e9,
        dict_bytes
    );
    out += "zoom   bodies   now_MB   raw_MB  zstdRS19  zstdRS19+dict  zstdC19  zstdC22   decode_us(now)  decode_us(zstd)\n";
    let mut t = S::default();
    for z in 0..15 {
        let s = g[z];
        if s.n == 0 {
            continue;
        }
        out += &format!(
            "z{z:<3} {:>9} {:>8.1} {:>8.1} {:>9.1} {:>12.1} {:>9.1} {:>8.1} {:>12.1} {:>12.1}\n",
            s.n,
            s.now as f64 / 1e6,
            s.raw as f64 / 1e6,
            s.rs19 as f64 / 1e6,
            s.rs19d as f64 / 1e6,
            s.c19 as f64 / 1e6,
            s.c22 as f64 / 1e6,
            s.t_now as f64 / s.n as f64 / 1e3,
            s.t_zstd as f64 / s.n as f64 / 1e3
        );
        t.n += s.n;
        t.now += s.now;
        t.raw += s.raw;
        t.rs19 += s.rs19;
        t.rs19d += s.rs19d;
        t.c19 += s.c19;
        t.c22 += s.c22;
        t.t_now += s.t_now;
        t.t_zstd += s.t_zstd;
    }
    out += &format!(
        "TOTAL {:>9} {:>8.1} {:>8.1} {:>9.1} {:>12.1} {:>9.1} {:>8.1} {:>12.1} {:>12.1}\n",
        t.n,
        t.now as f64 / 1e6,
        t.raw as f64 / 1e6,
        t.rs19 as f64 / 1e6,
        (t.rs19d as f64 + dict_bytes as f64) / 1e6,
        t.c19 as f64 / 1e6,
        t.c22 as f64 / 1e6,
        t.t_now as f64 / t.n as f64 / 1e3,
        t.t_zstd as f64 / t.n as f64 / 1e3
    );
    out += &format!("RECOMPRESS_TSV\t{}\t{}\t{}\t{}\t{}\t{}\t{}\n", t.now, t.rs19, t.rs19d + dict_bytes as u64, t.c19, t.c22, t.t_now / t.n.max(1), t.t_zstd / t.n.max(1));
    out += &format!("(decode = decompress + Body::parse on one host core; {:.0}s)\n", t0.elapsed().as_secs_f64());
    print!("{out}");
    std::fs::write(format!("{out_dir}/recompress.txt"), &out).unwrap();
}

// ------------------------------------------------------------ geometry / DEM comparison

struct SegGrid {
    cell: f64,
    origin: f64,
    n: usize,
    cells: Vec<Vec<u32>>,
    segs: Vec<((f64, f64), (f64, f64))>,
}

impl SegGrid {
    fn new(segs: Vec<((f64, f64), (f64, f64))>) -> SegGrid {
        let (origin, span, n) = (-1024.0, 6144.0, 48usize);
        let cell = span / n as f64;
        let mut cells = vec![Vec::new(); n * n];
        let ci = |v: f64| (((v - origin) / cell).floor() as i64).clamp(0, n as i64 - 1) as usize;
        for (k, (a, b)) in segs.iter().enumerate() {
            let (x0, x1) = (ci(a.0.min(b.0)), ci(a.0.max(b.0)));
            let (y0, y1) = (ci(a.1.min(b.1)), ci(a.1.max(b.1)));
            for y in y0..=y1 {
                for x in x0..=x1 {
                    cells[y * n + x].push(k as u32);
                }
            }
        }
        SegGrid { cell, origin, n, cells, segs }
    }
    /// Distance to the nearest segment, or None beyond `cap`.
    fn nearest(&self, p: (f64, f64), cap: f64) -> Option<f64> {
        let n = self.n as i64;
        let cx = ((p.0 - self.origin) / self.cell).floor() as i64;
        let cy = ((p.1 - self.origin) / self.cell).floor() as i64;
        let mut best = f64::INFINITY;
        let rmax = (cap / self.cell).ceil() as i64 + 1;
        for r in 0..=rmax {
            if best.is_finite() && best <= (r as f64 - 1.0).max(0.0) * self.cell {
                break;
            }
            for y in cy - r..=cy + r {
                for x in cx - r..=cx + r {
                    if (y - cy).abs() != r && (x - cx).abs() != r {
                        continue;
                    }
                    if x < 0 || y < 0 || x >= n || y >= n {
                        continue;
                    }
                    for &k in &self.cells[(y * n + x) as usize] {
                        let (a, b) = self.segs[k as usize];
                        best = best.min(seg_dist_sq_f(p, a, b).sqrt());
                    }
                }
            }
        }
        (best <= cap).then_some(best)
    }
}

fn seg_dist_sq_f(p: (f64, f64), a: (f64, f64), b: (f64, f64)) -> f64 {
    let (dx, dy) = (b.0 - a.0, b.1 - a.1);
    let l2 = dx * dx + dy * dy;
    let t = if l2 == 0.0 { 0.0 } else { (((p.0 - a.0) * dx + (p.1 - a.1) * dy) / l2).clamp(0.0, 1.0) };
    let (qx, qy) = (a.0 + t * dx, a.1 + t * dy);
    (p.0 - qx).powi(2) + (p.1 - qy).powi(2)
}

fn layer_segs_pts(l: &Layer) -> (Vec<((f64, f64), (f64, f64))>, Vec<(f64, f64)>) {
    let mut segs = Vec::new();
    let mut pts = Vec::new();
    for f in &l.features {
        if f.geom_type == GEOM_POINT {
            continue;
        }
        let from = f.parts_offset as usize;
        for p in &l.parts[from..from + f.part_count as usize] {
            let v = l.points(p);
            for w in v.windows(2) {
                segs.push(((w[0].0 as f64, w[0].1 as f64), (w[1].0 as f64, w[1].1 as f64)));
            }
            if f.geom_type == GEOM_POLYGON && v.len() > 2 && v.first() != v.last() {
                let (a, b) = (v[v.len() - 1], v[0]);
                segs.push(((a.0 as f64, a.1 as f64), (b.0 as f64, b.1 as f64)));
            }
            pts.extend(v.iter().map(|q| (q.0 as f64, q.1 as f64)));
        }
    }
    (segs, pts)
}

fn tile_lat(z: u8, y: u64) -> f64 {
    let n = std::f64::consts::PI * (1.0 - 2.0 * (y as f64 + 0.5) / (1u64 << z) as f64);
    n.sinh().atan().to_degrees()
}

/// Metres per extent unit for tile (z, y).
fn m_per_unit(z: u8, y: u64) -> f64 {
    40_075_016.686 * tile_lat(z, y).to_radians().cos() / (1u64 << z) as f64 / 4096.0
}

#[derive(Default, Clone)]
struct DevAcc {
    n: u64,
    sum_m: f64,
    max_m: f64,
    max_units: f64,
    hist: Vec<u64>, // 0.01-unit buckets up to the cap (32 units)
    missing: u64,
    sym_max_u: f64,
}
impl DevAcc {
    fn add(&mut self, d_units: f64, mpu: f64) {
        if self.hist.is_empty() {
            self.hist = vec![0; 3_201];
        }
        let m = d_units * mpu;
        self.n += 1;
        self.sum_m += m;
        self.max_m = self.max_m.max(m);
        self.max_units = self.max_units.max(d_units);
        self.hist[((d_units * 100.0) as usize).min(3_200)] += 1;
    }
    fn merge(&mut self, o: &DevAcc) {
        if self.hist.is_empty() {
            self.hist = vec![0; 3_201];
        }
        if o.hist.is_empty() {
            self.missing += o.missing;
            return;
        }
        self.n += o.n;
        self.sum_m += o.sum_m;
        self.max_m = self.max_m.max(o.max_m);
        self.max_units = self.max_units.max(o.max_units);
        self.missing += o.missing;
        self.sym_max_u = self.sym_max_u.max(o.sym_max_u);
        for (a, b) in self.hist.iter_mut().zip(o.hist.iter()) {
            *a += b;
        }
    }
    fn pct(&self, q: f64) -> f64 {
        let target = (self.n as f64 * q).ceil() as u64;
        let mut c = 0;
        for (i, h) in self.hist.iter().enumerate() {
            c += h;
            if c >= target {
                return i as f64 / 100.0;
            }
        }
        32.0
    }
}

fn cmd_cmp(base: &str, cand: &str, per_zoom: usize, out_dir: &str) {
    let a = open(base);
    let b = open(cand);
    let ia = TileIndex::build(&a);
    let ib = TileIndex::build(&b);
    let mut by_z: BTreeMap<u8, Vec<(u64, u64, u32)>> = BTreeMap::new();
    for &(id, _, off, len) in &ia.ents {
        let (z, _, _) = tile_zxy(id);
        by_z.entry(z).or_default().push((id, off, len));
    }
    let mut sample = Vec::new();
    for (_, v) in &by_z {
        let step = (v.len() / per_zoom.max(1)).max(1);
        sample.extend(v.iter().step_by(step).copied());
    }
    let nl = a.dict.layers.len();
    // key (zoom, layer) -> deviations; DEM per zoom
    let acc: Mutex<(BTreeMap<(u8, u8), DevAcc>, BTreeMap<u8, (f64, f64, u64, u64, u64)>)> = Mutex::new((BTreeMap::new(), BTreeMap::new()));
    let cap = 32.0;
    sample.par_chunks(32).for_each(|chunk| {
        let mut dev: BTreeMap<(u8, u8), DevAcc> = BTreeMap::new();
        let mut dem: BTreeMap<u8, (f64, f64, u64, u64, u64)> = BTreeMap::new();
        for &(id, off, len) in chunk {
            let (z, _x, y) = tile_zxy(id);
            let Some((boff, blen)) = ib.find(id) else { continue };
            let ba = body_at(&a, off, len);
            let bb = body_at(&b, boff, blen);
            let mpu = m_per_unit(z, y);
            for lid in 0..nl as u8 {
                let (la, lb) = (ba.layer(lid), bb.layer(lid));
                let Some(la) = la else { continue };
                let (sa, pa) = layer_segs_pts(la);
                if pa.is_empty() {
                    continue;
                }
                let d = dev.entry((z, lid)).or_default();
                let Some(lb) = lb else {
                    d.missing += pa.len() as u64;
                    continue;
                };
                let (sb, pb) = layer_segs_pts(lb);
                if sb.is_empty() {
                    d.missing += pa.len() as u64;
                    continue;
                }
                let gb = SegGrid::new(sb);
                // A -> B: how far each original vertex now is from the candidate geometry.
                for p in &pa {
                    match gb.nearest(*p, cap) {
                        Some(dd) => d.add(dd, mpu),
                        None => d.missing += 1,
                    }
                }
                // B -> A (symmetric Hausdorff half): candidate vertices against the original.
                if !sa.is_empty() {
                    let ga = SegGrid::new(sa);
                    for p in &pb {
                        if let Some(dd) = ga.nearest(*p, cap) {
                            d.sym_max_u = d.sym_max_u.max(dd);
                        }
                    }
                }
            }
            // DEM: both bilinear surfaces on a 33x33 lattice.
            let e = dem.entry(z).or_insert((0.0, 0.0, 0, 0, 0));
            match (&ba.heightmap, &bb.heightmap) {
                (Some(ha), Some(hb)) => {
                    for r in 0..33 {
                        for c in 0..33 {
                            let (fr, fc) = (r as f64 / 32.0, c as f64 / 32.0);
                            let d = (bilin(&ha.samples, ha.dim as usize, fr, fc) - bilin(&hb.samples, hb.dim as usize, fr, fc)).abs();
                            e.0 = e.0.max(d);
                            e.1 += d * d;
                            e.2 += 1;
                        }
                    }
                }
                (Some(_), None) | (None, Some(_)) => e.3 += 1,
                _ => {}
            }
            e.4 += 1;
        }
        let mut g = acc.lock().unwrap();
        for (k, v) in dev {
            g.0.entry(k).or_default().merge(&v);
        }
        for (k, v) in dem {
            let e = g.1.entry(k).or_insert((0.0, 0.0, 0, 0, 0));
            e.0 = e.0.max(v.0);
            e.1 += v.1;
            e.2 += v.2;
            e.3 += v.3;
            e.4 += v.4;
        }
    });
    let (dev, dem) = acc.into_inner().unwrap();
    let mut out = format!("cmp base={base}\n    cand={cand}\n(sampled {} tiles; deviation = distance from each base vertex to the candidate's geometry in the same tile/layer, in extent units (1/4096 tile) and metres; vertices with nothing within 32 units = dropped features; Hausdorff_u = max(base->cand, cand->base) over matched geometry. px: 1 unit = 1/16 px at the tile's own zoom (256-px tiles), 1/4 px for a z14 tile at z16, 1 px at z18)\n", sample.len());
    out += "band     layer        verts    mean_m   max_m  p50_u  p99_u p99.9_u  max_u  hausdorff_u  dropped%\n";
    let bands: [(&str, std::ops::RangeInclusive<u8>); 5] = [("z0-9", 0..=9), ("z10-11", 10..=11), ("z12", 12..=12), ("z13", 13..=13), ("z14", 14..=14)];
    let mut tsv = String::from("band\tlayer\tverts\tmean_m\tmax_m\tp99_u\tp999_u\tmax_u\thausdorff_u\tdropped_pct\n");
    for (bn, r) in bands.iter() {
        for lid in 0..nl as u8 {
            let mut d = DevAcc::default();
            for z in r.clone() {
                if let Some(v) = dev.get(&(z, lid)) {
                    d.merge(v);
                }
            }
            if d.n == 0 {
                continue;
            }
            let name = a.dict.layers[lid as usize].clone();
            let miss = 100.0 * d.missing as f64 / (d.n + d.missing).max(1) as f64;
            out += &format!(
                "{bn:<8} {name:<11} {:>9} {:>8.3} {:>7.2} {:>6.2} {:>6.2} {:>7.2} {:>6.2} {:>11.2} {:>9.4}\n",
                d.n,
                d.sum_m / d.n as f64,
                d.max_m,
                d.pct(0.5),
                d.pct(0.99),
                d.pct(0.999),
                d.max_units,
                d.max_units.max(d.sym_max_u),
                miss
            );
            tsv += &format!("{bn}\t{name}\t{}\t{:.4}\t{:.3}\t{:.3}\t{:.3}\t{:.3}\t{:.3}\t{:.4}\n", d.n, d.sum_m / d.n as f64, d.max_m, d.pct(0.99), d.pct(0.999), d.max_units, d.max_units.max(d.sym_max_u), miss);
        }
    }
    out += "\nDEM (candidate vs base surface, 33x33 lattice per tile; metres)\n";
    let (mut gm, mut gs, mut gn) = (0.0f64, 0.0f64, 0u64);
    for (z, (m, ss, n, mism, tiles)) in &dem {
        if *n == 0 && *mism == 0 {
            continue;
        }
        gm = gm.max(*m);
        gs += ss;
        gn += n;
        out += &format!("  z{z:<2} tiles {tiles:>7} max {m:>8.2} rms {:>7.3} presence_mismatch {mism}\n", (ss / (*n).max(1) as f64).sqrt());
    }
    out += &format!("  all max {gm:.2} rms {:.3}\n", (gs / gn.max(1) as f64).sqrt());
    tsv += &format!("DEM\tall\t{gn}\t\t\t{gm:.3}\t\t\t{:.4}\n", (gs / gn.max(1) as f64).sqrt());
    print!("{out}");
    std::fs::write(format!("{out_dir}/cmp.txt"), &out).unwrap();
    std::fs::write(format!("{out_dir}/cmp.tsv"), &tsv).unwrap();
}

// ------------------------------------------------------------ render

struct Canvas {
    w: usize,
    h: usize,
    px: Vec<[u8; 3]>,
}

impl Canvas {
    fn new(w: usize, h: usize, bg: [u8; 3]) -> Canvas {
        Canvas { w, h, px: vec![bg; w * h] }
    }
    fn blend(&mut self, x: i64, y: i64, c: [u8; 3], a: f32) {
        if x < 0 || y < 0 || x as usize >= self.w || y as usize >= self.h {
            return;
        }
        let p = &mut self.px[y as usize * self.w + x as usize];
        for i in 0..3 {
            p[i] = (p[i] as f32 * (1.0 - a) + c[i] as f32 * a) as u8;
        }
    }
    /// Even-odd scanline fill of a set of rings (pixel coords).
    fn fill(&mut self, rings: &[Vec<(f64, f64)>], c: [u8; 3], a: f32) {
        let (mut y0, mut y1) = (f64::INFINITY, f64::NEG_INFINITY);
        for r in rings {
            for p in r {
                y0 = y0.min(p.1);
                y1 = y1.max(p.1);
            }
        }
        let ys = (y0.floor().max(0.0)) as i64;
        let ye = (y1.ceil().min(self.h as f64 - 1.0)) as i64;
        let mut xs: Vec<f64> = Vec::new();
        for y in ys..=ye {
            let fy = y as f64 + 0.5;
            xs.clear();
            for r in rings {
                let n = r.len();
                for i in 0..n {
                    let (p, q) = (r[i], r[(i + 1) % n]);
                    if (p.1 <= fy) != (q.1 <= fy) {
                        xs.push(p.0 + (fy - p.1) / (q.1 - p.1) * (q.0 - p.0));
                    }
                }
            }
            xs.sort_by(|a, b| a.partial_cmp(b).unwrap());
            for pair in xs.chunks(2) {
                if pair.len() < 2 {
                    break;
                }
                let (xa, xb) = ((pair[0].round().max(0.0)) as i64, (pair[1].round().min(self.w as f64)) as i64);
                for x in xa..xb {
                    self.blend(x, y, c, a);
                }
            }
        }
    }
    fn line(&mut self, p: (f64, f64), q: (f64, f64), wpx: f64, c: [u8; 3]) {
        let len = ((q.0 - p.0).powi(2) + (q.1 - p.1).powi(2)).sqrt();
        let steps = (len * 2.0).ceil().max(1.0) as usize;
        let r = (wpx / 2.0).max(0.5);
        let ri = r.ceil() as i64;
        for s in 0..=steps {
            let t = s as f64 / steps as f64;
            let (x, y) = (p.0 + (q.0 - p.0) * t, p.1 + (q.1 - p.1) * t);
            for dy in -ri..=ri {
                for dx in -ri..=ri {
                    if ((dx * dx + dy * dy) as f64) <= r * r {
                        self.blend(x.round() as i64 + dx, y.round() as i64 + dy, c, 1.0);
                    }
                }
            }
        }
    }
}

fn kind_style(name: &str) -> Option<[u8; 3]> {
    let n = name;
    Some(if n.contains("water") || n == "lake" || n == "river" || n == "bay" || n == "strait" || n == "reservoir" || n == "ocean" || n == "canal" || n == "stream" || n == "basin" {
        [170, 211, 223]
    } else if n.contains("forest") || n.contains("wood") || n == "nature_reserve" || n == "national_park" || n == "protected_area" {
        [173, 209, 158]
    } else if n.contains("grass") || n == "park" || n == "meadow" || n == "golf_course" || n == "recreation_ground" || n == "cemetery" || n == "scrub" {
        [205, 235, 176]
    } else if n == "farmland" || n == "orchard" || n == "vineyard" || n == "allotments" {
        [238, 240, 213]
    } else if n == "residential" || n == "commercial" || n == "industrial" || n == "retail" {
        [224, 223, 223]
    } else if n == "sand" || n == "beach" || n == "bare_rock" || n == "glacier" || n == "wetland" || n == "quarry" {
        [242, 239, 233]
    } else {
        return None;
    })
}

fn render_body(body: &Body, dict: &Dictionary, z: u8, crop: (f64, f64, f64), size: usize) -> Canvas {
    let mut cv = Canvas::new(size, size, [242, 239, 233]);
    let (cx, cy, cs) = crop;
    let s = size as f64 / cs;
    let to_px = |p: &(i16, i16)| ((p.0 as f64 - cx) * s, (p.1 as f64 - cy) * s);
    let lid = |n: &str| dict.layers.iter().position(|l| l == n).map(|i| i as u8);
    // Hillshade first (from the heightmap), as a multiply over the background.
    if let Some(hm) = &body.heightmap {
        let d = hm.dim as usize;
        let mpu_px = 40_075_016.686 / (1u64 << z) as f64 / 4096.0 / s; // ~metres per pixel
        for py in 0..size {
            for px in 0..size {
                let (ux, uy) = ((cx + px as f64 / s) / 4096.0, (cy + py as f64 / s) / 4096.0);
                if !(0.0..=1.0).contains(&ux) || !(0.0..=1.0).contains(&uy) {
                    continue;
                }
                let e = 1.0 / 4096.0 / s * 2.0;
                let hx = bilin(&hm.samples, d, uy, (ux + e).min(1.0)) - bilin(&hm.samples, d, uy, (ux - e).max(0.0));
                let hy = bilin(&hm.samples, d, (uy + e).min(1.0), ux) - bilin(&hm.samples, d, (uy - e).max(0.0), ux);
                let (gx, gy) = (3.0 * hx / (4.0 * mpu_px), 3.0 * hy / (4.0 * mpu_px));
                let shade = ((-gx * 0.7 - gy * 0.7 + 1.0) / (1.0 + gx * gx + gy * gy).sqrt()).clamp(0.0, 1.5);
                let v = (shade * 170.0).clamp(0.0, 255.0) as u8;
                cv.blend(px as i64, py as i64, [v, v, v], 0.5);
            }
        }
    }
    let draw_polys = |cv: &mut Canvas, l: &Layer, colour: &dyn Fn(u16) -> Option<[u8; 3]>, alpha: f32| {
        for f in &l.features {
            if f.geom_type != GEOM_POLYGON {
                continue;
            }
            let Some(c) = colour(f.kind) else { continue };
            let from = f.parts_offset as usize;
            let rings: Vec<Vec<(f64, f64)>> = l.parts[from..from + f.part_count as usize].iter().map(|p| l.points(p).iter().map(to_px).collect()).collect();
            cv.fill(&rings, c, alpha);
        }
    };
    if let Some(l) = lid("landtype").and_then(|i| body.layer(i)) {
        draw_polys(&mut cv, l, &|k| kind_style(dict.kind_name(k).unwrap_or("")), 0.85);
        for f in &l.features {
            if f.geom_type == 1 {
                let from = f.parts_offset as usize;
                for p in &l.parts[from..from + f.part_count as usize] {
                    for w in l.points(p).windows(2) {
                        cv.line(to_px(&w[0]), to_px(&w[1]), 1.5, [130, 180, 210]);
                    }
                }
            }
        }
    }
    if let Some(l) = lid("buildings").and_then(|i| body.layer(i)) {
        draw_polys(&mut cv, l, &|_| Some([200, 190, 180]), 1.0);
        for f in &l.features {
            let from = f.parts_offset as usize;
            for p in &l.parts[from..from + f.part_count as usize] {
                let v = l.points(p);
                for i in 0..v.len() {
                    cv.line(to_px(&v[i]), to_px(&v[(i + 1) % v.len()]), 1.0, [150, 140, 130]);
                }
            }
        }
    }
    if let Some(l) = lid("boundaries").and_then(|i| body.layer(i)) {
        for f in &l.features {
            let from = f.parts_offset as usize;
            for p in &l.parts[from..from + f.part_count as usize] {
                for w in l.points(p).windows(2) {
                    cv.line(to_px(&w[0]), to_px(&w[1]), 1.5, [160, 100, 170]);
                }
            }
        }
    }
    if let Some(l) = lid("roads").and_then(|i| body.layer(i)) {
        for pass in 0..2 {
            for f in &l.features {
                let name = dict.kind_name(f.kind).unwrap_or("");
                let major = name.contains("highway") || name.contains("major") || name.contains("motorway") || name.contains("trunk") || name.contains("primary");
                let w = if major { 5.0 } else if name.contains("path") || name.contains("foot") || name.contains("track") { 1.2 } else { 3.0 };
                let (c, ww) = if pass == 0 { ([120, 120, 120], w + 1.5) } else if major { ([252, 214, 164], w) } else { ([255, 255, 255], w) };
                let from = f.parts_offset as usize;
                for p in &l.parts[from..from + f.part_count as usize] {
                    for wv in l.points(p).windows(2) {
                        cv.line(to_px(&wv[0]), to_px(&wv[1]), ww, c);
                    }
                }
            }
        }
    }
    cv
}

fn write_png(path: &str, cv: &Canvas) {
    let f = File::create(path).expect("png create");
    let mut enc = png::Encoder::new(std::io::BufWriter::new(f), cv.w as u32, cv.h as u32);
    enc.set_color(png::ColorType::Rgb);
    enc.set_depth(png::BitDepth::Eight);
    let mut w = enc.write_header().expect("png header");
    let data: Vec<u8> = cv.px.iter().flat_map(|p| p.iter().copied()).collect();
    w.write_image_data(&data).expect("png data");
}

/// `render OUT.png Z X Y CROP_Q ARCH [ARCH...]`: side-by-side renders of one tile from each
/// archive. CROP_Q = 1 renders the whole tile (its native zoom); 4 renders the top-left-of-centre
/// quarter-of-a-quarter, i.e. what the z+2 view of that tile shows (overzoom), and so on.
fn cmd_render(args: &[String]) {
    let out = &args[2];
    let z: u8 = args[3].parse().unwrap();
    let x: u64 = args[4].parse().unwrap();
    let y: u64 = args[5].parse().unwrap();
    let q: f64 = args[6].parse().unwrap();
    let archs = &args[7..];
    let size = 768usize;
    let cs = 4096.0 / q;
    let crop = ((4096.0 - cs) / 2.0, (4096.0 - cs) / 2.0, cs);
    let mut panels = Vec::new();
    for p in archs {
        let a = open(p);
        let ix = TileIndex::build(&a);
        let id = tilecodec::pmtiles::tile_id(z, x, y);
        let cv = match ix.find(id) {
            Some((off, len)) => render_body(&body_at(&a, off, len), &a.dict, z, crop, size),
            None => Canvas::new(size, size, [255, 0, 255]),
        };
        panels.push(cv);
    }
    let gap = 8;
    let mut all = Canvas::new(size * panels.len() + gap * (panels.len() - 1), size, [255, 255, 255]);
    for (i, p) in panels.iter().enumerate() {
        for yy in 0..size {
            for xx in 0..size {
                all.px[yy * all.w + i * (size + gap) + xx] = p.px[yy * size + xx];
            }
        }
    }
    write_png(out, &all);
    println!("wrote {out}");
}

// ------------------------------------------------------------ DEM pyramid redundancy

/// `dem-pyramid ARCH TOL_M`: how much the heightmaps would shrink if a tile whose grid is within
/// TOL_M of its nearest kept ancestor's (bilinearly upsampled) surface stored none and the
/// reader fell back to that ancestor (a format change: the shipped reader draws such a tile
/// flat). Measured exactly over every body: each body re-encoded without its heightmap and
/// zstd-19 compressed, against the same body with it.
fn cmd_dem_pyramid(path: &str, tol: f64, out_dir: &str) {
    let a = open(path);
    let ix = TileIndex::build(&a);
    // Kept heightmaps per tile id, processed zoom by zoom (parents first).
    let mut kept: std::collections::HashMap<u64, (u8, u64, u64)> = std::collections::HashMap::new(); // tile -> ancestor (z,x,y) whose grid serves it
    let mut grids: std::collections::HashMap<u64, Heightmap> = std::collections::HashMap::new();
    let mut by_z: BTreeMap<u8, Vec<(u64, u64, u32)>> = BTreeMap::new();
    for &(id, _, off, len) in &ix.ents {
        let (z, _, _) = tile_zxy(id);
        by_z.entry(z).or_default().push((id, off, len));
    }
    let mut out = format!("dem-pyramid {path} tol={tol} m\n zoom  with_dem  dropped  saved_MB(zstd19)\n");
    let (mut tot_saved, mut tot_drop, mut tot_dem) = (0i64, 0u64, 0u64);
    for (z, v) in &by_z {
        let res: Vec<(u64, Option<Heightmap>, Option<(u8, u64, u64)>, i64)> = v
            .par_iter()
            .map(|&(id, off, len)| {
                let (_, x, y) = tile_zxy(id);
                let body = body_at(&a, off, len);
                let Some(hm) = body.heightmap.clone() else { return (id, None, None, 0) };
                if *z == 0 {
                    return (id, Some(hm), None, 0);
                }
                // Serving ancestor: the parent's own grid, or whatever serves the parent.
                let pid = tilecodec::pmtiles::tile_id(z - 1, x / 2, y / 2);
                let anc = kept.get(&pid).copied().unwrap_or((z - 1, x / 2, y / 2));
                let aid = tilecodec::pmtiles::tile_id(anc.0, anc.1, anc.2);
                let Some(ag) = grids.get(&aid) else { return (id, Some(hm), None, 0) };
                let k = 1u64 << (z - anc.0);
                let (ox, oy) = ((x - anc.1 * k) as f64, (y - anc.2 * k) as f64);
                let d = hm.dim as usize;
                let mut mx = 0.0f64;
                for r in 0..d {
                    for c in 0..d {
                        let fr = (oy + r as f64 / (d - 1) as f64) / k as f64;
                        let fc = (ox + c as f64 / (d - 1) as f64) / k as f64;
                        let e = (bilin(&ag.samples, ag.dim as usize, fr, fc) - hm.samples[r * d + c] as f64).abs();
                        mx = mx.max(e);
                    }
                }
                if mx <= tol {
                    let mut nb = body.clone();
                    nb.heightmap = None;
                    let with = zstd_rs(&ser(&body)[H..], 19).len() as i64;
                    let without = zstd_rs(&ser(&nb)[H..], 19).len() as i64;
                    (id, None, Some(anc), with - without)
                } else {
                    (id, Some(hm), None, 0)
                }
            })
            .collect();
        let (mut saved, mut dropped, mut with_dem) = (0i64, 0u64, 0u64);
        for (id, hm, anc, s) in res {
            if let Some(h) = hm {
                grids.insert(id, h);
                with_dem += 1;
            }
            if let Some(an) = anc {
                kept.insert(id, an);
                dropped += 1;
                with_dem += 1;
                saved += s;
            }
        }
        out += &format!(" z{z:<3} {with_dem:>9} {dropped:>8} {:>10.2}\n", saved as f64 / 1e6);
        tot_saved += saved;
        tot_drop += dropped;
        tot_dem += with_dem;
    }
    out += &format!(" all  {tot_dem:>9} {tot_drop:>8} {:>10.2}\nDEMPYR_TSV\t{tol}\t{tot_dem}\t{tot_drop}\t{tot_saved}\n", tot_saved as f64 / 1e6);
    print!("{out}");
    std::fs::write(format!("{out_dir}/dem_pyramid_{tol}.txt"), &out).unwrap();
}

// ------------------------------------------------------------ transit pack analysis

fn rd_u32(b: &[u8], at: usize) -> u32 {
    u32::from_le_bytes(b[at..at + 4].try_into().unwrap())
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

fn uvar_len(v: u64) -> usize {
    let mut n = 1;
    let mut v = v >> 7;
    while v > 0 {
        n += 1;
        v >>= 7;
    }
    n
}

/// `transit WORLD.transit`: exact sizes of SHAPE_COORDS after error-bounded thinning (stop
/// vertices kept, so ROUTE_STOP_SHAPE stays valid after an index remap) at several
/// tolerances, with and without a 1e-6 deg grid; and what EXCEPTIONS would take packed.
fn cmd_transit(path: &str, out_dir: &str) {
    let f = File::open(path).expect("open");
    let m = unsafe { memmap2::Mmap::map(&f).expect("mmap") };
    let nsec = rd_u32(&m, 8) as usize;
    let route_count = rd_u32(&m, 16) as usize;
    let sec = |i: usize| -> &[u8] {
        let at = 80 + i * 16;
        let off = u64::from_le_bytes(m[at..at + 8].try_into().unwrap()) as usize;
        let len = u64::from_le_bytes(m[at + 8..at + 16].try_into().unwrap()) as usize;
        &m[off..off + len]
    };
    let mut out = format!("transit {path}: {nsec} sections, {route_count} routes\n");
    let shapes = sec(20);
    let shape_idx = sec(21);
    let routes = sec(2);
    let rss = sec(22);
    // Protected vertices per blob offset: every stop's vertex of every route using it.
    let mut prot: std::collections::HashMap<u32, Vec<u32>> = std::collections::HashMap::new();
    for r in 0..route_count {
        let off = rd_u32(shape_idx, r * 4);
        if off == u32::MAX {
            continue;
        }
        let n_stops = rd_u32(routes, r * 32 + 16) as usize;
        let first = rd_u32(routes, r * 32 + 20) as usize;
        let e = prot.entry(off).or_default();
        for k in 0..n_stops {
            let v = rd_u32(rss, (first + k) * 4);
            if v != u32::MAX {
                e.push(v);
            }
        }
    }
    let offs: Vec<u32> = {
        let mut v: Vec<u32> = prot.keys().copied().collect();
        v.sort();
        v
    };
    let tols = [0.0f64, 0.5, 1.0, 2.0, 5.0];
    // (bytes e7, bytes 1e-6 grid, points) per tolerance
    let res: Vec<Vec<(u64, u64, u64)>> = offs
        .par_iter()
        .map(|&off| {
            let mut pos = off as usize;
            let n = rd_u32(shapes, pos) as usize;
            pos += 4;
            let (mut la, mut lo) = (0i64, 0i64);
            let mut pts = Vec::with_capacity(n);
            for _ in 0..n {
                let dla = uvar(shapes, &mut pos);
                let dlo = uvar(shapes, &mut pos);
                la += ((dla >> 1) as i64) ^ -((dla & 1) as i64);
                lo += ((dlo >> 1) as i64) ^ -((dlo & 1) as i64);
                pts.push((la, lo));
            }
            let mut keep_base = vec![false; n];
            if n > 0 {
                keep_base[0] = true;
                keep_base[n - 1] = true;
            }
            for &v in &prot[&off] {
                if (v as usize) < n {
                    keep_base[v as usize] = true;
                }
            }
            let lat0 = if n > 0 { pts[0].0 as f64 * 1e-7 } else { 0.0 };
            let kx = 0.011_131_949 * lat0.to_radians().cos();
            let xy: Vec<(f64, f64)> = pts.iter().map(|p| (p.1 as f64 * kx, p.0 as f64 * 0.011_131_949)).collect();
            tols.iter()
                .map(|&tol| {
                    let mut keep = keep_base.clone();
                    if tol > 0.0 && n > 2 {
                        // DP between consecutive protected vertices.
                        let anchors: Vec<usize> = (0..n).filter(|&i| keep_base[i]).collect();
                        for w in anchors.windows(2) {
                            let mut stack = vec![(w[0], w[1])];
                            while let Some((a, b)) = stack.pop() {
                                if b <= a + 1 {
                                    continue;
                                }
                                let (mut worst, mut wi) = (tol * tol, usize::MAX);
                                for i in a + 1..b {
                                    let d = seg_dist_sq_f(xy[i], xy[a], xy[b]);
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
                    } else {
                        keep.iter_mut().for_each(|k| *k = true);
                    }
                    let (mut b7, mut b6, mut np) = (4u64, 4u64, 0u64);
                    let (mut p7, mut p6) = ((0i64, 0i64), (0i64, 0i64));
                    for i in 0..n {
                        if !keep[i] {
                            continue;
                        }
                        let (a, o) = pts[i];
                        let z = |v: i64| ((v << 1) ^ (v >> 63)) as u64;
                        b7 += (uvar_len(z(a - p7.0)) + uvar_len(z(o - p7.1))) as u64;
                        p7 = (a, o);
                        let q = ((a as f64 / 10.0).round() as i64, (o as f64 / 10.0).round() as i64);
                        b6 += (uvar_len(z(q.0 - p6.0)) + uvar_len(z(q.1 - p6.1))) as u64;
                        p6 = q;
                        np += 1;
                    }
                    (b7, b6, np)
                })
                .collect()
        })
        .collect();
    out += &format!("SHAPE_COORDS now {} B, {} unique blobs\n tol_m   bytes_e7    bytes_1e-6  points\n", shapes.len(), offs.len());
    for (ti, tol) in tols.iter().enumerate() {
        let (b7, b6, np) = res.iter().fold((0u64, 0u64, 0u64), |a, r| (a.0 + r[ti].0, a.1 + r[ti].1, a.2 + r[ti].2));
        out += &format!(" {tol:<5} {b7:>12} {b6:>12} {np:>10}\nTRANSIT_SHAPES_TSV\t{tol}\t{b7}\t{b6}\t{np}\n");
    }
    // EXCEPTIONS packed: per service CSR (EXCEPTIONS_IDX) => drop service_idx, varint date
    // delta (days) + added bit in the low bit.
    let exc = sec(12);
    let rows = exc.len() / 12;
    let mut packed = 0u64;
    let mut prev_svc = u32::MAX;
    let mut prev_date = 0i64;
    let days = |d: u32| -> i64 {
        // YYYYMMDD -> day number (approx, monotone within a service)
        let (y, mo, da) = ((d / 10000) as i64, ((d / 100) % 100) as i64, (d % 100) as i64);
        y * 372 + mo * 31 + da
    };
    for r in 0..rows {
        let svc = rd_u32(exc, r * 12);
        let date = days(rd_u32(exc, r * 12 + 4));
        let added = rd_u32(exc, r * 12 + 8) as i64;
        let delta = if svc != prev_svc { date } else { date - prev_date };
        packed += uvar_len(((delta.max(0) as u64) << 1) | added as u64) as u64;
        prev_svc = svc;
        prev_date = date;
    }
    out += &format!("EXCEPTIONS now {} B ({} rows x 12 B); packed (CSR keeps service, varint date delta + added bit) {} B\nTRANSIT_EXC_TSV\t{}\t{}\n", exc.len(), rows, packed, exc.len(), packed);
    out += &format!("ROUTE_TRIPS (varint stream, unread when ROUTE_TRIP_RECS is present) {} B\nTRANSIT_RT_TSV\t{}\n", sec(4).len(), sec(4).len());
    print!("{out}");
    std::fs::write(format!("{out_dir}/transit.txt"), &out).unwrap();
}

// ------------------------------------------------------------ ZBLK re-encode

/// `zblk FILE`: exact size of a ZBLK section re-encoded at zstd 10/19/22 with 4096- and
/// 16384-record blocks, plus ruzstd decode time per block (what one cold lookup costs).
fn cmd_zblk(path: &str) {
    let f = File::open(path).expect("open");
    let m = unsafe { memmap2::Mmap::map(&f).expect("mmap") };
    let b = tilecodec::blocked::Blocked::parse(&m).expect("zblk");
    let rl = b.record_len();
    let nb = b.block_count();
    let raw: Vec<Vec<u8>> = (0..nb).into_par_iter().map(|i| b.block(i).expect("block")).collect();
    let all: Vec<u8> = raw.concat();
    let mut out = format!("{path}: {} B, record_len {rl}, {} blocks of {} records, unpacked {} B\n", m.len(), nb, b.records_per_block(), all.len());
    for per in [4096usize, 16384] {
        for level in [10, 19, 22] {
            let chunks: Vec<&[u8]> = all.chunks(per * rl).collect();
            let frames: Vec<Vec<u8>> = chunks.par_iter().map(|c| zstd_rs(c, level)).collect();
            let total: usize = 28 + 8 * (frames.len() + 1) + frames.iter().map(|f| f.len()).sum::<usize>();
            // Decode cost: sample 2000 frames.
            let step = (frames.len() / 2000).max(1);
            let t = Instant::now();
            let mut n = 0;
            let mut dec = ruzstd::decoding::FrameDecoder::new();
            for (fr, c) in frames.iter().zip(chunks.iter()).step_by(step) {
                let mut o = vec![0u8; c.len()];
                dec.decode_all(fr, &mut o).expect("dec");
                n += 1;
            }
            let us = t.elapsed().as_secs_f64() * 1e6 / n as f64;
            out += &format!("  block {per:>5} level {level:>2}: {total:>12} B ({:.3}x of now)  decode {us:.0} us/block\nZBLK_TSV\t{path}\t{per}\t{level}\t{total}\t{us:.1}\n", total as f64 / m.len() as f64);
        }
    }
    print!("{out}");
}

/// `zooms ARCH`: exact stored bytes of unique bodies per zoom (a body shared across zooms is
/// counted once, at the lowest zoom that references it).
fn cmd_zooms(path: &str) {
    let a = open(path);
    let ents = entries(&a);
    let mut z = [0u64; 15];
    let mut n = [0u64; 15];
    for e in &ents {
        z[e.z.min(14) as usize] += e.len as u64;
        n[e.z.min(14) as usize] += 1;
    }
    for i in 0..15 {
        println!("ZOOM\t{i}\t{}\t{}", n[i], z[i]);
    }
    println!("LEAVES\t{}", a.hdr.leaf_len);
}
