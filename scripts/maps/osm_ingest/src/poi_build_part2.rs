#[cfg(test)]
mod tests {
    use super::*;
    use crate::testpbf;

    struct Record {
        lat_e7: i32,
        lon_e7: i32,
        name: String,
        type_: u16,
    }

    fn read_records(dir: &Path) -> (Vec<Record>, Vec<String>) {
        let index = std::fs::read(dir.join("poi_index.bin")).unwrap();
        let names = std::fs::read(dir.join("poi_names.bin")).unwrap();
        let geojson = std::fs::read_to_string(dir.join("pois.geojsonseq")).unwrap();
        assert_eq!(index.len() % 14, 0, "records must be exactly 14 bytes");
        let records = index
            .chunks_exact(14)
            .map(|r| {
                let off = u32::from_le_bytes(r[8..12].try_into().unwrap()) as usize;
                let end = off + names[off..].iter().position(|b| *b == 0).unwrap();
                Record {
                    lat_e7: i32::from_le_bytes(r[0..4].try_into().unwrap()),
                    lon_e7: i32::from_le_bytes(r[4..8].try_into().unwrap()),
                    name: String::from_utf8(names[off..end].to_vec()).unwrap(),
                    type_: u16::from_le_bytes(r[12..14].try_into().unwrap()),
                }
            })
            .collect();
        let lines = geojson.lines().map(|l| l.to_string()).collect();
        (records, lines)
    }

    /// Decode `poi_attrs.bin` the way [`crate::poi_attrs`] documents it: one
    /// `(key, value)` list per record ordinal, empty where the POI had nothing.
    fn read_attrs(dir: &Path) -> Vec<Vec<(u8, String)>> {
        use crate::poi_attrs::{HEADER_BYTES, MAGIC, NO_ATTRS, VERSION};
        let bytes = std::fs::read(dir.join("poi_attrs.bin")).unwrap();
        assert_eq!(&bytes[0..4], &MAGIC);
        assert_eq!(bytes[4], VERSION);
        let count = u32::from_le_bytes(bytes[8..12].try_into().unwrap()) as usize;
        let blob = &bytes[HEADER_BYTES + 4 * count..];
        (0..count)
            .map(|i| {
                let at = HEADER_BYTES + 4 * i;
                let off = u32::from_le_bytes(bytes[at..at + 4].try_into().unwrap());
                if off == NO_ATTRS {
                    return Vec::new();
                }
                let at = off as usize;
                let len = u16::from_le_bytes(blob[at..at + 2].try_into().unwrap()) as usize;
                let body = &blob[at + 2..at + 2 + len];
                let mut out = Vec::new();
                let mut j = 0;
                while j + 3 <= body.len() {
                    let vlen = u16::from_le_bytes(body[j + 1..j + 3].try_into().unwrap()) as usize;
                    out.push((
                        body[j],
                        String::from_utf8(body[j + 3..j + 3 + vlen].to_vec()).unwrap(),
                    ));
                    j += 3 + vlen;
                }
                out
            })
            .collect()
    }

    fn build_sample(tag: &str) -> (Stats, PathBuf) {
        let (pbf_path, dir) = testpbf::write_sample(tag);
        let stats = build(
            &pbf_path,
            &dir.join("pois.geojsonseq"),
            &dir.join("poi_names.bin"),
            &dir.join("poi_index.bin"),
            &dir.join("poi_attrs.bin"),
            &dir.join("poi_spatial.bin"),
            &dir.join("poi_name_index.bin"),
        )
        .unwrap();
        (stats, dir)
    }

    #[test]
    fn extracts_nodes_closed_ways_and_relations() {
        let (stats, dir) = build_sample("poi_build");

        // The cafe node, the closed "Plaza" way and the "Riverside Park"
        // relation. The bus stop node has a name but `highway=bus_stop` is not a
        // POI key, so it is correctly absent.
        assert_eq!(
            (stats.from_nodes, stats.from_ways, stats.from_relations),
            (1, 1, 1)
        );
        assert_eq!(stats.records, 3);

        let (records, lines) = read_records(&dir);
        assert_eq!(records.len(), 3);
        assert_eq!(lines.len(), 3);

        let by_name = |n: &str| records.iter().find(|r| r.name == n).unwrap();
        // amenity=cafe -> 1, at the cafe node's own location.
        let cafe = by_name("Corner Cafe");
        assert_eq!(cafe.type_, 1);
        assert_eq!((cafe.lat_e7, cafe.lon_e7), (370_040_000, -1_220_010_000));
        // The closed way's ring is nodes 1-4 plus a repeat of the ring's start,
        // which libosmium picks as the vertex with the smallest (lon, lat) — here
        // node 1. So the average is over lat 370_000_000 twice, then 010, 020, 030.
        let plaza = by_name("Plaza");
        assert_eq!(plaza.type_, 1);
        assert_eq!(plaza.lat_e7, 370_012_000);
        assert_eq!(plaza.lon_e7, -1_220_000_000);
        // leisure=park -> 12. The relation's outer ring IS that closed way, the
        // case the approximation reproduces exactly, so the centroid must match.
        let park = by_name("Riverside Park");
        assert_eq!(park.type_, 12);
        assert_eq!((park.lat_e7, park.lon_e7), (plaza.lat_e7, plaza.lon_e7));

        // Records are Morton-ordered.
        let keys: Vec<u64> = records
            .iter()
            .map(|r| crate::spatial::spatial_from_e7(r.lat_e7, r.lon_e7))
            .collect();
        assert!(keys.windows(2).all(|w| w[0] <= w[1]), "{keys:?}");

        // The geojson mirrors the index exactly, in the same order.
        assert!(lines[0].starts_with("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":["));
        for (line, rec) in lines.iter().zip(&records) {
            assert!(line.contains(&format!("\"name\":\"{}\"", rec.name)), "{line}");
            assert!(line.contains(&format!("\"type\":{}", rec.type_)), "{line}");
            assert!(
                line.contains(&format!("{:.7},{:.7}", rec.lon_e7 as f64 * 1e-7, rec.lat_e7 as f64 * 1e-7)),
                "{line}"
            );
        }
        // The relation's osm_id is negated so way and relation ids cannot collide.
        let park_line = lines.iter().find(|l| l.contains("Riverside Park")).unwrap();
        assert!(park_line.contains(&format!("\"osm_id\":{}", -testpbf::RELATION_ID)));
    }

    #[test]
    fn ring_centroid_doubles_the_smallest_lon_lat_vertex() {
        // libosmium sorted a ring's segments before walking them, so the ring
        // always starts — and therefore repeats — at the vertex with the smallest
        // (lon, lat). Averaging the way's own node list instead doubles the way's
        // FIRST node, which put small buildings metres off.
        let pts = [
            (1, (350_000_000, -1_200_000_000)),
            (2, (350_000_000, -1_200_010_000)), // smallest lon
            (3, (350_030_000, -1_200_010_000)),
            (4, (350_030_000, -1_200_000_000)),
        ];
        let location = |id: i64| pts.iter().find(|(i, _)| *i == id).map(|(_, l)| *l);

        // Same ring, three different starting vertices: the centroid must not move.
        for order in [
            vec![1, 2, 3, 4],
            vec![3, 4, 1, 2],
            vec![4, 3, 2, 1],
        ] {
            let (lat, lon) = ring_centroid(order.iter().copied(), &location).unwrap();
            // lat: (350.0 + 350.0 + 350.03 + 350.03 + 350.0[repeat of node 2]) / 5
            assert_eq!((lat * 1e7).round() as i64, 350_012_000, "order {order:?}");
            // lon: (-120.0 - 120.001 - 120.001 - 120.0 - 120.001) / 5
            assert_eq!((lon * 1e7).round() as i64, -1_200_006_000, "order {order:?}");
        }

        // Ties on lon are broken by lat, matching libosmium's Location ordering.
        let flat = [
            (1, (350_020_000, -1_200_000_000)),
            (2, (350_010_000, -1_200_000_000)),
        ];
        let loc2 = |id: i64| flat.iter().find(|(i, _)| *i == id).map(|(_, l)| *l);
        let (lat, _) = ring_centroid([1i64, 2].into_iter(), &loc2).unwrap();
        // Node 2 has the smaller lat, so it is the repeated vertex.
        assert_eq!((lat * 1e7).round() as i64, 350_013_333);

        // Nothing resolvable -> no POI rather than a (0, 0) centroid.
        assert!(ring_centroid([99i64].into_iter(), &location).is_none());
    }

    #[test]
    fn two_runs_are_byte_identical() {
        let (pbf_path, dir) = testpbf::write_sample("poi_det");
        let run = |suffix: &str| {
            build(
                &pbf_path,
                &dir.join(format!("pois{suffix}.geojsonseq")),
                &dir.join(format!("names{suffix}.bin")),
                &dir.join(format!("index{suffix}.bin")),
                &dir.join(format!("attrs{suffix}.bin")),
                &dir.join(format!("spatial{suffix}.bin")),
                &dir.join(format!("nameidx{suffix}.bin")),
            )
            .unwrap();
        };
        run("a");
        run("b");
        for (a, b) in [
            ("poisa.geojsonseq", "poisb.geojsonseq"),
            ("namesa.bin", "namesb.bin"),
            ("indexa.bin", "indexb.bin"),
            ("attrsa.bin", "attrsb.bin"),
            ("spatiala.bin", "spatialb.bin"),
            ("nameidxa.bin", "nameidxb.bin"),
        ] {
            assert_eq!(
                std::fs::read(dir.join(a)).unwrap(),
                std::fs::read(dir.join(b)).unwrap(),
                "{a} differs between runs"
            );
        }
    }

    /// The sidecar's join to `poi_index.bin` is by ordinal, so the two files have to
    /// have exactly the same length in records and agree row for row.
    #[test]
    fn the_attribute_sidecar_lines_up_with_the_index_by_ordinal() {
        use crate::poi_attrs::{
            KEY_CUISINE, KEY_HOUSENUMBER, KEY_OPENING_HOURS, KEY_PHONE, KEY_STREET, KEY_WEBSITE,
        };
        let (stats, dir) = build_sample("poi_attrs_join");
        let (records, _) = read_records(&dir);
        let attrs = read_attrs(&dir);

        assert_eq!(attrs.len(), records.len(), "one slot per index record");
        assert_eq!(stats.with_attrs, 1, "only the cafe node carries any");
        assert_eq!(stats.unique_attrs, 1);

        let cafe = records.iter().position(|r| r.name == "Corner Cafe").unwrap();
        assert_eq!(
            attrs[cafe],
            vec![
                (KEY_OPENING_HOURS, "24/7".to_string()),
                // The fixture tags `contact:phone` rather than `phone`, so the alias
                // has to be read.
                (KEY_PHONE, "+1-555-0100".to_string()),
                (KEY_WEBSITE, "https://cafe.example".to_string()),
                (KEY_HOUSENUMBER, "120".to_string()),
                (KEY_STREET, "Market St".to_string()),
                (KEY_CUISINE, "coffee_shop".to_string()),
            ]
        );

        for (i, a) in attrs.iter().enumerate() {
            if i != cafe {
                assert!(a.is_empty(), "{} has no attributes", records[i].name);
            }
        }
    }

    /// Adding the sidecar must not have widened the record everything else measures
    /// the file by. `record_count = filesize / 14` is asserted in the README too.
    #[test]
    fn the_index_record_is_still_fourteen_bytes() {
        let (stats, dir) = build_sample("poi_width");
        let index = std::fs::read(dir.join("poi_index.bin")).unwrap();
        assert_eq!(index.len(), stats.records * 14);
    }

    /// Review rows join on (element, OSM id) before the Morton sort, and land on the
    /// right ordinal after it. The relation row uses the plain id even though the
    /// POI stores it negated.
    #[test]
    fn review_rows_join_by_element_and_osm_id() {
        use crate::poi_attrs::{KEY_RATING, KEY_REVIEW_COUNT, KEY_REVIEW_SUMMARY, KEY_CUISINE};
        let (pbf_path, dir) = testpbf::write_sample("poi_reviews_join");
        let table = crate::poi_reviews::ReviewTable::parse(&format!(
            "node\t{}\t4.3\t120\tGreat coffee, slow at lunch.\n\
             way\t{}\t3.8\t15\t\n\
             relation\t{}\t\t40\tShady lawns.\n\
             way\t{}\t1.0\t1\tWrong element, must not match.\n",
            testpbf::CAFE_NODE_ID,
            testpbf::AREA_WAY_ID,
            testpbf::RELATION_ID,
            testpbf::CAFE_NODE_ID,
        ))
        .unwrap();
        let stats = build_with_reviews(
            &pbf_path,
            &dir.join("pois.geojsonseq"),
            &dir.join("poi_names.bin"),
            &dir.join("poi_index.bin"),
            &dir.join("poi_attrs.bin"),
            &dir.join("poi_spatial.bin"),
            &dir.join("poi_name_index.bin"),
            None,
            Some(&table),
        )
        .unwrap();
        assert_eq!(stats.with_reviews, 3);

        let (records, _) = read_records(&dir);
        let attrs = read_attrs(&dir);
        let of = |n: &str| &attrs[records.iter().position(|r| r.name == n).unwrap()];

        let cafe = of("Corner Cafe");
        assert_eq!(cafe[cafe.len() - 4], (KEY_CUISINE, "coffee_shop".to_string()), "tag fields first");
        assert_eq!(
            cafe[cafe.len() - 3..],
            [
                (KEY_RATING, "4.3".to_string()),
                (KEY_REVIEW_COUNT, "120".to_string()),
                (KEY_REVIEW_SUMMARY, "Great coffee, slow at lunch.".to_string()),
            ]
        );
        assert_eq!(
            of("Plaza"),
            &vec![(KEY_RATING, "3.8".to_string()), (KEY_REVIEW_COUNT, "15".to_string())]
        );
        assert_eq!(
            of("Riverside Park"),
            &vec![(KEY_REVIEW_COUNT, "40".to_string()), (KEY_REVIEW_SUMMARY, "Shady lawns.".to_string())]
        );
    }

    #[test]
    fn args_require_all_three_outputs() {
        let ok = parse_args(&[
            "in.pbf".into(),
            "--geojson".into(),
            "g".into(),
            "--names".into(),
            "n".into(),
            "--index".into(),
            "out/poi_index.bin".into(),
        ])
        .unwrap();
        assert_eq!(ok.input, PathBuf::from("in.pbf"));
        assert_eq!(ok.index, PathBuf::from("out/poi_index.bin"));
        // Defaulted beside the index, so a caller written before the sidecar existed
        // still emits one rather than silently skipping it.
        assert_eq!(ok.attrs, PathBuf::from("out/poi_attrs.bin"));
        assert!(ok.threads.is_none(), "the pool defaults to the box");
        assert!(parse_args(&["in.pbf".into()]).is_err());
        assert!(parse_args(&["in.pbf".into(), "--geojson".into()]).is_err());
    }

    #[test]
    fn threads_must_be_positive() {
        let base: Vec<String> = vec![
            "in.pbf".into(),
            "--geojson".into(),
            "g".into(),
            "--names".into(),
            "n".into(),
            "--index".into(),
            "i".into(),
        ];
        let mut with = base.clone();
        with.extend(["--threads".to_string(), "3".to_string()]);
        assert_eq!(parse_args(&with).unwrap().threads, Some(3));

        let mut zero = base.clone();
        zero.extend(["--threads".to_string(), "0".to_string()]);
        assert!(parse_args(&zero).is_err());

        let mut bare = base;
        bare.push("--threads".into());
        assert!(parse_args(&bare).is_err());
    }

    #[test]
    fn an_explicit_attrs_path_overrides_the_default() {
        let ok = parse_args(&[
            "in.pbf".into(),
            "--geojson".into(),
            "g".into(),
            "--names".into(),
            "n".into(),
            "--index".into(),
            "i".into(),
            "--attrs".into(),
            "elsewhere/a.bin".into(),
        ])
        .unwrap();
        assert_eq!(ok.attrs, PathBuf::from("elsewhere/a.bin"));
    }
}
