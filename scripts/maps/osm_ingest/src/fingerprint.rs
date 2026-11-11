//! Content fingerprints of input files: what a tool may key a cache guard or a build id on.
//!
//! Never a path, an mtime, an inode, a host or a user: two copies of the same bytes anywhere, made
//! at any time, fingerprint the same, and the archives built from them come out byte-identical.
//! (The blob-kinds sidecar used to carry the input's mtime, and the archive's build id hashed the
//! input's absolute path and mtime, so the same planet built on two machines differed.)
//!
//! A fingerprint is FNV-1a 64 over the file's length, its first [`SAMPLE_BYTES`] and its last
//! [`SAMPLE_BYTES`] (the whole file when it is no longer than twice that). That is a few MB of
//! reads per input, however large the input. It is a change detector, not a proof of identity:
//! callers that need more mix in what they already know about the content (a `.pbf`'s blob
//! index, the archive's own tile digest).

use std::io::{Read, Seek, SeekFrom};
use std::path::Path;

/// Bytes sampled from each end of a file.
pub const SAMPLE_BYTES: u64 = 4 << 20;

/// FNV-1a 64, the same hash the archive writer uses for its dedup keys: stable, dependency-free,
/// and defined byte by byte so it cannot depend on platform or word size.
#[derive(Debug, Clone, Copy)]
pub struct Fnv(pub u64);

impl Default for Fnv {
    fn default() -> Self {
        Fnv(0xcbf2_9ce4_8422_2325)
    }
}

impl Fnv {
    pub fn eat(&mut self, bytes: &[u8]) {
        for &b in bytes {
            self.0 ^= b as u64;
            self.0 = self.0.wrapping_mul(0x100_0000_01b3);
        }
    }

    pub fn eat_u64(&mut self, v: u64) {
        self.eat(&v.to_le_bytes());
    }

    pub fn finish(&self) -> u64 {
        self.0
    }
}

/// The content fingerprint of one file (see the module docs).
pub fn file_fingerprint(path: &Path) -> std::io::Result<u64> {
    let mut file = std::fs::File::open(path)?;
    let len = file.metadata()?.len();
    let mut h = Fnv::default();
    h.eat(b"file/1");
    h.eat_u64(len);
    let mut buf = Vec::new();
    if len <= 2 * SAMPLE_BYTES {
        file.read_to_end(&mut buf)?;
        h.eat(&buf);
    } else {
        buf.resize(SAMPLE_BYTES as usize, 0);
        file.read_exact(&mut buf)?;
        h.eat(&buf);
        file.seek(SeekFrom::Start(len - SAMPLE_BYTES))?;
        file.read_exact(&mut buf)?;
        h.eat(&buf);
    }
    Ok(h.finish())
}

/// The fingerprint of a directory's regular files: each file's name (relative, so the directory's
/// own location never counts) and [`file_fingerprint`], in name order. Dot-files are skipped:
/// `planet_build.sh` touches `.ready` in the graph dir as a signal, which is not content.
pub fn dir_fingerprint(dir: &Path) -> std::io::Result<u64> {
    let mut names: Vec<std::ffi::OsString> = Vec::new();
    for entry in std::fs::read_dir(dir)? {
        let entry = entry?;
        let name = entry.file_name();
        if name.to_string_lossy().starts_with('.') || !entry.file_type()?.is_file() {
            continue;
        }
        names.push(name);
    }
    names.sort();
    let mut h = Fnv::default();
    h.eat(b"dir/1");
    for name in names {
        h.eat(name.to_string_lossy().as_bytes());
        h.eat(&[0]);
        h.eat_u64(file_fingerprint(&dir.join(&name))?);
    }
    Ok(h.finish())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn scratch(name: &str) -> std::path::PathBuf {
        let d = std::env::temp_dir().join(format!("osm_fp_{}_{name}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    /// The same bytes fingerprint the same at another path with another mtime; a change at the
    /// head, the tail or the length moves it; small files are hashed whole.
    #[test]
    fn fingerprints_follow_content_not_place_or_time() {
        let a = scratch("a");
        let b = scratch("b");
        let big: Vec<u8> = (0..(SAMPLE_BYTES as usize * 3)).map(|i| (i * 31 % 251) as u8).collect();
        std::fs::write(a.join("x.bin"), &big).unwrap();
        std::thread::sleep(std::time::Duration::from_millis(20));
        std::fs::write(b.join("x.bin"), &big).unwrap();
        let fa = file_fingerprint(&a.join("x.bin")).unwrap();
        assert_eq!(fa, file_fingerprint(&b.join("x.bin")).unwrap());
        assert_eq!(dir_fingerprint(&a).unwrap(), dir_fingerprint(&b).unwrap());
        // Signals are not content.
        std::fs::write(b.join(".ready"), b"").unwrap();
        assert_eq!(dir_fingerprint(&a).unwrap(), dir_fingerprint(&b).unwrap());
        for (at, what) in [(0usize, "head"), (big.len() - 1, "tail")] {
            let mut changed = big.clone();
            changed[at] ^= 1;
            std::fs::write(b.join("x.bin"), &changed).unwrap();
            assert_ne!(fa, file_fingerprint(&b.join("x.bin")).unwrap(), "{what} change missed");
            assert_ne!(dir_fingerprint(&a).unwrap(), dir_fingerprint(&b).unwrap());
        }
        std::fs::write(b.join("x.bin"), &big[1..]).unwrap();
        assert_ne!(fa, file_fingerprint(&b.join("x.bin")).unwrap(), "length change missed");
        // A small file is hashed whole: a change anywhere moves it.
        std::fs::write(a.join("s.bin"), b"0123456789").unwrap();
        let fs = file_fingerprint(&a.join("s.bin")).unwrap();
        std::fs::write(a.join("s.bin"), b"0123x56789").unwrap();
        assert_ne!(fs, file_fingerprint(&a.join("s.bin")).unwrap());
        // A renamed file inside a directory is a different directory.
        std::fs::write(b.join("x.bin"), &big).unwrap();
        std::fs::rename(b.join("x.bin"), b.join("y.bin")).unwrap();
        assert_ne!(dir_fingerprint(&a).unwrap(), dir_fingerprint(&b).unwrap());
        let _ = std::fs::remove_dir_all(&a);
        let _ = std::fs::remove_dir_all(&b);
    }
}
