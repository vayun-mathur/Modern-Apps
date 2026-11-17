
#[cfg(test)]
mod blind_reaudit_r4_tests_2 {
    use crate::*;
    use lopdf::content::Operation;
    use lopdf::{dictionary, Stream};

    fn op(name: &str, operands: Vec<Object>) -> Operation {
        Operation::new(name, operands)
    }

    /// §7.3.3 bounds a real to the implementation limit, and lopdf's `Object::Real`
    /// is an `f32`, so a content stream carrying `1e40` parses to INFINITY. Nothing
    /// between the interpreter and `Canvas.drawPath` filters a non-finite coordinate
    /// — not `wire.rs`, not the parser, not the renderer (verified by inspection: no
    /// `is_finite`/`isFinite` guard in any of them).
    ///
    /// The damage is not local. One non-finite vertex makes the WHOLE contour
    /// non-finite, so a single bad number erases an entire fill rather than one
    /// point of it; as a `W n` clip path it can erase everything drawn after it. The
    /// `cm` arm already guards exactly this ("a non-finite CTM poisons every
    /// coordinate derived from it"), so the operand route was the same hazard left
    /// open beside a guarded one.
    ///
    /// The downstream symptom is asserted at the wire boundary only — what Skia does
    /// with a non-finite path is not verified here, which is precisely why the poison
    /// is dropped at the source instead of relying on the renderer to cope.
    #[test]
    fn non_finite_path_operands_never_reach_the_primitive_stream() {
        let doc = Document::with_version("1.7");
        let inf = Object::Real(f32::INFINITY);
        let nf = |p: &Prim| -> usize {
            match p {
                Prim::Fill { contours, .. } => contours
                    .iter()
                    .flatten()
                    .filter(|(x, y)| !x.is_finite() || !y.is_finite())
                    .count(),
                Prim::Stroke { pts, .. } => pts
                    .iter()
                    .filter(|(x, y)| !x.is_finite() || !y.is_finite())
                    .count(),
                Prim::ClipPush { pts, path_ops, .. } => {
                    pts.iter().filter(|(x, y)| !x.is_finite() || !y.is_finite()).count()
                        + path_ops.iter().flatten().filter(|o| match o {
                            PathOp::Move(x, y) | PathOp::Line(x, y) => !x.is_finite() || !y.is_finite(),
                            PathOp::Cubic(a, b, c, d, e, f) => {
                                [a, b, c, d, e, f].iter().any(|v| !v.is_finite())
                            }
                            PathOp::Close => false,
                        }).count()
                }
                _ => 0,
            }
        };

        let cases: Vec<(&str, Vec<Operation>)> = vec![
            ("l", vec![
                op("m", vec![0.into(), 0.into()]),
                op("l", vec![inf.clone(), 0.into()]),
                op("l", vec![10.into(), 10.into()]),
                op("f", vec![]),
            ]),
            ("m", vec![
                op("m", vec![inf.clone(), inf.clone()]),
                op("l", vec![10.into(), 10.into()]),
                op("S", vec![]),
            ]),
            ("re", vec![
                op("re", vec![0.into(), 0.into(), inf.clone(), 10.into()]),
                op("S", vec![]),
            ]),
            ("c", vec![
                op("m", vec![0.into(), 0.into()]),
                op("c", vec![inf.clone(), 0.into(), 5.into(), 5.into(), 10.into(), 0.into()]),
                op("S", vec![]),
            ]),
            // As a clip path, which is the case that can erase later content.
            ("re W n", vec![
                op("re", vec![0.into(), 0.into(), inf.clone(), 10.into()]),
                op("W", vec![]),
                op("n", vec![]),
                op("re", vec![0.into(), 0.into(), 10.into(), 10.into()]),
                op("f", vec![]),
            ]),
        ];

        for (label, ops) in cases {
            let mut prims = Vec::new();
            interpret_content(&doc, &ops, None, GraphicsState::default(), &mut prims, 0, false);
            let bad: usize = prims.iter().map(nf).sum();
            assert_eq!(bad, 0, "`{label}` leaked {bad} non-finite coordinate(s) to the wire");
        }

        // The guard must drop only the poison, not the drawing: a wholly finite path
        // alongside the same operators still paints.
        let ops = vec![
            op("re", vec![0.into(), 0.into(), 10.into(), 10.into()]),
            op("f", vec![]),
        ];
        let mut prims = Vec::new();
        interpret_content(&doc, &ops, None, GraphicsState::default(), &mut prims, 0, false);
        assert!(
            prims.iter().any(|p| matches!(p, Prim::Fill { .. })),
            "the guard must not suppress finite geometry"
        );
    }

    /// The matrix half of the same boundary. §8.3.3 defines a matrix as six numbers,
    /// and lopdf's `Object::Real` is an f32, so `1e40` in any `/Matrix` — or in `cm`
    /// or `Tm` — yields INFINITY. `read_rect` already rejected a non-finite
    /// rectangle; `read_matrix` did not, which left three carriers open that the
    /// path-operand guard cannot see, because they do not go through path
    /// construction at all:
    ///   - a form `/Matrix`, which poisons `form_ctm` and with it the `/BBox`
    ///     ClipPush corners — a non-finite CLIP is the case that erases everything
    ///     drawn after it, not merely the form;
    ///   - a pattern `/Matrix`, which reaches the shading placement matrix;
    ///   - `Tm`, which assigns straight to the text matrix and so places every
    ///     subsequent glyph at a non-finite origin.
///
    /// `cm` was already safe, but only because it guards the product it computes.
    /// Found by `a-images`, who hit the same class in shading matrices.
    #[test]
    fn a_non_finite_matrix_is_treated_as_absent() {
        let inf = Object::Real(f32::INFINITY);
        let bad = vec![inf.clone(), 0.into(), 0.into(), 1.into(), 0.into(), 0.into()];
        assert!(
            read_matrix(&bad).is_none(),
            "a non-finite matrix must be rejected at the parse boundary"
        );
        assert!(
            read_matrix(&[1.into(), 0.into(), 0.into(), 1.into(), 5.into(), 5.into()]).is_some(),
            "a finite matrix must still parse"
        );

        // A form whose /Matrix is non-finite: it must fall back to IDENTITY (the
        // §8.10.2 default for an ABSENT /Matrix) and emit a finite BBox clip, rather
        // than a clip whose corners are NaN.
        let mut doc = Document::with_version("1.7");
        let form = doc.add_object(Stream::new(
            dictionary! {
                "Type" => "XObject", "Subtype" => "Form",
                "BBox" => vec![0.into(), 0.into(), 100.into(), 100.into()],
                "Matrix" => bad.clone(),
            },
            b"0 0 50 50 re f".to_vec(),
        ));
        let res = dictionary! {
            "XObject" => dictionary! { "Fm" => Object::Reference(form) },
        };
        let ops = vec![op("Do", vec![Object::Name(b"Fm".to_vec())])];
        let mut prims = Vec::new();
        interpret_content(&doc, &ops, Some(&res), GraphicsState::default(), &mut prims, 0, false);

        let mut clips = 0;
        for p in &prims {
            if let Prim::ClipPush { pts, path_ops, .. } = p {
                clips += 1;
                assert!(
                    pts.iter().all(|(x, y)| x.is_finite() && y.is_finite()),
                    "a non-finite /Matrix produced a non-finite CLIP, which erases \
                     everything drawn after it"
                );
                for o in path_ops.iter().flatten() {
                    if let PathOp::Move(x, y) | PathOp::Line(x, y) = o {
                        assert!(x.is_finite() && y.is_finite(), "non-finite clip path op");
                    }
                }
            }
        }
        assert_eq!(clips, 1, "the form's /BBox clip must still be emitted");
        assert!(
            prims.iter().any(|p| matches!(p, Prim::Fill { .. })),
            "falling back to IDENTITY must still draw the form, not drop it"
        );
    }

    /// The form-invocation budget must be established once per top-level render
    /// at WHATEVER depth that render is entered, not only at `depth == 0`.
    ///
    /// `depth` is the §8.10.1 recursion counter, and legitimate top-level entries
    /// start it above zero — `annotations::render_annotation` enters an appearance
    /// stream at 1, and form-field appearances reach the interpreter through it.
    /// Keyed on `depth == 0`, such a render inherited whatever the previous one
    /// had left, which is ZERO after anything exhausted the budget (the branching
    /// bomb above does exactly that, and the thread is reused). Every `Do` in the
    /// appearance was then dropped with no error: the annotation, stamp or filled
    /// field simply rendered as nothing, which is the invisible, fail-closed
    /// failure the budget was never meant to be able to cause. Found by
    /// `a-annots`, whose
    /// `annotation_ca_is_applied_once_over_a_transparency_group` this broke.
    #[test]
    fn the_form_budget_is_refilled_for_an_entry_at_a_non_zero_depth() {
        let mut doc = Document::with_version("1.7");
        let inner = doc.add_object(Stream::new(
            dictionary! {
                "Type" => "XObject", "Subtype" => "Form",
                "BBox" => vec![0.into(), 0.into(), 10.into(), 10.into()],
            },
            b"0 0 10 10 re f".to_vec(),
        ));
        let res = dictionary! {
            "XObject" => dictionary! { "A" => Object::Reference(inner) },
        };
        let ops = vec![op("Do", vec![Object::Name(b"A".to_vec())])];

        // Drain this thread's budget the way the branching-bomb test does, so the
        // next render starts from a genuinely exhausted budget rather than the
        // full one a fresh thread happens to hold.
        let bomb = doc.new_object_id();
        doc.set_object(
            bomb,
            Stream::new(
                dictionary! {
                    "Type" => "XObject", "Subtype" => "Form",
                    "BBox" => vec![0.into(), 0.into(), 10.into(), 10.into()],
                    "Resources" => dictionary! {
                        "XObject" => dictionary! { "B" => Object::Reference(bomb) },
                    },
                },
                b"/B Do /B Do /B Do /B Do /B Do /B Do".to_vec(),
            ),
        );
        let bomb_res = dictionary! { "XObject" => dictionary! { "B" => Object::Reference(bomb) } };
        let mut drained = Vec::new();
        interpret_content(
            &doc,
            &[op("Do", vec![Object::Name(b"B".to_vec())])],
            Some(&bomb_res),
            GraphicsState::default(),
            &mut drained,
            0,
            false,
        );

        // Entry at depth 1 — the annotation-appearance path.
        let mut prims = Vec::new();
        interpret_content(&doc, &ops, Some(&res), GraphicsState::default(), &mut prims, 1, false);
        assert!(
            prims.iter().any(|p| matches!(p, Prim::Fill { .. })),
            "a `Do` at depth 1 after an exhausted render emitted nothing: the budget \
             is still keyed on depth 0"
        );

        // Depth 0 must keep working. Sharing WITHIN one render is structural —
        // nested `Do`s run inside the outer scope — and is pinned by
        // `a_branching_self_referential_form_is_bounded_by_a_total_budget`.
        let mut prims = Vec::new();
        interpret_content(&doc, &ops, Some(&res), GraphicsState::default(), &mut prims, 0, false);
        assert!(prims.iter().any(|p| matches!(p, Prim::Fill { .. })));
    }

    /// §7.9.5: a rectangle is four NUMBERS. A `/Rect` or `/BBox` carrying NaN or
    /// an infinity used to be read through verbatim, and one poisoned component
    /// propagates through every transform derived from it. The rasterizer drops a
    /// path containing a NaN point silently, so the result is a region of the page
    /// that vanishes with no error anywhere — the same failure mode the `cm` arm's
    /// non-finite CTM guard exists to prevent, and rejected the same way.
    #[test]
    fn read_rect_rejects_non_finite_components() {
        let doc = Document::with_version("1.7");
        let rect = |v: Vec<Object>| read_rect(&doc, &Object::Array(v));
        let n = |x: f64| Object::Real(x as f32);

        assert_eq!(
            rect(vec![n(0.0), n(0.0), n(10.0), n(10.0)]),
            Some([0.0, 0.0, 10.0, 10.0]),
            "a finite rect must still be read"
        );
        for bad in [f64::NAN, f64::INFINITY, f64::NEG_INFINITY] {
            for i in 0..4 {
                let mut v = vec![n(0.0), n(0.0), n(10.0), n(10.0)];
                v[i] = n(bad);
                assert_eq!(rect(v), None, "component {i} = {bad} must be rejected");
            }
        }
    }

    /// §11.6.5.2: a luminosity soft mask composites its group against a FULLY
    /// OPAQUE backdrop of `/BC` and converts the result to luminosity, so the
    /// mask value everywhere the group does not paint — including outside its
    /// `/BBox` — is luminosity(`/BC`). The backdrop fill was clipped to the
    /// `/BBox` quad, leaving the rest of the mask surface at luminosity 0, so a
    /// BRIGHT `/BC` hid the very content it was asking to reveal. Silent, and in
    /// the content-disappears direction. Found with `a-shading`, who traced the
    /// renderer side (the mask `saveLayer` starts transparent-black, and the
    /// alpha row of the luminosity ColorMatrix ignores input alpha, so an
    /// unpainted mask pixel is luminosity 0 and `DST_IN` erases under it).
    ///
    /// The default-`/BC` case is deliberately NOT changed by this: absent `/BC`
    /// means luminosity 0, which the transparent-black surface already gives.
    #[test]
    fn a_bright_bc_backdrop_covers_the_clip_not_just_the_group_bbox() {
        let mut doc = Document::with_version("1.7");
        // Mask group with a deliberately SMALL /BBox, painting nothing itself.
        let group = doc.add_object(Stream::new(
            dictionary! {
                "Type" => "XObject", "Subtype" => "Form",
                "BBox" => vec![0.into(), 0.into(), 10.into(), 10.into()],
                "Group" => dictionary! {
                    "S" => "Transparency", "CS" => "DeviceGray",
                },
            },
            Vec::new(),
        ));
        // /BC white — luminosity 1 — so everything outside the /BBox must be
        // REVEALED, which is the opposite of what a black backdrop would do.
        let gs_id = doc.add_object(dictionary! {
            "SMask" => dictionary! {
                "S" => "Luminosity",
                "G" => Object::Reference(group),
                "BC" => vec![1.0.into()],
            },
        });
        let res = dictionary! {
            "ExtGState" => dictionary! { "GS1" => Object::Reference(gs_id) },
        };
        // Masked content far outside the mask group's 10x10 /BBox.
        let ops = vec![
            op("gs", vec![Object::Name(b"GS1".to_vec())]),
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

        // The backdrop is the fill between SoftMaskPush and SoftMaskContent.
        let push = prims
            .iter()
            .position(|p| matches!(p, Prim::SoftMaskPush { .. }))
            .expect("a soft-mask bracket must be emitted");
        let content = prims
            .iter()
            .position(|p| matches!(p, Prim::SoftMaskContent))
            .expect("the bracket must have a content separator");
        let backdrop = prims[content..]
            .iter()
            .find_map(|p| match p {
                Prim::Fill { contours, .. } => Some(contours.clone()),
                _ => None,
            })
            .expect("a /BC backdrop fill must be emitted for a luminosity mask");
        assert!(push < content, "malformed bracket");

        let xs: Vec<f64> = backdrop.iter().flatten().map(|p| p.0 as f64).collect();
        let ys: Vec<f64> = backdrop.iter().flatten().map(|p| p.1 as f64).collect();
        let (w, h) = (
            xs.iter().cloned().fold(f64::MIN, f64::max) - xs.iter().cloned().fold(f64::MAX, f64::min),
            ys.iter().cloned().fold(f64::MIN, f64::max) - ys.iter().cloned().fold(f64::MAX, f64::min),
        );
        assert!(
            w > 190.0 && h > 190.0,
            "the /BC backdrop covers only {w}x{h}: it is still clipped to the group's \
             10x10 /BBox, so a bright /BC leaves everything outside it at luminosity 0 \
             and hides content that must be revealed"
        );
    }
}