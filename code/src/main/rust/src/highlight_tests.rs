//! Unit tests for `grammar`, `kind_for` and `highlight_source`.
//!
//! Span expectations are byte offsets `(start, end, kind)` verified against
//! the bundled tree-sitter grammars' actual highlight queries: a few
//! grammars (TypeScript, C++) only capture a narrow set of nodes, so those
//! tests assert only the spans those queries really produce.

use super::{grammar, highlight_source, kind_for};

const KINDS: std::ops::RangeInclusive<i32> = 0..=7;

fn out(language_id: &str, source: &str) -> Vec<i32> {
    highlight_source(language_id, source)
        .unwrap_or_else(|| panic!("expected Some for {language_id:?}"))
}

fn assert_contains(v: &[i32], start: i32, end: i32, kind: i32) {
    assert!(
        v.chunks_exact(3).any(|t| t == [start, end, kind]),
        "expected span ({start}, {end}, {kind}) in {v:?}"
    );
}

fn assert_well_formed(source: &str, v: &[i32]) {
    assert_eq!(v.len() % 3, 0, "packed triples: {v:?}");
    for t in v.chunks_exact(3) {
        let (start, end, kind) = (t[0], t[1], t[2]);
        assert!(0 <= start && start < end, "bad range {t:?} in {v:?}");
        assert!((end as usize) <= source.len(), "end past source {t:?}");
        assert!(KINDS.contains(&kind), "kind out of range {t:?}");
        assert!(source.get(start as usize..end as usize).is_some(), "off char boundary {t:?}");
    }
}

// ---------------------------------------------------------------- kind_for

#[test]
fn kind_for_maps_every_primary_prefix() {
    let cases: &[(&str, i32)] = &[
        ("keyword", 0),
        ("string", 1),
        ("char", 1),
        ("number", 2),
        ("constant", 2),
        ("float", 2),
        ("comment", 3),
        ("attribute", 4),
        ("annotation", 4),
        ("function", 5),
        ("method", 5),
        ("type", 6),
        ("constructor", 6),
        ("namespace", 6),
        ("property", 7),
        ("variable", 7),
        ("field", 7),
    ];
    for &(name, want) in cases {
        assert_eq!(kind_for(name), want, "kind_for({name:?})");
    }
}

#[test]
fn kind_for_dotted_names_use_first_component() {
    let cases: &[(&str, i32)] = &[
        ("keyword.control", 0),
        ("keyword.control.import", 0),
        ("keyword.operator", 0),
        ("string.special", 1),
        ("string.escape", 1),
        ("char.special", 1),
        ("number.float", 2),
        ("constant.builtin", 2),
        ("constant.numeric.integer", 2),
        ("float.literal", 2),
        ("comment.documentation", 3),
        ("attribute.builtin", 4),
        ("annotation.type", 4),
        ("function.builtin", 5),
        ("function.method", 5),
        ("function.call", 5),
        ("method.call", 5),
        ("method.definition", 5),
        ("type.builtin", 6),
        ("type.definition", 6),
        ("constructor.call", 6),
        ("namespace.definition", 6),
        ("property.definition", 7),
        ("property.builtin", 7),
        ("variable.member", 7),
        ("variable.parameter", 7),
        ("variable.builtin", 7),
        ("field.definition", 7),
        ("keyword.", 0),
    ];
    for &(name, want) in cases {
        assert_eq!(kind_for(name), want, "kind_for({name:?})");
    }
}

#[test]
fn kind_for_unmapped_names_return_minus_one() {
    // Genuinely unmapped prefixes (note `variable.parameter` maps via
    // `variable`, so it must not appear here).
    let cases: &[&str] = &[
        "operator",
        "operator.assignment",
        "punctuation",
        "punctuation.bracket",
        "punctuation.delimiter",
        "tag",
        "tag.delimiter",
        "spell",
        "embedded",
        "error",
        "none",
        "foo",
        "variable2",
        "keywords",
        "Keyword",
        "KEYWORD",
        "String",
        "",
        ".",
        ".keyword",
        ".string",
        "..keyword",
        " keyword",
    ];
    for &name in cases {
        assert_eq!(kind_for(name), -1, "kind_for({name:?})");
    }
}

// ----------------------------------------------------------------- grammar

#[test]
fn grammar_resolves_all_ten_language_ids() {
    for id in ["kotlin", "java", "javascript", "typescript", "python", "rust", "go", "json", "c", "cpp"] {
        let (_, query) = grammar(id).unwrap_or_else(|| panic!("grammar({id:?})"));
        assert!(!query.is_empty(), "empty highlights query for {id}");
    }
}

#[test]
fn grammar_rejects_unknown_empty_and_case_variants() {
    for id in [
        "", "unknown", "Kotlin", "KOTLIN", "RUST", "Rust", "Java", "CPP", "C", "kotlin ", " kotlin",
        "rs", "kt", "js", "ts", "py", "golang", "c++", "C++", "python3", "java ",
    ] {
        assert!(grammar(id).is_none(), "grammar({id:?}) should be None");
    }
}

// ---------------------------------------------------------- highlight_source

#[test]
fn highlight_unknown_language_returns_none() {
    for id in ["", "unknown", "Kotlin", "RUST", "rs", "c++"] {
        assert_eq!(highlight_source(id, "fn main() {}"), None, "id={id:?}");
        assert_eq!(highlight_source(id, ""), None, "id={id:?}");
    }
}

#[test]
fn highlight_empty_source_is_some_empty_for_all_languages() {
    for id in ["kotlin", "java", "javascript", "typescript", "python", "rust", "go", "json", "c", "cpp"] {
        assert_eq!(highlight_source(id, ""), Some(Vec::new()), "empty {id}");
    }
    // Whitespace-only source parses too, with no captures expected.
    assert_eq!(highlight_source("rust", "  \n\t "), Some(Vec::new()));
}

#[test]
fn highlight_rust_fn_main_exact_spans() {
    let src = "fn main() {}";
    let v = out("rust", src);
    assert_eq!(v, vec![0, 2, 0, 3, 7, 5]);
    assert_eq!(&src[0..2], "fn");
    assert_eq!(&src[3..7], "main");
    assert_well_formed(src, &v);
}

#[test]
fn highlight_rust_keyword_number_comment_string() {
    let src = "let x = 42; // hi\nlet s = \"hey\";";
    let v = out("rust", src);
    assert_eq!(v, vec![0, 3, 0, 8, 10, 2, 12, 17, 3, 18, 21, 0, 26, 31, 1]);
    assert_well_formed(src, &v);
}

#[test]
fn highlight_rust_struct_types_and_numbers() {
    let src = "struct Foo { x: i32 }\nfn f() -> i32 { 3 }\n";
    let v = out("rust", src);
    assert_contains(&v, 0, 6, 0); // struct
    assert_contains(&v, 7, 10, 6); // Foo
    assert_contains(&v, 13, 14, 7); // x (field)
    assert_contains(&v, 16, 19, 6); // i32
    assert_contains(&v, 25, 26, 5); // f
    assert_contains(&v, 38, 39, 2); // 3
    assert_well_formed(src, &v);
}

#[test]
fn highlight_rust_comment_only_source() {
    let src = "// just a comment\n";
    assert_eq!(out("rust", src), vec![0, 17, 3]);
}

#[test]
fn highlight_kotlin_function_keyword_and_number() {
    let src = "fun main() { val x = 42 }";
    let v = out("kotlin", src);
    assert_contains(&v, 0, 3, 0); // fun
    assert_contains(&v, 4, 8, 5); // main
    assert_contains(&v, 13, 16, 0); // val
    assert_contains(&v, 21, 23, 2); // 42
    assert_well_formed(src, &v);
}

#[test]
fn highlight_kotlin_class_and_type() {
    let src = "class Foo { fun bar(): Int = 1 }";
    let v = out("kotlin", src);
    assert_contains(&v, 0, 5, 0); // class
    assert_contains(&v, 6, 9, 6); // Foo
    assert_contains(&v, 16, 19, 5); // bar
    assert_contains(&v, 23, 26, 6); // Int
    assert_contains(&v, 29, 30, 2); // 1
    assert_well_formed(src, &v);
}

#[test]
fn highlight_kotlin_string_comment_and_float() {
    let src = "val s = \"hi\" // c\nval n = 3.14\n";
    let v = out("kotlin", src);
    assert_contains(&v, 8, 12, 1); // "hi"
    assert_contains(&v, 13, 17, 3); // // c
    assert_contains(&v, 26, 30, 2); // 3.14
    assert_well_formed(src, &v);
}

#[test]
fn highlight_kotlin_annotation() {
    let src = "@Deprecated(\"x\") fun f() {}\n";
    let v = out("kotlin", src);
    assert_contains(&v, 1, 11, 4); // Deprecated
    assert_contains(&v, 12, 15, 1); // "x"
    assert_contains(&v, 17, 20, 0); // fun
    assert_well_formed(src, &v);
}

#[test]
fn highlight_java_class_string_and_number() {
    let src = "class Foo { int x = 42; String s = \"hi\"; }";
    let v = out("java", src);
    assert_contains(&v, 0, 5, 0); // class
    assert_contains(&v, 6, 9, 6); // Foo
    assert_contains(&v, 12, 15, 6); // int
    assert_contains(&v, 20, 22, 2); // 42
    assert_contains(&v, 24, 30, 6); // String
    assert_contains(&v, 35, 39, 1); // "hi"
    assert_well_formed(src, &v);
}

#[test]
fn highlight_java_annotation_and_comment() {
    let ann = "@Override\nclass A {}\n";
    let v = out("java", ann);
    assert_contains(&v, 1, 9, 4); // Override
    assert_contains(&v, 10, 15, 0); // class

    let src = "// c\npublic class A { private int f() { return 1; } }\n";
    let v = out("java", src);
    assert_contains(&v, 0, 4, 3); // // c
    assert_contains(&v, 34, 35, 5); // f
    assert_contains(&v, 47, 48, 2); // 1
    assert_well_formed(src, &v);
}

#[test]
fn highlight_python_def_return_number_comment_string() {
    let src = "def foo(x):\n    return 42  # hi\ns = \"hey\"\n";
    let v = out("python", src);
    assert_contains(&v, 0, 3, 0); // def
    assert_contains(&v, 4, 7, 5); // foo
    assert_contains(&v, 16, 22, 0); // return
    assert_contains(&v, 23, 25, 2); // 42
    assert_contains(&v, 27, 31, 3); // # hi
    assert_contains(&v, 36, 41, 1); // "hey"
    assert_well_formed(src, &v);
}

#[test]
fn highlight_python_class_method_and_float() {
    let cls = "class Foo:\n    def bar(self):\n        return \"x\"\n";
    let v = out("python", cls);
    assert_contains(&v, 0, 5, 0); // class
    assert_contains(&v, 6, 9, 6); // Foo
    assert_contains(&v, 19, 22, 5); // bar
    assert_contains(&v, 45, 48, 1); // "x"
    assert_well_formed(cls, &v);

    let flt = "x = 1.5\n";
    assert_contains(&out("python", flt), 4, 7, 2); // 1.5

    let single = "# comment\nx = 'single'\n";
    let v = out("python", single);
    assert_contains(&v, 0, 9, 3); // # comment
    assert_contains(&v, 14, 22, 1); // 'single'
}

#[test]
fn highlight_json_keys_numbers_and_constants() {
    let src = "{\"key\": 42, \"flag\": true, \"s\": \"v\"}";
    let v = out("json", src);
    assert_contains(&v, 1, 6, 1); // "key"
    assert_contains(&v, 8, 10, 2); // 42
    assert_contains(&v, 20, 24, 2); // true (constant)
    assert_contains(&v, 31, 34, 1); // "v"
    assert_well_formed(src, &v);

    let arr = "{\"a\": [1, 2.5, null, true]}\n";
    let v = out("json", arr);
    assert_contains(&v, 7, 8, 2); // 1
    assert_contains(&v, 10, 13, 2); // 2.5
    assert_contains(&v, 15, 19, 2); // null
    assert_well_formed(arr, &v);
}

#[test]
fn highlight_go_package_func_number_string_comment() {
    let src = "package main\nfunc main() { x := 42 }\n";
    let v = out("go", src);
    assert_contains(&v, 0, 7, 0); // package
    assert_contains(&v, 13, 17, 0); // func
    assert_contains(&v, 18, 22, 5); // main
    assert_contains(&v, 32, 34, 2); // 42
    assert_well_formed(src, &v);

    let src2 = "package main\n// c\nvar s = \"str\"\n";
    let v = out("go", src2);
    assert_contains(&v, 13, 17, 3); // // c
    assert_contains(&v, 26, 31, 1); // "str"
    assert_well_formed(src2, &v);
}

#[test]
fn highlight_c_type_function_keyword_number_comment() {
    let src = "int main(void) { return 42; } // hi\n";
    let v = out("c", src);
    assert_contains(&v, 0, 3, 6); // int
    assert_contains(&v, 4, 8, 5); // main
    assert_contains(&v, 17, 23, 0); // return
    assert_contains(&v, 24, 26, 2); // 42
    assert_contains(&v, 30, 35, 3); // // hi
    assert_well_formed(src, &v);
}

#[test]
fn highlight_c_include_char_and_float() {
    let inc = "#include <stdio.h>\nint x = 1;\n";
    let v = out("c", inc);
    assert_contains(&v, 0, 8, 0); // #include
    assert_contains(&v, 9, 18, 1); // <stdio.h>

    // A C character literal is captured as a constant, hence kind 2.
    let src = "/* block */ char c = 'a'; float f = 1.5f;\n";
    let v = out("c", src);
    assert_contains(&v, 0, 11, 3); // /* block */
    assert_contains(&v, 12, 16, 6); // char (type)
    assert_contains(&v, 21, 24, 2); // 'a'
    assert_contains(&v, 36, 40, 2); // 1.5f
    assert_well_formed(src, &v);
}

#[test]
fn highlight_javascript_function_and_friends() {
    let src = "function foo() { return 42; } // hi\nconst s = \"x\";\n";
    let v = out("javascript", src);
    assert_contains(&v, 0, 8, 0); // function
    assert_contains(&v, 9, 12, 5); // foo
    assert_contains(&v, 17, 23, 0); // return
    assert_contains(&v, 24, 26, 2); // 42
    assert_contains(&v, 30, 35, 3); // // hi
    assert_contains(&v, 46, 49, 1); // "x"
    assert_well_formed(src, &v);
}

#[test]
fn highlight_typescript_interface_and_types() {
    // This grammar's bundled query captures types plus `interface`/`type`
    // keywords; it does not capture e.g. `function`/`const` keywords.
    let src = "interface Foo { x: number }\ntype A = string;\n";
    let v = out("typescript", src);
    assert_contains(&v, 0, 9, 0); // interface
    assert_contains(&v, 10, 13, 6); // Foo
    assert_contains(&v, 19, 25, 6); // number
    assert_contains(&v, 28, 32, 0); // type
    assert_contains(&v, 33, 34, 6); // A
    assert_contains(&v, 37, 43, 6); // string
    assert_well_formed(src, &v);

    let fn_src = "function foo(): number { return 42; }\n";
    assert_contains(&out("typescript", fn_src), 16, 22, 6); // number
}

#[test]
fn highlight_cpp_class_function_and_namespace() {
    // This grammar's bundled query captures `class`/`namespace` keywords
    // and member function names; plain `int main() {}` yields no spans.
    let src = "class Foo { int bar() { return 1; } };\n";
    let v = out("cpp", src);
    assert_contains(&v, 0, 5, 0); // class
    assert_contains(&v, 16, 19, 5); // bar
    assert_well_formed(src, &v);

    let ns = "namespace foo { class Bar { public: int x; }; }\n";
    let v = out("cpp", ns);
    assert_contains(&v, 0, 9, 0); // namespace
    assert_contains(&v, 16, 21, 0); // class
    assert_well_formed(ns, &v);

    assert_eq!(highlight_source("cpp", "int main() { return 42; }\n"), Some(Vec::new()));
}

#[test]
fn highlight_unicode_uses_utf8_byte_offsets() {
    // "é" is 2 UTF-8 bytes and "😀" is 4, so the string literal occupies
    // bytes 8..16 even though it is only 5 chars wide.
    let src = "let s = \"é😀\"; let n = 1;";
    let v = out("rust", src);
    assert_contains(&v, 8, 16, 1);
    assert_contains(&v, 26, 27, 2); // 1
    assert_eq!(&src.as_bytes()[8..16], "\"é😀\"".as_bytes());
    assert_eq!(&src[8..16], "\"é😀\"");
    assert_well_formed(src, &v);
}

#[test]
fn highlight_longer_snippet_is_well_formed_and_ordered() {
    let src = "fn main() { let a = 1; let b = 2; println!(\"{}\", a); }";
    let v = out("rust", src);
    assert_contains(&v, 0, 2, 0); // fn
    assert_contains(&v, 3, 7, 5); // main
    assert_contains(&v, 34, 41, 5); // println
    assert_contains(&v, 43, 47, 1); // "{}"
    assert_well_formed(src, &v);
    // Captures arrive in match order: starts are non-decreasing here.
    let starts: Vec<i32> = v.chunks_exact(3).map(|t| t[0]).collect();
    assert!(starts.windows(2).all(|w| w[0] <= w[1]), "starts unordered: {starts:?}");
}

#[test]
fn highlight_every_language_returns_some_on_plain_identifier() {
    for id in ["kotlin", "java", "javascript", "typescript", "python", "rust", "go", "json", "c", "cpp"] {
        let v = highlight_source(id, "x");
        assert!(v.is_some(), "expected Some for {id}");
        assert_well_formed("x", &v.unwrap());
    }
}

#[test]
fn highlight_malformed_source_still_returns_some() {
    // Tree-sitter is error-tolerant: broken syntax parses to an (error)
    // tree rather than failing, so the result is `Some`, well-formed.
    for (id, src) in [
        ("rust", "fn { (("),
        ("python", "def :\n  (("),
        ("json", "{\"a\": }"),
        ("java", "class {"),
    ] {
        let v = highlight_source(id, src)
            .unwrap_or_else(|| panic!("expected Some for {id} {src:?}"));
        assert_well_formed(src, &v);
    }
}
