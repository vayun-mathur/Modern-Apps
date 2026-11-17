//! OpenFormula spreadsheet evaluator (the native owner of the ODF formula engine).
//!
//! Value-typed engine: numbers, strings, booleans, error values. Recursive-descent
//! parser (comparison -> concat -> add/sub -> mul/div -> power -> unary -> primary),
//! ~150 functions, cross-sheet references with cycle detection, ODF number
//! formatting, and civil-date arithmetic (serial = days since 1899-12-30).
//!
//! Determinism: `TODAY`/`NOW` derive from a caller-supplied `now_millis`, and
//! `RAND`/`RANDBETWEEN` use a PRNG seeded from it (Kotlin's are non-deterministic;
//! we only require reproducibility here).

use serde::Deserialize;
use std::cell::RefCell;
use std::collections::{HashMap, HashSet};

const MS_PER_DAY: f64 = 86_400_000.0;
/// Days from the spreadsheet epoch (1899-12-30) to the Unix epoch (1970-01-01).
const EPOCH_OFFSET_DAYS: i64 = 25569;

// ---- serde workbook schema (must match the Kotlin serializer in OfficeNative) --

#[derive(Deserialize)]
struct WorkbookJson {
    sheets: Vec<SheetJson>,
    #[serde(default, rename = "namedRanges")]
    named_ranges: Vec<NamedRangeJson>,
}

#[derive(Deserialize)]
struct NamedRangeJson {
    #[serde(default)]
    name: String,
    #[serde(default)]
    addr: String,
}

#[derive(Deserialize)]
struct SheetJson {
    #[serde(default)]
    name: String,
    #[serde(default)]
    rows: Vec<RowJson>,
}

#[derive(Deserialize)]
struct RowJson {
    #[serde(default)]
    cells: Vec<Cell>,
}

#[derive(Deserialize, Clone, Default)]
pub struct Cell {
    #[serde(default)]
    text: String,
    #[serde(default)]
    formula: Option<String>,
    #[serde(default, rename = "numberValue")]
    number_value: Option<f64>,
    #[serde(default, rename = "valueType")]
    value_type: Option<String>,
    #[serde(default, rename = "isCovered")]
    is_covered: bool,
    #[serde(default, rename = "numberFormat")]
    number_format: Option<NumberFormat>,
}

#[derive(Deserialize, Clone)]
pub struct NumberFormat {
    #[serde(default)]
    decimals: Option<i32>,
    #[serde(default)]
    percent: bool,
    #[serde(default, rename = "currencySymbol")]
    currency_symbol: Option<String>,
    #[serde(default)]
    grouping: bool,
    #[serde(default, rename = "isDate")]
    is_date: bool,
    #[serde(default, rename = "isTime")]
    is_time: bool,
    #[serde(default, rename = "isScientific")]
    is_scientific: bool,
    #[serde(default, rename = "isFraction")]
    is_fraction: bool,
    #[serde(default = "one_i32", rename = "fractionDenominatorDigits")]
    fraction_denominator_digits: i32,
    #[serde(default, rename = "dateTimeTokens")]
    date_time_tokens: Vec<NumberToken>,
}

fn one_i32() -> i32 {
    1
}

#[derive(Deserialize, Clone)]
pub struct NumberToken {
    #[serde(default)]
    kind: String,
    #[serde(default)]
    style: Option<String>,
    #[serde(default)]
    text: Option<String>,
    #[serde(default)]
    textual: bool,
}

struct Sheet {
    name: String,
    rows: Vec<Vec<Cell>>,
}

pub struct Workbook {
    sheets: Vec<Sheet>,
    name_to_idx: HashMap<String, usize>,
    /// Named ranges, keyed case-insensitively (uppercase name -> parsed Ref).
    named: HashMap<String, Ref>,
    now_millis: i64,
    rng: RefCell<u64>,
}

// ---- value model -----------------------------------------------------------

#[derive(Clone, Debug, PartialEq)]
enum Value {
    Num(f64),
    Str(String),
    Bool(bool),
    Err(String),
    Blank,
    /// A spilled-array result (FILTER/SORT/UNIQUE/SEQUENCE/OFFSET with height-width,
    /// or a formula cell whose own result is an array). Scalar contexts use implicit
    /// intersection (the first element); aggregations expand all elements.
    Arr(Vec<Value>),
}

impl Value {
    /// Implicit intersection: the scalar a bare array contributes in scalar context.
    fn first_of(arr: &[Value]) -> Value {
        arr.first().cloned().unwrap_or(Value::Blank)
    }
}

#[derive(Clone, Debug)]
struct EvalError(String);

type R<T> = Result<T, EvalError>;

fn ferr(code: &str) -> EvalError {
    EvalError(code.to_string())
}

/// A parsed reference: single cell (`is_range=false`) or rectangular range.
#[derive(Clone)]
struct Ref {
    r1: i32,
    c1: i32,
    r2: i32,
    c2: i32,
    is_range: bool,
    sheet: Option<String>,
}

#[derive(Clone)]
enum Arg {
    Scalar(Value),
    RangeRef {
        r1: i32,
        c1: i32,
        r2: i32,
        c2: i32,
        sheet: Option<String>,
    },
}

/// A lazily-evaluated function argument (re-parsed each access, like the Kotlin ArgThunk).
struct ArgThunk {
    text: String,
}

impl Workbook {
    pub fn from_json(json: &str, now_millis: i64) -> Option<Workbook> {
        let parsed: WorkbookJson = serde_json::from_str(json).ok()?;
        let mut sheets = Vec::with_capacity(parsed.sheets.len());
        let mut name_to_idx = HashMap::new();
        for (i, s) in parsed.sheets.into_iter().enumerate() {
            name_to_idx.entry(s.name.clone()).or_insert(i);
            let rows: Vec<Vec<Cell>> = s.rows.into_iter().map(|r| r.cells).collect();
            sheets.push(Sheet { name: s.name, rows });
        }
        let mut named = HashMap::new();
        for nr in parsed.named_ranges {
            let key = nr.name.trim().to_uppercase();
            if key.is_empty() || named.contains_key(&key) {
                continue;
            }
            if let Some(r) = parse_odf_address(&nr.addr, &sheets, &name_to_idx) {
                named.insert(key, r);
            }
        }
        // Seed the PRNG deterministically from now_millis (splitmix64-style).
        let seed = (now_millis as u64) ^ 0x9E3779B97F4A7C15;
        Some(Workbook {
            sheets,
            name_to_idx,
            named,
            now_millis,
            rng: RefCell::new(seed.max(1)),
        })
    }

    pub fn display_value(&self, sheet_idx: usize, row: i32, col: i32) -> String {
        let sheet = match self.sheets.get(sheet_idx) {
            Some(s) => s,
            None => return String::new(),
        };
        let cell = match cell_at(sheet, row, col) {
            Some(c) => c.clone(),
            None => return String::new(),
        };
        if cell.formula.is_none() {
            return cell.text;
        }
        let visiting = RefCell::new(HashSet::new());
        let mut ev = Evaluator {
            wb: self,
            sheet_idx,
            sheet_name: sheet.name.clone(),
            cur_row: 0,
            cur_col: 0,
            visiting: &visiting,
        };
        match ev.evaluate_cell_value(row, col) {
            Value::Num(v) => {
                if v.is_nan() {
                    "#ERR".to_string()
                } else {
                    format_with_style(v, cell.number_format.as_ref())
                }
            }
            Value::Bool(b) => {
                if b {
                    "TRUE".to_string()
                } else {
                    "FALSE".to_string()
                }
            }
            Value::Str(s) => s,
            Value::Err(code) => code,
            Value::Blank => String::new(),
            // Spilled arrays display their top-left (implicit intersection) value.
            Value::Arr(vs) => display_scalar(&Value::first_of(&vs), cell.number_format.as_ref()),
        }
    }

    pub fn is_numeric(&self, sheet_idx: usize, row: i32, col: i32) -> bool {
        let sheet = match self.sheets.get(sheet_idx) {
            Some(s) => s,
            None => return false,
        };
        let cell = match cell_at(sheet, row, col) {
            Some(c) => c,
            None => return false,
        };
        if cell.number_value.is_some() {
            return true;
        }
        if let Some(vt) = &cell.value_type {
            if vt == "float" || vt == "percentage" || vt == "currency" {
                return true;
            }
        }
        if cell.formula.is_some() {
            let visiting = RefCell::new(HashSet::new());
            let mut ev = Evaluator {
                wb: self,
                sheet_idx,
                sheet_name: sheet.name.clone(),
                cur_row: 0,
                cur_col: 0,
                visiting: &visiting,
            };
            return match ev.evaluate_cell_value(row, col) {
                Value::Num(v) => !v.is_nan(),
                _ => false,
            };
        }
        parse_f64(&cell.text).is_some()
    }
}

fn cell_at(sheet: &Sheet, row: i32, col: i32) -> Option<&Cell> {
    if row < 0 || col < 0 {
        return None;
    }
    sheet
        .rows
        .get(row as usize)
        .and_then(|r| r.get(col as usize))
}

// ---- number / date formatting ---------------------------------------------

const MONTHS: [&str; 12] = [
    "January", "February", "March", "April", "May", "June", "July", "August", "September",
    "October", "November", "December",
];
const MONTHS_SHORT: [&str; 12] = [
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
];
const WEEKDAYS: [&str; 7] = [
    "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday",
];
const WEEKDAYS_SHORT: [&str; 7] = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];

/// Java Math.round semantics: floor(x + 0.5) (rounds half toward +infinity).
fn math_round(x: f64) -> i64 {
    (x + 0.5).floor() as i64
}

/// Parses a double the way Kotlin's String.toDoubleOrNull() does for cell text.
fn parse_f64(s: &str) -> Option<f64> {
    let t = s.trim();
    if t.is_empty() {
        return None;
    }
    t.parse::<f64>().ok()
}

/// Fixed-decimal formatting matching Java String.format("%.Nf") (HALF_UP rounding),
/// with optional thousands grouping.
fn format_fixed(value: f64, decimals: usize, grouping: bool) -> String {
    if value.is_nan() {
        return "NaN".to_string();
    }
    if value.is_infinite() {
        return if value < 0.0 { "-Infinity" } else { "Infinity" }.to_string();
    }
    let neg = value.is_sign_negative() && value != 0.0;
    let av = value.abs();
    let scale = 10f64.powi(decimals as i32);
    // HALF_UP: round half away from zero.
    let scaled = (av * scale + 0.5).floor();
    let int_part0 = (scaled / scale).floor() as i128;
    let frac_units0 = (scaled - (int_part0 as f64) * scale) as i128;
    // Guard against fp drift pushing frac to full unit.
    let (int_part, frac_units) = if frac_units0 >= scale as i128 {
        (int_part0 + 1, frac_units0 - scale as i128)
    } else {
        (int_part0, frac_units0)
    };
    let mut int_str = int_part.to_string();
    if grouping {
        int_str = group_thousands(&int_str);
    }
    let mut out = String::new();
    if neg {
        out.push('-');
    }
    out.push_str(&int_str);
    if decimals > 0 {
        out.push('.');
        let fs = frac_units.to_string();
        for _ in 0..(decimals.saturating_sub(fs.len())) {
            out.push('0');
        }
        out.push_str(&fs);
    }
    out
}

fn group_thousands(digits: &str) -> String {
    let bytes = digits.as_bytes();
    let mut out = String::new();
    let len = bytes.len();
    for (i, b) in bytes.iter().enumerate() {
        if i > 0 && (len - i).is_multiple_of(3) {
            out.push(',');
        }
        out.push(*b as char);
    }
    out
}

/// Java String.format("%.NE") scientific formatting: e.g. 12345 -> "1.23E+04".
fn format_scientific(value: f64, decimals: usize) -> String {
    if value == 0.0 {
        let mant = format_fixed(0.0, decimals, false);
        return format!("{}E+00", mant);
    }
    let neg = value < 0.0;
    let mut av = value.abs();
    let mut exp = av.log10().floor() as i32;
    let mut mant = av / 10f64.powi(exp);
    // Rounding the mantissa can bump it to 10.0; renormalize.
    let scale = 10f64.powi(decimals as i32);
    let rounded = (mant * scale + 0.5).floor() / scale;
    if rounded >= 10.0 {
        exp += 1;
        av = value.abs();
        mant = av / 10f64.powi(exp);
    } else {
        mant = rounded;
    }
    let mant_str = format_fixed(mant, decimals, false);
    let sign = if exp < 0 { '-' } else { '+' };
    let ea = exp.abs();
    let exp_str = if ea < 10 {
        format!("0{}", ea)
    } else {
        ea.to_string()
    };
    format!("{}{}E{}{}", if neg { "-" } else { "" }, mant_str, sign, exp_str)
}

pub fn format_number(v: f64) -> String {
    if v.is_nan() {
        return "#ERR".to_string();
    }
    if v.is_infinite() {
        return "#DIV/0!".to_string();
    }
    if v == (v as i64) as f64 {
        return (v as i64).to_string();
    }
    let s = format_fixed(v, 4, false);
    let trimmed = s.trim_end_matches('0');
    let trimmed = trimmed.trim_end_matches('.');
    trimmed.to_string()
}

/// Standalone number formatter for callers with no workbook context (e.g. XLSX
/// cell display). `nf_json` is a serialized [NumberFormat] (same schema as a
/// workbook cell's `numberFormat`); `"null"` or empty means "no format".
pub fn format_value_json(value: f64, nf_json: &str) -> String {
    let trimmed = nf_json.trim();
    let fmt: Option<NumberFormat> = if trimmed.is_empty() || trimmed == "null" {
        None
    } else {
        serde_json::from_str(trimmed).ok()
    };
    format_with_style(value, fmt.as_ref())
}

fn format_with_style(v: f64, fmt: Option<&NumberFormat>) -> String {
    let fmt = match fmt {
        Some(f) => f,
        None => return format_number(v),
    };
    if !fmt.date_time_tokens.is_empty() {
        return format_date_time(v, &fmt.date_time_tokens);
    }
    if fmt.is_date {
        return format_date_iso(v);
    }
    if fmt.is_time {
        return format_time(v);
    }
    if fmt.is_scientific {
        let decimals = fmt.decimals.unwrap_or(2).clamp(0, 10) as usize;
        return format_scientific(v, decimals);
    }
    if fmt.is_fraction {
        return format_fraction(v, fmt.fraction_denominator_digits);
    }
    let mut value = v;
    if fmt.percent {
        value *= 100.0;
    }
    let decimals = fmt.decimals.unwrap_or(2).clamp(0, 10) as usize;
    let mut s = format_fixed(value, decimals, fmt.grouping);
    if fmt.percent {
        s.push('%');
    }
    if let Some(cur) = &fmt.currency_symbol {
        s = format!("{}{}", cur, s);
    }
    s
}

include!("formula_part1.rs");
include!("formula_part2.rs");
include!("formula_part3.rs");
include!("formula_part4.rs");
include!("formula_part5.rs");
include!("formula_part6.rs");
include!("formula_part7.rs");
include!("formula_part8.rs");
include!("formula_part9.rs");
include!("formula_part10.rs");
include!("formula_part11.rs");
include!("formula_part12.rs");
include!("formula_part13.rs");

#[cfg(test)]
#[path = "formula_tests.rs"]
mod formula_tests;
#[cfg(test)]
#[path = "formula_helper_tests.rs"]
mod formula_helper_tests;