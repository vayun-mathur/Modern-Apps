//! `mamaps_overlay` — build patch-overlay segments from two `.mamaps` archives.
//!
//! Usage:
//!   mamaps_overlay --before OLD.mamaps --after NEW.mamaps --out-dir DIR
//!                  [--base-url URL]
//!
//! Emits `overlay-0000.mamaps`, `overlay-0001.mamaps`, … plus `overlay.json`
//! (the per-overlay half of `planet4.manifest.json`: overlay seq/build id,
//! segment list with lengths and SHA-256). Each segment is a small
//! self-contained `.mamaps` holding only changed/added tile bodies with
//! overlay-relative offsets; the base is never touched.
//!
//! The publisher renames the segment files to their content-addressed R2
//! keys, fills in the download URLs, and merges `overlay.json` into the
//! manifest (see `upload_planet.py --manifest`).

use std::path::PathBuf;
use std::process::ExitCode;

use tile_build::overlay::{emit_segment, open_pair, plan};

fn value(args: &[String], i: usize, name: &str) -> PathBuf {
    match args.get(i + 1) {
        Some(v) => PathBuf::from(v),
        None => {
            eprintln!("mamaps_overlay: {name} needs a value");
            std::process::exit(2);
        }
    }
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    let mut before: Option<PathBuf> = None;
    let mut after: Option<PathBuf> = None;
    let mut out_dir: Option<PathBuf> = None;
    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "--before" => before = Some(value(&args, i, "--before")),
            "--after" => after = Some(value(&args, i, "--after")),
            "--out-dir" => out_dir = Some(value(&args, i, "--out-dir")),
            other => {
                eprintln!("mamaps_overlay: unknown flag {other}");
                return ExitCode::from(2);
            }
        }
        i += 2;
    }
    let (Some(before), Some(after), Some(out_dir)) = (before, after, out_dir) else {
        eprintln!("usage: mamaps_overlay --before OLD.mamaps --after NEW.mamaps --out-dir DIR");
        return ExitCode::from(2);
    };
    match run(&before, &after, &out_dir) {
        Ok(summary) => {
            println!("{summary}");
            ExitCode::SUCCESS
        }
        Err(e) => {
            eprintln!("mamaps_overlay: {e}");
            ExitCode::from(1)
        }
    }
}

fn run(before: &std::path::Path, after: &std::path::Path, out_dir: &std::path::Path) -> Result<String, String> {
    let pair = open_pair(before, after).map_err(|e| e.to_string())?;
    let overlay = plan(&pair.old_snapshot, &pair.new_snapshot, &pair.old_archive, &pair.new_archive);
    std::fs::create_dir_all(out_dir)
        .map_err(|e| format!("cannot write {}: {e}", out_dir.display()))?;
    let mut total = 0u64;
    for (n, range) in overlay.segments.iter().enumerate() {
        let name = format!("overlay-{n:04}.mamaps");
        let len = emit_segment(&pair.new_archive, &overlay, *range, &out_dir.join(&name))
            .map_err(|e| e.to_string())?;
        total += len;
        // SHA-256 over the finished segment for the manifest.
        let digest = sha_file(&out_dir.join(&name))?;
        println!("segment {name}: {len} bytes sha256={digest}");
    }
    // The tombstone + compat descriptor the publisher merges into the manifest.
    // Tombstone ids ride inline: real month-over-month deletes number in the
    // hundreds-to-thousands (a coverage-edge storm means the wrong pair), so
    // this stays kilobytes. The client prunes ids the base does not hold.
    let mut tombstone_list = String::from("[");
    for (n, id) in overlay.tombstones.iter().enumerate() {
        if n > 0 {
            tombstone_list.push(',');
        }
        tombstone_list.push_str(&id.to_string());
    }
    tombstone_list.push(']');
    let descriptor = format!(
        "{{\"overlay_build_id\":\"{:#018x}\",\"base_build_id\":\"{:#018x}\",\"segments\":{},\"tombstones\":{tombstone_list}}}",
        overlay.overlay_build_id,
        overlay.header.base_build_id,
        overlay.segments.len(),
    );
    std::fs::write(out_dir.join("overlay.json"), descriptor)
        .map_err(|e| format!("cannot write overlay.json: {e}"))?;
    Ok(format!(
        "overlay: {} carries ({} segments), {} tombstones, {} bytes total",
        overlay.carries.len(),
        overlay.segments.len(),
        overlay.tombstones.len(),
        total,
    ))
}

/// Lowercase hex SHA-256 of a file, streamed (segments are hundreds of MB).
fn sha_file(path: &std::path::Path) -> Result<String, String> {
    use sha2::{Digest as _, Sha256};
    use std::io::Read as _;
    let mut hasher = Sha256::new();
    let mut file =
        std::fs::File::open(path).map_err(|e| format!("cannot read {}: {e}", path.display()))?;
    let mut buf = [0u8; 1 << 20];
    loop {
        let n = file.read(&mut buf).map_err(|e| format!("cannot read {}: {e}", path.display()))?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    let digest = hasher.finalize();
    Ok(digest.iter().map(|b| format!("{b:02x}")).collect())
}
