fn run(
    input: &std::path::Path,
    out: &std::path::Path,
    run: &RunSettings,
) -> Result<(), String> {
    let (layers, min_zoom, max_zoom, simplification) =
        (schema::Layers::all(), 0u8, DEFAULT_MAX_ZOOM, tiler::DEFAULT_SIMPLIFICATION);
    println!("reading {}", input.display());
    println!("region: {}", run.region.name());
    // Features are spilled here rather than held: it was 4.9 GB of a measured 10.03 GB California
    // peak, and nothing reads them until the tiler does.
    let spill = out.with_extension("features.tmp");
    // Named scratch (the pass-3 coordinate table: 83 GB on a planet) goes next to the spills,
    // not in the system temp dir; `MAPS_SCRATCH_DIR` overrides. Such files are unlinked as soon
    // as they are open, so a killed build leaves none of them behind.
    osm_ingest::mem::set_scratch_dir(spill.parent().map(|p| {
        if p.as_os_str().is_empty() { std::path::PathBuf::from(".") } else { p.to_path_buf() }
    }));
    check_required(&run.coastline, "--coastline")?;
    check_required(&run.transit_routes, "--transit-routes")?;
    check_required(&run.dem, "--dem")?;

    // --- unified pre-stages -------------------------------------------------
    //
    // `--build-graph-to` / `--build-poi-to` fold the two sidecar builds into
    // this process: same thread pool (no oversubscription from two binaries
    // each claiming the box), one shared blob scan via the graph's sidecar,
    // and the region filter applied once, consistently, in all three places.
    // Without them `--graph` must already exist and is validated below.
    let graph_dir: std::path::PathBuf = if let Some(dir) = run.build_graph_to.as_ref() {
        println!("building the routing graph in-process -> {}", dir.display());
        let mut opts = osm_ingest::graph_build::Options::default();
        opts.within_way_chains = true;
        opts.bbox = run.region.bbox();
        osm_ingest::graph_build::build_with(input, dir, opts)
            .map_err(|e| format!("graph build: {e}"))?;
        let ov = osm_ingest::overlay::build(dir, &dir.join("overlay.bin"))
            .map_err(|e| format!("overlay build: {e}"))?;
        println!("highway overlay: {} nodes, {} edges, {} bytes", ov.nodes, ov.edges, ov.bytes);
        dir.clone()
    } else {
        check_required(&run.graph, "--graph")?;
        run.graph.clone()
    };
    if let Some(dir) = run.build_poi_to.as_ref() {
        println!("building the POI sidecars in-process -> {}", dir.display());
        let poi_index = dir.join("poi_index.bin");
        osm_ingest::poi_build::build_with(
            input,
            &dir.join("poi.geojsonseq"),
            &dir.join("poi_names.bin"),
            &poi_index,
            &dir.join("poi_attrs.bin"),
            &dir.join("poi_spatial.bin"),
            &dir.join("poi_name_index.bin"),
            &dir.join("poi_region.bin"),
            run.region.bbox(),
        )
        .map_err(|e| format!("poi build: {e}"))?;
    }

    // The graph is validated HERE, before stage A — not 43 minutes in when
    // `traffic` tries to read it. This is the failure the world build hit:
    // an empty graph dir with no `metadata.bin`, paid for in full.
    //
    // With `--graph-ready-file` and the graph not yet ready, validation moves to the gate: it
    // runs the moment the ready file appears, before the first stage that reads the graph.
    let gate = GraphGate::new(graph_dir.clone(), run.graph_ready_file.clone(), run.region);
    if gate.ready_now() {
        gate.wait()?;
    } else {
        println!(
            "graph not ready yet: starting without it, will wait for {} before the junction layer",
            run.graph_ready_file.as_ref().expect("only without the flag is the graph always ready").display(),
        );
    }

    // The run's gapless phase clock. Opens in `"other"`, so the setup above (graph/POI builds,
    // validation) books as overhead; every major stage below switches it, and `finish` emits the
    // single `TIMING` line before the summary prints.
    let mut timer = crate::timing::PhaseTimer::start("tiles");
    let (store, stats, mut region_links, country_populations) = extract::extract(
        input,
        layers,
        &run.coastline,
        &run.transit_routes,
        &gate,
        &spill,
        run.region.bbox(),
        &mut timer,
    )
    .map_err(|e| format!("{}: {e}", input.display()))?;
    // The containment fallback: member-less place labels gain links from the whole-polygon
    // shapes above, never overwriting a member link. Runs here — after the store is sealed
    // and before the tiler reads the map — because only whole rings answer containment.
    // It also assigns country label start zooms from footprint × headcount (same pass,
    // footprints in hand).
    let mut country_zooms: std::collections::HashMap<u64, u8> = std::collections::HashMap::new();
    timer.switch("region-fallback");
    match crate::extract_fallback::extend_region_links_with(
        &store,
        &mut region_links,
        &mut country_zooms,
        &country_populations,
    ) {
        Ok(fallback) => {
            if fallback.places_total > 0 {
                println!(
                    "  place-label -> boundary links: {} place(s), {} member-linked, {} fallback-linked, {} missed",
                    fallback.places_total,
                    fallback.member_linked,
                    fallback.fallback_linked,
                    fallback.fallback_missed,
                );
                for (kind, total, linked) in &fallback.per_kind {
                    let rate = *linked as f64 / (*total).max(1) as f64 * 100.0;
                    println!("    {kind}: {linked}/{total} ({rate:.0}%)");
                }
            }
            if fallback.country_zooms > 0 {
                println!(
                    "  country label zooms: {} countr{} scored by footprint x headcount",
                    fallback.country_zooms,
                    if fallback.country_zooms == 1 { "y" } else { "ies" },
                );
            }
        }
        Err(e) => return Err(format!("region fallback: {e}")),
    }
    println!(
        "classified {} way(s), {} relation(s) and {} node(s) -> {} feature(s), {} node(s) resolved",
        stats.ways_classified,
        stats.relations_classified,
        stats.nodes_classified,
        stats.features,
        stats.nodes_needed,
    );
    if stats.geometry_failed > 0 {
        // Expected at an extract's cut edges, and worth reporting because a large count means
        // something else.
        println!("  {} classified element(s) produced no geometry", stats.geometry_failed);
    }
    if stats.land_polygons > 0 {
        println!("  including {} prepared land polygon(s)", stats.land_polygons);
    }
    if stats.transit_routes > 0 {
        println!("  including {} coloured transit route(s) from GTFS", stats.transit_routes);
    }
    if stats.traffic_segments > 0 {
        println!(
            "  including {} drivable component segment(s) for the traffic layer",
            stats.traffic_segments,
        );
    }
    if stats.junction_connectors > 0 {
        println!(
            "  including {} lane connector(s) for the junction layer",
            stats.junction_connectors,
        );
    }
    if stats.corridor_promotions > 0 {
        println!(
            "  {} road way(s) pulled to their corridor's zoom",
            stats.corridor_promotions,
        );
    }
    if stats.lanes_inherited > 0 {
        println!(
            "  {} untagged road way(s) took a lane count from a neighbour",
            stats.lanes_inherited,
        );
    }

    // The build id identifies the *data*: change the input and every reader has to drop its
    // cache. Derived rather than asked for, so a forgotten input cannot silently republish
    // under the old one. The region is part of the id: a california and a world
    // build from the same pbf are different archives.
    tiler::V8_BODIES.store(run.v8_bodies, std::sync::atomic::Ordering::Relaxed);
    let digests = input_digests(input, &run.coastline, &graph_dir, &run.transit_routes, &run.dem)?;
    let build_id = derive_build_id(
        &digests,
        layers,
        min_zoom,
        max_zoom,
        simplification,
        stats.features,
        run.region.name(),
        run.v8_bodies,
    );

    // The DEM heightmap dataset. Loaded here (this is where I/O belongs) and
    // handed to the tiler, which samples one grid per output tile into the body's heightmap section.
    timer.switch("dem-load");
    let dem = dem::Dem::load(&run.dem).map_err(|e| e.to_string())?;
    println!("loaded the DEM dataset at {}", run.dem.display());

    let settings = tiler::Settings {
        build_id,
        // Beside the archive, as the feature spill is. One zoom at a time, removed as each finishes.
        scratch: scratch_path(out),
        dem,
        region_links,
        country_zooms,
        // Honour the operator's backend choice: `MAPS_ANON_SPILL=1` (or any truthy value) stages
        // the tile-chunk spill in pagefile-backed anonymous memory instead of the scratch file
        // beside the output. Same chunks, same offsets, same bytes -- the merge reads through the
        // same `ChunkReader` either way, so the archive is identical and `-Verify` still holds.
        //
        // This matters because the tiler's own budget gate (`plan_chunk_spill`) answers from the
        // commit limit, not from this variable: on a box with a generous pagefile it picks anon
        // for an 11.9 M-feature store even when the operator knows the commit headroom is ~50 GB
        // and wants files. The variable is the override; the gate stays the default.
        force_chunk_spill_file: std::env::var("MAPS_ANON_SPILL")
            .map(|v| {
                let v = v.trim().to_ascii_lowercase();
                !(v == "1" || v == "true" || v == "yes")
            })
            .unwrap_or(false),
    };
    // Streamed straight to `out`: the archive's data section is tens of GB on a planet, and
    // holding it in a `Vec<u8>` beside the merge's own peak is the OOM after z14 merges.
    // `build_to_path` runs the identical zoom loop and assembles the identical
    // header/dictionary/root/leaves/data layout through `finish_to_path`.
    let per_zoom = tiler::build_to_path(&store, &settings, out, &mut timer).map_err(|e| e.to_string())?;
    tiler::check_not_empty(&per_zoom).map_err(|e| e.to_string())?;
    // The spill is scratch. Removed on success; left behind on failure, where it is evidence.
    let _ = std::fs::remove_file(&spill);
    // After the last work, before the summary prints: closes the final subphase and emits `TIMING`.
    timer.finish();

    println!(
        "\n{:<6}{:>10}{:>12}{:>12}{:>10}{:>12}{:>8}{:>8}{:>8}{:>8}",
        "zoom", "tiles", "features", "points", "dropped", "bytes", "map_s", "merge_s", "enc_s",
        "app_s",
    );
    for z in &per_zoom {
        println!(
            "z{:<5}{:>10}{:>12}{:>12}{:>10}{:>12}{:>8.1}{:>8.1}{:>8.1}{:>8.1}",
            z.zoom,
            z.tiles,
            z.features,
            z.points,
            z.dropped,
            z.bytes,
            z.map_ms as f64 / 1000.0,
            z.merge_ms as f64 / 1000.0,
            z.encode_ms as f64 / 1000.0,
            z.append_ms as f64 / 1000.0,
        );
        // The machine-readable twin of the text row above: integer milliseconds, so the
        // columns close exactly where the 0.1 s-rounded text does not.
        crate::timing::emit_zoom_row(
            z.zoom, z.tiles, z.features, z.points, z.dropped, z.bytes, z.map_ms, z.merge_ms,
            z.encode_ms, z.append_ms,
        );
    }
    // The four phase columns summed, because which of them dominates is the only thing that says
    // whether more cores would help: map and encode run on the pool, merge and append do not.
    let (map, merge, encode, append) = per_zoom.iter().fold((0u64, 0u64, 0u64, 0u64), |a, z| {
        (a.0 + z.map_ms, a.1 + z.merge_ms, a.2 + z.encode_ms, a.3 + z.append_ms)
    });
    let serial = (merge + append) as f64;
    let total = (map + merge + encode + append).max(1) as f64;
    // What coalescing removed. The `features` column above is counted in `push`, during the map
    // phase and therefore BEFORE the merge that coalescing runs after, so it reports what OSM
    // yielded rather than what was written -- which is the number the map phase's cost tracks. This
    // line is what was actually encoded.
    let lines = per_zoom.iter().fold(coalesce::Stats::default(), |mut a, z| {
        a.add(z.lines);
        a
    });
    if lines.features_before > lines.features_after {
        println!(
            "       coalesced: {} line feature(s) -> {} ({:.0}x), {} part(s) -> {} ({:.1}x)",
            lines.features_before,
            lines.features_after,
            lines.features_before as f64 / lines.features_after.max(1) as f64,
            lines.parts_before,
            lines.parts_after,
            lines.parts_before as f64 / lines.parts_after.max(1) as f64,
        );
    }
    // How close the archive came to the 65,535-feature cap on one tile-layer. Printed rather than
    // asserted because the answer is what decides whether the field has to widen, and widening it is
    // a `FORMAT_VERSION` bump and a matching renderer change.
    let widest = tiler::widest_layers();
    if !widest.is_empty() {
        let dict = tilecodec::mamaps::dict::Dictionary::schema();
        let worst = widest.iter().map(|(_, n)| *n).max().unwrap_or(0);
        let named: Vec<String> = widest
            .iter()
            .map(|(id, n)| {
                let name = dict.layer_name(*id).unwrap_or("?");
                format!("{name} {n}")
            })
            .collect();
        println!(
            "       widest tile-layer: {} (cap {}, {:.0}% used)",
            named.join(", "),
            u16::MAX,
            worst as f64 / u16::MAX as f64 * 100.0,
        );
    }
    println!(
        "total  map {:.1}s  merge {:.1}s  encode {:.1}s  append {:.1}s   ({:.0}% of tiling is serial)",
        map as f64 / 1000.0,
        merge as f64 / 1000.0,
        encode as f64 / 1000.0,
        append as f64 / 1000.0,
        serial / total * 100.0,
    );
    // The archive was streamed straight to `out` (`build_to_path`), so its length is read
    // back from the file rather than from a buffer that no longer exists.
    let archive_len = std::fs::metadata(out)
        .map(|m| m.len())
        .map_err(|e| format!("cannot stat {}: {e}", out.display()))?;
    // The header's id: the seed above folded with the archive's tile digest by the writer.
    let header_id = read_header_build_id(out)?;
    println!(
        "\nwrote {} ({archive_len} bytes, build_id {header_id:#018x}, seed {build_id:#018x})",
        out.display(),
    );
    Ok(())
}

/// Where the tiler parks one zoom's chunks while it merges them.
///
/// Beside the archive, as `<out>.tilechunks`, matching where the feature spill is placed. Appended
/// to the whole file name rather than replacing an extension, so `world.mamaps` and `world.tmp`
/// cannot collide on one path.
fn scratch_path(out: &std::path::Path) -> PathBuf {
    let mut p = out.as_os_str().to_owned();
    p.push(".tilechunks");
    PathBuf::from(p)
}

/// The build id in a finished archive's header.
fn read_header_build_id(out: &std::path::Path) -> Result<u64, String> {
    use std::io::Read;
    let mut head = vec![0u8; tilecodec::mamaps::header::HEADER_LEN];
    std::fs::File::open(out)
        .and_then(|mut f| f.read_exact(&mut head))
        .map_err(|e| format!("cannot read back {}: {e}", out.display()))?;
    tilecodec::mamaps::header::Header::parse(&head)
        .map(|h| h.build_id)
        .map_err(|e| format!("cannot parse {}: {e}", out.display()))
}

/// Content fingerprints of every file the archive is built from ([`input_digests`]).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct InputDigests {
    pub pbf: u64,
    pub coastline: u64,
    pub graph: u64,
    pub transit_routes: u64,
    pub dem: u64,
}

/// Fingerprint every input by content ([`osm_ingest::fingerprint`]): never a path or an mtime.
///
/// - `.osm.pbf`: length + first and last 4 MiB. The first blob is the `OSMHeader` block (with the
///   replication timestamp and sequence number), always well inside the first 4 MiB.
/// - coastline: the `.shp` (the only part of the shapefile read).
/// - graph: every regular file in the directory, by relative name (dot-files such as
///   `planet_build.sh`'s `.ready` signal skipped) -- metadata, nodes, edges, lanes, names, the
///   overlay outputs and `blob_kinds.bin`.
/// - transit routes and DEM: length + first and last 4 MiB.
pub(crate) fn input_digests(
    pbf: &std::path::Path,
    coastline: &std::path::Path,
    graph: &std::path::Path,
    transit_routes: &std::path::Path,
    dem: &std::path::Path,
) -> Result<InputDigests, String> {
    use osm_ingest::fingerprint::{dir_fingerprint, file_fingerprint};
    let file = |p: &std::path::Path| {
        file_fingerprint(p).map_err(|e| format!("cannot fingerprint {}: {e}", p.display()))
    };
    Ok(InputDigests {
        pbf: file(pbf)?,
        coastline: file(coastline)?,
        graph: dir_fingerprint(graph)
            .map_err(|e| format!("cannot fingerprint {}: {e}", graph.display()))?,
        transit_routes: file(transit_routes)?,
        dem: file(dem)?,
    })
}

/// The generator's build id: the seed the archive writer then mixes with the digest of every tile
/// it holds (`Options::build_id_from_content`, `tilecodec::mamaps::write::content_build_id`), so
/// the header's id is
///
/// ```text
/// header build_id = content_build_id(derive_build_id(..), tile digest)
/// tile digest     = fold over every appended tile, in order, of (tile id, stored length,
///                   FNV-1a 64 of the stored body)
/// ```
///
/// The seed is FNV-1a 64 over, in order: the tool revision tag `mamaps_build/18`; the archive
/// format version; the region name; the five input fingerprints ([`InputDigests`]: pbf,
/// coastline, graph, transit routes, DEM); the layer flags and zoom range; the simplification
/// factor; the stage-A feature count; the schema table's kind count; and the body encoding (v10,
/// or v8 under `--v8-bodies`).
///
/// Nothing environmental goes in -- no path, mtime, host, user or clock -- so identical inputs and
/// identical code give an identical id on any machine; a changed input changes the seed, and any
/// changed tile changes the digest. Readers key caches on this id, so both halves err on the side
/// of changing it.
#[allow(clippy::too_many_arguments)]
pub(crate) fn derive_build_id(
    inputs: &InputDigests,
    layers: schema::Layers,
    min_zoom: u8,
    max_zoom: u8,
    simplification: f64,
    features: u64,
    region: &str,
    v8_bodies: bool,
) -> u64 {
    let mut h = osm_ingest::fingerprint::Fnv::default();
    // Revision 17 fixed the missing lane byte in mixed landtype Road records (#821).
    // Revision 18: the id is content-derived (input fingerprints + tile digest), no longer the
    // input's path and mtime.
    h.eat(b"mamaps_build/18");
    h.eat(&[tilecodec::mamaps::header::FORMAT_VERSION]);
    h.eat(region.as_bytes());
    h.eat(&[0]);
    for digest in [inputs.pbf, inputs.coastline, inputs.graph, inputs.transit_routes, inputs.dem] {
        h.eat_u64(digest);
    }
    h.eat(&[
        u8::from(layers.landtype),
        u8::from(layers.buildings),
        u8::from(layers.roads),
        u8::from(layers.boundaries),
        u8::from(layers.places),
        u8::from(layers.poi),
        u8::from(layers.transit),
        u8::from(layers.traffic),
        u8::from(layers.junction),
        min_zoom,
        max_zoom,
    ]);
    h.eat(&simplification.to_le_bytes());
    h.eat_u64(features);
    // The schema table's own version, so a remapped kind invalidates every cache even when the
    // input has not moved.
    h.eat_u64(tilecodec::mamaps::dict::KINDS.len() as u64);
    h.eat(if v8_bodies { b"bodies/v8" } else { b"bodies/v10" });
    h.finish()
}
