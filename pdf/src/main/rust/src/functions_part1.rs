impl PdfFunction {
    /// Parse a function object (reference, dict, stream, or array-of-functions).
    pub(crate) fn parse(doc: &Document, obj: &Object) -> Option<PdfFunction> {
        let mut budget = MAX_FN_NODES;
        PdfFunction::parse_at(doc, obj, 0, &mut budget)
    }

    fn parse_at(
        doc: &Document,
        obj: &Object,
        depth: u32,
        budget: &mut usize,
    ) -> Option<PdfFunction> {
        if depth > MAX_FN_DEPTH || *budget == 0 {
            return None;
        }
        *budget -= 1;
        let resolved = deref(doc, obj)?;
        if let Object::Array(arr) = resolved {
            // Array of functions -> one output component each.
            let mut fns = Vec::new();
            for o in arr {
                if let Some(f) = PdfFunction::parse_at(doc, o, depth + 1, budget) {
                    fns.push(f);
                }
            }
            if fns.is_empty() {
                return None;
            }
            return Some(PdfFunction::Array(fns));
        }
        let (dict, stream_bytes): (&Dictionary, Option<Vec<u8>>) = match resolved {
            Object::Dictionary(d) => (d, None),
            Object::Stream(s) => (&s.dict, Some(stream_data_with_doc(doc, s))),
            _ => return None,
        };
        let ftype = dict.get(b"FunctionType").ok().and_then(num)? as i64;
        let domain_pairs = read_pairs(dict.get(b"Domain").ok());
        match ftype {
            0 => {
                let bytes = stream_bytes?;
                if bytes.len() > MAX_SAMPLED_BYTES {
                    return None;
                }
                let size: Vec<usize> = read_floats(dict.get(b"Size").ok())
                    .iter()
                    .map(|v| (*v as i64).max(0) as usize)
                    .collect();
                let bps = dict.get(b"BitsPerSample").ok().and_then(num)? as u32;
                let range = read_pairs(dict.get(b"Range").ok());
                if size.is_empty() || range.is_empty() || domain_pairs.is_empty() {
                    return None;
                }
                // 7.10.2 Table 39: BitsPerSample shall be one of these. An
                // out-of-set value (notably 0) makes the sample normaliser divide
                // by zero, and the resulting NaN survives the Range clamp and
                // poisons every output component.
                if !matches!(bps, 1 | 2 | 4 | 8 | 12 | 16 | 24 | 32) {
                    return None;
                }
                // Guard the 2^m corner interpolation and the flattened index maths.
                if size.len() > MAX_SAMPLED_INPUTS || range.len() > MAX_FN_OUTPUTS {
                    return None;
                }
                if size.contains(&0) {
                    return None;
                }
                let n_in = size.len();
                let n_out = range.len();
                let encode = {
                    let e = read_pairs(dict.get(b"Encode").ok());
                    if e.len() == n_in {
                        e
                    } else {
                        size.iter().map(|s| [0.0, (*s as f64 - 1.0).max(0.0)]).collect()
                    }
                };
                let decode = {
                    let d = read_pairs(dict.get(b"Decode").ok());
                    if d.len() == n_out { d } else { range.clone() }
                };
                Some(PdfFunction::Sampled {
                    domain: domain_pairs,
                    range,
                    size,
                    bps,
                    encode,
                    decode,
                    samples: bytes,
                    n_in,
                    n_out,
                })
            }
            2 => {
                let mut c0 = read_floats(dict.get(b"C0").ok());
                let mut c1 = read_floats(dict.get(b"C1").ok());
                if c0.is_empty() { c0 = vec![0.0]; }
                if c1.is_empty() { c1 = vec![1.0]; }
                // j is over-determined: 7.10.3 Table 40 makes C0/C1 arrays of j
                // numbers, and Table 38 requires /Range (when present) to hold 2*j
                // entries. Materialise all of them at the widest arity so the scalar
                // C0/C1 defaults broadcast, instead of pinning j to 1 and handing
                // the target colour space too few components. The padding values are
                // the same per-index defaults `eval` already applied, so this is a
                // no-op for every well-formed function.
                let range = read_pairs(dict.get(b"Range").ok());
                let j = c0.len().max(c1.len()).max(range.len()).min(MAX_FN_OUTPUTS);
                c0.resize(j, 0.0);
                c1.resize(j, 1.0);
                let n = dict.get(b"N").ok().and_then(num).unwrap_or(1.0);
                let domain = domain_pairs.first().copied().unwrap_or([0.0, 1.0]);
                Some(PdfFunction::Exponential { domain, range, c0, c1, n })
            }
            3 => {
                let funcs_obj = deref(doc, dict.get(b"Functions").ok()?)?;
                let funcs_arr = funcs_obj.as_array().ok()?;
                let mut functions = Vec::new();
                for o in funcs_arr {
                    functions.push(PdfFunction::parse_at(doc, o, depth + 1, budget)?);
                }
                // An empty /Functions array would make `eval` return an empty
                // vector, which downstream colour code cannot distinguish from a
                // one-component result. Reject it here so a parse failure is
                // reported as None instead.
                if functions.is_empty() {
                    return None;
                }
                let bounds = read_floats(dict.get(b"Bounds").ok());
                let encode = read_pairs(dict.get(b"Encode").ok());
                let range = read_pairs(dict.get(b"Range").ok());
                let domain = domain_pairs.first().copied().unwrap_or([0.0, 1.0]);
                Some(PdfFunction::Stitching { domain, range, functions, bounds, encode })
            }
            4 => {
                let bytes = stream_bytes?;
                let range = read_pairs(dict.get(b"Range").ok());
                let program = parse_ps_program(&bytes)?;
                Some(PdfFunction::PostScript { domain: domain_pairs, range, program })
            }
            _ => None,
        }
    }

    /// Evaluate the function, returning the output tuple.
    pub(crate) fn eval(&self, inputs: &[f64]) -> Vec<f64> {
        match self {
            PdfFunction::Exponential { domain, range, c0, c1, n } => {
                let t = clip(inputs.first().copied().unwrap_or(0.0), domain[0], domain[1]);
                // 7.10.3 constrains Domain so that t^N is defined, but a malformed
                // file can still reach negative t with a non-integer N, which gives
                // NaN and would poison every output component.
                let tn = if *n == 1.0 {
                    t
                } else {
                    let p = t.powf(*n);
                    if p.is_finite() { p } else { 0.0 }
                };
                let len = c0.len().max(c1.len());
                (0..len)
                    .map(|i| {
                        let a = c0.get(i).copied().unwrap_or(0.0);
                        let b = c1.get(i).copied().unwrap_or(1.0);
                        let v = a + (b - a) * tn;
                        match range.get(i) {
                            Some(r) => clip(v, r[0], r[1]),
                            None => v,
                        }
                    })
                    .collect()
            }
            PdfFunction::Stitching { domain, range, functions, bounds, encode } => {
                if functions.is_empty() {
                    return Vec::new();
                }
                let x = clip(inputs.first().copied().unwrap_or(0.0), domain[0], domain[1]);
                // Select sub-function k.
                let mut k = 0usize;
                while k < bounds.len() && x >= bounds[k] {
                    k += 1;
                }
                k = k.min(functions.len() - 1);
                // Sub-domain for k.
                let lo = if k == 0 { domain[0] } else { bounds[k - 1] };
                let hi = if k < bounds.len() { bounds[k] } else { domain[1] };
                let (e0, e1) = encode.get(k).map(|e| (e[0], e[1])).unwrap_or((0.0, 1.0));
                let xe = if (hi - lo).abs() < 1e-12 {
                    e0
                } else {
                    e0 + (x - lo) * (e1 - e0) / (hi - lo)
                };
                let mut out = functions[k].eval(&[xe]);
                for (i, v) in out.iter_mut().enumerate() {
                    if let Some(r) = range.get(i) {
                        *v = clip(*v, r[0], r[1]);
                    }
                }
                out
            }
            PdfFunction::Sampled { .. } => self.eval_sampled(inputs),
            PdfFunction::PostScript { domain, range, program } => {
                let clamped: Vec<f64> = inputs
                    .iter()
                    .enumerate()
                    .map(|(i, v)| {
                        if let Some(d) = domain.get(i) {
                            clip(*v, d[0], d[1])
                        } else {
                            *v
                        }
                    })
                    .collect();
                let mut out = eval_ps(program, &clamped).unwrap_or_default();
                if !range.is_empty() {
                    // Keep only the last n_out values and clamp to range.
                    let n_out = range.len();
                    if out.len() > n_out {
                        out = out.split_off(out.len() - n_out);
                    }
                    for (i, v) in out.iter_mut().enumerate() {
                        if let Some(r) = range.get(i) {
                            *v = clip(*v, r[0], r[1]);
                        }
                    }
                }
                out
            }
            PdfFunction::Array(fns) => {
                let mut out = Vec::new();
                for f in fns {
                    out.extend(f.eval(inputs));
                }
                out
            }
        }
    }

    /// Sample a one-in/one-out function into a 256-entry lookup table over the input
    /// range [0,1], with both index and value in 0..=255.
    ///
    /// This is the form a transfer function has to take to be usable per-pixel: it is
    /// evaluated once here instead of once per mask sample, and it can be carried over
    /// the wire and applied by a GPU shader or a bitmap remap, neither of which can run
    /// a PostScript calculator. Only the FIRST output component is used, which is what
    /// §11.6.5.2's `/TR` and §11.7.4's transfer functions specify.
    pub(crate) fn to_lut256(&self) -> [u8; 256] {
        let mut lut = [0u8; 256];
        for (i, slot) in lut.iter_mut().enumerate() {
            let v = self.eval(&[i as f64 / 255.0]).first().copied().unwrap_or(0.0);
            // A NaN from a malformed function must not become an arbitrary byte;
            // `clamp` propagates NaN, so test for it explicitly.
            *slot = if v.is_finite() {
                (v.clamp(0.0, 1.0) * 255.0).round() as u8
            } else {
                i as u8
            };
        }
        lut
    }

    fn eval_sampled(&self, inputs: &[f64]) -> Vec<f64> {
        let (domain, range, size, bps, encode, decode, samples, n_in, n_out) = match self {
            PdfFunction::Sampled {
                domain, range, size, bps, encode, decode, samples, n_in, n_out,
            } => (domain, range, size, *bps, encode, decode, samples, *n_in, *n_out),
            _ => return Vec::new(),
        };
        if n_in == 0 || n_out == 0 {
            return Vec::new();
        }
        // Encode each input to a continuous grid coordinate e in [0, size-1].
        let mut e = Vec::with_capacity(n_in);
        for (i, sz) in size.iter().enumerate().take(n_in) {
            let d = domain.get(i).copied().unwrap_or([0.0, 1.0]);
            let enc = encode.get(i).copied().unwrap_or([0.0, (*sz as f64 - 1.0).max(0.0)]);
            let x = clip(inputs.get(i).copied().unwrap_or(0.0), d[0], d[1]);
            let ev = if (d[1] - d[0]).abs() < 1e-12 {
                enc[0]
            } else {
                enc[0] + (x - d[0]) * (enc[1] - enc[0]) / (d[1] - d[0])
            };
            e.push(clip(ev, 0.0, (*sz as f64 - 1.0).max(0.0)));
        }
        // Multilinear interpolation over the 2^n_in surrounding grid corners.
        let max_val = if bps >= 32 { u32::MAX as f64 } else { ((1u64 << bps) - 1) as f64 };
        let corners = 1usize << n_in;
        let mut out = vec![0.0f64; n_out];
        for corner in 0..corners {
            let mut weight = 1.0;
            let mut grid = Vec::with_capacity(n_in);
            for i in 0..n_in {
                let floor = e[i].floor();
                let frac = e[i] - floor;
                let hi_bit = (corner >> i) & 1 == 1;
                let (idx, w) = if hi_bit {
                    ((floor as usize + 1).min(size[i].saturating_sub(1)), frac)
                } else {
                    (floor as usize, 1.0 - frac)
                };
                weight *= w;
                grid.push(idx);
            }
            if weight == 0.0 {
                continue;
            }
            // Flatten grid index (first dimension varies fastest per spec).
            let mut flat = 0usize;
            let mut stride = 1usize;
            let mut ok = true;
            for i in 0..n_in {
                match grid[i].checked_mul(stride).and_then(|v| flat.checked_add(v)) {
                    Some(f) => flat = f,
                    None => { ok = false; break; }
                }
                // Only advance the stride when another dimension follows, so a
                // harmless overflow on the final axis cannot discard a valid corner.
                if i + 1 < n_in {
                    match stride.checked_mul(size[i].max(1)) {
                        Some(s) => stride = s,
                        None => { ok = false; break; }
                    }
                }
            }
            if !ok {
                continue;
            }
            for (j, o) in out.iter_mut().enumerate() {
                let sample_idx = flat * n_out + j;
                let raw = read_sample(samples, sample_idx, bps);
                *o += weight * (raw / max_val);
            }
        }
        // Decode from [0,1] to Decode range, then clamp to Range.
        for (j, o) in out.iter_mut().enumerate() {
            let dec = decode.get(j).copied().unwrap_or([0.0, 1.0]);
            *o = dec[0] + *o * (dec[1] - dec[0]);
            if let Some(r) = range.get(j) {
                *o = clip(*o, r[0], r[1]);
            }
        }
        out
    }
}

/// Read the `idx`-th packed sample of `bps` bits (big-endian bit order). Returns
/// 0 when the sample lies wholly or partly past the end of `data`: a truncated
/// sample stream must not yield a partially-shifted value, which looks plausible
/// but is wrong.
fn read_sample(data: &[u8], idx: usize, bps: u32) -> f64 {
    let bit_pos = idx as u64 * bps as u64;
    let end_bit = bit_pos + bps as u64;
    if end_bit > (data.len() as u64).saturating_mul(8) {
        return 0.0;
    }
    let mut value: u64 = 0;
    for b in 0..bps as u64 {
        let bit = bit_pos + b;
        let byte = (bit / 8) as usize;
        if byte >= data.len() {
            break;
        }
        let bit_in_byte = 7 - (bit % 8) as u32;
        let bit_val = (data[byte] >> bit_in_byte) & 1;
        value = (value << 1) | bit_val as u64;
    }
    value as f64
}

// ---------------------------------------------------------------------------
// Type 4 PostScript calculator
// ---------------------------------------------------------------------------

fn parse_ps_program(bytes: &[u8]) -> Option<Vec<PsToken>> {
    let mut toks = Vec::new();
    let mut pos = 0usize;
    let mut count = 0usize;
    // Skip to first '{'.
    while pos < bytes.len() && bytes[pos] != b'{' {
        pos += 1;
    }
    if pos >= bytes.len() {
        return None;
    }
    pos += 1; // consume the outer '{'
    parse_ps_block(bytes, &mut pos, &mut toks, &mut count)?;
    Some(toks)
}

fn parse_ps_block(
    bytes: &[u8],
    pos: &mut usize,
    out: &mut Vec<PsToken>,
    count: &mut usize,
) -> Option<()> {
    while *pos < bytes.len() {
        if *count > MAX_PS_TOKENS {
            return None;
        }
        let c = bytes[*pos];
        if c.is_ascii_whitespace() {
            *pos += 1;
            continue;
        }
        if c == b'}' {
            *pos += 1;
            return Some(());
        }
        if c == b'{' {
            *pos += 1;
            let mut inner = Vec::new();
            parse_ps_block(bytes, pos, &mut inner, count)?;
            out.push(PsToken::Proc(inner));
            *count += 1;
            continue;
        }
        if c == b'%' {
            // comment to end of line
            while *pos < bytes.len() && bytes[*pos] != b'\n' && bytes[*pos] != b'\r' {
                *pos += 1;
            }
            continue;
        }
        // Read a token (number or operator name).
        let start = *pos;
        while *pos < bytes.len() {
            let ch = bytes[*pos];
            if ch.is_ascii_whitespace() || ch == b'{' || ch == b'}' || ch == b'%' {
                break;
            }
            *pos += 1;
        }
        let word = &bytes[start..*pos];
        if word.is_empty() {
            *pos += 1;
            continue;
        }
        let s = std::str::from_utf8(word).ok()?;
        if let Ok(n) = s.parse::<f64>() {
            out.push(PsToken::Num(n));
        } else if let Some(op) = parse_ps_op(s) {
            out.push(PsToken::Op(op));
        } else {
            // Unknown operator: ignore (best-effort).
        }
        *count += 1;
    }
    Some(())
}
