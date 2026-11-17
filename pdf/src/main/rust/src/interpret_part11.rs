
#[cfg(test)]
mod blind_reaudit_r4_tests {
    use crate::*;
    use lopdf::content::Operation;
    use lopdf::{dictionary, Stream};

    fn op(name: &str, operands: Vec<Object>) -> Operation {
        Operation::new(name, operands)
    }

    fn run(ops: &[Operation], res: Option<&lopdf::Dictionary>) -> Vec<Prim> {
        let doc = Document::with_version("1.7");
        let mut prims = Vec::new();
        interpret_content(&doc, ops, res, GraphicsState::default(), &mut prims, 0, false);
        prims
    }

    /// §8.5.4: "the clipping path operator shall not alter the current clipping
    /// path at the time it is invoked... the painting operation shall be
    /// unaffected by the new clipping path" — the pending `W` becomes the clip
    /// only AFTER the painting operator that terminates the path object.
    ///
    /// Committing it first clips the stroke to its own centreline, so `W S`
    /// renders at half width (and `W B`'s stroke loses its outer half). The
    /// order of `Prim::Stroke` and `Prim::ClipPush` in the stream IS the order
    /// the renderer applies them, so it is the thing to assert.
    #[test]
    fn a_pending_clip_takes_effect_only_after_the_painting_operator() {
        for painter in ["S", "f", "B"] {
            let ops = vec![
                op("re", vec![0.into(), 0.into(), 100.into(), 100.into()]),
                op("W", vec![]),
                op(painter, vec![]),
            ];
            let prims = run(&ops, None);
            let clip = prims
                .iter()
                .position(|p| matches!(p, Prim::ClipPush { .. }))
                .unwrap_or_else(|| panic!("`W {painter}` must still commit the clip"));
            let paint = prims
                .iter()
                .position(|p| matches!(p, Prim::Fill { .. } | Prim::Stroke { .. }))
                .unwrap_or_else(|| panic!("`W {painter}` must still paint"));
            assert!(
                paint < clip,
                "`W {painter}`: paint at {paint} must precede the clip at {clip}, \
                 otherwise the operator paints through its own clip"
            );
        }
        // `n` paints nothing but must still commit the clip (§8.5.3 Table 60).
        let ops = vec![
            op("re", vec![0.into(), 0.into(), 100.into(), 100.into()]),
            op("W", vec![]),
            op("n", vec![]),
        ];
        assert!(
            run(&ops, None).iter().any(|p| matches!(p, Prim::ClipPush { .. })),
            "`W n` must commit the pending clip"
        );
    }

    /// §8.5.2.1: `h` closes the current subpath. With no current point there is
    /// nothing to close, so it must be a no-op — emitting a bare `Close` put a
    /// `PathOp::Close` into the clip path ahead of its first `Move`, which
    /// desynchronised `clip_path_ops` from `subpaths`.
    #[test]
    fn h_with_no_current_point_is_a_no_op() {
        let ops = vec![
            op("h", vec![]),
            op("m", vec![0.into(), 0.into()]),
            op("l", vec![10.into(), 0.into()]),
            op("l", vec![10.into(), 10.into()]),
            op("W", vec![]),
            op("n", vec![]),
        ];
        let prims = run(&ops, None);
        let po = prims
            .iter()
            .find_map(|p| match p {
                Prim::ClipPush { path_ops: Some(po), .. } => Some(po.clone()),
                _ => None,
            })
            .expect("the clip must still be emitted");
        assert!(
            matches!(po.first(), Some(PathOp::Move(..))),
            "clip path must start with a Move, got {:?}",
            po.first()
        );
    }

    /// §9.3.6 modes 4-7 add the glyphs to the clipping path, applied at `ET`.
    /// Gating that marker on optional-content visibility left the renderer's
    /// accumulated outlines pending when a BDC/EMC hid only the TAIL of the text
    /// object, so they were folded into the next text object's clip and erased
    /// unrelated content. The marker also resets that accumulator, so it has to
    /// be emitted whenever any glyph was shown in a clip mode.
    #[test]
    fn et_applies_the_text_clip_even_when_the_tail_of_the_object_is_hidden() {
        let mut doc = Document::with_version("1.7");
        let ocg = doc.add_object(dictionary! {
            "Type" => "OCG", "Name" => Object::string_literal("off"),
        });
        let catalog = doc.add_object(dictionary! {
            "Type" => "Catalog",
            "OCProperties" => dictionary! {
                "OCGs" => vec![Object::Reference(ocg)],
                "D" => dictionary! { "OFF" => vec![Object::Reference(ocg)] },
            },
        });
        doc.trailer.set("Root", Object::Reference(catalog));
        let font = doc.add_object(dictionary! {
            "Type" => "Font", "Subtype" => "Type1", "BaseFont" => "Helvetica",
            "FirstChar" => 65, "LastChar" => 66,
            "Widths" => vec![1000.into(), 1000.into()],
        });
        let res = dictionary! {
            "Font" => dictionary! { "F1" => Object::Reference(font) },
            "Properties" => dictionary! { "P1" => Object::Reference(ocg) },
        };
        let ops = vec![
            op("BT", vec![]),
            op("Tf", vec![Object::Name(b"F1".to_vec()), 12.into()]),
            op("Tr", vec![7.into()]),
            // Shown while the layer is still visible: these glyphs reach the
            // renderer's clip accumulator.
            op("Tj", vec![Object::string_literal("AB")]),
            op("BDC", vec![Object::Name(b"OC".to_vec()), Object::Name(b"P1".to_vec())]),
            op("ET", vec![]),
        ];
        let mut prims = Vec::new();
        interpret_content(&doc, &ops, Some(&res), GraphicsState::default(), &mut prims, 0, false);
        assert!(
            prims.iter().any(|p| matches!(p, Prim::TextClipApply)),
            "ET must apply (and reset) the accumulated text clip"
        );
        // And it must stay balanced: the interpreter closes its own clip levels.
        let mut d = 0i32;
        for p in &prims {
            match p {
                Prim::ClipPush { .. } | Prim::TextClipApply => d += 1,
                Prim::ClipPop => d -= 1,
                _ => {}
            }
            assert!(d >= 0, "clip stack underflowed");
        }
        assert_eq!(d, 0, "{d} clip level(s) left open");
    }

    /// §8.10.1 puts no limit on `Do` recursion, so the interpreter caps it — but
    /// the cap was on DEPTH alone, which does not bound BRANCHING. A ~200-byte
    /// form whose own `/Resources` name it six times is 6^10 = 60M invocations;
    /// measured at 48 s in a release build (minutes in debug), and one more `Do`
    /// in the cell multiplies that by six again. The user-visible symptom is a
    /// viewer that hangs on open, from a file small enough to arrive by email.
    #[test]
    fn a_branching_self_referential_form_is_bounded_by_a_total_budget() {
        let mut doc = Document::with_version("1.7");
        let id = doc.new_object_id();
        doc.set_object(
            id,
            Stream::new(
                dictionary! {
                    "Type" => "XObject", "Subtype" => "Form",
                    "BBox" => vec![0.into(), 0.into(), 10.into(), 10.into()],
                    "Resources" => dictionary! {
                        "XObject" => dictionary! { "A" => Object::Reference(id) },
                    },
                },
                b"/A Do /A Do /A Do /A Do /A Do /A Do".to_vec(),
            ),
        );
        let res = dictionary! { "XObject" => dictionary! { "A" => Object::Reference(id) } };
        let ops = vec![op("Do", vec![Object::Name(b"A".to_vec())])];
        let mut prims = Vec::new();
        let started = std::time::Instant::now();
        interpret_content(&doc, &ops, Some(&res), GraphicsState::default(), &mut prims, 0, false);
        let elapsed = started.elapsed();
        println!("branching form bomb: {elapsed:?}, {} prims", prims.len());
        // Deliberately loose: the unbounded version needs minutes even in release,
        // so anything in seconds proves the budget bound it without being flaky on
        // a loaded machine.
        assert!(
            elapsed < std::time::Duration::from_secs(30),
            "took {elapsed:?} — the form-invocation budget is not binding"
        );
        // Each surviving invocation emits its /BBox clip as a balanced pair.
        let mut d = 0i32;
        for p in &prims {
            match p {
                Prim::ClipPush { .. } => d += 1,
                Prim::ClipPop => d -= 1,
                _ => {}
            }
            assert!(d >= 0, "clip stack underflowed");
        }
        assert_eq!(d, 0, "the budget must not leave {d} clip level(s) open");
    }

    /// §11.6.3: when `/BM` is an array the reader shall use the FIRST name in it
    /// that it RECOGNISES. The array form exists so a file can name a future or
    /// vendor blend mode first and a supported fallback after it — it is NOT
    /// "the first non-Normal name". `/BM [/Normal /Multiply]` therefore means
    /// Normal; reading it as Multiply darkens content that should composite
    /// normally, and doubly so wherever that content overlaps itself.
    /// Reported by `a-shading`.
    #[test]
    fn a_bm_array_uses_the_first_recognised_name() {
        let blend_of = |names: Vec<Object>| -> BlendMode {
            let mut doc = Document::with_version("1.7");
            let gs_id = doc.add_object(dictionary! { "BM" => names });
            let res = dictionary! {
                "ExtGState" => dictionary! { "GS1" => Object::Reference(gs_id) },
            };
            let ops = vec![
                op("gs", vec![Object::Name(b"GS1".to_vec())]),
                op("re", vec![0.into(), 0.into(), 10.into(), 10.into()]),
                op("f", vec![]),
            ];
            let mut prims = Vec::new();
            interpret_content(&doc, &ops, Some(&res), GraphicsState::default(), &mut prims, 0, false);
            prims
                .iter()
                .find_map(|p| match p {
                    Prim::Fill { blend, .. } => Some(*blend),
                    _ => None,
                })
                .expect("the fill must be emitted")
        };
        let name = |s: &str| Object::Name(s.as_bytes().to_vec());

        assert_eq!(
            blend_of(vec![name("Normal"), name("Multiply")]),
            BlendMode::Normal,
            "a leading /Normal is a recognised name and wins"
        );
        assert_eq!(
            blend_of(vec![name("Compatible"), name("Screen")]),
            BlendMode::Normal,
            "/Compatible is a Table 136 alias for Normal, not an unrecognised name"
        );
        // The point of the array form: an unrecognised leading name falls through
        // to the first one the reader does support.
        assert_eq!(
            blend_of(vec![name("FutureVendorMode"), name("Multiply")]),
            BlendMode::Multiply,
            "an unrecognised leading name must fall through to the supported one"
        );
        assert_eq!(blend_of(vec![name("Darken")]), BlendMode::Darken);
    }

    /// §7.3.10: any object may be an indirect reference, including a Table 58
    /// scalar. These were read with a bare `num`, which returns `None` for a
    /// reference, so `/ca 5 0 R` was silently dropped and the element painted
    /// fully opaque at the very moment the file asked for transparency — while
    /// the `w`/`J`/`j`/`M`/`i` operator arms deref theirs. Reported by `a-shading`.
    #[test]
    fn extgstate_scalars_may_be_indirect_references() {
        let mut doc = Document::with_version("1.7");
        let half = doc.add_object(Object::Real(0.5));
        let wide = doc.add_object(Object::Real(9.0));
        let gs_id = doc.add_object(dictionary! {
            "ca" => Object::Reference(half),
            "CA" => Object::Reference(half),
            "LW" => Object::Reference(wide),
        });
        let res = dictionary! {
            "ExtGState" => dictionary! { "GS1" => Object::Reference(gs_id) },
        };
        let ops = vec![
            op("gs", vec![Object::Name(b"GS1".to_vec())]),
            op("re", vec![0.into(), 0.into(), 10.into(), 10.into()]),
            op("B", vec![]),
        ];
        let mut prims = Vec::new();
        interpret_content(&doc, &ops, Some(&res), GraphicsState::default(), &mut prims, 0, false);

        let fill_alpha = prims.iter().find_map(|p| match p {
            Prim::Fill { argb, .. } => Some((argb >> 24) as u8),
            _ => None,
        });
        assert_eq!(fill_alpha, Some(0x80), "/ca behind a reference was dropped");
        let stroke = prims.iter().find_map(|p| match p {
            Prim::Stroke { argb, width, .. } => Some(((argb >> 24) as u8, *width)),
            _ => None,
        });
        let (sa, sw) = stroke.expect("the stroke must be emitted");
        assert_eq!(sa, 0x80, "/CA behind a reference was dropped");
        assert!((sw - 9.0).abs() < 1e-3, "/LW behind a reference was dropped, got {sw}");
    }

    /// §8.7.4.3 Table 78 `/Background`: it fills the parts of the painted area that
    /// lie outside the shading's own extent, and "shall be ignored by the `sh`
    /// operator" — it applies only when the shading is painted as a shading
    /// PATTERN. `images.rs` exposes that as two entry points with IDENTICAL
    /// signatures, so nothing but this test stops a future edit from swapping them
    /// back, and the failure is silent in both directions:
    ///   - `sh` honouring it floods the whole clip with the background colour,
    ///     painting a solid block over content (the damaging direction);
    ///   - a pattern ignoring it drops a legitimate, if rare, entry.
///
    /// Handoff from `a-images`/`a-shading`, who landed the images.rs side.
    #[test]
    fn background_applies_to_shading_patterns_but_not_to_the_sh_operator() {
        // An AXIAL shading whose axis spans only the middle of the paint area, with
        // /Extend false at both ends, so "outside the shading's extent" is a large,
        // unambiguous region: t < 0 left of x=90, t > 1 right of x=110. That makes it
        // a direct witness — those two arms in `images.rs` read `bg_argb`, which is
        // exactly what the entry point this test pins decides.
        //
        // Radial reaches the background by a DIFFERENT route, worth knowing before
        // anyone "simplifies" this fixture: `radial_shading_param` enforces /Extend
        // itself and returns None for an out-of-extent point, so for ShadingType 3
        // the t < 0 and t > 1 arms are real, correct and DEAD, and the background is
        // applied in the NaN branch instead. Both routes are gated on `bg_argb`, so
        // either shading type witnesses the split today; axial just witnesses it
        // without depending on that second route.
        //
        // History kept deliberately: radial used to lose /Background entirely,
        // because the NaN branch skipped the pixel. The natural reading of that is
        // "the §8.7.4.5 extent rule is not firing" — which is the mistake I made, and
        // it is backwards. The extent rule is what PRODUCES the None. Diagnosed by
        // `a-shading`, fixed by `a-images`.
        let build = || {
            let mut doc = Document::with_version("1.7");
            let func = doc.add_object(dictionary! {
                "FunctionType" => 2,
                "Domain" => vec![0.into(), 1.into()],
                "C0" => vec![0.0.into(), 0.0.into(), 0.0.into()],
                "C1" => vec![0.0.into(), 0.0.into(), 0.0.into()],
                "N" => 1,
            });
            let shading = dictionary! {
                "ShadingType" => 2,
                "ColorSpace" => "DeviceRGB",
                "Coords" => vec![90.into(), 100.into(), 110.into(), 100.into()],
                "Function" => Object::Reference(func),
                "Extend" => vec![false.into(), false.into()],
                // Pure red: unmistakable against the all-black gradient itself.
                "Background" => vec![1.0.into(), 0.0.into(), 0.0.into()],
            };
            (doc, shading)
        };

        // Count strongly-red opaque pixels in the rasterised gradient.
        let reds = |prims: &[Prim]| -> usize {
            prims
                .iter()
                .filter_map(|p| match p {
                    Prim::Image { data, .. } => Some(data),
                    _ => None,
                })
                .flat_map(|d| d.chunks(4))
                .filter(|px| px[3] > 128 && px[0] > 200 && px[1] < 64 && px[2] < 64)
                .count()
        };

        // 1. `sh` must IGNORE /Background.
        let (mut doc, shading) = build();
        let sh_id = doc.add_object(shading.clone());
        let res = dictionary! { "Shading" => dictionary! { "Sh" => Object::Reference(sh_id) } };
        let ops = vec![
            op("re", vec![0.into(), 0.into(), 200.into(), 200.into()]),
            op("W", vec![]),
            op("n", vec![]),
            op("sh", vec![Object::Name(b"Sh".to_vec())]),
        ];
        let mut sh_prims = Vec::new();
        interpret_content_seeded(
            &doc, &ops, Some(&res), GraphicsState::default(), &mut sh_prims, 0, false,
            Some([0.0, 0.0, 200.0, 200.0]),
        );
        assert!(
            sh_prims.iter().any(|p| matches!(p, Prim::Image { .. })),
            "precondition: the sh fixture must rasterize something"
        );
        assert_eq!(
            reds(&sh_prims),
            0,
            "`sh` must ignore /Background — flooding the clip with it paints a \
             solid block over whatever is underneath"
        );

        // 2. The same shading as a PatternType 2 fill must HONOUR it.
        let (mut doc2, shading2) = build();
        let sh_id2 = doc2.add_object(shading2);
        let pat = doc2.add_object(dictionary! {
            "Type" => "Pattern",
            "PatternType" => 2,
            "Shading" => Object::Reference(sh_id2),
        });
        let res2 = dictionary! { "Pattern" => dictionary! { "P0" => Object::Reference(pat) } };
        let ops2 = vec![
            op("cs", vec![Object::Name(b"Pattern".to_vec())]),
            op("scn", vec![Object::Name(b"P0".to_vec())]),
            op("re", vec![0.into(), 0.into(), 200.into(), 200.into()]),
            op("f", vec![]),
        ];
        let mut pat_prims = Vec::new();
        interpret_content_seeded(
            &doc2, &ops2, Some(&res2), GraphicsState::default(), &mut pat_prims, 0, false,
            Some([0.0, 0.0, 200.0, 200.0]),
        );
        assert!(
            pat_prims.iter().any(|p| matches!(p, Prim::Image { .. })),
            "precondition: the pattern fixture must rasterize something"
        );
        assert!(
            reds(&pat_prims) > 0,
            "a PatternType 2 shading must honour /Background outside its extent"
        );
    }
}