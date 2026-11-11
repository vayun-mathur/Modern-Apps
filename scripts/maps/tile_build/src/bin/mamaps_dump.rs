//! `mamaps_dump` — print a canonical, line-oriented summary of a `.mamaps` archive.
//!
//! Usage:
//!   mamaps_dump IN.mamaps [--mode summary|tiles|header|dict] [--layer NAME]
//!
//! Print a canonical, line-oriented summary of a `.mamaps` archive: a binary
//! container is only reviewable if there is a text rendering of it, and a golden
//! file only diffs if that rendering is deterministic. Everything here is
//! `BTreeMap`/`BTreeSet` ordered, so there is no sort step and no way for output
//! order to depend on a hash seed.
//!
//! Modes:
//!   summary  one line per (zoom, layer): tile/feature counts, geometry-type counts and the
//!            sorted union of `kind`s. The default.
//!   tiles    one line per (z/x/y, layer). Verbose, for chasing a single tile.
//!   rings    one line per (z/x/y, layer, feature, part): winding, point count and signed area.
//!            The finest grain there is, and only sensible with `--tile`.
//!   geometry one line per (z/x/y, layer) carrying the MEASURES of the shapes rather than their
//!            counts: ring count, total absolute ring area, net signed area and total line length.
//!            What `test/diff_mamaps.py` compares, and the check that says two archives hold the
//!            same picture when they do not hold the same bytes.
//!   header   the header fields, one per line. Also what checks a published file is sane.
//!   dict     the interned tables, one id per line.
//!
//! Unlike the on-device reader this reads the whole file into memory. A `.mamaps` archive is opened by
//! range request on device, but a dump is a host tool run against a file that is already local, and
//! the format's whole point is that it is smaller than the MVT it replaces.

use std::collections::{BTreeMap, BTreeSet};
use std::process::ExitCode;

use tile_build::mamaps::body::{Body, GEOM_LINE, GEOM_POINT, GEOM_POLYGON};
use tile_build::mamaps::{dict, read};
use tile_build::pmtiles::tile_zxy;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    let mut input = None;
    let mut mode = "summary".to_string();
    let mut only_layer: Option<String> = None;
    let mut only_tile: Option<(u8, u64, u64)> = None;

    let mut i = 1;
    while i < args.len() {
        let take = |i: usize| args.get(i + 1).cloned();
        match args[i].as_str() {
            "--mode" => {
                match take(i) {
                    Some(m) => mode = m,
                    None => {
                        eprintln!("mamaps_dump: --mode needs a value");
                        return ExitCode::from(2);
                    }
                }
                i += 2;
            }
            "--layer" => {
                only_layer = take(i);
                i += 2;
            }
            "--tile" => {
                match take(i).as_deref().and_then(parse_tile) {
                    Some(t) => only_tile = Some(t),
                    None => {
                        eprintln!("mamaps_dump: --tile needs a z/x/y, e.g. 13/1308/2994");
                        return ExitCode::from(2);
                    }
                }
                i += 2;
            }
            "-h" | "--help" => {
                usage();
                return ExitCode::SUCCESS;
            }
            other if other.starts_with('-') => {
                eprintln!("mamaps_dump: unexpected argument '{other}'");
                usage();
                return ExitCode::from(2);
            }
            other => {
                if input.is_some() {
                    eprintln!("mamaps_dump: more than one input archive given");
                    return ExitCode::from(2);
                }
                input = Some(other.to_string());
                i += 1;
            }
        }
    }

    let Some(input) = input else {
        usage();
        return ExitCode::from(2);
    };
    // Mapped, not read: `--mode header` is the planet build's publish gate and touches only the
    // ~9 KB prefix, but `std::fs::read` pulled the whole archive into memory first -- 81 GB and
    // 34 s on the Nov 10 planet, and an 81 GB anonymous allocation on a box whose memory budget
    // is the build's tightest constraint. Every mode sees the same bytes either way; the other
    // modes simply page in what they walk.
    let file = match std::fs::File::open(&input) {
        Ok(f) => f,
        Err(e) => {
            eprintln!("mamaps_dump: cannot read {input}: {e}");
            return ExitCode::from(1);
        }
    };
    let empty = file.metadata().map(|m| m.len() == 0).unwrap_or(false);
    // SAFETY: a read-only mapping of a finished archive. Nothing in this process writes the
    // file; another process truncating it mid-dump could fault the read, which for a
    // diagnostic dump of a published file is the same failure a short read would be.
    let map = if empty {
        None
    } else {
        match unsafe { memmap2::Mmap::map(&file) } {
            Ok(m) => Some(m),
            Err(e) => {
                eprintln!("mamaps_dump: cannot read {input}: {e}");
                return ExitCode::from(1);
            }
        }
    };
    let bytes: &[u8] = map.as_deref().unwrap_or(&[]);
    match run(bytes, &mode, only_layer.as_deref(), only_tile) {
        Ok(code) => code,
        Err(e) => {
            eprintln!("mamaps_dump: {input}: {e}");
            ExitCode::from(1)
        }
    }
}

/// `z/x/y` as three integers, or `None` if it is not that.
fn parse_tile(s: &str) -> Option<(u8, u64, u64)> {
    let mut parts = s.split('/');
    let z = parts.next()?.parse().ok()?;
    let x = parts.next()?.parse().ok()?;
    let y = parts.next()?.parse().ok()?;
    parts.next().is_none().then_some((z, x, y))
}

fn run(
    bytes: &[u8],
    mode: &str,
    only_layer: Option<&str>,
    only_tile: Option<(u8, u64, u64)>,
) -> tile_build::proto::Result<ExitCode> {
    let (header, dictionary, _root) = read::open_prefix(bytes)?;
    if mode == "header" {
        // The byte the archive actually carries, not the constant this binary was built with.
        // `open_prefix` refuses anything but the current version, so the two agree by the time we
        // get here — but printing the constant would report "3" for a v3 reader no matter what
        // was on disk, which makes the line useless as a check on a freshly built archive.
        println!("format_version\t{}", bytes[7]);
        println!("build_id\t{:#018x}", header.build_id);
        println!("flags\t{:#06x}", header.flags);
        println!("bodies_compressed\t{}", header.compressed());
        println!("rings_validated\t{}", header.rings_validated());
        println!("compression\t{}", header.compression);
        println!("layer_count\t{}", header.layer_count);
        println!("min_zoom\t{}", header.min_zoom);
        println!("max_zoom\t{}", header.max_zoom);
        println!("file_len\t{}", header.file_len);
        println!("dict\t{}+{}", header.dict_offset, header.dict_len);
        println!("root\t{}+{}", header.root_offset, header.root_len);
        println!("leaves\t{}+{}\tcount={}", header.leaf_offset, header.leaf_len, header.leaf_count);
        println!("data\t{}+{}", header.data_offset, header.data_len);
        println!("leaf_entry_capacity\t{}", header.leaf_entry_capacity);
        println!("tiles_addressed\t{}", header.tiles_addressed);
        println!("bodies_written\t{}", header.bodies_written);
        // The dedup ratio, which is the number worth watching between builds: it is what makes
        // ocean and empty tiles nearly free.
        println!(
            "dedup\t{:.3}",
            header.bodies_written as f64 / header.tiles_addressed.max(1) as f64,
        );
        println!("min_lon_e7\t{}", header.min_lon_e7);
        println!("min_lat_e7\t{}", header.min_lat_e7);
        println!("max_lon_e7\t{}", header.max_lon_e7);
        println!("max_lat_e7\t{}", header.max_lat_e7);
        println!("prefix_bytes\t{}", 128 + header.dict_len + header.root_len);
        return Ok(ExitCode::SUCCESS);
    }
    if mode == "dict" {
        for (id, name) in dictionary.layers.iter().enumerate() {
            println!("layer\t{id}\t{name}");
        }
        for (index, name) in dictionary.kinds.iter().enumerate() {
            println!("kind\t{}\t{name}", index + 1);
        }
        for (index, name) in dictionary.details.iter().enumerate() {
            println!("kind_detail\t{}\t{name}", index + 1);
        }
        return Ok(ExitCode::SUCCESS);
    }
    if !matches!(mode, "summary" | "tiles" | "geometry" | "rings" | "names" | "ids") {
        eprintln!("mamaps_dump: unknown mode '{mode}'");
        return Ok(ExitCode::from(2));
    }

    let mut agg: BTreeMap<(u8, String), Agg> = BTreeMap::new();
    let mut undecodable = 0usize;
    for (tile_id, run_length, body) in read::read_all(bytes)? {
        let body = match Body::parse(&body) {
            Ok(b) => b,
            Err(_) => {
                undecodable += 1;
                continue;
            }
        };
        // A run is one body standing in for several tiles, and each of those tiles is really
        // present — so expanding it is what makes the counts comparable with an archive that
        // happened not to dedup.
        for offset in 0..run_length as u64 {
            let (z, x, y) = tile_zxy(tile_id + offset);
            if only_tile.is_some_and(|want| want != (z, x, y)) {
                continue;
            }
            for layer in &body.layers {
                let name = dictionary
                    .layer_name(layer.layer_id)
                    .map(str::to_string)
                    .unwrap_or_else(|| format!("layer{}", layer.layer_id));
                if only_layer.is_some_and(|want| want != name) {
                    continue;
                }
                let mut geoms: BTreeMap<&str, usize> = BTreeMap::new();
                if mode == "names" {
                    // One line per named feature: the label spot-check. Unnamed features are
                    // silent, so an empty result means "no names here" rather than "no features".
                    for (fi, feature) in layer.features.iter().enumerate() {
                        if let Some(label) = feature.name(&body) {
                            let kind = dictionary
                                .kind_name(feature.kind)
                                .unwrap_or("?");
                            println!(
                                "{z}/{x}/{y}\t{name}\tfeature={fi}\tkind={kind}\tlabel={label}"
                            );
                        }
                    }
                    continue;
                }
                if mode == "ids" {
                    // One line per feature carrying an archive id. Only `places` and `poi`
                    // have an id table at all, so an empty result on those two means the
                    // build dropped the ids, while an empty result elsewhere is correct.
                    for (fi, feature) in layer.features.iter().enumerate() {
                        let Some(id) = body.feature_id(layer.layer_id, fi) else { break };
                        let kind = dictionary.kind_name(feature.kind).unwrap_or("?");
                        let label = feature.name(&body).unwrap_or("");
                        // The low two bits are the OSM id space; see `extract::tagged_id`.
                        let (space, osm) = match id & 0b11 {
                            0 => ("none", 0),
                            1 => ("node", id >> 2),
                            2 => ("way", id >> 2),
                            _ => ("relation", id >> 2),
                        };
                        println!(
                            "{z}/{x}/{y}\t{name}\tfeature={fi}\tkind={kind}\tid={id}\t{space}/{osm}\tlabel={label}"
                        );
                    }
                    continue;
                }
                let mut kinds: BTreeSet<String> = BTreeSet::new();
                let mut points = 0usize;
                for feature in &layer.features {
                    *geoms.entry(geom_name(feature.geom_type)).or_default() += 1;
                    kinds.insert(kind_label(&dictionary, feature));
                    points +=
                        layer.parts_of(feature).iter().map(|p| p.point_count as usize).sum::<usize>();
                }
                if mode == "rings" {
                    for (fi, feature) in layer.features.iter().enumerate() {
                        for (pi, part) in layer.parts_of(feature).iter().enumerate() {
                            let pts = layer.points(part);
                            // Transit colour and lane inputs ride along when set: the
                            // spot-check for coloured lines and for the parallel fan-out
                            // (both empty for every other layer).
                            let color = if feature.transit_color != 0 {
                                format!(
                                    "\ttcolor={:06X}\ttordinal={}\ttlanes={}\tttaper={}",
                                    feature.transit_color,
                                    feature.transit_ordinal,
                                    feature.transit_lanes,
                                    feature.transit_taper,
                                )
                            } else {
                                String::new()
                            };
                            println!(
                                "{z}/{x}/{y}\t{name}\tfeature={fi}\tpart={pi}\tkind={}\twinding={}\tpoints={}\tsigned={:.2}\tfirst={:?}\tlast={:?}{color}",
                                kind_label(&dictionary, feature),
                                if part.is_hole() { "hole" } else { "outer" },
                                part.point_count,
                                ring_area(pts),
                                pts.first(),
                                pts.last(),
                            );
                        }
                    }
                    continue;
                }
                if mode == "geometry" {
                    // Three measures, because they fail differently and the difference
                    // between them is diagnostic:
                    //
                    //   rings   how many parts. A dropped hole shows up here and nowhere
                    //           else -- the geom= column counts FEATURES by type, so a
                    //           feature losing a ring looks identical there.
                    //   area    absolute, summed per ring, so a hole adds rather than
                    //           cancels. Sensitive to a ring being lost.
                    //   net     signed, summed per FEATURE, so holes subtract. This is
                    //           the ground actually covered, and it is what a renderer
                    //           fills. A self-touching ring's zero-width sliver
                    //           contributes nothing to it, which is the point.
                    //
                    // `area` and `net` disagreeing is how a prong of a self-touching
                    // ring changing orientation is told apart from one going missing.
                    let mut rings = 0usize;
                    let mut area = 0.0f64;
                    let mut net = 0.0f64;
                    let mut length = 0.0f64;
                    for feature in &layer.features {
                        for part in layer.parts_of(feature) {
                            let pts = layer.points(part);
                            rings += 1;
                            if feature.geom_type == GEOM_POLYGON {
                                let signed = ring_area(pts);
                                area += signed.abs();
                                net += signed;
                            } else {
                                length += path_length(pts);
                            }
                        }
                    }
                    // Two decimals of an extent unit: far below the quantisation grid the
                    // coordinates are already on, so this cannot manufacture a difference,
                    // and fixed-width so a text diff is meaningful.
                    println!(
                        "{z}/{x}/{y}\t{name}\tfeatures={}\tpoints={points}\trings={rings}\tgeom={}\tarea={area:.2}\tnet={net:.2}\tlength={length:.2}",
                        layer.features.len(),
                        join_counts(&geoms),
                    );
                    continue;
                }
                if mode == "tiles" {
                    println!(
                        "{z}/{x}/{y}\t{name}\tfeatures={}\tpoints={points}\textent={}\tgeom={}\tkinds={}",
                        layer.features.len(),
                        body.extent,
                        join_counts(&geoms),
                        kinds.iter().cloned().collect::<Vec<_>>().join(","),
                    );
                    continue;
                }
                let entry = agg.entry((z, name)).or_default();
                entry.tiles += 1;
                entry.features += layer.features.len();
                entry.points += points;
                entry.extents.insert(body.extent);
                for (geom, count) in geoms {
                    *entry.geoms.entry(geom.to_string()).or_default() += count;
                }
                entry.kinds.extend(kinds);
            }
        }
    }

    if mode == "summary" {
        for ((z, layer), a) in &agg {
            println!(
                "z{z}\t{layer}\ttiles={}\tfeatures={}\tpoints={}\textent={}\tgeom={}\tkinds={}",
                a.tiles,
                a.features,
                a.points,
                a.extents.iter().map(|e| e.to_string()).collect::<Vec<_>>().join("/"),
                join_counts_owned(&a.geoms),
                a.kinds.iter().cloned().collect::<Vec<_>>().join(","),
            );
        }
    }
    // Counted and reported, but not fatal: a dump of a mostly-good archive is more use than no
    // dump at all, and the count is what says whether to trust the rest.
    if undecodable > 0 {
        eprintln!("mamaps_dump: {undecodable} tile(s) could not be decoded");
    }
    Ok(ExitCode::SUCCESS)
}

#[derive(Default)]
struct Agg {
    tiles: usize,
    features: usize,
    points: usize,
    extents: BTreeSet<u16>,
    geoms: BTreeMap<String, usize>,
    kinds: BTreeSet<String>,
}

/// How a feature's `kind` prints: its interned name, its numeric detail, or `-` for neither.
///
/// `boundaries` carries an admin level rather than a `kind`, so printing the number is the only way
/// its rows say anything at all.
fn kind_label(dictionary: &dict::Dictionary, feature: &tile_build::mamaps::body::Feature) -> String {
    if let Some(level) = feature.detail_number() {
        return format!("level{level}");
    }
    match dictionary.kind_name(feature.kind) {
        Some(name) => match dictionary.detail_name(feature.kind_detail) {
            Some(detail) => format!("{name}:{detail}"),
            None => name.to_string(),
        },
        None => "-".to_string(),
    }
}

fn geom_name(geom_type: u8) -> &'static str {
    match geom_type {
        GEOM_LINE => "line",
        GEOM_POLYGON => "polygon",
        GEOM_POINT => "point",
        _ => "unknown",
    }
}

/// Twice-the-signed-area over two, by the shoelace formula, treating the ring as closed.
///
/// `i64` throughout before the divide: two `i16` spans multiply to 32 bits and a ring can hold
/// thousands of them, so accumulating in `f64` would round while `i64` cannot overflow.
fn ring_area(pts: &[(i16, i16)]) -> f64 {
    let n = pts.len();
    if n < 3 {
        return 0.0;
    }
    let mut twice: i64 = 0;
    for i in 0..n {
        let (x1, y1) = pts[i];
        let (x2, y2) = pts[(i + 1) % n];
        twice += x1 as i64 * y2 as i64 - x2 as i64 * y1 as i64;
    }
    twice as f64 / 2.0
}

fn path_length(pts: &[(i16, i16)]) -> f64 {
    pts.windows(2)
        .map(|w| {
            let dx = w[1].0 as f64 - w[0].0 as f64;
            let dy = w[1].1 as f64 - w[0].1 as f64;
            (dx * dx + dy * dy).sqrt()
        })
        .sum()
}

fn join_counts(m: &BTreeMap<&str, usize>) -> String {
    m.iter().map(|(k, v)| format!("{k}:{v}")).collect::<Vec<_>>().join(",")
}

fn join_counts_owned(m: &BTreeMap<String, usize>) -> String {
    m.iter().map(|(k, v)| format!("{k}:{v}")).collect::<Vec<_>>().join(",")
}

fn usage() {
    eprintln!(
        "usage: mamaps_dump IN.mamaps [--mode summary|tiles|geometry|rings|names|ids|header|dict] [--layer NAME] [--tile Z/X/Y]"
    );
}
