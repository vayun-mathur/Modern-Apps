impl<'a> Interp<'a> {
    /// Returns true if an endchar/seac terminated the whole glyph.
    fn exec(&mut self, cs: &[u8], out: &mut ContourBuilder) -> bool {
        if self.depth > 30 {
            return true;
        }
        self.depth += 1;
        let mut i = 0;
        while i < cs.len() {
            let b = cs[i];
            i += 1;
            if b >= 32 {
                // Number operand.
                let v = if b <= 246 {
                    (b as i32 - 139) as f64
                } else if b <= 250 {
                    let w = *cs.get(i).unwrap_or(&0) as i32;
                    i += 1;
                    ((b as i32 - 247) * 256 + w + 108) as f64
                } else if b <= 254 {
                    let w = *cs.get(i).unwrap_or(&0) as i32;
                    i += 1;
                    (-(b as i32 - 251) * 256 - w - 108) as f64
                } else {
                    // 255: 32-bit signed integer.
                    let mut n: i32 = 0;
                    for _ in 0..4 {
                        n = (n << 8) | (*cs.get(i).unwrap_or(&0) as i32);
                        i += 1;
                    }
                    n as f64
                };
                self.stack.push(v);
                continue;
            }
            match b {
                13 => {
                    // hsbw: sbx wx
                    if self.stack.len() >= 2 {
                        self.sbx = self.stack[0];
                        self.x = self.stack[0];
                        self.y = 0.0;
                    }
                    self.stack.clear();
                }
                9 => {
                    // closepath
                    out.close();
                    self.stack.clear();
                }
                21 => {
                    // rmoveto
                    let n = self.stack.len();
                    if n >= 2 {
                        self.x += self.stack[n - 2];
                        self.y += self.stack[n - 1];
                    }
                    self.moveto(out);
                    self.stack.clear();
                }
                22 => {
                    // hmoveto
                    if let Some(&dx) = self.stack.last() {
                        self.x += dx;
                    }
                    self.moveto(out);
                    self.stack.clear();
                }
                4 => {
                    // vmoveto
                    if let Some(&dy) = self.stack.last() {
                        self.y += dy;
                    }
                    self.moveto(out);
                    self.stack.clear();
                }
                5 => {
                    // rlineto
                    let n = self.stack.len();
                    if n >= 2 {
                        self.x += self.stack[n - 2];
                        self.y += self.stack[n - 1];
                        out.line_to(self.x, self.y);
                    }
                    self.stack.clear();
                }
                6 => {
                    // hlineto
                    if let Some(&dx) = self.stack.last() {
                        self.x += dx;
                        out.line_to(self.x, self.y);
                    }
                    self.stack.clear();
                }
                7 => {
                    // vlineto
                    if let Some(&dy) = self.stack.last() {
                        self.y += dy;
                        out.line_to(self.x, self.y);
                    }
                    self.stack.clear();
                }
                8 => {
                    // rrcurveto: dx1 dy1 dx2 dy2 dx3 dy3
                    if self.stack.len() >= 6 {
                        let s = &self.stack[self.stack.len() - 6..];
                        self.curve(out, [(s[0], s[1]), (s[2], s[3]), (s[4], s[5])]);
                    }
                    self.stack.clear();
                }
                30 => {
                    // vhcurveto: dy1 dx2 dy2 dx3
                    if self.stack.len() >= 4 {
                        let s = &self.stack[self.stack.len() - 4..];
                        self.curve(out, [(0.0, s[0]), (s[1], s[2]), (s[3], 0.0)]);
                    }
                    self.stack.clear();
                }
                31 => {
                    // hvcurveto: dx1 dx2 dy2 dy3
                    if self.stack.len() >= 4 {
                        let s = &self.stack[self.stack.len() - 4..];
                        self.curve(out, [(s[0], 0.0), (s[1], s[2]), (0.0, s[3])]);
                    }
                    self.stack.clear();
                }
                1 | 3 => {
                    // hstem / vstem: ignore
                    self.stack.clear();
                }
                10 => {
                    // callsubr
                    if let Some(idx) = self.stack.pop() {
                        let idx = idx as i64;
                        if idx >= 0 && (idx as usize) < self.subrs.len() {
                            let sub = self.subrs[idx as usize].clone();
                            if self.exec(&sub, out) {
                                self.depth -= 1;
                                return true;
                            }
                        }
                    }
                }
                11 => {
                    // return
                    self.depth -= 1;
                    return false;
                }
                14 => {
                    // endchar
                    out.close();
                    self.depth -= 1;
                    return true;
                }
                12 => {
                    // escape
                    let b2 = *cs.get(i).unwrap_or(&0);
                    i += 1;
                    match b2 {
                        0..=2 => {
                            // dotsection / vstem3 / hstem3: ignore
                            self.stack.clear();
                        }
                        6 => {
                            // seac: asb adx ady bchar achar
                            if self.stack.len() >= 5 {
                                let s = self.stack.clone();
                                let n = s.len();
                                self.seac(out, s[n - 5], s[n - 4], s[n - 3], s[n - 2] as i32, s[n - 1] as i32);
                            }
                            self.stack.clear();
                            self.depth -= 1;
                            return true;
                        }
                        7 => {
                            // sbw
                            if self.stack.len() >= 4 {
                                self.sbx = self.stack[0];
                                self.x = self.stack[0];
                                self.y = self.stack[1];
                            }
                            self.stack.clear();
                        }
                        12 => {
                            // div
                            let n = self.stack.len();
                            if n >= 2 {
                                let a = self.stack[n - 2];
                                let bb = self.stack[n - 1];
                                self.stack.truncate(n - 2);
                                self.stack.push(if bb != 0.0 { a / bb } else { 0.0 });
                            }
                        }
                        16 => {
                            // callothersubr
                            self.callothersubr(out);
                        }
                        17 => {
                            // pop: PS stack -> operand stack
                            let v = self.ps_stack.pop().unwrap_or(0.0);
                            self.stack.push(v);
                        }
                        33 => {
                            // setcurrentpoint
                            if self.stack.len() >= 2 {
                                self.x = self.stack[0];
                                self.y = self.stack[1];
                            }
                            self.stack.clear();
                        }
                        _ => {
                            self.stack.clear();
                        }
                    }
                }
                _ => {
                    self.stack.clear();
                }
            }
        }
        self.depth -= 1;
        false
    }

    fn moveto(&mut self, out: &mut ContourBuilder) {
        if self.in_flex {
            self.flex_pts.push((self.x, self.y));
        } else {
            out.move_to(self.x, self.y);
        }
    }

    fn curve(&mut self, out: &mut ContourBuilder, deltas: [(f64, f64); 3]) {
        let (dx1, dy1) = deltas[0];
        let (dx2, dy2) = deltas[1];
        let (dx3, dy3) = deltas[2];
        let x1 = self.x + dx1;
        let y1 = self.y + dy1;
        let x2 = x1 + dx2;
        let y2 = y1 + dy2;
        let x3 = x2 + dx3;
        let y3 = y2 + dy3;
        out.curve_to(x1, y1, x2, y2, x3, y3);
        self.x = x3;
        self.y = y3;
    }

    /// Handle `callothersubr`: flex (0/1/2) and hint replacement (3). Others push
    /// their args back for subsequent `pop`s.
    fn callothersubr(&mut self, out: &mut ContourBuilder) {
        let othersubr = self.stack.pop().unwrap_or(-1.0) as i64;
        let nargs = self.stack.pop().unwrap_or(0.0) as i64;
        let nargs = nargs.max(0) as usize;
        let mut args = Vec::with_capacity(nargs);
        for _ in 0..nargs {
            args.push(self.stack.pop().unwrap_or(0.0));
        }
        args.reverse();
        match othersubr {
            1 => {
                // Start flex: begin collecting the 7 reference points.
                self.in_flex = true;
                self.flex_pts.clear();
            }
            2 => {
                // Collect flex point (the preceding rmoveto pushed it).
            }
            0 => {
                // End flex: emit two curves from the 7 collected points (point 0 is
                // the reference point; 1-3 and 4-6 are the two cubic segments). The
                // collected points are absolute glyph-space coordinates.
                self.in_flex = false;
                if self.flex_pts.len() >= 7 {
                    let p = self.flex_pts.clone();
                    out.curve_to(p[1].0, p[1].1, p[2].0, p[2].1, p[3].0, p[3].1);
                    out.curve_to(p[4].0, p[4].1, p[5].0, p[5].1, p[6].0, p[6].1);
                    self.x = p[6].0;
                    self.y = p[6].1;
                }
                // Return the end point for the following `pop pop setcurrentpoint`.
                self.ps_stack.push(self.y);
                self.ps_stack.push(self.x);
                self.flex_pts.clear();
            }
            3 => {
                // Hint replacement: return subr# 3 for the following `pop; callsubr`.
                self.ps_stack.push(3.0);
            }
            _ => {
                // Unknown: make args available to following pops in reverse order.
                for a in args.into_iter().rev() {
                    self.ps_stack.push(a);
                }
            }
        }
    }

    /// seac: compose an accented glyph from base `bchar` + accent `achar`, both
    /// referenced by StandardEncoding code.
    fn seac(&mut self, out: &mut ContourBuilder, asb: f64, adx: f64, ady: f64, bchar: i32, achar: i32) {
        let bname = std_name(bchar);
        let aname = std_name(achar);
        if let Some(name) = bname {
            if let Some(cs) = self.glyphs.get(name).cloned() {
                let mut sub = Interp {
                    stack: Vec::new(),
                    ps_stack: Vec::new(),
                    x: 0.0,
                    y: 0.0,
                    sbx: 0.0,
                    flex_pts: Vec::new(),
                    in_flex: false,
                    subrs: self.subrs,
                    glyphs: self.glyphs,
                    depth: self.depth,
                };
                sub.exec(&cs, out);
            }
        }
        if let Some(name) = aname {
            if let Some(cs) = self.glyphs.get(name).cloned() {
                // The accent's own `hsbw` resets the current point to its side
                // bearing, so seeding x/y here cannot place it. Interpret the
                // accent at its natural origin, then translate the finished
                // contours by `sbx + adx - asb` / `ady`: per TN #5015 `asb` is the
                // accent's own side bearing, which its `hsbw` re-applies, so it
                // must be subtracted out.
                let dx = self.sbx + adx - asb;
                let mut acc = ContourBuilder::new();
                let mut sub = Interp {
                    stack: Vec::new(),
                    ps_stack: Vec::new(),
                    x: 0.0,
                    y: 0.0,
                    sbx: 0.0,
                    flex_pts: Vec::new(),
                    in_flex: false,
                    subrs: self.subrs,
                    glyphs: self.glyphs,
                    depth: self.depth,
                };
                sub.exec(&cs, &mut acc);
                for c in acc.finish() {
                    out.add_contour(c.into_iter().map(|(px, py)| (px + dx, py + ady)).collect());
                }
            }
        }
    }
}

fn std_name(code: i32) -> Option<&'static str> {
    if !(0..=255).contains(&code) {
        return None;
    }
    STANDARD_ENCODING.iter().find(|(c, _)| *c as i32 == code).map(|(_, n)| *n)
}

pub(crate) fn parse(data: &[u8]) -> Option<Type1Font> {
    // The cleartext portion precedes `eexec`.
    let clear_end = find(data, b"eexec").unwrap_or(data.len());
    let cleartext = &data[..clear_end];
    let encoding = parse_encoding(cleartext);
    let font_matrix = parse_font_matrix(cleartext);

    let dec = extract_eexec(data)?;
    let (subrs, raw_glyphs) = parse_private(&dec);
    if raw_glyphs.is_empty() {
        return None;
    }

    let mut glyphs = HashMap::with_capacity(raw_glyphs.len());
    for (name, cs) in &raw_glyphs {
        let mut cb = ContourBuilder::new();
        run_charstring(cs, &subrs, &raw_glyphs, &mut cb);
        glyphs.insert(name.clone(), cb.finish());
    }

    Some(Type1Font { glyphs, encoding, font_matrix })
}

/// Adobe StandardEncoding: code -> glyph name (used for the `StandardEncoding`
/// shorthand and seac accent composition). Covers the printable Latin range.
pub(crate) static STANDARD_ENCODING: &[(u8, &str)] = &[
    (32, "space"), (33, "exclam"), (34, "quotedbl"), (35, "numbersign"), (36, "dollar"),
    (37, "percent"), (38, "ampersand"), (39, "quoteright"), (40, "parenleft"), (41, "parenright"),
    (42, "asterisk"), (43, "plus"), (44, "comma"), (45, "hyphen"), (46, "period"),
    (47, "slash"), (48, "zero"), (49, "one"), (50, "two"), (51, "three"), (52, "four"),
    (53, "five"), (54, "six"), (55, "seven"), (56, "eight"), (57, "nine"), (58, "colon"),
    (59, "semicolon"), (60, "less"), (61, "equal"), (62, "greater"), (63, "question"),
    (64, "at"), (65, "A"), (66, "B"), (67, "C"), (68, "D"), (69, "E"), (70, "F"), (71, "G"),
    (72, "H"), (73, "I"), (74, "J"), (75, "K"), (76, "L"), (77, "M"), (78, "N"), (79, "O"),
    (80, "P"), (81, "Q"), (82, "R"), (83, "S"), (84, "T"), (85, "U"), (86, "V"), (87, "W"),
    (88, "X"), (89, "Y"), (90, "Z"), (91, "bracketleft"), (92, "backslash"), (93, "bracketright"),
    (94, "asciicircum"), (95, "underscore"), (96, "quoteleft"), (97, "a"), (98, "b"), (99, "c"),
    (100, "d"), (101, "e"), (102, "f"), (103, "g"), (104, "h"), (105, "i"), (106, "j"),
    (107, "k"), (108, "l"), (109, "m"), (110, "n"), (111, "o"), (112, "p"), (113, "q"),
    (114, "r"), (115, "s"), (116, "t"), (117, "u"), (118, "v"), (119, "w"), (120, "x"),
    (121, "y"), (122, "z"), (123, "braceleft"), (124, "bar"), (125, "braceright"), (126, "asciitilde"),
    (161, "exclamdown"), (162, "cent"), (163, "sterling"), (164, "fraction"), (165, "yen"),
    (166, "florin"), (167, "section"), (168, "currency"), (169, "quotesingle"), (170, "quotedblleft"),
    (171, "guillemotleft"), (172, "guilsinglleft"), (173, "guilsinglright"), (174, "fi"), (175, "fl"),
    (177, "endash"), (178, "dagger"), (179, "daggerdbl"), (180, "periodcentered"), (182, "paragraph"),
    (183, "bullet"), (184, "quotesinglbase"), (185, "quotedblbase"), (186, "quotedblright"),
    (187, "guillemotright"), (188, "ellipsis"), (189, "perthousand"), (191, "questiondown"),
    (193, "grave"), (194, "acute"), (195, "circumflex"), (196, "tilde"), (197, "macron"),
    (198, "breve"), (199, "dotaccent"), (200, "dieresis"), (202, "ring"), (203, "cedilla"),
    (205, "hungarumlaut"), (206, "ogonek"), (207, "caron"), (208, "emdash"), (225, "AE"),
    (227, "ordfeminine"), (232, "Lslash"), (233, "Oslash"), (234, "OE"), (235, "ordmasculine"),
    (241, "ae"), (245, "dotlessi"), (248, "lslash"), (249, "oslash"), (250, "oe"), (251, "germandbls"),
];
