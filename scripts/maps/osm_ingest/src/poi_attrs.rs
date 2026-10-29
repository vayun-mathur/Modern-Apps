//! `poi_attrs.bin` — the attribute sidecar for `poi_index.bin`.
//!
//! `poi_index.bin`'s 14-byte record is fully packed, and its width is asserted in
//! three places (the writer, its test, and the README's `record_count = filesize /
//! 14`). So the OSM tag values that a place sheet wants — opening hours, phone,
//! website, address — go in a second file rather than a wider record.
//!
//! ## Indexed by record ordinal
//!
//! `attr_off[i]` describes the `i`th record of `poi_index.bin`. Not `osm_id`, which
//! is not in `poi_index.bin` at all, and not a coordinate: both files are written in
//! one pass over the same Morton-sorted POI vector, so ordinal alignment is free and
//! no join key has to be added to either format.
//!
//! ## Layout
//!
//! ```text
//! 0..4                       magic b"MAPA"
//! 4                          u8 version
//! 5..8                       reserved, zero (keeps the offset array 4-aligned)
//! 8..12                      u32 record_count
//! 12..12+4*record_count      u32 attr_off[], a byte offset into the blob
//!                            below, or NO_ATTRS for a POI with no attributes
//! then                       the blob
//! ```
//!
//! A blob record is a `u16` body length followed by that many bytes of
//! `u8 key, u16 value_len, value` triples, ascending by key. The length prefixes are
//! what let a reader built against version 1 skip a key added in version 2 instead
//! of losing the rest of the record — which is why the version byte does not gate
//! reading.
//!
//! Records are deduplicated whole, the way [`crate::names::NamePool`] dedupes names.
//! Chains repeat their whole attribute set, and `Mo-Su 00:00-24:00` is one of the
//! most common strings in OSM.

use std::collections::HashMap;
use std::io::{self, Write};

pub const MAGIC: [u8; 4] = *b"MAPA";
pub const VERSION: u8 = 1;
/// magic + version + reserved + record_count.
pub const HEADER_BYTES: usize = 12;
/// `attr_off` value for a POI that carries none of the attributes we read.
pub const NO_ATTRS: u32 = u32::MAX;

/// Attribute keys.
///
/// APPEND ONLY, and never renumber — exactly like the POI type enum. A code is
/// baked into every published sidecar, so reusing one relabels data already on
/// devices, and a device that has not updated has no way to tell.
pub const KEY_OPENING_HOURS: u8 = 1;
pub const KEY_PHONE: u8 = 2;
pub const KEY_WEBSITE: u8 = 3;
pub const KEY_HOUSENUMBER: u8 = 4;
pub const KEY_STREET: u8 = 5;
pub const KEY_CITY: u8 = 6;
pub const KEY_POSTCODE: u8 = 7;
pub const KEY_CUISINE: u8 = 8;
pub const KEY_WHEELCHAIR: u8 = 9;
/// Review data merged in from an external table at build time (see
/// [`crate::poi_reviews`]), never from OSM tags. ASCII decimal (`"4.3"`), ASCII
/// integer, and UTF-8 text.
pub const KEY_RATING: u8 = 10;
pub const KEY_REVIEW_COUNT: u8 = 11;
pub const KEY_REVIEW_SUMMARY: u8 = 12;

/// Which OSM tags each key is read from, in the order they are encoded.
///
/// Earlier tags win, so `phone` beats `contact:phone`. Kept deliberately short:
/// every key here costs bytes 22.6 million times over at planet scale, so a tag
/// earns its place by being something the place sheet actually shows.
pub const KEY_SOURCES: [(u8, &[&str]); 9] = [
    (KEY_OPENING_HOURS, &["opening_hours"]),
    (KEY_PHONE, &["phone", "contact:phone"]),
    (KEY_WEBSITE, &["website", "contact:website"]),
    (KEY_HOUSENUMBER, &["addr:housenumber"]),
    (KEY_STREET, &["addr:street"]),
    (KEY_CITY, &["addr:city"]),
    (KEY_POSTCODE, &["addr:postcode"]),
    (KEY_CUISINE, &["cuisine"]),
    (KEY_WHEELCHAIR, &["wheelchair"]),
];

/// Largest record body we can length-prefix.
///
/// Enforced by [`encode`] rather than checked by the writer, so the `u16` prefix in
/// [`AttrPool::push`] cannot fail: a single pathological OSM element must not abort a
/// planet build.
const MAX_BODY_BYTES: usize = u16::MAX as usize;

/// Encode one POI's attribute record body, empty when it has none.
///
/// `get` is called once per candidate tag in [`KEY_SOURCES`] order, so the output
/// depends only on the input tags — never on a hash map's iteration order, which
/// `two_runs_are_byte_identical` would catch.
pub fn encode<'a>(get: impl Fn(&str) -> Option<&'a [u8]>) -> Vec<u8> {
    let mut out = Vec::new();
    for (key, sources) in KEY_SOURCES {
        // The emptiness test goes inside the search, not after it: `phone=""` beside
        // a real `contact:phone` must fall through to the alias rather than
        // suppressing the key entirely.
        let Some(value) = sources
            .iter()
            .filter_map(|tag| get(tag))
            .find(|v| !v.is_empty())
        else {
            continue;
        };
        // A value too long to length-prefix is dropped rather than truncated: half a
        // URL or half a phone number looks valid and is not.
        let Ok(len) = u16::try_from(value.len()) else { continue };
        if out.len() + 3 + value.len() > MAX_BODY_BYTES {
            continue;
        }
        out.push(key);
        out.extend_from_slice(&len.to_le_bytes());
        out.extend_from_slice(value);
    }
    out
}

/// Append `(key, value)` fields after an [`encode`]d body.
///
/// `extra` must be ascending by key and every key must sort after the tag keys, so
/// the record stays ascending. Empty values are skipped, and a value that would
/// pass the body ceiling is dropped whole, under the same rules as [`encode`].
pub fn append(mut body: Vec<u8>, extra: &[(u8, &[u8])]) -> Vec<u8> {
    for &(key, value) in extra {
        debug_assert!(key > KEY_WHEELCHAIR, "extra keys must sort after the tag keys");
        if value.is_empty() {
            continue;
        }
        let Ok(len) = u16::try_from(value.len()) else { continue };
        if body.len() + 3 + value.len() > MAX_BODY_BYTES {
            continue;
        }
        body.push(key);
        body.extend_from_slice(&len.to_le_bytes());
        body.extend_from_slice(value);
    }
    body
}

/// Deduplicating collector for the sidecar.
///
/// Holds the blob in memory, which is affordable only because `poi_build` already
/// holds every POI — name and encoded attributes included — in memory to sort them.
#[derive(Default)]
pub struct AttrPool {
    offsets: HashMap<Vec<u8>, u32>,
    blob: Vec<u8>,
    slots: Vec<u32>,
}

impl AttrPool {
    pub fn new() -> Self {
        Self::default()
    }

    /// Record the next POI's attributes. Call order IS the record ordinal.
    pub fn push(&mut self, attrs: &[u8]) -> io::Result<()> {
        if attrs.is_empty() {
            self.slots.push(NO_ATTRS);
            return Ok(());
        }
        if let Some(off) = self.offsets.get(attrs) {
            self.slots.push(*off);
            return Ok(());
        }
        let off = u32::try_from(self.blob.len())
            .ok()
            .filter(|o| *o != NO_ATTRS)
            .ok_or_else(|| {
                io::Error::new(
                    io::ErrorKind::InvalidData,
                    format!("attribute blob would pass {NO_ATTRS} bytes"),
                )
            })?;
        // [`MAX_BODY_BYTES`] is enforced by `encode`, so this cannot fail for a body
        // this crate produced. Reported rather than asserted because a wrong answer
        // here would be a silently corrupt sidecar.
        let len = u16::try_from(attrs.len()).map_err(|_| {
            io::Error::new(
                io::ErrorKind::InvalidData,
                format!("attribute record of {} bytes is too long", attrs.len()),
            )
        })?;
        self.blob.extend_from_slice(&len.to_le_bytes());
        self.blob.extend_from_slice(attrs);
        self.offsets.insert(attrs.to_vec(), off);
        self.slots.push(off);
        Ok(())
    }

    pub fn write(&self, out: &mut impl Write) -> io::Result<()> {
        out.write_all(&MAGIC)?;
        out.write_all(&[VERSION, 0, 0, 0])?;
        let count = u32::try_from(self.slots.len()).map_err(|_| {
            io::Error::new(io::ErrorKind::InvalidData, "more than u32::MAX POI records")
        })?;
        out.write_all(&count.to_le_bytes())?;
        for off in &self.slots {
            out.write_all(&off.to_le_bytes())?;
        }
        out.write_all(&self.blob)
    }

    /// POIs that carry at least one attribute.
    pub fn with_attrs(&self) -> usize {
        self.slots.iter().filter(|o| **o != NO_ATTRS).count()
    }

    /// Distinct attribute records stored, before the offset array.
    pub fn unique_count(&self) -> usize {
        self.offsets.len()
    }

    pub fn blob_len(&self) -> usize {
        self.blob.len()
    }

    pub fn total_len(&self) -> usize {
        HEADER_BYTES + 4 * self.slots.len() + self.blob.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Decode a record body back into `(key, value)` pairs, the way a reader must.
    fn fields(body: &[u8]) -> Vec<(u8, String)> {
        let mut out = Vec::new();
        let mut i = 0;
        while i + 3 <= body.len() {
            let key = body[i];
            let len = u16::from_le_bytes(body[i + 1..i + 3].try_into().unwrap()) as usize;
            out.push((key, String::from_utf8(body[i + 3..i + 3 + len].to_vec()).unwrap()));
            i += 3 + len;
        }
        out
    }

    fn tags<'a>(pairs: &'a [(&'a str, &'a str)]) -> impl Fn(&str) -> Option<&'a [u8]> + 'a {
        move |key: &str| {
            pairs
                .iter()
                .find(|(k, _)| *k == key)
                .map(|(_, v)| v.as_bytes())
        }
    }

    #[test]
    fn a_poi_with_no_recognised_tags_encodes_to_nothing() {
        assert!(encode(tags(&[("amenity", "cafe"), ("name", "Corner Cafe")])).is_empty());
    }

    #[test]
    fn fields_come_out_in_ascending_key_order_whatever_the_tag_order() {
        let body = encode(tags(&[
            ("wheelchair", "yes"),
            ("addr:street", "Market St"),
            ("opening_hours", "24/7"),
        ]));
        assert_eq!(
            fields(&body),
            vec![
                (KEY_OPENING_HOURS, "24/7".into()),
                (KEY_STREET, "Market St".into()),
                (KEY_WHEELCHAIR, "yes".into()),
            ]
        );
    }

    #[test]
    fn the_preferred_tag_wins_over_its_contact_alias() {
        let body = encode(tags(&[
            ("contact:phone", "+1-555-0100"),
            ("phone", "+1-555-0199"),
            ("contact:website", "https://alias.example"),
        ]));
        assert_eq!(
            fields(&body),
            vec![
                (KEY_PHONE, "+1-555-0199".into()),
                (KEY_WEBSITE, "https://alias.example".into()),
            ],
            "phone from the preferred tag, website from the only one present"
        );
    }

    #[test]
    fn an_empty_tag_value_is_not_an_attribute() {
        assert!(encode(tags(&[("phone", ""), ("website", "")])).is_empty());
    }

    #[test]
    fn identical_records_are_stored_once_and_share_an_offset() {
        let chain = encode(tags(&[("opening_hours", "Mo-Su 00:00-24:00")]));
        let other = encode(tags(&[("opening_hours", "Mo-Fr 09:00-17:00")]));

        let mut pool = AttrPool::new();
        pool.push(&chain).unwrap();
        pool.push(&[]).unwrap();
        pool.push(&other).unwrap();
        pool.push(&chain).unwrap();

        assert_eq!(pool.slots, vec![0, NO_ATTRS, 2 + chain.len() as u32, 0]);
        assert_eq!(pool.unique_count(), 2);
        assert_eq!(pool.with_attrs(), 3);
    }

    #[test]
    fn the_file_round_trips_through_its_own_layout() {
        let hours = encode(tags(&[("opening_hours", "24/7"), ("phone", "+1-555-0100")]));
        let mut pool = AttrPool::new();
        pool.push(&[]).unwrap();
        pool.push(&hours).unwrap();

        let mut bytes = Vec::new();
        pool.write(&mut bytes).unwrap();
        assert_eq!(bytes.len(), pool.total_len());

        assert_eq!(&bytes[0..4], &MAGIC);
        assert_eq!(bytes[4], VERSION);
        assert_eq!(&bytes[5..8], &[0, 0, 0], "reserved bytes are zero");
        assert_eq!(u32::from_le_bytes(bytes[8..12].try_into().unwrap()), 2);

        let off = |i: usize| {
            let at = HEADER_BYTES + 4 * i;
            u32::from_le_bytes(bytes[at..at + 4].try_into().unwrap())
        };
        assert_eq!(off(0), NO_ATTRS, "the first POI has no attributes");

        let blob = &bytes[HEADER_BYTES + 8..];
        let at = off(1) as usize;
        let len = u16::from_le_bytes(blob[at..at + 2].try_into().unwrap()) as usize;
        assert_eq!(
            fields(&blob[at + 2..at + 2 + len]),
            vec![
                (KEY_OPENING_HOURS, "24/7".into()),
                (KEY_PHONE, "+1-555-0100".into()),
            ]
        );
    }

    /// The length prefixes exist so an older reader can step over a key it has
    /// never heard of instead of losing everything after it.
    #[test]
    fn an_unknown_key_is_skippable_without_losing_the_rest() {
        let mut body = encode(tags(&[("opening_hours", "24/7")]));
        body.push(200); // a key from some future version
        body.extend_from_slice(&3u16.to_le_bytes());
        body.extend_from_slice(b"abc");
        body.extend_from_slice(&encode(tags(&[("wheelchair", "yes")])));

        let decoded = fields(&body);
        assert_eq!(decoded.len(), 3);
        assert_eq!(decoded[2], (KEY_WHEELCHAIR, "yes".into()));
    }

    #[test]
    fn an_over_long_value_is_dropped_rather_than_truncated() {
        let long = "x".repeat(70_000);
        let body = encode(tags(&[("website", long.as_str()), ("wheelchair", "yes")]));
        assert_eq!(
            fields(&body),
            vec![(KEY_WHEELCHAIR, "yes".into())],
            "the long value is gone, the short one survives"
        );
    }

    /// The `u16` length prefix in [`AttrPool::push`] is only infallible because
    /// `encode` keeps the body under the ceiling, so one pathological element cannot
    /// abort a planet build.
    #[test]
    fn a_body_that_would_pass_the_ceiling_drops_its_last_fields() {
        let big = "x".repeat(60_000);
        let body = encode(tags(&[
            ("opening_hours", big.as_str()),
            ("phone", big.as_str()),
            ("wheelchair", "yes"),
        ]));
        assert!(body.len() <= MAX_BODY_BYTES);
        assert_eq!(
            fields(&body),
            vec![
                (KEY_OPENING_HOURS, big.clone()),
                // `phone` would have pushed the body over, so it and nothing after it
                // is written -- but `wheelchair` still fits after the skip.
                (KEY_WHEELCHAIR, "yes".into()),
            ]
        );

        let mut pool = AttrPool::new();
        pool.push(&body).expect("a body encode produced is always writable");
    }

    /// An empty preferred tag must not shadow a populated alias.
    #[test]
    fn an_empty_preferred_tag_falls_through_to_its_alias() {
        let body = encode(tags(&[("phone", ""), ("contact:phone", "+1-555-0100")]));
        assert_eq!(fields(&body), vec![(KEY_PHONE, "+1-555-0100".into())]);
    }

    #[test]
    fn review_fields_are_appended_after_the_tag_fields() {
        let body = append(
            encode(tags(&[("opening_hours", "24/7"), ("cuisine", "pizza")])),
            &[(KEY_RATING, b"4.3"), (KEY_REVIEW_COUNT, b"1234"), (KEY_REVIEW_SUMMARY, "Crisp crust, quick service.".as_bytes())],
        );
        assert_eq!(
            fields(&body),
            vec![
                (KEY_OPENING_HOURS, "24/7".into()),
                (KEY_CUISINE, "pizza".into()),
                (KEY_RATING, "4.3".into()),
                (KEY_REVIEW_COUNT, "1234".into()),
                (KEY_REVIEW_SUMMARY, "Crisp crust, quick service.".into()),
            ]
        );
    }

    #[test]
    fn review_fields_alone_make_a_record_and_empty_ones_are_skipped() {
        let body = append(Vec::new(), &[(KEY_RATING, b"4.0"), (KEY_REVIEW_COUNT, b""), (KEY_REVIEW_SUMMARY, b"")]);
        assert_eq!(fields(&body), vec![(KEY_RATING, "4.0".into())]);
        assert!(append(Vec::new(), &[(KEY_REVIEW_SUMMARY, b"")]).is_empty());
    }

    #[test]
    fn an_appended_value_past_the_ceiling_is_dropped_whole() {
        let big = "x".repeat(65_000);
        let body = append(
            encode(tags(&[("opening_hours", big.as_str())])),
            &[(KEY_RATING, b"4.5"), (KEY_REVIEW_SUMMARY, "y".repeat(1_000).as_bytes())],
        );
        assert!(body.len() <= MAX_BODY_BYTES);
        assert_eq!(fields(&body).last().unwrap(), &(KEY_RATING, "4.5".into()));
    }
}
