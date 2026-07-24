//! Type3 font parsing scaffold (Phase 3).
//! Minimal parser for FontMatrix, CharProcs, Encoding.
//! Full CharProcs interpretation deferred to shading-style rasterization.

use std::collections::HashMap;
use lopdf::{Dictionary, Document, Object, ObjectId};

type Mat = [f64; 6];

fn num(obj: &Object) -> Option<f64> {
    match obj {
        Object::Integer(i) => Some(*i as f64),
        Object::Real(r) => Some(*r as f64),
        _ => None,
    }
}
fn deref<'a>(doc: &'a Document, obj: &'a Object) -> Option<&'a Object> {
    match doc.dereference(obj) {
        Ok((_, o)) => Some(o),
        Err(_) => None,
    }
}
fn read_matrix_from_array(arr: &[Object], doc: &Document) -> Option<Mat> {
    let nums: Vec<f64> = arr.iter().filter_map(|o| deref(doc, o).and_then(num).or_else(|| num(o))).collect();
    if nums.len() == 6 {
        Some([nums[0], nums[1], nums[2], nums[3], nums[4], nums[5]])
    } else { None }
}

#[derive(Clone)]
pub struct Type3Info {
    pub font_matrix: Mat,
    pub bbox: Option<[f64;4]>,
    pub char_procs: HashMap<Vec<u8>, ObjectId>,
    pub encoding: HashMap<u8, Vec<u8>>,
    pub resources: Option<Dictionary>,
}

pub fn parse_type3_font(doc: &Document, dict: &Dictionary) -> Option<Type3Info> {
    let font_matrix = dict.get(b"FontMatrix").ok()
        .and_then(|o| deref(doc,o))
        .and_then(|o| o.as_array().ok())
        .and_then(|arr| read_matrix_from_array(&arr, doc))
        .unwrap_or([0.001,0.0,0.0,0.001,0.0,0.0]);

    let bbox = dict.get(b"FontBBox").ok()
        .and_then(|o| deref(doc,o))
        .and_then(|o| o.as_array().ok())
        .and_then(|arr| {
            let nums: Vec<f64> = arr.iter().filter_map(|o| deref(doc,o).and_then(num).or_else(|| num(o))).collect();
            if nums.len()==4 { Some([nums[0],nums[1],nums[2],nums[3]]) } else { None }
        });

    let char_procs = match dict.get(b"CharProcs").ok().and_then(|o| deref(doc,o)).and_then(|o| o.as_dict().ok()) {
        Some(cp) => {
            let mut m = HashMap::new();
            for (name, obj) in cp.iter() {
                if let Ok(id) = obj.as_reference() {
                    m.insert(name.clone(), id);
                }
            }
            m
        }
        None => HashMap::new(),
    };

    let mut encoding: HashMap<u8, Vec<u8>> = HashMap::new();
    if let Some(enc_dict) = dict.get(b"Encoding").ok().and_then(|o| deref(doc,o)).and_then(|o| o.as_dict().ok()) {
        if let Some(Object::Array(diffs)) = enc_dict.get(b"Differences").ok().and_then(|o| deref(doc,o)).or_else(|| enc_dict.get(b"Differences").ok()) {
            let mut code: u8 = 0;
            for item in diffs {
                if let Some(n) = deref(doc,item).and_then(num).or_else(|| num(item)) {
                    code = n as u8;
                } else {
                    let name_opt = item.as_name().ok().or_else(|| deref(doc,item).and_then(|o| o.as_name().ok()));
                    if let Some(name) = name_opt {
                        encoding.insert(code, name.to_vec());
                        code = code.wrapping_add(1);
                    }
                }
            }
        }
    }

    let resources = dict.get(b"Resources").ok().and_then(|o| deref(doc,o)).and_then(|o| o.as_dict().ok()).cloned();

    Some(Type3Info{font_matrix, bbox, char_procs, encoding, resources })
}
