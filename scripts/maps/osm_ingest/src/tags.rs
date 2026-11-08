//! OSM tag interpretation: road types, `maxspeed`, `turn:lanes`, and the POI
//! classifier.
//!
//! The lane bit masks and the POI type numbers are stable on-disk contracts —
//! `LANE_*` with `maps/src/main/rust/src/graph.rs`, the POI type numbers with
//! `maps/src/main/java/com/vayunmathur/maps/util/PoiIndex.kt` and the
//! `scripts/maps/README.md` type table. Never renumber an existing POI type;
//! only append.

use crate::tags_extra::parse_leading_f64;

// --- OSM turn:lanes indication bits (one u16 mask per lane) ---
pub const LANE_NONE: u16 = 1 << 0;
pub const LANE_THROUGH: u16 = 1 << 1;
pub const LANE_LEFT: u16 = 1 << 2;
pub const LANE_SLIGHT_LEFT: u16 = 1 << 3;
pub const LANE_SHARP_LEFT: u16 = 1 << 4;
pub const LANE_RIGHT: u16 = 1 << 5;
pub const LANE_SLIGHT_RIGHT: u16 = 1 << 6;
pub const LANE_SHARP_RIGHT: u16 = 1 << 7;
pub const LANE_REVERSE: u16 = 1 << 8;
pub const LANE_MERGE_TO_LEFT: u16 = 1 << 9;
pub const LANE_MERGE_TO_RIGHT: u16 = 1 << 10;

/// Road type number for a `highway=*` value; 0 means "not a routable road", and
/// such ways are skipped entirely.
///
/// A `*_link` slip road takes its parent's number. Without that every motorway
/// interchange ramp was dropped from the routing graph, so changing freeways
/// meant leaving them on surface roads (I-15 to I-70 at Cove Fort routed over
/// Fremont Canyon Road, 173 km instead of ~110 km).
pub fn get_hw_id(value: Option<&str>) -> u8 {
    match value.unwrap_or("") {
        "motorway" | "motorway_link" => 1,
        "trunk" | "trunk_link" => 2,
        "primary" | "primary_link" => 3,
        "secondary" | "secondary_link" => 4,
        "tertiary" | "tertiary_link" => 5,
        "unclassified" => 6,
        "residential" => 7,
        "service" => 8,
        "living_street" => 9,
        "pedestrian" => 10,
        "track" => 11,
        "footway" => 12,
        "cycleway" => 13,
        "path" => 14,
        "steps" => 15,
        _ => 0,
    }
}

/// Speed limit in km/h. Mirrors the old `strtod`-based parse: a leading number,
/// optional spaces, then an optional `mph`/`knots` unit. Anything else (`none`,
/// `signals`, `walk`) yields 0, meaning "use the road-type default".
pub fn parse_maxspeed(value: Option<&str>) -> u8 {
    let s = match value {
        Some(s) => s,
        None => return 0,
    };
    let (val, rest) = parse_leading_f64(s);
    if val <= 0.0 {
        return 0;
    }
    match rest.trim_start_matches(' ') {
        "mph" => (val * 1.60934).round() as u8,
        "knots" => (val * 1.852).round() as u8,
        _ => val as u8,
    }
}

/// Positive integer tag (`lanes`, `lanes:forward`, ...); 0 when absent or not a
/// positive number. Mirrors `atoi`: a trailing suffix is ignored.
pub fn parse_int_tag(value: Option<&str>) -> u32 {
    let s = match value {
        Some(s) => s,
        None => return 0,
    };
    let t = s.trim_start();
    let digits: String = t.chars().take_while(|c| c.is_ascii_digit()).collect();
    digits.parse::<u32>().unwrap_or(0)
}

/// Carriageway width in metres from a `width`-style tag; 0 when absent or not a
/// positive number.
///
/// Bare values and an `m` suffix are metres, which is the OSM default. Feet are
/// the one other unit the tag is written in often enough to matter, and are the
/// reason this cannot just be [`parse_int_tag`]: US data carries `width=10'`, and
/// reading that as 10 metres would make a residential street wider than a
/// motorway.
pub fn parse_width_m(value: Option<&str>) -> f64 {
    let s = match value {
        Some(s) => s,
        None => return 0.0,
    };
    let (val, rest) = parse_leading_f64(s);
    if val <= 0.0 {
        return 0.0;
    }
    // Trimmed and case-folded: `"10 ft "` and `"10 FT"` are the same ten feet, and
    // reading either as ten metres is the exact mistake this exists to prevent.
    match rest.trim().to_ascii_lowercase().as_str() {
        "ft" | "feet" | "'" => val * 0.3048,
        _ => val,
    }
}

/// One `turn:lanes` lane token, which may hold several `;`-separated
/// indications (e.g. `through;right`). `none`, empty and unrecognised values
/// contribute no bit, so a token with nothing recognised becomes `LANE_NONE`.
pub fn parse_lane_token(token: &str) -> u16 {
    let mut mask = 0u16;
    for ind in token.split(';') {
        mask |= match ind.trim_matches(|c| c == ' ' || c == '\t') {
            "left" => LANE_LEFT,
            "slight_left" => LANE_SLIGHT_LEFT,
            "sharp_left" => LANE_SHARP_LEFT,
            "through" => LANE_THROUGH,
            "right" => LANE_RIGHT,
            "slight_right" => LANE_SLIGHT_RIGHT,
            "sharp_right" => LANE_SHARP_RIGHT,
            "reverse" => LANE_REVERSE,
            "merge_to_left" => LANE_MERGE_TO_LEFT,
            "merge_to_right" => LANE_MERGE_TO_RIGHT,
            _ => 0,
        };
    }
    if mask == 0 {
        LANE_NONE
    } else {
        mask
    }
}

/// Split a `|`-separated `turn:lanes` value into one mask per lane, left to
/// right. Empty for an absent or empty tag.
pub fn parse_turn_lanes(value: Option<&str>) -> Vec<u16> {
    match value {
        Some(v) if !v.is_empty() => v.split('|').map(parse_lane_token).collect(),
        _ => Vec::new(),
    }
}

/// Upper bound on a lane count taken from a `lanes*` tag. The count only pads or
/// truncates a real `turn:lanes` list, and it has to fit the `u16` the lane
/// record stores, so a mistagged `lanes=999999999` must not become an
/// allocation size.
pub const MAX_LANES: u32 = 64;

/// Per-lane masks for one direction. Real `turn:lanes` win; a known plain lane
/// count only pads with `LANE_NONE` or truncates so the count stays accurate.
/// Empty means "no lane data", and the router falls back to topology inference.
pub fn build_dir_lanes(turn_spec: Option<&str>, count_hint: u32) -> Vec<u16> {
    let mut lanes = parse_turn_lanes(turn_spec);
    if lanes.is_empty() {
        return lanes;
    }
    if count_hint > 0 {
        lanes.resize(count_hint.min(MAX_LANES) as usize, LANE_NONE);
    }
    lanes.truncate(MAX_LANES as usize);
    lanes
}

/// True for the `highway`/`railway`/`public_transport` values that mark a node
/// as a transit stop worth pinning into the road graph.
pub fn is_stop_node(highway: Option<&str>, railway: Option<&str>, public_transport: Option<&str>) -> bool {
    matches!(highway, Some("bus_stop" | "bus_station" | "tram_stop"))
        || matches!(railway, Some("station" | "halt" | "tram_stop" | "stop"))
        || matches!(
            public_transport,
            Some("stop_position" | "platform" | "station")
        )
}

// ================================ POI TYPES ================================

/// Catch-all bucket for a recognised POI key with an unmapped value.
pub const TYPE_OTHER: u16 = 255;

/// Place labels (cities, towns, …) are searchable POI-index records. Appended —
/// never renumber — like every other POI type number.
pub const TYPE_PLACE: u16 = 51;

fn amenity_type(v: &str) -> Option<u16> {
    Some(match v {
        "restaurant" | "food_court" => 0,
        "cafe" | "ice_cream" => 1,
        "fast_food" => 2,
        "bar" | "pub" | "biergarten" | "nightclub" => 3,
        "fuel" => 6,
        "pharmacy" => 7,
        "bank" | "atm" | "bureau_de_change" => 9,
        "hospital" | "clinic" => 10,
        "school" | "college" | "university" | "kindergarten" | "language_school"
        | "driving_school" => 11,
        "place_of_worship" => 14,
        "parking" | "parking_entrance" | "bicycle_parking" => 16,
        "cinema" => 17,
        "theatre" | "arts_centre" => 18,
        "library" => 19,
        "post_office" => 20,
        "police" => 21,
        "fire_station" => 22,
        "townhall" | "courthouse" => 23,
        "car_rental" | "car_wash" | "car_sharing" => 28,
        "dentist" => 34,
        "doctors" => 35,
        "veterinary" => 36,
        "charging_station" => 37,
        "marketplace" => 49,
        "bus_station" => 50,
        _ => return None,
    })
}

fn shop_type(v: &str) -> Option<u16> {
    Some(match v {
        "supermarket" | "convenience" | "greengrocer" | "grocery" => 5,
        "chemist" | "pharmacy" => 7,
        "clothes" | "shoes" | "boutique" | "fashion" | "tailor" => 24,
        "electronics" | "mobile_phone" | "computer" | "hifi" => 25,
        "hardware" | "doityourself" | "trade" | "paint" => 26,
        "hairdresser" | "beauty" => 27,
        "car" | "car_repair" | "car_parts" | "tyres" => 28,
        "bakery" => 29,
        "books" | "stationery" => 30,
        "furniture" | "interior_decoration" | "houseware" => 31,
        "sports" | "outdoor" | "bicycle" => 32,
        "department_store" | "mall" => 33,
        "florist" => 41,
        "jewelry" | "jewellery" => 42,
        "optician" => 43,
        "laundry" | "dry_cleaning" => 44,
        "pet" => 45,
        "alcohol" | "wine" | "beverages" => 46,
        "toys" => 47,
        "gift" => 48,
        _ => return None,
    })
}

fn tourism_type(v: &str) -> Option<u16> {
    Some(match v {
        "hotel" | "motel" | "hostel" | "guest_house" | "apartment" => 8,
        "attraction" | "theme_park" | "zoo" | "viewpoint" | "artwork" | "gallery" | "aquarium" => 15,
        "museum" => 38,
        "information" => 40,
        _ => return None,
    })
}

fn leisure_type(v: &str) -> Option<u16> {
    Some(match v {
        "park" | "garden" | "nature_reserve" => 12,
        "fitness_centre" | "sports_centre" => 13,
        _ => return None,
    })
}

fn healthcare_type(v: &str) -> Option<u16> {
    Some(match v {
        "pharmacy" => 7,
        "hospital" | "clinic" => 10,
        "dentist" => 34,
        "doctor" | "centre" => 35,
        "veterinary" => 36,
        "optometrist" => 43,
        _ => return None,
    })
}

/// Named administrative places worth searching: countries down to city
/// neighbourhoods. Deliberately not every `place=*` value — counties are
/// covered by their boundary relations, and islands/islets/seas are not
/// searchable settlements.
fn place_type(v: &str) -> Option<u16> {
    match v {
        "country" | "state" | "province" | "city" | "town" | "village" | "hamlet" | "suburb"
        | "borough" | "neighbourhood" | "quarter" | "macrohood" => Some(TYPE_PLACE),
        _ => None,
    }
}

/// The tags a POI classification needs, so the classifier is testable without a
/// PBF block behind it.
pub struct PoiTags<'a> {
    pub railway: Option<&'a str>,
    pub public_transport: Option<&'a str>,
    pub amenity: Option<&'a str>,
    pub shop: Option<&'a str>,
    pub tourism: Option<&'a str>,
    pub leisure: Option<&'a str>,
    pub healthcare: Option<&'a str>,
    pub office: Option<&'a str>,
    pub place: Option<&'a str>,
}

/// A POI key's tag value paired with the value -> type-number map for that key.
type KeyedMap<'a> = (Option<&'a str>, fn(&str) -> Option<u16>);

/// POI type number, or `None` when the object carries no recognised POI key.
///
/// Station-like `railway`/`public_transport` values win outright (type 50).
/// Bare transit values — tracks, signals, bus poles, platforms — are explicitly
/// *not* POIs and must not reach the generic "recognised key -> other" path, or
/// they flood the map. Otherwise precedence is place, amenity, shop, tourism,
/// leisure, healthcare, then `office=*`.
pub fn classify(t: &PoiTags) -> Option<u16> {
    if matches!(t.railway, Some("station" | "halt" | "tram_stop"))
        || matches!(t.public_transport, Some("station"))
    {
        return Some(50);
    }

    if let Some(p) = t.place {
        if !p.is_empty() && p != "no" {
            if let Some(ty) = place_type(p) {
                return Some(ty);
            }
            return Some(TYPE_OTHER);
        }
    }

    let order: [KeyedMap; 5] = [
        (t.amenity, amenity_type),
        (t.shop, shop_type),
        (t.tourism, tourism_type),
        (t.leisure, leisure_type),
        (t.healthcare, healthcare_type),
    ];
    let mut recognised = false;
    for (value, map) in order {
        let v = match value {
            Some(v) if !v.is_empty() && v != "no" => v,
            _ => continue,
        };
        recognised = true;
        if let Some(ty) = map(v) {
            return Some(ty);
        }
    }

    // office=* is a POI but almost always the generic office bucket.
    if let Some(off) = t.office {
        if !off.is_empty() && off != "no" {
            return Some(if off == "government" { 23 } else { 39 });
        }
    }

    if recognised {
        Some(TYPE_OTHER)
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn highway_ids_cover_the_routable_set() {
        assert_eq!(get_hw_id(Some("motorway")), 1);
        assert_eq!(get_hw_id(Some("steps")), 15);
        assert_eq!(get_hw_id(Some("residential")), 7);
        // Slip roads are routable as their parent class.
        assert_eq!(get_hw_id(Some("motorway_link")), 1);
        assert_eq!(get_hw_id(Some("tertiary_link")), 5);
        assert_eq!(get_hw_id(Some("raceway_link")), 0);
        // Unroutable / absent -> 0, which makes the caller skip the way.
        assert_eq!(get_hw_id(Some("proposed")), 0);
        assert_eq!(get_hw_id(Some("")), 0);
        assert_eq!(get_hw_id(None), 0);
    }

    #[test]
    fn maxspeed_units_convert() {
        assert_eq!(parse_maxspeed(Some("50")), 50);
        assert_eq!(parse_maxspeed(Some("30 mph")), 48); // 30 * 1.60934 = 48.28
        assert_eq!(parse_maxspeed(Some("65mph")), 105); // 104.6 rounds to 105
        assert_eq!(parse_maxspeed(Some("10 knots")), 19); // 18.52 rounds to 19
        assert_eq!(parse_maxspeed(Some("50.5")), 50); // truncates like the C++ cast
        assert_eq!(parse_maxspeed(Some("50; 30")), 50); // leading number wins
    }

    #[test]
    fn maxspeed_non_numeric_values_are_zero() {
        for v in ["none", "signals", "walk", "RU:urban", "", "-20", "0"] {
            assert_eq!(parse_maxspeed(Some(v)), 0, "value {v:?}");
        }
        assert_eq!(parse_maxspeed(None), 0);
    }

    #[test]
    fn int_tag_takes_the_leading_digits() {
        assert_eq!(parse_int_tag(Some("3")), 3);
        assert_eq!(parse_int_tag(Some(" 4 ")), 4);
        assert_eq!(parse_int_tag(Some("2;3")), 2);
        assert_eq!(parse_int_tag(Some("unknown")), 0);
        assert_eq!(parse_int_tag(Some("-1")), 0);
        assert_eq!(parse_int_tag(None), 0);
    }

    #[test]
    fn turn_lanes_splits_on_pipe_and_semicolon() {
        assert_eq!(
            parse_turn_lanes(Some("left|through|through;right")),
            vec![LANE_LEFT, LANE_THROUGH, LANE_THROUGH | LANE_RIGHT]
        );
        assert_eq!(
            parse_turn_lanes(Some("none||merge_to_left")),
            vec![LANE_NONE, LANE_NONE, LANE_MERGE_TO_LEFT]
        );
        // Unknown indications degrade to LANE_NONE rather than dropping the lane.
        assert_eq!(parse_turn_lanes(Some("wat")), vec![LANE_NONE]);
        assert!(parse_turn_lanes(Some("")).is_empty());
        assert!(parse_turn_lanes(None).is_empty());
    }

    #[test]
    fn lane_count_hint_pads_and_truncates() {
        assert_eq!(
            build_dir_lanes(Some("left|through"), 4),
            vec![LANE_LEFT, LANE_THROUGH, LANE_NONE, LANE_NONE]
        );
        assert_eq!(
            build_dir_lanes(Some("left|through|right"), 2),
            vec![LANE_LEFT, LANE_THROUGH]
        );
        assert_eq!(
            build_dir_lanes(Some("left|through"), 0),
            vec![LANE_LEFT, LANE_THROUGH]
        );
        // No turn:lanes means no lane data at all, hint or not.
        assert!(build_dir_lanes(None, 3).is_empty());
    }

    #[test]
    fn lane_count_hint_is_clamped() {
        // A mistagged lanes=* must not become a multi-GB allocation, and the
        // result must stay within the u16 count the lane record stores.
        assert_eq!(
            build_dir_lanes(Some("left|through"), u32::MAX).len(),
            MAX_LANES as usize
        );
        // A pathological turn:lanes value is capped the same way.
        let wide = vec!["through"; 500].join("|");
        assert_eq!(build_dir_lanes(Some(&wide), 0).len(), MAX_LANES as usize);
    }

    fn tags(pairs: &[(&str, &str)]) -> PoiTags<'static> {
        // Leak is fine in a test; keeps the helper's signature simple.
        let get = |k: &str| -> Option<&'static str> {
            pairs
                .iter()
                .find(|(pk, _)| *pk == k)
                .map(|(_, v)| Box::leak(v.to_string().into_boxed_str()) as &'static str)
        };
        PoiTags {
            railway: get("railway"),
            public_transport: get("public_transport"),
            amenity: get("amenity"),
            shop: get("shop"),
            tourism: get("tourism"),
            leisure: get("leisure"),
            healthcare: get("healthcare"),
            office: get("office"),
            place: get("place"),
        }
    }

    #[test]
    fn classify_follows_key_precedence() {
        // amenity beats shop beats tourism beats leisure beats office.
        assert_eq!(
            classify(&tags(&[("amenity", "cafe"), ("shop", "bakery")])),
            Some(1)
        );
        assert_eq!(
            classify(&tags(&[("shop", "bakery"), ("tourism", "hotel")])),
            Some(29)
        );
        assert_eq!(
            classify(&tags(&[("tourism", "museum"), ("leisure", "park")])),
            Some(38)
        );
        assert_eq!(
            classify(&tags(&[("leisure", "park"), ("office", "company")])),
            Some(12)
        );
        assert_eq!(classify(&tags(&[("healthcare", "dentist")])), Some(34));
        assert_eq!(classify(&tags(&[("office", "company")])), Some(39));
        assert_eq!(classify(&tags(&[("office", "government")])), Some(23));
    }

    #[test]
    fn classify_stations_win_and_bare_transit_values_are_not_pois() {
        assert_eq!(classify(&tags(&[("railway", "station")])), Some(50));
        assert_eq!(classify(&tags(&[("public_transport", "station")])), Some(50));
        // A station tag outranks amenity.
        assert_eq!(
            classify(&tags(&[("railway", "halt"), ("amenity", "cafe")])),
            Some(50)
        );
        // Bare transit infrastructure is not a POI at all.
        assert_eq!(classify(&tags(&[("railway", "rail")])), None);
        assert_eq!(classify(&tags(&[("public_transport", "platform")])), None);
        assert_eq!(classify(&tags(&[("highway", "bus_stop")])), None);
    }

    #[test]
    fn classify_unmapped_but_recognised_key_is_other() {
        assert_eq!(classify(&tags(&[("amenity", "bbq")])), Some(TYPE_OTHER));
        assert_eq!(classify(&tags(&[("shop", "anime")])), Some(TYPE_OTHER));
        assert_eq!(classify(&tags(&[("place", "island")])), Some(TYPE_OTHER));
        // `no` and empty values do not count as recognised.
        assert_eq!(classify(&tags(&[("amenity", "no")])), None);
        assert_eq!(classify(&tags(&[("shop", "")])), None);
        assert_eq!(classify(&tags(&[("place", "no")])), None);
        assert_eq!(classify(&tags(&[])), None);
    }

    #[test]
    fn classify_place_labels_are_type_51() {
        for v in [
            "country",
            "state",
            "province",
            "city",
            "town",
            "village",
            "hamlet",
            "suburb",
            "borough",
            "neighbourhood",
            "quarter",
            "macrohood",
        ] {
            assert_eq!(classify(&tags(&[("place", v)])), Some(TYPE_PLACE), "place {v:?}");
        }
        assert_eq!(classify(&tags(&[("place", "city")])), Some(51));
        // Stations still win over place; place wins over amenity.
        assert_eq!(
            classify(&tags(&[("railway", "station"), ("place", "town")])),
            Some(50)
        );
        assert_eq!(
            classify(&tags(&[("place", "town"), ("amenity", "cafe")])),
            Some(TYPE_PLACE)
        );
    }
}
