
    // --- WS-F: simulated in-service vehicle positions ---

    /// A bbox comfortably around the `one_route_pack` stops (which sit on the
    /// -122.400 meridian between 37.700 and 37.720).
    const VEH_BBOX: (f64, f64, f64, f64) = (37.69, -122.41, 37.73, -122.39);

    #[test]
    fn a_vehicle_sits_exactly_on_a_stop_at_its_scheduled_time() {
        let idx = one_route_pack().index();
        let (m0, m1, m2, m3) = VEH_BBOX;
        // 08:00: the 08:00 trip is dwelling at Alpha (stop 0).
        let vs = active_vehicles(&idx, 28_800, sched(wednesday()), m0, m1, m2, m3);
        assert_eq!(vs.len(), 1, "exactly the one in-service trip");
        assert!((vs[0].lat - 37.700).abs() < 1e-9, "at Alpha's latitude");
        assert!((vs[0].lon - (-122.400)).abs() < 1e-9, "at Alpha's longitude");

        // 08:10: it has reached Gamma, the terminus (stop 2), exactly on its coord.
        let vs = active_vehicles(&idx, 29_400, sched(wednesday()), m0, m1, m2, m3);
        assert_eq!(vs.len(), 1, "the 09:00 trip has not started yet");
        assert!((vs[0].lat - 37.720).abs() < 1e-9);
        assert!((vs[0].lon - (-122.400)).abs() < 1e-9);
    }

    #[test]
    fn a_vehicle_between_two_stops_lies_between_them() {
        let idx = one_route_pack().index();
        let (m0, m1, m2, m3) = VEH_BBOX;
        // 08:02:30: halfway between Alpha (departs 28_800) and Beta (arrives 29_100).
        let vs = active_vehicles(&idx, 28_950, sched(wednesday()), m0, m1, m2, m3);
        assert_eq!(vs.len(), 1);
        let v = &vs[0];
        assert!(
            v.lat > 37.700 && v.lat < 37.710,
            "strictly between Alpha and Beta, got {}",
            v.lat
        );
        assert!((v.lat - 37.705).abs() < 1e-6, "halfway, got {}", v.lat);
        assert!((v.lon - (-122.400)).abs() < 1e-9);
        // Alpha -> Beta is due north (same longitude, rising latitude).
        assert!(v.bearing < 1.0 || v.bearing > 359.0, "heading north, got {}", v.bearing);
    }

    #[test]
    fn a_cancelled_trip_shows_no_vehicle() {
        let idx = one_route_pack().index();
        let (m0, m1, m2, m3) = VEH_BBOX;
        // Cancel the 08:00 departure from Alpha (route 0, stop position 0).
        let overlay = DelayOverlay::build(
            &idx,
            &[DelayEntry {
                lat: 37.700,
                lon: -122.400,
                route_name: "N".to_string(),
                sched_secs: 28_800,
                delay_secs: 0,
                cancelled: true,
            }],
        );
        // At 08:02:30 the 08:00 trip would be mid-route, but it is cancelled and
        // the 09:00 trip is not yet running.
        let vs = active_vehicles(
            &idx,
            28_950,
            Schedule { day: wednesday(), overlay: Some(&overlay) },
            m0,
            m1,
            m2,
            m3,
        );
        assert!(vs.is_empty(), "a cancelled trip yields no vehicle");
    }

    #[test]
    fn a_delayed_vehicle_is_placed_at_its_live_position() {
        let idx = one_route_pack().index();
        let (m0, m1, m2, m3) = VEH_BBOX;
        let overlay = DelayOverlay::build(
            &idx,
            &[DelayEntry {
                lat: 37.700,
                lon: -122.400,
                route_name: "N".to_string(),
                sched_secs: 28_800,
                delay_secs: 300,
                cancelled: false,
            }],
        );
        // 08:05: a 5-min-late trip is where the schedule put it at 08:00 — dwelling
        // at Alpha.
        let vs = active_vehicles(
            &idx,
            29_100,
            Schedule { day: wednesday(), overlay: Some(&overlay) },
            m0,
            m1,
            m2,
            m3,
        );
        assert_eq!(vs.len(), 1);
        assert!((vs[0].lat - 37.700).abs() < 1e-9, "held back to Alpha by the delay");
        // Schedule-only, the same instant would already have it dwelling at Beta.
        let vs2 = active_vehicles(&idx, 29_100, sched(wednesday()), m0, m1, m2, m3);
        assert!((vs2[0].lat - 37.710).abs() < 1e-9, "schedule-only is at Beta");
    }

    #[test]
    fn an_overnight_trip_is_placed_in_the_query_day_frame() {
        let mut pack = one_route_pack();
        // A single 24:30:00 trip: GTFS files it under the previous service day.
        pack.routes[0].trips = vec![Trip {
            start: 88_200,
            stoptimes: vec![(88_200, 88_200), (88_500, 88_500), (88_800, 88_800)],
            service: 0,
            headsign: "Owl",
        }];
        let idx = pack.index();
        let (m0, m1, m2, m3) = VEH_BBOX;
        let day = QueryDay {
            weekday: 3,
            date: 20_240_104,
            prev_weekday: 2,
            prev_date: 20_240_103,
        };
        // 00:32:30 Thursday: the trip runs 00:30->00:38 in Thursday's frame, so it
        // is halfway between Alpha (departs 00:30) and Beta (arrives 00:35).
        let vs = active_vehicles(&idx, 1_950, sched(day), m0, m1, m2, m3);
        assert_eq!(vs.len(), 1, "the overnight trip is in service after midnight");
        assert!(vs[0].lat > 37.700 && vs[0].lat < 37.710, "between Alpha and Beta");
    }

    #[test]
    fn a_vehicle_follows_the_gtfs_shape_between_stops() {
        let idx = shaped_route_pack().index();
        let (m0, m1, m2, m3) = VEH_BBOX;
        // Mid Alpha->Beta hop: the shape detours east, so the point must swing off
        // the straight -122.400 line the two stops share.
        let vs = active_vehicles(&idx, 28_950, sched(wednesday()), m0, m1, m2, m3);
        assert_eq!(vs.len(), 1);
        assert!(vs[0].lon > -122.400, "on the eastward shape detour, got {}", vs[0].lon);
    }

    #[test]
    fn a_route_outside_the_bbox_is_not_enumerated() {
        let idx = one_route_pack().index();
        // Far from the SF stops: nothing to place, and the route is culled before
        // its trips are ever scanned.
        let vs = active_vehicles(&idx, 28_950, sched(wednesday()), 40.0, -75.0, 41.0, -74.0);
        assert!(vs.is_empty(), "the route is nowhere near this bbox");
    }

    #[test]
    fn no_vehicle_before_the_first_departure_or_after_the_last_arrival() {
        let idx = one_route_pack().index();
        let (m0, m1, m2, m3) = VEH_BBOX;
        // 07:00: before the 08:00 trip departs and long before the 09:00 one.
        assert!(active_vehicles(&idx, 25_200, sched(wednesday()), m0, m1, m2, m3).is_empty());
        // 09:20: after the 09:00 trip has reached its terminus (09:10).
        assert!(active_vehicles(&idx, 33_600, sched(wednesday()), m0, m1, m2, m3).is_empty());
    }

    // --- Single-archive container: the transit pack as section 13 ---

    use tilecodec::mamaps::archive::{
        ARCHIVE_ALIGN, ARCHIVE_KIND_TRANSIT, ArchiveEntry, ArchiveFooter,
    };
    use tilecodec::mamaps::header::Header;

    const ARCHIVE_BUILD_ID: u64 = 0x0123_4567_89AB_CDEF;

    fn archive_header(file_len: u64) -> Header {
        Header {
            flags: 0,
            compression: 0,
            layer_count: 12,
            min_zoom: 0,
            max_zoom: 14,
            build_id: ARCHIVE_BUILD_ID,
            file_len,
            dict_offset: 128,
            dict_len: 64,
            leaf_entry_capacity: 4096,
            root_offset: 192,
            root_len: 32,
            leaf_count: 1,
            leaf_offset: 224,
            leaf_len: 16,
            data_offset: 240,
            data_len: 3856,
            tiles_addressed: 1,
            bodies_written: 1,
            min_lon_e7: 0,
            min_lat_e7: 0,
            max_lon_e7: 0,
            max_lat_e7: 0,
        }
    }

    // Whole file: tile prefix (zeroes under a real serialized header) + the
    // pack laid out 8-aligned + directory + footer.
    fn archive_with_pack(pack: &[u8]) -> Vec<u8> {
        let mut header = archive_header(0);
        let mut out = vec![0u8; 4096];
        while out.len() as u64 % ARCHIVE_ALIGN != 0 {
            out.push(0);
        }
        let offset = out.len() as u64;
        out.extend_from_slice(pack);
        let entries = vec![ArchiveEntry {
            kind: ARCHIVE_KIND_TRANSIT,
            flags: 0,
            offset,
            len: pack.len() as u64,
            extra: 0,
        }];
        while out.len() as u64 % ARCHIVE_ALIGN != 0 {
            out.push(0);
        }
        let dir_offset = out.len() as u64;
        let (dir, _) = tilecodec::mamaps::archive::serialize_dir(&entries, ARCHIVE_BUILD_ID, dir_offset);
        out.extend_from_slice(&dir);
        let footer = ArchiveFooter {
            dir_offset,
            dir_len: dir.len() as u64,
            build_id: ARCHIVE_BUILD_ID,
        };
        out.extend_from_slice(&footer.serialize());
        header.file_len = out.len() as u64;
        let head = header.serialize();
        out[..head.len()].copy_from_slice(&head);
        out
    }

    fn pack_section(bytes: &[u8]) -> Vec<u8> {
        let header = Header::parse(bytes).expect("header parses");
        let view = tilecodec::mamaps::archive::ArchiveView::parse(bytes, &header)
            .expect("sidecar parses");
        view.section(bytes, ARCHIVE_KIND_TRANSIT).expect("a transit section").to_vec()
    }

    #[test]
    fn a_pack_sliced_from_the_archive_plans_the_same_journey() {
        // The pack bytes through the container must read exactly as from the
        // pack's own file: same journey, same 08:00 trip.
        let pack = one_route_pack().build_with_version(7);
        let bytes = archive_with_pack(&pack);
        let idx = TransitIndex::from_bytes(pack_section(&bytes)).expect("section parses");
        let legs = plan(&idx, 37.700, -122.400, 37.720, -122.400, 25_000, sched(wednesday()))
            .expect("a journey exists");
        let ride = legs.iter().find(|l| l.kind == LegKind::Ride).expect("a ride leg");
        assert_eq!(ride.name, "N");
        assert_eq!(ride.from_stop, "Alpha");
        assert_eq!(ride.to_stop, "Gamma");
        assert_eq!(ride.dep_secs, 28_800);
        assert_eq!(ride.arr_secs, 29_400);
    }

    #[test]
    fn an_archive_without_a_transit_section_yields_no_index() {
        // A tiles-only archive (no section 13) is not a transit failure: the
        // caller degrades to no-transit, the same as a missing .transit file.
        let mut header = archive_header(0);
        let mut out = vec![0u8; 4096];
        while out.len() as u64 % ARCHIVE_ALIGN != 0 {
            out.push(0);
        }
        let dir_offset = out.len() as u64;
        let (dir, _) = tilecodec::mamaps::archive::serialize_dir(&[], ARCHIVE_BUILD_ID, dir_offset);
        out.extend_from_slice(&dir);
        let footer = ArchiveFooter {
            dir_offset,
            dir_len: dir.len() as u64,
            build_id: ARCHIVE_BUILD_ID,
        };
        out.extend_from_slice(&footer.serialize());
        header.file_len = out.len() as u64;
        let head = header.serialize();
        out[..head.len()].copy_from_slice(&head);
        let view = tilecodec::mamaps::archive::ArchiveView::parse(&out, &header)
            .expect("sidecar parses");
        assert!(
            view.location(ARCHIVE_KIND_TRANSIT).is_none(),
            "no section 13, no transit"
        );
    }

    // File-backed `load_archive` end to end, Unix only: the host stub cannot
    // mmap, so this is where the mapping half is exercised (CI runs it).
    #[cfg(unix)]
    #[test]
    fn load_archive_maps_one_file_and_plans() {
        let pack = one_route_pack().build_with_version(7);
        let bytes = archive_with_pack(&pack);
        let path = std::env::temp_dir().join(format!(
            "transit_archive_{}.mamaps",
            std::process::id()
        ));
        std::fs::write(&path, &bytes).unwrap();
        let idx =
            TransitIndex::load_archive(path.to_str().unwrap()).expect("an archived pack loads");
        let legs = plan(&idx, 37.700, -122.400, 37.720, -122.400, 25_000, sched(wednesday()))
            .expect("a journey exists");
        let ride = legs.iter().find(|l| l.kind == LegKind::Ride).expect("a ride leg");
        assert_eq!((ride.dep_secs, ride.arr_secs), (28_800, 29_400));
        std::fs::remove_file(&path).ok();
    }

    // The Kotlin discovery gate's presence probe: an archive with section 13
    // reads present, a tiles-only archive (or a missing/garbage file) reads
    // absent. Unix only, same mmap reason as the test above.
    #[cfg(unix)]
    #[test]
    fn has_archive_transit_sees_section_thirteen() {
        let pack = one_route_pack().build_with_version(7);
        let bytes = archive_with_pack(&pack);
        let path = std::env::temp_dir().join(format!(
            "transit_presence_{}.mamaps",
            std::process::id()
        ));
        std::fs::write(&path, &bytes).unwrap();
        assert!(
            TransitIndex::has_archive_transit(path.to_str().unwrap()),
            "an archive built with a transit section reads present"
        );
        std::fs::remove_file(&path).ok();
    }

    #[cfg(unix)]
    #[test]
    fn has_archive_transit_refuses_a_tiles_only_archive() {
        // Same tiles-only container as
        // `an_archive_without_a_transit_section_yields_no_index`: no section
        // 13, so the probe reads absent and Kotlin keeps the legacy
        // per-file path rather than advertising transit it cannot plan.
        let mut header = archive_header(0);
        let mut out = vec![0u8; 4096];
        while out.len() as u64 % ARCHIVE_ALIGN != 0 {
            out.push(0);
        }
        let dir_offset = out.len() as u64;
        let (dir, _) = tilecodec::mamaps::archive::serialize_dir(&[], ARCHIVE_BUILD_ID, dir_offset);
        out.extend_from_slice(&dir);
        let footer = ArchiveFooter {
            dir_offset,
            dir_len: dir.len() as u64,
            build_id: ARCHIVE_BUILD_ID,
        };
        out.extend_from_slice(&footer.serialize());
        header.file_len = out.len() as u64;
        let head = header.serialize();
        out[..head.len()].copy_from_slice(&head);
        let path = std::env::temp_dir().join(format!(
            "transit_presence_tiles_only_{}.mamaps",
            std::process::id()
        ));
        std::fs::write(&path, &out).unwrap();
        assert!(
            !TransitIndex::has_archive_transit(path.to_str().unwrap()),
            "a tiles-only archive reads absent"
        );
        assert!(
            !TransitIndex::has_archive_transit(
                path.with_extension("missing").to_str().unwrap()
            ),
            "a missing file reads absent rather than panicking"
        );
        std::fs::remove_file(&path).ok();
    }
