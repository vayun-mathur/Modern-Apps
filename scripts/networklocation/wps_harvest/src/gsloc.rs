//! Client for Apple's location service at `gs-loc.apple.com/clls/wloc`.
//!
//! Ported from the Kotlin client this app used to carry, recovered in
//! `reference/ApplePositioningService.kt.txt`; the wire schema is `reference/apple_wps.proto`.
//!
//! Apple does not return a device position. Given beacon identifiers it returns each beacon's
//! own coordinates and horizontal accuracy — which is precisely a beacon database lookup, and
//! why this makes a store builder rather than a positioning client.
//!
//! It also returns **more beacons than were asked for**: a query for one BSSID comes back with
//! a few hundred others in the same area. That is what makes a crawl possible at all, since
//! every response both answers the query and extends the frontier.
//!
//! ## This talks to somebody else's service
//!
//! Requests are serialized through one client and rate-limited by [`Client::min_interval`],
//! defaulting to something a person could plausibly generate. Errors back off rather than
//! retrying hard. Doing otherwise would be both rude and self-defeating.

use std::time::{Duration, Instant};

use crate::proto::{self, Field};

const ENDPOINT: &str = "https://gs-loc.apple.com/clls/wloc";

/// Apple prefixes the response protobuf with a fixed-size header.
const RESPONSE_HEADER_BYTES: usize = 10;

/// OS version string in the request envelope. Must be 10 bytes to match the hardcoded length.
const OS_VERSION: &str = "17.5.21F79";

/// Coordinates arrive as integer degrees x 1e8, which is exactly what the store keeps.
pub const COORD_SCALE: i64 = 100_000_000;

/// What Apple returns for a beacon it does not know: -180 degrees, in both axes.
const NOT_FOUND_E8: i64 = -18_000_000_000;

// Field numbers within `AppleWLoc`, from wtfps's `src/apple.proto`.
const F_WIFI_DEVICES: u32 = 2;
const F_CELL_RESPONSE: u32 = 22;
const F_CELL_REQUEST: u32 = 25;

/// Altitude (Apple's `location.5`) meaning "not reported": Apple sends -1.
pub const ALT_NOT_REPORTED: i64 = -1;

/// Clamp a source-reported altitude into the store's `i16` metres, mapping Apple's -1
/// "not reported" to [`crate::record::ALT_UNKNOWN`]. Saturates rather than wrapping: nothing
/// real sits outside ±32 km, so saturation can only trigger on garbage, which then reads as
/// extreme rather than plausible.
pub fn clamp_altitude(metres: i64) -> i16 {
    if metres == ALT_NOT_REPORTED {
        i16::MIN
    } else {
        metres.clamp(i16::MIN as i64 + 1, i16::MAX as i64) as i16
    }
}

/// A beacon Apple knows the location of.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WifiFix {
    /// Lowercase `aa:bb:cc:dd:ee:ff`, as echoed back.
    pub bssid: String,
    /// Latitude in degrees x 1e8.
    pub lat_e8: i64,
    /// Longitude in degrees x 1e8.
    pub lon_e8: i64,
    /// Horizontal accuracy in metres.
    pub accuracy_m: i32,
    /// Altitude in metres, or [`crate::record::ALT_UNKNOWN`]. Present on essentially every
    /// WiFi fix (Apple's `location.5`); cell responses have no altitude field at all.
    pub altitude_m: i16,
}

/// A cell tower Apple knows the location of.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CellFix {
    /// Mobile country code.
    pub mcc: i32,
    /// Mobile network code.
    pub mnc: i32,
    /// Cell id as Apple reports it.
    pub cell_id: i64,
    /// TAC or LAC.
    pub area: i32,
    /// Latitude in degrees x 1e8.
    pub lat_e8: i64,
    /// Longitude in degrees x 1e8.
    pub lon_e8: i64,
    /// Horizontal accuracy in metres.
    pub accuracy_m: i32,
}

/// What went wrong with a request. Callers distinguish these because they back off
/// differently: a transport hiccup is worth retrying, a refusal is not.
#[derive(Debug)]
pub enum Error {
    /// The request never completed.
    Transport(String),
    /// A non-200 response.
    Status(u16),
    /// A 200 that did not contain a parseable body.
    Malformed(&'static str),
}

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Error::Transport(e) => write!(f, "transport: {e}"),
            Error::Status(c) => write!(f, "HTTP {c}"),
            Error::Malformed(m) => write!(f, "malformed response: {m}"),
        }
    }
}

impl std::error::Error for Error {}

/// A rate-limited gs-loc client.
pub struct Client {
    agent: ureq::Agent,
    /// Minimum wall time between two requests from this client.
    pub min_interval: Duration,
    last: Option<Instant>,
}

impl Client {
/// Build a client.
    ///
    /// A global timeout is not optional: without one a stalled connection hangs the crawl
    /// indefinitely, and the service does sometimes accept a connection and then never answer.
    pub fn new(min_interval: Duration) -> Client {
        let config = ureq::Agent::config_builder()
            .timeout_global(Some(Duration::from_secs(20)))
            .timeout_connect(Some(Duration::from_secs(10)))
            .build();
        Client { agent: config.into(), min_interval, last: None }
    }

    fn throttle(&mut self) {
        if let Some(last) = self.last {
            let elapsed = last.elapsed();
            if elapsed < self.min_interval {
                std::thread::sleep(self.min_interval - elapsed);
            }
        }
        self.last = Some(Instant::now());
    }

    /// Ask for the locations of `bssids`.
    ///
    /// The response carries far more devices than were sent — Apple volunteers the neighbours
    /// of anything it recognises, which is the whole basis of the crawl. There is no field
    /// asking for that; it is simply what the service does.
    pub fn query_wifi(&mut self, bssids: &[String]) -> Result<Vec<WifiFix>, Error> {
        if bssids.is_empty() {
            return Ok(Vec::new());
        }
        let mut body = Vec::new();
        for b in bssids {
            let mut dev = Vec::new();
            proto::put_bytes(&mut dev, 1, b.as_bytes());
            proto::put_bytes(&mut body, F_WIFI_DEVICES, &dev);
        }
        let resp = self.post(&body)?;
        Ok(parse_wifi(&resp))
    }

    /// Ask for the location of one cell tower.
    ///
    /// `AppleWLoc` carries a single `cell_tower_request` (field 25) and answers in a repeated
    /// `cell_tower_response` (field 22), so unlike WiFi this is one tower per request.
    pub fn query_cell(&mut self, mcc: u32, mnc: u32, cell_id: u32, tac: u32) -> Result<Vec<CellFix>, Error> {
        let mut tower = Vec::new();
        proto::put_int(&mut tower, 1, mcc as i64);
        proto::put_int(&mut tower, 2, mnc as i64);
        proto::put_int(&mut tower, 3, cell_id as i64);
        proto::put_int(&mut tower, 4, tac as i64);
        let mut body = Vec::new();
        proto::put_bytes(&mut body, F_CELL_REQUEST, &tower);

        let resp = self.post(&body)?;
        Ok(parse_cells(&resp))
    }

    fn post(&mut self, payload: &[u8]) -> Result<Vec<u8>, Error> {
        self.throttle();
        let framed = frame(payload);
        let mut resp = self
            .agent
            .post(ENDPOINT)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "*/*")
            .header("Accept-Charset", "utf-8")
            .header("Accept-Language", "en-us")
            .header("User-Agent", "locationd/1753.17 CFNetwork/711.1.12 Darwin/14.0.0")
            .send(&framed[..])
            .map_err(|e| match e {
                ureq::Error::StatusCode(code) => Error::Status(code),
                other => Error::Transport(other.to_string()),
            })?;

        let raw = resp
            .body_mut()
            .with_config()
            .limit(64 * 1024 * 1024)
            .read_to_vec()
            .map_err(|e| Error::Transport(e.to_string()))?;
        if raw.len() <= RESPONSE_HEADER_BYTES {
            return Err(Error::Malformed("shorter than the response header"));
        }
        Ok(raw[RESPONSE_HEADER_BYTES..].to_vec())
    }
}

/// Wrap `payload` in Apple's request envelope.
///
/// Byte-for-byte from `joelkoen/wtfps` (`src/fetch.rs`), which is a working client. The
/// envelope recovered from this app's old Kotlin implementation was **wrong** in three ways
/// and produced an empty response every time:
///
/// * its trailer was eight bytes (`00 00 00 01` then `00 00 00 00`); the real one is **seven**;
/// * it length-prefixed the body with a big-endian `u16`; the real one uses a protobuf
///   **varint**, which for a short body is a single byte — so the two together put two spurious
///   zero bytes in front of every request;
/// * it sent the body once. The real one sends it **twice**, length-delimited and then again.
///
/// The duplicate looks like a mistake and may well be one, but it is what the working client
/// does and this is not the place to find out whether Apple tolerates its absence.
pub fn frame(payload: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(payload.len() * 2 + 64);
    out.extend_from_slice(&[0x00, 0x01, 0x00, 0x05]);
    out.extend_from_slice(b"en_US");
    out.extend_from_slice(&[0x00, 0x13]);
    out.extend_from_slice(b"com.apple.locationd");
    out.extend_from_slice(&[0x00, 0x0a]);
    out.extend_from_slice(OS_VERSION.as_bytes());
    out.extend_from_slice(&[0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00]);
    proto::put_varint(&mut out, payload.len() as u64);
    out.extend_from_slice(payload);
    out.extend_from_slice(payload);
    out
}

/// A location submessage. `None` when the beacon is unknown, which Apple signals by returning
/// -180 degrees in both axes rather than by omitting the field.
///
/// Returns `(lat, lon, accuracy_m, altitude_m)`. Only WiFi carries fields 5/6 (altitude and
/// vertical accuracy); cell locations top out at field 4, so their altitude reads as
/// "not reported" without any special-casing.
fn parse_location(body: &[u8]) -> Option<(i64, i64, i32, i16)> {
    let mut lat = None;
    let mut lon = None;
    let mut acc: i64 = -1;
    let mut alt: i64 = ALT_NOT_REPORTED;
    let mut r = proto::Reader::new(body);
    while let Some((field, v)) = r.next() {
        if let Field::Varint(raw) = v {
            match field {
                1 => lat = Some(raw as i64),
                2 => lon = Some(raw as i64),
                3 => acc = raw as i64,
                5 => alt = raw as i64,
                _ => {}
            }
        }
    }
    let (lat, lon) = (lat?, lon?);
    if lat == NOT_FOUND_E8 || lon == NOT_FOUND_E8 {
        return None;
    }
    // Accuracy is int64 on the wire but only ever a plausible radius in metres; clamp rather
    // than truncate so a hostile or garbled value cannot wrap into something small and
    // confident-looking.
    Some((lat, lon, acc.clamp(-1, i32::MAX as i64) as i32, clamp_altitude(alt)))
}

fn parse_wifi(body: &[u8]) -> Vec<WifiFix> {
    let mut out = Vec::new();
    let mut r = proto::Reader::new(body);
    while let Some((field, v)) = r.next() {
        let Field::Bytes(dev) = v else { continue };
        if field != F_WIFI_DEVICES {
            continue;
        }
        let mut bssid = None;
        let mut loc = None;
        let mut dr = proto::Reader::new(dev);
        while let Some((df, dv)) = dr.next() {
            match (df, dv) {
                (1, Field::Bytes(s)) => bssid = std::str::from_utf8(s).ok(),
                (2, Field::Bytes(b)) => loc = parse_location(b),
                _ => {}
            }
        }
        if let (Some(bssid), Some((lat_e8, lon_e8, accuracy_m, altitude_m))) = (bssid, loc) {
            out.push(WifiFix { bssid: bssid.to_ascii_lowercase(), lat_e8, lon_e8, accuracy_m, altitude_m });
        }
    }
    out
}

fn parse_cells(body: &[u8]) -> Vec<CellFix> {
    let mut out = Vec::new();
    let mut r = proto::Reader::new(body);
    while let Some((field, v)) = r.next() {
        let Field::Bytes(tower) = v else { continue };
        if field != F_CELL_RESPONSE {
            continue;
        }
        let (mut mcc, mut mnc, mut cell_id, mut area) = (None, None, None, None);
        let mut loc = None;
        let mut tr = proto::Reader::new(tower);
        while let Some((tf, tv)) = tr.next() {
            match (tf, tv) {
                (1, Field::Varint(x)) => mcc = Some(x as i64 as i32),
                (2, Field::Varint(x)) => mnc = Some(x as i64 as i32),
                (3, Field::Varint(x)) => cell_id = Some(x as i64),
                (4, Field::Varint(x)) => area = Some(x as i64 as i32),
                (5, Field::Bytes(b)) => loc = parse_location(b),
                _ => {}
            }
        }
        if let (Some(mcc), Some(mnc), Some(cell_id), Some(area), Some((lat_e8, lon_e8, acc, _))) =
            (mcc, mnc, cell_id, area, loc)
        {
            out.push(CellFix { mcc, mnc, cell_id, area, lat_e8, lon_e8, accuracy_m: acc });
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn envelope_matches_wtfps_byte_for_byte() {
        let framed = frame(&[0xAB, 0xCD]);
        let mut expect: Vec<u8> = Vec::new();
        expect.extend_from_slice(&[0x00, 0x01, 0x00, 0x05]);
        expect.extend_from_slice(b"en_US");
        expect.extend_from_slice(&[0x00, 0x13]);
        expect.extend_from_slice(b"com.apple.locationd");
        expect.extend_from_slice(&[0x00, 0x0a]);
        expect.extend_from_slice(b"17.5.21F79");
        expect.extend_from_slice(&[0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00]);
        expect.push(0x02); // varint length
        expect.extend_from_slice(&[0xAB, 0xCD]);
        expect.extend_from_slice(&[0xAB, 0xCD]); // body repeated
        assert_eq!(framed, expect);
    }

    /// The length-prefix is a protobuf varint, so a body of 128 bytes or more takes two bytes
    /// where the old `u16` framing always took two. Getting this wrong shifts everything after
    /// it and the server returns nothing at all.
    #[test]
    fn the_length_prefix_is_a_varint_not_a_u16() {
        let short = frame(&[0u8; 100]);
        let long = frame(&[0u8; 200]);
        // 7-byte trailer ends at this offset.
        let head = 4 + 5 + 2 + 19 + 2 + 10 + 7;
        assert_eq!(short[head], 100, "a short body takes a one-byte varint");
        assert_eq!(short.len(), head + 1 + 200);
        assert_eq!(&long[head..head + 2], &[0xC8, 0x01], "200 is two varint bytes");
        assert_eq!(long.len(), head + 2 + 400);
    }

    #[test]
    fn the_os_version_length_matches_its_hardcoded_prefix() {
        // The 0x00 0x0a before it is literal, so a different string silently corrupts framing.
        assert_eq!(OS_VERSION.len(), 0x0a);
    }

    /// Build the response body the parser expects: `AppleWLoc { wifi_devices }`.
    fn wifi_response(entries: &[(&str, Option<(i64, i64, i64, i64)>)]) -> Vec<u8> {
        let mut body = Vec::new();
        for (bssid, loc) in entries {
            let mut dev = Vec::new();
            proto::put_bytes(&mut dev, 1, bssid.as_bytes());
            if let Some((lat, lon, acc, alt)) = loc {
                let mut l = Vec::new();
                proto::put_int(&mut l, 1, *lat);
                proto::put_int(&mut l, 2, *lon);
                proto::put_int(&mut l, 3, *acc);
                proto::put_int(&mut l, 5, *alt);
                proto::put_bytes(&mut dev, 2, &l);
            }
            proto::put_bytes(&mut body, F_WIFI_DEVICES, &dev);
        }
        body
    }

    #[test]
    fn wifi_responses_decode_and_normalize_case() {
        let body = wifi_response(&[
            ("AA:BB:CC:DD:EE:01", Some((37_77493000, -122_41942000, 35, 12))),
            ("aa:bb:cc:dd:ee:02", Some((51_50735000, -0_12776000, 120, 44))),
        ]);
        let fixes = parse_wifi(&body);
        assert_eq!(fixes.len(), 2);
        assert_eq!(fixes[0].bssid, "aa:bb:cc:dd:ee:01");
        assert_eq!(fixes[0].lat_e8, 37_77493000);
        assert_eq!(fixes[0].accuracy_m, 35);
        assert_eq!(fixes[0].altitude_m, 12);
        assert_eq!(fixes[1].lon_e8, -0_12776000);
        assert_eq!(fixes[1].altitude_m, 44);
    }

    #[test]
    fn altitude_decodes_and_its_absence_is_unknown() {
        // Apple's "not reported" is -1; a missing field 5 reads the same way.
        let body = wifi_response(&[
            ("aa:bb:cc:dd:ee:01", Some((37_00000000, -122_00000000, 30, ALT_NOT_REPORTED))),
            ("aa:bb:cc:dd:ee:02", Some((37_00000000, -122_00000000, 30, -430))),
        ]);
        let fixes = parse_wifi(&body);
        assert_eq!(fixes[0].altitude_m, i16::MIN, "-1 means altitude unknown");
        assert_eq!(fixes[1].altitude_m, -430, "below sea level survives (Dead Sea)");
        assert_eq!(clamp_altitude(i64::MAX), i16::MAX);
        assert_eq!(clamp_altitude(i64::MIN + 1), i16::MIN + 1);
    }

    #[test]
    fn beacons_apple_does_not_know_are_dropped() {
        let body = wifi_response(&[
            // Apple's "not found": -180 degrees in both axes.
            ("aa:bb:cc:dd:ee:01", Some((NOT_FOUND_E8, NOT_FOUND_E8, 500, 10))),
            ("aa:bb:cc:dd:ee:02", None),
            ("aa:bb:cc:dd:ee:03", Some((37_00000000, -122_00000000, 50, 10))),
        ]);
        let fixes = parse_wifi(&body);
        assert_eq!(fixes.len(), 1, "only the one real fix survives");
        assert_eq!(fixes[0].bssid, "aa:bb:cc:dd:ee:03");
    }

    /// A negative accuracy is not the "unknown" signal — the coordinate sentinel is. Treating
    /// it as one, as the old client did, threw away real fixes.
    #[test]
    fn a_missing_accuracy_does_not_discard_a_real_fix() {
        let body = wifi_response(&[("aa:bb:cc:dd:ee:04", Some((37_00000000, -122_00000000, -1, 10)))]);
        let fixes = parse_wifi(&body);
        assert_eq!(fixes.len(), 1);
        assert_eq!(fixes[0].accuracy_m, -1, "reported as unknown, not dropped");
    }

    #[test]
    fn cell_responses_decode() {
        let mut body = Vec::new();
        let mut tower = Vec::new();
        proto::put_int(&mut tower, 1, 310);
        proto::put_int(&mut tower, 2, 260);
        proto::put_int(&mut tower, 3, 123456789);
        proto::put_int(&mut tower, 4, 4242);
        let mut l = Vec::new();
        proto::put_int(&mut l, 1, 40_71278000);
        proto::put_int(&mut l, 2, -74_00594000);
        proto::put_int(&mut l, 3, 900);
        proto::put_bytes(&mut tower, 5, &l);
        proto::put_bytes(&mut body, F_CELL_RESPONSE, &tower);

        let fixes = parse_cells(&body);
        assert_eq!(fixes.len(), 1);
        assert_eq!(fixes[0].mcc, 310);
        assert_eq!(fixes[0].cell_id, 123456789);
        assert_eq!(fixes[0].lat_e8, 40_71278000);
        assert_eq!(fixes[0].accuracy_m, 900);
    }

    /// Cell towers answer in field 22, not field 1. Reading the wrong field returns nothing
    /// while looking like an empty database.
    #[test]
    fn a_cell_response_in_the_old_field_number_is_ignored() {
        let mut body = Vec::new();
        let mut tower = Vec::new();
        proto::put_int(&mut tower, 1, 310);
        proto::put_bytes(&mut body, 1, &tower);
        assert!(parse_cells(&body).is_empty());
    }

    #[test]
    fn a_truncated_response_yields_what_decoded_rather_than_failing() {
        let body = wifi_response(&[
            ("aa:bb:cc:dd:ee:01", Some((37_00000000, -122_00000000, 30, 10))),
            ("aa:bb:cc:dd:ee:02", Some((38_00000000, -121_00000000, 40, 10))),
        ]);
        for cut in 1..body.len() {
            let fixes = parse_wifi(&body[..cut]);
            assert!(fixes.len() <= 2);
        }
        assert_eq!(parse_wifi(&body).len(), 2);
    }
}
