/// Read `input` and return every indexable feature, plus the dictionaries its strings were
/// interned into.
///
/// Every pass uses `run_pass_sink` rather than `run_pass`: the sink is called once per chunk,
/// in order, so a chunk's owned strings are interned and dropped immediately instead of every
/// chunk's strings being alive at once. On a planet run that is the difference between tens of
/// gigabytes and hundreds.
pub fn extract(
    input: &std::path::Path,
    bbox: Option<&BBox>,
) -> Result<(Vec<Row>, Strings, Stats)> {
    let blobs = pbf::scan_blobs(input)?;
    println!("Scanned {} data blob(s) in {}", blobs.len(), input.display());

    let mut stats = Stats::default();
    let mut rows: Vec<Row> = Vec::new();
    let mut strings = Strings::default();
    // Accumulated inside the pass sinks, which cannot borrow `stats` while it is also
    // borrowed by the way/relation materialization below.
    let mut incomplete = 0usize;

    // --- pass 1: relations decide which ways matter -----------------------
    let mut relations: Vec<(u8, [u32; DICTS], Vec<i64>)> = Vec::new();
    let kinds = {
        let relations = &mut relations;
        let strings = &mut strings;
        let incomplete = &mut incomplete;
        pbf::run_pass_sink(
            input,
            &blobs,
            None,
            KIND_RELATIONS,
            "relations",
            Vec::<(u8, Attrs, Vec<i64>)>::new,
            |acc, block| {
                let mut kinds = 0u8;
                visit_block(block, KIND_RELATIONS, &mut kinds, &mut |el| {
                    let Element::Relation(r) = el else { return Ok(()) };
                    // Only areas: a `multipolygon` or a `boundary` has an inside to put a
                    // point in. A route or a turn restriction does not.
                    let ty = r.tags.get("type").unwrap_or(b"");
                    if ty != b"multipolygon" && ty != b"boundary" {
                        return Ok(());
                    }
                    let Some(kind) = classify(&r.tags) else { return Ok(()) };
                    let members: Vec<i64> = r
                        .members
                        .iter()
                        .filter(|m| {
                            m.kind == MEMBER_WAY && (m.role == b"outer" || m.role.is_empty())
                        })
                        .map(|m| m.id)
                        .collect();
                    if members.is_empty() {
                        return Ok(());
                    }
                    acc.push((kind, read_attrs(&r.tags), members));
                    Ok(())
                })?;
                Ok(kinds)
            },
            |chunk| {
                for (kind, attrs, members) in chunk {
                    if !attrs_ok(kind, &attrs) {
                        *incomplete += 1;
                        continue;
                    }
                    relations.push((kind, strings.intern(&attrs), members));
                }
                Ok(())
            },
        )?
    };
    println!("  {} area relation(s)", relations.len());

    // Ways claimed by a relation, so the way pass keeps their refs even when the way itself is
    // untagged — which is the normal case for a multipolygon member.
    let mut claimed: Vec<i64> = relations.iter().flat_map(|(_, _, m)| m.iter().copied()).collect();
    claimed.sort_unstable();
    claimed.dedup();

    // --- pass 2: ways decide which node coordinates matter ----------------
    let mut ways: Vec<PendingWay> = Vec::new();
    let mut member_geom: HashMap<i64, Vec<i64>> = HashMap::new();
    {
        let ways = &mut ways;
        let member_geom = &mut member_geom;
        let strings = &mut strings;
        let claimed = &claimed;
        let incomplete = &mut incomplete;
        let _ = pbf::run_pass_sink(
            input,
            &blobs,
            Some(&kinds),
            KIND_WAYS,
            "ways",
            || (Vec::<(u8, Attrs, Vec<i64>)>::new(), Vec::<(i64, Vec<i64>)>::new()),
            |acc, block| {
                let mut kinds = 0u8;
                visit_block(block, KIND_WAYS, &mut kinds, &mut |el| {
                    let Element::Way(w) = el else { return Ok(()) };
                    if claimed.binary_search(&w.id).is_ok() {
                        acc.1.push((w.id, w.refs.to_vec()));
                    }
                    if let Some(kind) = classify(&w.tags) {
                        acc.0.push((kind, read_attrs(&w.tags), w.refs.to_vec()));
                    }
                    Ok(())
                })?;
                Ok(kinds)
            },
            |(tagged, members)| {
                for (kind, attrs, refs) in tagged {
                    if !attrs_ok(kind, &attrs) {
                        *incomplete += 1;
                        continue;
                    }
                    ways.push(PendingWay { kind, ids: strings.intern(&attrs), refs });
                }
                for (id, refs) in members {
                    let _ = member_geom.insert(id, refs);
                }
                Ok(())
            },
        )?;
    }
    println!("  {} tagged way(s), {} relation member way(s)", ways.len(), member_geom.len());

    // --- pass 3: node coordinates ----------------------------------------
    let mut needed: Vec<i64> = Vec::new();
    for w in &ways {
        needed.extend_from_slice(&w.refs);
    }
    for refs in member_geom.values() {
        needed.extend_from_slice(refs);
    }
    let table = NodeLocations::new(needed)?;
    println!("  {} distinct node(s) needed for geometry", table.len());
    let locs = resolve_nodes(input, &blobs, &kinds, "node coords", table)?;

    // --- materialize ways -------------------------------------------------
    // Chunks run in parallel and are collected in input order, so `rows` comes out exactly as
    // a sequential loop would leave it.
    let p = Progress::new("materialize ways", ways.len() as u64);
    let parts: Vec<(Vec<Row>, Stats)> = ways
        .par_chunks(MATERIALIZE_CHUNK)
        .map(|chunk| {
            let mut out = Vec::new();
            let mut st = Stats::default();
            for w in chunk {
                let coords: Vec<(i32, i32)> =
                    w.refs.iter().filter_map(|&id| locs.get(id)).collect();
                if coords.is_empty() {
                    continue;
                }
                let points: Vec<(i32, i32)> = if w.kind == K_STREET {
                    sample_line(&coords)
                } else {
                    average(&coords).into_iter().collect()
                };
                for (lat, lon) in points {
                    if !bbox::keep_e7(bbox, lat, lon) {
                        st.outside_bbox += 1;
                        continue;
                    }
                    push_counted(&mut out, &mut st, row_from(w.kind, w.ids, lat, lon), Origin::Way);
                }
            }
            p.add(chunk.len() as u64);
            (out, st)
        })
        .collect();
    absorb(&mut rows, &mut stats, parts);
    p.finish();
    drop(ways);

    // --- materialize relations -------------------------------------------
    let p = Progress::new("materialize relations", relations.len() as u64);
    let parts: Vec<(Vec<Row>, Stats)> = relations
        .par_chunks(MATERIALIZE_CHUNK)
        .map(|chunk| {
            let mut out = Vec::new();
            let mut st = Stats::default();
            for (kind, ids, members) in chunk {
                let mut coords: Vec<(i32, i32)> = Vec::new();
                for id in members {
                    if let Some(refs) = member_geom.get(id) {
                        coords.extend(refs.iter().filter_map(|&r| locs.get(r)));
                    }
                }
                let Some((lat, lon)) = average(&coords) else { continue };
                if !bbox::keep_e7(bbox, lat, lon) {
                    st.outside_bbox += 1;
                    continue;
                }
                push_counted(&mut out, &mut st, row_from(*kind, *ids, lat, lon), Origin::Relation);
            }
            p.add(chunk.len() as u64);
            (out, st)
        })
        .collect();
    absorb(&mut rows, &mut stats, parts);
    p.finish();
    drop(relations);

    drop(locs);
    drop(member_geom);

    // --- pass 4: node features -------------------------------------------
    {
        let rows = &mut rows;
        let stats = &mut stats;
        let strings = &mut strings;
        let _ = pbf::run_pass_sink(
            input,
            &blobs,
            Some(&kinds),
            KIND_NODES,
            "node features",
            Vec::<(u8, Attrs, i32, i32)>::new,
            |acc: &mut Vec<(u8, Attrs, i32, i32)>, block| {
                let mut kinds = 0u8;
                visit_block(block, KIND_NODES, &mut kinds, &mut |el| {
                    let Element::Node(n) = el else { return Ok(()) };
                    let Some(kind) = classify(&n.tags) else { return Ok(()) };
                    acc.push((kind, read_attrs(&n.tags), n.lat_e7, n.lon_e7));
                    Ok(())
                })?;
                Ok(kinds)
            },
            |chunk| {
                for (kind, attrs, lat, lon) in chunk {
                    if !bbox::keep_e7(bbox, lat, lon) {
                        stats.outside_bbox += 1;
                        continue;
                    }
                    if !is_useful(kind, &attrs, lat, lon) {
                        stats.incomplete += 1;
                        continue;
                    }
                    let ids = strings.intern(&attrs);
                    push_counted(rows, stats, row_from(kind, ids, lat, lon), Origin::Node);
                }
                Ok(())
            },
        )?;
    }

    if rows.is_empty() {
        return Err(Error("no indexable features found; check --bbox and the PBF".to_string()));
    }
    stats.incomplete += incomplete;
    Ok((rows, strings, stats))
}

pub(crate) enum Origin {
    Node,
    Way,
    Relation,
}

/// Ways or relations per parallel materialisation task.
const MATERIALIZE_CHUNK: usize = 1 << 14;

/// Append per-chunk results in chunk order, freeing each chunk as it goes.
fn absorb(rows: &mut Vec<Row>, stats: &mut Stats, parts: Vec<(Vec<Row>, Stats)>) {
    rows.reserve(parts.iter().map(|(r, _)| r.len()).sum());
    for (r, s) in parts {
        rows.extend_from_slice(&r);
        stats.add(&s);
    }
}

/// Record a materialized row, dropping any whose coordinates fell outside the representable
/// range. Tag-level completeness was already decided by [`attrs_ok`] before interning.
fn push_counted(rows: &mut Vec<Row>, stats: &mut Stats, row: Row, origin: Origin) {
    if !in_range(row.lat, row.lon) {
        stats.incomplete += 1;
        return;
    }
    match row.kind {
        K_ADDRESS => stats.addresses += 1,
        K_STREET => stats.streets += 1,
        K_POI => stats.pois += 1,
        _ => stats.places += 1,
    }
    match origin {
        Origin::Node => stats.from_nodes += 1,
        Origin::Way => stats.from_ways += 1,
        Origin::Relation => stats.from_relations += 1,
    }
    rows.push(row);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_closed_rings_repeated_vertex_does_not_bias_the_average() {
        // A unit square, with the first vertex repeated as OSM writes it.
        let ring = vec![(0, 0), (0, 100), (100, 100), (100, 0), (0, 0)];
        assert_eq!(average(&ring), Some((50, 50)));
        // Without the fix the repeated (0,0) would drag it to (40, 40).
        let naive: i64 = ring.iter().map(|c| c.0 as i64).sum::<i64>() / ring.len() as i64;
        assert_eq!(naive, 40, "premise: counting it twice biases the point");
    }

    #[test]
    fn an_open_way_averages_all_its_vertices() {
        assert_eq!(average(&[(0, 0), (10, 20)]), Some((5, 10)));
        assert_eq!(average(&[]), None);
    }

    #[test]
    fn a_short_street_yields_one_sample() {
        let line = vec![(37_7749300, -122_4194200), (37_7749400, -122_4194300)];
        assert_eq!(sample_line(&line).len(), 2, "endpoints are always anchored");
        assert_eq!(sample_line(&line[..1]).len(), 1);
    }

    #[test]
    fn a_long_street_is_sampled_along_its_length_not_just_at_its_middle() {
        // A degree of latitude in 1000 steps: 10 000 000 e7 units total.
        let line: Vec<(i32, i32)> =
            (0..1000).map(|i| (37_0000000 + i * 10_000, -122_0000000)).collect();
        let s = sample_line(&line);
        assert!(s.len() > 5, "expected several samples, got {}", s.len());
        assert!(s.len() <= STREET_MAX_SAMPLES, "cap not honoured: {}", s.len());
        assert_eq!(s[0], line[0], "the near end must be represented");
        assert_eq!(*s.last().unwrap(), *line.last().unwrap(), "and the far end");
        // Samples must be spread over the whole way, not bunched at the start: the midpoint
        // of the sample list should sit near the midpoint of the way.
        let mid = s[s.len() / 2].0;
        let want = (line[0].0 + line[line.len() - 1].0) / 2;
        assert!((mid - want).abs() < 1_000_000, "samples are bunched: mid {mid} vs {want}");
    }

    #[test]
    fn street_samples_respect_the_minimum_spacing() {
        // Vertices far closer together than the sample spacing.
        let line: Vec<(i32, i32)> = (0..500).map(|i| (37_0000000 + i * 10, -122_0000000)).collect();
        let s = sample_line(&line);
        // 499 gaps x 10 units = 4990 units total, under one sample spacing, so only the two
        // endpoints survive.
        assert_eq!(s.len(), 2, "got {s:?}");
    }

    #[test]
    fn a_zero_length_way_yields_one_point() {
        let line = vec![(37_0000000, -122_0000000); 5];
        assert_eq!(sample_line(&line), vec![(37_0000000, -122_0000000)]);
        assert!(sample_line(&[]).is_empty());
    }

    #[test]
    fn an_enormous_way_cannot_dominate_the_database() {
        let line: Vec<(i32, i32)> =
            (0..100_000).map(|i| (37_0000000 + i * 1_000, -122_0000000)).collect();
        let s = sample_line(&line);
        assert!(s.len() <= STREET_MAX_SAMPLES);
        // Even at the cap, the far end is still covered.
        assert_eq!(*s.last().unwrap(), *line.last().unwrap());
    }

    fn attrs(name: &str, house: &str, street: &str) -> Attrs {
        Attrs {
            name: name.to_string(),
            house: house.to_string(),
            street: street.to_string(),
            ..Attrs::default()
        }
    }

    #[test]
    fn an_address_needs_a_number_and_something_to_hang_it_on() {
        assert!(attrs_ok(K_ADDRESS, &attrs("", "123", "Main St")));
        assert!(!attrs_ok(K_ADDRESS, &attrs("", "123", "")));
        assert!(!attrs_ok(K_ADDRESS, &attrs("", "", "Main St")));
    }

    #[test]
    fn everything_else_needs_a_name() {
        assert!(attrs_ok(K_POI, &attrs("Blue Bottle", "", "")));
        assert!(!attrs_ok(K_POI, &attrs("", "", "")));
        assert!(attrs_ok(K_STREET, &attrs("Market Street", "", "")));
        assert!(attrs_ok(K_PLACE, &attrs("Mission District", "", "")));
    }

    #[test]
    fn only_roads_are_classified_as_streets() {
        for road in ["residential", "primary", "living_street", "pedestrian"] {
            assert!(is_road(road), "{road}");
        }
        for not_road in ["footway", "service", "bus_stop", "track", "motorway_link", "cycleway"] {
            assert!(!is_road(not_road), "{not_road}");
        }
    }

    #[test]
    fn rows_are_stored_at_e5() {
        let r = row_from(K_POI, [0; DICTS], 37_7749350, -122_4194249);
        assert_eq!((r.lat, r.lon), (3_777_494, -12_241_942));
    }

    #[test]
    fn out_of_range_coordinates_are_never_useful() {
        let a = attrs("somewhere", "", "");
        assert!(is_useful(K_POI, &a, 37_7749300, -122_4194200));
        assert!(!is_useful(K_POI, &a, 900_000_100, -122_4194200));
        assert!(!is_useful(K_POI, &a, 37_7749300, -1_800_000_100));
    }

    #[test]
    fn interning_assigns_one_id_per_distinct_string() {
        let mut s = Strings::default();
        let a = s.intern(&attrs("Blue Bottle", "", ""));
        let b = s.intern(&attrs("Blue Bottle", "", ""));
        let c = s.intern(&attrs("Other", "", ""));
        assert_eq!(a, b, "the same tags must intern to the same ids");
        assert_ne!(a[D_NAME], c[D_NAME]);
        assert_eq!(s.dicts[D_NAME].len(), 2);
        // The empty strings all collapse to one id per dictionary.
        assert_eq!(s.dicts[D_HOUSE].len(), 1);
    }

    #[test]
    fn addr_place_stands_in_for_addr_street() {
        // Much of DE/AT/JP/KR addresses buildings against a place, not a street. v2 required
        // addr:street and silently dropped all of them.
        let mut a = Attrs { house: "5".to_string(), ..Attrs::default() };
        assert!(!attrs_ok(K_ADDRESS, &a));
        a.street = "Marktplatz".to_string();
        assert!(attrs_ok(K_ADDRESS, &a));
    }
}
