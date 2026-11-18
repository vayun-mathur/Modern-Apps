//! KDBX (KeePass) read/write for the `passwords` app, backed by the Rust
//! `keepass` crate and exposed to Kotlin over JNI. Replaces keepassjava2 +
//! Bouncy Castle while keeping existing `.kdbx` vaults readable.
//!
//! Data model crossing JNI is a flat list of `{ field: value }` maps encoded as
//! JSON. Previously used `serde_json` for this tiny `Vec<BTreeMap>` wire format,
//! pulling zmij, memchr, itoa chain (per "single function we use, just write it
//! ourselves" + "prefer stdlib"). Now std-only JSON with proper escaping,
//! preserving all features (protected Password field, custom KPEX_PASSKEY_*,
//! nested group flattening). No feature reduction.

use keepass::db::{fields, Database, Value};
use keepass::DatabaseKey;
use std::collections::BTreeMap;

/// A single KeePass entry as a flat field map (both standard and custom fields).
type EntryMap = BTreeMap<String, String>;

// ---------------------------------------------------------------------------
// std-only JSON for Vec<EntryMap> – single-function rewrite
// ---------------------------------------------------------------------------
fn json_escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push('"');
    for ch in s.chars() {
        match ch {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if c.is_control() => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

fn to_json_std(entries: &[EntryMap]) -> Option<String> {
    let mut out = String::new();
    out.push('[');
    for (i, map) in entries.iter().enumerate() {
        if i > 0 { out.push(','); }
        out.push('{');
        let mut first = true;
        for (k, v) in map {
            if !first { out.push(','); }
            first = false;
            out.push_str(&json_escape(k));
            out.push(':');
            out.push_str(&json_escape(v));
        }
        out.push('}');
    }
    out.push(']');
    Some(out)
}

/// Very small JSON parser for `[{...}]` where values are strings only – matches
/// Kotlin `org.json.JSONArray.toString()` output (strings, no nested objects).
/// Uses std only, handles escaped quotes and `\n,\r,\t,\\,\/` plus `\uXXXX`.
fn from_json_std(s: &str) -> Option<Vec<EntryMap>> {
    let bytes = s.as_bytes();
    let mut i = 0usize;
    let skip_ws = |b: &[u8], i: &mut usize| {
        while *i < b.len() && b[*i].is_ascii_whitespace() { *i += 1; }
    };
    skip_ws(bytes, &mut i);
    if i >= bytes.len() || bytes[i] != b'[' { return None; }
    i += 1;
    let mut out: Vec<EntryMap> = Vec::new();
    skip_ws(bytes, &mut i);
    if i < bytes.len() && bytes[i] == b']' {
        i += 1;
        skip_ws(bytes, &mut i);
        if i != bytes.len() { return None; }
        return Some(out);
    }
    loop {
        skip_ws(bytes, &mut i);
        if i >= bytes.len() || bytes[i] != b'{' { return None; }
        i += 1;
        let mut map = EntryMap::new();
        skip_ws(bytes, &mut i);
        if i < bytes.len() && bytes[i] != b'}' {
            loop {
                // key string
                let key = parse_json_string(bytes, &mut i)?;
                skip_ws(bytes, &mut i);
                if i >= bytes.len() || bytes[i] != b':' { return None; }
                i += 1;
                let val = parse_json_string(bytes, &mut i)?;
                map.insert(key, val);
                skip_ws(bytes, &mut i);
                if i >= bytes.len() { return None; }
                if bytes[i] == b'}' { break; }
                if bytes[i] != b',' { return None; }
                i += 1;
            }
        }
        // skip closing '}'
        skip_ws(bytes, &mut i);
        if i >= bytes.len() || bytes[i] != b'}' { return None; }
        i += 1;
        out.push(map);
        skip_ws(bytes, &mut i);
        if i >= bytes.len() { return None; }
        if bytes[i] == b']' { i += 1; break; }
        if bytes[i] != b',' { return None; }
        i += 1;
    }
    skip_ws(bytes, &mut i);
    if i != bytes.len() { return None; }
    Some(out)
}

fn parse_json_string(bytes: &[u8], pos: &mut usize) -> Option<String> {
    while *pos < bytes.len() && bytes[*pos].is_ascii_whitespace() { *pos += 1; }
    if *pos >= bytes.len() || bytes[*pos] != b'"' { return None; }
    *pos += 1;
    let mut out = String::new();
    while *pos < bytes.len() {
        let b = bytes[*pos];
        if b == b'"' {
            *pos += 1;
            return Some(out);
        }
        if b == b'\\' {
            *pos += 1;
            if *pos >= bytes.len() { return None; }
            let esc = bytes[*pos];
            match esc {
                b'"' => out.push('"'),
                b'\\' => out.push('\\'),
                b'/' => out.push('/'),
                b'n' => out.push('\n'),
                b'r' => out.push('\r'),
                b't' => out.push('\t'),
                b'b' => out.push('\u{08}'),
                b'f' => out.push('\u{0C}'),
                b'u' => {
                    if *pos + 4 >= bytes.len() { return None; }
                    let hex_slice = &bytes[*pos + 1..*pos + 5];
                    let hex_str = String::from_utf8_lossy(hex_slice);
                    let code = u32::from_str_radix(&hex_str, 16).ok()?;
                    if (0xD800..0xDC00).contains(&code) {
                        // High surrogate: org.json escapes astral chars as a
                        // \uXXXX\uXXXX pair; combine into one scalar value.
                        if *pos + 10 >= bytes.len() { return None; }
                        if bytes[*pos + 5] != b'\\' || bytes[*pos + 6] != b'u' { return None; }
                        let lo_slice = &bytes[*pos + 7..*pos + 11];
                        let lo_str = String::from_utf8_lossy(lo_slice);
                        let lo = u32::from_str_radix(&lo_str, 16).ok()?;
                        if !(0xDC00..0xE000).contains(&lo) { return None; }
                        let c = 0x10000 + ((code - 0xD800) << 10) + (lo - 0xDC00);
                        out.push(std::char::from_u32(c)?);
                        *pos += 10;
                    } else if (0xDC00..0xE000).contains(&code) {
                        return None;
                    } else {
                        out.push(std::char::from_u32(code)?);
                        *pos += 4;
                    }
                }
                _ => return None,
            }
            *pos += 1;
        } else {
            // UTF-8 char
            // find next byte boundary
            let remaining = &bytes[*pos..];
            let ch = std::str::from_utf8(remaining).ok()?.chars().next()?;
            out.push(ch);
            *pos += ch.len_utf8();
        }
    }
    None
}

// ---------------------------------------------------------------------------
// core API
// ---------------------------------------------------------------------------

/// Open a KDBX vault and return every entry (from every group, recursively) as
/// a JSON array of `{ field: value }` objects. Returns `None` on wrong password
/// or malformed input.
pub fn import_kdbx(password: &str, data: &[u8]) -> Option<String> {
    let key = DatabaseKey::new().with_password(password);
    let db = Database::parse(data, key).ok()?;

    let mut out: Vec<EntryMap> = Vec::new();
    collect_group(&db, db.root().id(), &mut out);
    to_json_std(&out)
}

fn collect_group(db: &Database, group_id: keepass::db::GroupId, out: &mut Vec<EntryMap>) {
    let group = match db.group(group_id) {
        Some(g) => g,
        None => return,
    };
    for entry in group.entries() {
        let mut map = EntryMap::new();
        for (k, v) in &entry.fields {
            map.insert(k.clone(), v.get().clone());
        }
        out.push(map);
    }
    let child_ids: Vec<_> = group.group_ids().collect();
    for child in child_ids {
        collect_group(db, child, out);
    }
}

/// Build a KDBX4 vault from a JSON array of `{ field: value }` objects and
/// return the encrypted bytes. Every entry added to root group. The `Password`
/// field stored protected (KeePass default MemoryProtection); all other stored
/// unprotected. Returns `None` on bad JSON or encryption failure.
pub fn export_kdbx(password: &str, entries_json: &str) -> Option<Vec<u8>> {
    let entries = from_json_std(entries_json)?;

    let mut db = Database::new();
    for entry_map in entries {
        let mut root = db.root_mut();
        let mut entry = root.add_entry();
        for (k, val) in entry_map {
            if k == fields::PASSWORD {
                entry.set(k, Value::protected(val));
            } else {
                entry.set(k, Value::unprotected(val));
            }
        }
    }

    let key = DatabaseKey::new().with_password(password);
    let mut buf = Vec::new();
    db.save(&mut buf, key).ok()?;
    Some(buf)
}

/// Testable dispatch for the JNI import entry point: `None` inputs (failed
/// JNI string/byte-array conversion) and vault failures both map to `None`,
/// which the thin JNI wrapper turns into a Java `null`.
pub(crate) fn import_for_jni(password: Option<String>, data: Option<Vec<u8>>) -> Option<String> {
    import_kdbx(&password?, &data?)
}

/// Testable dispatch for the JNI export entry point; see [`import_for_jni`].
pub(crate) fn export_for_jni(password: Option<String>, entries_json: Option<String>) -> Option<Vec<u8>> {
    export_kdbx(&password?, &entries_json?)
}

mod jni_bridge;

#[cfg(test)]
mod json_tests;
#[cfg(test)]
mod tests;
