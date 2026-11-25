//! Basemap geometry queries for Kotlin (see `tile::geometry_query`).
//!
//! One entry point, deliberately **not** handle-based: a geometry query
//! has no surface, no Vulkan and no frame, so it must not queue behind
//! (or block) the render thread. Each call opens its own read-only
//! archive view over the on-device `.mamaps` file — the same
//! `FileRangeReader` + overlay discovery the tile workers use, so a
//! pinned overlay segment is honoured exactly as the renderer honours
//! it — batch-fetches the bbox's tiles through the codec's existing
//! `tiles()` wave, extracts, and drops the view. The file mapping is
//! lazy and the leaf cache is per-call, so a query costs a prefix parse
//! plus the covering tiles' bodies and nothing else.
//!
//! # Wire shape
//!
//! An array of `\u{1}`-joined strings, the `pickLabels` convention:
//!
//! * row 0: `OK \u{1} <truncated 0|1> \u{1} <coveredTiles> \u{1} <totalTiles>`,
//!   or the single row `UNAVAILABLE` when there is no readable archive
//!   (no path, no file, an unparseable archive, or every covering tile
//!   failed to decode). UNAVAILABLE is the honest "source unavailable"
//!   answer; an `OK` with zero features is "scanned, nothing here".
//! * rows 1..: `<KIND> \u{1} <class> \u{1} <closed 0|1> \u{1}
//!   <lonE7,latE7,...>` with KIND one of ROAD/PATH/WATER/BUILDING.
//!   E7 integers keep the payload compact and exact at ~1 cm, far
//!   below the wear consumer's 2 m quantization grid.

use super::log::log;
use crate::tile::geometry_query::{
    query_geometry, GeometryQuery, KIND_BUILDING, KIND_PATH, KIND_ROAD, KIND_WATER,
};
use crate::tile::source::{discover, FileRangeReader};
use jni::objects::{JClass, JObject, JString};
use jni::sys::{jdouble, jint, jobjectArray};
use jni::{jni_str, EnvUnowned};
use std::sync::Arc;
use tilecodec::mamaps::read::{LeafCache, MamapsArchive};

fn kind_name(kind: u32) -> &'static str {
    match kind {
        KIND_ROAD => "ROAD",
        KIND_PATH => "PATH",
        KIND_WATER => "WATER",
        KIND_BUILDING => "BUILDING",
        _ => "BUILDING",
    }
}

fn rows_to_array<'l>(
    env: &mut jni::Env<'l>,
    rows: &[String],
) -> jni::errors::Result<jobjectArray> {
    let out = env.new_object_array(rows.len() as i32, jni_str!("java/lang/String"), JObject::null())?;
    for (i, row) in rows.iter().enumerate() {
        let Ok(js) = env.new_string(row) else { continue };
        let _ = env.set_object_array_element(&out, i, js);
    }
    Ok(out.into_raw())
}

/// Query basemap geometry in a bbox from the on-device archive at
/// `local_path`. See the module docs for the wire shape and bounds.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_queryGeometry<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    local_path: JString<'l>,
    west: jdouble,
    south: jdouble,
    east: jdouble,
    north: jdouble,
    kinds_mask: jint,
    max_features: jint,
) -> jobjectArray {
    env.with_env(|env| -> jni::errors::Result<jobjectArray> {
        let unavailable = vec!["UNAVAILABLE".to_string()];
        let path: String = if local_path.is_null() {
            return rows_to_array(env, &unavailable);
        } else {
            match local_path.try_to_string(env) {
                Ok(s) if !s.is_empty() => s,
                _ => return rows_to_array(env, &unavailable),
            }
        };
        let path_ref = std::path::Path::new(&path);
        if !FileRangeReader::exists(path_ref) {
            return rows_to_array(env, &unavailable);
        }
        let query = GeometryQuery {
            west,
            south,
            east,
            north,
            kinds_mask: kinds_mask.max(0) as u32,
            max_features: max_features.max(0) as usize,
        };
        // Same source selection as the tile workers: a pinned overlay
        // serves patch-first, any refusal falls back to the base alone.
        let leaves = Arc::new(LeafCache::new());
        let result = if let Some(found) = discover(path_ref, Arc::clone(&leaves)) {
            query_geometry(&found.overlay, &query)
        } else {
            let reader = match FileRangeReader::open(path_ref) {
                Ok(r) => r,
                Err(e) => {
                    log(&format!("geometry query cannot open {path}: {e}"));
                    return rows_to_array(env, &unavailable);
                }
            };
            match MamapsArchive::open_with_cache(reader, leaves) {
                Ok(archive) => query_geometry(&archive, &query),
                Err(e) => {
                    log(&format!("geometry query cannot parse {path}: {e}"));
                    return rows_to_array(env, &unavailable);
                }
            }
        };
        // Nothing decoded and something failed: that is a broken
        // source, not an empty region. (Nothing decoded with no
        // failures is ordinary absent-tile ground — ocean — and stays
        // an honest OK with zero features.)
        if result.covered_tiles == 0 && result.failed_tiles > 0 {
            return rows_to_array(env, &unavailable);
        }
        let mut rows = Vec::with_capacity(result.features.len() + 1);
        rows.push(format!(
            "OK\u{1}{}\u{1}{}\u{1}{}",
            if result.truncated { 1 } else { 0 },
            result.covered_tiles,
            result.total_tiles,
        ));
        for f in &result.features {
            let mut coords = String::new();
            for (i, &(lon, lat)) in f.points.iter().enumerate() {
                if i > 0 {
                    coords.push(',');
                }
                coords.push_str(&format!("{},{}", (lon * 1e7).round() as i64, (lat * 1e7).round() as i64));
            }
            rows.push(format!(
                "{}\u{1}{}\u{1}{}\u{1}{}",
                kind_name(f.kind),
                f.class,
                if f.closed { 1 } else { 0 },
                coords,
            ));
        }
        rows_to_array(env, &rows)
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}
