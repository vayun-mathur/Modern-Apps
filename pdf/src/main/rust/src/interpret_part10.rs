
#[cfg(test)]
mod refdiff_followup_tests_2 {
    use crate::*;
    use lopdf::content::Operation;
    use lopdf::{dictionary, Stream};

    fn op(name: &str, operands: Vec<Object>) -> Operation {
        Operation::new(name, operands)
    }

    fn clip_applies(prims: &[Prim]) -> usize {
        prims.iter().filter(|p| matches!(p, Prim::TextClipApply)).count()
    }

    /// §8.7.4.2 makes a `/Shading` resource "a dictionary or a stream", and
    /// §7.3.8.1 requires only the STREAM form to be indirect — ShadingTypes 1-3
    /// are dictionaries and are legally written directly in the resource
    /// dictionary. `shadings_from_resources` collects only `as_reference()`
    /// entries, so `/Sh0 sh` against a direct one painted nothing at all.
    #[test]
    fn sh_finds_a_shading_written_as_a_direct_dictionary() {
        let mut doc = Document::with_version("1.7");
        let func = doc.add_object(dictionary! {
            "FunctionType" => 2,
            "Domain" => vec![0.into(), 1.into()],
            "C0" => vec![1.0.into(), 0.0.into(), 0.0.into()],
            "C1" => vec![0.0.into(), 0.0.into(), 1.0.into()],
            "N" => 1,
        });
        // The shading itself is DIRECT in /Resources /Shading — the point of the test.
        let res = dictionary! {
            "Shading" => dictionary! {
                "Sh" => dictionary! {
                    "ShadingType" => 2,
                    "ColorSpace" => "DeviceRGB",
                    "Coords" => vec![0.into(), 0.into(), 100.into(), 0.into()],
                    "Function" => Object::Reference(func),
                },
            },
        };
        let ops = vec![op("sh", vec![Object::Name(b"Sh".to_vec())])];
        let mut prims = Vec::new();
        interpret_content_seeded(
            &doc,
            &ops,
            Some(&res),
            GraphicsState::default(),
            &mut prims,
            0,
            false,
            Some([0.0, 0.0, 200.0, 200.0]),
        );
        assert_eq!(
            prims.iter().filter(|p| matches!(p, Prim::Image { .. })).count(),
            1,
            "a direct /Shading dictionary must paint, not be silently skipped"
        );
    }

    /// §11.6.5.2 makes a soft-mask group a form XObject, so §8.10.1's `/BBox`
    /// rule applies: content the group paints outside its box must read as
    /// backdrop. The extent was seeded (so a `sh` inside knew how big to
    /// rasterize) but never emitted as a clip, so a group whose content overran
    /// its box put mask luminosity where the file said there was none — the
    /// direction that REVEALS content the file hid.
    #[test]
    fn a_soft_mask_group_clips_its_content_to_its_bbox() {
        let mut doc = Document::with_version("1.7");
        // The group paints far outside its own 0..50 /BBox.
        let group = doc.add_object(Stream::new(
            dictionary! {
                "Type" => "XObject", "Subtype" => "Form",
                "BBox" => vec![0.into(), 0.into(), 50.into(), 50.into()],
                "Group" => dictionary! { "S" => "Transparency", "CS" => "DeviceGray" },
            },
            b"1 g 0 0 500 500 re f".to_vec(),
        ));
        let extg = doc.add_object(dictionary! {
            "Type" => "ExtGState",
            "SMask" => dictionary! {
                "S" => "Luminosity",
                "G" => Object::Reference(group),
            },
        });
        let res = dictionary! { "ExtGState" => dictionary! { "GS" => Object::Reference(extg) } };
        let ops = vec![
            op("gs", vec![Object::Name(b"GS".to_vec())]),
            op("re", vec![0.into(), 0.into(), 200.into(), 200.into()]),
            op("f", vec![]),
        ];
        let mut prims = Vec::new();
        interpret_content_seeded(
            &doc,
            &ops,
            Some(&res),
            GraphicsState::default(),
            &mut prims,
            0,
            false,
            Some([0.0, 0.0, 200.0, 200.0]),
        );
        let content = prims
            .iter()
            .position(|p| matches!(p, Prim::SoftMaskContent))
            .expect("the mask bracket must have been emitted");
        let mask_side = &prims[content..];
        let clip = mask_side
            .iter()
            .position(|p| matches!(p, Prim::ClipPush { .. }))
            .expect("the group's /BBox must be pushed as a clip");
        assert!(
            mask_side[clip + 1..].iter().any(|p| matches!(p, Prim::ClipPop)),
            "the /BBox clip must be balanced"
        );
        if let Prim::ClipPush { pts, .. } = &mask_side[clip] {
            let max_x = pts.iter().fold(f32::NEG_INFINITY, |m, p| m.max(p.0));
            let max_y = pts.iter().fold(f32::NEG_INFINITY, |m, p| m.max(p.1));
            assert!(
                (max_x - 50.0).abs() < 1e-3 && (max_y - 50.0).abs() < 1e-3,
                "the clip must be the group's /BBox, got corners {pts:?}"
            );
        }
    }

    /// A `Tr 4-7` text object that crosses [`MAX_PRIMITIVES`] before its `ET`
    /// used to emit no `TextClipApply`. The consumer resets its accumulated glyph
    /// path ONLY in that arm and accumulates by UNION, so the marker going missing
    /// does not drop the clip — it leaks this object's letterforms into the next
    /// `Tr 4-7` object, which then paints through both. The comment above the
    /// `ET` arm already gives that exact leak as the reason the marker is not
    /// gated on optional-content visibility; the primitive cap reintroduced it.
    ///
    /// The cap is reachable AND recoverable, which is what makes the leak real: a
    /// failed soft-mask expansion and the per-glyph Type 3 bound both truncate
    /// `prims` back below it, so a later object can emit a marker again and pick
    /// up the abandoned outlines.
    #[test]
    fn the_text_clip_marker_survives_the_primitive_cap() {
        let mut doc = Document::with_version("1.7");
        let font = doc.add_object(dictionary! {
            "Type" => "Font", "Subtype" => "Type1", "BaseFont" => "Helvetica",
        });
        let res = dictionary! { "Font" => dictionary! { "F1" => Object::Reference(font) } };
        let ops = vec![
            op("BT", vec![]),
            op("Tf", vec![Object::Name(b"F1".to_vec()), 12.into()]),
            op("Tr", vec![7.into()]),
            op("Tj", vec![Object::string_literal("A")]),
            op("Tj", vec![Object::string_literal("B")]),
            op("ET", vec![]),
        ];
        for seed in [MAX_PRIMITIVES - 1, MAX_PRIMITIVES + 8] {
            let mut prims: Vec<Prim> = (0..seed).map(|_| Prim::ClipPop).collect();
            let before = prims.len();
            interpret_content(&doc, &ops, Some(&res), GraphicsState::default(), &mut prims, 0, false);
            let tail = &prims[before..];
            assert!(
                tail.iter().any(|p| matches!(p, Prim::TextClipApply)),
                "seed {seed}: the marker must survive the cap, or its outlines leak forward"
            );
            let opens = tail.iter().filter(|p| matches!(p, Prim::TextClipApply | Prim::ClipPush { .. })).count();
            let closes = tail.iter().filter(|p| matches!(p, Prim::ClipPop)).count();
            assert_eq!(opens, closes, "seed {seed}: the clip stack must stay balanced");
        }
    }

    /// The predicate must be CONTINUOUS in how many glyphs actually resolved.
    /// A delivery-based latch inverted the failure on a single `/ToUnicode`
    /// entry: one glyph of ten resolving leaked ~0.3% of the page, zero resolving
    /// sent no marker at all and leaked 100% of it. Keying on the operand instead
    /// means an all-unresolvable run still clips — to empty, which is a SUBSET of
    /// the aperture the file asked for rather than a superset of the whole page.
    #[test]
    fn an_unresolvable_glyph_run_still_latches_the_text_clip() {
        let mut doc = Document::with_version("1.7");
        // A /ToUnicode mapping the only code to the EMPTY string, which real
        // producers emit for hidden and ligature-component glyphs. Every glyph is
        // then dropped by draw.rs, so nothing is delivered.
        let tounicode = doc.add_object(Stream::new(
            dictionary! {},
            b"/CIDInit /ProcSet findresource begin 1 begincmap\n\
              1 beginbfchar <41> <> endbfchar\n\
              endcmap end"
                .to_vec(),
        ));
        let font = doc.add_object(dictionary! {
            "Type" => "Font", "Subtype" => "Type1", "BaseFont" => "Helvetica",
            "ToUnicode" => Object::Reference(tounicode),
        });
        let res = dictionary! { "Font" => dictionary! { "F1" => Object::Reference(font) } };
        let ops = vec![
            op("BT", vec![]),
            op("Tf", vec![Object::Name(b"F1".to_vec()), 12.into()]),
            op("Tr", vec![7.into()]),
            op("Tj", vec![Object::string_literal("AAAA")]),
            op("ET", vec![]),
        ];
        let mut prims = Vec::new();
        interpret_content(&doc, &ops, Some(&res), GraphicsState::default(), &mut prims, 0, false);
        assert!(
            !prims.iter().any(|p| matches!(p, Prim::Text { .. })),
            "precondition: no glyph may resolve, or this test passes for the wrong reason"
        );
        assert_eq!(
            prims.iter().filter(|p| matches!(p, Prim::TextClipApply)).count(),
            1,
            "a run the file asked to show must clip even when no glyph resolved, \
             or the following content paints across the whole page"
        );
    }

    /// The `d1` detector is POSITIONAL — `ops.first()`. `fix-text` asked for this
    /// pinned end to end, because if `content::repair_d0_d1` ever left an
    /// operation ahead of the `d1` (an orphaned operand promoted into an op of
    /// its own being the obvious way), detection would silently fail and the
    /// glyph's colour operators would start taking effect against §9.6.5.
    ///
    /// The other d1 test builds the operator list by hand, so it cannot catch
    /// that. This one goes through the real tokenizer + repair path a CharProc
    /// actually takes.
    #[test]
    fn the_d1_detector_survives_the_tokenizer_repair_end_to_end() {
        let charproc = b"0 0 0 0 750 750 d1\n1 1 1 rg\n0 0 100 100 re\nf";
        let ops = crate::content::strict_operations(charproc).expect("strict parse");
        assert_eq!(ops[0].operator, "d1", "the repair must leave d1 at index 0");

        let doc = Document::with_version("1.7");
        let red = rgb_to_argb(1.0, 0.0, 0.0);
        let mut gs = GraphicsState::default();
        gs.fill = red;
        let mut prims = Vec::new();
        interpret_content(&doc, &ops, None, gs.clone(), &mut prims, 0, false);
        let fills: Vec<u32> = prims
            .iter()
            .filter_map(|p| match p {
                Prim::Fill { argb, .. } => Some(*argb),
                _ => None,
            })
            .collect();
        assert_eq!(
            fills,
            vec![red],
            "the glyph must paint in the text-state colour, not the `1 1 1 rg` it declares"
        );
    }

    /// `!bytes.is_empty()` is an OPERAND property; the predicate needs an
    /// ITERATION property. `FontInfo::for_each_code`'s Identity-H/V branch is
    /// `while i + 1 < bytes.len()`, so a ONE-byte string in a two-byte font is
    /// non-empty yet yields ZERO codes and shows no glyph. Latching there sent
    /// `TextClipApply` over an empty accumulation, and with the consumer's
    /// unconditional `clipPath` that blanked every mark to the matching
    /// `ClipPop` — a whole-page regression, and strictly worse than the
    /// delivery-based predicate it replaced. Caught by `fix-text`,
    /// `hunt-missing` and `differential`.
    #[test]
    fn a_one_byte_identity_h_string_shows_no_glyph_and_must_not_latch() {
        let mut doc = Document::with_version("1.7");
        let descendant = doc.add_object(dictionary! {
            "Type" => "Font", "Subtype" => "CIDFontType2", "BaseFont" => "Test",
            "CIDSystemInfo" => dictionary! {
                "Registry" => Object::string_literal("Adobe"),
                "Ordering" => Object::string_literal("Identity"),
                "Supplement" => 0,
            },
            "DW" => 1000,
        });
        let font = doc.add_object(dictionary! {
            "Type" => "Font", "Subtype" => "Type0", "BaseFont" => "Test",
            "Encoding" => "Identity-H",
            "DescendantFonts" => vec![Object::Reference(descendant)],
        });
        let res = dictionary! { "Font" => dictionary! { "F1" => Object::Reference(font) } };
        let clips = |s: &[u8]| {
            let ops = vec![
                op("BT", vec![]),
                op("Tf", vec![Object::Name(b"F1".to_vec()), 12.into()]),
                op("Tr", vec![7.into()]),
                op("Tj", vec![Object::String(s.to_vec(), lopdf::StringFormat::Literal)]),
                op("ET", vec![]),
            ];
            let mut prims = Vec::new();
            interpret_content(&doc, &ops, Some(&res), GraphicsState::default(), &mut prims, 0, false);
            prims.iter().filter(|p| matches!(p, Prim::TextClipApply)).count()
        };
        // Precondition: two bytes IS one code in this font, so the fixture really
        // is an Identity-H font and the one-byte case is the odd one out.
        assert_eq!(clips(&[0x00, 0x41]), 1, "precondition: a full 2-byte code shows a glyph");
        assert_eq!(
            clips(&[0x41]),
            0,
            "a stray byte shows no glyph, so latching would clip the page to empty"
        );
    }

    /// §11.6.5.2: `/BC` is OPTIONAL and defaults to "the colour representing a
    /// zero luminosity in the group's colour space" — BLACK, not "no backdrop".
    /// Gating the whole backdrop fill on `/BC` being PRESENT treated a defaulted
    /// value as an absent feature, leaving the mask surface without the fully
    /// opaque backdrop the clause composites against. Reported by `hunt-wrong2`.
    #[test]
    fn a_luminosity_mask_gets_its_default_black_backdrop_without_bc() {
        let backdrops = |bc: Option<Object>| {
            let mut doc = Document::with_version("1.7");
            let group = doc.add_object(Stream::new(
                dictionary! {
                    "Type" => "XObject", "Subtype" => "Form",
                    "BBox" => vec![0.into(), 0.into(), 50.into(), 50.into()],
                    "Group" => dictionary! { "S" => "Transparency", "CS" => "DeviceGray" },
                },
                b"1 g 0 0 50 50 re f".to_vec(),
            ));
            let mut smask = dictionary! {
                "S" => "Luminosity",
                "G" => Object::Reference(group),
            };
            if let Some(v) = bc {
                smask.set("BC", v);
            }
            let extg = doc.add_object(dictionary! { "Type" => "ExtGState", "SMask" => smask });
            let res = dictionary! { "ExtGState" => dictionary! { "GS" => Object::Reference(extg) } };
            let ops = vec![
                op("gs", vec![Object::Name(b"GS".to_vec())]),
                op("re", vec![0.into(), 0.into(), 200.into(), 200.into()]),
                op("f", vec![]),
            ];
            let mut prims = Vec::new();
            interpret_content_seeded(
                &doc, &ops, Some(&res), GraphicsState::default(), &mut prims, 0, false,
                Some([0.0, 0.0, 200.0, 200.0]),
            );
            let content = prims
                .iter()
                .position(|p| matches!(p, Prim::SoftMaskContent))
                .expect("the mask bracket must have been emitted");
            // The backdrop is the first fill on the mask side, before the group's
            // own `1 g` fill and before the /BBox clip.
            prims[content..]
                .iter()
                .take_while(|p| !matches!(p, Prim::ClipPush { .. }))
                .find_map(|p| match p {
                    Prim::Fill { argb, .. } => Some(*argb),
                    _ => None,
                })
        };

        // Precondition: an explicit /BC still produces its own colour, so this
        // fixture really does reach the backdrop path.
        assert_eq!(
            backdrops(Some(Object::Array(vec![Object::Real(1.0)]))),
            Some(rgb_to_argb(1.0, 1.0, 1.0)),
            "an explicit white /BC must still paint white"
        );
        assert_eq!(
            backdrops(None),
            Some(0xFF00_0000),
            "an absent /BC defaults to zero luminosity, not to no backdrop at all"
        );
    }

    /// Residual of the `/BC` fix, found by `hunt-wrong2`: the backdrop block was
    /// still wrapped in `if let Some(rect) = ...BBox...`, but `rect` is only used
    /// on the no-extent FALLBACK path. So a mask group with no `/BBox` got no
    /// backdrop even when `masked_extent` was `Some` and the extent needed to
    /// paint one was right there — the pre-fix behaviour surviving in a narrower
    /// case. §8.10.2 makes `/BBox` required, but producers omit it.
    #[test]
    fn a_luminosity_mask_without_a_bbox_still_gets_its_backdrop() {
        let mut doc = Document::with_version("1.7");
        // Deliberately NO /BBox on the group: the point of the test.
        let group = doc.add_object(Stream::new(
            dictionary! {
                "Type" => "XObject", "Subtype" => "Form",
                "Group" => dictionary! { "S" => "Transparency", "CS" => "DeviceGray" },
            },
            b"1 g 0 0 50 50 re f".to_vec(),
        ));
        let extg = doc.add_object(dictionary! {
            "Type" => "ExtGState",
            "SMask" => dictionary! { "S" => "Luminosity", "G" => Object::Reference(group) },
        });
        let res = dictionary! { "ExtGState" => dictionary! { "GS" => Object::Reference(extg) } };
        let ops = vec![
            op("gs", vec![Object::Name(b"GS".to_vec())]),
            op("re", vec![0.into(), 0.into(), 200.into(), 200.into()]),
            op("f", vec![]),
        ];
        let mut prims = Vec::new();
        interpret_content_seeded(
            &doc, &ops, Some(&res), GraphicsState::default(), &mut prims, 0, false,
            Some([0.0, 0.0, 200.0, 200.0]),
        );
        let content = prims
            .iter()
            .position(|p| matches!(p, Prim::SoftMaskContent))
            .expect("the mask bracket must have been emitted");
        assert_eq!(
            prims[content..].iter().find_map(|p| match p {
                Prim::Fill { argb, .. } => Some(*argb),
                _ => None,
            }),
            Some(0xFF00_0000),
            "the masked extent supplies the area, so a missing /BBox must not skip the backdrop"
        );
    }
}