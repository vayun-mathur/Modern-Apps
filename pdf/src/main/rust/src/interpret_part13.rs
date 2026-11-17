
#[cfg(test)]
mod blind_reaudit_r5_tests {
    use crate::*;
    use lopdf::content::Operation;
    use lopdf::{dictionary, Stream};

    fn op(name: &str, operands: Vec<Object>) -> Operation {
        Operation::new(name, operands)
    }

    fn run(ops: &[Operation]) -> Vec<Prim> {
        let doc = Document::with_version("1.7");
        let mut prims = Vec::new();
        interpret_content(&doc, ops, None, GraphicsState::default(), &mut prims, 0, false);
        prims
    }

    fn contours(prims: &[Prim]) -> Vec<Vec<(f32, f32)>> {
        prims
            .iter()
            .find_map(|p| match p {
                Prim::Fill { contours, .. } => Some(contours.clone()),
                _ => None,
            })
            .expect("a fill must be emitted")
    }

    /// §8.5.2.1 Table 59, `h`: "This operator shall terminate the current subpath.
    /// Appending another segment to the current path shall begin a new subpath, even
    /// if the new segment begins at the endpoint reached by the h operation." `re` is
    /// defined in the same table as `x y m … l h`, so it closes too.
    ///
    /// Appending to the already-closed contour instead merges the two shapes into one
    /// polygon: the closing edge disappears from the fill and the merged region winds
    /// differently, so a `h`-then-`l` path fills as a single blob. The two internal
    /// representations of the same path also disagreed — `clip_path_ops` records a
    /// `Close`, and a `lineTo` after a `close` starts a fresh contour, so `W f` clipped
    /// to two contours while filling one.
    #[test]
    fn a_segment_after_a_close_begins_a_new_subpath() {
        // `h` form.
        let prims = run(&[
            op("m", vec![0.into(), 0.into()]),
            op("l", vec![10.into(), 0.into()]),
            op("l", vec![0.into(), 10.into()]),
            op("h", vec![]),
            op("l", vec![20.into(), 20.into()]),
            op("l", vec![30.into(), 20.into()]),
            op("l", vec![20.into(), 30.into()]),
            op("f", vec![]),
        ]);
        let c = contours(&prims);
        assert_eq!(c.len(), 2, "`h` must terminate the subpath, got {c:?}");
        assert_eq!(c[0].len(), 4, "the closed triangle keeps its closing point");
        assert_eq!(
            c[1][0],
            (0.0, 0.0),
            "the new subpath starts at the closepoint, not at the first operand"
        );

        // `re` form: the implicit `h` closes just the same.
        let prims = run(&[
            op("re", vec![0.into(), 0.into(), 100.into(), 100.into()]),
            op("l", vec![200.into(), 200.into()]),
            op("l", vec![250.into(), 200.into()]),
            op("l", vec![250.into(), 250.into()]),
            op("f", vec![]),
        ]);
        let c = contours(&prims);
        assert_eq!(c.len(), 2, "`re` closes its subpath, got {c:?}");
        assert_eq!(c[0].len(), 5, "the rectangle must not absorb the later segments");

        // A curve after the close is the same rule (§8.5.2.1 covers every segment
        // operator, not just `l`).
        let prims = run(&[
            op("re", vec![0.into(), 0.into(), 10.into(), 10.into()]),
            op("c", vec![5.into(), 20.into(), 15.into(), 20.into(), 20.into(), 0.into()]),
            op("f", vec![]),
        ]);
        assert_eq!(contours(&prims).len(), 2, "`c` after a close starts a new subpath");

        // And the two representations of the same path must now agree: one `Move`
        // per contour.
        let prims = run(&[
            op("m", vec![0.into(), 0.into()]),
            op("l", vec![10.into(), 0.into()]),
            op("l", vec![0.into(), 10.into()]),
            op("h", vec![]),
            op("l", vec![20.into(), 20.into()]),
            op("l", vec![30.into(), 20.into()]),
            op("l", vec![20.into(), 30.into()]),
            op("W", vec![]),
            op("f", vec![]),
        ]);
        let moves = prims
            .iter()
            .find_map(|p| match p {
                Prim::ClipPush { path_ops: Some(po), .. } => Some(
                    po.iter().filter(|o| matches!(o, PathOp::Move(..))).count(),
                ),
                _ => None,
            })
            .expect("the clip must be emitted");
        assert_eq!(moves, contours(&prims).len(), "clip and fill describe different paths");
    }

    /// What the page LOOKS LIKE at [`MAX_SUBPATHS`]. `m` used to drop the subpath at
    /// the cap while still moving the current point, so every following `l` was
    /// appended to the LAST subpath that did fit — drawing a stray line from it out to
    /// each dropped point and back. One overrun therefore corrupted a contour that had
    /// already been built correctly, which is worse than losing the tail. Truncation
    /// must be clean.
    #[test]
    fn overrunning_the_subpath_cap_truncates_cleanly_instead_of_joining_up() {
        let mut ops = Vec::new();
        for i in 0..(MAX_SUBPATHS + 5) {
            let x = (i % 500) as i64;
            let y = (i / 500) as i64;
            ops.push(op("m", vec![x.into(), y.into()]));
            ops.push(op("l", vec![(x + 1).into(), y.into()]));
        }
        ops.push(op("S", vec![]));
        let prims = run(&ops);
        let strokes: Vec<&Vec<(f32, f32)>> = prims
            .iter()
            .filter_map(|p| match p {
                Prim::Stroke { pts, .. } => Some(pts),
                _ => None,
            })
            .collect();
        assert_eq!(strokes.len(), MAX_SUBPATHS, "the cap must bound the subpath count");
        for (i, pts) in strokes.iter().enumerate() {
            assert_eq!(
                pts.len(),
                2,
                "subpath {i} picked up {} points: segments past the cap were appended \
                 to it, drawing a stray line across the page",
                pts.len()
            );
        }
    }

    /// §8.11.3.3 makes an OFF optional-content group's content UNDRAWN. `W`/`W*` are
    /// clipping-path operators (§8.5.4) and `n` is the no-op path-painting operator
    /// (§8.5.3 Table 60), so `W n` marks nothing at all — it sets the clipping path,
    /// which §8.4.1 Table 52 lists as a graphics-state parameter. Suppressing it is
    /// suppressing a state change, not suppressing drawing, and the clip survives the
    /// `EMC` to bound the VISIBLE content after it. Dropping it painted that content
    /// unclipped, i.e. ink outside the box the file drew for it.
    ///
    /// Consistency argument as much as a spec one: `q`, `Q`, `cm`, `gs` and the colour
    /// operators inside the same hidden run were never suppressed here.
    #[test]
    fn a_clip_set_inside_a_hidden_oc_section_still_applies_afterwards() {
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
        let res = dictionary! {
            "Properties" => dictionary! { "P1" => Object::Reference(ocg) },
        };
        let ops = vec![
            op("BDC", vec![Object::Name(b"OC".to_vec()), Object::Name(b"P1".to_vec())]),
            op("re", vec![0.into(), 0.into(), 50.into(), 50.into()]),
            op("W", vec![]),
            op("n", vec![]),
            op("EMC", vec![]),
            // Visible, and much larger than the clip the hidden run established.
            op("re", vec![0.into(), 0.into(), 200.into(), 200.into()]),
            op("f", vec![]),
        ];
        let mut prims = Vec::new();
        interpret_content(&doc, &ops, Some(&res), GraphicsState::default(), &mut prims, 0, false);

        let clip = prims
            .iter()
            .position(|p| matches!(p, Prim::ClipPush { .. }))
            .expect("`W n` in a hidden section still sets the clipping path");
        let fill = prims
            .iter()
            .position(|p| matches!(p, Prim::Fill { .. }))
            .expect("the visible fill must still paint");
        assert!(clip < fill, "the clip must be in force for the content after EMC");

        // The hidden run must still not PAINT: no fill from inside the BDC/EMC.
        assert_eq!(
            prims.iter().filter(|p| matches!(p, Prim::Fill { .. })).count(),
            1,
            "only the visible fill may paint"
        );

        // Balanced, as `Q`/end-of-stream accounting depends on.
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

    /// `text_only` is `search::build_index`, which reads only `Prim::Text` and throws
    /// the rest away. Every path, image and shading site gates soft-mask expansion on
    /// it; the four text-showing operators did not, so a `Tj` under an ExtGState
    /// `/SMask` re-interpreted the whole mask group — rasterizing any shading in it —
    /// and spent the shared [`MAX_PRIMITIVES`] budget that `show_string`'s own Text
    /// records are gated on. A mask-heavy document therefore indexed less of its text
    /// the deeper into the page it got.
    #[test]
    fn building_the_text_index_does_not_expand_soft_mask_groups() {
        let mut doc = Document::with_version("1.7");
        let mask_group = doc.add_object(Stream::new(
            dictionary! {
                "Type" => "XObject", "Subtype" => "Form",
                "Group" => dictionary! { "S" => "Transparency", "CS" => "DeviceGray" },
                "BBox" => vec![0.into(), 0.into(), 100.into(), 100.into()],
            },
            b"1 g 0 0 100 100 re f".to_vec(),
        ));
        let egs = doc.add_object(dictionary! {
            "SMask" => dictionary! {
                "S" => "Luminosity",
                "G" => Object::Reference(mask_group),
            },
        });
        let font = doc.add_object(dictionary! {
            "Type" => "Font", "Subtype" => "Type1", "BaseFont" => "Helvetica",
            "FirstChar" => 65, "LastChar" => 66,
            "Widths" => vec![1000.into(), 1000.into()],
        });
        let res = dictionary! {
            "ExtGState" => dictionary! { "GS1" => Object::Reference(egs) },
            "Font" => dictionary! { "F1" => Object::Reference(font) },
        };
        let ops = vec![
            op("gs", vec![Object::Name(b"GS1".to_vec())]),
            op("BT", vec![]),
            op("Tf", vec![Object::Name(b"F1".to_vec()), 12.into()]),
            op("Tj", vec![Object::string_literal("AB")]),
            op("ET", vec![]),
        ];

        let go = |text_only: bool| -> Vec<Prim> {
            let mut prims = Vec::new();
            interpret_content(&doc, &ops, Some(&res), GraphicsState::default(), &mut prims, 0, text_only);
            prims
        };

        let indexed = go(true);
        assert!(
            indexed.iter().any(|p| matches!(p, Prim::Text { .. })),
            "the run must still reach the search index"
        );
        assert!(
            !indexed.iter().any(|p| matches!(p, Prim::SoftMaskPush { .. })),
            "the index path must not expand the mask group"
        );

        // The render path must be unchanged: the mask still brackets the glyphs.
        let rendered = go(false);
        assert!(
            rendered.iter().any(|p| matches!(p, Prim::SoftMaskPush { .. })),
            "the render path must still apply the soft mask to text"
        );
    }

    /// A soft-mask bracket whose mask group could not be expanded must be UNWOUND,
    /// not shipped empty.
    ///
    /// §11.6.5.2 makes the mask value 0 everywhere the group does not paint, and the
    /// renderer composites the mask layer with `DST_IN`, so `SoftMaskPush` …
    /// `SoftMaskContent` `SoftMaskPop` with nothing between the separator and the pop
    /// erases every primitive inside the bracket. `wrap_with_soft_mask` opened the
    /// bracket whenever `depth < MAX_GROUP_DEPTH` (10) while `render_soft_mask_group`
    /// refused to fill it at `MAX_PATTERN_RECURSION` (4) — and refused outright for a
    /// `/G` that is missing or not a stream. Both turned "cannot mask this" into
    /// "delete this", invisibly and in the content-disappears direction. Unmasked is
    /// the §11.6.5.1 no-mask default and the right degradation.
    #[test]
    fn a_soft_mask_that_cannot_be_expanded_paints_unmasked_rather_than_erasing() {
        let mut doc = Document::with_version("1.7");
        let group = doc.add_object(Stream::new(
            dictionary! {
                "Type" => "XObject", "Subtype" => "Form",
                "Group" => dictionary! { "S" => "Transparency", "CS" => "DeviceGray" },
                "BBox" => vec![0.into(), 0.into(), 100.into(), 100.into()],
            },
            b"1 g 0 0 100 100 re f".to_vec(),
        ));
        let fill_under = |mask: SoftMask, depth: u32| -> Vec<Prim> {
            let mut prims = vec![Prim::Fill {
                argb: 0xFF00_0000,
                even_odd: false,
                contours: vec![vec![(0.0, 0.0), (10.0, 0.0), (10.0, 10.0)]],
                blend: BlendMode::Normal,
            }];
            let mut bracket = None;
            wrap_with_soft_mask(SoftMaskWrap { prims: &mut prims, start: 0, doc: &doc, resources: None, mask: &mask, depth, bracket: &mut bracket, masked_extent: None });
            prims
        };
        let good = SoftMask {
            group_id: group,
            mask_type: 1,
            ctm: IDENTITY,
            backdrop: None,
            tr: None,
        };

        // Below the expansion cap: a real bracket with real mask content.
        let ok = fill_under(good.clone(), 0);
        assert!(matches!(ok.first(), Some(Prim::SoftMaskPush { .. })), "expected a bracket");
        let sep = ok
            .iter()
            .position(|p| matches!(p, Prim::SoftMaskContent))
            .expect("separator");
        assert!(
            ok[sep + 1..].iter().any(|p| matches!(p, Prim::Fill { .. })),
            "the mask group must contribute mask content"
        );

        // At the expansion cap the bracket must be gone, and the fill must survive.
        let capped = fill_under(good.clone(), MAX_PATTERN_RECURSION);
        assert!(
            !capped.iter().any(|p| matches!(p, Prim::SoftMaskPush { .. })),
            "an unfillable bracket erases the content it was supposed to mask"
        );
        assert_eq!(
            capped.iter().filter(|p| matches!(p, Prim::Fill { .. })).count(),
            1,
            "the masked content must still be painted, unmasked"
        );

        // Same for a dangling /G, which is the malformed-file route to the same
        // erasure. A `/TR` makes the unwind remove two inserted prims, not one.
        let dangling = SoftMask {
            group_id: (9999, 0),
            mask_type: 1,
            ctm: IDENTITY,
            backdrop: None,
            tr: Some([7u8; 256]),
        };
        let broken = fill_under(dangling, 0);
        assert_eq!(broken.len(), 1, "the bracket must be unwound completely");
        assert!(matches!(broken[0], Prim::Fill { .. }));
    }
}