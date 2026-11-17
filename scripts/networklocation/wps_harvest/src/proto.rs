//! The slice of protobuf the gs-loc protocol uses, hand-rolled.
//!
//! Four message types with a dozen scalar fields between them does not justify a codegen
//! dependency, and the same reasoning produced `proto.rs` in `scripts/maps/osm_ingest`. The
//! schema this implements is `reference/apple_wps.proto`.
//!
//! Only what the protocol needs: varints, length-delimited fields, and enough of the wire
//! format to skip everything else. Signed fields are plain `int32`/`int64`, not zigzag, so a
//! negative value arrives as a ten-byte varint and is reinterpreted rather than decoded
//! specially.

/// Wire type 0.
pub const WIRE_VARINT: u8 = 0;
/// Wire type 2.
pub const WIRE_LEN: u8 = 2;

/// Append a base-128 varint.
pub fn put_varint(out: &mut Vec<u8>, mut v: u64) {
    loop {
        let byte = (v & 0x7F) as u8;
        v >>= 7;
        if v == 0 {
            out.push(byte);
            return;
        }
        out.push(byte | 0x80);
    }
}

/// Append a tag (field number + wire type).
pub fn put_tag(out: &mut Vec<u8>, field: u32, wire: u8) {
    put_varint(out, ((field as u64) << 3) | wire as u64);
}

/// Append a varint field. `int32`/`int64` sign-extend to ten bytes, as proto2 requires.
pub fn put_int(out: &mut Vec<u8>, field: u32, v: i64) {
    put_tag(out, field, WIRE_VARINT);
    put_varint(out, v as u64);
}

/// Append a length-delimited field.
pub fn put_bytes(out: &mut Vec<u8>, field: u32, body: &[u8]) {
    put_tag(out, field, WIRE_LEN);
    put_varint(out, body.len() as u64);
    out.extend_from_slice(body);
}

/// One field of a message, as it appears on the wire.
#[derive(Debug, Clone, Copy)]
pub enum Field<'a> {
    /// Wire type 0.
    Varint(u64),
    /// Wire type 2: a nested message, string or byte string.
    Bytes(&'a [u8]),
    /// Wire types 1 and 5, which this schema does not use but must still be skipped over.
    Fixed(u64),
}

/// Cursor over a serialized message.
pub struct Reader<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    /// Start reading `buf`.
    pub fn new(buf: &'a [u8]) -> Reader<'a> {
        Reader { buf, pos: 0 }
    }

    fn varint(&mut self) -> Option<u64> {
        let mut v = 0u64;
        let mut shift = 0u32;
        loop {
            let b = *self.buf.get(self.pos)?;
            self.pos += 1;
            if shift < 64 {
                v |= ((b & 0x7F) as u64) << shift;
            }
            if b & 0x80 == 0 {
                return Some(v);
            }
            shift += 7;
            // A varint is at most ten bytes; anything longer is corrupt, not merely large.
            if shift > 63 && b & 0x80 != 0 && shift > 70 {
                return None;
            }
        }
    }

}

impl<'a> Iterator for Reader<'a> {
    type Item = (u32, Field<'a>);

    /// Next `(field number, value)`, or `None` at the end of the message or on malformed
    /// input. The two are not distinguished: a truncated response and a complete one are
    /// handled the same way, by using whatever was decoded before the cut.
    fn next(&mut self) -> Option<Self::Item> {
        if self.pos >= self.buf.len() {
            return None;
        }
        let tag = self.varint()?;
        let field = (tag >> 3) as u32;
        match (tag & 7) as u8 {
            0 => Some((field, Field::Varint(self.varint()?))),
            1 => {
                let end = self.pos.checked_add(8)?;
                let s = self.buf.get(self.pos..end)?;
                self.pos = end;
                let mut a = [0u8; 8];
                a.copy_from_slice(s);
                Some((field, Field::Fixed(u64::from_le_bytes(a))))
            }
            2 => {
                let len = self.varint()? as usize;
                let end = self.pos.checked_add(len)?;
                let s = self.buf.get(self.pos..end)?;
                self.pos = end;
                Some((field, Field::Bytes(s)))
            }
            5 => {
                let end = self.pos.checked_add(4)?;
                let s = self.buf.get(self.pos..end)?;
                self.pos = end;
                let mut a = [0u8; 4];
                a.copy_from_slice(s);
                Some((field, Field::Fixed(u32::from_le_bytes(a) as u64)))
            }
            _ => None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn varints_round_trip_including_the_ten_byte_negative_form() {
        for v in [0i64, 1, 127, 128, 300, -1, -2, i64::MIN, i64::MAX] {
            let mut out = Vec::new();
            put_int(&mut out, 3, v);
            let mut r = Reader::new(&out);
            match r.next() {
                Some((3, Field::Varint(raw))) => assert_eq!(raw as i64, v, "{v}"),
                other => panic!("unexpected {other:?} for {v}"),
            }
            assert!(r.next().is_none());
        }
    }

    #[test]
    fn negative_ints_use_the_full_ten_bytes() {
        let mut out = Vec::new();
        put_int(&mut out, 1, -1);
        // one tag byte + ten payload bytes
        assert_eq!(out.len(), 11);
    }

    #[test]
    fn nested_messages_and_strings_round_trip() {
        let mut inner = Vec::new();
        put_bytes(&mut inner, 1, b"aa:bb:cc:dd:ee:ff");
        let mut outer = Vec::new();
        put_bytes(&mut outer, 2, &inner);
        put_int(&mut outer, 3, 100);

        let mut r = Reader::new(&outer);
        let (f, v) = r.next().unwrap();
        assert_eq!(f, 2);
        let Field::Bytes(body) = v else { panic!("expected a nested message") };
        let mut ir = Reader::new(body);
        let (bf, bv) = ir.next().unwrap();
        assert_eq!(bf, 1);
        let Field::Bytes(s) = bv else { panic!("expected a string") };
        assert_eq!(s, b"aa:bb:cc:dd:ee:ff");

        assert!(matches!(r.next(), Some((3, Field::Varint(100)))));
        assert!(r.next().is_none());
    }

    #[test]
    fn unknown_wire_types_and_fields_are_skipped_not_fatal() {
        let mut out = Vec::new();
        put_tag(&mut out, 9, 5); // fixed32, a field this schema never sends
        out.extend_from_slice(&7u32.to_le_bytes());
        put_int(&mut out, 3, 42);

        let mut r = Reader::new(&out);
        assert!(matches!(r.next(), Some((9, Field::Fixed(7)))));
        assert!(matches!(r.next(), Some((3, Field::Varint(42)))));
    }

    #[test]
    fn a_truncated_message_stops_rather_than_panicking() {
        let mut out = Vec::new();
        put_bytes(&mut out, 2, &[1, 2, 3, 4, 5, 6]);
        for cut in 1..out.len() {
            let mut r = Reader::new(&out[..cut]);
            // Must terminate and must not index out of bounds.
            while r.next().is_some() {}
        }
    }
}
