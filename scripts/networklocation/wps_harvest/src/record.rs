//! Observation records and the fixed-width scratch format the pipeline passes them in.
//!
//! `wps_seed` and `wps_crawl` both emit unsorted [`Record`] shards; `wps_build` sorts, merges
//! and filters them into a store. The scratch format is fixed-width so an external sort can
//! seek and a merge can stream, and little-endian so a shard is byte-identical on any host
//! the pipeline runs on.

use std::io::{self, Read, Write};

/// Bytes on disk per record: key(16) + lat(8) + lon(8) + accuracy(2) + source(1)
/// + altitude(2) + pad(2) + flags(1).
///
/// The altitude slot and flag byte reuse what was the zero pad, so a shard written before
/// altitude existed still reads cleanly: its flags byte is 0, meaning "altitude unknown".
pub const RECORD_BYTES: usize = 40;

/// `lat * 1e8`, the range the store's 35-bit latitude field covers.
pub const LAT_E8_MIN: i64 = -90_00_000_000;
/// `lat * 1e8`, upper bound.
pub const LAT_E8_MAX: i64 = 90_00_000_000;
/// `lon * 1e8`, the range the store's 36-bit longitude field covers.
pub const LON_E8_MIN: i64 = -180_00_000_000;
/// `lon * 1e8`, upper bound.
pub const LON_E8_MAX: i64 = 180_00_000_000;

/// Stored accuracy meaning "the source did not report one".
pub const ACC_UNKNOWN: u16 = 0xFFFF;

/// Stored altitude meaning "the source did not report one" (or the shard predates altitude,
/// which reads as flags = 0). Metres are otherwise well inside `i16` (Dead Sea to Everest).
pub const ALT_UNKNOWN: i16 = i16::MIN;

/// Flags byte bit: the altitude slot holds a real measurement.
const FLAG_ALTITUDE_PRESENT: u8 = 1;

/// Where an observation came from. Kept per record so the merge can prefer a source and so a
/// disagreement between sources is attributable rather than anonymous.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Source {
    /// beacondb bulk dump.
    Beacondb = 1,
    /// OpenCelliD bulk dump.
    OpenCellId = 2,
    /// Apple gs-loc crawl.
    Gsloc = 3,
}

impl Source {
    fn from_wire(v: u8) -> Option<Source> {
        match v {
            1 => Some(Source::Beacondb),
            2 => Some(Source::OpenCellId),
            3 => Some(Source::Gsloc),
            _ => None,
        }
    }
}

/// One observation of one beacon.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Record {
    /// Packed store key (see [`crate::keys`]).
    pub key: u128,
    /// Latitude in degrees x 1e8.
    pub lat_e8: i64,
    /// Longitude in degrees x 1e8.
    pub lon_e8: i64,
    /// Horizontal accuracy in metres, or [`ACC_UNKNOWN`].
    pub accuracy_m: u16,
    /// Altitude in metres (Apple's `location.5`), or [`ALT_UNKNOWN`]. Cells and the bulk
    /// CSV dumps have no altitude; WiFi from gs-loc does.
    pub altitude_m: i16,
    /// Which dataset or crawl produced it.
    pub source: Source,
}

impl Record {
    /// Whether the coordinates are inside the range the store's fields can represent.
    ///
    /// Out-of-range values are not clamped: a clamped coordinate is a confident-looking claim
    /// about the wrong place, whereas a dropped record is just a miss.
    pub fn in_range(&self) -> bool {
        (LAT_E8_MIN..=LAT_E8_MAX).contains(&self.lat_e8)
            && (LON_E8_MIN..=LON_E8_MAX).contains(&self.lon_e8)
    }

    /// Serialize into `out`, which must be [`RECORD_BYTES`] long.
    pub fn write_into(&self, out: &mut [u8; RECORD_BYTES]) {
        out[0..16].copy_from_slice(&self.key.to_le_bytes());
        out[16..24].copy_from_slice(&self.lat_e8.to_le_bytes());
        out[24..32].copy_from_slice(&self.lon_e8.to_le_bytes());
        out[32..34].copy_from_slice(&self.accuracy_m.to_le_bytes());
        out[34] = self.source as u8;
        let has_alt = self.altitude_m != ALT_UNKNOWN;
        out[35..37].copy_from_slice(&self.altitude_m.to_le_bytes());
        out[37..39].fill(0);
        out[39] = if has_alt { FLAG_ALTITUDE_PRESENT } else { 0 };
    }

    /// Deserialize from exactly [`RECORD_BYTES`], or `None` if the source byte is unknown.
    pub fn read_from(buf: &[u8; RECORD_BYTES]) -> Option<Record> {
        let mut k = [0u8; 16];
        k.copy_from_slice(&buf[0..16]);
        let mut a = [0u8; 8];
        a.copy_from_slice(&buf[16..24]);
        let mut b = [0u8; 8];
        b.copy_from_slice(&buf[24..32]);
        let altitude_m = if buf[39] & FLAG_ALTITUDE_PRESENT != 0 {
            i16::from_le_bytes([buf[35], buf[36]])
        } else {
            ALT_UNKNOWN
        };
        Some(Record {
            key: u128::from_le_bytes(k),
            lat_e8: i64::from_le_bytes(a),
            lon_e8: i64::from_le_bytes(b),
            accuracy_m: u16::from_le_bytes([buf[32], buf[33]]),
            altitude_m,
            source: Source::from_wire(buf[34])?,
        })
    }
}

/// Buffered writer for a record shard.
pub struct RecordWriter<W: Write> {
    inner: W,
    scratch: [u8; RECORD_BYTES],
    count: u64,
}

impl<W: Write> RecordWriter<W> {
    /// Wrap `inner`. Callers are expected to pass something buffered.
    pub fn new(inner: W) -> RecordWriter<W> {
        RecordWriter { inner, scratch: [0u8; RECORD_BYTES], count: 0 }
    }

    /// Append one record.
    pub fn push(&mut self, r: &Record) -> io::Result<()> {
        r.write_into(&mut self.scratch);
        self.inner.write_all(&self.scratch)?;
        self.count += 1;
        Ok(())
    }

    /// Flush buffered records to disk without consuming the writer.
    ///
    /// A crawl runs for weeks and is expected to be killed. Buffered records that were never
    /// flushed are simply lost, and the frontier will not re-offer the BSSIDs that produced
    /// them, so the work is gone for good — flushing at each checkpoint bounds that loss.
    pub fn flush(&mut self) -> io::Result<()> {
        self.inner.flush()
    }

    /// Records written so far.
    pub fn count(&self) -> u64 {
        self.count
    }

    /// Flush and return the number of records written.
    pub fn finish(mut self) -> io::Result<u64> {
        self.inner.flush()?;
        Ok(self.count)
    }
}

/// Streaming reader over a record shard.
pub struct RecordReader<R: Read> {
    inner: R,
    scratch: [u8; RECORD_BYTES],
}

impl<R: Read> RecordReader<R> {
    /// Wrap `inner`. Callers are expected to pass something buffered.
    pub fn new(inner: R) -> RecordReader<R> {
        RecordReader { inner, scratch: [0u8; RECORD_BYTES] }
    }

    /// Next record, or `Ok(None)` at a clean end of file.
    ///
    /// A partial trailing record is an error rather than an end: shards are produced by long
    /// runs that can be killed, and silently ignoring a torn tail would quietly drop data.
    pub fn next(&mut self) -> io::Result<Option<Record>> {
        let mut filled = 0usize;
        while filled < RECORD_BYTES {
            match self.inner.read(&mut self.scratch[filled..])? {
                0 => break,
                n => filled += n,
            }
        }
        if filled == 0 {
            return Ok(None);
        }
        if filled != RECORD_BYTES {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                format!("shard ends with a partial record ({filled} of {RECORD_BYTES} bytes)"),
            ));
        }
        Record::read_from(&self.scratch)
            .map(Some)
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "unknown record source"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> Record {
        Record {
            key: (0x4D904u128 << 64) | 0x3003039ABCDEF123,
            lat_e8: 37_77_493_000,
            lon_e8: -122_41_942_000,
            accuracy_m: 35,
            altitude_m: 12,
            source: Source::Gsloc,
        }
    }

    #[test]
    fn record_round_trips_through_the_scratch_format() {
        let r = sample();
        let mut buf = [0u8; RECORD_BYTES];
        r.write_into(&mut buf);
        assert_eq!(Record::read_from(&buf), Some(r));
    }

    #[test]
    fn extremes_and_the_unknown_accuracy_sentinel_round_trip() {
        for r in [
            Record { lat_e8: LAT_E8_MIN, lon_e8: LON_E8_MIN, accuracy_m: 0, ..sample() },
            Record { lat_e8: LAT_E8_MAX, lon_e8: LON_E8_MAX, accuracy_m: 65534, ..sample() },
            Record { accuracy_m: ACC_UNKNOWN, ..sample() },
            Record { key: u128::MAX >> 44, ..sample() },
        ] {
            let mut buf = [0u8; RECORD_BYTES];
            r.write_into(&mut buf);
            assert_eq!(Record::read_from(&buf), Some(r));
        }
    }

    #[test]
    fn altitude_round_trips_and_absence_is_distinct_from_zero() {
        for alt in [ALT_UNKNOWN, -430, 0, 55, 8848] {
            let r = Record { altitude_m: alt, ..sample() };
            let mut buf = [0u8; RECORD_BYTES];
            r.write_into(&mut buf);
            assert_eq!(Record::read_from(&buf).unwrap().altitude_m, alt, "altitude {alt}");
        }
    }

    #[test]
    fn a_pre_altitude_shard_reads_as_altitude_unknown() {
        // Old layout: everything past `source` was zero pad.
        let r = Record { altitude_m: 250, ..sample() };
        let mut buf = [0u8; RECORD_BYTES];
        r.write_into(&mut buf);
        buf[35..40].fill(0);
        assert_eq!(Record::read_from(&buf).unwrap().altitude_m, ALT_UNKNOWN);
    }

    #[test]
    fn out_of_range_coordinates_are_detected() {
        assert!(sample().in_range());
        assert!(!Record { lat_e8: LAT_E8_MAX + 1, ..sample() }.in_range());
        assert!(!Record { lon_e8: LON_E8_MIN - 1, ..sample() }.in_range());
    }

    #[test]
    fn a_shard_round_trips_through_reader_and_writer() {
        let records: Vec<Record> = (0..500)
            .map(|i| Record { key: i as u128 * 7919, accuracy_m: i as u16, ..sample() })
            .collect();
        let mut bytes: Vec<u8> = Vec::new();
        let mut w = RecordWriter::new(&mut bytes);
        for r in &records {
            w.push(r).unwrap();
        }
        assert_eq!(w.finish().unwrap(), 500);

        let mut rd = RecordReader::new(bytes.as_slice());
        let mut got = Vec::new();
        while let Some(r) = rd.next().unwrap() {
            got.push(r);
        }
        assert_eq!(got, records);
    }

    #[test]
    fn a_torn_trailing_record_is_an_error_not_an_end() {
        let mut bytes: Vec<u8> = Vec::new();
        let mut w = RecordWriter::new(&mut bytes);
        w.push(&sample()).unwrap();
        w.finish().unwrap();
        bytes.truncate(RECORD_BYTES + 9);
        bytes.extend_from_slice(&[0u8; 9]);
        bytes.truncate(RECORD_BYTES + 9);

        let mut rd = RecordReader::new(bytes.as_slice());
        assert_eq!(rd.next().unwrap(), Some(sample()));
        assert!(rd.next().is_err(), "a partial tail must not read as end of shard");
    }
}
