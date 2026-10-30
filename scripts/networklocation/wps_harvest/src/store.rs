//! Writer for the `WPSDB2` store format read by
//! `networklocation/src/main/rust/src/wpsdb.rs`.
//!
//! The layout is documented there and duplicated here only as far as the constants; the two
//! must stay in step. `wpsdb.rs`'s own fixture tests build a store with a transcription of
//! this writer, so a divergence shows up as a failure on the reader side too.
//!
//! ```text
//! 0  magic "WPSDB2\0\0"                8 B
//! 8  key_kind:u8, universe_bits:u8, payload_kind:u8, l:u8
//! 12 select_sample_log2:u8, reserved:u8 * 3
//! 16 n:u64
//! 24 high_len_bits:u64
//! 32 high_len:u64
//! 40 high[high_len]
//!    zeros_len:u64,   zeros[zeros_len]
//!    low_len:u64,     low[low_len]
//!    payload_len:u64, payload[payload_len]
//! ```
//!
//! Nothing is held in memory proportional to the record count except the `select0` sample
//! table (a few MB at a billion records): `high`, `low` and `payload` are streamed to scratch
//! files as the sorted records arrive and concatenated at the end.

use std::fs::File;
use std::io::{self, BufReader, BufWriter, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};

use crate::record::{ALT_UNKNOWN, Record, LAT_E8_MIN, LON_E8_MIN};

const MAGIC: &[u8; 8] = b"WPSDB2\x00\x00";

const LAT_BITS: u32 = 35;
const LON_BITS: u32 = 36;
const ACC_BITS: u32 = 16;
const ALT_BITS: u32 = 16;

/// Payload: lat(35) | lon(36) | accuracy(16) | altitude(16), LSB-first — 103 bits.
/// Altitude is metres biased by 32768 (two's complement in 16 bits), 0 meaning "unknown".
/// Kind-1 stores (87 bits, no altitude) remain readable; new builds always write kind 2.
const PAYLOAD_LATLON_E8_ACC16_ALT16: u8 = 2;

/// Cap on the Elias-Fano low width, so a low value always fits the reader's `u64`. Exceeding
/// it would only ever lengthen `high`, so the cap costs nothing at real record counts.
pub(crate) const MAX_L: u8 = 64;

/// Default `select0` sampling stride, as a power of two. 4096 bounds the in-block scan of a
/// `select0` to 4096 bit tests while keeping the table small.
pub const DEFAULT_SAMPLE_LOG2: u8 = 12;

/// Which keyspace a store indexes. Recorded in the header for diagnostics; the reader keys
/// its behaviour off `universe_bits`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum KeyKind {
    /// 48-bit BSSID.
    Wifi = 1,
    /// 84-bit packed cell identity.
    Cell = 2,
}

/// Elias-Fano low-part width for `n` keys drawn from a `universe_bits`-wide universe.
///
/// The bucket space is `2^(universe_bits - l)` bits of `high`, so a small `l` over a wide
/// universe is catastrophic rather than merely wasteful: at `n = 0` the unguarded formula
/// gives `l = 0` and asks for `2^48` bits of padding. An empty or tiny set therefore pins `l`
/// as wide as it is allowed to go, which makes the bucket space as small as possible.
pub fn choose_l(universe_bits: u8, n: u64) -> u8 {
    if n == 0 {
        return universe_bits.min(MAX_L);
    }
    let u = 1u128 << universe_bits;
    let mut l = 0u8;
    while l < MAX_L && (u >> (l as u32 + 1)) >= n as u128 {
        l += 1;
    }
    l
}

// --------------------------------------------------------------------------- bit writer
struct BitWriter<W: Write> {
    inner: W,
    cur: u8,
    used: u32,
    bits: u64,
}

impl<W: Write> BitWriter<W> {
    fn new(inner: W) -> BitWriter<W> {
        BitWriter { inner, cur: 0, used: 0, bits: 0 }
    }

    fn push(&mut self, v: u64, nbits: u32) -> io::Result<()> {
        for k in 0..nbits {
            self.push_bit((v >> k) & 1 == 1)?;
        }
        Ok(())
    }

    fn push_bit(&mut self, set: bool) -> io::Result<()> {
        if set {
            self.cur |= 1 << self.used;
        }
        self.used += 1;
        self.bits += 1;
        if self.used == 8 {
            self.inner.write_all(&[self.cur])?;
            self.cur = 0;
            self.used = 0;
        }
        Ok(())
    }

    /// Flush the partial final byte and return `(bits written, bytes written)`.
    fn finish(mut self) -> io::Result<(u64, u64)> {
        let mut bytes = self.bits / 8;
        if self.used > 0 {
            self.inner.write_all(&[self.cur])?;
            bytes += 1;
        }
        self.inner.flush()?;
        Ok((self.bits, bytes))
    }
}

// --------------------------------------------------------------------------- scratch
/// A scratch file that deletes itself.
struct Scratch {
    path: PathBuf,
}

impl Scratch {
    fn create(dir: &Path, name: &str) -> io::Result<(Scratch, File)> {
        std::fs::create_dir_all(dir)?;
        let path = dir.join(name);
        let file = File::create(&path)?;
        Ok((Scratch { path }, file))
    }
}

impl Drop for Scratch {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.path);
    }
}

fn append_file(out: &mut impl Write, path: &Path) -> io::Result<u64> {
    let mut f = BufReader::new(File::open(path)?);
    io::copy(&mut f, out)
}

// --------------------------------------------------------------------------- writer
/// Write a `WPSDB2` store.
///
/// `records` must be **sorted by key and free of duplicate keys**, and must yield exactly `n`
/// items. Both are invariants of the Elias-Fano index rather than preferences: an out-of-order
/// key produces a bitvector that decodes to a different key, and a duplicate produces two
/// records the reader can only ever reach one of. They are checked, not assumed.
///
/// Records are consumed as a stream of results so the caller can read them straight off disk;
/// nothing proportional to `n` is held in memory. `scratch_dir` holds three intermediate files
/// whose combined size is roughly that of the finished store; they are removed on both success
/// and failure.
pub fn write_store<I>(
    out_path: &Path,
    scratch_dir: &Path,
    kind: KeyKind,
    universe_bits: u8,
    n: u64,
    sample_log2: u8,
    records: I,
) -> io::Result<u64>
where
    I: IntoIterator<Item = io::Result<Record>>,
{
    if universe_bits == 0 || universe_bits > 127 {
        return Err(invalid(format!("universe_bits {universe_bits} out of range")));
    }
    if !(6..=20).contains(&sample_log2) {
        return Err(invalid(format!("select sample stride 2^{sample_log2} out of range")));
    }
    let l = choose_l(universe_bits, n);
    if (universe_bits as u32).saturating_sub(l as u32) > 64 {
        return Err(invalid("bucket space wider than 64 bits".to_string()));
    }
    let buckets = 1u64 << (universe_bits as u32 - l as u32);

    let (high_s, high_f) = Scratch::create(scratch_dir, "wpsdb.high")?;
    let (low_s, low_f) = Scratch::create(scratch_dir, "wpsdb.low")?;
    let (pay_s, pay_f) = Scratch::create(scratch_dir, "wpsdb.payload")?;

    let mut high = BitWriter::new(BufWriter::new(high_f));
    let mut low = BitWriter::new(BufWriter::new(low_f));
    let mut payload = BitWriter::new(BufWriter::new(pay_f));

    // `zeros[b]` = number of zero bits in high[0 .. b << sample_log2). Accumulated while the
    // bitvector streams past, so `high` never has to be resident to build the index.
    let sample_mask = (1u64 << sample_log2) - 1;
    let mut zeros: Vec<u64> = vec![0];
    let mut zero_count = 0u64;
    let mut high_bits = 0u64;

    let emit_high = |bit: bool,
                         high: &mut BitWriter<BufWriter<File>>,
                         zeros: &mut Vec<u64>,
                         zero_count: &mut u64,
                         high_bits: &mut u64|
     -> io::Result<()> {
        high.push_bit(bit)?;
        if !bit {
            *zero_count += 1;
        }
        *high_bits += 1;
        if (*high_bits & sample_mask) == 0 {
            zeros.push(*zero_count);
        }
        Ok(())
    };

    let mut written = 0u64;
    let mut prev_bucket = 0u64;
    let mut prev_key: Option<u128> = None;

    for r in records {
        let r = r?;
        if written == n {
            return Err(invalid(format!("more than the declared {n} records")));
        }
        if r.key >= (1u128 << universe_bits) {
            return Err(invalid(format!("key {:#x} outside the {universe_bits}-bit universe", r.key)));
        }
        if let Some(p) = prev_key {
            if r.key <= p {
                return Err(invalid(format!(
                    "records must be strictly ascending by key; {:#x} follows {:#x}",
                    r.key, p
                )));
            }
        }
        if !r.in_range() {
            return Err(invalid(format!("record {:#x} has out-of-range coordinates", r.key)));
        }
        prev_key = Some(r.key);

        let bucket = (r.key >> l) as u64;
        for _ in prev_bucket..bucket {
            emit_high(false, &mut high, &mut zeros, &mut zero_count, &mut high_bits)?;
        }
        emit_high(true, &mut high, &mut zeros, &mut zero_count, &mut high_bits)?;
        prev_bucket = bucket;

        let lo = if l == 0 { 0 } else { (r.key & ((1u128 << l) - 1)) as u64 };
        low.push(lo, l as u32)?;

        payload.push((r.lat_e8 - LAT_E8_MIN) as u64, LAT_BITS)?;
        payload.push((r.lon_e8 - LON_E8_MIN) as u64, LON_BITS)?;
        payload.push(u64::from(r.accuracy_m), ACC_BITS)?;
        // Altitude biased so "unknown" (i16::MIN) lands on 0 and real metres on 1..=65535.
        payload.push((i64::from(r.altitude_m) - i64::from(ALT_UNKNOWN)) as u64, ALT_BITS)?;

        written += 1;
    }
    if written != n {
        return Err(invalid(format!("declared {n} records but got {written}")));
    }

    // Pad the bucket space out so `select0(bucket - 1)` resolves for every reachable bucket,
    // including ones past the last key.
    for _ in prev_bucket..buckets {
        emit_high(false, &mut high, &mut zeros, &mut zero_count, &mut high_bits)?;
    }

    let (high_len_bits, high_len) = high.finish()?;
    let (_, low_len) = low.finish()?;
    let (_, payload_len) = payload.finish()?;

    debug_assert_eq!(high_len_bits, n + buckets);
    if zeros.len() as u64 != (high_len_bits >> sample_log2) + 1 {
        return Err(invalid("select0 sample table has the wrong length".to_string()));
    }

    let mut out = BufWriter::new(File::create(out_path)?);
    out.write_all(MAGIC)?;
    out.write_all(&[kind as u8, universe_bits, PAYLOAD_LATLON_E8_ACC16_ALT16, l])?;
    out.write_all(&[sample_log2, 0, 0, 0])?;
    out.write_all(&n.to_le_bytes())?;
    out.write_all(&high_len_bits.to_le_bytes())?;
    out.write_all(&high_len.to_le_bytes())?;
    let copied = append_file(&mut out, &high_s.path)?;
    debug_assert_eq!(copied, high_len);
    out.write_all(&((zeros.len() * 8) as u64).to_le_bytes())?;
    for z in &zeros {
        out.write_all(&z.to_le_bytes())?;
    }
    out.write_all(&low_len.to_le_bytes())?;
    let _ = append_file(&mut out, &low_s.path)?;
    out.write_all(&payload_len.to_le_bytes())?;
    let _ = append_file(&mut out, &pay_s.path)?;
    out.flush()?;

    let mut f = out.into_inner().map_err(|e| e.into_error())?;
    let size = f.seek(SeekFrom::End(0))?;
    Ok(size)
}

fn invalid(msg: String) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, msg)
}

// --------------------------------------------------------------------------- self-test
/// Sequential LSB-first bit reader over one section of the store.
struct BitStream<R: Read> {
    inner: R,
    acc: u64,
    have: u32,
}

impl<R: Read> BitStream<R> {
    fn new(inner: R) -> BitStream<R> {
        BitStream { inner, acc: 0, have: 0 }
    }

    fn take_small(&mut self, n: u32) -> io::Result<u64> {
        while self.have < n {
            let mut b = [0u8; 1];
            self.inner.read_exact(&mut b)?;
            self.acc |= (b[0] as u64) << self.have;
            self.have += 8;
        }
        let v = if n == 64 { self.acc } else { self.acc & ((1u64 << n) - 1) };
        self.acc >>= n;
        self.have -= n;
        Ok(v)
    }

    /// Next `n` bits (`n <= 64`). Taken in 32-bit chunks so the accumulator cannot overflow.
    fn take(&mut self, n: u32) -> io::Result<u64> {
        let mut out = 0u64;
        let mut taken = 0u32;
        while taken < n {
            let chunk = (n - taken).min(32);
            out |= self.take_small(chunk)? << taken;
            taken += chunk;
        }
        Ok(out)
    }

    fn take_bit(&mut self) -> io::Result<bool> {
        Ok(self.take_small(1)? == 1)
    }
}

fn section_reader(path: &Path, offset: u64) -> io::Result<BufReader<File>> {
    let mut f = File::open(path)?;
    let _ = f.seek(SeekFrom::Start(offset))?;
    Ok(BufReader::with_capacity(1 << 16, f))
}

/// Read a finished store back and check every record resolves to what was written.
///
/// The store's own writer is not reused: this walks the Elias-Fano bitvector directly instead
/// of using `select0`, so a shared misunderstanding between two `index()` implementations
/// cannot hide here. It is also the only check that the bytes on disk are what the writer
/// believed it wrote.
///
/// `expected` must yield the same records, in the same order, that were written. All four
/// sections are streamed in parallel — `idx` only ever increases, so `low` and `payload` are
/// consumed strictly in order — which keeps this O(1) in memory over a store of any size.
pub fn verify_store<I>(path: &Path, expected: I) -> io::Result<u64>
where
    I: IntoIterator<Item = io::Result<Record>>,
{
    let mut head = [0u8; 40];
    File::open(path)?.read_exact(&mut head)?;
    if &head[0..8] != MAGIC {
        return Err(invalid("bad magic".to_string()));
    }
    let universe_bits = head[9];
    let l = head[11] as u32;
    let n = u64::from_le_bytes(head[16..24].try_into().map_err(|_| invalid("short header".into()))?);
    let high_len_bits =
        u64::from_le_bytes(head[24..32].try_into().map_err(|_| invalid("short header".into()))?);
    let high_len =
        u64::from_le_bytes(head[32..40].try_into().map_err(|_| invalid("short header".into()))?);

    // Section offsets follow from the lengths; the file has no offset table.
    let mut len_buf = [0u8; 8];
    let read_u64_at = |off: u64, buf: &mut [u8; 8]| -> io::Result<u64> {
        let mut f = File::open(path)?;
        let _ = f.seek(SeekFrom::Start(off))?;
        f.read_exact(buf)?;
        Ok(u64::from_le_bytes(*buf))
    };
    let zeros_len_off = 40 + high_len;
    let zeros_len = read_u64_at(zeros_len_off, &mut len_buf)?;
    let low_len_off = zeros_len_off + 8 + zeros_len;
    let low_len = read_u64_at(low_len_off, &mut len_buf)?;
    let payload_len_off = low_len_off + 8 + low_len;
    let _payload_len = read_u64_at(payload_len_off, &mut len_buf)?;

    let mut high = BitStream::new(section_reader(path, 40)?);
    let mut low = BitStream::new(section_reader(path, low_len_off + 8)?);
    let mut payload = BitStream::new(section_reader(path, payload_len_off + 8)?);

    let mut want = expected.into_iter();
    let mut idx = 0u64;
    let mut p = 0u64;
    while p < high_len_bits && idx < n {
        if high.take_bit()? {
            let upper = (p - idx) as u128;
            let lo = if l == 0 { 0 } else { low.take(l)? as u128 };
            let key = (upper << l) | lo;

            let lat = payload.take(LAT_BITS)? as i64 + LAT_E8_MIN;
            let lon = payload.take(LON_BITS)? as i64 + LON_E8_MIN;
            let acc = payload.take(ACC_BITS)? as u16;
            let alt = payload.take(ALT_BITS)? as i64 + i64::from(ALT_UNKNOWN);

            let w = want
                .next()
                .transpose()?
                .ok_or_else(|| invalid(format!("store holds more than the {idx} expected")))?;
            if key != w.key || lat != w.lat_e8 || lon != w.lon_e8 || acc != w.accuracy_m {
                return Err(invalid(format!(
                    "record {idx} mismatch: got ({key:#x}, {lat}, {lon}, {acc}), \
                     want ({:#x}, {}, {}, {})",
                    w.key, w.lat_e8, w.lon_e8, w.accuracy_m
                )));
            }
            if alt != i64::from(w.altitude_m) {
                return Err(invalid(format!(
                    "record {idx} altitude mismatch: got {alt}, want {}",
                    w.altitude_m
                )));
            }
            if key >= (1u128 << universe_bits) {
                return Err(invalid(format!("record {idx} key escapes the universe")));
            }
            idx += 1;
        }
        p += 1;
    }
    if idx != n {
        return Err(invalid(format!("recovered {idx} of {n} records from the bitvector")));
    }
    if want.next().transpose()?.is_some() {
        return Err(invalid(format!("expected more records than the {n} the store holds")));
    }
    Ok(n)
}
