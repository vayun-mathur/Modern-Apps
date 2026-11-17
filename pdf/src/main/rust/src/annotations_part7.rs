#[cfg(test)]
mod synthesis_tests {
    
    use crate::*;

    // A page index arrives from the JNI boundary as a `jint` and is not validated there,
    // so `nth_page_id` must treat a negative one as out of range. `(index as u32) + 1`
    // panicked in debug for -1 and wrapped to 0 in release, which page keys being 1-based
    // made harmless by luck rather than by design.
    #[test]
    fn a_negative_page_index_is_out_of_range_not_an_overflow() {
        let mut doc = Document::with_version("1.7");
        let pages_id = doc.new_object_id();
        let page = doc.add_object(dictionary! {
            "Type" => name_obj("Page"),
            "Parent" => Object::Reference(pages_id),
            "MediaBox" => rect_obj([0.0, 0.0, 100.0, 100.0]),
        });
        doc.objects.insert(
            pages_id,
            Object::Dictionary(dictionary! {
                "Type" => name_obj("Pages"),
                "Kids" => Object::Array(vec![Object::Reference(page)]),
                "Count" => 1,
            }),
        );
        let catalog_id = doc.add_object(dictionary! {
            "Type" => name_obj("Catalog"),
            "Pages" => Object::Reference(pages_id),
        });
        doc.trailer.set("Root", catalog_id);
        assert_eq!(nth_page_id(&doc, 0), Some(page), "page 0 is the first page");
        for bad in [-1, -2, -1000, i32::MIN, i32::MIN + 1] {
            assert_eq!(nth_page_id(&doc, bad), None, "index {bad} must be out of range");
        }
        assert_eq!(nth_page_id(&doc, i32::MAX), None, "past the end is out of range");
    }

    // §8.7.4.3: `sh` fills the whole of the CURRENT CLIP. An appearance stream is a form
    // XObject (§12.5.5) and is normally clipped to its /BBox, but a missing or degenerate
    // /BBox used to emit no clip at all — so one `sh` inside such an appearance flooded
    // the entire page instead of the annotation. The /Rect bounds the annotation whatever
    // the /BBox says, so it is the fallback.
    #[test]
    fn an_appearance_without_a_usable_bbox_still_clips_shadings_to_the_rect() {
        let rect = [10.0, 20.0, 40.0, 60.0];
        for bbox in [None, Some(vec![0.into(), 0.into(), 0.into(), 0.into()])] {
            let mut doc = Document::with_version("1.7");
            let mut ap_dict = dictionary! {
                "Type" => name_obj("XObject"),
                "Subtype" => name_obj("Form"),
            };
            if let Some(b) = bbox.clone() {
                ap_dict.set("BBox", Object::Array(b));
            }
            let ap = doc.add_object(Stream::new(ap_dict, b"0 0 1 rg 0 0 100 100 re f".to_vec()));
            let annot = dictionary! {
                "Type" => name_obj("Annot"),
                "Subtype" => name_obj("Square"),
                "Rect" => rect_obj(rect),
                "AP" => dictionary! { "N" => ap },
            };
            let mut prims = Vec::new();
            render_annotation(&doc, &annot, &IDENTITY, &mut prims);

            let clips: Vec<&Prim> = prims
                .iter()
                .filter(|p| matches!(p, Prim::ClipPush { .. }))
                .collect();
            assert_eq!(clips.len(), 1, "an unusable /BBox must still emit one clip");
            let Prim::ClipPush { pts, .. } = clips[0] else {
                unreachable!()
            };
            let xs = pts.iter().map(|p| p.0);
            let ys = pts.iter().map(|p| p.1);
            let (x0, x1) = (xs.clone().fold(f32::MAX, f32::min), xs.fold(f32::MIN, f32::max));
            let (y0, y1) = (ys.clone().fold(f32::MAX, f32::min), ys.fold(f32::MIN, f32::max));
            for (got, want) in [(x0, 10.0), (y0, 20.0), (x1, 40.0), (y1, 60.0)] {
                assert!(
                    (got - want).abs() < 0.01,
                    "clip must be the /Rect, got [{x0} {y0} {x1} {y1}]"
                );
            }
            // The clip is still balanced.
            assert_eq!(
                prims.iter().filter(|p| matches!(p, Prim::ClipPop)).count(),
                1,
                "the fallback clip must be popped exactly once"
            );
        }
    }

    // The OPPOSITE failure to the clip test above, and a separate bug: §8.7.4.1 makes `sh`
    // cover the whole clipping region, so a shading with no `/BBox` of its own needs the
    // device clip extent to know what to cover. `render_annotation` used `interpret_content`,
    // which seeds that extent as `None`, and `rasterize_shading` then returns `None` and
    // pushes nothing — so a gradient painted with `sh` inside an appearance stream was
    // INVISIBLE. Too little painted, where the clip bug was too much.
    #[test]
    fn a_shading_with_no_bbox_still_paints_inside_an_appearance_stream() {
        let mut doc = Document::with_version("1.7");
        let func = doc.add_object(dictionary! {
            "FunctionType" => 2,
            "Domain" => Object::Array(vec![0.into(), 1.into()]),
            "C0" => Object::Array(vec![1.into(), 0.into(), 0.into()]),
            "C1" => Object::Array(vec![0.into(), 0.into(), 1.into()]),
            "N" => 1,
        });
        // Deliberately no /BBox on the shading: that is the case that needs the seed.
        let shading = doc.add_object(dictionary! {
            "ShadingType" => 2,
            "ColorSpace" => name_obj("DeviceRGB"),
            "Coords" => Object::Array(vec![0.into(), 0.into(), 40.into(), 0.into()]),
            "Function" => Object::Reference(func),
        });
        let ap = doc.add_object(Stream::new(
            dictionary! {
                "Type" => name_obj("XObject"),
                "Subtype" => name_obj("Form"),
                "BBox" => Object::Array(vec![0.into(), 0.into(), 40.into(), 40.into()]),
                "Resources" => dictionary! { "Shading" => dictionary! { "Sh0" => shading } },
            },
            b"/Sh0 sh".to_vec(),
        ));
        let annot = dictionary! {
            "Type" => name_obj("Annot"),
            "Subtype" => name_obj("Square"),
            "Rect" => rect_obj([0.0, 0.0, 40.0, 40.0]),
            "AP" => dictionary! { "N" => ap },
        };
        let mut prims = Vec::new();
        render_annotation(&doc, &annot, &IDENTITY, &mut prims);
        let images = prims.iter().filter(|p| matches!(p, Prim::Image { .. })).count();
        assert_eq!(
            images, 1,
            "the shading must rasterize: got {:?}",
            prims
                .iter()
                .map(|p| match p {
                    Prim::Image { .. } => "Image",
                    Prim::ClipPush { .. } => "ClipPush",
                    Prim::ClipPop => "ClipPop",
                    _ => "other",
                })
                .collect::<Vec<_>>()
        );
    }

    pub(crate) fn annot(subtype: &str) -> Dictionary {
        let mut d = Dictionary::new();
        d.set("Type", name_obj("Annot"));
        d.set("Subtype", name_obj(subtype));
        d
    }

    /// Synthesize with an identity base matrix so device space == page space.
    pub(crate) fn synth(dict: &Dictionary, rect: [f64; 4]) -> Vec<Prim> {
        let doc = Document::with_version("1.7");
        let mut prims = Vec::new();
        synthesize_annotation_appearance(&doc, dict, rect, &IDENTITY, &mut prims);
        prims
    }

    /// §12.5.2 /CA is the opacity of the WHOLE annotation, and §11.6.6 applies a group's
    /// alpha once, to the composited result. Round 1 put that alpha on `GroupPush`; round 2
    /// added a `/CA` pass that walked the emitted prims and scaled each one — so an
    /// appearance containing a transparency group got `/CA` on the group AND on everything
    /// inside it, i.e. CA² on the contents. This pins one application per layer.
    #[test]
    fn annotation_ca_is_applied_once_over_a_transparency_group() {
        let mut doc = Document::with_version("1.7");
        let form = doc.add_object(Stream::new(
            dictionary! {
                "Type" => name_obj("XObject"),
                "Subtype" => name_obj("Form"),
                "BBox" => Object::Array(vec![0.into(), 0.into(), 10.into(), 10.into()]),
                "Group" => dictionary! { "S" => name_obj("Transparency") },
            },
            b"0 0 1 rg 0 0 10 10 re f".to_vec(),
        ));
        let gstate = doc.add_object(dictionary! { "Type" => name_obj("ExtGState"), "ca" => 0.5 });
        let ap = doc.add_object(Stream::new(
            dictionary! {
                "Type" => name_obj("XObject"),
                "Subtype" => name_obj("Form"),
                "BBox" => Object::Array(vec![0.into(), 0.into(), 10.into(), 10.into()]),
                "Resources" => dictionary! {
                    "XObject" => dictionary! { "Fm1" => form },
                    "ExtGState" => dictionary! { "GS1" => gstate },
                },
            },
            b"/GS1 gs /Fm1 Do".to_vec(),
        ));
        let annot = dictionary! {
            "Type" => name_obj("Annot"),
            "Subtype" => name_obj("Square"),
            "Rect" => Object::Array(vec![0.into(), 0.into(), 10.into(), 10.into()]),
            "AP" => dictionary! { "N" => ap },
            "CA" => 0.5,
        };
        let mut prims = Vec::new();
        render_annotation(&doc, &annot, &IDENTITY, &mut prims);

        let group_alphas: Vec<f32> = prims
            .iter()
            .filter_map(|p| match p {
                Prim::GroupPush { alpha, .. } => Some(*alpha),
                _ => None,
            })
            .collect();
        assert_eq!(group_alphas.len(), 2, "one layer for /CA, one for the form's own group");
        for a in &group_alphas {
            assert!((a - 0.5).abs() < 1e-6, "each layer carries its own alpha once, got {a}");
        }
        let fills: Vec<u32> = prims
            .iter()
            .filter_map(|p| match p {
                Prim::Fill { argb, .. } => Some(*argb),
                _ => None,
            })
            .collect();
        assert_eq!(fills.len(), 1, "expected the form's single fill");
        assert_eq!(
            fills[0] >> 24,
            0xFF,
            "the fill inside the group must stay opaque: both /ca and /CA belong to the \
             composited layers, not to each element inside them"
        );
        // Every push is closed.
        let pushes = prims.iter().filter(|p| matches!(p, Prim::GroupPush { .. })).count();
        let pops = prims.iter().filter(|p| matches!(p, Prim::GroupPop)).count();
        assert_eq!(pushes, pops, "group brackets must balance");
    }

    /// `Prim` has no `Debug`, so failures report the primitive kinds instead.
    pub(crate) fn kinds(prims: &[Prim]) -> Vec<&'static str> {
        prims
            .iter()
            .map(|p| match p {
                Prim::Text { .. } => "Text",
                Prim::Fill { .. } => "Fill",
                Prim::Stroke { .. } => "Stroke",
                _ => "other",
            })
            .collect()
    }

    fn only_stroke(prims: &[Prim]) -> Vec<(f32, f32)> {
        let mut found = None;
        for p in prims {
            if let Prim::Stroke { pts, .. } = p {
                assert!(found.is_none(), "expected exactly one stroke, got {:?}", kinds(prims));
                found = Some(pts.clone());
            }
        }
        found.expect("no stroke emitted")
    }

    /// §12.5.6.10 quad order is UL, UR, LL, LR. The rule must be built from the
    /// quad's own edges: taking the DEVICE-SPACE BBOX instead collapses a rotated
    /// quad to an axis-aligned box, so an underline under vertical text was drawn
    /// as a short horizontal stroke across the glyphs instead of a long vertical
    /// one alongside them. Same class of bug as the round-1 Highlight bow-tie.
    #[test]
    fn text_markup_rules_follow_the_quad_not_its_bbox() {
        // A quad whose reading direction (UL -> UR) runs along +Y: vertical text.
        let quad = [0.0, 0.0, 0.0, 100.0, 20.0, 0.0, 20.0, 100.0];
        for subtype in ["Underline", "StrikeOut"] {
            let mut d = annot(subtype);
            d.set("QuadPoints", Object::Array(quad.iter().map(|v| (*v).into()).collect()));
            let pts = only_stroke(&synth(&d, [0.0, 0.0, 20.0, 100.0]));
            assert_eq!(pts.len(), 2, "{subtype}");
            let (dx, dy) = ((pts[1].0 - pts[0].0).abs(), (pts[1].1 - pts[0].1).abs());
            assert!(dx < 0.01, "{subtype}: rule is not parallel to the text (dx={dx})");
            assert!((dy - 100.0).abs() < 0.01, "{subtype}: rule spans {dy}, not the quad's 100");
        }
        // StrikeOut bisects the quad; Underline sits near the bottom edge, which
        // for this quad is the x=20 side.
        let mut d = annot("StrikeOut");
        d.set("QuadPoints", Object::Array(quad.iter().map(|v| (*v).into()).collect()));
        assert!((only_stroke(&synth(&d, [0.0, 0.0, 20.0, 100.0]))[0].0 - 10.0).abs() < 0.01);
        let mut d = annot("Underline");
        d.set("QuadPoints", Object::Array(quad.iter().map(|v| (*v).into()).collect()));
        assert!((only_stroke(&synth(&d, [0.0, 0.0, 20.0, 100.0]))[0].0 - 18.0).abs() < 0.01);
    }

    /// A crude wrong shape is worse than an absent one: these subtypes used to
    /// fall back to stroking the `/Rect`, which is indistinguishable from a Square
    /// annotation and asserts a geometry the file never supplied.
    #[test]
    fn subtypes_without_their_defining_geometry_draw_nothing() {
        let rect = [0.0, 0.0, 60.0, 40.0];
        for subtype in ["Polygon", "PolyLine", "FileAttachment", "Sound", "Movie", "Screen", "Link", "Widget"] {
            let mut d = annot(subtype);
            d.set("C", Object::Array(vec![1.into(), 0.into(), 0.into()]));
            let prims = synth(&d, rect);
            assert!(prims.is_empty(), "{subtype} drew {:?}", kinds(&prims));
        }
        // A Stamp with no /Name has no wording to show, so it draws nothing too.
        assert!(synth(&annot("Stamp"), rect).is_empty());
    }

    /// A Caret is an insertion mark (§12.5.6.11) and a Stamp says something
    /// (§12.5.6.12) — both get a synthesis that carries their meaning.
    #[test]
    fn caret_and_named_stamp_synthesize_something_meaningful() {
        let rect = [0.0, 0.0, 60.0, 40.0];
        let caret = synth(&annot("Caret"), rect);
        assert!(
            matches!(caret.as_slice(), [Prim::Fill { contours, .. }] if contours[0].len() == 3),
            "caret should be a filled triangle, got {:?}",
            kinds(&caret)
        );

        let mut d = annot("Stamp");
        d.set("Name", name_obj("ForPublicRelease"));
        let prims = synth(&d, rect);
        let text: Vec<&String> = prims
            .iter()
            .filter_map(|p| if let Prim::Text { text, .. } = p { Some(text) } else { None })
            .collect();
        assert_eq!(text, vec!["FOR PUBLIC RELEASE"], "got {:?}", kinds(&prims));
    }

    /// §12.5.5 fits the /Matrix-transformed /BBox onto /Rect. The rotated-page
    /// appearances (`add_free_text`, `add_callout`, `add_note`, `add_stamp`,
    /// generated field appearances) rely on that fit coming out as a pure
    /// translation: they author a `dw`x`dh` box in DISPLAY orientation and let
    /// `/Matrix` rotate it back, so if the transformed BBox did not match the raw
    /// `/Rect` the content would be squashed instead of rotated.
    #[test]
    fn oriented_appearance_fits_its_rect_without_scaling() {
        let (dw, dh) = (160.0_f64, 40.0_f64);
        for rot in [0i64, 90, 180, 270] {
            let (_, _, apm) = display_orientation(rot, dw, dh);
            // The raw /Rect a caller stores: display dims swap for quarter turns.
            let rect = match rot {
                90 | 270 => [10.0, 20.0, 10.0 + dh, 20.0 + dw],
                _ => [10.0, 20.0, 10.0 + dw, 20.0 + dh],
            };
            let m = appearance_matrix(rect, [0.0, 0.0, dw, dh], apm);
            let sx = (m[0] * m[0] + m[1] * m[1]).sqrt();
            let sy = (m[2] * m[2] + m[3] * m[3]).sqrt();
            assert!(
                (sx - 1.0).abs() < 1e-9 && (sy - 1.0).abs() < 1e-9,
                "rot={rot}: appearance scaled by ({sx},{sy}) instead of only rotated"
            );
            for (x, y) in [(0.0, 0.0), (dw, 0.0), (dw, dh), (0.0, dh)] {
                let (px, py) = transform(&m, x, y);
                assert!(
                    px >= rect[0] - 1e-6 && px <= rect[2] + 1e-6
                        && py >= rect[1] - 1e-6 && py <= rect[3] + 1e-6,
                    "rot={rot}: BBox corner ({px},{py}) fell outside {rect:?}"
                );
            }
        }
        // /Rotate 0 must be a strict no-op.
        assert_eq!(display_orientation(0, dw, dh), (dw, dh, IDENTITY));
    }

    /// §12.5.5 computes the appearance placement as `AA = Matrix × A`, where
    /// `AA` maps form space straight to page space. A caller that emits
    /// `cm ... Do` must NOT use `AA`: §8.10.2 makes `Do` concatenate the form's
    /// own `/Matrix` for it, so `AA` there applies `/Matrix` twice. This pins
    /// `appearance_fit_matrix` as `A` and shows that the doubled form escapes the
    /// `/Rect` entirely, which is what `flatten_document` used to bake in.
    #[test]
    fn the_fit_matrix_omits_the_form_matrix_that_do_reapplies() {
        let (dw, dh) = (160.0_f64, 40.0_f64);
        let bbox = [0.0, 0.0, dw, dh];
        for rot in [0i64, 90, 180, 270] {
            let (_, _, apm) = display_orientation(rot, dw, dh);
            let rect = match rot {
                90 | 270 => [10.0, 20.0, 10.0 + dh, 20.0 + dw],
                _ => [10.0, 20.0, 10.0 + dw, 20.0 + dh],
            };
            let aa = appearance_matrix(rect, bbox, apm);
            let a = appearance_fit_matrix(rect, bbox, apm);
            // What the interpreter's `Do` arm actually builds from a `cm A`.
            let via_do = mat_mul(&apm, &a);
            for i in 0..6 {
                assert!(
                    (via_do[i] - aa[i]).abs() < 1e-9,
                    "rot={rot}: `cm A ... Do` must land on AA, element {i}"
                );
            }
            if rot == 0 {
                assert_eq!(a, aa, "identity /Matrix: A and AA coincide");
                continue;
            }
            // Emitting AA in the `cm` instead: `Do` rotates a second time and the
            // appearance leaves its own /Rect.
            let doubled = mat_mul(&apm, &aa);
            let (px, py) = transform(&doubled, dw, dh);
            assert!(
                px < rect[0] - 1e-6 || px > rect[2] + 1e-6 || py < rect[1] - 1e-6 || py > rect[3] + 1e-6,
                "rot={rot}: doubling /Matrix should have escaped {rect:?}, got ({px},{py})"
            );
        }
    }

    /// §12.5.6.24 Table 187: `/IC` is "the interior colour with which to fill the
    /// redacted region AFTER the affected content has been removed". Painting it
    /// while the content is still there is a wash over the very text the user has
    /// to read to check the mark. Only the outline may be synthesized.
    #[test]
    fn a_pending_redaction_marks_the_region_without_covering_it() {
        let mut d = annot("Redact");
        d.set("IC", Object::Array(vec![0.into(), 0.into(), 0.into()]));
        d.set("C", Object::Array(vec![1.into(), 0.into(), 0.into()]));
        let prims = synth(&d, [0.0, 0.0, 60.0, 40.0]);
        assert!(
            !prims.iter().any(|p| matches!(p, Prim::Fill { .. })),
            "a pending redaction must not fill its region: {:?}",
            kinds(&prims)
        );
        assert_eq!(
            prims.iter().filter(|p| matches!(p, Prim::Stroke { .. })).count(),
            1,
            "the region must still be outlined: {:?}",
            kinds(&prims)
        );
    }

}