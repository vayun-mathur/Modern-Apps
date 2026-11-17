//! Direct tests for the pure free helpers (parts 1, 4, 9, 13):
//! civil-date arithmetic, fraction/time formatting, roman numerals,
//! percentile/correlation math, 30/360 day counts and LET helpers.
use super::*;

#[test]
fn civil_date_arithmetic() {
    assert_eq!(days_from_civil(1970, 1, 1), 0);
    assert_eq!(days_from_civil(2000, 1, 1), 10957);
    assert_eq!(civil_from_days(0), (1970, 1, 1));
    assert_eq!(civil_from_days(10957), (2000, 1, 1));
    for &(y, m, d) in &[
        (1900, 3, 1),
        (1999, 12, 31),
        (2000, 2, 29),
        (2020, 2, 29),
        (2023, 11, 14),
        (2400, 2, 29),
    ] {
        assert_eq!(civil_from_days(days_from_civil(y, m, d)), (y, m, d));
    }
    assert!(is_leap(2000));
    assert!(!is_leap(1900));
    assert!(is_leap(2024));
    assert!(!is_leap(2023));
    assert_eq!(days_in_month(2020, 2), 29);
    assert_eq!(days_in_month(2019, 2), 28);
    assert_eq!(days_in_month(2021, 4), 30);
    assert_eq!(days_in_month(2021, 1), 31);
    // Serial epoch: DATE(2020,1,1) = 43831, DATE(2020,2,29) = 43890.
    assert_eq!(date_serial(2020, 1, 1), 43831.0);
    assert_eq!(date_serial(2020, 2, 29), 43890.0);
    // Lenient month overflow rolls into the next year.
    assert_eq!(date_serial(2020, 13, 1), date_serial(2021, 1, 1));
    let dt = serial_to_datetime(43831.0);
    assert_eq!((dt.year, dt.month, dt.day, dt.dow), (2020, 1, 1, 4)); // Wed
    assert_eq!(format_date_iso(43831.0), "2020-01-01");
}

#[test]
fn add_months_clamps_day() {
    assert_eq!(add_months(2020, 1, 31, 1), (2020, 2, 29));
    assert_eq!(add_months(2019, 1, 31, 1), (2019, 2, 28));
    assert_eq!(add_months(2020, 3, 31, -1), (2020, 2, 29));
    assert_eq!(add_months(2020, 12, 15, 1), (2021, 1, 15));
    assert_eq!(add_months(2020, 1, 15, -1), (2019, 12, 15));
    assert_eq!(add_months(2020, 6, 15, 12), (2021, 6, 15));
}

#[test]
fn fraction_and_time_formatting() {
    assert_eq!(format_fraction(1.5, 1), "1 1/2");
    assert_eq!(format_fraction(0.25, 2), "1/4");
    assert_eq!(format_fraction(-0.5, 1), "-1/2");
    assert_eq!(format_fraction(-1.5, 1), "-1 1/2");
    assert_eq!(format_fraction(2.0, 1), "2");
    assert_eq!(format_fraction(0.333, 2), "1/3");
    assert_eq!(format_time(0.5), "12:00:00");
    assert_eq!(format_time(0.25), "06:00:00");
    assert_eq!(format_time(0.0), "00:00:00");
    assert_eq!(text_format(0.5, "0%"), "50%");
    assert_eq!(text_format(1234.5, "#,##0.00"), "1,234.50");
    assert_eq!(text_format(1234.5, "0"), "1235");
    assert_eq!(format_scientific(12345.0, 2), "1.23E+04");
    assert_eq!(format_scientific(0.0, 2), "0.00E+00");
}

#[test]
fn date_time_token_formatting() {
    let tok = |kind: &str, style: Option<&str>, textual: bool| NumberToken {
        kind: kind.to_string(),
        style: style.map(|s| s.to_string()),
        text: None,
        textual,
    };
    let tokens = vec![
        tok("year", Some("long"), false),
        tok("text", None, false),
        tok("month", Some("long"), true),
        tok("text", None, false),
        tok("day", Some("long"), false),
    ];
    let mut tokens = tokens;
    tokens[1].text = Some("-".to_string());
    tokens[3].text = Some("-".to_string());
    assert_eq!(format_date_time(43831.0, &tokens), "2020-January-01");
    let dow = vec![tok("day-of-week", Some("long"), false)];
    assert_eq!(format_date_time(43831.0, &dow), "Wednesday");
    let dow_short = vec![tok("day-of-week", None, false)];
    assert_eq!(format_date_time(43831.0, &dow_short), "Wed");
}

#[test]
fn roman_and_integer_helpers() {
    assert_eq!(roman_to_arabic("XIV"), 14);
    assert_eq!(roman_to_arabic("MCMXCIV"), 1994);
    assert_eq!(roman_to_arabic("xiv"), 14);
    assert_eq!(roman_to_arabic(""), 0);
    assert_eq!(arabic_to_roman(1994), "MCMXCIV");
    assert_eq!(arabic_to_roman(4), "IV");
    assert_eq!(arabic_to_roman(0), "0");
    assert_eq!(arabic_to_roman(4000), "4000");
    assert_eq!(gcd_l(12, 18), 6);
    assert_eq!(gcd_l(0, 5), 5);
    assert_eq!(gcd_l(-12, 18), 6);
    assert_eq!(fact_d(0), 1.0);
    assert_eq!(fact_d(5), 120.0);
    assert!(avg_of(&[]).is_nan());
    assert_eq!(avg_of(&[1.0, 2.0, 3.0]), 2.0);
    assert_eq!(ksign(-2.5), -1.0);
    assert_eq!(ksign(0.0), 0.0);
    assert_eq!(ksign(3.0), 1.0);
    assert_eq!(substring_after_last("Sheet1.A1", '.'), "A1");
    assert_eq!(substring_after_last("A1", '.'), "A1");
    assert_eq!(pipe_min(&[]), 0.0);
    assert_eq!(pipe_max(&[1.0, 9.0, 3.0]), 9.0);
    assert!(index_of("hello", "L", 0, true) == Some(2));
    assert!(index_of("hello", "L", 0, false).is_none());
    assert_eq!(bool_or_null(&Value::Num(0.0)), Some(false));
    assert_eq!(bool_or_null(&Value::Str("x".into())), None);
}

#[test]
fn stats_and_calendar_helpers() {
    let sorted = [1.0, 2.0, 3.0, 4.0];
    assert_eq!(percentile_of(&sorted, 0.0), 1.0);
    assert_eq!(percentile_of(&sorted, 0.5), 2.5);
    assert_eq!(percentile_of(&sorted, 1.0), 4.0);
    assert_eq!(correl(&[1.0, 2.0, 3.0], &[10.0, 20.0, 30.0]).unwrap(), 1.0);
    assert!(correl(&[1.0, 1.0], &[2.0, 2.0]).is_err());
    assert_eq!(slope(&[10.0, 20.0, 30.0], &[1.0, 2.0, 3.0]).unwrap(), 10.0);
    assert!(slope(&[1.0], &[1.0]).is_err());
    // 30/360: Jan 1 -> Feb 1 is 30 days both conventions.
    assert_eq!(days360(date_serial(2020, 1, 1), date_serial(2020, 2, 1), false), 30);
    assert_eq!(days360(date_serial(2020, 1, 31), date_serial(2020, 2, 1), true), 1);
    assert_eq!(weeknum(date_serial(2020, 1, 1)), 1);
    assert_eq!(weeknum(date_serial(2020, 1, 5)), 2);
    assert_eq!(iso_week_num(date_serial(2020, 1, 1)), 1);
    assert_eq!(iso_week_num(date_serial(2020, 12, 31)), 53);
    assert_eq!(year_frac(date_serial(2020, 1, 1), date_serial(2021, 1, 1), 3), 366.0 / 365.0);
    // Financial cores: PMT of a 1-period 10% loan of 1000 is -1100.
    assert!((pmt_calc(0.1, 1.0, 1000.0, 0.0, 0) + 1100.0).abs() < 1e-9);
    assert_eq!(pmt_calc(0.0, 10.0, 1000.0, 0.0, 0), -100.0);
    assert!((fv_of(0.1, 1.0, -100.0, 0.0, 0) - 100.0).abs() < 1e-9);
    assert_eq!(ipmt_calc(0.1, 1, 1.0, 1000.0, 0.0, 0), -100.0);
    assert_eq!(a1_to_coords("B3"), Some((2, 1)));
    assert_eq!(a1_to_coords("Sheet1.B3"), Some((2, 1)));
    assert_eq!(a1_to_coords("$A$1"), Some((0, 0)));
    assert_eq!(a1_to_coords(""), None);
}

#[test]
fn let_and_compare_helpers() {
    assert_eq!(
        split_top_compare("[.A1:.A3]>15"),
        Some((">".to_string(), "[.A1:.A3]".to_string(), "15".to_string()))
    );
    assert_eq!(
        split_top_compare("x<=2"),
        Some(("<=".to_string(), "x".to_string(), "2".to_string()))
    );
    assert!(split_top_compare("SUM(1;2)").is_none());
    assert!(split_top_compare("\"a>b\"").is_none());
    assert!(is_bare_ref_text("[.A1:.A3]"));
    assert!(is_bare_ref_text("MyRange"));
    assert!(!is_bare_ref_text("SUM(A1)"));
    assert!(!is_bare_ref_text("1ABC"));
    assert!(is_let_name("my_var"));
    assert!(!is_let_name("X1"));
    assert!(!is_let_name("TRUE"));
    assert!(!is_let_name("1x"));
    assert_eq!(
        substitute_let_names("x+1", &[("X".to_string(), "2".to_string())]),
        "(2)+1"
    );
    assert_eq!(
        substitute_let_names("\"x\"&x", &[("X".to_string(), "2".to_string())]),
        "\"x\"&(2)"
    );
    assert_eq!(value_literal(&Value::Num(2.5)).unwrap(), "2.5");
    assert_eq!(value_literal(&Value::Str("a\"b".into())).unwrap(), "\"a\"\"b\"");
    assert_eq!(value_literal(&Value::Bool(true)).unwrap(), "TRUE");
    assert!(value_literal(&Value::Err("#N/A".into())).is_err());
    assert_eq!(format_scalar(&Value::Bool(false)), "FALSE");
    assert_eq!(format_scalar(&Value::Blank), "");
    // Excel ordering: numbers < text < logicals < blanks < errors.
    assert_eq!(cmp_values(&Value::Num(1.0), &Value::Str("a".into())), std::cmp::Ordering::Less);
    assert_eq!(cmp_values(&Value::Str("b".into()), &Value::Str("A".into())), std::cmp::Ordering::Greater);
    assert_eq!(cmp_values(&Value::Bool(false), &Value::Bool(true)), std::cmp::Ordering::Less);
    assert!(wildcard_match(&Value::Str("a*".into()), &Value::Str("abc".into())).unwrap());
    assert!(!wildcard_match(&Value::Str("a*".into()), &Value::Str("xbc".into())).unwrap());
}

#[test]
fn address_and_wildcard_helpers() {
    assert_eq!(parse_a1_coords("B12"), Some((11, 1)));
    assert_eq!(parse_a1_coords("$c$3"), Some((2, 2)));
    assert_eq!(parse_a1_coords("A1"), Some((0, 0)));
    assert_eq!(parse_a1_coords("12"), None);
    assert_eq!(parse_a1_coords("A0"), None);
    assert_eq!(parse_a1_coords("A1x"), None);
    assert_eq!(split_sheet_cell("Sheet1.A1"), (Some("Sheet1".to_string()), "A1"));
    assert_eq!(split_sheet_cell("A1"), (None, "A1"));
    assert_eq!(split_sheet_cell("'My Sheet'.$B$2"), (Some("My Sheet".to_string()), "$B$2"));
    assert_eq!(col_to_index("A"), 0);
    assert_eq!(col_to_index("Z"), 25);
    assert_eq!(col_to_index("AA"), 26);
    assert_eq!(index_to_col(0), "A");
    assert_eq!(index_to_col(25), "Z");
    assert_eq!(index_to_col(26), "AA");
    assert_eq!(index_to_col(701), "ZZ");
    for i in [0, 1, 25, 26, 51, 52, 701, 702] {
        assert_eq!(col_to_index(&index_to_col(i)), i);
    }
    assert_eq!(normalize("of:=1+1"), "1+1");
    assert_eq!(normalize("=1+1"), "1+1");
    assert_eq!(normalize(" 1+1 "), "1+1");
    assert!(cmp_ignore_case("abc", "ABC") == 0);
    assert!(cmp_ignore_case("abc", "abd") < 0);
    assert!(cmp_ignore_case("ab", "a") > 0);
    assert!(wildcard_equals("Hello", "h*o"));
    assert!(wildcard_equals("abc", "a?c"));
    assert!(!wildcard_equals("abc", "a?d"));
    assert!(wildcard_equals("abc", "ABC"));
    assert!(!wildcard_equals("abc", "abd"));
    // parse_odf_address against a one-sheet workbook model.
    let sheets = vec![Sheet { name: "Sheet1".to_string(), rows: Vec::new() }];
    let mut idx = HashMap::new();
    idx.insert("Sheet1".to_string(), 0usize);
    let r = parse_odf_address("Sheet1.A1:Sheet1.B2", &sheets, &idx).unwrap();
    assert_eq!((r.r1, r.c1, r.r2, r.c2), (0, 0, 1, 1));
    let r = parse_odf_address("=B2", &sheets, &idx).unwrap();
    assert_eq!((r.r1, r.c1), (1, 1));
    assert!(parse_odf_address("Nope.A1", &sheets, &idx).is_none());
    assert!(parse_odf_address("A1", &[], &idx).is_none());
}

#[test]
fn text_before_after_helper() {
    assert_eq!(
        text_before_after("a,b,c", ",", 1, true).unwrap(),
        Value::Str("a".to_string())
    );
    assert_eq!(
        text_before_after("a,b,c", ",", 2, false).unwrap(),
        Value::Str("c".to_string())
    );
    assert!(text_before_after("abc", ",", 1, true).is_err());
    assert_eq!(
        text_before_after("a--b--c", "--", 1, false).unwrap(),
        Value::Str("b--c".to_string())
    );
    assert_eq!(find_chars(&['a', 'b', 'c'], &['b'], 0), Some(1));
    assert_eq!(find_chars(&['a'], &['b'], 0), None);
}
