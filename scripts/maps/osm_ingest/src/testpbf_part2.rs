// ---- the POI admin-hierarchy fixture ----------------------------------------
//
// A FIFTH fixture, for `poi_admin.bin`: three nested administrative squares
// (country > state > city), each its own `type=boundary` relation over one
// closed way, a fourth level-8 boundary ("Ghosttown") inside the state that no
// POI falls in, and three cafes — one in the city, one in the state outside the
// city, one in the country outside the state.

/// `admin_level=2`, `name=Testlandia`, `name:en=Testland`.
pub const ADMIN_COUNTRY_ID: i64 = 901;
/// `admin_level=4`, `name=Teststate`.
pub const ADMIN_STATE_ID: i64 = 902;
/// `admin_level=8`, `name=Testcity`.
pub const ADMIN_CITY_ID: i64 = 903;
/// `admin_level=8`, `name=Ghosttown`: inside the state, reached by no POI.
pub const ADMIN_GHOST_ID: i64 = 904;

/// `(way id, relation id, [south, west, north, east] in 1e-7 degrees)` per square.
const ADMIN_SQUARES: [(i64, i64, [i32; 4]); 4] = [
    (811, ADMIN_COUNTRY_ID, [400_000_000, -1_000_000_000, 410_000_000, -990_000_000]),
    (812, ADMIN_STATE_ID, [402_000_000, -998_000_000, 408_000_000, -992_000_000]),
    (813, ADMIN_CITY_ID, [404_000_000, -996_000_000, 406_000_000, -994_000_000]),
    (814, ADMIN_GHOST_ID, [402_500_000, -997_500_000, 403_000_000, -997_000_000]),
];

/// The three cafes: in the city, in the state only, in the country only.
const ADMIN_CAFES: [(i64, i32, i32, &str); 3] = [
    (821, 405_000_000, -995_000_000, "City Cafe"),
    (822, 407_500_000, -993_000_000, "State Cafe"),
    (823, 409_000_000, -991_000_000, "Country Cafe"),
];

fn admin_block() -> Vec<u8> {
    let mut st = StringTable::new();
    let k_amenity = st.id("amenity");
    let v_cafe = st.id("cafe");
    let k_name = st.id("name");
    let k_name_en = st.id("name:en");
    let k_type = st.id("type");
    let k_boundary = st.id("boundary"); // also the value of `type=*`
    let v_admin = st.id("administrative");
    let k_admin_level = st.id("admin_level");
    let role_outer = st.id("outer");
    let levels = [st.id("2"), st.id("4"), st.id("8"), st.id("8")];
    let names = [st.id("Testlandia"), st.id("Teststate"), st.id("Testcity"), st.id("Ghosttown")];
    let v_testland = st.id("Testland");

    // Ring corners: four nodes per square, ids way_id * 10 + 1..=4.
    let mut nodes: Vec<FixtureNode> = Vec::new();
    for (way_id, _, [s, w, n, e]) in ADMIN_SQUARES {
        for (k, (lat, lon)) in [(s, w), (s, e), (n, e), (n, w)].into_iter().enumerate() {
            nodes.push((way_id * 10 + k as i64 + 1, lat, lon, Vec::new()));
        }
    }
    for (id, lat, lon, name) in ADMIN_CAFES {
        nodes.push((id, lat, lon, vec![(k_amenity, v_cafe), (k_name, st.id(name))]));
    }
    nodes.sort_by_key(|n| n.0);
    let dense = dense_group(&nodes);
    let ways: Vec<Vec<u8>> = ADMIN_SQUARES
        .iter()
        .map(|(way_id, _, _)| {
            let c = |k: i64| way_id * 10 + k;
            way(*way_id, &[], &[c(1), c(2), c(3), c(4), c(1)])
        })
        .collect();
    let rels: Vec<Vec<u8>> = ADMIN_SQUARES
        .iter()
        .enumerate()
        .map(|(i, (way_id, rel_id, _))| {
            let mut tags = vec![
                (k_type, k_boundary),
                (k_boundary, v_admin),
                (k_admin_level, levels[i]),
                (k_name, names[i]),
            ];
            if *rel_id == ADMIN_COUNTRY_ID {
                tags.push((k_name_en, v_testland));
            }
            relation(*rel_id, &tags, &[(*way_id, role_outer, 1)])
        })
        .collect();

    let mut node_group = Vec::new();
    bytes_field(2, &dense, &mut node_group);
    let mut way_group = Vec::new();
    for w in &ways {
        bytes_field(3, w, &mut way_group);
    }
    let mut rel_group = Vec::new();
    for r in &rels {
        bytes_field(4, r, &mut rel_group);
    }
    let mut block = Vec::new();
    bytes_field(1, &st.encode(), &mut block);
    bytes_field(2, &node_group, &mut block);
    bytes_field(2, &way_group, &mut block);
    bytes_field(2, &rel_group, &mut block);
    varint_field(17, 100, &mut block); // granularity
    block
}

pub fn write_admin_sample(tag: &str) -> (std::path::PathBuf, std::path::PathBuf) {
    write_pbf(tag, &pbf_from_block(&admin_block()))
}
