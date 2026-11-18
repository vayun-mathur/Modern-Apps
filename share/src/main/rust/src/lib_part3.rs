// File-list JSON (PROTOCOL_CONTRACT.md §6), part of `lib.rs` via `include!`.
//
// Split out of `lib.rs` to keep that file under the `rustFileLength` gate,
// like `lib_part1.rs` / `lib_part2.rs`; shares `lib.rs` scope textually.

fn json_escape(s: &str) -> String {
    s.replace('\\', "\\\\").replace('"', "\\\"")
}

fn file_list_json(files: &[payload::FileMeta]) -> String {
    let mut json = String::from("[");
    for (i, f) in files.iter().enumerate() {
        if i > 0 {
            json.push(',');
        }
        json.push_str(&format!(
            "{{\"name\":\"{}\",\"sizeBytes\":{},\"mimeType\":\"{}\"}}",
            json_escape(&f.name),
            f.size_bytes,
            json_escape(&f.mime_type),
        ));
    }
    json.push(']');
    json
}

/// Parse the fixed `[{name,sizeBytes,mimeType}]` shape produced by `ShareSession`.
///
/// Hand-rolled rather than adding `serde_json` to this crate: the shape is fixed by
/// `PROTOCOL_CONTRACT.md` §6 and both sides of it live in this repository.
fn parse_file_list_json(text: &str) -> Option<Vec<payload::FileMeta>> {
    /// Decode one JSON string literal starting at the first `"` of `s`.
    ///
    /// The producer is kotlinx's `Json.encodeToString` (and this crate's
    /// own [`file_list_json`]), which escapes `"` and `\` inside names.
    /// Stopping at the first unescaped quote and decoding the escapes is
    /// what makes a name like `my "quoted" file.txt` survive the round
    /// trip; the previous find-the-next-quote version mangled it.
    fn string_field(obj: &str, key: &str) -> Option<String> {
        let after_key = obj.split_once(&format!("\"{key}\""))?.1;
        let after_colon = after_key.split_once(':')?.1;
        let open = after_colon.find('"')?;
        let mut out = String::new();
        let mut chars = after_colon[open + 1..].chars();
        loop {
            match chars.next()? {
                '"' => return Some(out),
                '\\' => match chars.next()? {
                    '"' => out.push('"'),
                    '\\' => out.push('\\'),
                    '/' => out.push('/'),
                    'b' => out.push('\u{8}'),
                    'f' => out.push('\u{c}'),
                    'n' => out.push('\n'),
                    'r' => out.push('\r'),
                    't' => out.push('\t'),
                    'u' => {
                        let hex: String = chars.by_ref().take(4).collect();
                        let code = u32::from_str_radix(&hex, 16).ok()?;
                        out.push(char::from_u32(code).unwrap_or('\u{fffd}'));
                    }
                    _ => return None,
                },
                c => out.push(c),
            }
        }
    }
    fn number_field(obj: &str, key: &str) -> Option<u64> {
        let after_key = obj.split_once(&format!("\"{key}\""))?.1;
        let after_colon = after_key.split_once(':')?.1;
        let digits: String = after_colon
            .trim_start()
            .chars()
            .take_while(char::is_ascii_digit)
            .collect();
        digits.parse().ok()
    }

    let inner = text
        .trim()
        .strip_prefix('[')
        .and_then(|s| s.strip_suffix(']'))?
        .trim();
    if inner.is_empty() {
        return Some(Vec::new());
    }
    // Split the objects on the commas *between* them: a `,` or `}` inside
    // a string value (a file named `a},b.txt`) is not a separator.
    let mut objects = Vec::new();
    let mut in_string = false;
    let mut escaped = false;
    let mut depth = 0;
    let mut start = 0;
    for (i, c) in inner.char_indices() {
        if in_string {
            if escaped {
                escaped = false;
            } else if c == '\\' {
                escaped = true;
            } else if c == '"' {
                in_string = false;
            }
        } else if c == '"' {
            in_string = true;
        } else if c == '{' {
            depth += 1;
        } else if c == '}' {
            depth -= 1;
        } else if c == ',' && depth == 0 {
            objects.push(&inner[start..i]);
            start = i + 1;
        }
    }
    objects.push(&inner[start..]);
    let mut out = Vec::new();
    for obj in objects {
        let obj = obj.trim().strip_prefix('{').and_then(|s| s.strip_suffix('}'))?;
        out.push(payload::FileMeta {
            name: string_field(obj, "name")?,
            size_bytes: number_field(obj, "sizeBytes")?,
            mime_type: string_field(obj, "mimeType").unwrap_or_default(),
            payload_id: 0,
        });
    }
    Some(out)
}
