fn make_poi(
    (lat, lon): (f64, f64),
    type_: u16,
    osm_id: i64,
    name: &[u8],
    attrs: Vec<u8>,
    region: u64,
    place_cap: u8,
) -> Poi {
    let lat_e7 = (lat * 1e7).round() as i32;
    let lon_e7 = (lon * 1e7).round() as i32;
    Poi {
        lat,
        lon,
        lat_e7,
        lon_e7,
        // Derived from the *stored* integers, not the pre-rounding f64, so the
        // sort order matches the key a reader recomputes from `poi_index.bin`.
        morton: spatial_from_e7(lat_e7, lon_e7),
        type_,
        osm_id,
        name: name.to_vec(),
        attrs,
        region,
        place_cap,
    }
}

struct Written {
    records: usize,
    unique_names: usize,
    name_bytes: u32,
    with_attrs: usize,
    unique_attrs: usize,
    attr_bytes: usize,
    spatial_cells: usize,
    name_entries: usize,
}

/// Output buffer size. Large sequential writes: the build box's root is btrfs
/// with `compress-force=zstd`, which pays per extent, and a default 8 KiB
/// `BufWriter` turns a multi-GB geojson into a million small writes.
const WRITE_BUF: usize = 8 << 20;

/// POIs formatted per parallel geojson batch. Bounds the formatted text held at
/// once (~0.3 KB a line) while giving every thread plenty of chunks.
const GEOJSON_BATCH: usize = 1 << 18;
const GEOJSON_CHUNK: usize = 2048;

#[allow(clippy::too_many_arguments)]
fn write_outputs(
    pois: &[Poi],
    geojson: &Path,
    names: &Path,
    index: &Path,
    attrs: &Path,
    spatial: &Path,
    name_index: &Path,
    region: &Path,
    timer: &mut crate::timing::PhaseTimer,
) -> Result<Written> {
    // All seven files are pure functions of the Morton-sorted `pois`, and each is
    // written by exactly one task, so running the tasks side by side cannot change a
    // byte of any of them. They still join by record ordinal because every one of
    // them walks the same slice in the same order — the property the single serial
    // loop used to guarantee.
    timer.switch("write");
    let (r_names, r_geojson, r_attrs, r_spatial, r_name_index, r_region) = {
        let mut r_names = None;
        let mut r_geojson = None;
        let mut r_attrs = None;
        let mut r_spatial = None;
        let mut r_name_index = None;
        let mut r_region = None;
        crate::par::install(|| {
            rayon::scope(|sc| {
                sc.spawn(|_| r_names = Some(write_names_and_index(pois, names, index)));
                sc.spawn(|_| r_geojson = Some(write_geojson(pois, geojson)));
                sc.spawn(|_| r_attrs = Some(write_attrs(pois, attrs)));
                sc.spawn(|_| {
                    r_spatial = Some(write_with(spatial, |out| {
                        let coords: Vec<(i32, i32)> =
                            pois.iter().map(|p| (p.lat_e7, p.lon_e7)).collect();
                        poi_side::write_spatial(out, &coords)
                    }))
                });
                sc.spawn(|_| {
                    r_name_index = Some(write_with(name_index, |out| {
                        let name_slices: Vec<&[u8]> = pois.iter().map(|p| p.name.as_slice()).collect();
                        poi_side::write_name_index(out, &name_slices)
                    }))
                });
                sc.spawn(|_| {
                    r_region = Some(write_with(region, |out| {
                        let region_ids: Vec<u64> = pois.iter().map(|p| p.region).collect();
                        poi_side::write_region(out, &region_ids)
                    }))
                });
            })
        });
        let done = "every write task ran";
        (
            r_names.expect(done),
            r_geojson.expect(done),
            r_attrs.expect(done),
            r_spatial.expect(done),
            r_name_index.expect(done),
            r_region.expect(done),
        )
    };
    // Errors surface in the order the serial writer would have hit them.
    let (unique, bytes) = r_names?;
    r_geojson?;
    let attr_pool = r_attrs?;
    let spatial_cells = r_spatial?;
    let name_entries = r_name_index?;
    r_region?;

    println!(
        "Wrote {} record(s), {unique} unique name(s), {bytes} name byte(s)",
        pois.len()
    );
    println!(
        "Wrote {} POI(s) with attributes, {} unique record(s), {} sidecar byte(s)",
        attr_pool.with_attrs(),
        attr_pool.unique_count(),
        attr_pool.total_len()
    );
    println!(
        "Wrote {spatial_cells} populated grid cell(s), {name_entries} name index entr(ies)"
    );
    Ok(Written {
        records: pois.len(),
        unique_names: unique,
        name_bytes: bytes,
        with_attrs: attr_pool.with_attrs(),
        unique_attrs: attr_pool.unique_count(),
        attr_bytes: attr_pool.total_len(),
        spatial_cells,
        name_entries,
    })
}

/// Create `path`, hand `f` a large buffered writer over it, flush.
fn write_with<T>(path: &Path, f: impl FnOnce(&mut BufWriter<File>) -> std::io::Result<T>) -> Result<T> {
    let mut out = BufWriter::with_capacity(WRITE_BUF, create(path)?);
    let v = f(&mut out).map_err(io_err)?;
    out.flush().map_err(io_err)?;
    Ok(v)
}

/// `poi_names.bin` + `poi_index.bin`. Serial on purpose: a name's offset is the
/// pool size when it is first seen in record order (see `names.rs`).
fn write_names_and_index(pois: &[Poi], names: &Path, index: &Path) -> Result<(usize, u32)> {
    let mut pool = NamePool::new(BufWriter::with_capacity(WRITE_BUF, create(names)?));
    let mut index_out = BufWriter::with_capacity(WRITE_BUF, create(index)?);
    for p in pois {
        let off = pool.intern(&p.name).map_err(io_err)?;
        let mut rec = [0u8; 14];
        rec[0..4].copy_from_slice(&p.lat_e7.to_le_bytes());
        rec[4..8].copy_from_slice(&p.lon_e7.to_le_bytes());
        rec[8..12].copy_from_slice(&off.to_le_bytes());
        rec[12..14].copy_from_slice(&p.type_.to_le_bytes());
        index_out.write_all(&rec).map_err(io_err)?;
    }
    index_out.flush().map_err(io_err)?;
    let unique = pool.unique_count();
    let bytes = pool.byte_len();
    pool.finish().map_err(io_err)?;
    Ok((unique, bytes))
}

/// The geojsonseq, formatted in parallel batches and written in record order.
/// Each line is a function of its own POI only, so the bytes are the serial
/// loop's.
fn write_geojson(pois: &[Poi], geojson: &Path) -> Result<()> {
    use rayon::prelude::*;
    let mut out = BufWriter::with_capacity(WRITE_BUF, create(geojson)?);
    for batch in pois.chunks(GEOJSON_BATCH) {
        let texts: Vec<Vec<u8>> = batch
            .par_chunks(GEOJSON_CHUNK)
            .map(|chunk| {
                let mut buf: Vec<u8> = Vec::with_capacity(chunk.len() * 192);
                for p in chunk {
                    geojson_line(p, &mut buf);
                }
                buf
            })
            .collect();
        for t in &texts {
            out.write_all(t).map_err(io_err)?;
        }
    }
    out.flush().map_err(io_err)
}

/// One geojsonseq feature line, appended to `line`.
fn geojson_line(p: &Poi, line: &mut Vec<u8>) {
    // Writes into a Vec cannot fail.
    let _ = write!(
        line,
        "{{\"type\":\"Feature\",\"geometry\":{{\"type\":\"Point\",\"coordinates\":[{:.7},{:.7}]}},\"properties\":{{\"name\":\"",
        p.lon, p.lat
    );
    json_escape(&p.name, line);
    let _ = writeln!(line, "\",\"type\":{},\"osm_id\":{}}}}}", p.type_, p.osm_id);
}

/// `poi_attrs.bin`, deduplicated in record order (offsets are first-seen).
fn write_attrs(pois: &[Poi], attrs: &Path) -> Result<AttrPool> {
    let mut attr_pool = AttrPool::new();
    for p in pois {
        attr_pool.push(&p.attrs).map_err(io_err)?;
    }
    write_with(attrs, |out| attr_pool.write(out))?;
    Ok(attr_pool)
}

fn create(path: &Path) -> Result<File> {
    File::create(path).map_err(|e| Error(format!("cannot write {}: {e}", path.display())))
}

fn io_err(e: std::io::Error) -> Error {
    Error(e.to_string())
}

pub struct Args {
    pub input: PathBuf,
    pub geojson: PathBuf,
    pub names: PathBuf,
    pub index: PathBuf,
    pub attrs: PathBuf,
    pub spatial: PathBuf,
    pub name_index: PathBuf,
    /// Tagged boundary ids per POI ordinal. Defaults beside `--index`.
    pub region: PathBuf,
    /// Region bbox filter. `None` (world) keeps everything.
    pub bbox: Option<crate::bbox::BBox>,
    /// `None` leaves the pool at whatever `par::threads()` decides.
    pub threads: Option<usize>,
    /// Review table merged into the attribute sidecar (`--reviews FILE`).
    pub reviews: Option<PathBuf>,
}

/// `poi_extract IN.osm.pbf --geojson FILE --names FILE --index FILE [--attrs FILE]`
/// `[--spatial FILE] [--name-index FILE] [--reviews FILE] [--region california|na|world] [--threads N]`
///
/// The optional outputs default to their conventional names beside `--index`, so a
/// caller that predates any of them keeps working and still emits them. Making them
/// required would break build_pois_layer.sh and build_all.* on the same commit.
pub fn parse_args(args: &[String]) -> std::result::Result<Args, String> {
    let mut input: Option<PathBuf> = None;
    let mut geojson: Option<PathBuf> = None;
    let mut names: Option<PathBuf> = None;
    let mut index: Option<PathBuf> = None;
    let mut attrs: Option<PathBuf> = None;
    let mut spatial: Option<PathBuf> = None;
    let mut name_index: Option<PathBuf> = None;
    let mut region: Option<crate::region::Region> = None;
    let mut threads: Option<usize> = None;
    let mut reviews: Option<PathBuf> = None;
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "--threads" => {
                i += 1;
                let value = args
                    .get(i)
                    .ok_or_else(|| "--threads needs a value".to_string())?;
                threads = Some(crate::par::parse_threads(value)?);
            }
            "--region" => {
                i += 1;
                let value = args
                    .get(i)
                    .ok_or_else(|| "--region needs `california`, `na` or `world`".to_string())?;
                region = Some(crate::region::Region::parse(value).map_err(|e| e.0)?);
            }
            flag @ ("--geojson" | "--names" | "--index" | "--attrs" | "--spatial"
            | "--name-index" | "--reviews") => {
                let flag = flag.to_string();
                i += 1;
                let value = args
                    .get(i)
                    .map(PathBuf::from)
                    .ok_or_else(|| format!("{flag} needs a value"))?;
                match flag.as_str() {
                    "--geojson" => geojson = Some(value),
                    "--names" => names = Some(value),
                    "--attrs" => attrs = Some(value),
                    "--spatial" => spatial = Some(value),
                    "--name-index" => name_index = Some(value),
                    "--reviews" => reviews = Some(value),
                    _ => index = Some(value),
                }
            }
            a if a.starts_with('-') => return Err(format!("unknown option: {a}")),
            a => {
                if input.is_some() {
                    return Err(format!("unexpected extra argument: {a}"));
                }
                input = Some(PathBuf::from(a));
            }
        }
        i += 1;
    }
    // Resolved in the order the usage line lists them, so a bare `in.pbf` still
    // complains about the first thing it is missing.
    let input = input.ok_or_else(|| "missing IN.osm.pbf".to_string())?;
    let geojson = geojson.ok_or_else(|| "--geojson is required".to_string())?;
    let names = names.ok_or_else(|| "--names is required".to_string())?;
    let index = index.ok_or_else(|| "--index is required".to_string())?;
    let attrs = attrs.unwrap_or_else(|| index.with_file_name("poi_attrs.bin"));
    let spatial = spatial.unwrap_or_else(|| index.with_file_name("poi_spatial.bin"));
    let name_index = name_index.unwrap_or_else(|| index.with_file_name("poi_name_index.bin"));
    let region_ids = index.with_file_name("poi_region.bin");
    Ok(Args {
        input,
        geojson,
        names,
        index,
        attrs,
        spatial,
        name_index,
        region: region_ids,
        bbox: region.and_then(|r| r.bbox()),
        threads,
        reviews,
    })
}
