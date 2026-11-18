//! Host JVM tests for the JNI wrappers (`NetworkLocationNative`,
//! `GeocoderNative`, `WpsStoreNative`).
//!
//! The wrappers are thin, but they own the handle/null contracts Kotlin
//! relies on. These tests boot one host JVM, open a synthetic geocoder
//! database through a real file descriptor, and drive the exported symbols
//! exactly as the Kotlin loaders do.

use jni::objects::{JClass, JDoubleArray, JObjectArray, JString};
use jni::{Env, EnvUnowned, JavaVM};
use std::io::Cursor;
use std::os::unix::io::AsRawFd;
use std::sync::OnceLock;

static JVM: OnceLock<JavaVM> = OnceLock::new();

fn jvm() -> &'static JavaVM {
    JVM.get_or_init(|| {
        let args = jni::InitArgsBuilder::new().build().expect("JNI init args");
        JavaVM::new(args).expect("host JVM must start for JNI tests")
    })
}

fn with_env<R>(f: impl FnOnce(&mut Env) -> R) -> R {
    let mut out: Option<R> = None;
    jvm()
        .attach_current_thread(|env| {
            out = Some(f(env));
            Ok::<(), jni::errors::Error>(())
        })
        .expect("attach_current_thread");
    out.expect("closure ran")
}

fn un<'a>(env: &Env<'a>) -> EnvUnowned<'a> {
    unsafe { EnvUnowned::from_raw(env.get_raw()) }
}

// ---------------------------------------------------------- synthetic geodb
// Same v5 fixture as `geocoder::geocoder_tests`: 3 records in 2 grid cells.

fn compress(raw: &[u8]) -> Vec<u8> {
    ruzstd::encoding::compress_to_vec(Cursor::new(raw), ruzstd::encoding::CompressionLevel::Uncompressed)
}

fn zigzag(v: i32) -> u32 {
    ((v << 1) ^ (v >> 31)) as u32
}

fn push_varint(mut v: u32, out: &mut Vec<u8>) {
    loop {
        let b = (v & 0x7F) as u8;
        v >>= 7;
        if v == 0 {
            out.push(b);
            return;
        }
        out.push(b | 0x80);
    }
}

fn be32(out: &mut Vec<u8>, v: u32) {
    out.extend_from_slice(&v.to_be_bytes());
}

fn column_body(vals: &[i32], delta: bool) -> Vec<u8> {
    let mut raw = Vec::new();
    let mut prev = 0i32;
    for &v in vals {
        let dv = if delta { v.wrapping_sub(prev) } else { v };
        prev = v;
        push_varint(zigzag(dv), &mut raw);
    }
    let comp = compress(&raw);
    let mut out = Vec::new();
    be32(&mut out, vals.len() as u32);
    be32(&mut out, 1);
    be32(&mut out, raw.len() as u32);
    be32(&mut out, comp.len() as u32);
    out.extend_from_slice(&comp);
    out
}

fn dict_body(strings: &[&str], per: usize) -> Vec<u8> {
    let blocks: Vec<&[&str]> = strings.chunks(per).collect();
    let firsts: Vec<u8> = blocks.iter().flat_map(|b| b[0].bytes()).collect();
    let mut out = Vec::new();
    be32(&mut out, strings.len() as u32);
    be32(&mut out, per as u32);
    be32(&mut out, blocks.len() as u32);
    let mut end = 0u32;
    for b in &blocks {
        end += b[0].len() as u32;
        be32(&mut out, end);
    }
    out.extend_from_slice(&firsts);
    let mut end = 0u32;
    let mut comps = Vec::new();
    for b in &blocks {
        let mut raw = Vec::new();
        for s in *b {
            be32(&mut raw, s.len() as u32);
            raw.extend_from_slice(s.as_bytes());
        }
        let c = compress(&raw);
        end += c.len() as u32;
        be32(&mut out, end);
        comps.push(c);
    }
    for c in comps {
        out.extend_from_slice(&c);
    }
    out
}

fn synthetic_db() -> Vec<u8> {
    const CELL: i64 = 5_000;
    const COLS: i64 = 7_200;
    const MIN_LAT: i32 = -9_000_000;
    const MIN_LON: i32 = -18_000_000;
    const REC: [[i32; 10]; 3] = [
        [3_777_493, -12_241_942, -1, 0, 0, 0, 0, 0, 0, 1],
        [3_777_500, -12_241_900, 0, -1, 0, 0, 0, 0, -1, 3],
        [3_800_000, -12_200_000, 1, -1, 1, 0, 0, 0, 1, 4],
    ];
    const DELTA: [bool; 10] = [true, true, false, false, false, false, false, false, false, false];
    let cell = |r: &[i32; 10]| ((r[0] as i64 - MIN_LAT as i64) / CELL) * COLS + (r[1] as i64 - MIN_LON as i64) / CELL;
    let dicts: [&[&str]; 7] = [
        &["Alpha Cafe", "Beta Park", "Gamma Plaza"],
        &["10", "20"],
        &["Main St", "Oak Ave"],
        &["Springfield"],
        &["Illinois"],
        &["US"],
        &["00001", "00002"],
    ];
    let mut bodies: Vec<Vec<u8>> = dicts.iter().map(|d| dict_body(d, 2)).collect();
    for c in 0..10 {
        bodies.push(column_body(&REC.iter().map(|r| r[c]).collect::<Vec<_>>(), DELTA[c]));
    }
    let mut grid = Vec::new();
    be32(&mut grid, 2);
    grid.extend_from_slice(&cell(&REC[0]).to_be_bytes());
    be32(&mut grid, 0);
    grid.extend_from_slice(&cell(&REC[2]).to_be_bytes());
    be32(&mut grid, 2);
    bodies.push(grid);
    bodies.push(column_body(&[0, 1, 2], true));
    bodies.push(column_body(&[0, 1], true));
    bodies.push(column_body(&[1, 2], false));

    let mut out = Vec::new();
    be32(&mut out, 0x4D41_4745);
    be32(&mut out, 5);
    be32(&mut out, 3);
    for b in &bodies {
        be32(&mut out, b.len() as u32);
        out.extend_from_slice(b);
    }
    out
}

fn read_strings(env: &mut Env, raw: jni::sys::jobjectArray) -> Vec<String> {
    assert!(!raw.is_null(), "expected non-null string array");
    let arr = unsafe { JObjectArray::<JString>::from_raw(env, raw) };
    let n = arr.len(env).expect("len");
    (0..n)
        .map(|i| {
            let s = arr.get_element(env, i).expect("element");
            s.try_to_string(env).expect("string")
        })
        .collect()
}

#[test]
fn estimate_position_via_jni() {
    use crate::jni::Java_com_vayunmathur_networklocation_NetworkLocationNative_estimatePosition as estimate;
    with_env(|env| {
        fn mk<'a>(env: &mut Env<'a>, vals: &[f64]) -> JDoubleArray<'a> {
            let a = env.new_double_array(vals.len()).expect("array");
            a.set_region(env, 0, vals).expect("set");
            a
        }
        // Three consistent beacons around (37.0, -122.0).
        let beacons = mk(&mut *env, &[37.0, -122.0, 50.0, 37.0001, -122.0, 50.0, 37.0, -121.9999, 50.0]);
        let raw = estimate(un(&*env), JClass::null(), beacons);
        assert!(!raw.is_null());
        let arr = unsafe { JDoubleArray::from_raw(&*env, raw) };
        let mut buf = [0f64; 3];
        #[allow(deprecated)]
        env.get_double_array_region(&arr, 0, &mut buf).expect("region");
        assert!((buf[0] - 37.0).abs() < 0.01, "lat {}", buf[0]);
        assert!((buf[1] + 122.0).abs() < 0.01, "lon {}", buf[1]);
        assert!(buf[2] > 0.0, "accuracy {}", buf[2]);

        // No usable input yields null, never a bogus fix.
        let empty = mk(&mut *env, &[]);
        assert!(estimate(un(&*env), JClass::null(), empty).is_null());
        let ragged = mk(&mut *env, &[37.0, -122.0]);
        assert!(estimate(un(&*env), JClass::null(), ragged).is_null());
        env.exception_clear();
    });
}

#[test]
fn geocoder_via_jni() {
    use crate::geocoder::{
        Java_com_vayunmathur_networklocation_GeocoderNative_close as geo_close,
        Java_com_vayunmathur_networklocation_GeocoderNative_forward as geo_forward,
        Java_com_vayunmathur_networklocation_GeocoderNative_open as geo_open,
        Java_com_vayunmathur_networklocation_GeocoderNative_reverse as geo_reverse,
        Java_com_vayunmathur_networklocation_GeocoderNative_searchName as geo_search,
    };
    let path = std::env::temp_dir().join(format!("geocoder-jni-{}.db", std::process::id()));
    std::fs::write(&path, synthetic_db()).expect("write db");
    let file = std::fs::File::open(&path).expect("open db");
    let fd = file.as_raw_fd();

    with_env(|env| {
        let handle = geo_open(un(&*env), JClass::null(), fd, 0, 0);
        assert_ne!(handle, 0, "synthetic db opens through the JNI entry");

        let raw = geo_reverse(un(&*env), JClass::null(), handle, 37.77493, -122.41942);
        let fields = read_strings(&mut *env, raw);
        assert_eq!(fields.len(), 10);
        assert_eq!(fields[4], "Main St");
        assert_eq!(fields[5], "Springfield");
        assert_eq!(fields[7], "US");

        let (c, s, ci, st) = (
            env.new_string("US").expect("s"),
            env.new_string("Illinois").expect("s"),
            env.new_string("Springfield").expect("s"),
            env.new_string("Main St").expect("s"),
        );
        let raw = geo_forward(un(&*env), JClass::null(), handle, c, s, ci, st, 10);
        let hits = read_strings(&mut *env, raw);
        assert_eq!(hits.len(), 20, "two Main St records x 10 fields");

        let p = env.new_string("Alpha").expect("s");
        let raw = geo_search(un(&*env), JClass::null(), handle, p, 10);
        let hits = read_strings(&mut *env, raw);
        assert_eq!(hits.len(), 10);
        assert_eq!(hits[2], "Alpha Cafe");

        // Null-handle contract: every query returns null.
        let raw = geo_reverse(un(&*env), JClass::null(), 0, 37.0, -122.0);
        assert!(raw.is_null());
        geo_close(un(&*env), JClass::null(), handle);
        geo_close(un(&*env), JClass::null(), 0);
        env.exception_clear();
    });
    drop(file);
    let _ = std::fs::remove_file(&path);

    // A bad descriptor fails open cleanly (handle 0), for both stores.
    with_env(|env| {
        assert_eq!(geo_open(un(&*env), JClass::null(), -1, 0, 0), 0);
        use crate::wpsdb::Java_com_vayunmathur_networklocation_WpsStoreNative_open as wps_open;
        assert_eq!(wps_open(un(&*env), JClass::null(), -1, 0, 0), 0);
        env.exception_clear();
    });
}

#[test]
fn wps_store_null_handle_contract() {
    use crate::wpsdb::{
        Java_com_vayunmathur_networklocation_WpsStoreNative_close as wps_close,
        Java_com_vayunmathur_networklocation_WpsStoreNative_lookup as wps_lookup,
    };
    with_env(|env| {
        let raw = wps_lookup(un(&*env), JClass::null(), 0, 0, 0);
        assert!(raw.is_null(), "handle 0 looks up nothing");
        wps_close(un(&*env), JClass::null(), 0);
        env.exception_clear();
    });
}
