//! Tests for `transit_shapes`, moved wholesale out of `transit_shapes.rs` to keep
//! the bin under the file-size limit. Behaviour-identical: same helpers, same
//! assertions, only the module path changed.

use super::extra2::run;
use super::*;
use gtfs_ingest::gtfs::parse_csv;
use gtfs_ingest::rail::route_color;
use gtfs_ingest::manifest::FeedSpec;
use std::path::PathBuf;

#[test]
fn only_rail_route_types_are_carried() {
    assert_eq!(mode_of(0), Some("light_rail"));
    assert_eq!(mode_of(1), Some("subway"));
    assert_eq!(mode_of(2), Some("train"));
    assert_eq!(mode_of(5), Some("tram"), "cable tram");
    assert_eq!(mode_of(7), Some("tram"), "funicular");
    assert_eq!(mode_of(12), Some("monorail"));
    assert_eq!(mode_of(3), None, "a bus is not rail");
    assert_eq!(mode_of(4), None, "nor a ferry");
    assert_eq!(mode_of(11), None, "nor a trolleybus");
    // The extended ranges European feeds publish instead of the basic set.
    assert_eq!(mode_of(109), Some("train"), "suburban railway");
    assert_eq!(mode_of(117), Some("train"));
    assert_eq!(mode_of(403), Some("subway"), "metro");
    assert_eq!(mode_of(405), Some("monorail"));
    assert_eq!(mode_of(906), Some("tram"));
    assert_eq!(mode_of(700), None, "bus service");
    assert_eq!(mode_of(1200), None, "ferry service");
}

#[test]
fn a_route_colour_is_bare_hex_and_never_zero() {
    assert_eq!(route_color("E4002B"), Some(0xE4002B));
    assert_eq!(route_color(" e4002b "), Some(0xE4002B), "trimmed, case-insensitive");
    assert_eq!(route_color("#E4002B"), Some(0xE4002B), "a stray hash is tolerated");
    assert_eq!(route_color(""), None, "absent");
    assert_eq!(route_color("red"), None, "a name is not a colour");
    assert_eq!(route_color("F00"), None, "GTFS has no short form");
    assert_eq!(route_color("GGGGGG"), None, "not hex");
    // Black is the one legal value that cannot be put on the wire.
    assert_eq!(route_color("000000"), None);
}

/// The fallback table is the mode vocabulary, and none of it may be zero:
/// `Sink::push_transit` refuses a zero colour outright.
#[test]
fn every_mode_has_a_nonzero_fallback() {
    for mode in ["subway", "light_rail", "tram", "train", "monorail"] {
        assert_ne!(fallback_color(mode), 0, "{mode}");
    }
}

#[test]
fn one_line_per_distinct_shape_not_per_trip() {
    let routes = parse_csv(
        "route_id,route_short_name,route_type,route_color\n\
         SUB,N,1,0054A5\n\
         BUS,38,3,FFFFFF\n",
    );
    let rail = rail_routes(&routes);
    assert_eq!(rail.len(), 1, "the bus route is dropped");
    // Four trips over two shapes, plus a bus trip and a trip with no shape.
    let trips = parse_csv(
        "route_id,trip_id,shape_id\n\
         SUB,T1,OUTBOUND\n\
         SUB,T2,OUTBOUND\n\
         SUB,T3,INBOUND\n\
         SUB,T4,INBOUND\n\
         SUB,T5,\n\
         BUS,T6,BUSSHAPE\n",
    );
    let by_route = shape_ids_by_route(&trips, &rail);
    assert_eq!(by_route.len(), 1);
    assert_eq!(
        by_route["SUB"].iter().cloned().collect::<Vec<String>>(),
        vec!["INBOUND".to_string(), "OUTBOUND".to_string()],
        "two distinct shapes for four trips",
    );
}

/// The dedup key. Two feeds tracing the same alignment must collapse; two
/// different alignments must not.
#[test]
fn the_polyline_hash_collapses_only_the_same_alignment() {
    let line: Vec<(i32, i32)> =
        vec![(37_700_000, -122_400_000), (37_705_000, -122_390_000), (37_710_000, -122_400_000)];
    assert_eq!(polyline_hash(&line), polyline_hash(&line));
    // A sub-0.1 m difference in the last digit is the same alignment.
    let jittered: Vec<(i32, i32)> =
        vec![(37_700_003, -122_400_000), (37_705_001, -122_390_000), (37_710_000, -122_400_000)];
    assert_eq!(polyline_hash(&jittered), polyline_hash(&line));
    // A real difference is not.
    let elsewhere: Vec<(i32, i32)> =
        vec![(37_700_000, -122_400_000), (37_705_000, -122_390_000), (37_800_000, -122_400_000)];
    assert_ne!(polyline_hash(&elsewhere), polyline_hash(&line));
    // And neither is the same points in the other direction: this pass is exact
    // after rounding, and the near-match that collapses a route's two directions
    // is `bundle::same_line`, one layer up.
    let reversed: Vec<(i32, i32)> = line.iter().rev().copied().collect();
    assert_ne!(polyline_hash(&reversed), polyline_hash(&line));
}

/// End to end over two feeds that share one line, which is the case cross-feed
/// dedup exists for: Transitous ships a merged regional feed *and* its member
/// agencies, so one alignment is published twice.
#[test]
fn two_feeds_sharing_an_alignment_emit_it_once() {
    let root = std::env::temp_dir().join(format!("gtfs_shapes_{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&root);

    // `shared` is byte-identical in both feeds; each feed also has one of its
    // own. The second feed's copy of the shared route is uncoloured, so if the
    // wrong one survived the colour would change.
    let write_feed = |tag: &str, color: &str, own: &str| -> PathBuf {
        let dir = root.join(tag);
        std::fs::create_dir_all(&dir).unwrap();
        let w = |file: &str, body: String| std::fs::write(dir.join(file), body).unwrap();
        w(
            "routes.txt",
            format!(
                "route_id,route_short_name,route_type,route_color\n\
                 SHARED,S,1,{color}\n\
                 OWN,O,0,00FF00\n\
                 BUS,B,3,123456\n"
            ),
        );
        w(
            "trips.txt",
            "route_id,trip_id,shape_id\n\
             SHARED,T1,SH_SHARED\n\
             SHARED,T2,SH_SHARED\n\
             OWN,T3,SH_OWN\n\
             BUS,T4,SH_BUS\n"
                .to_string(),
        );
        w(
            "shapes.txt",
            format!(
                "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\n\
                 SH_SHARED,37.700,-122.400,1\n\
                 SH_SHARED,37.710,-122.390,2\n\
                 SH_OWN,{own},-122.300,1\n\
                 SH_OWN,{own},-122.290,2\n\
                 SH_BUS,37.600,-122.500,1\n\
                 SH_BUS,37.610,-122.490,2\n"
            ),
        );
        dir
    };

    let specs: Vec<FeedSpec> = vec![
        ("a".to_string(), write_feed("a", "0054A5", "37.800"), String::new()),
        // No colour on `b`'s copy of SHARED, so `a`'s must be the one kept.
        ("b".to_string(), write_feed("b", "", "37.900"), String::new()),
    ];

    let out = root.join("routes.geojsonseq");
    run(&out, &specs).unwrap();
    let text = std::fs::read_to_string(&out).unwrap();
    let emitted: Vec<&str> = text.lines().collect();

    assert_eq!(emitted.len(), 3, "one shared line plus one per feed: {text}");
    assert_eq!(
        text.matches("\"mode\":\"subway\"").count(),
        1,
        "the shared alignment must dedup to one feature: {text}"
    );
    assert!(text.contains("\"color\":\"0054A5\""), "the first feed's colour wins: {text}");
    assert_eq!(text.matches("\"mode\":\"light_rail\"").count(), 2, "one per feed: {text}");
    assert!(!text.contains("122.5"), "the bus shape must not be emitted: {text}");
    // Nothing on the wire may carry colour zero, and the uncoloured route took
    // its mode's fallback rather than black.
    assert!(!text.contains("\"color\":\"000000\""), "{text}");
    // Byte-identical between runs.
    let again = root.join("routes.again.geojsonseq");
    run(&again, &specs).unwrap();
    assert_eq!(std::fs::read(&out).unwrap(), std::fs::read(&again).unwrap());

    let _ = std::fs::remove_dir_all(&root);
}

/// A route's own two directions draw as one line; its branch draws only where it
/// branches. THE case: a corridor of six services drew twelve lines before this.
#[test]
fn a_routes_two_directions_draw_as_one_line() {
    let root =
        std::env::temp_dir().join(format!("gtfs_shapes_collapse_{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&root);
    std::fs::create_dir_all(&root).unwrap();
    let w = |file: &str, body: &str| std::fs::write(root.join(file), body).unwrap();
    w("routes.txt", "route_id,route_short_name,route_type,route_color\nN,N,0,00985F\n");
    w(
        "trips.txt",
        "route_id,trip_id,shape_id\nN,T1,OUT\nN,T2,BACK\nN,T3,BRANCH\n",
    );
    // OUT runs 5.5 km north; BACK is the other track, 9 m west, run the other way;
    // BRANCH follows OUT for 2.7 km and then strikes east for 4 km.
    w(
        "shapes.txt",
        "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\n\
         OUT,37.7000,-122.4000,1\n\
         OUT,37.7250,-122.4000,2\n\
         OUT,37.7500,-122.4000,3\n\
         BACK,37.7500,-122.40010,1\n\
         BACK,37.7250,-122.40010,2\n\
         BACK,37.7000,-122.40010,3\n\
         BRANCH,37.7000,-122.4000,1\n\
         BRANCH,37.7250,-122.4000,2\n\
         BRANCH,37.7250,-122.3550,3\n",
    );

    let feed = read_feed(&("solo".to_string(), root.clone(), String::new())).unwrap();
    assert_eq!(feed.shapes_read, 3, "every shape reaches the cross-feed pass whole");

    let out = root.join("routes.geojsonseq");
    run(&out, &vec![("solo".to_string(), root.clone(), String::new())]).unwrap();
    let text = std::fs::read_to_string(&out).unwrap();
    let emitted: Vec<&str> = text.lines().collect();
    assert_eq!(emitted.len(), 2, "the trunk once, plus the branch's own leg: {text}");
    // Nothing of one colour may be parallel to itself, so the second direction is gone
    // and the branch kept only what it added.
    assert_eq!(
        text.matches("\"lanes\":1").count(),
        2,
        "one service, no fan: {text}",
    );
    assert!(emitted.iter().any(|l| l.contains("-122.355")), "the branch's leg: {text}");

    let _ = std::fs::remove_dir_all(&root);
}

/// Two services over one track fan out either side of it, and a third route
/// running elsewhere stays on its own alignment.
#[test]
fn routes_sharing_a_corridor_take_lanes_either_side_of_it() {
    let root = std::env::temp_dir().join(format!("gtfs_shapes_spread_{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&root);
    std::fs::create_dir_all(&root).unwrap();
    let w = |file: &str, body: &str| std::fs::write(root.join(file), body).unwrap();
    w(
        "routes.txt",
        "route_id,route_short_name,route_type,route_color\n\
         A,A,1,0000FF\n\
         B,B,1,FF0000\n\
         C,C,1,00FF00\n",
    );
    w("trips.txt", "route_id,trip_id,shape_id\nA,T1,SA\nB,T2,SB\nC,T3,SC\n");
    // SA and SB are one track 9 m apart; SC is a kilometre east.
    let mut shapes = String::from("shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\n");
    for i in 0..21 {
        let lat = 37.7 + i as f64 * 0.0005;
        shapes.push_str(&format!("SA,{lat:.4},-122.40000,{}\n", i + 1));
        shapes.push_str(&format!("SB,{lat:.4},-122.40010,{}\n", i + 1));
        shapes.push_str(&format!("SC,{lat:.4},-122.38000,{}\n", i + 1));
    }
    w("shapes.txt", &shapes);

    let out = root.join("routes.geojsonseq");
    let specs: Vec<FeedSpec> = vec![("solo".to_string(), root.clone(), String::new())];
    run(&out, &specs).unwrap();
    let text = std::fs::read_to_string(&out).unwrap();

    assert_eq!(text.lines().count(), 3, "{text}");
    // The corridor reports two colours and hands out both ordinals; which side of the
    // track each lands on is the renderer's arithmetic, not this file's.
    assert_eq!(text.matches("\"ordinal\":0,\"lanes\":2").count(), 1, "{text}");
    assert_eq!(text.matches("\"ordinal\":1,\"lanes\":2").count(), 1, "{text}");
    // And the route with the corridor to itself is not moved off its alignment.
    assert_eq!(text.matches("\"ordinal\":0,\"lanes\":1").count(), 1, "{text}");

    // Byte-identical between runs, lanes included.
    let again = root.join("routes.again.geojsonseq");
    run(&again, &specs).unwrap();
    assert_eq!(std::fs::read(&out).unwrap(), std::fs::read(&again).unwrap());

    let _ = std::fs::remove_dir_all(&root);
}

/// The cross-feed pass the exact hash cannot do. Transitous and 511-style regional
/// feeds republish their member agencies' lines **re-surveyed**, a few metres off, so
/// the hashes differ — and every city inside such a feed would otherwise draw twice and
/// pair each line with its own duplicate into a two-route corridor instead of joining the
/// corridor it really shares.
#[test]
fn a_line_another_feed_re_surveyed_collapses_onto_the_first() {
    let root = std::env::temp_dir().join(format!("gtfs_shapes_resurvey_{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&root);

    // `city` and `regional` trace one alignment; the regional copy is shifted about 4 m
    // east and carries an extra intermediate point, so no hash can match them. The
    // regional feed also has a differently-coloured service over the *same* track,
    // which must survive: that is what the corridor slots exist to draw.
    let write_feed = |tag: &str, lon_a: &str, lon_b: &str, extra: &str| -> PathBuf {
        let dir = root.join(tag);
        std::fs::create_dir_all(&dir).unwrap();
        let w = |file: &str, body: String| std::fs::write(dir.join(file), body).unwrap();
        w(
            "routes.txt",
            "route_id,route_short_name,route_type,route_color\nN,N,1,0054A5\n".to_string(),
        );
        w("trips.txt", "route_id,trip_id,shape_id\nN,T1,SH\n".to_string());
        w(
            "shapes.txt",
            format!(
                "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\n\
                 SH,37.7000,{lon_a},1\n{extra}SH,37.7100,{lon_b},3\n"
            ),
        );
        dir
    };
    let city = write_feed("city", "-122.40000", "-122.40000", "");
    let regional = write_feed(
        "regional",
        "-122.400045",
        "-122.400045",
        "SH,37.7050,-122.400045,2\n",
    );
    // A second service of another colour over the same track, in its own feed.
    let other = root.join("other");
    std::fs::create_dir_all(&other).unwrap();
    std::fs::write(
        other.join("routes.txt"),
        "route_id,route_short_name,route_type,route_color\nK,K,1,E31E24\n",
    )
    .unwrap();
    std::fs::write(other.join("trips.txt"), "route_id,trip_id,shape_id\nK,T1,SH\n").unwrap();
    std::fs::write(
        other.join("shapes.txt"),
        "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\n\
         SH,37.7000,-122.40000,1\n\
         SH,37.7100,-122.40000,2\n",
    )
    .unwrap();

    let specs: Vec<FeedSpec> = vec![
        ("city".to_string(), city, String::new()),
        ("regional".to_string(), regional, String::new()),
        ("other".to_string(), other, String::new()),
    ];
    let out = root.join("routes.geojsonseq");
    run(&out, &specs).unwrap();
    let text = std::fs::read_to_string(&out).unwrap();

    assert_eq!(
        text.matches("\"color\":\"0054A5\"").count(),
        1,
        "the re-surveyed republication must collapse onto the first: {text}",
    );
    assert_eq!(
        text.matches("\"color\":\"E31E24\"").count(),
        1,
        "a different service over the same track must survive: {text}",
    );
    // And the two that survive share the track, so they fan out either side of it
    // rather than both collapsing to one lane.
    assert_eq!(text.matches("\"ordinal\":0,\"lanes\":2").count(), 1, "{text}");
    assert_eq!(text.matches("\"ordinal\":1,\"lanes\":2").count(), 1, "{text}");

    let _ = std::fs::remove_dir_all(&root);
}

/// A rail route whose trips name a shape the feed does not carry is reported,
/// not silently emitted as an empty line.
#[test]
fn a_route_with_no_usable_shape_is_counted_and_drawn_nothing() {
    let root = std::env::temp_dir().join(format!("gtfs_shapes_noshape_{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&root);
    std::fs::create_dir_all(&root).unwrap();
    let w = |file: &str, body: &str| std::fs::write(root.join(file), body).unwrap();
    w("routes.txt", "route_id,route_short_name,route_type,route_color\nR,R,2,\n");
    w("trips.txt", "route_id,trip_id,shape_id\nR,T1,MISSING\n");
    // One point is not a line, so even a present shape can be unusable.
    w(
        "shapes.txt",
        "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\nOTHER,37.7,-122.4,1\n",
    );

    let feed = read_feed(&("solo".to_string(), root.clone(), String::new())).unwrap();
    assert_eq!(feed.routes_kept, 1);
    assert_eq!(feed.routes_without_shape, 1);
    assert!(feed.lines.is_empty());

    let _ = std::fs::remove_dir_all(&root);
}

/// The cover gates resolve a window of lines speculated in parallel; a window of one line is
/// the sequential fold exactly, and every other window must reproduce it byte for byte. The
/// set is built to make the windows disagree if anything were stale: many routes republishing
/// one track a few metres apart (the colour gate), uncoloured copies (the mode gate), five or
/// more colours over one stretch (the crowd gate), short-turns, and lines long enough to take
/// the wide per-line and sharded-add paths.
#[test]
fn the_speculative_window_changes_no_byte() {
    let root = std::env::temp_dir().join(format!("gtfs_shapes_window_{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&root);
    std::fs::create_dir_all(&root).unwrap();
    let colours = ["0054A5", "E31E24", "00A651", "", "FFC20E", "8E44AD", "", "0054A5"];
    let mut routes = String::from("route_id,route_short_name,route_type,route_color\n");
    let mut trips = String::from("route_id,trip_id,shape_id\n");
    let mut shapes = String::from("shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\n");
    let mut x = 0x2545_F491_4F6C_DD1Du64;
    let mut next = move || {
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        x
    };
    for r in 0..60u64 {
        let colour = colours[(next() % colours.len() as u64) as usize];
        let route_type = [1, 1, 0, 2][(next() % 4) as usize];
        routes.push_str(&format!("R{r},L{},{route_type},{colour}\n", r % 9));
        trips.push_str(&format!("R{r},T{r},S{r}\n"));
        // Up to ~1.1 degrees (~120 km) of one north-south trunk, starting anywhere along its
        // first half, offset sideways by 0-40 m, with a kink partway.
        let start = 37.0 + (next() % 500) as f64 * 1e-3;
        let length = [0.02, 0.1, 0.5, 1.1][(next() % 4) as usize];
        let lon = -122.0 + (next() % 40) as f64 * 1e-5;
        let kink = (next() % 3) as f64 * 1e-4;
        let points = [(start, lon), (start + length * 0.4, lon + kink), (start + length, lon)];
        for (seq, (lat, lon)) in points.iter().enumerate() {
            shapes.push_str(&format!("S{r},{lat:.7},{lon:.7},{}\n", seq + 1));
        }
    }
    std::fs::write(root.join("routes.txt"), routes).unwrap();
    std::fs::write(root.join("trips.txt"), trips).unwrap();
    std::fs::write(root.join("shapes.txt"), shapes).unwrap();
    let specs: Vec<FeedSpec> = vec![("trunk".to_string(), root.clone(), String::new())];

    let render = |window: usize| -> String {
        let out = root.join(format!("w{window}.geojsonseq"));
        super::extra2::run_windowed(&out, super::extra2::Inputs::Feeds(&specs), None, window).unwrap();
        std::fs::read_to_string(&out).unwrap()
    };
    let sequential = render(1);
    assert!(sequential.lines().count() > 3, "the set must keep several lines: {sequential}");
    for window in [2, 3, 7, 16, 64] {
        assert_eq!(render(window), sequential, "window {window}");
    }
    let _ = std::fs::remove_dir_all(&root);
}
