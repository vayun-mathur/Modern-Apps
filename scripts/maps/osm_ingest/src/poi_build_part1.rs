fn make_poi(
    (lat, lon): (f64, f64),
    type_: u16,
    osm_id: i64,
    name: &[u8],
    attrs: Vec<u8>,
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

fn write_outputs(
    pois: &[Poi],
    geojson: &Path,
    names: &Path,
    index: &Path,
    attrs: &Path,
    spatial: &Path,
    name_index: &Path,
) -> Result<Written> {
    let mut pool = NamePool::new(BufWriter::new(create(names)?));
    let mut index_out = BufWriter::new(create(index)?);
    let mut geojson_out = BufWriter::new(create(geojson)?);
    // The sidecar is indexed by record ordinal, so it is filled in this same loop
    // over the same Morton-sorted vector. Any second pass over `pois` would be an
    // opportunity for the two files to disagree.
    let mut attr_pool = AttrPool::new();
    let mut line: Vec<u8> = Vec::new();

    for p in pois {
        let off = pool.intern(&p.name).map_err(io_err)?;
        index_out.write_all(&p.lat_e7.to_le_bytes()).map_err(io_err)?;
        index_out.write_all(&p.lon_e7.to_le_bytes()).map_err(io_err)?;
        index_out.write_all(&off.to_le_bytes()).map_err(io_err)?;
        index_out.write_all(&p.type_.to_le_bytes()).map_err(io_err)?;
        attr_pool.push(&p.attrs).map_err(io_err)?;

        line.clear();
        write!(
            line,
            "{{\"type\":\"Feature\",\"geometry\":{{\"type\":\"Point\",\"coordinates\":[{:.7},{:.7}]}},\"properties\":{{\"name\":\"",
            p.lon, p.lat
        )
        .map_err(io_err)?;
        json_escape(&p.name, &mut line);
        writeln!(line, "\",\"type\":{},\"osm_id\":{}}}}}", p.type_, p.osm_id).map_err(io_err)?;
        geojson_out.write_all(&line).map_err(io_err)?;
    }

    let mut attrs_out = BufWriter::new(create(attrs)?);
    attr_pool.write(&mut attrs_out).map_err(io_err)?;
    attrs_out.flush().map_err(io_err)?;

    // Both side files are derived from the same Morton-sorted vector as the index, in
    // the same function, for the same reason the sidecar is: they join by record
    // ordinal, and any second pass over `pois` is an opportunity to disagree.
    let coords: Vec<(i32, i32)> = pois.iter().map(|p| (p.lat_e7, p.lon_e7)).collect();
    let mut spatial_out = BufWriter::new(create(spatial)?);
    let spatial_cells = poi_side::write_spatial(&mut spatial_out, &coords).map_err(io_err)?;
    spatial_out.flush().map_err(io_err)?;

    let name_slices: Vec<&[u8]> = pois.iter().map(|p| p.name.as_slice()).collect();
    let mut name_index_out = BufWriter::new(create(name_index)?);
    let name_entries =
        poi_side::write_name_index(&mut name_index_out, &name_slices).map_err(io_err)?;
    name_index_out.flush().map_err(io_err)?;

    index_out.flush().map_err(io_err)?;
    geojson_out.flush().map_err(io_err)?;
    let unique = pool.unique_count();
    let bytes = pool.byte_len();
    pool.finish().map_err(io_err)?;
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
    Ok(Args {
        input,
        geojson,
        names,
        index,
        attrs,
        spatial,
        name_index,
        bbox: region.and_then(|r| r.bbox()),
        threads,
        reviews,
    })
}
