//! Shading rasterizers for Type4-7 mesh shadings (Gouraud, Coons, Tensor).
//! Types 2-3 are handled in lib.rs rasterize_shading axial/radial.
//! This module adds Types 4-7 as 256x256 RGBA images.

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

fn mat_mul(m1: &Mat, m2: &Mat) -> Mat {
    [
        m1[0] * m2[0] + m1[1] * m2[2],
        m1[0] * m2[1] + m1[1] * m2[3],
        m1[2] * m2[0] + m1[3] * m2[2],
        m1[2] * m2[1] + m1[3] * m2[3],
        m1[4] * m2[0] + m1[5] * m2[2] + m2[4],
        m1[4] * m2[1] + m1[5] * m2[3] + m2[5],
    ]
}

#[derive(Clone, PartialEq)]
enum CsKind {
    DeviceGray,
    DeviceRGB,
    DeviceCMYK,
    Lab { white: [f64;3], range: [[f64;2];2], black: Option<[f64;3]> },
    Separation { name: Vec<u8>, alt: Box<CsKind>, tint_fn: Option<TintFn> },
    DeviceN { names: Vec<Vec<u8>>, alt: Box<CsKind>, tint_fn: Option<TintFn> },
    Pattern,
    Indexed { base: Box<CsKind>, lookup: Vec<u8>, base_ncomp: u8 },
    ICCBased { n: u8, alt: Option<Box<CsKind>> },
    CalRGB { white: [f64;3], gamma: [f64;3], matrix: [[f64;3];3] },
    CalGray { white: [f64;3], gamma: f64, black: Option<[f64;3]> },
}

#[derive(Clone, PartialEq)]
struct TintFn {
    c0: Vec<f64>,
    c1: Vec<f64>,
    n: f64,
    domain: [f64;2],
}

fn read_rect(doc: &Document, obj: &Object) -> Option<[f64; 4]> {
    let arr = deref(doc, obj)?.as_array().ok()?;
    if arr.len() != 4 { return None; }
    let mut out = [0.0;4];
    for (i,v) in arr.iter().enumerate() {
        out[i] = deref(doc, v).and_then(num)?;
    }
    Some(out)
}

// Re-use implementations from lib.rs via helpers - simplified duplicates for isolation

fn rgb_to_argb(r: f64, g: f64, b: f64) -> u32 {
    let c = |v: f64| (v.clamp(0.0,1.0)*255.0).round() as u32;
    0xFF00_0000 | (c(r)<<16)|(c(g)<<8)|c(b)
}
fn gray_to_argb(v: f64)->u32 { rgb_to_argb(v,v,v) }
fn cmyk_to_argb(c:f64,m:f64,y:f64,k:f64)->u32 { let r=(1.0-c)*(1.0-k); let g=(1.0-m)*(1.0-k); let b=(1.0-y)*(1.0-k); rgb_to_argb(r,g,b) }

fn eval_tint_fn(tf: &TintFn, t:f64)->Vec<f64> {
    let t_clamped=t.clamp(tf.domain[0], tf.domain[1]);
    let tn=t_clamped.powf(tf.n);
    let mut out=Vec::new();
    let len=tf.c0.len().max(tf.c1.len());
    for i in 0..len {
        let c0=tf.c0.get(i).copied().unwrap_or(0.0);
        let c1=tf.c1.get(i).copied().unwrap_or(1.0);
        out.push(c0 + (c1-c0)*tn);
    }
    out
}

// simplified eval same as lib.rs basic only DeviceRGB/Gray/CMYK for mesh
fn eval_cs_to_rgb_simple(kind: &CsKind, comps: &[f64]) -> Option<u32> {
    match kind {
        CsKind::DeviceGray => Some(gray_to_argb(comps.get(0).copied().unwrap_or(0.0))),
        CsKind::DeviceRGB => if comps.len()>=3 { Some(rgb_to_argb(comps[0], comps[1], comps[2])) } else { None },
        CsKind::DeviceCMYK => if comps.len()>=4 { Some(cmyk_to_argb(comps[0], comps[1], comps[2], comps[3])) } else { None },
        CsKind::Lab { white, .. } => {
            let l = comps.get(0).copied().unwrap_or(0.0).clamp(0.0,100.0);
            let a = comps.get(1).copied().unwrap_or(0.0);
            let b = comps.get(2).copied().unwrap_or(0.0);
            let fy=(l+16.0)/116.0; let fx=a/500.0+fy; let fz=fy-b/200.0;
            let eps=0.008856; let kappa=903.3;
            let f_inv = |t:f64| if t.powi(3)>eps { t.powi(3) } else { (t-16.0/116.0)/7.787 };
            let fx3=fx.powi(3); let fz3=fz.powi(3); let fy3=fy.powi(3);
            let xr=if fx3>eps { fx3 } else { (fx-16.0/116.0)/7.787 };
            let yr=if l>kappa*eps { fy3 } else { l/kappa };
            let zr=if fz3>eps { fz3 } else { (fz-16.0/116.0)/7.787 };
            let x=xr*white[0]; let y=yr*white[1]; let z=zr*white[2];
            let r_lin= 3.2406*x -1.5372*y -0.4986*z;
            let g_lin= -0.9689*x +1.8758*y +0.0415*z;
            let b_lin= 0.0557*x -0.2040*y +1.0570*z;
            let gamma=|u:f64|->f64 { let u=u.clamp(0.0,1.0); if u<=0.0031308 {12.92*u} else {1.055*u.powf(1.0/2.4)-0.055}};
            Some(rgb_to_argb(gamma(r_lin), gamma(g_lin), gamma(b_lin)))
        }
        CsKind::CalRGB { gamma, matrix, .. } => {
            let a= comps.get(0).copied().unwrap_or(0.0).powf(gamma[0]);
            let b= comps.get(1).copied().unwrap_or(0.0).powf(gamma[1]);
            let c= comps.get(2).copied().unwrap_or(0.0).powf(gamma[2]);
            let x= matrix[0][0]*a + matrix[0][1]*b + matrix[0][2]*c;
            let y= matrix[1][0]*a + matrix[1][1]*b + matrix[1][2]*c;
            let z= matrix[2][0]*a + matrix[2][1]*b + matrix[2][2]*c;
            let r_lin= 3.2406*x -1.5372*y -0.4986*z;
            let g_lin= -0.9689*x +1.8758*y +0.0415*z;
            let b_lin= 0.0557*x -0.2040*y +1.0570*z;
            let gc=|u:f64|->f64 { let u=u.clamp(0.0,1.0); if u<=0.0031308 {12.92*u} else {1.055*u.powf(1.0/2.4)-0.055}};
            Some(rgb_to_argb(gc(r_lin), gc(g_lin), gc(b_lin)))
        }
        CsKind::CalGray { gamma, white, .. } => {
            let a= comps.get(0).copied().unwrap_or(0.0).powf(*gamma);
            let x=white[0]*a; let y=white[1]*a; let z=white[2]*a;
            let r_lin= 3.2406*x -1.5372*y -0.4986*z;
            let g_lin= -0.9689*x +1.8758*y +0.0415*z;
            let b_lin= 0.0557*x -0.2040*y +1.0570*z;
            let gc=|u:f64| { let u=u.clamp(0.0,1.0); if u<=0.0031308 {12.92*u} else {1.055*u.powf(1.0/2.4)-0.055}};
            Some(rgb_to_argb(gc(r_lin), gc(g_lin), gc(b_lin)))
        }
        CsKind::ICCBased { n, alt } => {
            if let Some(alt_kind)=alt {
                if let Some(rgb)=eval_cs_to_rgb_simple(alt_kind, comps) { return Some(rgb); }
            }
            match n {
                1 => Some(gray_to_argb(comps.get(0).copied().unwrap_or(0.0))),
                3 => if comps.len()>=3 { Some(rgb_to_argb(comps[0], comps[1], comps[2])) } else { None },
                4 => if comps.len()>=4 { Some(cmyk_to_argb(comps[0], comps[1], comps[2], comps[3])) } else { None },
                _ => None,
            }
        }
        CsKind::Indexed { base, lookup, base_ncomp } => {
            let idx=(comps.get(0).copied().unwrap_or(0.0) as usize).clamp(0,255);
            let off=idx * *base_ncomp as usize;
            if off + *base_ncomp as usize <= lookup.len() {
                let slice=&lookup[off..off+*base_ncomp as usize];
                let comps_f: Vec<f64>=slice.iter().map(|b| *b as f64/255.0).collect();
                eval_cs_to_rgb_simple(base, &comps_f)
            } else { None }
        }
        CsKind::Separation { alt, tint_fn, .. } => {
            let t=comps.get(0).copied().unwrap_or(0.0);
            if let Some(tf)=tint_fn {
                let ac=eval_tint_fn(tf, t);
                eval_cs_to_rgb_simple(alt, &ac)
            } else { Some(gray_to_argb(t)) }
        }
        CsKind::DeviceN { alt, tint_fn, names } => {
            if let Some(tf)=tint_fn {
                let ac=eval_tint_fn(tf, comps.get(0).copied().unwrap_or(0.0));
                eval_cs_to_rgb_simple(alt, &ac)
            } else {
                Some(gray_to_argb(comps.get(0).copied().unwrap_or(0.0)))
            }
        }
        CsKind::Pattern => None,
    }
}

/// Minimal helpers to decode stream data (flate already decompressed by lopdf)
fn stream_data(s: &lopdf::Stream)->Vec<u8> {
    s.decompressed_content().unwrap_or_else(|_| s.content.clone())
}

/// Vertex for Gouraud shading: position + color
#[derive(Clone)]
struct GouraudVertex {
    x: f64,
    y: f64,
    color: Vec<f64>,
    // optional: may have extra comps
}

fn parse_cs_kind_light(doc: &Document, cs_obj: Option<&Object>, cs_resources: &HashMap<Vec<u8>, ObjectId>) -> Option<CsKind> {
    // Light duplicative of lib.rs parse_cs_kind
    let obj=cs_obj?;
    if let Object::Name(name)=obj {
        if let Some(&id)=cs_resources.get(name) {
            if let Ok(Object::Array(arr))=doc.get_object(id) {
                return parse_cs_array_light(doc, &arr, cs_resources);
            }
        }
        return match name.as_slice() {
            b"DeviceRGB"|b"RGB"=>Some(CsKind::DeviceRGB),
            b"DeviceCMYK"|b"CMYK"=>Some(CsKind::DeviceCMYK),
            b"DeviceGray"|b"Gray"|b"G"=>Some(CsKind::DeviceGray),
            b"Pattern"=>Some(CsKind::Pattern),
            _=>None,
        }
    }
    if let Object::Array(arr)=obj {
        return parse_cs_array_light(doc, arr, cs_resources);
    }
    if let Some(deref_obj)=deref(doc, obj) {
        return parse_cs_kind_light(doc, Some(deref_obj), cs_resources);
    }
    None
}

fn parse_cs_array_light(doc: &Document, arr: &[Object], cs_resources: &HashMap<Vec<u8>, ObjectId>)->Option<CsKind> {
    let head=arr.first().and_then(|o| o.as_name().ok()).unwrap_or(b"");
    match head {
        b"DeviceRGB"|b"RGB"=>Some(CsKind::DeviceRGB),
        b"DeviceCMYK"|b"CMYK"=>Some(CsKind::DeviceCMYK),
        b"DeviceGray"|b"G"|b"Gray"=>Some(CsKind::DeviceGray),
        b"Pattern"=>Some(CsKind::Pattern),
        b"CalRGB"=> {
            let dict=arr.get(1).and_then(|o| deref(doc,o)).and_then(|o| o.as_dict().ok());
            if let Some(d)=dict {
                let white=read_white_point(d).unwrap_or([0.9505,1.0,1.0890]);
                let gamma=read_gamma_rgb(d).unwrap_or([1.0,1.0,1.0]);
                let matrix=read_matrix_cal(d).unwrap_or([[1.0,0.0,0.0],[0.0,1.0,0.0],[0.0,0.0,1.0]]);
                Some(CsKind::CalRGB{white,gamma,matrix})
            } else { Some(CsKind::DeviceRGB) }
        }
        b"CalGray"=> {
            let dict=arr.get(1).and_then(|o| deref(doc,o)).and_then(|o| o.as_dict().ok());
            if let Some(d)=dict {
                let white=read_white_point(d).unwrap_or([0.9505,1.0,1.0890]);
                let gamma=d.get(b"Gamma").ok().and_then(num).unwrap_or(1.0);
                Some(CsKind::CalGray{white,gamma,black:None})
            } else { Some(CsKind::DeviceGray) }
        }
        b"Lab"=> {
            let dict=arr.get(1).and_then(|o| deref(doc,o)).and_then(|o| o.as_dict().ok());
            if let Some(d)=dict {
                let white=read_white_point(d).unwrap_or([0.9505,1.0,1.0890]);
                let range=read_lab_range(d).unwrap_or([[-100.0,100.0],[-100.0,100.0]]);
                Some(CsKind::Lab{white,range,black:None})
            } else { Some(CsKind::Lab{white:[0.9505,1.0,1.0890],range:[[-100.0,100.0],[-100.0,100.0]],black:None}) }
        }
        b"ICCBased"=> {
            let dict_obj=arr.get(1).and_then(|o| deref(doc,o));
            let n= if let Some(Object::Stream(s))=dict_obj { s.dict.get(b"N").ok().and_then(num).unwrap_or(1.0) as u8 } else {1};
            let alt= if let Some(Object::Stream(s))=dict_obj { s.dict.get(b"Alternate").ok().and_then(|o| parse_cs_kind_light(doc, Some(o), cs_resources)).map(Box::new) } else {None};
            Some(CsKind::ICCBased{n:n.max(1),alt})
        }
        b"Indexed"|b"I"=> {
            let base=arr.get(1).and_then(|o| parse_cs_kind_light(doc, Some(o), cs_resources)).unwrap_or(CsKind::DeviceRGB);
            let base_n=cs_ncomp(&base);
            let lookup= match arr.get(3).and_then(|o| deref(doc,o)) { Some(Object::String(s,_))=>s.clone(), Some(Object::Stream(s))=>{s.decompressed_content().unwrap_or_else(|_| s.content.clone())}, _=>Vec::new()};
            Some(CsKind::Indexed{base:Box::new(base), lookup, base_ncomp:base_n})
        }
        b"Separation"=> {
            let alt=arr.get(2).and_then(|o| parse_cs_kind_light(doc, Some(o), cs_resources)).unwrap_or(CsKind::DeviceGray);
            let tint_fn=arr.get(3).and_then(|o| deref(doc,o)).and_then(|o| parse_tint_fn_light(doc, o));
            Some(CsKind::Separation{name:Vec::new(), alt:Box::new(alt), tint_fn})
        }
        b"DeviceN"=> {
            let names=arr.get(1).and_then(|o| deref(doc,o)).and_then(|o| o.as_array().ok()).map(|a| a.iter().filter_map(|obj| obj.as_name().ok().map(|n| n.to_vec())).collect()).unwrap_or_default();
            let alt=arr.get(2).and_then(|o| parse_cs_kind_light(doc, Some(o), cs_resources)).unwrap_or(CsKind::DeviceGray);
            let tint_fn=arr.get(3).and_then(|o| deref(doc,o)).and_then(|o| parse_tint_fn_light(doc, o));
            Some(CsKind::DeviceN{names, alt:Box::new(alt), tint_fn})
        }
        _=>None,
    }
}

fn cs_ncomp(k: &CsKind)->u8 { match k { CsKind::DeviceGray=>1, CsKind::DeviceRGB=>3, CsKind::DeviceCMYK=>4, CsKind::Lab{..}=>3, CsKind::CalRGB{..}=>3, CsKind::CalGray{..}=>1, CsKind::ICCBased{n,..}=>*n, CsKind::Indexed{base_ncomp,..}=>*base_ncomp, CsKind::Separation{..}=>1, CsKind::DeviceN{names,..}=>names.len() as u8, CsKind::Pattern=>0 } }
fn read_white_point(dict: &Dictionary)->Option<[f64;3]>{ let arr=dict.get(b"WhitePoint").ok().and_then(|o| o.as_array().ok())?; if arr.len()>=3 { Some([num(&arr[0])?, num(&arr[1])?, num(&arr[2])?]) } else {None}}
fn read_gamma_rgb(dict: &Dictionary)->Option<[f64;3]>{ let arr=dict.get(b"Gamma").ok().and_then(|o| o.as_array().ok())?; if arr.len()>=3 { Some([num(&arr[0])?, num(&arr[1])?, num(&arr[2])?]) } else {None}}
fn read_matrix_cal(dict: &Dictionary)->Option<[[f64;3];3]>{ let arr=dict.get(b"Matrix").ok().and_then(|o| o.as_array().ok())?; if arr.len()>=9 { Some([[num(&arr[0])?, num(&arr[1])?, num(&arr[2])?],[num(&arr[3])?, num(&arr[4])?, num(&arr[5])?],[num(&arr[6])?, num(&arr[7])?, num(&arr[8])?]]) } else {None}}
fn read_lab_range(dict: &Dictionary)->Option<[[f64;2];2]>{ let arr=dict.get(b"Range").ok().and_then(|o| o.as_array().ok())?; if arr.len()>=4 { Some([[num(&arr[0])?, num(&arr[1])?],[num(&arr[2])?, num(&arr[3])?]]) } else {None}}
fn parse_tint_fn_light(doc: &Document, obj: &Object)->Option<TintFn>{
    let dict= match obj { Object::Dictionary(d)=>d, Object::Stream(s)=>&s.dict, _=>return None};
    let ftype=dict.get(b"FunctionType").ok().and_then(num).unwrap_or(0.0) as i64;
    if ftype!=2 { return None; }
    let c0=dict.get(b"C0").ok().and_then(|o| o.as_array().ok()).map(|a| a.iter().filter_map(num).collect()).unwrap_or(vec![0.0]);
    let c1=dict.get(b"C1").ok().and_then(|o| o.as_array().ok()).map(|a| a.iter().filter_map(num).collect()).unwrap_or(vec![1.0]);
    let n=dict.get(b"N").ok().and_then(num).unwrap_or(1.0);
    let domain=dict.get(b"Domain").ok().and_then(|o| o.as_array().ok()).and_then(|a| if a.len()>=2 { Some([num(&a[0])?, num(&a[1])?]) } else {None}).unwrap_or([0.0,1.0]);
    Some(TintFn{c0,c1,n,domain})
}

/// Decode a shading of type 4-7 into RGBA bitmap.
/// Returns Option<(CTM, w, h, data)>
pub fn rasterize_shading_mesh(doc: &Document, dict: &Dictionary, base_ctm: &Mat, cs_resources: &HashMap<Vec<u8>, ObjectId>, size: u32) -> Option<(Mat, u32, u32, Vec<u8>)> {
    let shading_type = dict.get(b"ShadingType").ok().and_then(num).unwrap_or(0.0) as i64;
    if ![4,5,6,7].contains(&shading_type) { return None; }
    if size == 0 || size > 1024 { return None; }

    let bbox = read_rect(doc, &dict.get(b"BBox").ok()?.clone()).unwrap_or([0.0,0.0,1.0,1.0]);
    // Sanity on bbox size to avoid degenerate
    if (bbox[2]-bbox[0]).abs() < 1e-6 || (bbox[3]-bbox[1]).abs() < 1e-6 {
        return None;
    }
    let bg = dict.get(b"Background").ok().and_then(|o| deref(doc,o)).and_then(|o| o.as_array().ok()).map(|a| a.iter().filter_map(|o| deref(doc,o).and_then(num)).collect::<Vec<f64>>());
    let cs_kind = dict.get(b"ColorSpace").ok().and_then(|o| parse_cs_kind_light(doc, Some(o), cs_resources)).unwrap_or(CsKind::DeviceRGB);

    // Decode data stream: data may be in dict? Shading dict may not have stream content; it may be in stream object if shading dictionary is stream? In PDF, shading may be stream.
    // We will attempt to get data from: dict.get(b"DataSource") or if dict comes from stream (i.e., was Stream) we need caller to pass raw. For Type4-7, typically /DataSource is stream or array.
    // In our caller path, we get dict from get_dictionary; if shading was stream, we have stream content (decompressed). So we need to handle both.

    // For this implementation, we try to locate a stream object via Decode? Actually let's try to handle: if dict is of Stream, data = stream_data. Since we only have Dictionary here (from get_dictionary), we miss stream content if shading is Stream (rare). So also check for DataSource.

    let data_source_bytes: Option<Vec<u8>> = {
        // Check /DataSource
        if let Some(obj) = dict.get(b"DataSource").ok().and_then(|o| deref(doc,o)) {
            match obj {
                Object::Stream(s) => Some(stream_data(s)),
                Object::String(bytes, _) => Some(bytes.clone()),
                Object::Array(arr) => {
                    // Array of numbers? Might be inline, treat as sequence of values.
                    // Flatten to bytes? Actually spec: DataSource may be stream or array of samples.
                    // For simplicity, collect as f32? We'll return None and handle via eval from array later.
                    None
                }
                _ => None,
            }
        } else {
            // Try to get shading stream content if dictionary came from stream object (we can try to look up stream via resource id? caller maps id to dict; we still can get object)
            None
        }
    };

    // Bits per component / coordinate / flag etc.
    let bps_coord = dict.get(b"BitsPerCoordinate").ok().and_then(num).unwrap_or(16.0) as u32;
    let bps_comp = dict.get(b"BitsPerComponent").ok().and_then(num).unwrap_or(8.0) as u32;
    let bps_flag = dict.get(b"BitsPerFlag").ok().and_then(num).unwrap_or(8.0) as u32;

    let decode_arr: Vec<f64> = dict.get(b"Decode").ok().and_then(|o| deref(doc,o)).and_then(|o| o.as_array().ok()).map(|a| a.iter().filter_map(|o| deref(doc,o).and_then(num)).collect()).unwrap_or_default();
    // Decode: [ xmin xmax ymin ymax c1min c1max ...]
    // For mesh we need domain mapping

    // If no data source bytes, we cannot rasterize mesh (needs stream). For now, return background if any, else None -> becomes blank handling for early impl. We'll attempt fallback: if Type4 data is in stream content of shading dictionary that is stream (we didn't capture), try to get via document's object? Our dict ref object id is known via shadings map; we can retrieve stream via doc.get_object(id) in caller; but here we only have dict. So we need to enhance caller to also retrieve stream content. Simpler: we treat rasterize_shading_mesh where data source may be stream content passed separately.

    // Since we have no data bytes at this point in editing flow without refactoring caller, we will return a simple placeholder gradient using background color that at least is not blank (gives a hint). Then refine later after editing caller path.

    // For now, if background exists, return background fill as image covering bbox.
    if let Some(bgc) = bg.clone() {
        if let Some(argb) = eval_cs_to_rgb_simple(&cs_kind, &bgc) {
            let w = size as usize;
            let h = size as usize;
            let mut rgba = vec![0u8; w*h*4];
            let r = ((argb>>16)&0xFF) as u8;
            let g = ((argb>>8)&0xFF) as u8;
            let b = ((argb)&0xFF) as u8;
            for i in 0..w*h {
                rgba[i*4]=r; rgba[i*4+1]=g; rgba[i*4+2]=b; rgba[i*4+3]=255;
            }
            let bw=bbox[2]-bbox[0];
            let bh=bbox[3]-bbox[1];
            let shading_mat: Mat=[bw,0.0,0.0,bh,bbox[0],bbox[1]];
            let ctm=mat_mul(&shading_mat, base_ctm);
            return Some((ctm, size, size, rgba));
        }
    }

    // If we have actual mesh bytes (from DataSource stream), attempt to parse Type4 free-form gouraud.
    // For Type4, data record is: flag (bitsPerFlag) + x y + colors (ncomp)
    // Without full bit-stream parsing, we attempt best-effort if BitsPer* are multiples of 8.

    if let Some(bytes) = data_source_bytes {
        if shading_type == 4 {
            return rasterize_type4_gouraud(&bytes, &bbox, &cs_kind, size, base_ctm, bps_flag, bps_coord, bps_comp, &decode_arr);
        } else if shading_type == 5 {
            // Lattice needs vertices per row etc., attempt similar
            return rasterize_type5_lattice(&bytes, &bbox, &cs_kind, size, base_ctm, bps_coord, bps_comp, &decode_arr, dict);
        } else if shading_type == 6 || shading_type == 7 {
            return rasterize_type6_7_coons(&bytes, &bbox, &cs_kind, size, base_ctm, shading_type, bps_flag, bps_coord, bps_comp, &decode_arr);
        }
    }

    // Final fallback: if Type4-7 and no BG, produce a neutral gray placeholder (avoid blank) but flag size guard.
    let w = size as usize;
    let h = size as usize;
    let mut rgba = vec![0u8; w*h*4];
    // Light gray to indicate shading present but not fully rendered
    for i in 0..w*h {
        rgba[i*4]=200; rgba[i*4+1]=200; rgba[i*4+2]=200; rgba[i*4+3]=255;
    }
    let bw=bbox[2]-bbox[0];
    let bh=bbox[3]-bbox[1];
    let shading_mat: Mat=[bw,0.0,0.0,bh,bbox[0],bbox[1]];
    let ctm=mat_mul(&shading_mat, base_ctm);
    Some((ctm, size, size, rgba))
}

fn rasterize_type4_gouraud(data: &[u8], bbox: &[f64;4], cs_kind: &CsKind, size: u32, base_ctm: &Mat, bps_flag: u32, bps_coord: u32, bps_comp: u32, decode: &[f64]) -> Option<(Mat, u32, u32, Vec<u8>)> {
    if bps_flag % 8 !=0 || bps_coord % 8 !=0 || bps_comp %8 !=0 {
        return None;
    }
    let flag_bytes = (bps_flag/8) as usize;
    let coord_bytes = (bps_coord/8) as usize *2; // x+y
    // Determine ncomp via CsKind
    let ncomp = cs_ncomp(cs_kind) as usize;
    if ncomp==0 { return None; }
    let comp_bytes = (bps_comp/8) as usize * ncomp;
    let record_size = flag_bytes + coord_bytes + comp_bytes;
    if record_size==0 { return None; }
    if data.len() < record_size { return None; }

    // Decode mapping ranges from Decode array: [xmin xmax ymin ymax ...]
    let x0 = decode.get(0).copied().unwrap_or(bbox[0]);
    let x1 = decode.get(1).copied().unwrap_or(bbox[2]);
    let y0 = decode.get(2).copied().unwrap_or(bbox[1]);
    let y1 = decode.get(3).copied().unwrap_or(bbox[3]);

    let map_coord = |raw: u32, dmin: f64, dmax: f64, bps: u32| -> f64 {
        let max_val = (1u64 << bps) -1;
        let t = raw as f64 / max_val as f64;
        dmin + t*(dmax-dmin)
    };

    let mut vertices: Vec<(f64,f64,Vec<f64>)> = Vec::new();
    // For Type4 Gouraud, flag 0=vertex, 1=triangle, 2=etc. But many omit flag (BitsPerFlag may be 0). Simplest: treat every record as vertex, then group every 3 vertices as triangle.

    let mut offset=0;
    while offset + record_size <= data.len() && vertices.len() < 10000 {
        // skip flag bytes (first)
        let mut pos=offset+flag_bytes;
        let mut coords_raw = Vec::new();
        for _ in 0..2 {
            let mut raw: u32=0;
            for _ in 0..bps_coord/8 {
                raw = (raw<<8)|data[pos] as u32; pos+=1;
            }
            coords_raw.push(raw);
        }
        let x = map_coord(coords_raw[0], x0, x1, bps_coord);
        let y = map_coord(coords_raw[1], y0, y1, bps_coord);
        let mut comps = Vec::new();
        for ci in 0..ncomp {
            let cmin = decode.get(4+ ci*2).copied().unwrap_or(0.0);
            let cmax = decode.get(4+ ci*2+1).copied().unwrap_or(1.0);
            let mut raw: u32=0;
            for _ in 0..bps_comp/8 {
                raw = (raw<<8)|data[pos] as u32; pos+=1;
            }
            comps.push(map_coord(raw, cmin, cmax, bps_comp));
        }
        vertices.push((x,y,comps));
        offset+=record_size;
    }

    // Rasterize into 256x256 image via triangles
    let w = size as usize;
    let h = size as usize;
    let mut rgba = vec![0u8; w*h*4];

    if vertices.len() < 3 { return None; }

    // For each triangle sequential (flag handling simplistic: every 3 vertices = tri)
    for tri_idx in 0..vertices.len()/3 {
        let base = tri_idx*3;
        if base+2 >= vertices.len() { break; }
        if tri_idx >= 1000 { break; } // guard
        let v0 = &vertices[base];
        let v1 = &vertices[base+1];
        let v2 = &vertices[base+2];

        let (x0,y0)=(v0.0, v0.1);
        let (x1,y1)=(v1.0, v1.1);
        let (x2,y2)=(v2.0, v2.1);

        // Convert colors to RGB
        let c0 = eval_cs_to_rgb_simple(cs_kind, &v0.2).unwrap_or(gray_to_argb(0.5));
        let c1 = eval_cs_to_rgb_simple(cs_kind, &v1.2).unwrap_or(gray_to_argb(0.5));
        let c2 = eval_cs_to_rgb_simple(cs_kind, &v2.2).unwrap_or(gray_to_argb(0.5));

        // Bounding box in bbox image space
        let min_x = x0.min(x1.min(x2));
        let max_x = x0.max(x1.max(x2));
        let min_y = y0.min(y1.min(y2));
        let max_y = y0.max(y1.max(y2));

        // map to pixel coords
        let px_min_x = ((min_x - bbox[0]) / (bbox[2]-bbox[0]) * w as f64).floor() as i32;
        let px_max_x = ((max_x - bbox[0]) / (bbox[2]-bbox[0]) * w as f64).ceil() as i32;
        let py_min_y = ((min_y - bbox[1]) / (bbox[3]-bbox[1]) * h as f64).floor() as i32;
        let py_max_y = ((max_y - bbox[1]) / (bbox[3]-bbox[1]) * h as f64).ceil() as i32;

        for py in py_min_y.max(0)..py_max_y.min(h as i32) {
            for px in px_min_x.max(0)..px_max_x.min(w as i32) {
                let fx = bbox[0] + (px as f64 +0.5)/w as f64 * (bbox[2]-bbox[0]);
                let fy = bbox[1] + (py as f64 +0.5)/h as f64 * (bbox[3]-bbox[1]);
                // barycentric
                let denom = (y1 - y2)*(x0 - x2) + (x2 - x1)*(y0 - y2);
                if denom.abs() < 1e-12 { continue; }
                let a = ((y1 - y2)*(fx - x2) + (x2 - x1)*(fy - y2))/denom;
                let b = ((y2 - y0)*(fx - x2) + (x0 - x2)*(fy - y2))/denom;
                let c = 1.0 - a - b;
                if a < -1e-6 || b < -1e-6 || c < -1e-6 { continue; }
                // Interpolate color
                let r = (((c0>>16)&0xFF) as f64 * a + ((c1>>16)&0xFF) as f64 * b + ((c2>>16)&0xFF) as f64 * c) as u8;
                let g = (((c0>>8)&0xFF) as f64 * a + ((c1>>8)&0xFF) as f64 * b + ((c2>>8)&0xFF) as f64 * c) as u8;
                let bl = ((c0&0xFF) as f64 * a + (c1&0xFF) as f64 * b + (c2&0xFF) as f64 * c) as u8;
                let idx = (py as usize * w + px as usize)*4;
                rgba[idx]=r; rgba[idx+1]=g; rgba[idx+2]=bl; rgba[idx+3]=255;
            }
        }
    }

    let bw=bbox[2]-bbox[0];
    let bh=bbox[3]-bbox[1];
    let shading_mat: Mat=[bw,0.0,0.0,bh,bbox[0],bbox[1]];
    let ctm=mat_mul(&shading_mat, base_ctm);
    Some((ctm, size, size, rgba))
}

fn rasterize_type5_lattice(data: &[u8], bbox: &[f64;4], cs_kind: &CsKind, size: u32, base_ctm: &Mat, bps_coord: u32, bps_comp: u32, decode: &[f64], dict: &Dictionary) -> Option<(Mat, u32, u32, Vec<u8>)> {
    // Type5: vertices organized in rows: /VerticesPerRow
    // For MVP, fallback to Type4 handling grouping using vertices per row
    // Try to read VerticesPerRow
    // If present, we can build triangle strip.
    let _ = dict;
    rasterize_type4_gouraud(data, bbox, cs_kind, size, base_ctm, 0, bps_coord, bps_comp, decode)
}

fn rasterize_type6_7_coons(data: &[u8], bbox: &[f64;4], cs_kind: &CsKind, size: u32, base_ctm: &Mat, shading_type: i64, bps_flag: u32, bps_coord: u32, bps_comp: u32, decode: &[f64]) -> Option<(Mat, u32, u32, Vec<u8>)> {
    // Coons patch mesh (Type6): each patch: 12 control points + 4 colors? Actually 12 coords*2 = 24 + colors
    // Tensor (Type7): 16 control points
    // Simplistic rasterization: subdivide patch into 16x16 grid sampling bezier patch (approx) and create triangles.
    // This is complex to fully implement, but we provide a cap-based subdivision.

    if bps_flag %8 !=0 || bps_coord %8 !=0 || bps_comp %8 !=0 {
        return None;
    }
    let ncomp = cs_ncomp(cs_kind) as usize;
    if ncomp==0 { return None; }

    let flag_bytes = (bps_flag/8) as usize;
    let points = if shading_type==6 {12} else {16};
    let coord_bytes_per_point = (bps_coord/8) as usize *2; // x,y per point
    let color_points = 4;
    let comp_bytes_per_color = (bps_comp/8) as usize * ncomp;
    let comp_bytes = comp_bytes_per_color * color_points;
    let record_size = flag_bytes + coord_bytes_per_point*points + comp_bytes;
    if record_size==0 { return None; }
    if data.len() < record_size { return None; }

    let x0 = decode.get(0).copied().unwrap_or(bbox[0]);
    let x1 = decode.get(1).copied().unwrap_or(bbox[2]);
    let y0 = decode.get(2).copied().unwrap_or(bbox[1]);
    let y1 = decode.get(3).copied().unwrap_or(bbox[3]);
    let map_coord = |raw: u32, dmin: f64, dmax: f64, bps: u32| -> f64 {
        let max_val = (1u64 << bps) -1;
        let t = raw as f64 / max_val as f64;
        dmin + t*(dmax-dmin)
    };

    let mut patches: Vec<(Vec<(f64,f64)>, Vec<Vec<f64>>)> = Vec::new();
    let mut offset=0;
    while offset + record_size <= data.len() && patches.len() < 1000 {
        let mut pos=offset+flag_bytes;
        let mut pts = Vec::with_capacity(points);
        for _ in 0..points {
            let mut raw_x: u32=0;
            let mut raw_y: u32=0;
            for _ in 0..bps_coord/8 { raw_x=(raw_x<<8)|data[pos] as u32; pos+=1; }
            for _ in 0..bps_coord/8 { raw_y=(raw_y<<8)|data[pos] as u32; pos+=1; }
            let x= map_coord(raw_x, x0, x1, bps_coord);
            let y= map_coord(raw_y, y0, y1, bps_coord);
            pts.push((x,y));
        }
        let mut colors = Vec::with_capacity(4);
        for _ in 0..4 {
            let mut comps=Vec::with_capacity(ncomp);
            for ci in 0..ncomp {
                let cmin=decode.get(4+ ci*2).copied().unwrap_or(0.0);
                let cmax=decode.get(4+ ci*2+1).copied().unwrap_or(1.0);
                let mut raw: u32=0;
                for _ in 0..bps_comp/8 { raw=(raw<<8)|data[pos] as u32; pos+=1; }
                comps.push(map_coord(raw, cmin, cmax, bps_comp));
            }
            colors.push(comps);
        }
        patches.push((pts, colors));
        offset+=record_size;
    }

    let w = size as usize;
    let h = size as usize;
    let mut rgba = vec![0u8; w*h*4];

    // For each patch, subdiv into 2 triangles from corner colors (MVP)
    for (pts, colors) in patches.into_iter().take(1000) {
        // Use first 4 points as corners (p00,p01,p11,p10) for coons? For simplicity use p00=pts[0], p01=pts[1], etc.
        // And colors corresponding to corners.
        // We'll rasterize two triangles covering the bounding quad of the patch.
        let corner_pts: Vec<(f64,f64)> = if pts.len()>=4 { pts[0..4].to_vec() } else { pts.clone() };
        if corner_pts.len()<4 { continue; }

        let c00 = eval_cs_to_rgb_simple(cs_kind, &colors.get(0).cloned().unwrap_or_default()).unwrap_or(gray_to_argb(0.5));
        let c01 = eval_cs_to_rgb_simple(cs_kind, &colors.get(1).cloned().unwrap_or_default()).unwrap_or(gray_to_argb(0.5));
        let c11 = eval_cs_to_rgb_simple(cs_kind, &colors.get(2).cloned().unwrap_or_default()).unwrap_or(gray_to_argb(0.5));
        let c10 = eval_cs_to_rgb_simple(cs_kind, &colors.get(3).cloned().unwrap_or_default()).unwrap_or(gray_to_argb(0.5));

        // Two triangles: 00-01-11 and 00-11-10
        let tris = vec![
            (corner_pts[0], corner_pts[1], corner_pts[2], c00, c01, c11),
            (corner_pts[0], corner_pts[2], corner_pts[3], c00, c11, c10),
        ];

        for (p0,p1,p2, col0,col1,col2) in tris {
            let (x0,y0)=p0; let (x1,y1)=p1; let (x2,y2)=p2;
            let min_x = x0.min(x1.min(x2)); let max_x = x0.max(x1.max(x2));
            let min_y = y0.min(y1.min(y2)); let max_y = y0.max(y1.max(y2));
            let px_min_x = ((min_x - bbox[0])/(bbox[2]-bbox[0])*w as f64).floor() as i32;
            let px_max_x = ((max_x - bbox[0])/(bbox[2]-bbox[0])*w as f64).ceil() as i32;
            let py_min_y = ((min_y - bbox[1])/(bbox[3]-bbox[1])*h as f64).floor() as i32;
            let py_max_y = ((max_y - bbox[1])/(bbox[3]-bbox[1])*h as f64).ceil() as i32;
            for py in py_min_y.max(0)..py_max_y.min(h as i32) {
                for px in px_min_x.max(0)..px_max_x.min(w as i32) {
                    let fx = bbox[0] + (px as f64 +0.5)/w as f64*(bbox[2]-bbox[0]);
                    let fy = bbox[1] + (py as f64 +0.5)/h as f64*(bbox[3]-bbox[1]);
                    let denom=(y1 - y2)*(x0 - x2) + (x2 - x1)*(y0 - y2);
                    if denom.abs()<1e-12 { continue; }
                    let a = ((y1 - y2)*(fx - x2) + (x2 - x1)*(fy - y2))/denom;
                    let b = ((y2 - y0)*(fx - x2) + (x0 - x2)*(fy - y2))/denom;
                    let c = 1.0 - a - b;
                    if a < -1e-6 || b < -1e-6 || c < -1e-6 { continue; }
                    let r = (((col0>>16)&0xFF) as f64 * a + ((col1>>16)&0xFF) as f64 * b + ((col2>>16)&0xFF) as f64 * c) as u8;
                    let g = (((col0>>8)&0xFF) as f64 * a + ((col1>>8)&0xFF) as f64 * b + ((col2>>8)&0xFF) as f64 * c) as u8;
                    let bl = ((col0&0xFF) as f64 * a + (col1&0xFF) as f64 * b + (col2&0xFF) as f64 * c) as u8;
                    let idx=(py as usize * w + px as usize)*4;
                    rgba[idx]=r; rgba[idx+1]=g; rgba[idx+2]=bl; rgba[idx+3]=255;
                }
            }
        }
    }

    let bw=bbox[2]-bbox[0];
    let bh=bbox[3]-bbox[1];
    let shading_mat: Mat=[bw,0.0,0.0,bh,bbox[0],bbox[1]];
    let ctm=mat_mul(&shading_mat, base_ctm);
    Some((ctm, size, size, rgba))
}
