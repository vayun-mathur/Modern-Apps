//! Offline key → coordinate lookup over a packed `WPSDB2` store, exposed via JNI.
//!
//! One generic reader serves both offline stores the app ships (mirroring the offline
//! geocoder pattern in `geocoder.rs`):
//!   * `wifi-v2.wpsdb`  — 48-bit MAC (BSSID) key → coord + accuracy
//!   * `cells-v2.wpsdb` — 84-bit packed cell key → coord + accuracy
//!
//! The on-disk format is produced by `scripts/networklocation/wps_harvest` (`wps_build`).
//! All multi-byte scalars are little-endian; the bit-packed arrays are LSB-first within
//! each byte (bit `k` of a value lives at `buf[p >> 3] & (1 << (p & 7))`).
//!
//! ```text
//! 0  magic "WPSDB2\0\0"                8 B
//! 8  key_kind:u8, universe_bits:u8, payload_kind:u8, l:u8
//! 12 select_sample_log2:u8, reserved:u8 * 3
//! 16 n:u64
//! 24 high_len_bits:u64
//! 32 high_len:u64
//! 40 high[high_len]
//!    zeros_len:u64,   zeros[zeros_len]      (u64 each: zeros before bit b << sample_log2)
//!    low_len:u64,     low[low_len]          (n * l bits, bit-packed)
//!    payload_len:u64, payload[payload_len]  (n * 87 bits, bit-packed)
//! ```
//!
//! Membership and index come from the Elias–Fano upper bitvector (`high`). At world scale
//! that vector is hundreds of MB, far too much to hold in an always-bound system service, so
//! it is **mmap'd** rather than read: random `select0` probes hit the page cache and the
//! resident set stays proportional to what is actually touched. The `select0` sample table is
//! precomputed by the builder and read once (a few MB at a billion records) instead of being
//! derived by scanning the whole bitvector at open. `low` and `payload` are read on demand
//! via positional reads, so a multi-gigabyte store costs almost no memory.
//!
//! ## Coordinates
//!
//! Records store `lat`/`lon` as **integer degrees x 1e8**, which is bit-exact against the
//! source the stores are built from, plus the beacon's own **horizontal accuracy in metres**.
//! The previous format quantized to a 20 m grid and carried no accuracy at all, so every
//! offline fix was reported with the same invented radius and the solver's uncertainty
//! weighting — which is fully wired, see `jni.rs` and `multilateration.rs` — did nothing.

use std::fs::File;
// `FromRawFd` (fd → File) only exists on unix; the host fallbacks below never
// touch fds.
#[cfg(unix)]
use std::os::unix::io::FromRawFd;

use jni::objects::{JByteArray, JClass, JDoubleArray, JObjectArray, JString};
use jni::sys::{jdoubleArray, jint, jlong};
use jni::{Env, EnvUnowned, jni_str};

const MAGIC: &[u8; 8] = b"WPSDB2\x00\x00";

/// `high` begins here, immediately after the fixed header.
const HIGH_OFF: u64 = 40;

// --------------------------------------------------------------------------- payload codec
// One record is 87 bits, LSB-first: lat(35) | lon(36) | accuracy(16).
const LAT_BITS: u32 = 35;
const LON_BITS: u32 = 36;
const ACC_BITS: u32 = 16;
const RECORD_BITS: u32 = LAT_BITS + LON_BITS + ACC_BITS;

/// Bias that maps `lat * 1e8` from [-90, 90] onto [0, 180e8], which fits 35 bits.
const LAT_BIAS: i64 = 90_00_000_000;
/// Bias that maps `lon * 1e8` from [-180, 180] onto [0, 360e8], which fits 36 bits.
const LON_BIAS: i64 = 180_00_000_000;
const COORD_SCALE: f64 = 1e8;

/// Stored accuracy sentinel for "the source did not report one".
const ACC_UNKNOWN: u64 = 0xFFFF;
/// What `lookup` reports for [`ACC_UNKNOWN`]. Negative so it cannot be mistaken for a radius;
/// the Kotlin side substitutes its own conservative default.
const ACC_UNKNOWN_OUT: f64 = -1.0;

const PAYLOAD_LATLON_E8_ACC16: u8 = 1;

/// `l` is the Elias–Fano low-part width. Capped so a low value always fits a `u64`, which is
/// what the bit reader returns. The builder applies the same cap; exceeding it only ever
/// lengthens `high`, so the cap costs nothing at real record counts.
const MAX_L: u8 = 64;

// --------------------------------------------------------------------------- byte source
/// Positional reader over a region of a file, starting at `base`.
struct Src {
    file: File,
    base: u64,
}
impl Src {
    /// Same contract as the geocoder's `Src::read` (see `geocoder.rs`).
    fn read(&self, pos: u64, len: usize) -> Option<Vec<u8>> {
        let mut b = vec![0u8; len];
        #[cfg(unix)]
        {
            use std::os::unix::fs::FileExt;
            self.file.read_exact_at(&mut b, self.base + pos).ok()?;
        }
        #[cfg(windows)]
        {
            use std::os::windows::fs::FileExt;
            self.file.seek_read(&mut b, self.base + pos).ok()?;
        }
        #[cfg(not(any(unix, windows)))]
        {
            use std::io::{Read, Seek, SeekFrom};
            let mut f = self.file.try_clone().ok()?;
            f.seek(SeekFrom::Start(self.base + pos)).ok()?;
            f.read_exact(&mut b).ok()?;
        }
        Some(b)
    }
    fn rd_u64(&self, pos: u64) -> Option<u64> {
        let b = self.read(pos, 8)?;
        Some(u64::from_le_bytes([b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7]]))
    }
    fn rd_u8(&self, pos: u64) -> Option<u8> {
        Some(self.read(pos, 1)?[0])
    }
}

// --------------------------------------------------------------------------- reader
// `high` is read through a `crate::mmap::Map`: it is the one section with unpredictable access
// that is too large to hold resident.
use crate::mmap::Map;

pub struct Reader {
    src: Src,
    n: u64,
    l: u8,
    universe_bits: u8,
    high_len_bits: u64,
    /// mmap of the Elias–Fano upper bitvector.
    high: Map,
    /// `zero_samples[b]` = number of zero bits in `high[0 .. b << sample_log2)`.
    zero_samples: Vec<u64>,
    sample_log2: u8,
    low_off: u64,
    payload_off: u64,
}

impl Reader {
    #[cfg(unix)]
    fn open(fd: i32, offset: i64) -> Option<Reader> {
        let dupfd = unsafe { libc::dup(fd) };
        if dupfd < 0 {
            return None;
        }
        let file = unsafe { File::from_raw_fd(dupfd) };
        Reader::from_src(Src { file, base: offset as u64 })
    }

    /// Host fallback: `fd` is an APK asset descriptor, meaningless off-device.
    #[cfg(not(unix))]
    fn open(_fd: i32, _offset: i64) -> Option<Reader> {
        None
    }

    /// Open a `.wpsdb` straight from a filesystem path (base offset 0).
    pub fn open_path<P: AsRef<std::path::Path>>(path: P) -> Option<Reader> {
        let file = File::open(path).ok()?;
        Reader::from_src(Src { file, base: 0 })
    }

    fn from_src(src: Src) -> Option<Reader> {
        if src.read(0, 8)?[..] != MAGIC[..] {
            return None;
        }
        let _key_kind = src.rd_u8(8)?;
        let universe_bits = src.rd_u8(9)?;
        let payload_kind = src.rd_u8(10)?;
        let l = src.rd_u8(11)?;
        let sample_log2 = src.rd_u8(12)?;

        // Reject anything this build cannot decode rather than silently mis-reading it.
        if payload_kind != PAYLOAD_LATLON_E8_ACC16 || l > MAX_L {
            return None;
        }
        // `index` shifts a key right by `l` into a u64 bucket number.
        if universe_bits == 0 || universe_bits > 127 || (universe_bits as u32).saturating_sub(l as u32) > 64 {
            return None;
        }
        // `select0` scans at most one sample block, so an absurd stride would make lookups
        // unbounded; a zero stride would divide by zero.
        if sample_log2 < 6 || sample_log2 > 20 {
            return None;
        }

        let n = src.rd_u64(16)?;
        let high_len_bits = src.rd_u64(24)?;
        let high_len = src.rd_u64(32)?;
        if high_len_bits < n || high_len < high_len_bits.div_ceil(8) {
            return None;
        }

        let zeros_len_off = HIGH_OFF + high_len;
        let zeros_len = src.rd_u64(zeros_len_off)?;
        let zeros_off = zeros_len_off + 8;
        if zeros_len % 8 != 0 {
            return None;
        }
        // One sample per block, plus the leading zero. Bounded by high_len_bits, so a
        // corrupt length cannot make this allocate wildly.
        let expect_zeros = (high_len_bits >> sample_log2) + 1;
        if zeros_len / 8 != expect_zeros {
            return None;
        }
        let zeros_raw = src.read(zeros_off, zeros_len as usize)?;
        let zero_samples: Vec<u64> = zeros_raw
            .chunks_exact(8)
            .map(|c| u64::from_le_bytes([c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7]]))
            .collect();

        let low_len_off = zeros_off + zeros_len;
        let low_len = src.rd_u64(low_len_off)?;
        let low_off = low_len_off + 8;
        if low_len < (n * l as u64).div_ceil(8) {
            return None;
        }

        let payload_len_off = low_off + low_len;
        let payload_len = src.rd_u64(payload_len_off)?;
        let payload_off = payload_len_off + 8;
        if payload_len < (n * RECORD_BITS as u64).div_ceil(8) {
            return None;
        }

        // Every section must actually be on disk. This is what catches a partially
        // downloaded store, whose header parses fine and whose sizes are self-consistent;
        // without it the `high` mapping would extend past EOF and a probe into the missing
        // tail would raise SIGBUS rather than miss.
        let need = src.base + payload_off + payload_len;
        if src.file.metadata().ok()?.len() < need {
            return None;
        }

        let high = Map::new(&src.file, src.base + HIGH_OFF, high_len as usize)?;

        Some(Reader {
            src,
            n,
            l,
            universe_bits,
            high_len_bits,
            high,
            zero_samples,
            sample_log2,
            low_off,
            payload_off,
        })
    }

    #[inline]
    fn high_bit(&self, p: u64) -> bool {
        let buf = self.high.as_slice();
        (buf[(p >> 3) as usize] >> (p & 7)) & 1 == 1
    }

    /// Position of the zero bit whose 0-indexed rank is `j` (i.e. the `(j+1)`-th zero), or
    /// `None` when fewer than `j+1` zeros exist.
    fn select0(&self, j: u64) -> Option<u64> {
        let total_zeros = self.high_len_bits - self.n;
        if j >= total_zeros {
            return None;
        }
        // Largest block b with zero_samples[b] <= j.
        let mut lo = 0usize;
        let mut hi = self.zero_samples.len() - 1;
        while lo < hi {
            let mid = (lo + hi + 1) / 2;
            if self.zero_samples[mid] <= j {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        let mut count = self.zero_samples[lo];
        let mut p = (lo as u64) << self.sample_log2;
        while p < self.high_len_bits {
            if !self.high_bit(p) {
                if count == j {
                    return Some(p);
                }
                count += 1;
            }
            p += 1;
        }
        None
    }

    /// Read `nbits` bits (LSB-first) starting at bit `bit_index` within the section at file
    /// byte offset `section_off`. `nbits <= 64`.
    fn read_bits(&self, section_off: u64, bit_index: u64, nbits: u32) -> Option<u64> {
        if nbits == 0 {
            return Some(0);
        }
        let first_bit = bit_index & 7;
        let byte_pos = section_off + (bit_index >> 3);
        let nbytes = ((first_bit + nbits as u64).div_ceil(8)) as usize;
        let buf = self.src.read(byte_pos, nbytes)?;
        Some(extract_bits(&buf, first_bit as u32, nbits))
    }

    #[inline]
    fn low(&self, i: u64) -> Option<u64> {
        self.read_bits(self.low_off, i * self.l as u64, self.l as u32)
    }

    /// Decode record `i`. One positional read covers the whole 87-bit record, so a lookup
    /// costs a single `pread` here rather than one per field.
    fn record(&self, i: u64) -> Option<(f64, f64, f64)> {
        let bit_index = i * RECORD_BITS as u64;
        let first_bit = (bit_index & 7) as u32;
        let byte_pos = self.payload_off + (bit_index >> 3);
        let nbytes = ((first_bit + RECORD_BITS).div_ceil(8)) as usize;
        let buf = self.src.read(byte_pos, nbytes)?;

        let lat_u = extract_bits(&buf, first_bit, LAT_BITS);
        let lon_u = extract_bits(&buf, first_bit + LAT_BITS, LON_BITS);
        let acc_u = extract_bits(&buf, first_bit + LAT_BITS + LON_BITS, ACC_BITS);

        let lat = (lat_u as i64 - LAT_BIAS) as f64 / COORD_SCALE;
        let lon = (lon_u as i64 - LON_BIAS) as f64 / COORD_SCALE;
        let acc = if acc_u == ACC_UNKNOWN { ACC_UNKNOWN_OUT } else { acc_u as f64 };
        Some((lat, lon, acc))
    }

    /// Elias–Fano index of `key`, or `None` if `key` is not in the set.
    fn index(&self, key: u128) -> Option<u64> {
        let l = self.l as u32;
        let hi = (key >> l) as u64;
        let lo = if l == 0 { 0 } else { (key & ((1u128 << l) - 1)) as u64 };

        // i0 = number of keys with upper < hi; `start` = first high-bit position of bucket hi.
        let (mut i, start) = if hi == 0 {
            (0u64, 0u64)
        } else {
            let s0 = self.select0(hi - 1)?;
            let start = s0 + 1;
            (start - hi, start)
        };

        let mut p = start;
        let mut bucket = hi;
        while p < self.high_len_bits {
            if self.high_bit(p) {
                if bucket != hi {
                    break;
                }
                if self.low(i)? == lo {
                    return Some(i);
                }
                i += 1;
            } else {
                bucket += 1;
                if bucket > hi {
                    break;
                }
            }
            p += 1;
        }
        None
    }

    /// Look up `key`; returns `(lat, lon, accuracy_m)` or `None` for an unknown key.
    /// A negative accuracy means the source did not report one.
    pub fn lookup(&self, key: u128) -> Option<(f64, f64, f64)> {
        let i = self.index(key)?;
        self.record(i)
    }
}

/// Pull `nbits` (<= 64) LSB-first from `buf` starting at bit `first_bit`.
#[inline]
fn extract_bits(buf: &[u8], first_bit: u32, nbits: u32) -> u64 {
    let mut v = 0u64;
    for k in 0..nbits {
        let p = (first_bit + k) as usize;
        if (buf[p >> 3] >> (p & 7)) & 1 == 1 {
            v |= 1u64 << k;
        }
    }
    v
}

// --------------------------------------------------------------------------- JNI
type Handle = Reader;

/// `open(fd, offset, length) -> handle` (0 on failure). The native side dups `fd`, so the
/// caller may close its own descriptor after this returns. `length` is reserved (the section
/// framing bounds every read) and kept for API symmetry with `GeocoderNative`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_networklocation_WpsStoreNative_open<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    fd: jint,
    offset: jlong,
    _length: jlong,
) -> jlong {
    match Reader::open(fd, offset) {
        Some(r) => Box::into_raw(Box::new(r)) as jlong,
        None => 0,
    }
}

include!("wpsdb_part1.rs");
include!("wpsdb_part2.rs");