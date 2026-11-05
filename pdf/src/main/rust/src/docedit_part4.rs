#[cfg(test)]
mod redaction_tests_cont {
    use super::*;
    use super::redaction_tests::{annot_exists, page_bytes, redactable_doc};

    /// An inline image makes lopdf reject the whole stream, and the lenient tokenizer
    /// cannot promise it saw every text-show operator. Redacting anyway would cover the
    /// text with black, drop the annotation and still ship the text inside the file, so
    /// the operation must refuse and leave the document exactly as it was.
    #[test]
    fn a_stream_only_the_lenient_tokenizer_can_read_is_not_redacted() {
        // No /BPC, so the strict content parse fails on the inline image and only
        // the lenient tokenizer can read this stream.
        let mut content = b"BT /F1 12 Tf 100 700 Td (secret) Tj ET\n".to_vec();
        content.extend_from_slice(b"BI /W 2 /H 2 /CS /G ID ");
        content.extend_from_slice(&[0x00, 0x40, 0x80, 0xFF]);
        content.extend_from_slice(b" EI\n");
        let (handle, page_id, annot_id) = redactable_doc(&content, [90, 690, 200, 720]);
        {
            let reg = registry().lock().unwrap_or_else(|e| e.into_inner());
            let doc = reg.get(&handle).expect("handle is registered");
            assert!(
                crate::content::strict_operations(&crate::content::page_content_bytes(
                    doc, page_id
                ))
                .is_none(),
                "precondition: the strict content parse is expected to reject this stream"
            );
        }

        assert!(!apply_redactions(handle), "redaction must report failure, not success");
        assert!(
            annot_exists(handle, annot_id),
            "the annotation must survive so has_redactions stays true"
        );
        let after = page_bytes(handle, page_id);
        assert_eq!(after, content, "the content stream must be left untouched");
        close_document(handle);
    }

    /// §12.5.6.24: redaction means the content is REMOVED, not covered. The
    /// pre-redaction content stream still holds the text verbatim and
    /// `save_document` writes every object in the document, so leaving it behind
    /// ships a file the user believes is redacted with the text still in it —
    /// recoverable with any object dumper.
    #[test]
    fn the_pre_redaction_content_stream_is_not_left_in_the_saved_file() {
        let content = b"BT /F1 12 Tf 100 700 Td (secret) Tj ET\n";
        let (handle, _page_id, _annot_id) = redactable_doc(content, [90, 690, 200, 720]);
        assert!(apply_redactions(handle));
        let saved = save_document(handle).expect("save");
        assert!(
            !saved.windows(6).any(|w| w == b"secret"),
            "the redacted text is still in the saved file"
        );
        close_document(handle);
    }

    /// §8.9.7: lopdf represents `BI` as a single operation whose operand is an
    /// `Object::Stream`, and `Content::encode` writes an `Object::Stream` in
    /// INDIRECT-object syntax (`<<...>> stream ... endstream`) — there is no `ID`
    /// and the `BI` lands after the data. Re-encoding such a page destroys the
    /// image and desynchronises everything after it. The refusal at the
    /// `page_operations` call only covers streams lopdf CANNOT parse, so a
    /// perfectly good inline image took this path.
    #[test]
    fn redacting_a_page_with_an_inline_image_keeps_the_image_intact() {
        let px: Vec<u8> = (1u8..=12).collect(); // 2x2, 3 components, 8 bpc
        let mut content = b"BT /F1 12 Tf 100 700 Td (secret) Tj ET\n".to_vec();
        content.extend_from_slice(b"q 10 0 0 10 300 300 cm BI /W 2 /H 2 /CS /RGB /BPC 8 ID ");
        content.extend_from_slice(&px);
        content.extend_from_slice(b" EI Q\n");
        let (handle, page_id, _annot_id) = redactable_doc(&content, [90, 690, 200, 720]);
        {
            let reg = registry().lock().unwrap_or_else(|e| e.into_inner());
            let doc = reg.get(&handle).expect("handle is registered");
            assert!(
                doc.get_and_decode_page_content(page_id).is_ok(),
                "precondition: lopdf parses this inline image, so redaction proceeds"
            );
        }
        assert!(apply_redactions(handle), "a healthy page must redact");

        let after = page_bytes(handle, page_id);
        assert!(
            !after.windows(6).any(|w| w == b"secret"),
            "the text under the rect must be gone: {}",
            String::from_utf8_lossy(&after)
        );
        assert!(
            !after.windows(9).any(|w| w == b"endstream"),
            "an inline image must not be written back as an indirect stream: {}",
            String::from_utf8_lossy(&after)
        );
        let ops = crate::content::parse_operations_lenient(&after);
        let images: Vec<Vec<u8>> = ops
            .iter()
            .filter(|o| o.operator == "BI")
            .filter_map(|o| match o.operands.first() {
                Some(Object::Stream(s)) => Some(s.content.clone()),
                _ => None,
            })
            .collect();
        assert_eq!(images, vec![px], "the inline image data must survive verbatim");
        // And the operators after the image must still be there.
        let names: Vec<&str> = ops.iter().map(|o| o.operator.as_str()).collect();
        assert!(names.contains(&"Q"), "content after the image was lost: {names:?}");
        close_document(handle);
    }

    /// §7.7.3.4: `/Resources` is INHERITABLE. `resources_dict` (the renderer's
    /// read path) walks `/Parent` for it, so a page that carries none is not a
    /// page without resources. `add_page_xobject` wrote an inline `/Resources`
    /// built only from the page's OWN entry, which on such a page is an empty
    /// dictionary that then SHADOWS the inherited one — flattening a single
    /// annotation blanked every font and image on the page.
    #[test]
    fn flatten_does_not_shadow_an_inherited_resources_dictionary() {
        let mut doc = Document::with_version("1.7");
        let ap_id = doc.add_object(Stream::new(
            dictionary! {
                "Type" => name_obj("XObject"),
                "Subtype" => name_obj("Form"),
                "BBox" => rect_obj([0.0, 0.0, 10.0, 10.0]),
            },
            b"0 0 1 rg 0 0 10 10 re f".to_vec(),
        ));
        let annot_id = doc.add_object(dictionary! {
            "Type" => name_obj("Annot"),
            "Subtype" => name_obj("Square"),
            "Rect" => rect_obj([10.0, 10.0, 20.0, 20.0]),
            "AP" => dictionary! { "N" => ap_id },
        });
        let pages_id = doc.new_object_id();
        // No /Resources on the page: it inherits the /Pages node's.
        let page_id = doc.add_object(dictionary! {
            "Type" => name_obj("Page"),
            "Parent" => Object::Reference(pages_id),
            "MediaBox" => rect_obj([0.0, 0.0, 612.0, 792.0]),
            "Annots" => Object::Array(vec![Object::Reference(annot_id)]),
        });
        doc.objects.insert(
            pages_id,
            Object::Dictionary(dictionary! {
                "Type" => name_obj("Pages"),
                "Kids" => Object::Array(vec![Object::Reference(page_id)]),
                "Count" => 1,
                "Resources" => Object::Dictionary(helvetica_resources()),
            }),
        );
        let cat = doc.add_object(dictionary! {
            "Type" => name_obj("Catalog"),
            "Pages" => Object::Reference(pages_id),
        });
        doc.trailer.set("Root", cat);
        let handle = next_handle();
        registry().lock().unwrap_or_else(|e| e.into_inner()).insert(handle, doc);

        assert!(flatten_document(handle));
        let reg = registry().lock().unwrap_or_else(|e| e.into_inner());
        let doc = reg.get(&handle).expect("handle is registered");
        let res = resources_dict(doc, page_id).expect("the page must still resolve resources");
        assert!(
            res.get(b"Font").is_ok(),
            "the inherited /Font was shadowed by the flatten overlay: {res:?}"
        );
        assert!(
            res.get(b"XObject").is_ok(),
            "the flattened appearance must still be reachable: {res:?}"
        );
        drop(reg);
        close_document(handle);
    }

    /// The refusal above must not cost the normal path: a stream lopdf parses is still
    /// redacted, so the fix cannot regress any document that redacts today.
    #[test]
    fn a_stream_lopdf_parses_is_still_redacted() {
        let content = b"BT /F1 12 Tf 100 700 Td (secret) Tj ET\n";
        let (handle, page_id, annot_id) = redactable_doc(content, [90, 690, 200, 720]);

        assert!(apply_redactions(handle), "a healthy page must still redact");
        assert!(!annot_exists(handle, annot_id), "the applied annotation must be removed");
        let after = page_bytes(handle, page_id);
        assert!(
            !after.windows(6).any(|w| w == b"secret"),
            "the text under the rect must be gone: {}",
            String::from_utf8_lossy(&after)
        );
        assert!(
            after.windows(5).any(|w| w == b" re f"),
            "the region must be covered: {}",
            String::from_utf8_lossy(&after)
        );
        close_document(handle);
    }
}

