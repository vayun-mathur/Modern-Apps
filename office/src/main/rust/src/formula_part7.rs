impl<'a> Evaluator<'a> {
    fn apply_function3(&mut self, name: &str, a: &[ArgThunk]) -> R<Value> {
        match name {
            // ---- extended date ----
            "DAYS" => Ok(Value::Num(self.t_num(&a[0])?.floor() - self.t_num(&a[1])?.floor())),
            "DATEVALUE" => Ok(Value::Num(self.t_num(&a[0])?.floor())),
            "EDATE" => {
                let dt = serial_to_datetime(self.t_num(&a[0])?);
                let k = self.t_num(&a[1])? as i64;
                let (y2, m2, d2) = add_months(dt.year, dt.month, dt.day, k);
                Ok(Value::Num(date_serial(y2, m2, d2)))
            }
            "EOMONTH" => {
                let dt = serial_to_datetime(self.t_num(&a[0])?);
                let k = self.t_num(&a[1])? as i64;
                let (y2, m2, _) = add_months(dt.year, dt.month, 1, k);
                let last = days_in_month(y2, m2);
                Ok(Value::Num(date_serial(y2, m2, last)))
            }
            "WEEKNUM" => Ok(Value::Num(weeknum(self.t_num(&a[0])?) as f64)),
            "DATEDIF" => {
                let n0 = self.t_num(&a[0])?;
                let n1 = self.t_num(&a[1])?;
                let unit = self.t_str(&a[2])?.to_uppercase();
                let c1 = serial_to_datetime(n0);
                let c2 = serial_to_datetime(n1);
                // Complete months between the dates (Excel DATEDIF semantics):
                // the day-of-month must have been reached to count the month.
                let complete_months = {
                    let mut m = (c2.year - c1.year) * 12 + (c2.month - c1.month);
                    if c2.day < c1.day {
                        m -= 1;
                    }
                    m
                };
                match unit.as_str() {
                    "D" => Ok(Value::Num(n1.floor() - n0.floor())),
                    "M" => Ok(Value::Num(complete_months as f64)),
                    "Y" => Ok(Value::Num(complete_months.div_euclid(12) as f64)),
                    "MD" => {
                        let d1 = c1.day;
                        let d2 = c2.day;
                        let mut diff = d2 - d1;
                        if diff < 0 {
                            let (py, pm, _) = add_months(c2.year, c2.month, 1, -1);
                            let prev_last = days_in_month(py, pm);
                            diff = prev_last - d1 + d2;
                        }
                        Ok(Value::Num(diff as f64))
                    }
                    "YM" => {
                        let mut m = (c2.year - c1.year) * 12 + (c2.month - c1.month);
                        if c2.day < c1.day {
                            m -= 1;
                        }
                        Ok(Value::Num((((m % 12) + 12) % 12) as f64))
                    }
                    "YD" => {
                        let mut start = date_serial(c2.year, c1.month, c1.day);
                        if start > n1.floor() {
                            start = date_serial(c2.year - 1, c1.month, c1.day);
                        }
                        Ok(Value::Num(n1.floor() - start))
                    }
                    _ => Err(ferr("#NUM!")),
                }
            }
            "NETWORKDAYS" => {
                let s = self.t_num(&a[0])?.floor() as i64;
                let e = self.t_num(&a[1])?.floor() as i64;
                let lo = s.min(e);
                let hi = s.max(e);
                let mut cnt = 0;
                for d in lo..=hi {
                    let dow = serial_to_datetime(d as f64).dow;
                    if dow != 7 && dow != 1 {
                        cnt += 1;
                    }
                }
                Ok(Value::Num((if e < s { -cnt } else { cnt }) as f64))
            }
            "WORKDAY" => {
                let mut d = self.t_num(&a[0])?.floor() as i64;
                let mut remaining = self.t_num(&a[1])? as i64;
                let step: i64 = if remaining >= 0 { 1 } else { -1 };
                while remaining != 0 {
                    d += step;
                    let dow = serial_to_datetime(d as f64).dow;
                    if dow != 7 && dow != 1 {
                        remaining -= step;
                    }
                }
                Ok(Value::Num(d as f64))
            }

            // ---- financial ----
            "PMT" => {
                let r = self.t_num(&a[0])?;
                let nper = self.t_num(&a[1])?;
                let pv = self.t_num(&a[2])?;
                let fv = if a.len() > 3 { self.t_num(&a[3])? } else { 0.0 };
                Ok(Value::Num(if r == 0.0 {
                    -(pv + fv) / nper
                } else {
                    -(pv * (1.0 + r).powf(nper) + fv) * r / ((1.0 + r).powf(nper) - 1.0)
                }))
            }
            "FV" => {
                let r = self.t_num(&a[0])?;
                let nper = self.t_num(&a[1])?;
                let pmt = self.t_num(&a[2])?;
                let pv = if a.len() > 3 { self.t_num(&a[3])? } else { 0.0 };
                Ok(Value::Num(if r == 0.0 {
                    -(pv + pmt * nper)
                } else {
                    -(pv * (1.0 + r).powf(nper) + pmt * ((1.0 + r).powf(nper) - 1.0) / r)
                }))
            }
            "PV" => {
                let r = self.t_num(&a[0])?;
                let nper = self.t_num(&a[1])?;
                let pmt = self.t_num(&a[2])?;
                let fv = if a.len() > 3 { self.t_num(&a[3])? } else { 0.0 };
                Ok(Value::Num(if r == 0.0 {
                    -(fv + pmt * nper)
                } else {
                    -(fv + pmt * ((1.0 + r).powf(nper) - 1.0) / r) / (1.0 + r).powf(nper)
                }))
            }
            "NPV" => {
                let r = self.t_num(&a[0])?;
                let mut total = 0.0;
                let mut t = 1i32;
                for arg in a.iter().skip(1) {
                    for cf in self.t_nums(arg)? {
                        total += cf / (1.0 + r).powi(t);
                        t += 1;
                    }
                }
                Ok(Value::Num(total))
            }
            "NPER" => {
                let r = self.t_num(&a[0])?;
                let pmt = self.t_num(&a[1])?;
                let pv = self.t_num(&a[2])?;
                let fv = if a.len() > 3 { self.t_num(&a[3])? } else { 0.0 };
                Ok(Value::Num(if r == 0.0 {
                    -(pv + fv) / pmt
                } else {
                    ((pmt - fv * r) / (pmt + pv * r)).ln() / (1.0 + r).ln()
                }))
            }

            // ---- info / logical ----
            "ISEVEN" => Ok(Value::Bool(self.t_num(&a[0])?.trunc() as i64 % 2 == 0)),
            "ISODD" => Ok(Value::Bool(self.t_num(&a[0])?.trunc() as i64 % 2 != 0)),
            "ISFORMULA" => {
                let r = self.t_ref(&a[0]);
                let ok = match r {
                    Some(rf) => self
                        .cell_at_ref(&rf.sheet, rf.r1.min(rf.r2), rf.c1.min(rf.c2))
                        .map(|c| c.formula.is_some())
                        .unwrap_or(false),
                    None => false,
                };
                Ok(Value::Bool(ok))
            }
            "ISREF" => Ok(Value::Bool(self.t_ref(&a[0]).is_some())),
            "ISNONTEXT" => Ok(Value::Bool(!matches!(self.safe_value(&a[0]), Value::Str(_)))),
            "N" => match self.t_value(&a[0])? {
                Value::Num(x) => Ok(Value::Num(x)),
                Value::Bool(b) => Ok(Value::Num(if b { 1.0 } else { 0.0 })),
                Value::Err(c) => Ok(Value::Err(c)),
                Value::Arr(vs) => Ok(Value::Num(self.num(Value::first_of(&vs))?)),
                _ => Ok(Value::Num(0.0)),
            },
            "TYPE" => Ok(Value::Num(match self.safe_value(&a[0]) {
                Value::Num(_) => 1.0,
                Value::Str(_) => 2.0,
                Value::Bool(_) => 4.0,
                Value::Err(_) => 16.0,
                Value::Blank => 1.0,
                // Excel TYPE of an array constant.
                Value::Arr(_) => 64.0,
            })),
            "ERROR.TYPE" => {
                let code = self.is_error(&a[0]);
                let t = match code.as_deref() {
                    Some("#NULL!") => Some(1),
                    Some("#DIV/0!") => Some(2),
                    Some("#VALUE!") => Some(3),
                    Some("#REF!") => Some(4),
                    Some("#NAME?") => Some(5),
                    Some("#NUM!") => Some(6),
                    Some("#N/A") => Some(7),
                    Some("#CALC!") => Some(14),
                    _ => None,
                };
                match t {
                    Some(x) => Ok(Value::Num(x as f64)),
                    None => Err(ferr("#N/A")),
                }
            }
            "SHEET" => {
                let idx = self
                    .wb
                    .sheets
                    .iter()
                    .position(|s| s.name == self.sheet_name);
                Ok(Value::Num(match idx {
                    Some(i) => (i + 1) as f64,
                    None => 1.0,
                }))
            }
            "SHEETS" => Ok(Value::Num(if self.wb.sheets.is_empty() {
                1.0
            } else {
                self.wb.sheets.len() as f64
            })),

            // ---- math (phase 1) ----
            "QUOTIENT" => {
                let d = self.t_num(&a[1])?;
                if d == 0.0 {
                    Err(ferr("#DIV/0!"))
                } else {
                    Ok(Value::Num((self.t_num(&a[0])? / d).trunc()))
                }
            }
            "SEC" => Ok(Value::Num(1.0 / self.t_num(&a[0])?.cos())),
            "CSC" => Ok(Value::Num(1.0 / self.t_num(&a[0])?.sin())),
            "COT" => Ok(Value::Num(1.0 / self.t_num(&a[0])?.tan())),
            "SINH" => Ok(Value::Num(self.t_num(&a[0])?.sinh())),
            "COSH" => Ok(Value::Num(self.t_num(&a[0])?.cosh())),
            "TANH" => Ok(Value::Num(self.t_num(&a[0])?.tanh())),
            "ASINH" => Ok(Value::Num(self.t_num(&a[0])?.asinh())),
            "ACOSH" => Ok(Value::Num(self.t_num(&a[0])?.acosh())),
            "ATANH" => Ok(Value::Num(self.t_num(&a[0])?.atanh())),
            "MULTINOMIAL" => {
                let ns: Vec<i64> = self.all_nums(a)?.iter().map(|x| *x as i64).collect();
                let mut r = fact_d(ns.iter().sum());
                for x in ns {
                    r /= fact_d(x);
                }
                Ok(Value::Num(r))
            }
            "SUMX2PY2" => {
                let (xs, ys) = self.pair(a, 0, 1)?;
                let mut t = 0.0;
                for i in 0..xs.len() {
                    t += xs[i] * xs[i] + ys[i] * ys[i];
                }
                Ok(Value::Num(t))
            }
            "SUMX2MY2" => {
                let (xs, ys) = self.pair(a, 0, 1)?;
                let mut t = 0.0;
                for i in 0..xs.len() {
                    t += xs[i] * xs[i] - ys[i] * ys[i];
                }
                Ok(Value::Num(t))
            }
            "SUMXMY2" => {
                let (xs, ys) = self.pair(a, 0, 1)?;
                let mut t = 0.0;
                for i in 0..xs.len() {
                    t += (xs[i] - ys[i]).powi(2);
                }
                Ok(Value::Num(t))
            }
            "BASE" => {
                let num = self.t_num(&a[0])? as i64;
                let radix = self.t_num(&a[1])? as u32;
                let min_len = if a.len() > 2 { self.t_num(&a[2])? as usize } else { 0 };
                if !(2..=36).contains(&radix) {
                    Err(ferr("#NUM!"))
                } else {
                    let s = to_radix(num, radix).to_uppercase();
                    Ok(Value::Str(pad_start(&s, min_len, '0')))
                }
            }
            "DECIMAL" => {
                let radix = self.t_num(&a[1])? as u32;
                if !(2..=36).contains(&radix) {
                    Err(ferr("#NUM!"))
                } else {
                    let s = self.t_str(&a[0])?;
                    match i64::from_str_radix(s.trim(), radix) {
                        Ok(v) => Ok(Value::Num(v as f64)),
                        Err(_) => Err(ferr("#NUM!")),
                    }
                }
            }
            "ARABIC" => Ok(Value::Num(roman_to_arabic(&self.t_str(&a[0])?) as f64)),
            "ROMAN" => Ok(Value::Str(arabic_to_roman(self.t_num(&a[0])? as i32))),

            // ---- bitwise (phase 1) ----
            "BITAND" => Ok(Value::Num(
                (self.t_num(&a[0])? as i64 & self.t_num(&a[1])? as i64) as f64,
            )),
            "BITOR" => Ok(Value::Num(
                (self.t_num(&a[0])? as i64 | self.t_num(&a[1])? as i64) as f64,
            )),
            "BITXOR" => Ok(Value::Num(
                (self.t_num(&a[0])? as i64 ^ self.t_num(&a[1])? as i64) as f64,
            )),
            "BITLSHIFT" => {
                let x = self.t_num(&a[0])? as i64;
                let sh = self.t_num(&a[1])? as i64;
                Ok(Value::Num((if sh >= 0 { x << sh } else { x >> (-sh) }) as f64))
            }
            "BITRSHIFT" => {
                let x = self.t_num(&a[0])? as i64;
                let sh = self.t_num(&a[1])? as i64;
                Ok(Value::Num((if sh >= 0 { x >> sh } else { x << (-sh) }) as f64))
            }

            _ => self.apply_function4(name, a),
        }
    }
}

fn to_radix(mut num: i64, radix: u32) -> String {
    if num == 0 {
        return "0".to_string();
    }
    let neg = num < 0;
    if neg {
        num = -num;
    }
    let digits = b"0123456789abcdefghijklmnopqrstuvwxyz";
    let mut out = Vec::new();
    while num > 0 {
        out.push(digits[(num % radix as i64) as usize]);
        num /= radix as i64;
    }
    if neg {
        out.push(b'-');
    }
    out.reverse();
    String::from_utf8(out).unwrap()
}

fn pad_start(s: &str, min_len: usize, pad: char) -> String {
    let len = s.chars().count();
    if len >= min_len {
        s.to_string()
    } else {
        let mut out = String::new();
        for _ in 0..(min_len - len) {
            out.push(pad);
        }
        out.push_str(s);
        out
    }
}
