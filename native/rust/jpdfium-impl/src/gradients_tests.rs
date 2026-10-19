use std::io::Cursor;

use lopdf::{
    content::{Content, Operation},
    dictionary, Dictionary, Document, Object, Stream,
};

/// Minimal doc with one page whose resources carry an axial shading pattern,
/// mirroring how krilla writes an SVG linear gradient: objectBoundingBox baked
fn gradient_pdf() -> Vec<u8> {
    let mut doc = Document::with_version("1.7");
    let pages_id = doc.new_object_id();

    let function = dictionary! {
        "FunctionType" => 2,
        "Domain" => vec![0.into(), 1.into()],
        "C0" => vec![0.2f32.into(), 0.4f32.into(), 1.0f32.into()],
        "C1" => vec![1.0f32.into(), 0.8f32.into(), 0.0f32.into()],
        "N" => 1,
    };
    let function_id = doc.add_object(function);
    let shading = dictionary! {
        "ShadingType" => 2,
        "ColorSpace" => Object::Name(b"DeviceRGB".to_vec()),
        "Coords" => vec![0.into(), 0.into(), 1.into(), 0.into()],
        "Extend" => vec![true.into(), true.into()],
        "Function" => function_id,
    };
    let shading_id = doc.add_object(shading);
    let pattern = dictionary! {
        "Type" => Object::Name(b"Pattern".to_vec()),
        "PatternType" => 2,
        "Matrix" => vec![80.into(), 0.into(), 0.into(), (-50).into(), 10.into(), 110.into()],
        "Shading" => shading_id,
    };
    let pattern_id = doc.add_object(pattern);
    let resources = dictionary! {
        "Pattern" => dictionary! { "p0" => pattern_id },
    };
    let resources_id = doc.add_object(resources);

    let content = Content {
        operations: vec![
            Operation::new("q", vec![]),
            Operation::new("re", vec![10.into(), 10.into(), 80.into(), 50.into()]),
            Operation::new("W", vec![]),
            Operation::new("n", vec![]),
            Operation::new(
                "cm",
                vec![
                    1.into(),
                    0.into(),
                    0.into(),
                    (-1).into(),
                    0.into(),
                    120.into(),
                ],
            ),
            Operation::new("cs", vec![Object::Name(b"Pattern".to_vec())]),
            Operation::new("scn", vec![Object::Name(b"p0".to_vec())]),
            Operation::new("re", vec![10.into(), 10.into(), 80.into(), 50.into()]),
            Operation::new("f", vec![]),
            Operation::new("Q", vec![]),
        ],
    };
    let content_id = doc.add_object(Stream::new(dictionary! {}, content.encode().unwrap()));

    let page = dictionary! {
        "Type" => Object::Name(b"Page".to_vec()),
        "Parent" => pages_id,
        "MediaBox" => vec![0.into(), 0.into(), 200.into(), 120.into()],
        "Resources" => resources_id,
        "Contents" => content_id,
    };
    let page_id = doc.add_object(page);
    let pages = dictionary! {
        "Type" => Object::Name(b"Pages".to_vec()),
        "Kids" => vec![page_id.into()],
        "Count" => 1,
    };
    doc.objects.insert(pages_id, Object::Dictionary(pages));
    let catalog = dictionary! {
        "Type" => Object::Name(b"Catalog".to_vec()),
        "Pages" => pages_id,
    };
    let catalog_id = doc.add_object(catalog);
    doc.trailer.set("Root", catalog_id);

    let mut bytes = Vec::new();
    doc.save_to(&mut Cursor::new(&mut bytes)).unwrap();
    bytes
}

#[test]
fn extracts_axial_shading_as_stops() {
    let pdf = gradient_pdf();
    let payload = crate::gradients::page_gradients(&pdf, 0).expect("gradient payload");
    let text = String::from_utf8(payload).unwrap();
    println!("{text}");

    let header: Vec<&str> = text.lines().next().unwrap().split_whitespace().collect();
    assert_eq!(header[0], "G");
    assert_eq!(header[1], "2", "axial shading type");
    assert_eq!(header[2], "1", "extend start");
    assert_eq!(header[3], "1", "extend end");
    // Pattern matrix maps the unit square onto the object, and the axis runs
    // from (10, 110) to (90, 110) in device space.
    assert_eq!(
        &header[4..10],
        ["10.0000", "110.0000", "0.0000", "90.0000", "110.0000", "0.0000"],
        "axis runs (10,110) -> (90,110) in device space"
    );
    assert_eq!(header.last(), Some(&"32"), "sampled stop count");

    // The quad is the pattern unit square in device space, which for this
    // objectBoundingBox-style matrix coincides with the object's own bounds.
    assert_eq!(
        &header[10..18],
        [
            "10.0000", "110.0000", "90.0000", "110.0000", "90.0000", "60.0000", "10.0000",
            "60.0000"
        ],
        "unit square maps onto the painted object's bounds"
    );
    let stops: Vec<&str> = text.lines().filter(|l| l.starts_with("S ")).collect();
    assert_eq!(stops.len(), 32);
    let first: Vec<f32> = stops[0]
        .split_whitespace()
        .skip(1)
        .map(|v| v.parse().unwrap())
        .collect();
    let last: Vec<f32> = stops[31]
        .split_whitespace()
        .skip(1)
        .map(|v| v.parse().unwrap())
        .collect();
    assert!((first[0] - 0.0).abs() < 1e-4, "t=0 offset");
    assert!(
        (first[1] - 0.2).abs() < 1e-3 && (first[2] - 0.4).abs() < 1e-3,
        "C0 colour"
    );
    assert!((last[0] - 1.0).abs() < 1e-4, "t=1 offset");
    assert!(
        (last[1] - 1.0).abs() < 1e-3 && (last[2] - 0.8).abs() < 1e-3,
        "C1 colour"
    );
}

#[test]
fn page_without_shadings_returns_none() {
    let mut doc = Document::with_version("1.7");
    let pages_id = doc.new_object_id();
    let content_id = doc.add_object(Stream::new(dictionary! {}, b"".to_vec()));
    let resources: Dictionary = dictionary! {};
    let resources_id = doc.add_object(resources);
    let page = dictionary! {
        "Type" => Object::Name(b"Page".to_vec()),
        "Parent" => pages_id,
        "MediaBox" => vec![0.into(), 0.into(), 100.into(), 100.into()],
        "Resources" => resources_id,
        "Contents" => content_id,
    };
    let page_id = doc.add_object(page);
    doc.objects.insert(
        pages_id,
        Object::Dictionary(dictionary! {
            "Type" => Object::Name(b"Pages".to_vec()),
            "Kids" => vec![page_id.into()],
            "Count" => 1,
        }),
    );
    let catalog_id = doc.add_object(dictionary! {
        "Type" => Object::Name(b"Catalog".to_vec()),
        "Pages" => pages_id,
    });
    doc.trailer.set("Root", catalog_id);
    let mut bytes = Vec::new();
    doc.save_to(&mut Cursor::new(&mut bytes)).unwrap();

    assert!(crate::gradients::page_gradients(&bytes, 0).is_none());
    assert!(crate::gradients::page_gradients(&bytes, 5).is_none());
}

#[test]
fn records_a_non_normal_blend_mode() {
    let mut doc = Document::with_version("1.7");
    let pages_id = doc.new_object_id();

    let function = dictionary! {
        "FunctionType" => 2,
        "Domain" => vec![0.into(), 1.into()],
        "C0" => vec![0.0f32.into(), 0.0f32.into(), 0.0f32.into()],
        "C1" => vec![1.0f32.into(), 1.0f32.into(), 1.0f32.into()],
        "N" => 1,
    };
    let function_id = doc.add_object(function);
    let shading_id = doc.add_object(dictionary! {
        "ShadingType" => 2,
        "ColorSpace" => Object::Name(b"DeviceRGB".to_vec()),
        "Coords" => vec![0.into(), 0.into(), 100.into(), 0.into()],
        "Extend" => vec![true.into(), true.into()],
        "Function" => function_id,
    });
    let pattern_id = doc.add_object(dictionary! {
        "Type" => Object::Name(b"Pattern".to_vec()),
        "PatternType" => 2,
        "Matrix" => vec![100.into(), 0.into(), 0.into(), 100.into(), 0.into(), 0.into()],
        "Shading" => shading_id,
    });
    let extgstate_id = doc.add_object(dictionary! {
        "Type" => Object::Name(b"ExtGState".to_vec()),
        "BM" => Object::Name(b"Multiply".to_vec()),
    });
    let resources_id = doc.add_object(dictionary! {
        "Pattern" => dictionary! { "p0" => pattern_id },
        "ExtGState" => dictionary! { "GS0" => extgstate_id },
    });

    let content = Content {
        operations: vec![
            Operation::new("q", vec![]),
            Operation::new("gs", vec![Object::Name(b"GS0".to_vec())]),
            Operation::new("cs", vec![Object::Name(b"Pattern".to_vec())]),
            Operation::new("scn", vec![Object::Name(b"p0".to_vec())]),
            Operation::new("re", vec![10.into(), 10.into(), 40.into(), 30.into()]),
            Operation::new("f", vec![]),
            Operation::new("Q", vec![]),
        ],
    };
    let content_id = doc.add_object(Stream::new(dictionary! {}, content.encode().unwrap()));
    let page_id = doc.add_object(dictionary! {
        "Type" => Object::Name(b"Page".to_vec()),
        "Parent" => pages_id,
        "MediaBox" => vec![0.into(), 0.into(), 100.into(), 100.into()],
        "Resources" => resources_id,
        "Contents" => content_id,
    });
    doc.objects.insert(
        pages_id,
        Object::Dictionary(dictionary! {
            "Type" => Object::Name(b"Pages".to_vec()),
            "Kids" => vec![page_id.into()],
            "Count" => 1,
        }),
    );
    let catalog_id = doc.add_object(dictionary! {
        "Type" => Object::Name(b"Catalog".to_vec()),
        "Pages" => pages_id,
    });
    doc.trailer.set("Root", catalog_id);
    let mut bytes = Vec::new();
    doc.save_to(&mut Cursor::new(&mut bytes)).unwrap();

    let text = String::from_utf8(crate::gradients::page_gradients(&bytes, 0).unwrap()).unwrap();
    println!("{text}");
    let paint = text
        .lines()
        .find(|l| l.starts_with("P "))
        .expect("a paint record for the pattern-filled rectangle");
    let tokens: Vec<&str> = paint.split_whitespace().collect();
    assert_eq!(
        &tokens[1..5],
        ["10.0000", "10.0000", "50.0000", "40.0000"],
        "device bbox"
    );
    assert_eq!(tokens[5], "0", "gradient index");
    assert_eq!(tokens[6], "Multiply", "blend mode");
}

#[test]
fn file_route_matches_the_byte_route_behind_a_page_marker() {
    let pdf = gradient_pdf();
    let dir = std::env::temp_dir().join(format!("jpdfium-grad-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("one.pdf");
    std::fs::write(&path, &pdf).unwrap();

    let single = crate::gradients::page_gradients(&pdf, 0).expect("byte route payload");
    let file = std::fs::File::open(&path).unwrap();
    let multi = crate::gradients::page_gradients_reader(file, Some(&[0][..])).expect("file route");
    let text = String::from_utf8(multi).unwrap();
    assert!(text.starts_with("#PAGE 0\n"), "page marker missing: {text}");
    assert_eq!(text["#PAGE 0\n".len()..].as_bytes(), single.as_slice());

    // An index the document does not have must yield nothing, not an empty page.
    let file = std::fs::File::open(&path).unwrap();
    assert!(crate::gradients::page_gradients_reader(file, Some(&[7][..])).is_none());

    let _ = std::fs::remove_dir_all(&dir);
}
