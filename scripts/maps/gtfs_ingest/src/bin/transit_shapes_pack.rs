//! Lines for `transit_shapes` read from a built `world.transit` pack instead of raw GTFS.
//!
//! A v7 pack's RAIL_LINES section holds exactly what [`read_feed`] takes out of each raw feed:
//! every whole, unsimplified shape of every rail route, with its route's type, colour, name and
//! id, in the same order. So the fold, corridor pass and writer downstream produce the same layer
//! from the pack as from the ~80 GB of feeds it was built from.

use super::*;
use gtfs_ingest::reader::Reader;
use rayon::prelude::*;

/// Every RAIL_LINES entry as a candidate line, in pack order.
pub(crate) fn pack_lines(path: &Path) -> Result<FeedLines, String> {
    // Only what this reads: the RAIL_LINES records, the feed table and the string pool their
    // names live in — about 300 MB of a 4.4 GB world pack.
    let pack = Reader::open_sections(
        path,
        &[gtfs_ingest::reader::SEC_STRINGS, gtfs_ingest::reader::SEC_FEEDS, gtfs_ingest::reader::SEC_RAIL_LINES],
    )?;
    let Some(recs) = pack.rail_lines() else {
        return Err(format!(
            "{} is a v{} pack with no RAIL_LINES section; rebuild it with scripts/maps/build_world_transit.ps1",
            path.display(),
            pack.version(),
        ));
    };
    let feeds: Vec<String> = (0..pack.feed_count()).map(|f| pack.feed_name(f)).collect();
    let mut out = FeedLines::default();
    // Built wide (the intercity test walks every line), then taken in pack order, so the first
    // bad record is still the one reported.
    let built: Vec<Result<Line, String>> = recs
        .into_par_iter()
        .map(|rec| {
            let Some(mode) = mode_of(rec.route_type) else {
                return Err(format!("RAIL_LINES carries route_type {}, which is not rail", rec.route_type));
            };
            let (color, fallback) = match rec.color {
                0 => (fallback_color(mode), true),
                c => (c, false),
            };
            Ok(Line {
                intercity: intercity::is_intercity(intercity::is_long_distance_type(rec.route_type), &rec.points),
                points: rec.points,
                color,
                mode,
                fallback,
                route: rec.name,
                route_key: format!("{}\u{0}{}", feeds[rec.feed_idx as usize], rec.route_id),
                ordinal: 0,
                lanes: 1,
                taper: 255,
            })
        })
        .collect();
    for line in built {
        out.lines.push(line?);
        out.shapes_read += 1;
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use gtfs_ingest::index::{FeedDir, IndexBuilder};

    /// Two feeds sharing a rail alignment plus a bus, through the real pack builder: the layer
    /// derived from the pack is byte-identical to the one derived from the feeds.
    #[test]
    fn a_pack_draws_exactly_the_lines_of_the_feeds_it_was_built_from() {
        let root = std::env::temp_dir().join(format!("transit_shapes_pack_{}", std::process::id()));
        let feed = |tag: &str, rail_color: &str, lat: &str| -> PathBuf {
            let dir = root.join(tag);
            std::fs::create_dir_all(&dir).unwrap();
            let w = |f: &str, s: String| std::fs::write(dir.join(f), s).unwrap();
            w("agency.txt", "agency_id,agency_name,agency_url,agency_timezone\nA,A,http://a,America/Los_Angeles\n".into());
            w("stops.txt", format!("stop_id,stop_name,stop_lat,stop_lon\nS1,One,37.700,-122.40\nS2,Two,{lat},-122.40\n"));
            w("routes.txt", format!(
                "route_id,route_short_name,route_long_name,route_type,route_color\nR,Rail,,2,{rail_color}\nB,Bus,,3,FF0000\n"
            ));
            w("trips.txt", "route_id,service_id,trip_id,shape_id\nR,WK,T1,SH1\nR,WK,T2,SH2\nB,WK,T3,SH1\n".into());
            w("stop_times.txt", "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n\
                T1,08:00:00,08:00:00,S1,1\nT1,08:10:00,08:10:00,S2,2\n\
                T2,09:00:00,09:00:00,S2,1\nT2,09:10:00,09:10:00,S1,2\n\
                T3,10:00:00,10:00:00,S1,1\nT3,10:10:00,10:10:00,S2,2\n".into());
            w("calendar.txt", "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\nWK,1,1,1,1,1,0,0,20240101,20301231\n".into());
            // A long outbound shape at full precision and a short return with a kink.
            w("shapes.txt", format!(
                "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\n\
                 SH1,37.6999999,-122.4000001,1\nSH1,37.75,-122.4,2\nSH1,{lat},-122.4,3\n\
                 SH2,{lat},-122.4,1\nSH2,37.75,-122.39,2\nSH2,37.7,-122.4,3\n"
            ));
            dir
        };
        let specs: Vec<FeedSpec> = vec![
            ("a".to_string(), feed("a", "0054A5", "37.800"), String::new()),
            ("b".to_string(), feed("b", "", "39.500"), String::new()),
        ];
        let mut builder = IndexBuilder::new("test");
        for (name, dir, prefix) in &specs {
            let table = |f: &str| gtfs::read_table(dir, f);
            let (stops, routes, trips) =
                (table("stops.txt").unwrap(), table("routes.txt").unwrap(), table("trips.txt").unwrap());
            let (calendar, agency, shapes) = (table("calendar.txt"), table("agency.txt"), gtfs::read_shapes(dir));
            builder
                .add_feed_dir(&FeedDir {
                    name: name.clone(),
                    motis_prefix: prefix.clone(),
                    dir,
                    stops: &stops,
                    routes: &routes,
                    trips: &trips,
                    calendar: calendar.as_ref(),
                    calendar_dates: None,
                    agency: agency.as_ref(),
                    shapes: shapes.as_ref(),
                })
                .unwrap();
        }
        let mut blob = Vec::new();
        builder.finish_to(&mut blob).unwrap();
        let pack = root.join("test.transit");
        std::fs::write(&pack, &blob).unwrap();

        let (from_feeds, from_pack) = (root.join("feeds.geojsonseq"), root.join("pack.geojsonseq"));
        super::super::extra2::run_with_timer(&from_feeds, super::super::extra2::Inputs::Feeds(&specs), None).unwrap();
        super::super::extra2::run_with_timer(&from_pack, super::super::extra2::Inputs::Pack(&pack), None).unwrap();
        let (a, b) = (std::fs::read_to_string(&from_feeds).unwrap(), std::fs::read_to_string(&from_pack).unwrap());
        let stored = Reader::open(&pack).unwrap().rail_lines().expect("a v7 pack");
        let _ = std::fs::remove_dir_all(&root);
        assert!(
            stored.iter().any(|l| l.points.first() == Some(&(376_999_999, -1_224_000_001)) && l.points.len() == 3),
            "every shapes.txt point, at full e7 precision"
        );
        assert_eq!(stored.len(), 4, "two shapes per feed, and no bus");
        assert!(a.contains("\"intercity\":1"), "the 200 km line is intercity");
        assert!(!a.contains("FF0000"), "no bus");
        assert_eq!(a, b, "the pack-derived layer is byte-identical");
    }

    #[test]
    fn a_v6_pack_is_refused_with_a_rebuild_hint() {
        let path = std::env::temp_dir().join(format!("transit_shapes_v6_{}.transit", std::process::id()));
        let mut blob = vec![0u8; gtfs_ingest::index::HEADER_LEN + 27 * 16];
        blob[0..4].copy_from_slice(&gtfs_ingest::index::MAGIC.to_le_bytes());
        blob[4..8].copy_from_slice(&6u32.to_le_bytes());
        blob[8..12].copy_from_slice(&27u32.to_le_bytes());
        let end = (blob.len() as u64).to_le_bytes();
        for i in 0..27 {
            let at = gtfs_ingest::index::HEADER_LEN + i * 16;
            blob[at..at + 8].copy_from_slice(&end);
        }
        std::fs::write(&path, &blob).unwrap();
        let err = pack_lines(&path).err().expect("refused");
        let _ = std::fs::remove_file(&path);
        assert!(err.contains("rebuild"), "{err}");
    }
}
