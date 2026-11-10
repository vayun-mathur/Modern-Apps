fn usage() {
    eprintln!(
        "usage: mamaps_build --input IN.osm.pbf --out OUT.mamaps\n\
        \x20                   --coastline LAND.shp|LAND.geojsonseq\n\
        \x20                   --transit-routes ROUTES.geojsonseq\n\
        \x20                   --graph GRAPH_DIR\n\
        \x20                   --dem HEIGHTMAPS.mdem\n\
        \x20                   [--region california|world]\n\
        \x20                   [--build-graph-to DIR] [--build-poi-to DIR]\n\
        \n\
        \x20All six flags are required. Every build carries all 9 layers at\n\
        \x20z0-14 as FORMAT_VERSION 8. --region filters roads/pois/buildings\n\
        \x20to the region (default world = no filter); everything else is\n\
        \x20always included."
    );
}

/// Whether this build is allowed to proceed given what it was told about a side input.
///
/// **Every side input is required, with no way to decline it.** A missing coastline used to be a
/// warning, which is how an archive ends up with islands and no mainland: the line scrolls past
/// in a build that takes hours and nobody reads it until the map is on a phone. The same holds
/// for the other three: the `transit` layer comes from GTFS rather than the `.osm.pbf`, the
/// `traffic` and `junction` layers from the routing graph, and every tile's heightmap from the
/// DEM — so a build without one is silently missing a layer, not a build with different options.
fn check_required(path: &std::path::Path, flag: &str) -> Result<(), String> {
    if path.as_os_str().is_empty() {
        return Err(format!("{flag} is required: every build carries all 9 layers"));
    }
    Ok(())
}

/// Validate the routing graph directory BEFORE stage A runs.
///
/// This is the failure the world build hit: an empty graph dir (no
/// `metadata.bin` because `road_graph` never ran or wrote elsewhere), paid
/// for with 43 minutes of stage A before `traffic` tried to read it. Every
/// check here fails in seconds with the path in the message.
///
/// A region build additionally requires the graph to cover the region: a
/// world graph beside a california tile build would silently double-cover
/// traffic, and a california graph beside a world build would silently drop
/// it outside the state. The graph's own node bbox is compared against the
/// region's — it must at least touch it.
fn check_graph_dir(dir: &std::path::Path, region: osm_ingest::region::Region) -> Result<(), String> {
    let read = |name: &str| -> Result<Vec<u8>, String> {
        let path = dir.join(name);
        std::fs::read(&path).map_err(|e| format!("cannot read {}: {e}", path.display()))
    };
    // `metadata.bin` first: its absence is THE failure (empty dir), and its
    // counts size every other check.
    let meta = read("metadata.bin")?;
    if meta.len() < 40 {
        return Err(format!(
            "metadata.bin is {} bytes, a v6 MARG header is 40 — is {} a graph dir?",
            meta.len(),
            dir.display(),
        ));
    }
    let u32_at = |b: &[u8], at: usize| u32::from_le_bytes([b[at], b[at + 1], b[at + 2], b[at + 3]]);
    let u64_at = |b: &[u8], at: usize| {
        u64::from_le_bytes([
            b[at], b[at + 1], b[at + 2], b[at + 3], b[at + 4], b[at + 5], b[at + 6], b[at + 7],
        ])
    };
    if u32_at(&meta, 0) != 0x4752_414D {
        return Err(format!("{} is not a MARG graph header", dir.join("metadata.bin").display()));
    }
    let version = u32_at(&meta, 4);
    if version != 6 && version != 7 {
        return Err(format!("the graph is version {version}; this build reads v6..=v7"));
    }
    let blocked_v7 = version >= 7;
    let node_count = u64_at(&meta, 8);
    let edge_count = u64_at(&meta, 16);
    if node_count == 0 {
        return Err(format!("the graph at {} has 0 nodes — road_graph wrote nothing?", dir.display()));
    }
    // The remaining files must exist and match the header's counts.
    // Under v7 the record arrays are ZBLK sections: unpack at the boundary
    // (this is a preflight check, not the hot path, so whole-section inflate
    // is fine).
    // Block-parallel (see `schema::traffic::inflate_all`): the planet's 5 GB node table used to
    // inflate on one thread here, in the preflight, before stage A could start.
    fn inflate_all(v: &tilecodec::blocked::Blocked) -> Result<Vec<u8>, String> {
        crate::schema::traffic::inflate_all(v).map_err(|e| format!("blocked inflate failed: {}", e.0))
    }
    let want_nodes = (node_count + 1)
        .checked_mul(12)
        .ok_or_else(|| "node table size overflows".to_string())?;
    let nodes_raw = read("nodes.bin")?;
    let nodes: Vec<u8> = if blocked_v7 {
        let v = tilecodec::blocked::Blocked::parse(&nodes_raw).map_err(|e| {
            format!("nodes.bin is not a blocked section: {e}")
        })?;
        if v.unpacked_len() != want_nodes || v.record_len() != 12 {
            return Err(format!(
                "nodes.bin unpacks to {} byte(s), expected {want_nodes} for {node_count}+1 records",
                v.unpacked_len(),
            ));
        }
        inflate_all(&v)?
    } else {
        if nodes_raw.len() as u64 != want_nodes {
            return Err(format!(
                "nodes.bin is {} bytes, expected {want_nodes} for {node_count}+1 records — stale graph dir?",
                nodes_raw.len(),
            ));
        }
        nodes_raw
    };
    let edges = read("edges.bin")?;
    if blocked_v7 {
        // v7: the record array is blocked (data-dependent length), so only
        // the tail minimum applies. The real validation (blocked parse +
        // unpacked lengths + escape/name checks) lives in `traffic::Graph::load`,
        // which reads this same dir next.
        let v = tilecodec::blocked::Blocked::parse(&edges).map_err(|e| {
            format!("edges.bin is not a blocked section: {e}")
        })?;
        if v.unpacked_len() != edge_count * 7 || v.record_len() != 7 {
            return Err(format!(
                "edges.bin unpacks to {} byte(s), expected {edge_count} 7-byte records",
                v.unpacked_len(),
            ));
        }
    } else {
        let want_edges_min = ((edge_count * 7 + 7) & !7) as usize;
        if edges.len() < want_edges_min {
            return Err(format!(
                "edges.bin is {} bytes, too short for {edge_count} edges — stale graph dir?",
                edges.len(),
            ));
        }
    }
    let inter = read("intermediate.bin")?;
    if inter.len() < 8 {
        return Err(format!(
            "intermediate.bin is {} bytes — stale graph dir?",
            inter.len(),
        ));
    }
    println!("graph ok: {node_count} node(s), {edge_count} edge(s) at {}", dir.display());

    // Region coverage: the graph's live extent must touch the region's. A
    // region graph covers only the region (world nodes outside it are dropped
    // at collapse), so the extent is the proof the graph and the region agree.
    //
    // Sampled by STRIDE, not by prefix: `nodes.bin` is Morton-ordered, so the
    // first 1M records are one corner of the spatial curve, not the world.
    // Stepping across the whole file touches every part of the curve for the
    // cost of ~1M random 12-byte reads.
    if let Some(b) = region.bbox() {
        let (mut min_lon, mut min_lat) = (i32::MAX, i32::MAX);
        let (mut max_lon, mut max_lat) = (i32::MIN, i32::MIN);
        let stride = (node_count / 1_000_000).max(1);
        let mut sampled = 0u64;
        let mut n = 0u64;
        while n < node_count {
            let base = (n as usize) * 12;
            let (lat, lon) = (
                i32::from_le_bytes(nodes[base..base + 4].try_into().unwrap()),
                i32::from_le_bytes(nodes[base + 4..base + 8].try_into().unwrap()),
            );
            // The trailing sentinel record is (0, 0); skip it and any node the
            // build left at the origin.
            if lat != 0 || lon != 0 {
                min_lon = min_lon.min(lon);
                min_lat = min_lat.min(lat);
                max_lon = max_lon.max(lon);
                max_lat = max_lat.max(lat);
                sampled += 1;
            }
            n += stride;
        }
        if sampled == 0 {
            return Err(format!(
                "the graph at {} has no located nodes — road_graph wrote nothing?",
                dir.display(),
            ));
        }
        let touches = !(max_lon < (b.min_lon * 1e7).round() as i32
            || min_lon > (b.max_lon * 1e7).round() as i32
            || max_lat < (b.min_lat * 1e7).round() as i32
            || min_lat > (b.max_lat * 1e7).round() as i32);
        if !touches {
            return Err(format!(
                "the graph at {} covers lon {:.1}..{:.1}, lat {:.1}..{:.1}, which does not touch the {} region — wrong --graph for --region {}?",
                dir.display(),
                min_lon as f64 * 1e-7,
                max_lon as f64 * 1e-7,
                min_lat as f64 * 1e-7,
                max_lat as f64 * 1e-7,
                region.name(),
                region.name(),
            ));
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Every side input is **required**, and there is no flag that says otherwise.
    ///
    /// A missing input used to print a line and carry on, which is how an archive ships with
    /// islands and no mainland: the warning scrolls past in a build that takes hours and nobody
    /// sees it until the map is on a phone.
    #[test]
    fn a_build_cannot_proceed_without_its_side_inputs() {
        let shp = std::path::Path::new("land_polygons.shp");
        assert!(check_required(shp, "--coastline").is_ok(), "the normal build");
        assert!(
            check_required(std::path::Path::new(""), "--coastline").is_err(),
            "an empty path is a missing input",
        );
    }

    /// The schema's own floor for buildings and the style's have to agree, or the archive either
    /// carries what nothing draws or is asked for what it does not carry.
    #[test]
    fn the_schema_floor_matches_the_styles() {
        // The style's floor lives in `library/map`'s flat style, which this crate does not link. So
        // this pins the number and names where the other copy is: a change here without a change
        // there shows up as a tile the renderer asks for and does not get.
        assert_eq!(
            schema::buildings::MIN_ZOOM,
            14,
            "buildings' floor is 14 in library/map/src/main/rust/style/basemap.flat.json too",
        );
    }

    /// The id has to change when the data would, and not otherwise. A rebuild from an unchanged file
    /// keeps its id, which is what makes a byte-identical rebuild byte-identical.
    #[test]
    fn a_build_id_follows_the_inputs_that_decide_the_output() {
        let path = std::path::Path::new("nonexistent.osm.pbf");
        let all = schema::Layers::all();
        let base = derive_build_id(path, all, 0, 14, 1.0, 100, "world");
        assert_eq!(base, derive_build_id(path, all, 0, 14, 1.0, 100, "world"), "stable");
        for other in [
            derive_build_id(path, all, 0, 14, 2.0, 100, "world"),
            derive_build_id(path, all, 0, 14, 1.0, 101, "world"),
            derive_build_id(path, all, 0, 14, 1.0, 100, "california"),
            derive_build_id(
                std::path::Path::new("other.osm.pbf"),
                all,
                0,
                14,
                1.0,
                100,
                "world",
            ),
        ] {
            assert_ne!(base, other, "a changed input should change the id");
        }
    }

    /// A missing or stale graph dir fails HERE, in seconds — not 43 minutes
    /// into stage A when `traffic` first reads it. That is the world-build
    /// failure this exists for: an empty graph dir with no `metadata.bin`.
    #[test]
    fn a_missing_graph_fails_before_stage_a() {
        use osm_ingest::region::Region;
        let missing = std::path::Path::new("no_such_graph_dir_xyz");
        let err = check_graph_dir(missing, Region::World).expect_err("missing dir must fail");
        assert!(err.contains("cannot read"), "{err}");
        // An empty dir: no metadata.bin is the reported failure.
        let dir = std::env::temp_dir()
            .join(format!("mamaps_graphcheck_{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let err = check_graph_dir(&dir, Region::World).expect_err("empty dir must fail");
        assert!(err.contains("metadata.bin"), "{err}");
        // A bad magic: not a graph dir at all.
        std::fs::write(dir.join("metadata.bin"), vec![0u8; 40]).unwrap();
        let err = check_graph_dir(&dir, Region::World).expect_err("bad magic must fail");
        assert!(err.contains("MARG"), "{err}");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
