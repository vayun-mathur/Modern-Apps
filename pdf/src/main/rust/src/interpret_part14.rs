
#[cfg(test)]
mod blind_reaudit_r5_tests_2 {
    use crate::*;
    use lopdf::content::Operation;
    use lopdf::dictionary;

    fn op(name: &str, operands: Vec<Object>) -> Operation {
        Operation::new(name, operands)
    }

    /// §8.11.3.3 bars DRAWING inside an OFF optional-content group, not state
    /// changes — and §9.3.6 Table 106 mode 7 is precisely "add to clip, paint
    /// nothing", which is what suppressing the paint of a mode 4-6 run leaves.
    /// Forcing the whole 0-7 range to 3 dropped the clip contribution as well:
    /// `text_clip_used` was already latched from the real mode, so `ET` still
    /// emitted `TextClipApply`, but the glyphs went out tagged mode 3 and the
    /// renderer only accumulates outlines from `rm in 4..7` — it opened a canvas
    /// level and narrowed nothing, so artwork meant to show through the letterforms
    /// painted as a full opaque rectangle. Reported by `r5-kotlin`.
    #[test]
    fn a_hidden_clip_mode_text_run_still_contributes_its_outline_to_the_clip() {
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
        let modes = |tr: i64| -> Vec<u8> {
            let ops = vec![
                op("BDC", vec![Object::Name(b"OC".to_vec()), Object::Name(b"P1".to_vec())]),
                op("BT", vec![]),
                op("Tf", vec![Object::Name(b"F1".to_vec()), 12.into()]),
                op("Tr", vec![tr.into()]),
                op("Tj", vec![Object::string_literal("AB")]),
                op("ET", vec![]),
                op("EMC", vec![]),
            ];
            let mut prims = Vec::new();
            interpret_content(&doc, &ops, Some(&res), GraphicsState::default(), &mut prims, 0, false);
            prims
                .iter()
                .filter_map(|p| match p {
                    Prim::Text { render_mode, .. } => Some(*render_mode),
                    _ => None,
                })
                .collect()
        };

        // Clip modes: suppressed to 7, so the renderer still builds the clip.
        for tr in [4, 5, 6, 7] {
            let m = modes(tr);
            assert!(!m.is_empty(), "Tr {tr}: the run must stay in the stream");
            assert!(
                m.iter().all(|&r| r == 7),
                "Tr {tr} hidden: glyphs went out as {m:?}; only mode 7 reaches the \
                 renderer's clip accumulator, so anything else opens a clip level \
                 that narrows nothing"
            );
        }
        // Paint modes: still 3 — no ink, no clip, but still indexed.
        for tr in [0, 1, 2] {
            let m = modes(tr);
            assert!(m.iter().all(|&r| r == 3), "Tr {tr} hidden: got {m:?}, expected 3");
        }
    }

    /// §8.6.1 lets any non-device colour space be written as a NAME resolved
    /// through `/Resources /ColorSpace`, and §8.7.4.3 Table 78 puts no restriction
    /// on the form a shading's `/ColorSpace` takes. A PatternType 2 dictionary has
    /// no `/Resources` of its own — only tiling patterns do (§8.7.3.1) — so a name
    /// there resolves against the stream that invoked the pattern.
    ///
    /// Both pattern painters passed `&HashMap::new()` to
    /// `rasterize_shading_as_pattern` while the `sh` arm passed the real map, so
    /// every named space missed and silently fell back to DeviceRGB. For a
    /// 1-component `/Separation` that means the tint transform's output is read as
    /// if it were RGB. Found by `r5-color`.
    #[test]
    fn a_shading_pattern_resolves_a_named_colour_space_from_the_resources() {
        // /Separation with a tint transform that maps t -> a single gray-ish
        // component; under DeviceRGB the 1-component result cannot be read as a
        // colour at all, so the two paths are trivially distinguishable.
        let build = |cs_named: bool| -> Vec<Prim> {
            let mut doc = Document::with_version("1.7");
            let tint = doc.add_object(dictionary! {
                "FunctionType" => 2,
                "Domain" => vec![0.into(), 1.into()],
                "C0" => vec![0.0.into(), 0.0.into(), 1.0.into()],
                "C1" => vec![0.0.into(), 0.0.into(), 1.0.into()],
                "N" => 1,
            });
            let sep = doc.add_object(Object::Array(vec![
                Object::Name(b"Separation".to_vec()),
                Object::Name(b"Spot".to_vec()),
                Object::Name(b"DeviceRGB".to_vec()),
                Object::Reference(tint),
            ]));
            let func = doc.add_object(dictionary! {
                "FunctionType" => 2,
                "Domain" => vec![0.into(), 1.into()],
                "C0" => vec![1.0.into()],
                "C1" => vec![1.0.into()],
                "N" => 1,
            });
            let shading = doc.add_object(dictionary! {
                "ShadingType" => 2,
                // The whole point: a NAME, resolvable only through /Resources.
                "ColorSpace" => if cs_named {
                    Object::Name(b"CS0".to_vec())
                } else {
                    Object::Reference(sep)
                },
                "Coords" => vec![0.into(), 0.into(), 100.into(), 0.into()],
                "Extend" => vec![true.into(), true.into()],
                "Function" => Object::Reference(func),
            });
            let pat = doc.add_object(dictionary! {
                "Type" => "Pattern",
                "PatternType" => 2,
                "Shading" => Object::Reference(shading),
            });
            let res = dictionary! {
                "Pattern" => dictionary! { "P0" => Object::Reference(pat) },
                "ColorSpace" => dictionary! { "CS0" => Object::Reference(sep) },
            };
            let ops = vec![
                op("cs", vec![Object::Name(b"Pattern".to_vec())]),
                op("scn", vec![Object::Name(b"P0".to_vec())]),
                op("re", vec![0.into(), 0.into(), 100.into(), 100.into()]),
                op("f", vec![]),
            ];
            let mut prims = Vec::new();
            interpret_content_seeded(
                &doc, &ops, Some(&res), GraphicsState::default(), &mut prims, 0, false,
                Some([0.0, 0.0, 100.0, 100.0]),
            );
            prims
        };

        // The reference: the same space written inline, which never needed the map.
        let inline = build(false);
        let named = build(true);
        let opaque_px = |prims: &[Prim]| -> Vec<[u8; 4]> {
            prims
                .iter()
                .filter_map(|p| match p {
                    Prim::Image { data, .. } => Some(data),
                    _ => None,
                })
                .flat_map(|d| d.chunks(4))
                .filter(|px| px[3] > 128)
                .map(|px| [px[0], px[1], px[2], px[3]])
                .collect()
        };
        let want = opaque_px(&inline);
        let got = opaque_px(&named);
        assert!(!want.is_empty(), "precondition: the inline-space pattern must rasterize");
        assert_eq!(
            got.len(),
            want.len(),
            "the named-space pattern rasterized a different number of pixels"
        );
        assert_eq!(
            got.first(),
            want.first(),
            "a named /Separation in a shading PATTERN resolved to something other \
             than the inline form — the /Resources /ColorSpace map is not reaching \
             rasterize_shading_as_pattern, so it fell back to DeviceRGB"
        );
    }

    /// §7.3.3 bounds a real to the implementation limit, and lopdf's `Object::Real`
    /// is an `f32`, so a long enough literal parses to INFINITY. Matrices, rects and
    /// path operands were all guarded; the SCALAR operands were not, and they feed
    /// the text state directly.
    ///
    /// NaN is the half that matters and it needs no malformed syntax: an infinite
    /// `Tfs` times the zero scale of a perfectly legal `0 0 0 0 0 0 cm` is
    /// `inf * 0` = NaN. NaN is not a wrong number, it is an invisible one — every
    /// comparison against it is false, so it survives `f64::clamp` here and
    /// `coerceIn` on the Kotlin side, and the first thing that notices is the
    /// rasterizer, which drops the geometry silently. Reported by `r5-text` and
    /// `r5-kotlin` from a non-finite `Prim::Text.h_scale`.
    #[test]
    fn non_finite_scalar_operands_never_reach_the_graphics_state() {
        let doc = Document::with_version("1.7");
        let font = {
            let mut d = Document::with_version("1.7");
            let f = d.add_object(dictionary! {
                "Type" => "Font", "Subtype" => "Type1", "BaseFont" => "Helvetica",
                "FirstChar" => 65, "LastChar" => 66,
                "Widths" => vec![1000.into(), 1000.into()],
            });
            (d, f)
        };
        let inf = Object::Real(f32::INFINITY);
        let nan = Object::Real(f32::NAN);

        // Stroke parameters: width, miter, dash and phase all ride on Prim::Stroke.
        for bad in [inf.clone(), nan.clone()] {
            let ops = vec![
                op("w", vec![bad.clone()]),
                op("M", vec![bad.clone()]),
                op("d", vec![Object::Array(vec![bad.clone(), 2.into()]), bad.clone()]),
                op("m", vec![0.into(), 0.into()]),
                op("l", vec![10.into(), 10.into()]),
                op("S", vec![]),
            ];
            let mut prims = Vec::new();
            interpret_content(&doc, &ops, None, GraphicsState::default(), &mut prims, 0, false);
            for p in &prims {
                if let Prim::Stroke { width, dash, dash_phase, miter, .. } = p {
                    assert!(width.is_finite(), "stroke width {width} reached the wire");
                    assert!(miter.is_finite(), "miter {miter} reached the wire");
                    assert!(dash_phase.is_finite(), "dash phase {dash_phase} reached the wire");
                    assert!(dash.iter().all(|d| d.is_finite()), "non-finite dash segment");
                }
            }
        }

        // Text state. `0 0 0 0 0 0 cm` is the inf -> NaN multiplier, and it is a
        // legal operator, so this is the whole route with no malformed syntax.
        let (doc, fid) = font;
        let res = dictionary! { "Font" => dictionary! { "F1" => Object::Reference(fid) } };
        for bad in [inf.clone(), nan.clone()] {
            for setter in [
                op("Tf", vec![Object::Name(b"F1".to_vec()), bad.clone()]),
                op("Tz", vec![bad.clone()]),
                op("Tc", vec![bad.clone()]),
                op("Tw", vec![bad.clone()]),
                op("Ts", vec![bad.clone()]),
                op("TL", vec![bad.clone()]),
            ] {
                let ops = vec![
                    op("cm", vec![0.into(), 0.into(), 0.into(), 0.into(), 0.into(), 0.into()]),
                    op("BT", vec![]),
                    op("Tf", vec![Object::Name(b"F1".to_vec()), 12.into()]),
                    setter.clone(),
                    op("Td", vec![bad.clone(), bad.clone()]),
                    op("TJ", vec![Object::Array(vec![
                        Object::string_literal("A"),
                        bad.clone(),
                        Object::string_literal("B"),
                    ])]),
                    op("T*", vec![]),
                    op("Tj", vec![Object::string_literal("AB")]),
                    op("ET", vec![]),
                ];
                let mut prims = Vec::new();
                interpret_content(&doc, &ops, Some(&res), GraphicsState::default(), &mut prims, 0, false);
                for p in &prims {
                    if let Prim::Text { x, y, size, advance, h_scale, .. } = p {
                        for (name, v) in [
                            ("x", *x), ("y", *y), ("size", *size),
                            ("advance", *advance), ("h_scale", *h_scale),
                        ] {
                            assert!(
                                v.is_finite(),
                                "{} left Prim::Text.{name} = {v}",
                                setter.operator
                            );
                        }
                    }
                }
            }
        }

        // The guard must reject the operand, not the operator: finite values still
        // take effect.
        let ops = vec![
            op("BT", vec![]),
            op("Tf", vec![Object::Name(b"F1".to_vec()), 12.into()]),
            op("Tz", vec![200.into()]),
            op("Tj", vec![Object::string_literal("AB")]),
            op("ET", vec![]),
        ];
        let mut prims = Vec::new();
        interpret_content(&doc, &ops, Some(&res), GraphicsState::default(), &mut prims, 0, false);
        let hs = prims.iter().find_map(|p| match p {
            Prim::Text { h_scale, .. } => Some(*h_scale),
            _ => None,
        });
        assert_eq!(hs, Some(2.0), "a finite Tz must still be applied");
    }

    /// q/Q must round-trip the COMPLETE graphics state. It does so structurally —
    /// `q` clones the whole struct and `Q` assigns it back — but that is only worth
    /// relying on if nothing is copied field-by-field, so this exercises one operator
    /// per §8.4.1 Table 52 parameter the interpreter models and checks the emitted
    /// primitive is back to the default afterwards. A field added to the struct and
    /// forgotten in a hand-written save reads perfectly fine and leaks permanently.
    #[test]
    fn q_and_q_round_trip_every_modelled_state_parameter() {
        let mut doc = Document::with_version("1.7");
        let egs = doc.add_object(dictionary! {
            "ca" => 0.25, "CA" => 0.25, "BM" => "Multiply",
        });
        let res = dictionary! {
            "ExtGState" => dictionary! { "GS1" => Object::Reference(egs) },
        };
        let stroke_of = |prims: &[Prim]| -> (u32, f32, usize, u8, u8, f32, BlendMode) {
            prims
                .iter()
                .rev()
                .find_map(|p| match p {
                    Prim::Stroke { argb, width, dash, cap, join, miter, blend, .. } => {
                        Some((*argb, *width, dash.len(), *cap, *join, *miter, *blend))
                    }
                    _ => None,
                })
                .expect("a stroke must be emitted")
        };

        let line = [op("m", vec![0.into(), 0.into()]),
            op("l", vec![50.into(), 50.into()]),
            op("S", vec![])];
        let mut baseline_ops = Vec::new();
        baseline_ops.extend(line.iter().cloned());
        let mut prims = Vec::new();
        interpret_content(&doc, &baseline_ops, Some(&res), GraphicsState::default(), &mut prims, 0, false);
        let expected = stroke_of(&prims);

        // Change every stroke-visible parameter inside a q/Q, then repeat the
        // identical line outside it.
        let mut ops = vec![op("q", vec![])];
        ops.extend([
            op("cm", vec![3.into(), 0.into(), 0.into(), 3.into(), 7.into(), 7.into()]),
            op("w", vec![9.into()]),
            op("J", vec![2.into()]),
            op("j", vec![2.into()]),
            op("M", vec![2.into()]),
            op("d", vec![Object::Array(vec![4.into(), 4.into()]), 1.into()]),
            op("RG", vec![1.into(), 0.into(), 0.into()]),
            op("gs", vec![Object::Name(b"GS1".to_vec())]),
        ]);
        ops.extend(line.iter().cloned());
        ops.push(op("Q", vec![]));
        ops.extend(line.iter().cloned());
        let mut prims = Vec::new();
        interpret_content(&doc, &ops, Some(&res), GraphicsState::default(), &mut prims, 0, false);
        assert_eq!(
            stroke_of(&prims),
            expected,
            "a graphics-state parameter leaked past its `Q`"
        );

        // An unbalanced `Q` must be ignored, not underflow (§8.4.2), and must not
        // resurrect the pre-`q` state from an earlier bracket.
        let mut ops = vec![op("Q", vec![]), op("Q", vec![])];
        ops.extend(line.iter().cloned());
        let mut prims = Vec::new();
        interpret_content(&doc, &ops, Some(&res), GraphicsState::default(), &mut prims, 0, false);
        assert_eq!(stroke_of(&prims), expected, "an unmatched `Q` disturbed the state");
    }
}

#[cfg(test)]
mod stroke_pattern_tests {
    use super::stroke_outline_quads;

    // A single horizontal segment yields one segment quad plus two vertex
    // squares, all offset by the half width.
    #[test]
    fn horizontal_segment_quad_offsets_by_half_width() {
        let sp = vec![vec![(0.0, 0.0), (10.0, 0.0)]];
        let quads = stroke_outline_quads(&sp, 2.0);
        // 1 segment quad + 2 vertex squares.
        assert_eq!(quads.len(), 3);
        let seg = &quads[0];
        assert_eq!(seg.len(), 4);
        // Normal to a horizontal segment is vertical: y offset = +/-hw.
        assert!(seg.iter().any(|&(_, y)| (y - 2.0).abs() < 1e-9));
        assert!(seg.iter().any(|&(_, y)| (y + 2.0).abs() < 1e-9));
    }

    // Zero-length segments are skipped (no NaN normals), but the vertex square
    // still covers the point.
    #[test]
    fn degenerate_segment_is_skipped() {
        let sp = vec![vec![(5.0, 5.0), (5.0, 5.0)]];
        let quads = stroke_outline_quads(&sp, 1.0);
        // No segment quad, just the two coincident vertex squares.
        assert_eq!(quads.len(), 2);
    }
}
