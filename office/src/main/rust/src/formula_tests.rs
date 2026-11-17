//! Evaluation tests for the extended function families (parts 5-9):
//! multi-criteria aggregation, extended math/statistics, extended date,
//! financial, info, bitwise, radix/roman and lookup helpers. Every
//! expectation is the Excel/OpenFormula result for the same call.
use super::*;

// Sheet1: A1..A3 = 1,2,3 ; B1..B3 = 10,20,30 ; probe formula in C1.
fn wb_with(formula: &str) -> Workbook {
    let data = [[1.0, 10.0], [2.0, 20.0], [3.0, 30.0]];
    let mut rows: Vec<String> = Vec::new();
    for (r, row) in data.iter().enumerate() {
        let mut cells: Vec<String> = row
            .iter()
            .map(|v| format!("{{\"text\":\"{}\",\"numberValue\":{}}}", v, v))
            .collect();
        if r == 0 {
            cells.push(format!(
                "{{\"text\":\"\",\"formula\":\"{}\"}}",
                formula.replace('"', "\\\"")
            ));
        }
        rows.push(format!("{{\"cells\":[{}]}}", cells.join(",")));
    }
    let json = format!(
        "{{\"sheets\":[{{\"name\":\"Sheet1\",\"rows\":[{}]}}]}}",
        rows.join(",")
    );
    Workbook::from_json(&json, 1_700_000_000_000).unwrap()
}

fn eval(formula: &str) -> String {
    wb_with(formula).display_value(0, 0, 2)
}

// A-column probe over a caller-supplied single column (for MODE/IRR).
fn eval_col(formula: &str, col_a: &[f64]) -> String {
    let mut rows: Vec<String> = Vec::new();
    for (r, v) in col_a.iter().enumerate() {
        let mut cells = vec![format!("{{\"text\":\"{}\",\"numberValue\":{}}}", v, v)];
        if r == 0 {
            cells.push("{\"text\":\"10\",\"numberValue\":10}".to_string());
            cells.push(format!(
                "{{\"text\":\"\",\"formula\":\"{}\"}}",
                formula.replace('"', "\\\"")
            ));
        }
        rows.push(format!("{{\"cells\":[{}]}}", cells.join(",")));
    }
    let json = format!(
        "{{\"sheets\":[{{\"name\":\"Sheet1\",\"rows\":[{}]}}]}}",
        rows.join(",")
    );
    Workbook::from_json(&json, 1_700_000_000_000)
        .unwrap()
        .display_value(0, 0, 2)
}

#[test]
fn multi_criteria_and_products() {
    assert_eq!(eval("of:=SUMIFS([.B1:.B3];[.A1:.A3];\">1\")"), "50");
    assert_eq!(eval("of:=COUNTIFS([.A1:.A3];\">1\";[.B1:.B3];\"<30\")"), "1");
    assert_eq!(eval("of:=AVERAGEIFS([.B1:.B3];[.A1:.A3];\">1\")"), "25");
    assert_eq!(eval("of:=AVERAGEIFS([.B1:.B3];[.A1:.A3];\">99\")"), "#DIV/0!");
    assert_eq!(eval("of:=SUMPRODUCT([.A1:.A3];[.B1:.B3])"), "140");
    assert_eq!(eval("of:=SUMSQ([.A1:.A3])"), "14");
    assert_eq!(eval("of:=SUMX2PY2([.A1:.A3];[.B1:.B3])"), "1414");
    assert_eq!(eval("of:=SUMX2MY2([.A1:.A3];[.B1:.B3])"), "-1386");
    assert_eq!(eval("of:=SUMXMY2([.A1:.A3];[.B1:.B3])"), "1134");
}

#[test]
fn extended_math() {
    assert_eq!(eval("of:=MROUND(10;3)"), "9");
    assert_eq!(eval("of:=MROUND(11;3)"), "12");
    assert_eq!(eval("of:=MROUND(10;0)"), "0");
    assert_eq!(eval("of:=EVEN(1.5)"), "2");
    assert_eq!(eval("of:=EVEN(-1.5)"), "-2");
    assert_eq!(eval("of:=EVEN(2)"), "2");
    assert_eq!(eval("of:=ODD(2)"), "3");
    assert_eq!(eval("of:=ODD(-2)"), "-3");
    assert_eq!(eval("of:=ODD(0)"), "1");
    assert_eq!(eval("of:=GCD(12;18)"), "6");
    assert_eq!(eval("of:=LCM(4;6)"), "12");
    assert_eq!(eval("of:=FACT(5)"), "120");
    assert_eq!(eval("of:=FACT(-1)"), "#NUM!");
    assert_eq!(eval("of:=COMBIN(5;2)"), "10");
    assert_eq!(eval("of:=COMBIN(5;6)"), "#NUM!");
    assert_eq!(eval("of:=MULTINOMIAL(2;3)"), "10");
    assert_eq!(eval("of:=QUOTIENT(10;3)"), "3");
    assert_eq!(eval("of:=QUOTIENT(-10;3)"), "-3");
    assert_eq!(eval("of:=QUOTIENT(10;0)"), "#DIV/0!");
    assert_eq!(eval("of:=MOD(10;3)"), "1");
    assert_eq!(eval("of:=MOD(-3;2)"), "1");
    assert_eq!(eval("of:=PRODUCT([.A1:.A3])"), "6");
    assert_eq!(eval("of:=MEDIAN([.B1:.B3])"), "20");
    assert_eq!(eval("of:=SIGN(-4)"), "-1");
    assert_eq!(eval("of:=INT(-1.5)"), "-2");
    assert_eq!(eval("of:=TRUNC(-1.5)"), "-1");
    assert_eq!(eval("of:=ROUND(2.567;2)"), "2.57");
    assert_eq!(eval("of:=ROUNDUP(2.561;2)"), "2.57");
    assert_eq!(eval("of:=ROUNDDOWN(2.569;2)"), "2.56");
    assert_eq!(eval("of:=CEILING(2.1;0.5)"), "2.5");
    assert_eq!(eval("of:=FLOOR(2.9;0.5)"), "2.5");
    assert_eq!(eval("of:=LOG(1000)"), "3");
    assert_eq!(eval("of:=LOG(8;2)"), "3");
    assert_eq!(eval("of:=POWER(2;10)"), "1024");
}

#[test]
fn trig_and_hyperbolic() {
    assert_eq!(eval("of:=SIN(0)"), "0");
    assert_eq!(eval("of:=COS(0)"), "1");
    assert_eq!(eval("of:=TAN(0)"), "0");
    assert_eq!(eval("of:=SEC(0)"), "1");
    assert_eq!(eval("of:=SINH(0)"), "0");
    assert_eq!(eval("of:=COSH(0)"), "1");
    assert_eq!(eval("of:=TANH(0)"), "0");
    assert_eq!(eval("of:=ASINH(0)"), "0");
    assert_eq!(eval("of:=ACOSH(1)"), "0");
    assert_eq!(eval("of:=ATANH(0)"), "0");
    assert_eq!(eval("of:=RADIANS(180)"), "3.1416");
    assert_eq!(eval("of:=DEGREES(1)"), "57.2958");
    assert_eq!(eval("of:=ATAN2(1;1)"), "0.7854");
}

#[test]
fn extended_statistics() {
    assert_eq!(eval("of:=STDEVP([.A1:.A3])"), "0.8165");
    assert_eq!(eval("of:=VARP([.A1:.A3])"), "0.6667");
    assert_eq!(eval("of:=STDEV([.A1:.A3])"), "1");
    assert_eq!(eval("of:=VAR([.A1:.A3])"), "1");
    assert_eq!(eval("of:=GEOMEAN([.A1:.A3])"), "1.8171");
    assert_eq!(eval("of:=HARMEAN([.A1:.A3])"), "1.6364");
    assert_eq!(eval("of:=AVEDEV([.A1:.A3])"), "0.6667");
    assert_eq!(eval("of:=DEVSQ([.A1:.A3])"), "2");
    assert_eq!(eval("of:=CORREL([.A1:.A3];[.B1:.B3])"), "1");
    assert_eq!(eval("of:=PEARSON([.A1:.A3];[.B1:.B3])"), "1");
    assert_eq!(eval("of:=COVAR([.A1:.A3];[.B1:.B3])"), "6.6667");
    assert_eq!(eval("of:=SLOPE([.B1:.B3];[.A1:.A3])"), "10");
    assert_eq!(eval("of:=INTERCEPT([.B1:.B3];[.A1:.A3])"), "0");
    assert_eq!(eval("of:=FORECAST(4;[.B1:.B3];[.A1:.A3])"), "40");
    assert_eq!(eval("of:=QUARTILE([.A1:.A3];0)"), "1");
    assert_eq!(eval("of:=QUARTILE([.A1:.A3];2)"), "2");
    assert_eq!(eval("of:=QUARTILE([.A1:.A3];4)"), "3");
    assert_eq!(eval("of:=QUARTILE([.A1:.A3];5)"), "#NUM!");
    assert_eq!(eval("of:=PERCENTRANK([.A1:.A3];2)"), "0.5");
    assert_eq!(eval("of:=PERCENTRANK([.A1:.A3];1)"), "0");
    assert_eq!(eval("of:=PERCENTRANK([.A1:.A3];3)"), "1");
    assert_eq!(eval("of:=PERCENTILE([.A1:.A3];0.5)"), "2");
    assert_eq!(eval("of:=LARGE([.B1:.B3];2)"), "20");
    assert_eq!(eval("of:=SMALL([.B1:.B3];1)"), "10");
    assert_eq!(eval("of:=LARGE([.B1:.B3];9)"), "#NUM!");
    assert_eq!(eval("of:=RANK(3;[.A1:.A3])"), "1");
    assert_eq!(eval("of:=RANK(1;[.A1:.A3];1)"), "1");
    assert_eq!(eval("of:=RANK(9;[.A1:.A3])"), "#N/A");
    assert_eq!(eval("of:=AVERAGEA([.A1:.A3])"), "2");
    assert_eq!(eval("of:=MAXA([.A1:.A3])"), "3");
    assert_eq!(eval("of:=MINA([.A1:.A3])"), "1");
    assert_eq!(eval_col("of:=MODE([.A1:.A4])", &[1.0, 1.0, 2.0, 3.0]), "1");
}

#[test]
fn extended_logical_and_info() {
    assert_eq!(eval("of:=SWITCH(2;1;\"one\";2;\"two\";\"def\")"), "two");
    assert_eq!(eval("of:=SWITCH(9;1;\"one\";\"def\")"), "def");
    assert_eq!(eval("of:=SWITCH(9;1;\"one\")"), "#N/A");
    assert_eq!(eval("of:=XOR(1;0)"), "TRUE");
    assert_eq!(eval("of:=XOR(1;1)"), "FALSE");
    assert_eq!(eval("of:=IFS(1>2;\"a\";2>1;\"b\")"), "b");
    assert_eq!(eval("of:=IFS(1>2;\"a\")"), "#N/A");
    assert_eq!(eval("of:=CHOOSE(2;\"a\";\"b\";\"c\")"), "b");
    assert_eq!(eval("of:=CHOOSE(9;\"a\")"), "#VALUE!");
    assert_eq!(eval("of:=ISEVEN(4)"), "TRUE");
    assert_eq!(eval("of:=ISEVEN(3)"), "FALSE");
    assert_eq!(eval("of:=ISODD(3)"), "TRUE");
    assert_eq!(eval("of:=ISERROR(1/0)"), "TRUE");
    assert_eq!(eval("of:=ISERROR(1)"), "FALSE");
    assert_eq!(eval("of:=ISERR(1/0)"), "TRUE");
    assert_eq!(eval("of:=ISERR(NA())"), "FALSE");
    assert_eq!(eval("of:=ISNA(NA())"), "TRUE");
    assert_eq!(eval("of:=ISNUMBER(1)"), "TRUE");
    assert_eq!(eval("of:=ISTEXT(\"x\")"), "TRUE");
    assert_eq!(eval("of:=ISLOGICAL(TRUE)"), "TRUE");
    assert_eq!(eval("of:=IFNA(NA();\"fallback\")"), "fallback");
    assert_eq!(eval("of:=IFNA(5;\"fallback\")"), "5");
    assert_eq!(eval("of:=N(5)"), "5");
    assert_eq!(eval("of:=N(TRUE)"), "1");
    assert_eq!(eval("of:=N(\"x\")"), "0");
    assert_eq!(eval("of:=TYPE(5)"), "1");
    assert_eq!(eval("of:=TYPE(\"x\")"), "2");
    assert_eq!(eval("of:=TYPE(TRUE)"), "4");
    assert_eq!(eval("of:=TYPE(1/0)"), "16");
    assert_eq!(eval("of:=ERROR.TYPE(1/0)"), "2");
    assert_eq!(eval("of:=ERROR.TYPE(NA())"), "7");
    assert_eq!(eval("of:=ERROR.TYPE(5)"), "#N/A");
    assert_eq!(eval("of:=SHEETS()"), "1");
    assert_eq!(eval("of:=SHEET()"), "1");
    assert_eq!(eval("of:=ISREF([.A1])"), "TRUE");
    assert_eq!(eval("of:=ISFORMULA([.C1])"), "TRUE");
    assert_eq!(eval("of:=ISFORMULA([.A1])"), "FALSE");
}

#[test]
fn extended_text() {
    assert_eq!(eval("of:=TEXTJOIN(\"-\";TRUE;[.A1:.A3])"), "1-2-3");
    assert_eq!(eval("of:=TEXTJOIN(\",\";FALSE;\"a\";\"\";\"b\")"), "a,,b");
    assert_eq!(eval("of:=TEXTJOIN(\",\";TRUE;\"a\";\"\";\"b\")"), "a,b");
    assert_eq!(eval("of:=CHAR(65)"), "A");
    assert_eq!(eval("of:=CODE(\"A\")"), "65");
    assert_eq!(eval("of:=CODE(\"\")"), "#VALUE!");
    assert_eq!(eval("of:=T(\"x\")"), "x");
    assert_eq!(eval("of:=T(5)"), "");
    assert_eq!(eval("of:=CLEAN(CHAR(7)&\"a\")"), "a");
    assert_eq!(eval("of:=NUMBERVALUE(\"1,234\")"), "1234");
    assert_eq!(eval("of:=NUMBERVALUE(\"abc\")"), "#VALUE!");
    assert_eq!(eval("of:=UNICHAR(65)"), "A");
    assert_eq!(eval("of:=UNICODE(\"A\")"), "65");
    assert_eq!(eval("of:=UNICHAR(0)"), "#VALUE!");
    assert_eq!(eval("of:=RIGHT(\"hello\";2)"), "lo");
    assert_eq!(eval("of:=LEFT(\"hello\")"), "h");
    assert_eq!(eval("of:=REPT(\"ab\";3)"), "ababab");
    assert_eq!(eval("of:=EXACT(\"a\";\"a\")"), "TRUE");
    assert_eq!(eval("of:=EXACT(\"a\";\"A\")"), "FALSE");
    assert_eq!(eval("of:=FIND(\"l\";\"hello\")"), "3");
    assert_eq!(eval("of:=FIND(\"L\";\"hello\")"), "#VALUE!");
    assert_eq!(eval("of:=SEARCH(\"L\";\"hello\")"), "3");
    assert_eq!(eval("of:=SUBSTITUTE(\"aaa\";\"a\";\"b\")"), "bbb");
    assert_eq!(eval("of:=REPLACE(\"hello\";2;3;\"a\")"), "hao");
    assert_eq!(eval("of:=VALUE(\"2.5\")"), "2.5");
    assert_eq!(eval("of:=VALUE(\"abc\")"), "#VALUE!");
    assert_eq!(eval("of:=PROPER(\"hello world\")"), "Hello World");
    assert_eq!(eval("of:=PROPER(\"hELLO wORLD\")"), "Hello World");
    assert_eq!(eval("of:=TRIM(\"  a   b  \")"), "a b");
    assert_eq!(eval("of:=FIXED(1234.567;2)"), "1,234.57");
    assert_eq!(eval("of:=FIXED(1234.567;2;TRUE)"), "1234.57");
    assert_eq!(eval("of:=DOLLAR(1234.5)"), "$1,234.50");
    assert_eq!(eval("of:=TEXTBEFORE(\"a,b,c\";\",\")"), "a");
    assert_eq!(eval("of:=TEXTAFTER(\"a,b,c\";\",\")"), "b,c");
    assert_eq!(eval("of:=TEXTAFTER(\"a,b,c\";\",\";2)"), "c");
    assert_eq!(eval("of:=TEXTBEFORE(\"abc\";\",\")"), "#N/A");
    assert_eq!(eval("of:=TEXT(0.5;\"0%\")"), "50%");
    assert_eq!(eval("of:=TEXT(1234.5;\"#,##0.00\")"), "1,234.50");
    assert_eq!(eval("of:=CONCAT(\"a\";[.A1:.A3])"), "a123");
}

#[test]
fn extended_dates() {
    // 2020-01-01 = 43831 (a Wednesday); 2020 is a leap year.
    assert_eq!(eval("of:=DATE(2020;1;1)"), "43831");
    assert_eq!(eval("of:=EDATE(DATE(2020;1;31);1)"), "43890"); // 2020-02-29
    assert_eq!(eval("of:=EDATE(DATE(2020;1;15);-1)"), "43814"); // 2019-12-15
    assert_eq!(eval("of:=EOMONTH(DATE(2020;1;15);0)"), "43861"); // 2020-01-31
    assert_eq!(eval("of:=EOMONTH(DATE(2020;2;3);0)"), "43890"); // 2020-02-29
    assert_eq!(eval("of:=DAYS(DATE(2020;1;10);DATE(2020;1;1))"), "9");
    assert_eq!(eval("of:=TIME(12;0;0)"), "0.5");
    assert_eq!(eval("of:=HOUR(0.5)"), "12");
    assert_eq!(eval("of:=MINUTE(0.5)"), "0");
    assert_eq!(eval("of:=SECOND(0.5)"), "0");
    assert_eq!(eval("of:=WEEKNUM(DATE(2020;1;1))"), "1");
    assert_eq!(eval("of:=WEEKNUM(DATE(2020;1;5))"), "2");
    assert_eq!(eval("of:=ISOWEEKNUM(DATE(2020;1;1))"), "1");
    assert_eq!(eval("of:=ISOWEEKNUM(DATE(2020;12;31))"), "53");
    assert_eq!(eval("of:=NETWORKDAYS(DATE(2020;1;1);DATE(2020;1;1))"), "1");
    assert_eq!(eval("of:=NETWORKDAYS(DATE(2020;1;4);DATE(2020;1;4))"), "0"); // Sat
    assert_eq!(eval("of:=NETWORKDAYS(DATE(2020;1;1);DATE(2020;1;7))"), "5");
    assert_eq!(eval("of:=WORKDAY(DATE(2020;1;3);1)"), "43836"); // Fri -> Mon
    assert_eq!(eval("of:=WORKDAY(DATE(2020;1;6);-1)"), "43833"); // Mon -> Fri
    assert_eq!(eval("of:=DATEDIF(DATE(2020;1;1);DATE(2020;1;10);\"D\")"), "9");
    assert_eq!(eval("of:=DATEDIF(DATE(2020;1;15);DATE(2021;3;20);\"M\")"), "14");
    assert_eq!(eval("of:=DATEDIF(DATE(2020;1;15);DATE(2021;3;20);\"Y\")"), "1");
    assert_eq!(eval("of:=DATEDIF(DATE(2020;1;31);DATE(2020;2;1);\"M\")"), "0");
    assert_eq!(eval("of:=DATEDIF(DATE(2019;6;15);DATE(2020;6;14);\"Y\")"), "0");
    assert_eq!(eval("of:=DATEDIF(DATE(2020;1;15);DATE(2020;3;10);\"MD\")"), "24");
    assert_eq!(eval("of:=DATEDIF(DATE(2020;1;15);DATE(2021;3;10);\"YM\")"), "1");
    assert_eq!(eval("of:=DAYS360(DATE(2020;1;1);DATE(2020;2;1))"), "30");
    assert_eq!(eval("of:=YEARFRAC(DATE(2020;1;1);DATE(2021;1;1);3)"), "1.0027");
    assert_eq!(eval("of:=YEARFRAC(DATE(2020;1;1);DATE(2020;7;1);2)"), "0.5056");
    // now_millis = 2023-11-14T22:13:20Z -> TODAY serial 45244.
    assert_eq!(eval("of:=TODAY()"), "45244");
    assert_eq!(eval("of:=NOW()"), "45244.9259");
    assert_eq!(eval("of:=DATEVALUE(43831)"), "43831");
}

#[test]
fn financial_functions() {
    assert_eq!(eval("of:=PMT(0;10;1000)"), "-100");
    assert_eq!(eval("of:=PMT(0.1;1;1000)"), "-1100");
    assert_eq!(eval("of:=FV(0;10;-100;0)"), "1000");
    assert_eq!(eval("of:=FV(0.1;1;-100)"), "100");
    assert_eq!(eval("of:=PV(0.1;1;-100)"), "90.9091");
    assert_eq!(eval("of:=PV(0;1;-100)"), "100");
    assert_eq!(eval("of:=NPV(0.1;100)"), "90.9091");
    assert_eq!(eval("of:=NPV(0;100;100)"), "200");
    assert_eq!(eval("of:=NPER(0;-100;1000)"), "10");
    assert_eq!(eval("of:=RATE(1;-1100;1000)"), "0.1");
    assert_eq!(eval("of:=IPMT(0.1;1;1;1000)"), "-100");
    assert_eq!(eval("of:=PPMT(0.1;1;1;1000)"), "-1000");
    assert_eq!(eval("of:=CUMIPMT(0.1;1;1000;1;1;0)"), "-100");
    assert_eq!(eval("of:=CUMPRINC(0.1;1;1000;1;1;0)"), "-1000");
    assert_eq!(eval("of:=SLN(1000;100;5)"), "180");
    assert_eq!(eval("of:=SYD(1000;0;5;1)"), "333.3333");
    assert_eq!(eval_col("of:=IRR([.A1:.A2])", &[-100.0, 110.0]), "0.1");
}

#[test]
fn radix_roman_bitwise() {
    assert_eq!(eval("of:=BASE(255;16)"), "FF");
    assert_eq!(eval("of:=BASE(5;2;4)"), "0101");
    assert_eq!(eval("of:=BASE(5;1)"), "#NUM!");
    assert_eq!(eval("of:=DECIMAL(\"FF\";16)"), "255");
    assert_eq!(eval("of:=DECIMAL(\"102\";2)"), "#NUM!");
    assert_eq!(eval("of:=ARABIC(\"XIV\")"), "14");
    assert_eq!(eval("of:=ARABIC(\"MCMXCIV\")"), "1994");
    assert_eq!(eval("of:=ROMAN(14)"), "XIV");
    assert_eq!(eval("of:=ROMAN(1994)"), "MCMXCIV");
    assert_eq!(eval("of:=ROMAN(0)"), "0");
    assert_eq!(eval("of:=BITAND(12;10)"), "8");
    assert_eq!(eval("of:=BITOR(12;10)"), "14");
    assert_eq!(eval("of:=BITXOR(12;10)"), "6");
    assert_eq!(eval("of:=BITLSHIFT(1;3)"), "8");
    assert_eq!(eval("of:=BITLSHIFT(8;-1)"), "4");
    assert_eq!(eval("of:=BITRSHIFT(16;2)"), "4");
    assert_eq!(eval("of:=BITRSHIFT(4;-1)"), "8");
}

#[test]
fn lookup_dimensions() {
    assert_eq!(eval("of:=ROWS([.A1:.B3])"), "3");
    assert_eq!(eval("of:=COLUMNS([.A1:.B3])"), "2");
    assert_eq!(eval("of:=ROW([.B2])"), "2");
    assert_eq!(eval("of:=COLUMN([.B2])"), "2");
    assert_eq!(eval("of:=ROW()"), "1");
    assert_eq!(eval("of:=COLUMN()"), "3");
    assert_eq!(eval("of:=HLOOKUP(10;[.A1:.B3];3;0)"), "30");
    assert_eq!(eval("of:=LOOKUP(2;[.A1:.A3];[.B1:.B3])"), "20");
    assert_eq!(eval("of:=LOOKUP(2.5;[.A1:.A3];[.B1:.B3])"), "20");
    assert_eq!(eval("of:=LOOKUP(0;[.A1:.A3];[.B1:.B3])"), "#N/A");
    assert_eq!(eval("of:=ADDRESS(2;2)"), "$B$2");
    assert_eq!(eval("of:=ADDRESS(2;2;4)"), "B2");
    assert_eq!(eval("of:=ADDRESS(1;1;2)"), "A$1");
    assert_eq!(eval("of:=ADDRESS(1;1;3)"), "$A1");
    assert_eq!(eval("of:=INDIRECT(\"A2\")"), "2");
    assert_eq!(eval("of:=INDIRECT(\"ZZ9\")"), "");
    assert_eq!(eval("of:=HYPERLINK(\"http://x\";\"label\")"), "label");
    assert_eq!(eval("of:=HYPERLINK(\"http://x\")"), "http://x");
    assert_eq!(eval("of:=MATCH(20;[.B1:.B3];0)"), "2");
    assert_eq!(eval("of:=MATCH(25;[.B1:.B3];1)"), "2");
    assert_eq!(eval("of:=INDEX([.A1:.B3];2;2)"), "20");
    assert_eq!(eval("of:=COUNTBLANK([.A1:.B3])"), "0");
    assert_eq!(eval("of:=COUNTA([.A1:.B3])"), "6");
    // RAND is seeded from now_millis: reproducible across workbooks.
    assert_eq!(eval("of:=RAND()"), eval("of:=RAND()"));
    let r: f64 = eval("of:=RANDBETWEEN(1;10)").parse().unwrap();
    assert!((1.0..=10.0).contains(&r));
}
