pub(crate) mod cmap {
    use std::collections::HashMap;

    enum Token {
        Hex(Vec<u8>),
        ArrayOpen,
        ArrayClose,
        Keyword(String),
    }

    /// Parse a `/ToUnicode` CMap stream into a `code -> string` map, handling
    /// `beginbfchar`/`endbfchar` and `beginbfrange`/`endbfrange`.
    pub fn parse(data: &[u8]) -> HashMap<u32, String> {
        let tokens = tokenize(data);
        let mut map = HashMap::new();
        let mut i = 0;
        while i < tokens.len() {
            match &tokens[i] {
                Token::Keyword(k) if k == "beginbfchar" => {
                    i += 1;
                    while i < tokens.len() {
                        if let Token::Keyword(e) = &tokens[i] {
                            if e == "endbfchar" {
                                break;
                            }
                        }
                        if let (Token::Hex(src), Some(Token::Hex(dst))) =
                            (&tokens[i], tokens.get(i + 1))
                        {
                            map.insert(code(src), utf16be(dst));
                            i += 2;
                        } else {
                            i += 1;
                        }
                    }
                    i += 1; // skip endbfchar
                }
                Token::Keyword(k) if k == "beginbfrange" => {
                    i += 1;
                    while i < tokens.len() {
                        if let Token::Keyword(e) = &tokens[i] {
                            if e == "endbfrange" {
                                break;
                            }
                        }
                        match (tokens.get(i), tokens.get(i + 1), tokens.get(i + 2)) {
                            (Some(Token::Hex(lo)), Some(Token::Hex(hi)), Some(Token::Hex(dst))) => {
                                let (lo, hi) = (code(lo), code(hi));
                                // A single bfrange cannot sanely span more than the
                                // 16-bit code space; clamp so a corrupt 4-byte `hi`
                                // cannot allocate billions of strings.
                                let hi = hi.min(lo.saturating_add(super::MAX_CID));
                                let base = utf16be_units(dst);
                                for (n, c) in (lo..=hi).enumerate() {
                                    map.insert(c, units_to_string_incremented(&base, n as u32));
                                }
                                i += 3;
                            }
                            (Some(Token::Hex(lo)), Some(Token::Hex(hi)), Some(Token::ArrayOpen)) => {
                                let lo = code(lo);
                                // 9.10.3: the array holds one destination per code in
                                // lo..=hi. Clamped like the incrementing form so a
                                // corrupt `hi` cannot be outrun by a longer array.
                                let hi = code(hi).min(lo.saturating_add(super::MAX_CID));
                                i += 3; // skip lo, hi, '['
                                let mut n = 0u32;
                                while i < tokens.len() {
                                    match &tokens[i] {
                                        Token::ArrayClose => {
                                            i += 1;
                                            break;
                                        }
                                        Token::Hex(dst) => {
                                            // `lo` comes from the file and the array
                                            // may be longer than hi-lo+1, so cap the
                                            // walk at `hi` instead of letting it run
                                            // past the range and wrap.
                                            let c = lo.saturating_add(n);
                                            if c > hi {
                                                i += 1;
                                                continue;
                                            }
                                            map.insert(c, utf16be(dst));
                                            n += 1;
                                            i += 1;
                                        }
                                        _ => i += 1,
                                    }
                                }
                            }
                            _ => i += 1,
                        }
                    }
                    i += 1; // skip endbfrange
                }
                _ => i += 1,
            }
        }
        map
    }

    fn tokenize(data: &[u8]) -> Vec<Token> {
        let mut tokens = Vec::new();
        let mut i = 0;
        while i < data.len() {
            let b = data[i];
            match b {
                b'<' => {
                    let mut hex = String::new();
                    i += 1;
                    while i < data.len() && data[i] != b'>' {
                        if !data[i].is_ascii_whitespace() {
                            hex.push(data[i] as char);
                        }
                        i += 1;
                    }
                    i += 1; // consume '>'
                    tokens.push(Token::Hex(hex_to_bytes(&hex)));
                }
                b'[' => {
                    tokens.push(Token::ArrayOpen);
                    i += 1;
                }
                b']' => {
                    tokens.push(Token::ArrayClose);
                    i += 1;
                }
                _ if b.is_ascii_alphabetic() => {
                    let mut kw = String::new();
                    while i < data.len()
                        && (data[i].is_ascii_alphanumeric() || data[i] == b'*')
                    {
                        kw.push(data[i] as char);
                        i += 1;
                    }
                    tokens.push(Token::Keyword(kw));
                }
                _ => i += 1,
            }
        }
        tokens
    }

    fn hex_to_bytes(hex: &str) -> Vec<u8> {
        // Tokenizers push raw bytes as chars; non-ASCII input would slice inside a char.
        if !hex.is_ascii() {
            return Vec::new();
        }
        let mut h = hex.to_string();
        if h.len() % 2 == 1 {
            h.push('0');
        }
        (0..h.len())
            .step_by(2)
            .filter_map(|i| u8::from_str_radix(&h[i..i + 2], 16).ok())
            .collect()
    }

    fn code(bytes: &[u8]) -> u32 {
        let mut c = 0u32;
        for &b in bytes {
            c = (c << 8) | b as u32;
        }
        c
    }

    /// Codespace ranges for the predefined mixed-width CMap families (PDF 9.7.5.2,
    /// Table 118). These interleave 1-byte and 2-byte codes, so decoding them as
    /// fixed 2-byte codes desynchronizes the byte stream for the rest of the
    /// string. The CID mapping itself still needs the real compiled table, but
    /// getting the segmentation right makes `/ToUnicode` (which is keyed by CODE)
    /// resolve correctly, which is what most such files rely on.
    ///
    /// Returning `None` means "fixed 2-byte", which is right for Identity-H/V,
    /// the `Uni*-UCS2-*` families, and the pure-2-byte ISO-2022 families
    /// (`H`, `V`, `Add-H`, `Ext-H`, whose codespace is <2121>-<7E7E>).
    pub fn predefined_codespace(name: &str) -> Option<Vec<(u32, u32, u8)>> {
        // UTF-8 (UniJIS-UTF8-H, UniGB-UTF8-H, UniCNS-UTF8-H, UniKS-UTF8-H): 1-4
        // bytes, so a fixed 2-byte read desynchronizes on the very first ASCII
        // character. Checked before the region families because the names
        // overlap (e.g. "UniGB-UTF8-H" also contains "GB").
        if name.contains("UTF8") {
            return Some(vec![
                (0x00, 0x7F, 1),
                (0xC080, 0xDFBF, 2),
                (0xE08080, 0xEFBFBF, 3),
                (0xF0808080, 0xF7BFBFBF, 4),
            ]);
        }
        // UTF-16: 2 bytes, except surrogate pairs which are 4.
        if name.contains("UTF16") {
            return Some(vec![
                (0x0000, 0xD7FF, 2),
                (0xD800DC00, 0xDBFFDFFF, 4),
                (0xE000, 0xFFFF, 2),
            ]);
        }
        // Shift-JIS: 90ms-RKSJ-H, 90msp-RKSJ-V, 90pv-RKSJ-H, Add-RKSJ-H, Ext-RKSJ-H
        if name.contains("RKSJ") {
            return Some(vec![
                (0x00, 0x80, 1),
                (0x8140, 0x9FFC, 2),
                (0xA0, 0xDF, 1),
                (0xE040, 0xFCFC, 2),
            ]);
        }
        // Japanese EUC: EUC-H, EUC-V. The 0x8E single-shift form is a 2-byte code.
        if name.starts_with("EUC-") {
            return Some(vec![(0x00, 0x80, 1), (0x8EA0, 0x8EFE, 2), (0xA1A1, 0xFEFE, 2)]);
        }
        // GBK: GBK-EUC-H, GBKp-EUC-H, GBK2K-H
        if name.contains("GBK") {
            let mut cs = vec![(0x00, 0x80, 1), (0x8140, 0xFEFE, 2)];
            // GBK2K-H/V is GB18030, which adds a four-byte plane distinguished from the
            // two-byte plane only by the SECOND byte (0x30-0x39). `code_len` dispatches on
            // the first byte alone and cannot see it, so `code_len_at` resolves this range
            // with a one-byte lookahead. It is listed AFTER the two-byte range so
            // `code_len` still answers 2 for a bare first byte. Plain GBK-EUC has no such
            // plane and must not get the range.
            if name.contains("GBK2K") {
                cs.push((0x8130_8130, 0xFE39_FE39, 4));
            }
            return Some(cs);
        }
        // EUC-CN: GB-EUC-H, GBpc-EUC-H (checked after GBK, whose names also
        // contain "GB").
        if name.contains("GB") && name.contains("EUC") {
            return Some(vec![(0x00, 0x80, 1), (0xA1A1, 0xFEFE, 2)]);
        }
        // Big5: ETen-B5-H, ETenms-B5-H, B5pc-H, HKscs-B5-H
        if name.contains("-B5") || name.starts_with("B5") {
            return Some(vec![(0x00, 0x80, 1), (0xA140, 0xFEFE, 2)]);
        }
        // Korean UHC / EUC-KR: KSCms-UHC-H, KSCms-UHC-HW-V, KSC-EUC-H, KSCpc-EUC-H
        if name.contains("UHC") || name.contains("KSC") {
            return Some(vec![(0x00, 0x80, 1), (0x8141, 0xFEFE, 2)]);
        }
        None
    }

    /// A Type0 `/Encoding` CMap: variable-length codespace ranges plus code->CID
    /// mappings (from `begincidrange`/`begincidchar`), and the writing mode.
    #[derive(Default, Clone)]
    pub struct EncodingCMap {
        /// (lo, hi, byte_len) codespace ranges.
        pub codespace: Vec<(u32, u32, u8)>,
        pub single: HashMap<u32, u32>,
        /// (lo, hi, cid_of_lo) contiguous ranges.
        pub ranges: Vec<(u32, u32, u32)>,
        pub wmode: u8,
    }

    impl EncodingCMap {
        /// Map a character code to a CID (identity fallback if unmapped).
        pub fn to_cid(&self, code: u32) -> u32 {
            if let Some(c) = self.single.get(&code) {
                return *c;
            }
            for &(lo, hi, c0) in &self.ranges {
                if code >= lo && code <= hi {
                    return c0 + (code - lo);
                }
            }
            code
        }

        /// Byte length of the code beginning with `first_byte`, using the
        /// codespace ranges (defaults to 2 bytes, the Identity case).
        pub fn code_len(&self, first_byte: u8) -> usize {
            for &(lo, hi, n) in &self.codespace {
                let shift = (n.saturating_sub(1)) * 8;
                let flo = (lo >> shift) & 0xFF;
                let fhi = (hi >> shift) & 0xFF;
                if (first_byte as u32) >= flo && (first_byte as u32) <= fhi {
                    return n as usize;
                }
            }
            if self.codespace.is_empty() { 2 } else { self.codespace[0].2 as usize }
        }

        /// Byte length of the code beginning at `bytes[0]`, using the following byte to
        /// disambiguate where the codespace needs it.
        ///
        /// §9.7.6.2 matches a code against the codespace ranges byte by byte, so two
        /// ranges may share a first byte and differ in length. GB18030 (GBK2K-H/V) is the
        /// case that matters in practice: its four-byte plane differs from its two-byte
        /// plane only in the SECOND byte.
        ///
        /// This is a strict refinement of [`Self::code_len`] — it only ever chooses a
        /// LONGER range, and only when that range also matches the second byte. No other
        /// predefined codespace has two ranges of different length sharing a first byte,
        /// so for every font but GB18030 the answer is identical to `code_len`.
        pub fn code_len_at(&self, bytes: &[u8]) -> usize {
            let Some(&first) = bytes.first() else { return 1 };
            let n = self.code_len(first);
            let Some(&second) = bytes.get(1) else { return n };
            for &(lo, hi, len) in &self.codespace {
                let len = len as usize;
                if len <= n || len > 4 {
                    continue;
                }
                let s1 = (len - 1) * 8;
                if !((lo >> s1) & 0xFF..=(hi >> s1) & 0xFF).contains(&(first as u32)) {
                    continue;
                }
                let s2 = (len - 2) * 8;
                if ((lo >> s2) & 0xFF..=(hi >> s2) & 0xFF).contains(&(second as u32)) {
                    return len;
                }
            }
            n
        }
    }

    /// Parse a Type0 `/Encoding` CMap stream. Handles `codespacerange`,
    /// `cidrange`, `cidchar`, and `/WMode`. Numeric CID operands are decimal.
    pub fn parse_encoding_cmap(data: &[u8]) -> EncodingCMap {
        let mut cm = EncodingCMap::default();
        // Lightweight token scan: hex strings <..>, decimal integers, keywords.
        #[derive(PartialEq)]
        enum T { Hex(Vec<u8>), Int(u32), Kw(String) }
        let mut toks: Vec<T> = Vec::new();
        let mut i = 0;
        while i < data.len() {
            let b = data[i];
            if b == b'<' {
                let mut hex = String::new();
                i += 1;
                while i < data.len() && data[i] != b'>' {
                    if !data[i].is_ascii_whitespace() { hex.push(data[i] as char); }
                    i += 1;
                }
                i += 1;
                toks.push(T::Hex(hex_to_bytes(&hex)));
            } else if b.is_ascii_digit() {
                let s = i;
                while i < data.len() && data[i].is_ascii_digit() { i += 1; }
                let n: u32 = std::str::from_utf8(&data[s..i]).ok().and_then(|x| x.parse().ok()).unwrap_or(0);
                toks.push(T::Int(n));
            } else if b.is_ascii_alphabetic() || b == b'/' {
                let s = i;
                i += 1;
                // '-' is part of predefined CMap names (`/90ms-RKSJ-H usecmap`), so
                // it must not terminate the token.
                while i < data.len() && (data[i].is_ascii_alphanumeric() || data[i] == b'/' || data[i] == b'.' || data[i] == b'-') { i += 1; }
                toks.push(T::Kw(String::from_utf8_lossy(&data[s..i]).into_owned()));
            } else {
                i += 1;
            }
        }
        let byte_len = |bytes: &[u8]| -> u8 { bytes.len().clamp(1, 4) as u8 };
        let mut j = 0;
        while j < toks.len() {
            match &toks[j] {
                // `/SomeCMap usecmap` inherits the referenced CMap. Only a
                // predefined name can be inherited here (an embedded one would have
                // to be reachable through the stream's own /UseCMap, which lopdf
                // does not hand us), and only its codespace ranges are recoverable,
                // so inherit those and let this stream's own ranges override.
                T::Kw(k) if k == "usecmap" => {
                    if let Some(T::Kw(name)) = j.checked_sub(1).and_then(|p| toks.get(p)) {
                        let base = name.trim_start_matches('/');
                        if let Some(cs) = predefined_codespace(base) {
                            for r in cs {
                                if !cm.codespace.contains(&r) {
                                    cm.codespace.push(r);
                                }
                            }
                        }
                        if base.ends_with("-V") {
                            cm.wmode = 1;
                        }
                    }
                    j += 1;
                }
                T::Kw(k) if k == "/WMode" => {
                    if let Some(T::Int(w)) = toks.get(j + 1) { cm.wmode = if *w >= 1 { 1 } else { 0 }; }
                    j += 1;
                }
                T::Kw(k) if k == "begincodespacerange" => {
                    j += 1;
                    while j + 1 < toks.len() {
                        if let (T::Hex(lo), T::Hex(hi)) = (&toks[j], &toks[j + 1]) {
                            cm.codespace.push((code(lo), code(hi), byte_len(lo)));
                            j += 2;
                        } else { break; }
                    }
                }
                T::Kw(k) if k == "begincidrange" => {
                    j += 1;
                    while j + 2 < toks.len() {
                        match (&toks[j], &toks[j + 1], &toks[j + 2]) {
                            (T::Hex(lo), T::Hex(hi), T::Int(cid)) => {
                                cm.ranges.push((code(lo), code(hi), *cid));
                                j += 3;
                            }
                            _ => break,
                        }
                    }
                }
                T::Kw(k) if k == "begincidchar" => {
                    j += 1;
                    while j + 1 < toks.len() {
                        match (&toks[j], &toks[j + 1]) {
                            (T::Hex(c), T::Int(cid)) => {
                                cm.single.insert(code(c), *cid);
                                j += 2;
                            }
                            _ => break,
                        }
                    }
                }
                _ => { j += 1; }
            }
        }
        cm
    }

    fn utf16be_units(bytes: &[u8]) -> Vec<u16> {
        bytes
            .chunks(2)
            .map(|c| {
                let hi = c[0] as u16;
                let lo = *c.get(1).unwrap_or(&0) as u16;
                (hi << 8) | lo
            })
            .collect()
    }

    fn utf16be(bytes: &[u8]) -> String {
        String::from_utf16_lossy(&utf16be_units(bytes))
    }

    /// Increment the last UTF-16 code unit by `n` (per PDF bfrange semantics)
    /// and decode the result.
    fn units_to_string_incremented(units: &[u16], n: u32) -> String {
        let mut u = units.to_vec();
        if let Some(last) = u.last_mut() {
            *last = last.wrapping_add(n as u16);
        }
        String::from_utf16_lossy(&u)
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn parses_bfchar_single_byte() {
            let cmap = b"2 beginbfchar\n<41> <0041>\n<42> <0042>\nendbfchar";
            let map = parse(cmap);
            assert_eq!(map.get(&0x41).map(String::as_str), Some("A"));
            assert_eq!(map.get(&0x42).map(String::as_str), Some("B"));
        }

        #[test]
        fn parses_bfchar_two_byte() {
            let cmap = b"1 beginbfchar\n<0003> <0048>\nendbfchar";
            let map = parse(cmap);
            assert_eq!(map.get(&0x0003).map(String::as_str), Some("H"));
        }

        #[test]
        fn parses_bfrange_incrementing() {
            let cmap = b"1 beginbfrange\n<0041> <0043> <0061>\nendbfrange";
            let map = parse(cmap);
            assert_eq!(map.get(&0x41).map(String::as_str), Some("a"));
            assert_eq!(map.get(&0x42).map(String::as_str), Some("b"));
            assert_eq!(map.get(&0x43).map(String::as_str), Some("c"));
        }

        #[test]
        fn parses_bfrange_after_preamble() {
            // A realistic ToUnicode with a dict/codespace preamble before the
            // bfrange block (regression for a token double-increment bug).
            let cmap = b"/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n<< /Registry (TTX+0) /Ordering (T1) /Supplement 0 >> def\n1 begincodespacerange\n<0000><FFFF>\nendcodespacerange\n2 beginbfrange\n<0033><0033><0050>\n<0055><0055><0072>\nendbfrange\nendcmap";
            let map = parse(cmap);
            assert_eq!(map.get(&0x33).map(String::as_str), Some("P"));
            assert_eq!(map.get(&0x55).map(String::as_str), Some("r"));
        }
    }
}
