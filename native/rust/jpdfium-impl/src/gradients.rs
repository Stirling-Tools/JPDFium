//! Page shading extraction for the PDF -> SVG exporter.
//! PDFium exposes no shading read side, so the page's own object graph is walked with lopdf.

use lopdf::content::Content;
use lopdf::{Dictionary, Document, Object, ObjectId};

use std::collections::HashMap;
use std::io::{Cursor, Read};

/// Colour stops sampled per gradient. 32 is smooth for the two-colour exponential functions
/// that dominate real documents.
const STOPS: usize = 32;

/// Never emit more than this many gradients for one page.
const MAX_GRADIENTS: usize = 4096;

/// 2x3 affine matrix (PDF `cm` order): `x' = a x + c y + e`, `y' = b x + d y + f`.
#[derive(Clone, Copy)]
struct Mat {
    a: f32,
    b: f32,
    c: f32,
    d: f32,
    e: f32,
    f: f32,
}

impl Mat {
    const IDENTITY: Mat = Mat { a: 1.0, b: 0.0, c: 0.0, d: 1.0, e: 0.0, f: 0.0 };

    fn apply(&self, x: f32, y: f32) -> (f32, f32) {
        (self.a * x + self.c * y + self.e, self.b * x + self.d * y + self.f)
    }

    /// `self * other`: apply `other` first, then `self`.
    fn mul(&self, other: &Mat) -> Mat {
        Mat {
            a: self.a * other.a + self.c * other.b,
            b: self.b * other.a + self.d * other.b,
            c: self.a * other.c + self.c * other.d,
            d: self.b * other.c + self.d * other.d,
            e: self.a * other.e + self.c * other.f + self.e,
            f: self.b * other.e + self.d * other.f + self.f,
        }
    }

    /// Device-space bounding box of a local-space rectangle.
    fn bounds_of(&self, rect: [f32; 4]) -> [f32; 4] {
        let corners = [
            self.apply(rect[0], rect[1]),
            self.apply(rect[2], rect[1]),
            self.apply(rect[2], rect[3]),
            self.apply(rect[0], rect[3]),
        ];
        let mut box_ = [f32::MAX, f32::MAX, f32::MIN, f32::MIN];
        for (x, y) in corners {
            box_[0] = box_[0].min(x);
            box_[1] = box_[1].min(y);
            box_[2] = box_[2].max(x);
            box_[3] = box_[3].max(y);
        }
        box_
    }
}

/// A shading resolved to device space and pre-sampled colour stops.
struct Gradient {
    /// 2 = axial (linear), 3 = radial.
    kind: u8,
    extend_start: bool,
    extend_end: bool,
    /// Axial: `x0 y0 x1 y1 0 0`. Radial: `x0 y0 r0 x1 y1 r1`.
    coords: [f32; 6],
    /// Device-space corners of the painted extent, used to match the gradient
    /// to the page object that actually uses it (pattern space maps the unit
    quad: [[f32; 2]; 4],
    stops: Vec<[f32; 3]>,
}

fn clamp01(v: f32) -> f32 {
    if v.is_nan() { 0.0 } else { v.clamp(0.0, 1.0) }
}

fn as_dict_like(object: &Object) -> Option<&Dictionary> {
    match object {
        Object::Dictionary(dict) => Some(dict),
        Object::Stream(stream) => Some(&stream.dict),
        _ => None,
    }
}

// ------------------------------------------------------------------ colours

/// A device-independent-enough colour space for gradient stops.
enum Cs {
    Gray,
    Rgb,
    Cmyk,
    Indexed { base: Box<Cs>, hival: usize, lookup: Vec<u8> },
}

impl Cs {
    fn components(&self) -> usize {
        match self {
            Cs::Gray => 1,
            Cs::Rgb => 3,
            Cs::Cmyk => 4,
            Cs::Indexed { base, .. } => base.components(),
        }
    }

    fn to_rgb(&self, values: &[f32]) -> [f32; 3] {
        let at = |i: usize| clamp01(values.get(i).copied().unwrap_or(0.0));
        match self {
            Cs::Gray => {
                let g = at(0);
                [g, g, g]
            }
            Cs::Rgb => [at(0), at(1), at(2)],
            Cs::Cmyk => {
                let (c, m, y, k) = (at(0), at(1), at(2), at(3));
                [(1.0 - c) * (1.0 - k), (1.0 - m) * (1.0 - k), (1.0 - y) * (1.0 - k)]
            }
            Cs::Indexed { base, hival, lookup } => {
                let n = base.components();
                let index = (at(0) * (*hival as f32)).round().max(0.0) as usize;
                let index = index.min(*hival);
                let start = index * n;
                if n == 0 || start + n > lookup.len() {
                    return [0.0, 0.0, 0.0];
                }
                let comps: Vec<f32> =
                    lookup[start..start + n].iter().map(|b| *b as f32 / 255.0).collect();
                base.to_rgb(&comps)
            }
        }
    }
}

fn resolve_colour_space(doc: &Document, object: &Object) -> Option<Cs> {
    let (_, object) = doc.dereference(object).ok()?;
    if let Ok(name) = object.as_name() {
        return match name {
            b"DeviceGray" | b"CalGray" | b"G" => Some(Cs::Gray),
            b"DeviceRGB" | b"CalRGB" | b"RGB" => Some(Cs::Rgb),
            b"DeviceCMYK" | b"CMYK" => Some(Cs::Cmyk),
            _ => None,
        };
    }
    let array = object.as_array().ok()?;
    let head = array.first()?.as_name().ok()?;
    match head {
        b"CalGray" => Some(Cs::Gray),
        b"CalRGB" => Some(Cs::Rgb),
        b"ICCBased" => {
            let stream = doc.dereference(array.get(1)?).ok()?.1;
            let dict = as_dict_like(stream)?;
            let n = dict.get(b"N").ok()?.as_i64().ok()?;
            match n {
                1 => Some(Cs::Gray),
                3 => Some(Cs::Rgb),
                4 => Some(Cs::Cmyk),
                _ => None,
            }
        }
        b"Indexed" | b"I" => {
            let base = resolve_colour_space(doc, array.get(1)?)?;
            let hival = doc.dereference(array.get(2)?).ok()?.1.as_i64().ok()? as usize;
            let lookup_object = doc.dereference(array.get(3)?).ok()?.1;
            let lookup = match lookup_object {
                Object::String(bytes, _) => bytes.clone(),
                Object::Stream(stream) => stream.get_plain_content().ok()?,
                _ => return None,
            };
            Some(Cs::Indexed { base: Box::new(base), hival, lookup })
        }
        _ => None,
    }
}

// --------------------------------------------------------------- functions

/// A PDF function (`/Function`) reduced to what a gradient needs.
enum Func {
    Exponential {
        c0: Vec<f32>,
        c1: Vec<f32>,
        n: f32,
        domain: (f32, f32),
        out: usize,
    },
    Stitching {
        funcs: Vec<Func>,
        bounds: Vec<f32>,
        encode: Vec<f32>,
        domain: (f32, f32),
        out: usize,
    },
    Sampled {
        samples: Vec<f32>,
        sample_count: usize,
        domain: (f32, f32),
        encode: (f32, f32),
        decode: Vec<(f32, f32)>,
        out: usize,
    },
}

impl Func {
    fn eval(&self, t: f32) -> Vec<f32> {
        match self {
            Func::Exponential { c0, c1, n, domain, out } => {
                let (d0, d1) = *domain;
                let x = if d1 > d0 { clamp01((t - d0) / (d1 - d0)) } else { 0.0 };
                let scaled = if *n == 1.0 { x } else { x.powf(*n) };
                (0..*out)
                    .map(|i| {
                        let a = c0.get(i).copied().unwrap_or(0.0);
                        let b = c1.get(i).copied().unwrap_or(0.0);
                        clamp01(a + (b - a) * scaled)
                    })
                    .collect()
            }
            Func::Stitching { funcs, bounds, encode, domain, out } => {
                let (d0, d1) = *domain;
                let x = t.clamp(d0.min(d1), d0.max(d1));
                let mut index = funcs.len().saturating_sub(1);
                for (i, bound) in bounds.iter().enumerate() {
                    if x < *bound {
                        index = i;
                        break;
                    }
                }
                let lo = if index == 0 { d0 } else { bounds[index - 1] };
                let hi = if index >= bounds.len() { d1 } else { bounds[index] };
                let local = if hi > lo { clamp01((x - lo) / (hi - lo)) } else { 0.0 };
                let e0 = encode.get(2 * index).copied().unwrap_or(0.0);
                let e1 = encode.get(2 * index + 1).copied().unwrap_or(1.0);
                match funcs.get(index) {
                    Some(func) => func.eval(e0 + local * (e1 - e0)),
                    None => vec![0.0; *out],
                }
            }
            Func::Sampled { samples, sample_count, domain, encode, decode, out } => {
                let (d0, d1) = *domain;
                let x = if d1 > d0 { clamp01((t - d0) / (d1 - d0)) } else { 0.0 };
                let (e0, e1) = *encode;
                let position = (e0 + x * (e1 - e0)).clamp(0.0, 1.0) * (*sample_count as f32 - 1.0);
                let low = position.floor() as usize;
                let high = (low + 1).min(sample_count.saturating_sub(1));
                let frac = position - low as f32;
                (0..*out)
                    .map(|c| {
                        let a = samples.get(low * *out + c).copied().unwrap_or(0.0);
                        let b = samples.get(high * *out + c).copied().unwrap_or(0.0);
                        let raw = a + (b - a) * frac;
                        let (lo, hi) = decode.get(c).copied().unwrap_or((0.0, 1.0));
                        clamp01(lo + raw * (hi - lo))
                    })
                    .collect()
            }
        }
    }
}

fn number_array(doc: &Document, object: &Object) -> Option<Vec<f32>> {
    let (_, object) = doc.dereference(object).ok()?;
    object.as_array().ok()?.iter().map(|o| o.as_float().ok()).collect()
}

fn read_range(doc: &Document, object: &Object) -> Vec<(f32, f32)> {
    let Some(values) = number_array(doc, object) else {
        return Vec::new();
    };
    values.chunks_exact(2).map(|c| (c[0], c[1])).collect()
}

fn resolve_function(doc: &Document, object: &Object) -> Option<Func> {
    let (_, object) = doc.dereference(object).ok()?;

    // A shading `/Function` may be an array of single-output functions, one per
    // colour component; that is handled by the caller, so only a single
    let dict = as_dict_like(object)?;
    let domain = *read_range(doc, dict.get(b"Domain").ok()?).first()?;
    let range = match dict.get(b"Range") {
        Ok(value) => read_range(doc, value),
        Err(_) => Vec::new(),
    };
    let function_type = dict.get(b"FunctionType").ok()?.as_i64().ok()?;

    match function_type {
        2 => {
            let c0 = number_array(doc, dict.get(b"C0").ok()?)?;
            let c1 = number_array(doc, dict.get(b"C1").ok()?)?;
            let n = dict.get(b"N").ok().and_then(|o| o.as_float().ok()).unwrap_or(1.0);
            let out = c0.len().max(c1.len());
            if out == 0 || out > 8 {
                return None;
            }
            Some(Func::Exponential { c0, c1, n, domain, out })
        }
        3 => {
            let functions =
                doc.dereference(dict.get(b"Functions").ok()?).ok()?.1.as_array().ok()?;
            let mut funcs = Vec::with_capacity(functions.len());
            for function in functions {
                funcs.push(resolve_function(doc, function)?);
            }
            if funcs.is_empty() || funcs.len() > 64 {
                return None;
            }
            let bounds = number_array(doc, dict.get(b"Bounds").ok()?)?;
            let encode = match dict.get(b"Encode") {
                Ok(value) => number_array(doc, value).unwrap_or_default(),
                Err(_) => Vec::new(),
            };
            let out = funcs[0].output_count();
            Some(Func::Stitching { funcs, bounds, encode, domain, out })
        }
        0 => {
            let stream = object.as_stream().ok()?;
            let size = number_array(doc, dict.get(b"Size").ok()?)?;
            let bits = dict.get(b"BitsPerSample").ok()?.as_i64().ok()?;
            if size.len() != 1 || bits % 8 != 0 || bits < 8 || bits > 32 {
                return None;
            }
            let sample_count = *size.first()? as usize;
            if sample_count == 0 || sample_count > 65536 {
                return None;
            }
            let encode = *read_range(doc, dict.get(b"Encode").ok()?).first()?;
            let decode = match dict.get(b"Decode") {
                Ok(value) => read_range(doc, value),
                Err(_) => range.clone(),
            };
            let out = if decode.is_empty() { return None } else { decode.len() };
            if out == 0 || out > 8 {
                return None;
            }
            let bytes = stream.get_plain_content().ok()?;
            let bytes_per_sample = (bits as usize / 8) * out;
            if bytes_per_sample == 0 || bytes.len() < sample_count * bytes_per_sample {
                return None;
            }
            let mut samples = Vec::with_capacity(sample_count * out);
            for s in 0..sample_count {
                for c in 0..out {
                    let offset = s * bytes_per_sample + c * (bits as usize / 8);
                    let mut value: f32 = 0.0;
                    for b in 0..(bits as usize / 8) {
                        value = value * 256.0 + bytes[offset + b] as f32;
                    }
                    samples.push(value / ((1u64 << bits) as f32 - 1.0));
                }
            }
            Some(Func::Sampled { samples, sample_count, domain, encode, decode, out })
        }
        // FunctionType 4 is a PostScript program; not interpreted here.
        _ => None,
    }
}

impl Func {
    fn output_count(&self) -> usize {
        match self {
            Func::Exponential { out, .. }
            | Func::Stitching { out, .. }
            | Func::Sampled { out, .. } => *out,
        }
    }
}

/// Evaluate a `/Function` entry (single function or per-component array).
fn evaluate_function(
    doc: &Document,
    object: &Object,
    components: usize,
    t: f32,
) -> Option<Vec<f32>> {
    let (_, object) = doc.dereference(object).ok()?;
    if let Ok(array) = object.as_array() {
        let mut values = Vec::with_capacity(array.len());
        for (i, entry) in array.iter().enumerate() {
            let _ = i;
            let function = resolve_function(doc, entry)?;
            values.push(function.eval(t).first().copied().unwrap_or(0.0));
        }
        if values.is_empty() {
            return None;
        }
        return Some(values);
    }
    let function = resolve_function(doc, object)?;
    let mut values = function.eval(t);
    if values.is_empty() {
        return None;
    }
    values.resize(components.max(values.len()), 0.0);
    Some(values)
}

// ---------------------------------------------------------------- shadings

fn resolve_shading(doc: &Document, object: &Object, matrix: Mat) -> Option<Gradient> {
    let (_, object) = doc.dereference(object).ok()?;
    let dict = as_dict_like(object)?;
    let kind = dict.get(b"ShadingType").ok()?.as_i64().ok()?;
    if kind != 2 && kind != 3 {
        return None;
    }
    let colour_space = resolve_colour_space(doc, dict.get(b"ColorSpace").ok()?)?;
    let components = colour_space.components();
    if components == 0 {
        return None;
    }
    let coords = number_array(doc, dict.get(b"Coords").ok()?)?;
    let extend = match dict.get(b"Extend") {
        Ok(value) => value
            .as_array()
            .ok()
            .map(|a| a.iter().map(|o| o.as_bool().unwrap_or(false)).collect::<Vec<bool>>())
            .unwrap_or_default(),
        Err(_) => Vec::new(),
    };
    let extend_start = extend.first().copied().unwrap_or(false);
    let extend_end = extend.get(1).copied().unwrap_or(false);
    let function = dict.get(b"Function").ok()?;

    let (device, quad);
    if kind == 2 {
        if coords.len() < 4 {
            return None;
        }
        let (x0, y0) = matrix.apply(coords[0], coords[1]);
        let (x1, y1) = matrix.apply(coords[2], coords[3]);
        device = [x0, y0, 0.0, x1, y1, 0.0];
    } else {
        // Radial coords are `x0 y0 r0 x1 y1 r1`: two circles, so the radii pick
        // up the matrix's average scale rather than the raw translation.
        if coords.len() < 6 {
            return None;
        }
        let (cx0, cy0) = matrix.apply(coords[0], coords[1]);
        let (cx1, cy1) = matrix.apply(coords[3], coords[4]);
        let scale = matrix_scale(matrix);
        let r0 = coords[2] * scale;
        let r1 = coords[5] * scale;
        device = [cx0, cy0, r0, cx1, cy1, r1];
    }

    // The quad is the pattern's unit square in device space. Producers that bake
    // the fill's objectBoundingBox into `/Matrix` (krilla does) make this coincide
    quad = [
        matrix.apply(0.0, 0.0).into(),
        matrix.apply(1.0, 0.0).into(),
        matrix.apply(1.0, 1.0).into(),
        matrix.apply(0.0, 1.0).into(),
    ];

    let stops = sample_stops(doc, function, &colour_space, components)?;
    if stops.len() < 2 {
        return None;
    }
    Some(Gradient { kind: kind as u8, extend_start, extend_end, coords: device, quad, stops })
}

fn matrix_scale(matrix: Mat) -> f32 {
    let sx = (matrix.a * matrix.a + matrix.b * matrix.b).sqrt();
    let sy = (matrix.c * matrix.c + matrix.d * matrix.d).sqrt();
    let average = (sx + sy) / 2.0;
    if average.is_finite() && average > 0.0 { average } else { 1.0 }
}

fn sample_stops(
    doc: &Document,
    function: &Object,
    colour_space: &Cs,
    components: usize,
) -> Option<Vec<[f32; 3]>> {
    let mut stops = Vec::with_capacity(STOPS);
    let last = (STOPS - 1) as f32;
    for i in 0..STOPS {
        let t = i as f32 / last;
        let values = evaluate_function(doc, function, components, t)?;
        stops.push(colour_space.to_rgb(&values));
    }
    Some(stops)
}

// --------------------------------------------------------------- resources

fn page_resources<'a>(doc: &'a Document, page_id: ObjectId) -> Option<&'a Dictionary> {
    let mut current = page_id;
    for _ in 0..64 {
        let dict = doc.get_dictionary(current).ok()?;
        if let Ok(resources) = dict.get(b"Resources") {
            if let Ok((_, resolved)) = doc.dereference(resources) {
                if let Ok(resource_dict) = resolved.as_dict() {
                    return Some(resource_dict);
                }
            }
        }
        current = dict.get(b"Parent").and_then(Object::as_reference).ok()?;
    }
    None
}

fn collect_page_shadings(
    doc: &Document,
    resources: &Dictionary,
) -> (Vec<Gradient>, HashMap<Vec<u8>, usize>) {
    let mut gradients = Vec::new();
    let mut names = HashMap::new();

    // `/Pattern` entries with `/PatternType 2`. A bare `/Shading` entry is only
    // painted by the `sh` operator, whose space is tracked in the content stream.
    if let Ok(entry) = resources.get(b"Pattern") {
        if let Ok((_, resolved)) = doc.dereference(entry) {
            if let Ok(patterns) = resolved.as_dict() {
                for (name, value) in patterns.iter() {
                    if gradients.len() >= MAX_GRADIENTS {
                        break;
                    }
                    let Ok((_, pattern)) = doc.dereference(value) else { continue };
                    let Some(dict) = as_dict_like(pattern) else { continue };
                    let pattern_type =
                        dict.get(b"PatternType").ok().and_then(|o| o.as_i64().ok()).unwrap_or(1);
                    if pattern_type != 2 {
                        continue;
                    }
                    let matrix = match dict.get(b"Matrix") {
                        Ok(value) => read_matrix(doc, value),
                        Err(_) => Mat::IDENTITY,
                    };
                    let Ok(shading) = dict.get(b"Shading") else { continue };
                    if let Some(gradient) = resolve_shading(doc, shading, matrix) {
                        names.insert(name.clone(), gradients.len());
                        gradients.push(gradient);
                    }
                }
            }
        }
    }

    (gradients, names)
}

fn read_matrix(doc: &Document, object: &Object) -> Mat {
    match number_array(doc, object) {
        Some(values) if values.len() >= 6 && values.iter().all(|v| v.is_finite()) => Mat {
            a: values[0],
            b: values[1],
            c: values[2],
            d: values[3],
            e: values[4],
            f: values[5],
        },
        _ => Mat::IDENTITY,
    }
}

// ------------------------------------------------------------- content walk

/// One painted object found in the content stream.
struct Paint {
    /// Device-space bounds of the painted path (or of the clip for `sh`).
    bbox: [f32; 4],
    /// Index into the gradient list when the fill is a shading, else `None`.
    gradient: Option<usize>,
    /// PDF blend mode, `None` for Normal.
    blend: Option<String>,
}

/// Graphics state carried through a content stream.
#[derive(Clone)]
struct State {
    ctm: Mat,
    fill_is_pattern: bool,
    pattern_name: Option<Vec<u8>>,
    blend: Option<String>,
}

fn float(operands: &[Object], index: usize) -> Option<f32> {
    operands.get(index).and_then(|object| object.as_float().ok())
}

fn points_of(operands: &[Object], count: usize) -> Vec<(f32, f32)> {
    let mut points = Vec::with_capacity(count);
    for index in 0..count {
        if let (Some(x), Some(y)) = (float(operands, index * 2), float(operands, index * 2 + 1)) {
            points.push((x, y));
        }
    }
    points
}

fn matrix_of(operands: &[Object]) -> Option<Mat> {
    Some(Mat {
        a: float(operands, 0)?,
        b: float(operands, 1)?,
        c: float(operands, 2)?,
        d: float(operands, 3)?,
        e: float(operands, 4)?,
        f: float(operands, 5)?,
    })
}

/// `/BM` value, normalised to `None` for Normal.
fn blend_name(value: &Object) -> Option<String> {
    let name = match value {
        Object::Array(items) => items.first()?,
        other => other,
    };
    let text = String::from_utf8(name.as_name().ok()?.to_vec()).ok()?;
    if text.is_empty() || text.eq_ignore_ascii_case("Normal") {
        None
    } else {
        Some(text)
    }
}

fn resource_entry<'a>(
    resources: &'a Dictionary,
    doc: &'a Document,
    category: &[u8],
    name: &[u8],
) -> Option<&'a Object> {
    let entry = resources.get(category).ok()?;
    let (_, resolved) = doc.dereference(entry).ok()?;
    let dict = resolved.as_dict().ok()?;
    let value = dict.get(name).ok()?;
    doc.dereference(value).ok().map(|(_, object)| object)
}

fn form_resources<'a>(doc: &'a Document, dict: &'a Dictionary) -> Option<&'a Dictionary> {
    let entry = dict.get(b"Resources").ok()?;
    let (_, resolved) = doc.dereference(entry).ok()?;
    resolved.as_dict().ok()
}

fn stream_bytes(object: &Object) -> Option<Vec<u8>> {
    object.as_stream().ok()?.get_plain_content().ok()
}

fn page_content_bytes(doc: &Document, page_id: ObjectId) -> Vec<u8> {
    let Ok(entry) = doc.get_dictionary(page_id).and_then(|dict| dict.get(b"Contents")) else {
        return Vec::new();
    };
    let Ok((_, object)) = doc.dereference(entry) else { return Vec::new() };
    match object {
        Object::Stream(_) => stream_bytes(object).unwrap_or_default(),
        Object::Array(items) => {
            let mut data = Vec::new();
            for item in items {
                if let Ok((_, resolved)) = doc.dereference(item) {
                    if let Some(bytes) = stream_bytes(resolved) {
                        data.extend_from_slice(&bytes);
                        data.push(b'\n');
                    }
                }
            }
            data
        }
        _ => Vec::new(),
    }
}

fn grow(path: &mut Option<[f32; 4]>, points: &[(f32, f32)]) {
    for &(x, y) in points {
        match path {
            Some(box_) => {
                box_[0] = box_[0].min(x);
                box_[1] = box_[1].min(y);
                box_[2] = box_[2].max(x);
                box_[3] = box_[3].max(y);
            }
            None => *path = Some([x, y, x, y]),
        }
    }
}

fn intersect(clip: Option<[f32; 4]>, box_: [f32; 4]) -> Option<[f32; 4]> {
    match clip {
        None => Some(box_),
        Some(clip) => {
            let merged =
                [clip[0].max(box_[0]), clip[1].max(box_[1]), clip[2].min(box_[2]), clip[3].min(box_[3])];
            if merged[0] < merged[2] && merged[1] < merged[3] {
                Some(merged)
            } else {
                Some(clip)
            }
        }
    }
}

/// Resolve one `/Pattern` entry of the given resources to a gradient.
fn pattern_shading(doc: &Document, resources: &Dictionary, name: &[u8]) -> Option<Gradient> {
    let dict = as_dict_like(resource_entry(resources, doc, b"Pattern", name)?)?;
    if dict.get(b"PatternType").ok().and_then(|o| o.as_i64().ok()).unwrap_or(1) != 2 {
        return None;
    }
    let matrix = match dict.get(b"Matrix") {
        Ok(value) => read_matrix(doc, value),
        Err(_) => Mat::IDENTITY,
    };
    resolve_shading(doc, dict.get(b"Shading").ok()?, matrix)
}

/// Walk a content stream tracking the graphics state, emitting a paint record
/// for every fill that uses a shading pattern or a non-Normal blend mode.
#[allow(clippy::too_many_arguments)]
fn walk(
    doc: &Document,
    resources: Option<&Dictionary>,
    data: &[u8],
    ctm: Mat,
    depth: u32,
    gradients: &mut Vec<Gradient>,
    names: &mut HashMap<Vec<u8>, usize>,
    paints: &mut Vec<Paint>,
) -> bool {
    if depth > 8 {
        return true;
    }
    let Ok(content) = Content::decode(data) else { return false };

    let mut state = State { ctm, fill_is_pattern: false, pattern_name: None, blend: None };
    let mut stack: Vec<State> = Vec::new();
    let mut path: Option<[f32; 4]> = None;
    let mut clip: Option<[f32; 4]> = None;
    let mut pending: Option<[f32; 4]> = None;

    for op in content.operations {
        match op.operator.as_str() {
            "q" => stack.push(state.clone()),
            "Q" => {
                if let Some(previous) = stack.pop() {
                    state = previous;
                }
            }
            "cm" => {
                if let Some(matrix) = matrix_of(&op.operands) {
                    state.ctm = matrix.mul(&state.ctm);
                }
            }
            "gs" => {
                if let Some(name) = op.operands.first().and_then(|o| o.as_name().ok()) {
                    state.blend = resources
                        .and_then(|r| resource_entry(r, doc, b"ExtGState", name))
                        .and_then(as_dict_like)
                        .and_then(|g| g.get(b"BM").ok())
                        .and_then(blend_name);
                }
            }
            "cs" => {
                state.fill_is_pattern = op
                    .operands
                    .first()
                    .and_then(|o| o.as_name().ok())
                    .map(|name| name == b"Pattern")
                    .unwrap_or(false);
            }
            "scn" => {
                state.pattern_name = if state.fill_is_pattern {
                    op.operands.iter().find_map(|o| o.as_name().ok().map(<[u8]>::to_vec))
                } else {
                    None
                };
                // Only the patterns the stream actually selects get resolved.
                if let (Some(name), Some(res)) = (state.pattern_name.as_ref(), resources) {
                    if !names.contains_key(name) && gradients.len() < MAX_GRADIENTS {
                        if let Some(gradient) = pattern_shading(doc, res, name) {
                            names.insert(name.clone(), gradients.len());
                            gradients.push(gradient);
                        }
                    }
                }
            }
            "sc" => state.pattern_name = None,
            "re" => {
                if op.operands.len() >= 4 {
                    if let (Some(x), Some(y), Some(w), Some(h)) = (
                        float(&op.operands, 0),
                        float(&op.operands, 1),
                        float(&op.operands, 2),
                        float(&op.operands, 3),
                    ) {
                        grow(&mut path, &[(x, y), (x + w, y + h)]);
                    }
                }
            }
            "m" | "l" => {
                if let (Some(x), Some(y)) = (float(&op.operands, 0), float(&op.operands, 1)) {
                    grow(&mut path, &[(x, y)]);
                }
            }
            "c" => {
                let points = points_of(&op.operands, 3);
                grow(&mut path, &points);
            }
            "v" | "y" => {
                let points = points_of(&op.operands, 2);
                grow(&mut path, &points);
            }
            "W" | "W*" => {
                if let Some(local) = path {
                    pending = Some(state.ctm.bounds_of(local));
                }
            }
            "sh" => {
                if let Some(name) = op.operands.first().and_then(|o| o.as_name().ok()) {
                    if gradients.len() < MAX_GRADIENTS {
                        let shading = resources
                            .and_then(|r| resource_entry(r, doc, b"Shading", name));
                        if let Some(gradient) = shading
                            .and_then(|shading| resolve_shading(doc, shading, state.ctm))
                        {
                            let index = gradients.len();
                            gradients.push(gradient);
                            paints.push(Paint {
                                bbox: clip.or(pending).unwrap_or([0.0, 0.0, 0.0, 0.0]),
                                gradient: Some(index),
                                blend: state.blend.clone(),
                            });
                        }
                    }
                }
            }
            "Do" => {
                if let Some(name) = op.operands.first().and_then(|o| o.as_name().ok()) {
                    let form =
                        resources.and_then(|r| resource_entry(r, doc, b"XObject", name));
                    if let Some(form) = form {
                        if let Some(dict) = as_dict_like(form) {
                            if dict.get(b"Subtype").ok().and_then(|o| o.as_name().ok())
                                == Some(b"Form".as_ref())
                            {
                                let matrix = match dict.get(b"Matrix") {
                                    Ok(value) => read_matrix(doc, value),
                                    Err(_) => Mat::IDENTITY,
                                };
                                let inner = form_resources(doc, dict).or(resources);
                                if let Some(bytes) = stream_bytes(form) {
                                    walk(
                                        doc,
                                        inner,
                                        &bytes,
                                        state.ctm.mul(&matrix),
                                        depth + 1,
                                        gradients,
                                        names,
                                        paints,
                                    );
                                }
                            }
                        }
                    }
                }
            }
            "f" | "F" | "f*" | "B" | "B*" | "b" | "b*" | "S" | "s" | "n" => {
                if let Some(box_) = pending.take() {
                    clip = intersect(clip, box_);
                }
                if !matches!(op.operator.as_str(), "S" | "s" | "n") {
                    if let Some(local) = path {
                        let gradient =
                            state.pattern_name.as_ref().and_then(|n| names.get(n).copied());
                        if gradient.is_some() || state.blend.is_some() {
                            paints.push(Paint {
                                bbox: state.ctm.bounds_of(local),
                                gradient,
                                blend: state.blend.clone(),
                            });
                        }
                    }
                }
                path = None;
            }
            _ => {}
        }
    }
    true
}

// ------------------------------------------------------------------ payload

fn write_gradients(gradients: &[Gradient]) -> String {
    use std::fmt::Write as _;

    let mut out = String::with_capacity(gradients.len() * 128);
    for gradient in gradients {
        let _ = write!(
            out,
            "G {} {} {} {:.4} {:.4} {:.4} {:.4} {:.4} {:.4}",
            gradient.kind,
            gradient.extend_start as u8,
            gradient.extend_end as u8,
            gradient.coords[0],
            gradient.coords[1],
            gradient.coords[2],
            gradient.coords[3],
            gradient.coords[4],
            gradient.coords[5]
        );
        for corner in &gradient.quad {
            let _ = write!(out, " {:.4} {:.4}", corner[0], corner[1]);
        }
        let _ = writeln!(out, " {}", gradient.stops.len());
        for (index, stop) in gradient.stops.iter().enumerate() {
            let _ = writeln!(
                out,
                "S {:.5} {:.5} {:.5} {:.5}",
                index as f32 / (gradient.stops.len().max(2) - 1) as f32,
                stop[0],
                stop[1],
                stop[2]
            );
        }
    }
    out
}

fn write_payload(gradients: &[Gradient], paints: &[Paint]) -> String {
    use std::fmt::Write as _;

    let mut out = write_gradients(gradients);
    for paint in paints {
        let _ = writeln!(
            out,
            "P {:.4} {:.4} {:.4} {:.4} {} {}",
            paint.bbox[0],
            paint.bbox[1],
            paint.bbox[2],
            paint.bbox[3],
            paint.gradient.map_or(-1, |index| index as i64),
            paint.blend.as_deref().unwrap_or("-")
        );
    }
    out
}

/// Resolve the shadings reachable from one page together with the paint records
/// tying them to the shapes drawn. `None` when the page declares no shading.
fn page_records(doc: &Document, page_id: ObjectId) -> Option<(Vec<Gradient>, Vec<Paint>)> {
    let resources = page_resources(doc, page_id)?;
    let mut gradients = Vec::new();
    let mut names = HashMap::new();
    let mut paints = Vec::new();
    let content = page_content_bytes(doc, page_id);
    let decoded = walk(
        doc,
        Some(resources),
        &content,
        Mat::IDENTITY,
        0,
        &mut gradients,
        &mut names,
        &mut paints,
    );
    if !decoded {
        // Unparsable content stream: fall back to every pattern the page declares.
        let base = gradients.len();
        let (declared, declared_names) = collect_page_shadings(doc, resources);
        for (key, value) in declared_names {
            names.entry(key).or_insert(base + value);
        }
        gradients.extend(declared);
    }
    if gradients.is_empty() {
        return None;
    }
    Some((gradients, paints))
}

/// Zero-based index of every page in document order, paired with its object id.
fn numbered_pages(doc: &Document) -> Vec<(i32, ObjectId)> {
    let mut ids: Vec<(i32, ObjectId)> =
        doc.get_pages().into_iter().map(|(number, id)| (number as i32 - 1, id)).collect();
    ids.sort_unstable_by_key(|(index, _)| *index);
    ids
}

/// Append one page's records behind the `#PAGE` marker Java splits the payload on.
fn append_page(out: &mut String, index: i32, doc: &Document, page_id: ObjectId) -> bool {
    use std::fmt::Write as _;

    let Some((gradients, paints)) = page_records(doc, page_id) else { return false };
    let _ = write!(out, "#PAGE {index}\n");
    out.push_str(&write_payload(&gradients, &paints));
    true
}

/// Extract the shadings of the selected pages from an already parsed document.
/// A `pages` of `None` or an empty slice selects every page.
fn selected_page_gradients(doc: &Document, pages: Option<&[i32]>) -> Option<Vec<u8>> {
    let selected = pages.filter(|slice| !slice.is_empty());
    let mut out = String::new();
    let mut found = false;
    for (index, page_id) in numbered_pages(doc) {
        if selected.is_some_and(|slice| !slice.contains(&index)) {
            continue;
        }
        found |= append_page(&mut out, index, doc, page_id);
    }
    if !found {
        return None;
    }
    Some(out.into_bytes())
}

/// Resolve every shading reachable from one page. `None` when the page declares none.
pub(crate) fn page_gradients(pdf: &[u8], page_index: i32) -> Option<Vec<u8>> {
    if page_index < 0 {
        return None;
    }
    let doc = Document::load_from(Cursor::new(pdf)).ok()?;
    let page_id = *doc.get_pages().get(&((page_index as u32) + 1))?;
    let (gradients, paints) = page_records(&doc, page_id)?;
    Some(write_payload(&gradients, &paints).into_bytes())
}

/// Multi-page extraction from any reader. The document is parsed once and its
/// pages are walked in document order, which is the file-backed export route.
pub(crate) fn page_gradients_reader<R: Read>(reader: R, pages: Option<&[i32]>) -> Option<Vec<u8>> {
    selected_page_gradients(&Document::load_from(reader).ok()?, pages)
}
