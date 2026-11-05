#[cfg(test)]
mod synthesis_tests2 {
    use super::stamp_label;
    use super::synthesis_tests::{annot, kinds, synth};
    use crate::*;

    /// §12.5.4 Table 166: "/W ... If this value is 0, no border shall be drawn."
    /// The synthesized stroke floors its line width at 0.5, so without an explicit
    /// check a filled Square or Circle came out ringed in a hairline outline the
    /// file expressly asked not to have — and `set_shape_border` in this very file
    /// writes `W 0` for every filled shape it authors.
    #[test]
    fn a_zero_border_width_draws_no_border() {
        for subtype in ["Square", "Circle"] {
            let mut d = annot(subtype);
            d.set("C", Object::Array(vec![1.into(), 0.into(), 0.into()]));
            d.set("IC", Object::Array(vec![0.into(), 0.into(), 1.into()]));
            d.set("BS", dictionary! { "W" => 0 });
            let prims = synth(&d, [0.0, 0.0, 60.0, 40.0]);
            assert!(
                !prims.iter().any(|p| matches!(p, Prim::Stroke { .. })),
                "{subtype} with /BS /W 0 drew a border: {:?}",
                kinds(&prims)
            );
            assert!(
                prims.iter().any(|p| matches!(p, Prim::Fill { .. })),
                "{subtype}: the /IC interior must still paint"
            );
            // A width the file did not suppress still strokes.
            d.set("BS", dictionary! { "W" => 2 });
            assert!(
                synth(&d, [0.0, 0.0, 60.0, 40.0])
                    .iter()
                    .any(|p| matches!(p, Prim::Stroke { .. })),
                "{subtype}: a nonzero /W must still draw"
            );
        }
    }

    /// Editing a FreeText's text must not re-orient it. On a rotated page
    /// `add_free_text` authors the appearance in DISPLAY orientation and carries
    /// it back with the form's `/Matrix` (§12.5.5); regenerating with an identity
    /// `/Matrix` and the raw `/Rect` extents laid the edited text sideways, and
    /// squashed it because §12.5.5 scales the transformed /BBox to fit /Rect.
    #[test]
    fn editing_free_text_preserves_the_appearance_orientation() {
        for rot in [0i64, 90, 180, 270] {
            let mut doc = Document::with_version("1.7");
            let pages_id = doc.new_object_id();
            let page = doc.add_object(dictionary! {
                "Type" => name_obj("Page"),
                "Parent" => Object::Reference(pages_id),
                "MediaBox" => rect_obj([0.0, 0.0, 612.0, 792.0]),
                "Rotate" => Object::Integer(rot),
            });
            doc.objects.insert(
                pages_id,
                Object::Dictionary(dictionary! {
                    "Type" => name_obj("Pages"),
                    "Kids" => Object::Array(vec![Object::Reference(page)]),
                    "Count" => 1,
                }),
            );
            let cat = doc.add_object(dictionary! {
                "Type" => name_obj("Catalog"),
                "Pages" => Object::Reference(pages_id),
            });
            doc.trailer.set("Root", cat);
            let handle = next_handle();
            registry().lock().unwrap_or_else(|e| e.into_inner()).insert(handle, doc);

            let aid = add_free_text(handle, 0, [40.0, 60.0, 200.0, 100.0], 0xFF00_0000, 12.0, "before")
                .expect("annotation added");
            let before = ap_box(handle, aid);
            assert!(update_free_text(handle, aid, "after"), "rot={rot}: update failed");
            let after = ap_box(handle, aid);
            assert_eq!(after, before, "rot={rot}: the appearance box/matrix changed");
            close_document(handle);
        }
    }

    /// The `/AP /N` stream's `/BBox` extents and `/Matrix`, rounded so the f32
    /// round-trip through `Object::Real` compares cleanly.
    fn ap_box(handle: i64, annot_id: i64) -> ([i64; 2], [i64; 6]) {
        let reg = registry().lock().unwrap_or_else(|e| e.into_inner());
        let doc = reg.get(&handle).expect("handle is registered");
        let dict = doc.get_dictionary(decode_id(annot_id)).expect("annot");
        let ap = dict
            .get(b"AP")
            .ok()
            .and_then(|o| deref(doc, o))
            .and_then(|o| o.as_dict().ok())
            .and_then(|d| d.get(b"N").ok())
            .and_then(|o| deref(doc, o))
            .and_then(|o| o.as_stream().ok())
            .expect("/AP /N stream");
        let bb = normalize_rect(
            ap.dict.get(b"BBox").ok().and_then(|o| read_rect(doc, o)).expect("/BBox"),
        );
        let m = ap.dict.get(b"Matrix").ok().and_then(read_matrix_obj).unwrap_or(IDENTITY);
        (
            [(bb[2] - bb[0]).round() as i64, (bb[3] - bb[1]).round() as i64],
            [
                m[0].round() as i64, m[1].round() as i64, m[2].round() as i64,
                m[3].round() as i64, m[4].round() as i64, m[5].round() as i64,
            ],
        )
    }

    #[test]
    fn stamp_names_split_into_words() {
        assert_eq!(stamp_label(b"Approved"), "APPROVED");
        assert_eq!(stamp_label(b"NotForPublicRelease"), "NOT FOR PUBLIC RELEASE");
        assert_eq!(stamp_label(b"TopSecret"), "TOP SECRET");
        assert_eq!(stamp_label(b""), "");
    }

    /// §12.5.6.7: `/LE` line endings. `/None` and unrecognised names must add
    /// nothing to the bare segment; an arrow adds exactly one more subpath.
    #[test]
    fn line_endings_are_painted_and_unknown_ones_are_ignored() {
        let strokes = |le: Option<Vec<&str>>| -> usize {
            let mut d = annot("Line");
            d.set("L", Object::Array(vec![0.into(), 0.into(), 100.into(), 0.into()]));
            if let Some(le) = le {
                d.set("LE", Object::Array(le.iter().map(|s| name_obj(s)).collect()));
            }
            synth(&d, [0.0, -10.0, 100.0, 10.0])
                .iter()
                .filter(|p| matches!(p, Prim::Stroke { .. }))
                .count()
        };
        assert_eq!(strokes(None), 1, "no /LE: just the segment");
        assert_eq!(strokes(Some(vec!["None", "None"])), 1, "/None draws nothing");
        assert_eq!(strokes(Some(vec!["Wat", "Nope"])), 1, "unknown names draw nothing");
        assert_eq!(strokes(Some(vec!["None", "OpenArrow"])), 2, "one arrowhead");
        assert_eq!(strokes(Some(vec!["ClosedArrow", "ClosedArrow"])), 3, "two heads");
    }

    /// §7.3.10 lets any object be indirect. An indirect `/C` used to read as "no
    /// colour", silently substituting the default for the author's.
    #[test]
    fn an_indirect_colour_is_dereferenced() {
        let mut doc = Document::with_version("1.7");
        let cid = doc.add_object(Object::Array(vec![1.into(), 0.into(), 0.into()]));
        let mut d = annot("Underline");
        d.set("C", Object::Reference(cid));
        d.set("QuadPoints", Object::Array(vec![0.into(), 10.into(), 100.into(), 10.into(), 0.into(), 0.into(), 100.into(), 0.into()]));
        let mut prims = Vec::new();
        synthesize_annotation_appearance(&doc, &d, [0.0, 0.0, 100.0, 10.0], &IDENTITY, &mut prims);
        let argb = prims
            .iter()
            .find_map(|p| if let Prim::Stroke { argb, .. } = p { Some(*argb) } else { None })
            .expect("no stroke");
        assert_eq!(argb, 0xFFFF_0000, "indirect /C ignored, fell back to black");
    }

    /// §7.3.3 numbers are finite, but a real with more digits than `f32` holds
    /// parses to an infinity. One such coordinate reaches every primitive built
    /// from it, and the rasterizer drops a path containing a non-finite point
    /// silently — so the annotation disappears with no error rather than being
    /// visibly malformed. Draw nothing instead, as for absent geometry.
    #[test]
    fn non_finite_geometry_draws_nothing_rather_than_a_poisoned_path() {
        // A real with more digits than f32 can hold parses to this.
        let bad = Object::Real(f32::INFINITY);
        let ok = |v: f64| Object::Real(v as f32);

        let mut line = annot("Line");
        line.set("C", Object::Array(vec![1.into(), 0.into(), 0.into()]));
        line.set("L", Object::Array(vec![ok(0.0), ok(0.0), bad.clone(), ok(10.0)]));
        assert!(synth(&line, [0.0, 0.0, 100.0, 20.0]).is_empty(), "/L");

        let mut poly = annot("Polygon");
        poly.set("C", Object::Array(vec![1.into(), 0.into(), 0.into()]));
        poly.set(
            "Vertices",
            Object::Array(vec![ok(0.0), ok(0.0), ok(10.0), bad.clone(), ok(5.0), ok(9.0)]),
        );
        assert!(synth(&poly, [0.0, 0.0, 100.0, 20.0]).is_empty(), "/Vertices");

        let mut ink = annot("Ink");
        ink.set("C", Object::Array(vec![1.into(), 0.into(), 0.into()]));
        ink.set(
            "InkList",
            Object::Array(vec![
                Object::Array(vec![ok(0.0), ok(0.0), bad.clone(), ok(1.0)]),
                Object::Array(vec![ok(0.0), ok(0.0), ok(9.0), ok(9.0)]),
            ]),
        );
        let strokes = synth(&ink, [0.0, 0.0, 100.0, 20.0]);
        assert_eq!(strokes.len(), 1, "the poisoned /InkList path drops, the clean one stays");

        let mut hl = annot("Highlight");
        hl.set(
            "QuadPoints",
            Object::Array(vec![
                ok(0.0), ok(10.0), bad, ok(10.0), ok(0.0), ok(0.0), ok(100.0), ok(0.0),
            ]),
        );
        // Falls back to the /Rect quad, which `read_rect` has already proven finite.
        for p in synth(&hl, [0.0, 0.0, 100.0, 10.0]) {
            if let Prim::Fill { contours, .. } = p {
                for (x, y) in contours.iter().flatten() {
                    assert!(x.is_finite() && y.is_finite(), "non-finite point reached a Fill");
                }
            }
        }
    }

    /// Everything a run of `Prim::Text` would paint, concatenated in emission
    /// order: the interpreter emits one prim per glyph, so a value only exists
    /// as the whole run.
    pub(crate) fn painted_text(prims: &[Prim]) -> String {
        prims
            .iter()
            .filter_map(|p| match p {
                Prim::Text { text, .. } => Some(text.as_str()),
                _ => None,
            })
            .collect()
    }

    /// A single-page document whose catalog carries an `/AcroForm`, optionally
    /// with `/NeedAppearances` set.
    pub(crate) fn form_doc(need_appearances: bool) -> Document {
        let mut doc = Document::with_version("1.7");
        let pages_id = doc.new_object_id();
        let page = doc.add_object(dictionary! {
            "Type" => name_obj("Page"),
            "Parent" => Object::Reference(pages_id),
            "MediaBox" => rect_obj([0.0, 0.0, 200.0, 200.0]),
        });
        doc.objects.insert(
            pages_id,
            Object::Dictionary(dictionary! {
                "Type" => name_obj("Pages"),
                "Kids" => Object::Array(vec![Object::Reference(page)]),
                "Count" => 1,
            }),
        );
        let mut acro = dictionary! { "Fields" => Object::Array(vec![]) };
        if need_appearances {
            acro.set("NeedAppearances", Object::Boolean(true));
        }
        let acro_id = doc.add_object(acro);
        let catalog_id = doc.add_object(dictionary! {
            "Type" => name_obj("Catalog"),
            "Pages" => Object::Reference(pages_id),
            "AcroForm" => Object::Reference(acro_id),
        });
        doc.trailer.set("Root", catalog_id);
        doc
    }

    pub(crate) fn text_widget(value: &str) -> Dictionary {
        let mut w = annot("Widget");
        w.set("Rect", rect_obj([10.0, 10.0, 110.0, 30.0]));
        w.set("FT", name_obj("Tx"));
        w.set("T", Object::string_literal("field"));
        w.set("DA", Object::string_literal("/Helv 10 Tf 0 g"));
        w.set("V", Object::string_literal(value));
        w
    }

    /// An `/AP /N` stream painting one unmistakable fill, to tell "the file's
    /// appearance ran" apart from "we synthesized one".
    pub(crate) fn fill_ap(doc: &mut Document) -> Dictionary {
        let ap = doc.add_object(Stream::new(
            dictionary! {
                "Type" => name_obj("XObject"),
                "Subtype" => name_obj("Form"),
                "BBox" => Object::Array(vec![0.into(), 0.into(), 100.into(), 20.into()]),
            },
            b"1 0 0 rg 0 0 100 20 re f".to_vec(),
        ));
        dictionary! { "N" => ap }
    }

    /// §12.7.2 Table 218 makes /V plus /DA enough for a consumer to build the
    /// appearance, and non-Acrobat producers routinely ship a filled-in form with
    /// no /AP on its widgets at all. `/NeedAppearances` was write-only in this
    /// crate and `synthesize_annotation_appearance` had no Widget arm, so such a
    /// form rendered COMPLETELY BLANK — the value was nowhere on the page.
    #[test]
    fn a_widget_with_no_appearance_still_paints_its_value() {
        let doc = form_doc(false);
        let mut prims = Vec::new();
        render_annotation(&doc, &text_widget("Ada Lovelace"), &IDENTITY, &mut prims);
        assert_eq!(painted_text(&prims), "Ada Lovelace", "the field value must be painted");
    }

    /// An empty /V has nothing to draw, and a /Btn's value NAMES an /AP state
    /// rather than supplying text — synthesizing "Off" or "Yes" as a caption
    /// would be inventing content.
    #[test]
    fn only_a_variable_text_field_with_a_value_is_synthesized() {
        let doc = form_doc(false);
        for (ft, v) in [("Tx", ""), ("Btn", "Yes"), ("Sig", "x")] {
            let mut w = text_widget(v);
            w.set("FT", name_obj(ft));
            if v.is_empty() {
                w.remove(b"V");
            }
            let mut prims = Vec::new();
            render_annotation(&doc, &w, &IDENTITY, &mut prims);
            assert!(painted_text(&prims).is_empty(), "/FT {ft} with /V {v:?} must draw no text");
        }
    }

    /// The precedence §12.7.2 Table 218 sets: `/NeedAppearances` means the
    /// appearance in the file is stale and the consumer SHALL rebuild it, so it
    /// overrides a present /AP. Without the flag the file's /AP is authoritative
    /// and must be used as-is.
    #[test]
    fn need_appearances_overrides_a_present_ap_and_nothing_else_does() {
        for need in [false, true] {
            let mut doc = form_doc(need);
            let ap = fill_ap(&mut doc);
            let mut w = text_widget("Ada Lovelace");
            w.set("AP", Object::Dictionary(ap));

            let mut prims = Vec::new();
            render_annotation(&doc, &w, &IDENTITY, &mut prims);
            let fills = prims.iter().filter(|p| matches!(p, Prim::Fill { .. })).count();
            let texts = painted_text(&prims);
            if need {
                assert_eq!(texts, "Ada Lovelace", "the appearance must be regenerated");
                assert_eq!(fills, 0, "the stale /AP must not also be played");
            } else {
                assert_eq!(fills, 1, "the file's own /AP wins when the flag is absent");
                assert!(texts.is_empty(), "nothing may be synthesized over a valid /AP");
            }
        }
    }

    /// §12.7.4.3 Table 226 bit 14: a Password field's value shall not be echoed
    /// visually. Regenerating an appearance for one would put a stored password
    /// on screen — the one case where drawing nothing is required, not merely
    /// preferred.
    #[test]
    fn a_password_field_value_is_never_regenerated_on_screen() {
        let doc = form_doc(true);
        let mut w = text_widget("hunter2");
        w.set("Ff", Object::Integer(1 << 13));
        let mut prims = Vec::new();
        render_annotation(&doc, &w, &IDENTITY, &mut prims);
        assert!(painted_text(&prims).is_empty(), "a password must not be painted");
    }

    /// /V and /DA are field keys (§12.7.3.1), so a widget that is a separate
    /// /Kids entry carries neither: both are inherited through /Parent. Reading
    /// only the widget's own dictionary blanks every multi-widget field.
    #[test]
    fn a_kid_widget_inherits_its_value_from_the_parent_field() {
        let mut doc = form_doc(false);
        let field = doc.add_object(dictionary! {
            "FT" => name_obj("Tx"),
            "T" => Object::string_literal("field"),
            "V" => Object::string_literal("inherited"),
            "DA" => Object::string_literal("/Helv 10 Tf 0 g"),
        });
        let mut w = annot("Widget");
        w.set("Rect", rect_obj([10.0, 10.0, 110.0, 30.0]));
        w.set("Parent", Object::Reference(field));
        let mut prims = Vec::new();
        render_annotation(&doc, &w, &IDENTITY, &mut prims);
        assert_eq!(painted_text(&prims), "inherited");
    }

    /// A value longer than its field must stop at the field's edge: §8.10.2
    /// clips a form XObject to its /BBox, and a regenerated appearance is one
    /// (§12.5.5). Without the clip an over-long value runs out across the page.
    #[test]
    fn a_regenerated_field_appearance_is_clipped_to_its_widget() {
        let doc = form_doc(false);
        let mut prims = Vec::new();
        render_annotation(&doc, &text_widget(&"x".repeat(400)), &IDENTITY, &mut prims);
        let clips: Vec<&Prim> = prims.iter().filter(|p| matches!(p, Prim::ClipPush { .. })).collect();
        assert_eq!(clips.len(), 1, "exactly one /BBox clip");
        let Prim::ClipPush { pts, .. } = clips[0] else { unreachable!() };
        let xs = pts.iter().map(|p| p.0);
        let ys = pts.iter().map(|p| p.1);
        let (x0, x1) = (xs.clone().fold(f32::MAX, f32::min), xs.fold(f32::MIN, f32::max));
        let (y0, y1) = (ys.clone().fold(f32::MAX, f32::min), ys.fold(f32::MIN, f32::max));
        for (got, want) in [(x0, 10.0), (y0, 10.0), (x1, 110.0), (y1, 30.0)] {
            assert!((got - want).abs() < 0.01, "clip must be the /Rect, got [{x0} {y0} {x1} {y1}]");
        }
        assert_eq!(
            prims.iter().filter(|p| matches!(p, Prim::ClipPop)).count(),
            1,
            "the clip must be popped exactly once"
        );
    }

    /// `objects.rs` deliberately yields an empty operation list rather than
    /// still-encoded bytes when every decoder fails, so an /AP /N with a broken
    /// filter chain is indistinguishable here from an empty appearance. Every
    /// other unusable-/AP branch falls back to the synthesized shape; this one
    /// returned early, so the annotation vanished outright.
    #[test]
    fn an_undecodable_appearance_stream_falls_back_to_synthesis() {
        let mut doc = Document::with_version("1.7");
        // Flate is implemented, and these bytes are not valid Flate, so the
        // decoder judges them corrupt and yields nothing.
        let ap = doc.add_object(Stream::new(
            dictionary! {
                "Type" => name_obj("XObject"),
                "Subtype" => name_obj("Form"),
                "BBox" => Object::Array(vec![0.into(), 0.into(), 20.into(), 20.into()]),
                "Filter" => name_obj("FlateDecode"),
            },
            vec![0xFF, 0x00, 0xFF, 0x00, 0xFF],
        ));
        let mut sq = annot("Square");
        sq.set("Rect", rect_obj([0.0, 0.0, 20.0, 20.0]));
        sq.set("C", Object::Array(vec![1.into(), 0.into(), 0.into()]));
        sq.set("AP", Object::Dictionary(dictionary! { "N" => ap }));

        let mut prims = Vec::new();
        render_annotation(&doc, &sq, &IDENTITY, &mut prims);
        assert!(
            prims.iter().any(|p| matches!(p, Prim::Stroke { .. })),
            "the Square must fall back to its synthesized outline, got {} prims",
            prims.len()
        );
    }

    /// §12.5.4 Table 166: "if this value is 0, no border shall be drawn". The
    /// Square arm honours it; FreeText did not, and `line_width`'s 0.5 floor
    /// turned the declared zero into a hairline the file never asked for. The
    /// /Contents text still has to survive losing the border.
    #[test]
    fn a_freetext_with_a_zero_border_width_draws_no_border() {
        let mut ft = annot("FreeText");
        ft.set("C", Object::Array(vec![0.into(), 0.into(), 1.into()]));
        ft.set("Contents", Object::string_literal("note"));
        ft.set("BS", dictionary! { "W" => 0 });
        let prims = synth(&ft, [0.0, 0.0, 100.0, 40.0]);
        assert!(
            !prims.iter().any(|p| matches!(p, Prim::Stroke { .. })),
            "/BS /W 0 must draw no border"
        );
        assert_eq!(painted_text(&prims), "note", "the text must remain");

        ft.set("BS", dictionary! { "W" => 2 });
        assert!(
            synth(&ft, [0.0, 0.0, 100.0, 40.0]).iter().any(|p| matches!(p, Prim::Stroke { .. })),
            "a declared non-zero width must still draw"
        );
    }

}