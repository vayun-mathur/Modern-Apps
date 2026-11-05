#[cfg(test)]
mod tests_cont {
    use super::*;
    use super::tests::{num_operands, operators};
    /// §8.4.3.6 Table 52: a conforming `d` takes an ARRAY then a number, so the
    /// repair must never touch one — including the empty-array "solid" form,
    /// which has the same operand COUNT as a mangled `d0`.
    #[test]
    fn strict_operations_leaves_a_real_dash_operator_alone() {
        for src in [
            &b"[3 3] 0 d\n0 0 m\n"[..],
            &b"[] 0 d\n0 0 m\n"[..],
            &b"[2 2] 0 d\n"[..],
        ] {
            let ops = strict_operations(src).expect("strict");
            assert_eq!(ops[0].operator, "d", "rewrote a conforming dash in {src:?}");
            if let Some(next) = ops.get(1) {
                assert_eq!(next.operands.len(), 2, "ate an operand of the next op in {src:?}");
            }
        }
    }

    /// The precondition that keeps a genuinely malformed `d` intact: the leaked
    /// digit must actually be sitting there as the next operation's first
    /// operand. `3 3 d` is not a legal dash, but nothing follows it that looks
    /// like the split-off `0`, so it stays a `d`.
    #[test]
    fn strict_operations_requires_the_leaked_digit_before_rewriting() {
        let ops = strict_operations(b"3 3 d\n5 7 m\n").expect("strict");
        assert_eq!(ops[0].operator, "d", "rewrote a `d` with no leaked digit after it");
        assert_eq!(num_operands(&ops[1]), vec![5.0, 7.0]);

        // Six numeric operands, but the next op does not begin with `1`.
        let ops = strict_operations(b"0 0 0 0 750 750 d\n5 7 m\n").expect("strict");
        assert_eq!(ops[0].operator, "d");
        assert_eq!(num_operands(&ops[1]), vec![5.0, 7.0]);
    }

    #[test]
    fn page_operations_handles_a_page_with_no_contents() {
        let mut doc = Document::with_version("1.5");
        let page_id = doc.add_object(dictionary! {
            "Type" => "Page",
            "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
        });
        let (ops, recovered) = page_operations(&doc, page_id);
        assert!(ops.is_empty());
        assert!(!recovered);
    }

    #[test]
    fn stream_operations_recovers_a_form_xobject_lopdf_cannot_parse() {
        // A form XObject / soft-mask group / tiling cell containing an inline image
        // hits Content::decode directly, and the caller drops the whole stream on Err —
        // so one BI blanks the entire form exactly as it blanks a whole page.
        let doc = Document::with_version("1.5");
        let mut content = b"q 0 0 1 rg\n".to_vec();
        content.extend_from_slice(b"BI /W 2 /H 2 /CS /G ID ");
        content.extend_from_slice(&[0x00, 0x40, 0x80, 0xFF]);
        content.extend_from_slice(b" EI\nQ 5 5 20 20 re f\n");
        let stream = Stream::new(dictionary! { "Type" => "XObject", "Subtype" => "Form" }, content);

        // lopdf's raw decoder succeeds here (it skips the undecodable image
        // and keeps the operators), so it cannot establish the P0. The strict
        // content parse — the path the app actually takes — must fail on the
        // dropped image instead of succeeding without it.
        assert!(
            crate::content::strict_operations(&stream.content).is_none(),
            "precondition: the strict content parse is expected to reject this nested stream"
        );

        let ops = stream_operations(&doc, &stream);
        let names = operators(&ops);
        for op in ["q", "rg", "BI", "Q", "re", "f"] {
            assert!(names.contains(&op.to_string()), "lost `{op}`: {names:?}");
        }
    }

    #[test]
    fn the_depth_pre_check_only_counts_real_nesting() {
        // The guard decides whether lopdf's unbounded strict parser is safe to call, so a
        // false negative is a process kill. But a false POSITIVE silently demotes healthy
        // content to the lenient tokenizer, so brackets inside strings and comments must
        // not count.
        assert!(!nesting_is_too_deep(b"BT [(x)] TJ ET", MAX_DEPTH));
        let deep = |n: usize| {
            let mut v = b"BT ".to_vec();
            v.extend(std::iter::repeat_n(b'[', n));
            v.extend_from_slice(b"(x)");
            v.extend(std::iter::repeat_n(b']', n));
            v.extend_from_slice(b" TJ ET");
            v
        };
        assert!(!nesting_is_too_deep(&deep(MAX_DEPTH as usize), MAX_DEPTH));
        assert!(nesting_is_too_deep(&deep(MAX_DEPTH as usize + 1), MAX_DEPTH));
        // §7.3.4.2: a literal string may contain unescaped balanced parens and escaped
        // anything. None of these brackets are nesting.
        let mut s = b"BT (".to_vec();
        s.extend(std::iter::repeat_n(b'[', 200));
        s.extend_from_slice(b"(nested) \\) \\( ");
        s.extend_from_slice(b") Tj ET");
        assert!(!nesting_is_too_deep(&s, MAX_DEPTH));
        // §7.2.4 comment, and a §7.3.4.3 hex string.
        let mut c = b"% ".to_vec();
        c.extend(std::iter::repeat_n(b'[', 200));
        c.extend_from_slice(b"\nBT <");
        c.extend(std::iter::repeat_n(b'A', 40));
        c.extend_from_slice(b"> Tj ET");
        assert!(!nesting_is_too_deep(&c, MAX_DEPTH));
        // Dictionaries count, and an unterminated construct must not run past the end.
        assert!(nesting_is_too_deep(&b"<<".repeat(MAX_DEPTH as usize + 1), MAX_DEPTH));
        assert!(!nesting_is_too_deep(b"BT (unterminated", MAX_DEPTH));
        assert!(!nesting_is_too_deep(b"BT <unterminated", MAX_DEPTH));
    }

    #[test]
    fn stream_operations_leaves_a_healthy_nested_stream_to_lopdf() {
        let doc = Document::with_version("1.5");
        let stream = Stream::new(Dictionary::new(), b"q 1 0 0 1 3 4 cm 0 0 10 10 re f Q".to_vec());
        let strict = Content::decode(&stream.content).expect("precondition").operations;
        assert_eq!(operators(&stream_operations(&doc, &stream)), operators(&strict));
    }

    #[test]
    fn arbitrary_bytes_never_panic() {
        // Deterministic LCG, so a failure is always reproducible.
        let mut state: u32 = 0x1234_5678;
        let mut buf = vec![0u8; 4096];
        for _ in 0..64 {
            for slot in buf.iter_mut() {
                state = state.wrapping_mul(1_103_515_245).wrapping_add(12_345);
                *slot = (state >> 16) as u8;
            }
            let _ = parse_operations_lenient(&buf);
        }
        // Bytes biased towards content-stream syntax exercise deeper paths.
        let alphabet = b"BI ID EI /W /H /CS /G 0123456789 <<>>[]()\\% qQmlScmTjBTET";
        for _ in 0..64 {
            for slot in buf.iter_mut() {
                state = state.wrapping_mul(1_103_515_245).wrapping_add(12_345);
                *slot = alphabet[(state >> 16) as usize % alphabet.len()];
            }
            let _ = parse_operations_lenient(&buf);
        }
    }

    #[test]
    fn nesting_is_bounded() {
        let deep = format!("{}1{} m", "[".repeat(500), "]".repeat(500));
        let ops = parse_operations_lenient(deep.as_bytes());
        // The point is that it returns at all rather than overflowing the stack.
        assert!(operators(&ops).contains(&"m".to_string()));
    }

    #[test]
    fn inline_length_candidates_reject_a_filter_without_length() {
        let mut d = Dictionary::new();
        d.set("W", Object::Integer(4));
        d.set("H", Object::Integer(4));
        d.set("BPC", Object::Integer(8));
        d.set("CS", Object::Name(b"G".to_vec()));
        assert_eq!(inline_len_candidates(&d, 1024), vec![16]);
        d.set("F", Object::Name(b"AHx".to_vec()));
        assert!(
            inline_len_candidates(&d, 1024).is_empty(),
            "a filtered image's encoded length is not derivable from its geometry"
        );
        d.set("L", Object::Integer(7));
        assert_eq!(inline_len_candidates(&d, 1024), vec![7], "/L wins");
    }

    #[test]
    fn inline_row_length_rounds_up_to_whole_bytes() {
        // 7 columns at 1bpc is 1 byte per row, not 7/8 of one.
        let mut d = Dictionary::new();
        d.set("W", Object::Integer(7));
        d.set("H", Object::Integer(3));
        d.set("IM", Object::Boolean(true));
        assert_eq!(inline_len_candidates(&d, 1024), vec![3]);
    }

    #[test]
    fn a_length_longer_than_the_stream_is_not_allocated() {
        // A hostile /L must never drive an allocation.
        let mut d = Dictionary::new();
        d.set("W", Object::Integer(1));
        d.set("H", Object::Integer(1));
        d.set("L", Object::Integer(i64::MAX));
        assert!(inline_len_candidates(&d, 64).iter().all(|&n| n <= 64));
    }
}