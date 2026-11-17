/// Seed 1: text + vector paths. Exercises the tokenizer, the text machinery and
/// the path/fill/stroke pipeline.
fn seed_text_and_paths() -> Vec<u8> {
    let mut doc = Document::with_version("1.7");
    let content = Content {
        operations: vec![
            Operation::new("rg", vec![1.0.into(), 0.0.into(), 0.0.into()]),
            Operation::new("re", vec![100.into(), 100.into(), 50.into(), 40.into()]),
            Operation::new("f", vec![]),
            Operation::new("0.5 w", vec![]),
            Operation::new("m", vec![10.into(), 10.into()]),
            Operation::new("c", vec![20.into(), 40.into(), 60.into(), 80.into(), 90.into(), 20.into()]),
            Operation::new("S", vec![]),
            Operation::new("q", vec![]),
            Operation::new("W", vec![]),
            Operation::new("re", vec![0.into(), 0.into(), 300.into(), 300.into()]),
            Operation::new("n", vec![]),
            Operation::new("BT", vec![]),
            Operation::new("Tf", vec![Object::Name(b"F1".to_vec()), 12.into()]),
            Operation::new("Td", vec![72.into(), 700.into()]),
            Operation::new("Tj", vec![Object::string_literal("Robustness harness seed one")]),
            Operation::new("TL", vec![14.into()]),
            Operation::new("T*", vec![]),
            Operation::new("TJ", vec![Object::Array(vec![
                Object::string_literal("kerned"),
                (-120).into(),
                Object::string_literal("text"),
            ])]),
            Operation::new("ET", vec![]),
            Operation::new("Q", vec![]),
        ],
    };
    let font_id = doc.add_object(dictionary! {
        "Type" => "Font", "Subtype" => "Type1", "BaseFont" => "Helvetica",
    });
    let content_id = doc.add_object(Stream::new(dictionary! {}, content.encode().unwrap()));
    let pages_id = doc.new_object_id();
    let page_id = doc.add_object(dictionary! {
        "Type" => "Page",
        "Parent" => pages_id,
        "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
        "Contents" => content_id,
        "Resources" => dictionary! { "Font" => dictionary! { "F1" => font_id } },
    });
    doc.objects.insert(
        pages_id,
        Object::Dictionary(dictionary! {
            "Type" => "Pages", "Kids" => vec![page_id.into()], "Count" => 1,
        }),
    );
    let catalog_id = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
    doc.trailer.set("Root", catalog_id);
    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).unwrap();
    bytes
}

/// Seed 2: a Flate-compressed DeviceRGB image XObject. Exercises the filter
/// chain, the predictor path and the image decoder.
fn seed_image() -> Vec<u8> {
    const W: usize = 24;
    const H: usize = 16;
    let mut raw = Vec::with_capacity(W * H * 3);
    for y in 0..H {
        for x in 0..W {
            raw.push((x * 10) as u8);
            raw.push((y * 15) as u8);
            raw.push(0x40);
        }
    }
    let mut doc = Document::with_version("1.7");
    let img_id = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject",
            "Subtype" => "Image",
            "Width" => W as i64,
            "Height" => H as i64,
            "ColorSpace" => "DeviceRGB",
            "BitsPerComponent" => 8,
            "Filter" => "FlateDecode",
        },
        flate(&raw),
    ));
    let content = Content {
        operations: vec![
            Operation::new("q", vec![]),
            Operation::new("cm", vec![200.into(), 0.into(), 0.into(), 150.into(), 50.into(), 400.into()]),
            Operation::new("Do", vec![Object::Name(b"Im0".to_vec())]),
            Operation::new("Q", vec![]),
        ],
    };
    let content_id = doc.add_object(Stream::new(dictionary! {}, content.encode().unwrap()));
    let pages_id = doc.new_object_id();
    let page_id = doc.add_object(dictionary! {
        "Type" => "Page",
        "Parent" => pages_id,
        "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
        "Contents" => content_id,
        "Resources" => dictionary! { "XObject" => dictionary! { "Im0" => img_id } },
    });
    doc.objects.insert(
        pages_id,
        Object::Dictionary(dictionary! {
            "Type" => "Pages", "Kids" => vec![page_id.into()], "Count" => 1,
        }),
    );
    let catalog_id = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
    doc.trailer.set("Root", catalog_id);
    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).unwrap();
    bytes
}

/// Seed 3: a form XObject under a transparency group, an axial shading driven by
/// a Type 2 function, a tiling pattern, and an annotation with an appearance
/// stream. This is the seed whose mutants reach the most code.
fn seed_rich() -> Vec<u8> {
    let mut doc = Document::with_version("1.7");

    let form_id = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject",
            "Subtype" => "Form",
            "BBox" => vec![0.into(), 0.into(), 100.into(), 100.into()],
            "Group" => dictionary! { "Type" => "Group", "S" => "Transparency" },
        },
        Content {
            operations: vec![
                Operation::new("0 0 1 rg", vec![]),
                Operation::new("re", vec![0.into(), 0.into(), 80.into(), 80.into()]),
                Operation::new("f", vec![]),
            ],
        }
        .encode()
        .unwrap(),
    ));
    let fn_id = doc.add_object(dictionary! {
        "FunctionType" => 2,
        "Domain" => vec![0.into(), 1.into()],
        "C0" => vec![1.0.into(), 0.0.into(), 0.0.into()],
        "C1" => vec![0.0.into(), 0.0.into(), 1.0.into()],
        "N" => 1,
    });
    let sh_id = doc.add_object(dictionary! {
        "ShadingType" => 2,
        "ColorSpace" => "DeviceRGB",
        "Coords" => vec![0.into(), 0.into(), 400.into(), 400.into()],
        "Function" => fn_id,
        "Extend" => vec![true.into(), true.into()],
    });
    let pat_id = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "Pattern",
            "PatternType" => 1,
            "PaintType" => 1,
            "TilingType" => 1,
            "BBox" => vec![0.into(), 0.into(), 10.into(), 10.into()],
            "XStep" => 10,
            "YStep" => 10,
            "Resources" => dictionary! {},
        },
        Content {
            operations: vec![
                Operation::new("re", vec![0.into(), 0.into(), 5.into(), 5.into()]),
                Operation::new("f", vec![]),
            ],
        }
        .encode()
        .unwrap(),
    ));
    let egs_id = doc.add_object(dictionary! { "Type" => "ExtGState", "ca" => 0.4, "CA" => 0.6 });

    let content = Content {
        operations: vec![
            Operation::new("q", vec![]),
            Operation::new("gs", vec![Object::Name(b"GS0".to_vec())]),
            Operation::new("Do", vec![Object::Name(b"Fm0".to_vec())]),
            Operation::new("Q", vec![]),
            Operation::new("q", vec![]),
            Operation::new("re", vec![0.into(), 0.into(), 300.into(), 300.into()]),
            Operation::new("W", vec![]),
            Operation::new("n", vec![]),
            Operation::new("sh", vec![Object::Name(b"Sh0".to_vec())]),
            Operation::new("Q", vec![]),
            Operation::new("cs", vec![Object::Name(b"Pattern".to_vec())]),
            Operation::new("scn", vec![Object::Name(b"P0".to_vec())]),
            Operation::new("re", vec![300.into(), 300.into(), 100.into(), 100.into()]),
            Operation::new("f", vec![]),
        ],
    };
    let content_id = doc.add_object(Stream::new(dictionary! {}, content.encode().unwrap()));

    let ap_id = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Form",
            "BBox" => vec![0.into(), 0.into(), 50.into(), 20.into()],
        },
        Content {
            operations: vec![
                Operation::new("1 1 0 rg", vec![]),
                Operation::new("re", vec![0.into(), 0.into(), 50.into(), 20.into()]),
                Operation::new("f", vec![]),
            ],
        }
        .encode()
        .unwrap(),
    ));
    let annot_id = doc.add_object(dictionary! {
        "Type" => "Annot",
        "Subtype" => "Square",
        "Rect" => vec![500.into(), 500.into(), 550.into(), 520.into()],
        "F" => 4,
        "AP" => dictionary! { "N" => ap_id },
    });

    let pages_id = doc.new_object_id();
    let page_id = doc.add_object(dictionary! {
        "Type" => "Page",
        "Parent" => pages_id,
        "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
        "Contents" => content_id,
        "Annots" => vec![annot_id.into()],
        "Resources" => dictionary! {
            "XObject" => dictionary! { "Fm0" => form_id },
            "Shading" => dictionary! { "Sh0" => sh_id },
            "Pattern" => dictionary! { "P0" => pat_id },
            "ExtGState" => dictionary! { "GS0" => egs_id },
        },
    });
    doc.objects.insert(
        pages_id,
        Object::Dictionary(dictionary! {
            "Type" => "Pages", "Kids" => vec![page_id.into()], "Count" => 1,
        }),
    );
    let catalog_id = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
    doc.trailer.set("Root", catalog_id);
    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).unwrap();
    bytes
}

fn seeds() -> Vec<(&'static str, Vec<u8>)> {
    vec![
        ("text_and_paths", seed_text_and_paths()),
        ("image", seed_image()),
        ("rich", seed_rich()),
    ]
}

// ---------------------------------------------------------------------------
// Raw-PDF assembler for adversarial constructs
// ---------------------------------------------------------------------------

/// Assemble a byte-exact PDF from object bodies with a CORRECT classic xref, so
/// that a fixture is malformed only where it is meant to be. Object ids must
/// start at 1; gaps become free entries.
///
/// Being byte-exact matters: with a broken xref every fixture would go through
/// the rebuild path instead of the construct it is trying to test.
fn raw_pdf(objects: &[(u32, Vec<u8>)], trailer_extra: &str) -> Vec<u8> {
    let mut out = Vec::from(&b"%PDF-1.7\n%\xE2\xE3\xCF\xD3\n"[..]);
    let mut offsets: Vec<(u32, usize)> = Vec::new();
    for (id, body) in objects {
        offsets.push((*id, out.len()));
        out.extend_from_slice(format!("{id} 0 obj\n").as_bytes());
        out.extend_from_slice(body);
        out.extend_from_slice(b"\nendobj\n");
    }
    let xref_at = out.len();
    let max_id = objects.iter().map(|(i, _)| *i).max().unwrap_or(0);
    out.extend_from_slice(format!("xref\n0 {}\n", max_id + 1).as_bytes());
    out.extend_from_slice(b"0000000000 65535 f \n");
    for id in 1..=max_id {
        match offsets.iter().find(|(i, _)| *i == id) {
            Some((_, off)) => out.extend_from_slice(format!("{off:010} 00000 n \n").as_bytes()),
            None => out.extend_from_slice(b"0000000000 65535 f \n"),
        }
    }
    out.extend_from_slice(
        format!(
            "trailer\n<< /Size {} {} >>\nstartxref\n{}\n%%EOF\n",
            max_id + 1,
            trailer_extra,
            xref_at
        )
        .as_bytes(),
    );
    out
}

/// A raw stream object whose dictionary is written verbatim, so `/Length` can
/// lie, and whose body is arbitrary bytes.
fn raw_stream(dict: &str, body: &[u8]) -> Vec<u8> {
    let mut v = Vec::new();
    v.extend_from_slice(dict.as_bytes());
    v.extend_from_slice(b"\nstream\n");
    v.extend_from_slice(body);
    v.extend_from_slice(b"\nendstream");
    v
}

/// Standard catalog + single-page skeleton for `raw_pdf`, so each adversarial
/// fixture only has to describe the thing it is attacking.
/// Objects 1=Catalog, 2=Pages, 3=Page, 4=Contents; caller supplies 5.. .
fn raw_one_page(page_extra: &str, content: &[u8], mut rest: Vec<(u32, Vec<u8>)>) -> Vec<u8> {
    let mut objs: Vec<(u32, Vec<u8>)> = vec![
        (1, b"<< /Type /Catalog /Pages 2 0 R >>".to_vec()),
        (2, b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_vec()),
        (
            3,
            format!(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] \
                 /Contents 4 0 R {page_extra} >>"
            )
            .into_bytes(),
        ),
        (
            4,
            raw_stream(&format!("<< /Length {} >>", content.len()), content),
        ),
    ];
    objs.append(&mut rest);
    raw_pdf(&objs, "/Root 1 0 R")
}

// ---------------------------------------------------------------------------
// Mutators
// ---------------------------------------------------------------------------

fn find_all(hay: &[u8], needle: &[u8]) -> Vec<usize> {
    if needle.is_empty() || hay.len() < needle.len() {
        return Vec::new();
    }
    (0..=hay.len() - needle.len())
        .filter(|&i| &hay[i..i + needle.len()] == needle)
        .collect()
}

/// Overwrite `len` bytes at `at` with `fill`, clamped to the buffer.
fn splat(bytes: &[u8], at: usize, len: usize, fill: u8) -> Vec<u8> {
    let mut v = bytes.to_vec();
    let end = (at + len).min(v.len());
    if at < v.len() {
        v[at..end].fill(fill);
    }
    v
}

/// Replace the ASCII digit run starting at `at` with `replacement`, keeping the
/// rest of the file intact. Used to corrupt offsets and /Length values.
fn replace_digits_at(bytes: &[u8], at: usize, replacement: &str) -> Vec<u8> {
    let mut i = at;
    while i < bytes.len() && bytes[i].is_ascii_whitespace() {
        i += 1;
    }
    let start = i;
    while i < bytes.len() && bytes[i].is_ascii_digit() {
        i += 1;
    }
    if start == i {
        return bytes.to_vec();
    }
    let mut v = Vec::with_capacity(bytes.len() + replacement.len());
    v.extend_from_slice(&bytes[..start]);
    v.extend_from_slice(replacement.as_bytes());
    v.extend_from_slice(&bytes[i..]);
    v
}
