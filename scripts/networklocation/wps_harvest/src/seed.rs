//! Ingest of the open bulk datasets that seed the stores.
//!
//! **Cells only, in practice.** OpenCelliD publishes a full CSV dump. There is no equivalent
//! for WiFi: beaconDB's dumps are announced but unpublished, Mozilla Location Services was
//! never able to release its WiFi data for legal and privacy reasons, and WiGLE's terms forbid
//! bulk redistribution. The WiFi store is built by `wps_crawl` instead, which needs only a
//! handful of real BSSIDs to start from.
//!
//! The parser is nonetheless source-agnostic and handles WiFi dumps too, so a dump becoming
//! available later needs no code change. Column names differ between sources and have changed
//! over time, so it is driven by the header row rather than by fixed positions — a renamed
//! column fails loudly instead of silently reading longitude out of the accuracy field.
//!
//! Coordinates are parsed as **exact decimals**, not through `f64`. The store keeps degrees
//! x 1e8 and the whole point of that choice is that it is bit-exact against the source, which
//! a parse-then-multiply round trip would quietly give up.
//!
//! Compressed dumps are read from stdin (`gunzip -c dump.csv.gz | wps_seed -`) rather than
//! decompressed in process; that keeps a decompressor out of the dependency list for a step
//! that runs once per dataset.

use std::io::{self, BufRead};

use crate::keys::{self, CellId, RadioType};
use crate::quality::clamp_accuracy;
use crate::record::{ALT_UNKNOWN, Record, Source, ACC_UNKNOWN};

/// Column aliases accepted for each field, lowercased.
const MAC_NAMES: &[&str] = &["bssid", "mac", "macaddress", "mac_address"];
const LAT_NAMES: &[&str] = &["lat", "latitude"];
const LON_NAMES: &[&str] = &["lon", "long", "lng", "longitude"];
const ACC_NAMES: &[&str] = &["accuracy", "range", "radius", "horizontal_accuracy"];
const RADIO_NAMES: &[&str] = &["radio", "radiotype", "radio_type"];
const MCC_NAMES: &[&str] = &["mcc", "mobilecountrycode"];
const MNC_NAMES: &[&str] = &["net", "mnc", "mobilenetworkcode"];
const AREA_NAMES: &[&str] = &["area", "lac", "tac", "lacid", "locationareacode"];
const CELL_NAMES: &[&str] = &["cell", "cid", "cellid", "cell_id"];

/// What a dump holds.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    /// WiFi access points, keyed by BSSID.
    Wifi,
    /// Cell towers.
    Cell,
}

/// Column positions resolved from a header row.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Schema {
    kind: Kind,
    mac: usize,
    lat: usize,
    lon: usize,
    accuracy: Option<usize>,
    radio: Option<usize>,
    mcc: usize,
    mnc: usize,
    area: usize,
    cell: usize,
}

fn find(header: &[String], names: &[&str]) -> Option<usize> {
    header.iter().position(|h| names.contains(&h.as_str()))
}

impl Schema {
    /// Resolve `header` for a dump of `kind`.
    pub fn from_header(kind: Kind, header_line: &str) -> Result<Schema, String> {
        let header: Vec<String> =
            split_csv(header_line).into_iter().map(|s| s.trim().to_ascii_lowercase()).collect();

        let lat = find(&header, LAT_NAMES).ok_or("no latitude column")?;
        let lon = find(&header, LON_NAMES).ok_or("no longitude column")?;
        let accuracy = find(&header, ACC_NAMES);
        let radio = find(&header, RADIO_NAMES);

        let (mac, mcc, mnc, area, cell) = match kind {
            Kind::Wifi => (find(&header, MAC_NAMES).ok_or("no bssid column")?, 0, 0, 0, 0),
            Kind::Cell => (
                0,
                find(&header, MCC_NAMES).ok_or("no mcc column")?,
                find(&header, MNC_NAMES).ok_or("no mnc/net column")?,
                find(&header, AREA_NAMES).ok_or("no area/lac/tac column")?,
                find(&header, CELL_NAMES).ok_or("no cell id column")?,
            ),
        };
        if kind == Kind::Cell && radio.is_none() {
            // Without it every tower would be filed under one radio type and LTE/GSM cells
            // would alias, which is exactly the bug the new key layout exists to fix.
            return Err("no radio column; a cell dump without one cannot be keyed".to_string());
        }
        Ok(Schema { kind, mac, lat, lon, accuracy, radio, mcc, mnc, area, cell })
    }

    /// Parse one data row. `Ok(None)` means "skip this row", which is normal: dumps carry
    /// entries this store cannot represent.
    pub fn parse_row(&self, line: &str, source: Source) -> Result<Option<Record>, String> {
        let f = split_csv(line);
        let get = |i: usize| -> Result<&str, String> {
            f.get(i).map(|s| s.trim()).ok_or_else(|| format!("row has only {} fields", f.len()))
        };

        let lat_e8 = match parse_degrees(get(self.lat)?) {
            Some(v) => v,
            None => return Ok(None),
        };
        let lon_e8 = match parse_degrees(get(self.lon)?) {
            Some(v) => v,
            None => return Ok(None),
        };
        let accuracy_m = match self.accuracy {
            // Dumps report an integer radius in metres; a blank or unparseable one is
            // "unknown", not zero, because zero would claim perfect precision.
            Some(i) => get(i)?.parse::<f64>().ok().map_or(ACC_UNKNOWN, |v| clamp_accuracy(v as i32)),
            None => ACC_UNKNOWN,
        };

        let key = match self.kind {
            Kind::Wifi => {
                let Some(mac) = keys::parse_mac(&get(self.mac)?.to_ascii_lowercase()) else {
                    return Ok(None);
                };
                keys::wifi_key(mac)
            }
            Kind::Cell => {
                let radio = match self.radio.and_then(|i| get(i).ok()).and_then(RadioType::parse) {
                    Some(r) => r,
                    None => return Ok(None),
                };
                let id = CellId {
                    mcc: match get(self.mcc)?.parse() {
                        Ok(v) => v,
                        Err(_) => return Ok(None),
                    },
                    mnc: match get(self.mnc)?.parse() {
                        Ok(v) => v,
                        Err(_) => return Ok(None),
                    },
                    radio,
                    cell_id: match get(self.cell)?.parse() {
                        Ok(v) => v,
                        Err(_) => return Ok(None),
                    },
                    area: match get(self.area)?.parse() {
                        Ok(v) => v,
                        Err(_) => return Ok(None),
                    },
                };
                if !keys::cell_fits(&id) {
                    return Ok(None);
                }
                keys::cell_key(&id)
            }
        };

        let record = Record { key, lat_e8, lon_e8, accuracy_m, altitude_m: ALT_UNKNOWN, source };
        Ok(record.in_range().then_some(record))
    }
}

/// Split one CSV line, honouring double-quoted fields.
///
/// Not a general CSV implementation: these dumps have no embedded newlines, and a field is
/// either bare or wholly quoted.
fn split_csv(line: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut cur = String::new();
    let mut quoted = false;
    let mut chars = line.trim_end_matches(['\r', '\n']).chars().peekable();
    while let Some(c) = chars.next() {
        match c {
            '"' if quoted && chars.peek() == Some(&'"') => {
                cur.push('"');
                let _ = chars.next();
            }
            '"' => quoted = !quoted,
            ',' if !quoted => out.push(std::mem::take(&mut cur)),
            _ => cur.push(c),
        }
    }
    out.push(cur);
    out
}

/// Parse decimal degrees to degrees x 1e8, exactly.
///
/// Going through `f64` would round: `-122.41942` is not representable, so multiplying by 1e8
/// and truncating can land a unit either side. Digits are shifted instead, which is both exact
/// and immune to locale.
pub fn parse_degrees(s: &str) -> Option<i64> {
    let s = s.trim();
    if s.is_empty() {
        return None;
    }
    let (neg, rest) = match s.as_bytes().first()? {
        b'-' => (true, s.get(1..)?),
        b'+' => (false, s.get(1..)?),
        _ => (false, s),
    };
    if rest.is_empty() {
        return None;
    }
    let (int_part, frac_part) = match rest.split_once('.') {
        Some((i, f)) => (i, f),
        None => (rest, ""),
    };
    // An empty integer part (".5") is fine; anything non-numeric is not.
    if !int_part.bytes().all(|b| b.is_ascii_digit()) || !frac_part.bytes().all(|b| b.is_ascii_digit())
    {
        return None;
    }
    let whole: i64 = if int_part.is_empty() { 0 } else { int_part.parse().ok()? };

    let mut frac: i64 = 0;
    for i in 0..8 {
        let digit = frac_part.as_bytes().get(i).map_or(0, |b| (b - b'0') as i64);
        frac = frac * 10 + digit;
    }
    let v = whole.checked_mul(100_000_000)?.checked_add(frac)?;
    Some(if neg { -v } else { v })
}

/// Read a whole dump, calling `sink` for every record that survives parsing.
///
/// Returns `(rows read, records emitted)`. Malformed rows are counted, not fatal: a bulk dump
/// of hundreds of millions of lines will have some, and aborting the ingest over one is worse
/// than skipping it. A malformed *header* is fatal, because that means every row is being
/// misread.
pub fn read_dump<R: BufRead>(
    input: R,
    kind: Kind,
    source: Source,
    mut sink: impl FnMut(Record) -> io::Result<()>,
) -> io::Result<(u64, u64, u64)> {
    let mut lines = input.lines();
    let header = lines
        .next()
        .transpose()?
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "empty dump"))?;
    let schema = Schema::from_header(kind, &header)
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, format!("{e}: {header:?}")))?;

    let (mut rows, mut emitted, mut skipped) = (0u64, 0u64, 0u64);
    for line in lines {
        let line = line?;
        if line.trim().is_empty() {
            continue;
        }
        rows += 1;
        match schema.parse_row(&line, source) {
            Ok(Some(r)) => {
                sink(r)?;
                emitted += 1;
            }
            Ok(None) | Err(_) => skipped += 1,
        }
    }
    Ok((rows, emitted, skipped))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn degrees_parse_exactly_without_going_through_f64() {
        assert_eq!(parse_degrees("0"), Some(0));
        assert_eq!(parse_degrees("1"), Some(100_000_000));
        assert_eq!(parse_degrees("-1"), Some(-100_000_000));
        assert_eq!(parse_degrees("37.774930"), Some(3_777_493_000));
        assert_eq!(parse_degrees("-122.41942"), Some(-12_241_942_000));
        assert_eq!(parse_degrees("+51.5"), Some(5_150_000_000));
        assert_eq!(parse_degrees(".5"), Some(50_000_000));
        assert_eq!(parse_degrees("-0.00000001"), Some(-1));
        // More precision than the field holds is truncated, not rounded up into a lie.
        assert_eq!(parse_degrees("1.123456789"), Some(112_345_678));
        // 90 and 180 exactly, the range endpoints.
        assert_eq!(parse_degrees("90"), Some(9_000_000_000));
        assert_eq!(parse_degrees("-180"), Some(-18_000_000_000));
    }

    #[test]
    fn malformed_degrees_are_rejected() {
        for bad in ["", " ", "-", "+", "abc", "1.2.3", "1e5", "--1", "1,5", "nan"] {
            assert_eq!(parse_degrees(bad), None, "{bad:?} must not parse");
        }
    }

    #[test]
    fn csv_splitting_handles_quotes() {
        assert_eq!(split_csv("a,b,c"), vec!["a", "b", "c"]);
        assert_eq!(split_csv("a,,c"), vec!["a", "", "c"]);
        assert_eq!(split_csv(r#"a,"b,c",d"#), vec!["a", "b,c", "d"]);
        assert_eq!(split_csv(r#""a""b",c"#), vec![r#"a"b"#, "c"]);
        assert_eq!(split_csv("a,b\r\n"), vec!["a", "b"]);
    }

    #[test]
    fn opencellid_header_resolves() {
        let header = "radio,mcc,net,area,cell,unit,lon,lat,range,samples,changeable,created,updated,averageSignal";
        let s = Schema::from_header(Kind::Cell, header).unwrap();
        let row = "LTE,310,260,4242,123456789,0,-122.41942,37.774930,650,12,1,0,0,-90";
        let r = s.parse_row(row, Source::OpenCellId).unwrap().unwrap();
        assert_eq!(r.lat_e8, 3_777_493_000);
        assert_eq!(r.lon_e8, -12_241_942_000);
        assert_eq!(r.accuracy_m, 650);
        assert_eq!(
            r.key,
            keys::cell_key(&CellId {
                mcc: 310,
                mnc: 260,
                radio: RadioType::Lte,
                cell_id: 123456789,
                area: 4242,
            })
        );
    }

    #[test]
    fn a_wifi_header_resolves_under_either_spelling() {
        for header in ["bssid,lat,lon,accuracy", "mac,latitude,longitude,radius"] {
            let s = Schema::from_header(Kind::Wifi, header).unwrap();
            let r = s
                .parse_row("00:11:22:33:44:55,37.774930,-122.41942,20", Source::Beacondb)
                .unwrap()
                .unwrap();
            assert_eq!(r.key, 0x001122334455);
            assert_eq!(r.accuracy_m, 20);
        }
    }

    #[test]
    fn a_missing_column_is_a_hard_error_not_a_wrong_guess() {
        assert!(Schema::from_header(Kind::Wifi, "lat,lon").is_err());
        assert!(Schema::from_header(Kind::Cell, "mcc,net,area,cell,lat,lon").is_err());
        assert!(Schema::from_header(Kind::Cell, "radio,mcc,net,cell,lat,lon").is_err());
    }

    #[test]
    fn rows_the_store_cannot_represent_are_skipped_not_truncated() {
        let s = Schema::from_header(Kind::Cell, "radio,mcc,net,area,cell,lon,lat,range").unwrap();
        // 5G NCI wider than 36 bits.
        let too_wide = format!("NR,310,260,1,{},1.0,1.0,100", 1u64 << 37);
        assert_eq!(s.parse_row(&too_wide, Source::OpenCellId).unwrap(), None);
        // CDMA has no identity of this shape.
        assert_eq!(s.parse_row("CDMA,310,260,1,5,1.0,1.0,100", Source::OpenCellId).unwrap(), None);
        // Out of range coordinates.
        assert_eq!(s.parse_row("LTE,310,260,1,5,1.0,91.0,100", Source::OpenCellId).unwrap(), None);
        // A zero cell id is a placeholder, not a cell.
        assert_eq!(s.parse_row("LTE,310,260,1,0,1.0,1.0,100", Source::OpenCellId).unwrap(), None);
    }

    #[test]
    fn a_blank_accuracy_is_unknown_rather_than_zero() {
        let s = Schema::from_header(Kind::Wifi, "bssid,lat,lon,accuracy").unwrap();
        let r = s.parse_row("00:11:22:33:44:55,1.0,1.0,", Source::Beacondb).unwrap().unwrap();
        assert_eq!(r.accuracy_m, ACC_UNKNOWN);
    }

    #[test]
    fn a_dump_reads_end_to_end_and_counts_what_it_skipped() {
        let dump = "bssid,lat,lon,accuracy\n\
                    00:11:22:33:44:55,37.774930,-122.41942,20\n\
                    garbage\n\
                    \n\
                    00:11:22:33:44:56,51.5,-0.1276,15\n\
                    zz:11:22:33:44:57,1.0,1.0,5\n";
        let mut got = Vec::new();
        let (rows, emitted, skipped) =
            read_dump(dump.as_bytes(), Kind::Wifi, Source::Beacondb, |r| {
                got.push(r);
                Ok(())
            })
            .unwrap();
        assert_eq!((rows, emitted, skipped), (4, 2, 2));
        assert_eq!(got.len(), 2);
    }

    #[test]
    fn a_bad_header_is_fatal() {
        let dump = "wat,who\n1,2\n";
        let err = read_dump(dump.as_bytes(), Kind::Wifi, Source::Beacondb, |_| Ok(()));
        assert!(err.is_err(), "a misread header would misread every row");
    }
}
