impl<'a> Decoder<'a> {
    fn byte(&mut self) -> Result<u8> {
        let b = *self.data.get(self.index).ok_or(Error::Eos)?;
        self.index += 1;
        Ok(b)
    }

    fn int16(&mut self) -> Result<usize> {
        Ok(((self.byte()? as usize) << 8) | self.byte()? as usize)
    }

    fn int20(&mut self) -> Result<usize> {
        Ok((((self.byte()? & 0x0F) as usize) << 16)
            | ((self.byte()? as usize) << 8)
            | self.byte()? as usize)
    }

    fn int32(&mut self) -> Result<usize> {
        Ok(((self.byte()? as usize) << 24)
            | ((self.byte()? as usize) << 16)
            | ((self.byte()? as usize) << 8)
            | self.byte()? as usize)
    }

    fn raw(&mut self, length: usize) -> Result<Vec<u8>> {
        let end = self.index.checked_add(length).ok_or(Error::Eos)?;
        if end > self.data.len() {
            return Err(Error::Eos);
        }
        let out = self.data[self.index..end].to_vec();
        self.index = end;
        Ok(out)
    }

    fn read_packed(&mut self, tag: u8) -> Result<String> {
        let start = self.byte()?;
        let mut out = String::new();
        for _ in 0..(start & 127) {
            let b = self.byte()?;
            out.push(unpack_byte(tag, (b >> 4) & 0x0F)?);
            out.push(unpack_byte(tag, b & 0x0F)?);
        }
        // The high bit means the final nibble was padding.
        if (start >> 7) != 0 {
            out.pop();
        }
        Ok(out)
    }

    fn read_list_size(&mut self, tag: u8) -> Result<usize> {
        match tag {
            LIST_EMPTY => Ok(0),
            LIST_8 => Ok(self.byte()? as usize),
            LIST_16 => self.int16(),
            other => Err(Error::Invalid(format!("unknown list tag {other}"))),
        }
    }

    fn read(&mut self, as_string: bool) -> Result<Value> {
        let tag = self.byte()?;
        match tag {
            LIST_EMPTY => Ok(Value::Empty),
            LIST_8 | LIST_16 => {
                let size = self.read_list_size(tag)?;
                let mut nodes = Vec::with_capacity(size.min(1024));
                for _ in 0..size {
                    nodes.push(self.read_node()?);
                }
                Ok(Value::List(nodes))
            }
            BINARY_8 | BINARY_20 | BINARY_32 => {
                let size = match tag {
                    BINARY_8 => self.byte()? as usize,
                    BINARY_20 => self.int20()?,
                    _ => self.int32()?,
                };
                let bytes = self.raw(size)?;
                Ok(if as_string {
                    Value::Text(String::from_utf8_lossy(&bytes).into_owned())
                } else {
                    Value::Bytes(bytes)
                })
            }
            DICTIONARY_0..=DICTIONARY_3 => {
                let index = self.byte()? as usize;
                let dict = (tag - DICTIONARY_0) as usize;
                Ok(Value::Text(
                    DOUBLE_BYTE_TOKENS
                        .get(dict)
                        .and_then(|d| d.get(index))
                        .unwrap_or(&"")
                        .to_string(),
                ))
            }
            AD_JID => {
                let agent = self.byte()?;
                let device = self.byte()?;
                let user = self.read(true)?.into_text().unwrap_or_default();
                Ok(Value::Text(format!("{user}.{agent}:{device}@s.whatsapp.net")))
            }
            FB_JID => {
                let user = self.read(true)?.into_text().unwrap_or_default();
                let device = self.int16()?;
                let server = self.read(true)?.into_text().unwrap_or_else(|| "msgr".into());
                Ok(Value::Text(format!("{user}:{device}@{server}")))
            }
            INTEROP_JID => {
                let user = self.read(true)?.into_text().unwrap_or_default();
                let device = self.int16()?;
                let integrator = self.int16()?;
                let server = self.read(true)?.into_text().unwrap_or_default();
                Ok(Value::Text(format!("{user}:{device}:{integrator}@{server}")))
            }
            JID_PAIR => {
                let user = self.read(true)?.into_text();
                let server = self
                    .read(true)?
                    .into_text()
                    .ok_or_else(|| Error::Invalid("JID missing server".into()))?;
                Ok(Value::Text(match user {
                    Some(u) => format!("{u}@{server}"),
                    None => format!("@{server}"),
                }))
            }
            NIBBLE_8 | HEX_8 => Ok(Value::Text(self.read_packed(tag)?)),
            other => {
                let index = other as usize;
                if index >= 1 && index < SINGLE_BYTE_TOKENS.len() {
                    Ok(Value::Text(SINGLE_BYTE_TOKENS[index].to_string()))
                } else {
                    Err(Error::Invalid(format!(
                        "invalid token {other} at position {}",
                        self.index
                    )))
                }
            }
        }
    }

    fn read_node(&mut self) -> Result<Node> {
        let list_tag = self.byte()?;
        let list_size = self.read_list_size(list_tag)?;
        // The encoder emits LIST_8,0 for tag "0", the empty-node marker.
        // A normal node always has list_size >= 1 (its tag), so this size
        // can only be that marker; accept it as Node::new("0") so encode
        // and decode round-trip. LIST_EMPTY ([0]) stays invalid here.
        if list_tag == LIST_8 && list_size == 0 {
            return Ok(Node::new("0"));
        }
        let tag = self
            .read(true)?
            .into_text()
            .ok_or_else(|| Error::Invalid("node tag is not a string".into()))?;
        if list_size == 0 || tag.is_empty() {
            return Err(Error::Invalid("invalid node".into()));
        }

        let attr_count = (list_size - 1) >> 1;
        let mut attrs = BTreeMap::new();
        for _ in 0..attr_count {
            let Some(key) = self.read(true)?.into_text() else { continue };
            let value = self.read(true)?.into_text().unwrap_or_default();
            attrs.insert(key, value);
        }

        let mut content = Vec::new();
        let mut data = None;
        // An even list size means a content slot follows the tag and attribute pairs.
        if list_size % 2 == 0 {
            match self.read(false)? {
                Value::List(nodes) => content = nodes,
                Value::Bytes(b) => data = Some(b),
                Value::Text(s) => data = Some(s.into_bytes()),
                Value::Empty => {}
            }
        }

        Ok(Node { tag, attrs, content, data })
    }
}

fn unpack_byte(tag: u8, value: u8) -> Result<char> {
    match tag {
        NIBBLE_8 => match value {
            0..=9 => Ok((b'0' + value) as char),
            10 => Ok('-'),
            11 => Ok('.'),
            15 => Ok('\0'),
            other => Err(Error::Invalid(format!("invalid nibble {other}"))),
        },
        HEX_8 => match value {
            0..=9 => Ok((b'0' + value) as char),
            10..=15 => Ok((b'A' + value - 10) as char),
            other => Err(Error::Invalid(format!("invalid hex {other}"))),
        },
        other => Err(Error::Invalid(format!("unknown packed tag {other}"))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Encodes then decodes, dropping the frame flag byte the encoder prepends.
    fn round_trip(node: &Node) -> Node {
        let encoded = encode_node(node);
        assert_eq!(encoded[0], 0, "frame flag byte must lead the encoding");
        decode_node(&encoded[1..]).expect("decode")
    }

    #[test]
    fn token_tables_have_the_expected_shape() {
        assert_eq!(SINGLE_BYTE_TOKENS.len(), 236);
        assert_eq!(SINGLE_BYTE_TOKENS[0], "", "slot 0 is unused");
        assert_eq!(SINGLE_BYTE_TOKENS[3], "s.whatsapp.net");
        for dict in DOUBLE_BYTE_TOKENS.iter() {
            assert_eq!(dict.len(), 256);
        }
    }

    #[test]
    fn simple_node_round_trips() {
        let node = Node::new("iq").attr("type", "get").attr("id", "1234");
        assert_eq!(round_trip(&node), node);
    }

    #[test]
    fn single_byte_tokens_are_used_for_known_strings() {
        // "iq" is in the single-byte table, so the tag costs one byte.
        let encoded = encode_node(&Node::new("iq"));
        let iq = index_of_single_token("iq").expect("iq is a known token");
        assert!(encoded.contains(&iq));
    }

    #[test]
    fn unknown_strings_fall_back_to_raw() {
        let node = Node::new("iq").attr("zzz_unknown_key", "zzz_unknown_value");
        assert_eq!(round_trip(&node), node);
    }

    #[test]
    fn nested_children_round_trip() {
        let mut parent = Node::new("message");
        parent.attrs.insert("to".into(), "user@s.whatsapp.net".into());
        parent.content.push(Node::new("enc").attr("v", "2"));
        parent.content.push(Node::new("participant"));
        assert_eq!(round_trip(&parent), parent);
    }

    #[test]
    fn binary_payloads_round_trip_at_each_length_class() {
        for size in [0usize, 1, 255, 256, 70_000] {
            let mut node = Node::new("enc");
            node.data = Some(vec![0xAB; size]);
            let decoded = round_trip(&node);
            assert_eq!(decoded.data.as_ref().map(Vec::len), Some(size), "size {size}");
        }
    }

    #[test]
    fn jid_attributes_use_the_structured_form() {
        // A raw-string JID gets the stanza rejected by the server, so check the tag byte.
        let node = Node::new("iq").attr("to", "15551234567@s.whatsapp.net");
        let encoded = encode_node(&node);
        assert!(encoded.contains(&JID_PAIR), "plain JIDs use JID_PAIR");
        assert_eq!(round_trip(&node), node);
    }

    #[test]
    fn device_jids_use_ad_jid() {
        let node = Node::new("iq").attr("to", "15551234567.0:3@s.whatsapp.net");
        let encoded = encode_node(&node);
        assert!(encoded.contains(&AD_JID), "agent/device JIDs use AD_JID");
        assert_eq!(round_trip(&node), node);
    }

    #[test]
    fn group_jids_stay_jid_pair() {
        // Only s.whatsapp.net gets the AD form; g.us must not.
        let node = Node::new("iq").attr("to", "123-456@g.us");
        let encoded = encode_node(&node);
        assert!(encoded.contains(&JID_PAIR));
        assert!(!encoded.contains(&AD_JID));
        assert_eq!(round_trip(&node), node);
    }

    #[test]
    fn digit_strings_are_nibble_packed_and_survive_odd_lengths() {
        for value in ["1234567890", "123", "1-2.3", "9"] {
            let node = Node::new("iq").attr("zzz_raw_key", value);
            let decoded = round_trip(&node);
            assert_eq!(decoded.attrs["zzz_raw_key"], value, "value {value}");
        }
        let encoded = encode_node(&Node::new("iq").attr("zzz_raw_key", "1234567890"));
        assert!(encoded.contains(&NIBBLE_8));
    }

    #[test]
    fn lowercase_hex_is_not_packed() {
        // Packing it would decode back upper-cased and change the value.
        let node = Node::new("iq").attr("zzz_raw_key", "abcdef");
        let encoded = encode_node(&node);
        assert!(!encoded.contains(&HEX_8), "lowercase must fall through to raw");
        assert_eq!(round_trip(&node), node, "and must survive unchanged");
    }

    #[test]
    fn uppercase_hex_is_packed() {
        let node = Node::new("iq").attr("zzz_raw_key", "ABCDEF");
        let encoded = encode_node(&node);
        assert!(encoded.contains(&HEX_8));
        assert_eq!(round_trip(&node), node);
    }

    #[test]
    fn empty_attributes_are_dropped() {
        // The length prefix counts only non-empty attributes; emitting one desyncs the reader.
        let node = Node::new("iq").attr("keep", "1").attr("drop", "");
        let decoded = round_trip(&node);
        assert_eq!(decoded.attrs.len(), 1);
        assert_eq!(decoded.attrs["keep"], "1");
    }

    #[test]
    fn overlong_strings_are_not_packed() {
        let long_digits = "1".repeat(PACKED_MAX + 1);
        assert!(!is_nibble_packable(&long_digits));
        let node = Node::new("iq").attr("zzz_raw_key", &long_digits);
        assert_eq!(round_trip(&node), node);
    }

    #[test]
    fn truncated_input_errors_instead_of_panicking() {
        let encoded = encode_node(&Node::new("iq").attr("to", "a@s.whatsapp.net"));
        for cut in 1..encoded.len() {
            // Every prefix must produce Ok or Err, never a panic or a hang.
            let _ = decode_node(&encoded[1..cut]);
        }
    }

    #[test]
    fn garbage_input_errors() {
        assert!(decode_node(&[]).is_err());
        assert!(decode_node(&[0xFF, 0xFF, 0xFF]).is_err());
    }
}
