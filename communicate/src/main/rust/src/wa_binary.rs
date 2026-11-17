//! WhatsApp binary-node codec.
//!
//! Port of `BinaryEncoder`/`BinaryDecoder` in `WhatsAppProtocol.kt`. WhatsApp's stanza format is a
//! binary encoding of an XMPP-like tree: a tag, string attributes, and either child nodes or an
//! opaque payload. Strings are compressed against the dictionaries in [`crate::wa_tokens`], and
//! digit- or hex-only strings are nibble-packed two characters per byte.
//!
//! Scope: [`decode_node`] takes an **already-inflated** frame body. The frame flag byte and zlib
//! inflate stay in Kotlin (`java.util.zip`), matching how IO is split elsewhere in this crate and
//! keeping `miniz_oxide` out of the dependency tree.
//!
//! Fidelity matters more than elegance here — the server rejects stanzas that are merely
//! *valid* but not byte-identical in shape. Two rules are easy to lose and are pinned by tests:
//! JID attributes must use the JID_PAIR/AD_JID forms rather than raw strings, and hex packing is
//! uppercase-only (lowercase must fall through to a raw string or it decodes back upper-cased).

use crate::wa_tokens::{DOUBLE_BYTE_TOKENS, SINGLE_BYTE_TOKENS};
use std::collections::BTreeMap;

const LIST_EMPTY: u8 = 0;
const DICTIONARY_0: u8 = 236;
const DICTIONARY_3: u8 = 239;
const INTEROP_JID: u8 = 245;
const FB_JID: u8 = 246;
const AD_JID: u8 = 247;
const LIST_8: u8 = 248;
const LIST_16: u8 = 249;
const JID_PAIR: u8 = 250;
const HEX_8: u8 = 251;
const BINARY_8: u8 = 252;
const BINARY_20: u8 = 253;
const BINARY_32: u8 = 254;
const NIBBLE_8: u8 = 255;
/// Longest string that can be nibble/hex packed.
const PACKED_MAX: usize = 127;

/// One stanza node. `data` and `content` are mutually exclusive on the wire.
///
/// Attributes are a `BTreeMap` so encoding is deterministic; the Kotlin uses insertion-ordered
/// maps, and while the server does not require a particular attribute order, a stable order makes
/// round-trip tests meaningful.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Node {
    pub tag: String,
    pub attrs: BTreeMap<String, String>,
    pub content: Vec<Node>,
    pub data: Option<Vec<u8>>,
}

impl Node {
    pub fn new(tag: &str) -> Self {
        Self { tag: tag.to_string(), ..Default::default() }
    }

    pub fn attr(mut self, key: &str, value: &str) -> Self {
        self.attrs.insert(key.to_string(), value.to_string());
        self
    }
}

#[derive(Debug)]
pub enum Error {
    Eos,
    Invalid(String),
}

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Error::Eos => write!(f, "end of stream"),
            Error::Invalid(m) => write!(f, "{m}"),
        }
    }
}

pub type Result<T> = std::result::Result<T, Error>;

// ---------------------------------------------------------------------------
// Encoding
// ---------------------------------------------------------------------------

/// Encodes a node, including the leading frame flag byte (always 0: uncompressed).
pub fn encode_node(node: &Node) -> Vec<u8> {
    let mut enc = Encoder { out: vec![0] };
    enc.write_node(node);
    enc.out
}

struct Encoder {
    out: Vec<u8>,
}

impl Encoder {
    fn push(&mut self, b: u8) {
        self.out.push(b);
    }

    fn push_int16(&mut self, v: usize) {
        self.push((v >> 8) as u8);
        self.push(v as u8);
    }

    fn push_int20(&mut self, v: usize) {
        self.push(((v >> 16) & 0x0F) as u8);
        self.push((v >> 8) as u8);
        self.push(v as u8);
    }

    fn push_int32(&mut self, v: usize) {
        self.push((v >> 24) as u8);
        self.push((v >> 16) as u8);
        self.push((v >> 8) as u8);
        self.push(v as u8);
    }

    fn write_byte_length(&mut self, length: usize) {
        if length < 256 {
            self.push(BINARY_8);
            self.push(length as u8);
        } else if length < (1 << 20) {
            self.push(BINARY_20);
            self.push_int20(length);
        } else {
            self.push(BINARY_32);
            self.push_int32(length);
        }
    }

    fn write_node(&mut self, n: &Node) {
        // The literal tag "0" is the wire's "empty node" marker.
        if n.tag == "0" {
            self.push(LIST_8);
            self.push(LIST_EMPTY);
            return;
        }

        let has_content = usize::from(n.data.is_some() || !n.content.is_empty());
        let attr_count = n.attrs.values().filter(|v| !v.is_empty()).count();
        self.write_list_start(2 * attr_count + 1 + has_content);
        self.write_string(&n.tag);
        self.write_attributes(&n.attrs);

        if let Some(data) = &n.data {
            self.write_byte_length(data.len());
            self.out.extend_from_slice(data);
        } else if !n.content.is_empty() {
            self.write_list_start(n.content.len());
            for child in &n.content {
                self.write_node(child);
            }
        }
    }

    fn write_string(&mut self, value: &str) {
        if let Some(index) = index_of_single_token(value) {
            self.push(index);
            return;
        }
        if let Some((dict, index)) = index_of_double_token(value) {
            self.push(DICTIONARY_0 + dict);
            self.push(index);
            return;
        }
        if is_nibble_packable(value) {
            self.write_packed(value, NIBBLE_8);
        } else if is_hex_packable(value) {
            self.write_packed(value, HEX_8);
        } else {
            let bytes = value.as_bytes();
            self.write_byte_length(bytes.len());
            self.out.extend_from_slice(bytes);
        }
    }

    /// Writes a JID as its structured form.
    ///
    /// The server rejects stanzas (usync, prekey fetch) whose `jid` attributes arrive as raw
    /// strings, so this must not fall back to `write_string` for anything containing `@`.
    fn write_jid(&mut self, jid: &str) {
        let Some(at) = jid.find('@') else {
            self.write_string(jid);
            return;
        };
        let (user_part, server) = (&jid[..at], &jid[at + 1..]);

        // AD_JID carries exactly `<user>.<agent>:<device>` with numeric
        // agent/device bytes. Only that canonical form may use it: splitting
        // at the first '.' unconditionally would truncate a dotted user such
        // as `a.b.c`, and using AD_JID for agent-only or device-only forms
        // would synthesise the missing `:0`/`.0` on decode. Everything else
        // keeps the full user part in a JID_PAIR so it round-trips losslessly.
        let ad_form = if server == "s.whatsapp.net" {
            parse_ad_jid(user_part)
        } else {
            None
        };
        if let Some((user, agent, device)) = ad_form {
            self.push(AD_JID);
            self.push(agent);
            self.push(device);
            self.write_string(user);
        } else {
            self.push(JID_PAIR);
            if user_part.is_empty() {
                self.push(LIST_EMPTY);
            } else {
                self.write_string(user_part);
            }
            self.write_string(server);
        }
    }

    fn write_attributes(&mut self, attrs: &BTreeMap<String, String>) {
        for (key, value) in attrs {
            if value.is_empty() {
                continue;
            }
            self.write_string(key);
            if value.contains('@') {
                self.write_jid(value);
            } else {
                self.write_string(value);
            }
        }
    }

    fn write_list_start(&mut self, size: usize) {
        if size == 0 {
            self.push(LIST_EMPTY);
        } else if size < 256 {
            self.push(LIST_8);
            self.push(size as u8);
        } else {
            self.push(LIST_16);
            self.push_int16(size);
        }
    }

    fn write_packed(&mut self, value: &str, data_type: u8) {
        self.push(data_type);
        let chars: Vec<char> = value.chars().collect();
        let rounded = chars.len().div_ceil(2);
        // The high bit flags an odd length, whose final nibble is padding.
        let flag = if chars.len() % 2 == 0 { rounded } else { rounded | 128 };
        self.push(flag as u8);

        let pack = |c: char| if data_type == NIBBLE_8 { pack_nibble(c) } else { pack_hex(c) };
        for i in 0..chars.len() / 2 {
            self.push((pack(chars[2 * i]) << 4) | pack(chars[2 * i + 1]));
        }
        if chars.len() % 2 != 0 {
            self.push((pack(chars[chars.len() - 1]) << 4) | 15);
        }
    }
}

/// Parses `<user>.<agent>:<device>` where agent and device are decimal
/// `u8`s. Returns `None` for any other user shape (dotted names, agent-only,
/// device-only, out-of-range or non-numeric suffixes).
fn parse_ad_jid(user_part: &str) -> Option<(&str, u8, u8)> {
    let (before_colon, device) = user_part.split_once(':')?;
    if device.is_empty() || !device.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    let (user, agent) = before_colon.split_once('.')?;
    if agent.is_empty() || !agent.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    Some((user, agent.parse().ok()?, device.parse().ok()?))
}

fn index_of_single_token(token: &str) -> Option<u8> {
    if token.is_empty() {
        return None;
    }
    SINGLE_BYTE_TOKENS.iter().position(|t| *t == token).map(|i| i as u8)
}

fn index_of_double_token(token: &str) -> Option<(u8, u8)> {
    if token.is_empty() {
        return None;
    }
    for (dict, tokens) in DOUBLE_BYTE_TOKENS.iter().enumerate() {
        if let Some(index) = tokens.iter().position(|t| *t == token) {
            return Some((dict as u8, index as u8));
        }
    }
    None
}

fn is_nibble_packable(value: &str) -> bool {
    value.len() <= PACKED_MAX
        && !value.is_empty()
        && value.chars().all(|c| c.is_ascii_digit() || c == '-' || c == '.')
}

/// Hex packing is uppercase-only. Lowercase would decode back upper-cased, so it must fall
/// through to a raw string (whatsmeow `binary/encoder.go`).
fn is_hex_packable(value: &str) -> bool {
    value.len() <= PACKED_MAX
        && !value.is_empty()
        && value.chars().all(|c| c.is_ascii_digit() || ('A'..='F').contains(&c))
}

fn pack_nibble(c: char) -> u8 {
    match c {
        '0'..='9' => c as u8 - b'0',
        '-' => 10,
        '.' => 11,
        _ => 15,
    }
}

fn pack_hex(c: char) -> u8 {
    match c {
        '0'..='9' => c as u8 - b'0',
        'A'..='F' => 10 + (c as u8 - b'A'),
        'a'..='f' => 10 + (c as u8 - b'a'),
        _ => 15,
    }
}

// ---------------------------------------------------------------------------
// Decoding
// ---------------------------------------------------------------------------

/// Decodes a node from an **already-inflated** frame body (no flag byte).
pub fn decode_node(data: &[u8]) -> Result<Node> {
    Decoder { data, index: 0 }.read_node()
}

/// A decoded wire value: a token/string, an opaque payload, or a child list.
enum Value {
    Empty,
    Text(String),
    Bytes(Vec<u8>),
    List(Vec<Node>),
}

impl Value {
    fn into_text(self) -> Option<String> {
        match self {
            Value::Text(s) => Some(s),
            Value::Bytes(b) => Some(String::from_utf8_lossy(&b).into_owned()),
            _ => None,
        }
    }
}

struct Decoder<'a> {
    data: &'a [u8],
    index: usize,
}

include!("wa_binary_part1.rs");