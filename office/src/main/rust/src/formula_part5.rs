impl<'a> Evaluator<'a> {
    fn t_nums(&mut self, t: &ArgThunk) -> R<Vec<f64>> {
        let vs = self.t_values(t)?;
        Ok(vs.iter().filter_map(|v| self.num_or_null(v)).collect())
    }

    fn vals_a(&mut self, a: &[ArgThunk]) -> R<Vec<f64>> {
        let vals = self.all_vals(a)?;
        let mut out = Vec::new();
        for v in vals {
            match v {
                Value::Num(x) => out.push(x),
                Value::Bool(b) => out.push(if b { 1.0 } else { 0.0 }),
                Value::Str(_) => out.push(0.0),
                Value::Blank => {}
                Value::Err(c) => return Err(EvalError(c)),
                // all_vals already expands arrays; a nested Arr can only arrive
                // via a cell holding a spilled result — count its first element.
                Value::Arr(vs) => {
                    let first = Value::first_of(&vs);
                    match first {
                        Value::Num(x) => out.push(x),
                        Value::Bool(b) => out.push(if b { 1.0 } else { 0.0 }),
                        Value::Str(_) => out.push(0.0),
                        Value::Blank => {}
                        Value::Err(c) => return Err(EvalError(c)),
                        Value::Arr(_) => {}
                    }
                }
            }
        }
        Ok(out)
    }

    fn now_serial(&self, with_time: bool) -> f64 {
        let raw = (self.wb.now_millis as f64 + EPOCH_OFFSET_DAYS as f64 * MS_PER_DAY) / MS_PER_DAY;
        if with_time {
            raw
        } else {
            raw.floor()
        }
    }

    fn next_rand(&self) -> f64 {
        let mut r = self.wb.rng.borrow_mut();
        let mut x = *r;
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        *r = x;
        (x >> 11) as f64 / (1u64 << 53) as f64
    }

    fn pair(&mut self, a: &[ArgThunk], i: usize, j: usize) -> R<(Vec<f64>, Vec<f64>)> {
        let x = self.t_nums(&a[i])?;
        let y = self.t_nums(&a[j])?;
        let m = x.len().min(y.len());
        Ok((x[..m].to_vec(), y[..m].to_vec()))
    }

    fn apply_function(&mut self, name: &str, a: &[ArgThunk]) -> R<Value> {
        match name {
            // ---- math / statistics ----
            "SUM" => Ok(Value::Num(self.all_nums(a)?.iter().sum())),
            "AVERAGE" => {
                let ns = self.all_nums(a)?;
                if ns.is_empty() {
                    Err(ferr("#DIV/0!"))
                } else {
                    Ok(Value::Num(avg_of(&ns)))
                }
            }
            "MIN" => {
                let ns = self.all_nums(a)?;
                Ok(Value::Num(pipe_min(&ns)))
            }
            "MAX" => {
                let ns = self.all_nums(a)?;
                Ok(Value::Num(pipe_max(&ns)))
            }
            "COUNT" => Ok(Value::Num(self.all_nums(a)?.len() as f64)),
            "COUNTA" => {
                let vs = self.all_vals(a)?;
                Ok(Value::Num(vs.iter().filter(|v| **v != Value::Blank).count() as f64))
            }
            "COUNTBLANK" => {
                let vs = self.all_vals(a)?;
                Ok(Value::Num(vs.iter().filter(|v| **v == Value::Blank).count() as f64))
            }
            "PRODUCT" => {
                let ns = self.all_nums(a)?;
                if ns.is_empty() {
                    Ok(Value::Num(0.0))
                } else {
                    Ok(Value::Num(ns.iter().fold(1.0, |x, y| x * y)))
                }
            }
            "ABS" => Ok(Value::Num(self.t_num(&a[0])?.abs())),
            "SQRT" => Ok(Value::Num(self.t_num(&a[0])?.sqrt())),
            "POWER" => Ok(Value::Num(self.t_num(&a[0])?.powf(self.t_num(&a[1])?))),
            "MOD" => {
                let b = self.t_num(&a[1])?;
                if b == 0.0 {
                    Err(ferr("#DIV/0!"))
                } else {
                    let x = self.t_num(&a[0])?;
                    Ok(Value::Num(x - (x / b).floor() * b))
                }
            }
            "INT" => Ok(Value::Num(self.t_num(&a[0])?.floor())),
            "TRUNC" => Ok(Value::Num(self.t_num(&a[0])?.trunc())),
            "SIGN" => Ok(Value::Num(ksign(self.t_num(&a[0])?))),
            "EXP" => Ok(Value::Num(self.t_num(&a[0])?.exp())),
            "LN" => Ok(Value::Num(self.t_num(&a[0])?.ln())),
            "LOG10" => Ok(Value::Num(self.t_num(&a[0])?.log10())),
            "LOG" => {
                let x = self.t_num(&a[0])?;
                let base = if a.len() > 1 { self.t_num(&a[1])? } else { 10.0 };
                Ok(Value::Num(x.ln() / base.ln()))
            }
            "ROUND" => {
                let d = if a.len() > 1 { self.t_num(&a[1])? as i32 } else { 0 };
                let f = 10f64.powi(d);
                let x = self.t_num(&a[0])?;
                Ok(Value::Num(math_round(x * f) as f64 / f))
            }
            "ROUNDUP" => {
                let d = if a.len() > 1 { self.t_num(&a[1])? as i32 } else { 0 };
                let f = 10f64.powi(d);
                let x = self.t_num(&a[0])?;
                Ok(Value::Num((x.abs() * f).ceil() / f * (if x < 0.0 { -1.0 } else { 1.0 })))
            }
            "ROUNDDOWN" => {
                let d = if a.len() > 1 { self.t_num(&a[1])? as i32 } else { 0 };
                let f = 10f64.powi(d);
                let x = self.t_num(&a[0])?;
                Ok(Value::Num((x.abs() * f).floor() / f * (if x < 0.0 { -1.0 } else { 1.0 })))
            }
            "CEILING" => {
                let step = if a.len() > 1 { self.t_num(&a[1])? } else { 1.0 };
                if step == 0.0 {
                    Ok(Value::Num(0.0))
                } else {
                    Ok(Value::Num((self.t_num(&a[0])? / step).ceil() * step))
                }
            }
            "FLOOR" => {
                let step = if a.len() > 1 { self.t_num(&a[1])? } else { 1.0 };
                if step == 0.0 {
                    Ok(Value::Num(0.0))
                } else {
                    Ok(Value::Num((self.t_num(&a[0])? / step).floor() * step))
                }
            }
            "MEDIAN" => {
                let mut ns = self.all_nums(a)?;
                if ns.is_empty() {
                    return Err(ferr("#NUM!"));
                }
                ns.sort_by(|x, y| x.total_cmp(y));
                let sz = ns.len();
                Ok(Value::Num(if sz % 2 == 1 {
                    ns[sz / 2]
                } else {
                    (ns[sz / 2 - 1] + ns[sz / 2]) / 2.0
                }))
            }
            "STDEV" => {
                let ns = self.all_nums(a)?;
                if ns.len() < 2 {
                    Err(ferr("#DIV/0!"))
                } else {
                    let m = avg_of(&ns);
                    Ok(Value::Num(
                        (ns.iter().map(|x| (x - m).powi(2)).sum::<f64>() / (ns.len() - 1) as f64).sqrt(),
                    ))
                }
            }
            "VAR" => {
                let ns = self.all_nums(a)?;
                if ns.len() < 2 {
                    Err(ferr("#DIV/0!"))
                } else {
                    let m = avg_of(&ns);
                    Ok(Value::Num(
                        ns.iter().map(|x| (x - m).powi(2)).sum::<f64>() / (ns.len() - 1) as f64,
                    ))
                }
            }

            // ---- conditional aggregation ----
            "SUMIF" => self.conditional_agg(a, true, |vals| vals.iter().sum()),
            "AVERAGEIF" => self.conditional_agg(a, true, |vals| {
                if vals.is_empty() {
                    f64::NAN
                } else {
                    avg_of(vals)
                }
            }),
            "COUNTIF" => {
                let rng = self.t_arg(&a[0])?;
                let crit = self.t_value(&a[1])?;
                let mut count = 0;
                let cells = self.for_each_range_cell(&rng)?;
                for cv in cells {
                    if self.matches_criteria(&cv, &crit)? {
                        count += 1;
                    }
                }
                Ok(Value::Num(count as f64))
            }

            // ---- logical ----
            "IF" => {
                if self.t_truthy(&a[0])? {
                    self.t_value(&a[1])
                } else if a.len() > 2 {
                    self.t_value(&a[2])
                } else {
                    Ok(Value::Bool(false))
                }
            }
            "IFS" => {
                let mut i = 0;
                while i + 1 < a.len() {
                    if self.t_truthy(&a[i])? {
                        return self.t_value(&a[i + 1]);
                    }
                    i += 2;
                }
                Err(ferr("#N/A"))
            }
            "IFERROR" => match self.t_value(&a[0]) {
                Ok(Value::Err(_)) => self.t_value(&a[1]),
                Ok(r) => Ok(r),
                Err(_) => self.t_value(&a[1]),
            },
            "IFNA" => match self.t_value(&a[0]) {
                Ok(Value::Err(c)) if c == "#N/A" => self.t_value(&a[1]),
                Ok(r) => Ok(r),
                Err(e) if e.0 == "#N/A" => self.t_value(&a[1]),
                Err(e) => Err(e),
            },
            "AND" => {
                let vals = self.all_vals(a)?;
                let f: Vec<bool> = vals.iter().filter_map(bool_or_null).collect();
                Ok(Value::Bool(!f.is_empty() && f.iter().all(|b| *b)))
            }
            "OR" => {
                let vals = self.all_vals(a)?;
                let f: Vec<bool> = vals.iter().filter_map(bool_or_null).collect();
                Ok(Value::Bool(f.iter().any(|b| *b)))
            }
            "NOT" => Ok(Value::Bool(!self.t_truthy(&a[0])?)),
            "ISERROR" => Ok(Value::Bool(self.is_error(&a[0]).is_some())),
            "ISERR" => {
                let e = self.is_error(&a[0]);
                Ok(Value::Bool(e.is_some() && e.as_deref() != Some("#N/A")))
            }
            "ISNA" => Ok(Value::Bool(self.is_error(&a[0]).as_deref() == Some("#N/A"))),
            "ISNUMBER" => Ok(Value::Bool(matches!(self.safe_value(&a[0]), Value::Num(_)))),
            "ISTEXT" => Ok(Value::Bool(matches!(self.safe_value(&a[0]), Value::Str(_)))),
            "ISBLANK" => Ok(Value::Bool(self.safe_value(&a[0]) == Value::Blank)),
            "ISLOGICAL" => Ok(Value::Bool(matches!(self.safe_value(&a[0]), Value::Bool(_)))),
            "NA" => Err(ferr("#N/A")),

            // ---- lookup ----
            "CHOOSE" => {
                let idx = self.t_num(&a[0])? as i32;
                if idx < 1 || idx as usize >= a.len() {
                    Err(ferr("#VALUE!"))
                } else {
                    self.t_value(&a[idx as usize])
                }
            }
            "VLOOKUP" => self.lookup(a, false),
            "HLOOKUP" => self.lookup(a, true),
            "MATCH" => self.match_fn(a),
            "INDEX" => self.index_fn(a),

            // ---- text ----
            "LEN" => Ok(Value::Num(self.t_str(&a[0])?.chars().count() as f64)),
            "LEFT" => {
                let s = self.t_str(&a[0])?;
                let k = if a.len() > 1 { self.t_num(&a[1])? as i32 } else { 1 }.max(0) as usize;
                Ok(Value::Str(s.chars().take(k).collect()))
            }
            "RIGHT" => {
                let s = self.t_str(&a[0])?;
                let k = if a.len() > 1 { self.t_num(&a[1])? as i32 } else { 1 }.max(0) as usize;
                let chars: Vec<char> = s.chars().collect();
                let start = chars.len().saturating_sub(k);
                Ok(Value::Str(chars[start..].iter().collect()))
            }
            "MID" => {
                let s: Vec<char> = self.t_str(&a[0])?.chars().collect();
                let start = (self.t_num(&a[1])? as i32 - 1).max(0) as usize;
                let len = (self.t_num(&a[2])? as i32).max(0) as usize;
                if start >= s.len() {
                    Ok(Value::Str(String::new()))
                } else {
                    let end = s.len().min(start + len);
                    Ok(Value::Str(s[start..end].iter().collect()))
                }
            }
            "UPPER" => Ok(Value::Str(self.t_str(&a[0])?.to_uppercase())),
            "LOWER" => Ok(Value::Str(self.t_str(&a[0])?.to_lowercase())),
            "TRIM" => {
                let s = self.t_str(&a[0])?;
                Ok(Value::Str(s.split_whitespace().collect::<Vec<_>>().join(" ")))
            }
            "PROPER" => {
                let s = self.t_str(&a[0])?;
                let out: Vec<String> = s
                    .split(' ')
                    .map(|w| {
                        let mut ch = w.chars();
                        match ch.next() {
                            Some(f) => {
                                f.to_uppercase().collect::<String>()
                                    + &ch.flat_map(|c| c.to_lowercase()).collect::<String>()
                            }
                            None => String::new(),
                        }
                    })
                    .collect();
                Ok(Value::Str(out.join(" ")))
            }
            "CONCATENATE" | "CONCAT" => {
                let vals = self.all_vals(a)?;
                let mut out = String::new();
                for v in vals {
                    out.push_str(&self.str_of(v)?);
                }
                Ok(Value::Str(out))
            }
            "REPT" => {
                let s = self.t_str(&a[0])?;
                let k = (self.t_num(&a[1])? as i32).max(0) as usize;
                Ok(Value::Str(s.repeat(k)))
            }
            "EXACT" => Ok(Value::Bool(self.t_str(&a[0])? == self.t_str(&a[1])?)),
            "FIND" => {
                let start = if a.len() > 2 { self.t_num(&a[2])? as i32 - 1 } else { 0 }.max(0) as usize;
                let needle = self.t_str(&a[0])?;
                let hay = self.t_str(&a[1])?;
                match index_of(&hay, &needle, start, false) {
                    Some(i) => Ok(Value::Num((i + 1) as f64)),
                    None => Err(ferr("#VALUE!")),
                }
            }
            "SEARCH" => {
                let start = if a.len() > 2 { self.t_num(&a[2])? as i32 - 1 } else { 0 }.max(0) as usize;
                let needle = self.t_str(&a[0])?;
                let hay = self.t_str(&a[1])?;
                match index_of(&hay, &needle, start, true) {
                    Some(i) => Ok(Value::Num((i + 1) as f64)),
                    None => Err(ferr("#VALUE!")),
                }
            }
            "SUBSTITUTE" => {
                let s = self.t_str(&a[0])?;
                let from = self.t_str(&a[1])?;
                let to = self.t_str(&a[2])?;
                Ok(Value::Str(if from.is_empty() { s } else { s.replace(&from, &to) }))
            }
            "REPLACE" => {
                let s: Vec<char> = self.t_str(&a[0])?.chars().collect();
                let start = ((self.t_num(&a[1])? as i32 - 1).max(0) as usize).min(s.len());
                let len = (self.t_num(&a[2])? as i32).max(0) as usize;
                let end = s.len().min(start + len);
                let repl = self.t_str(&a[3])?;
                let mut out: String = s[..start].iter().collect();
                out.push_str(&repl);
                out.extend(s[end..].iter());
                Ok(Value::Str(out))
            }
            "VALUE" => {
                let s = self.t_str(&a[0])?;
                parse_f64(&s).map(Value::Num).ok_or_else(|| ferr("#VALUE!"))
            }
            "TEXT" => {
                let v = self.t_num(&a[0])?;
                let f = self.t_str(&a[1])?;
                Ok(Value::Str(text_format(v, &f)))
            }

            _ => self.apply_function2(name, a),
        }
    }
}

// MIN/MAX with empty -> 0.0 (mirrors Kotlin minOrNull()?.let{...} ?: Num(0.0)).
fn pipe_min(ns: &[f64]) -> f64 {
    if ns.is_empty() {
        0.0
    } else {
        ns.iter().cloned().fold(f64::INFINITY, f64::min)
    }
}
fn pipe_max(ns: &[f64]) -> f64 {
    if ns.is_empty() {
        0.0
    } else {
        ns.iter().cloned().fold(f64::NEG_INFINITY, f64::max)
    }
}

fn bool_or_null(v: &Value) -> Option<bool> {
    match v {
        Value::Num(x) => Some(*x != 0.0),
        Value::Bool(b) => Some(*b),
        _ => None,
    }
}

/// UTF-16-agnostic char index-of (returns char index), optional case-insensitive.
fn index_of(hay: &str, needle: &str, start: usize, ignore_case: bool) -> Option<usize> {
    let h: Vec<char> = if ignore_case {
        hay.chars().map(|c| c.to_ascii_uppercase()).collect()
    } else {
        hay.chars().collect()
    };
    let n: Vec<char> = if ignore_case {
        needle.chars().map(|c| c.to_ascii_uppercase()).collect()
    } else {
        needle.chars().collect()
    };
    if n.is_empty() {
        return Some(start.min(h.len()));
    }
    if start > h.len() {
        return None;
    }
    let mut i = start;
    while i + n.len() <= h.len() {
        if h[i..i + n.len()] == n[..] {
            return Some(i);
        }
        i += 1;
    }
    None
}
