// jpdfium-impl - Rust-powered PDF processing functions for JPDFium.
//
// Provides four C-ABI functions compiled into libjpdfium_rust.a and linked
// directly into libjpdfium.so as first-class, always-available features:
//
//   jpdfium_rust_compress_pdf   - lopdf + zopfli superior DEFLATE, flate2 fallback decompression
//   jpdfium_rust_repair_lopdf   - lopdf tolerant XRef rebuild / repair
//   jpdfium_rust_resize_pixels  - fast_image_resize SIMD pixel scaling (Lanczos3)
//   jpdfium_rust_unpack_pixels  - gray/rgb/cmyk/bgr samples -> opaque ARGB u32
//   jpdfium_rust_compress_png   - oxipng lossless PNG optimisation
//   jpdfium_rust_free           - free buffers allocated by any of the above
//
// All functions use libc::malloc for output allocation so the Java side can
// free them with jpdfium_rust_free regardless of Rust's internal allocator.

use std::ffi::CStr;
use std::io::{Cursor, Read};
use std::os::raw::c_char;
use std::slice;

use fast_image_resize as fir;
use fir::images::Image as FirImage;
use flate2::read::ZlibDecoder;
use lopdf::Document;
use zopfli::{compress as zopfli_compress, Format, Options};

mod gradients;

#[cfg(test)]
mod gradients_tests;

#[cfg(test)]
mod bench_tests;

// Return codes matching jpdfium.h / jpdfium_rust.h
const JPDFIUM_OK: i32 = 0;
const JPDFIUM_ERR_INVALID: i32 = -1;
const JPDFIUM_ERR_GENERIC: i32 = -1;
const JPDFIUM_REPAIR_FIXED: i32 = 1;
const JPDFIUM_REPAIR_FAILED: i32 = -1;

/// Allocate a buffer with libc::malloc and copy `data` into it.
/// Returns null if malloc fails or data is empty.
unsafe fn malloc_copy(data: &[u8]) -> *mut u8 {
    if data.is_empty() {
        return std::ptr::null_mut();
    }
    let ptr = libc::malloc(data.len()) as *mut u8;
    if ptr.is_null() {
        return std::ptr::null_mut();
    }
    std::ptr::copy_nonoverlapping(data.as_ptr(), ptr, data.len());
    ptr
}

/// Decompress ZLib (FlateDecode) bytes using flate2.
/// Returns the raw content, or None on failure.
fn flate2_decompress_zlib(data: &[u8]) -> Option<Vec<u8>> {
    let mut decoder = ZlibDecoder::new(data);
    let mut output = Vec::new();
    decoder.read_to_end(&mut output).ok()?;
    Some(output)
}

/// Compress `input` with zopfli using ZLib format (matches PDF FlateDecode).
/// Returns None if compression fails or produces output no smaller than `input`.
fn zopfli_compress_zlib(input: &[u8], iterations: u32) -> Option<Vec<u8>> {
    if input.is_empty() {
        return None;
    }
    let iters = std::num::NonZeroU64::new(iterations.max(1) as u64)
        .unwrap_or(std::num::NonZeroU64::new(15).unwrap());
    let options = Options {
        iteration_count: iters,
        ..Options::default()
    };
    let mut output: Vec<u8> = Vec::new();
    match zopfli_compress(options, Format::Zlib, input, &mut output) {
        Ok(()) if output.len() < input.len() => Some(output),
        _ => None,
    }
}

/// Shared system-font database for SVG text rendering (loaded lazily once).
fn system_fontdb() -> std::sync::Arc<usvg::fontdb::Database> {
    static DB: std::sync::OnceLock<std::sync::Arc<usvg::fontdb::Database>> =
        std::sync::OnceLock::new();
    DB.get_or_init(|| {
        let mut db = usvg::fontdb::Database::new();
        db.load_system_fonts();
        std::sync::Arc::new(db)
    })
    .clone()
}

/// The same font discovery for krilla-svg's usvg 0.47 tree.
///
/// usvg 0.47 and 0.48 depend on different fontdb majors, so the two trees
/// cannot share one `Database` value. Loading the system fonts once per tree is
/// still cheap compared with parsing the SVG itself.
fn system_fontdb_v47() -> std::sync::Arc<usvg_v47::fontdb::Database> {
    static DB: std::sync::OnceLock<std::sync::Arc<usvg_v47::fontdb::Database>> =
        std::sync::OnceLock::new();
    DB.get_or_init(|| {
        let mut db = usvg_v47::fontdb::Database::new();
        db.load_system_fonts();
        std::sync::Arc::new(db)
    })
    .clone()
}

/// Compress a PDF using lopdf + zopfli for superior FlateDecode streams.
///
/// Pipeline:
///   1. Load PDF with lopdf (tolerant parser).
///   2. `doc.decompress()` - strips all Filter entries, exposing raw content.
///      Streams lopdf could not decompress are retried with flate2 directly.
///   3. Re-compress each decompressed stream with zopfli (ZLib format).
///   4. `doc.save_to()` - write corrected output.
///
/// # Safety
/// `input` must point to `input_len` valid bytes.
/// `out_ptr` and `out_len` must be valid non-null pointers.
#[no_mangle]
pub unsafe extern "C" fn jpdfium_rust_compress_pdf(
    input: *const u8,
    input_len: i64,
    out_ptr: *mut *mut u8,
    out_len: *mut i64,
    zopfli_iters: i32,
) -> i32 {
    *out_ptr = std::ptr::null_mut();
    *out_len = 0;

    if input.is_null() || input_len <= 0 {
        return JPDFIUM_ERR_GENERIC;
    }

    let input_slice = slice::from_raw_parts(input, input_len as usize);
    let iterations = zopfli_iters.max(1) as u32;

    match compress_pdf_impl(input_slice, iterations) {
        Ok(bytes) => {
            let ptr = malloc_copy(&bytes);
            if ptr.is_null() {
                return JPDFIUM_ERR_GENERIC;
            }
            *out_ptr = ptr;
            *out_len = bytes.len() as i64;
            JPDFIUM_OK
        }
        Err(_) => JPDFIUM_ERR_GENERIC,
    }
}

fn compress_pdf_impl(input: &[u8], iterations: u32) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
    let mut doc = Document::load_from(Cursor::new(input))?;

    // lopdf's decompress() uses flate2 internally for all FlateDecode streams.
    doc.decompress();

    for (_, obj) in doc.objects.iter_mut() {
        if let lopdf::Object::Stream(ref mut stream) = obj {
            if stream.content.is_empty() || !stream.allows_compression {
                continue;
            }

            // After doc.decompress(), streams that were successfully decoded
            // no longer have a Filter entry. Streams that lopdf could NOT
            // decode still have Filter set.
            let still_filtered = stream.dict.get(b"Filter").is_ok();

            // Obtain raw (uncompressed) content as an owned Vec to avoid borrow
            // conflicts when we later mutate stream.content.
            let raw: Vec<u8> = if still_filtered {
                // lopdf left this stream compressed - try flate2 as a fallback.
                match flate2_decompress_zlib(&stream.content) {
                    Some(decoded) => decoded,
                    None => continue, // Cannot decode; leave stream untouched.
                }
            } else {
                stream.content.clone()
            };

            // Re-compress with zopfli.  Only replace if we actually produce
            // smaller output (zopfli_compress_zlib returns None otherwise).
            if let Some(compressed) = zopfli_compress_zlib(&raw, iterations) {
                stream.content = compressed;
                // Ensure the Filter entry is set correctly (flate2-decoded
                // streams had their Filter removed by doc.decompress()).
                stream
                    .dict
                    .set("Filter", lopdf::Object::Name(b"FlateDecode".to_vec()));
            } else if still_filtered {
                // flate2 decoded the stream but zopfli didn't help: store the
                // decoded bytes re-compressed with the default FlateDecode so
                // the Filter entry remains consistent.
                stream.content = raw;
                // Filter entry already present - leave it.
            }
        }
    }

    let mut output = Vec::new();
    doc.save_to(&mut Cursor::new(&mut output))?;
    Ok(output)
}

/// Repair a PDF using lopdf's tolerant XRef parser.
///
/// lopdf's parser tolerates broken cross-reference tables and can often load
/// PDFs that PDFium and qpdf reject. Saving immediately after loading rebuilds
/// the XRef table from scratch, producing a structurally valid PDF.
///
/// # Safety
/// `input` must point to `input_len` valid bytes.
/// `out_ptr` and `out_len` must be valid non-null pointers.
#[no_mangle]
pub unsafe extern "C" fn jpdfium_rust_repair_lopdf(
    input: *const u8,
    input_len: i64,
    out_ptr: *mut *mut u8,
    out_len: *mut i64,
) -> i32 {
    *out_ptr = std::ptr::null_mut();
    *out_len = 0;

    if input.is_null() || input_len <= 0 {
        return JPDFIUM_REPAIR_FAILED;
    }

    let input_slice = slice::from_raw_parts(input, input_len as usize);

    match repair_lopdf_impl(input_slice) {
        Ok(bytes) => {
            let ptr = malloc_copy(&bytes);
            if ptr.is_null() {
                return JPDFIUM_REPAIR_FAILED;
            }
            *out_ptr = ptr;
            *out_len = bytes.len() as i64;
            JPDFIUM_REPAIR_FIXED
        }
        Err(_) => JPDFIUM_REPAIR_FAILED,
    }
}

fn repair_lopdf_impl(input: &[u8]) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
    // lopdf's tolerant XRef parser handles many corruptions.
    // Immediately saving rebuilds the XRef table correctly from object offsets.
    let mut doc = Document::load_from(Cursor::new(input))?;
    let mut output = Vec::new();
    doc.save_to(&mut Cursor::new(&mut output))?;
    Ok(output)
}

/// Resize raw interleaved pixel data using SIMD-accelerated Lanczos3 resampling.
///
/// `components` controls the pixel format:
///   1 = grayscale (U8)
///   3 = RGB (U8x3)
///   4 = RGBA (U8x4)
///
/// # Safety
/// `src_pixels` must point to `src_len` valid bytes.
/// `out_ptr` and `out_len` must be valid non-null pointers.
#[no_mangle]
pub unsafe extern "C" fn jpdfium_rust_resize_pixels(
    src_pixels: *const u8,
    src_len: i64,
    src_width: i32,
    src_height: i32,
    components: i32,
    dst_width: i32,
    dst_height: i32,
    out_ptr: *mut *mut u8,
    out_len: *mut i64,
) -> i32 {
    *out_ptr = std::ptr::null_mut();
    *out_len = 0;

    if src_pixels.is_null()
        || src_len <= 0
        || src_width <= 0
        || src_height <= 0
        || dst_width <= 0
        || dst_height <= 0
        || !(1..=4).contains(&components)
    {
        return JPDFIUM_ERR_GENERIC;
    }

    let src_slice = slice::from_raw_parts(src_pixels, src_len as usize);

    let pixel_type = match components {
        1 => fir::PixelType::U8,
        3 => fir::PixelType::U8x3,
        4 => fir::PixelType::U8x4,
        _ => return JPDFIUM_ERR_GENERIC,
    };

    match resize_pixels_impl(
        src_slice,
        src_width as u32,
        src_height as u32,
        dst_width as u32,
        dst_height as u32,
        pixel_type,
    ) {
        Ok(bytes) => {
            let ptr = malloc_copy(&bytes);
            if ptr.is_null() {
                return JPDFIUM_ERR_GENERIC;
            }
            *out_ptr = ptr;
            *out_len = bytes.len() as i64;
            JPDFIUM_OK
        }
        Err(_) => JPDFIUM_ERR_GENERIC,
    }
}

fn resize_pixels_impl(
    src: &[u8],
    src_w: u32,
    src_h: u32,
    dst_w: u32,
    dst_h: u32,
    pixel_type: fir::PixelType,
) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
    if src_w == 0 || src_h == 0 || dst_w == 0 || dst_h == 0 {
        return Err("dimensions must be non-zero".into());
    }

    // from_slice_u8 requires &mut [u8]; clone so we own the data.
    let mut src_buf = src.to_vec();
    let src_image = FirImage::from_slice_u8(src_w, src_h, &mut src_buf, pixel_type)?;
    let mut dst_image = FirImage::new(dst_w, dst_h, pixel_type);

    let mut resizer = fir::Resizer::new();
    let options = fir::ResizeOptions::new()
        .resize_alg(fir::ResizeAlg::Convolution(fir::FilterType::Lanczos3));
    resizer.resize(&src_image, &mut dst_image, Some(&options))?;

    Ok(dst_image.into_vec())
}

/// Unpack raw samples into opaque ARGB u32s. Formats: 1=gray8, 2=rgb24, 3=cmyk32, 4=bgr24, 5=bgrx/bgra.
///
/// # Safety
///
/// `src` must be readable for `src_stride * (height - 1) + width * bpp` bytes,
/// where `bpp` is the per-format bytes-per-pixel; `dst` must be writable for
/// `width * height` u32s.
/// CMYK (32bpp) samples to opaque ARGB for one row, sixteen pixels per vector
/// step where NEON is available. Reads are unaligned because rows are strided.
unsafe fn cmyk_row_to_argb(row: *const u8, d: &mut [u32]) {
    let n = d.len();
    let mut x = 0usize;
    #[cfg(target_arch = "aarch64")]
    {
        use std::arch::aarch64::*;
        let out = d.as_mut_ptr() as *mut u8;
        let alpha = vdupq_n_u8(0xFF);
        let full = vdupq_n_u8(255);
        while x + 16 <= n {
            // vld4q_u8 de-interleaves the CMYK quads into four byte planes.
            let quad = unsafe { vld4q_u8(row.add(x * 4)) };
            let k = quad.3;
            let (c, m, y) = unsafe {
                (
                    vsubq_u8(full, vqaddq_u8(quad.0, k)),
                    vsubq_u8(full, vqaddq_u8(quad.1, k)),
                    vsubq_u8(full, vqaddq_u8(quad.2, k)),
                )
            };
            // ARGB bytes in memory are y, m, c, alpha on little-endian hosts.
            unsafe { vst4q_u8(out.add(x * 4), uint8x16x4_t(y, m, c, alpha)) };
            x += 16;
        }
    }
    while x < n {
        let px = unsafe { u32::from_le(std::ptr::read_unaligned(row.add(x * 4) as *const u32)) };
        let k = px >> 24;
        let c = 255 - ((px & 0xFF) + k).min(255);
        let m = 255 - (((px >> 8) & 0xFF) + k).min(255);
        let y = 255 - (((px >> 16) & 0xFF) + k).min(255);
        d[x] = 0xFF00_0000 | (c << 16) | (m << 8) | y;
        x += 1;
    }
}

/// RGB (24bpp, or BGR when `bgr`) samples to opaque ARGB for one row, sixteen
/// pixels per vector step where NEON is available. Reads are unaligned.
unsafe fn rgb_row_to_argb(row: *const u8, d: &mut [u32], bgr: bool) {
    let n = d.len();
    let mut x = 0usize;
    #[cfg(target_arch = "aarch64")]
    {
        use std::arch::aarch64::*;
        let out = d.as_mut_ptr() as *mut u8;
        let alpha = vdupq_n_u8(0xFF);
        while x + 16 <= n {
            // vld3q_u8 de-interleaves the RGB triples into three byte planes.
            let rgb = unsafe { vld3q_u8(row.add(x * 3)) };
            let (r, g, b) = if bgr {
                (rgb.2, rgb.1, rgb.0)
            } else {
                (rgb.0, rgb.1, rgb.2)
            };
            // ARGB bytes in memory are b, g, r, alpha on little-endian hosts.
            unsafe { vst4q_u8(out.add(x * 4), uint8x16x4_t(b, g, r, alpha)) };
            x += 16;
        }
    }
    while x < n {
        let o = x * 3;
        let (r, g, b) = unsafe {
            if bgr {
                (*row.add(o + 2), *row.add(o + 1), *row.add(o))
            } else {
                (*row.add(o), *row.add(o + 1), *row.add(o + 2))
            }
        };
        d[x] = 0xFF00_0000 | ((r as u32) << 16) | ((g as u32) << 8) | (b as u32);
        x += 1;
    }
}

#[no_mangle]
pub unsafe extern "C" fn jpdfium_rust_unpack_pixels(
    src: *const u8,
    width: i32,
    height: i32,
    src_stride: i64,
    format: i32,
    dst: *mut u32,
    dst_len: i64,
) -> i32 {
    if src.is_null() || dst.is_null() || width <= 0 || height <= 0 {
        return JPDFIUM_ERR_INVALID;
    }
    let bpp: i64 = match format {
        1 => 1,
        2 | 4 => 3,
        3 | 5 => 4,
        _ => return JPDFIUM_ERR_INVALID,
    };
    let pixels = (width as i64) * (height as i64);
    if dst_len < pixels || src_stride < (width as i64) * bpp {
        return JPDFIUM_ERR_INVALID;
    }
    // Reject strides whose row-major extent overflows or exceeds the addressable
    // range, so the `y * stride` indexing below can never wrap.
    let Some(stride_extent) = src_stride.checked_mul(height as i64) else {
        return JPDFIUM_ERR_INVALID;
    };
    if stride_extent > isize::MAX as i64 {
        return JPDFIUM_ERR_INVALID;
    }

    let w = width as usize;
    let h = height as usize;
    let stride = src_stride as usize;

    match format {
        1 => {
            for y in 0..h {
                let s = slice::from_raw_parts(src.add(y * stride), w);
                let d = slice::from_raw_parts_mut(dst.add(y * w), w);
                for (di, &sv) in d.iter_mut().zip(s) {
                    let v = sv as u32;
                    *di = 0xFF00_0000 | (v << 16) | (v << 8) | v;
                }
            }
        }
        2 => {
            for y in 0..h {
                let row = src.add(y * stride);
                let d = slice::from_raw_parts_mut(dst.add(y * w), w);
                unsafe { rgb_row_to_argb(row, d, false) };
            }
        }
        3 => {
            for y in 0..h {
                let row = src.add(y * stride);
                let d = slice::from_raw_parts_mut(dst.add(y * w), w);
                unsafe { cmyk_row_to_argb(row, d) };
            }
        }
        4 => {
            for y in 0..h {
                let row = src.add(y * stride);
                let d = slice::from_raw_parts_mut(dst.add(y * w), w);
                unsafe { rgb_row_to_argb(row, d, true) };
            }
        }
        5 => {
            for y in 0..h {
                let s = slice::from_raw_parts(src.add(y * stride), w * 4);
                let d = slice::from_raw_parts_mut(dst.add(y * w), w);
                for (x, di) in d.iter_mut().enumerate() {
                    let o = x * 4;
                    *di = ((s[o + 3] as u32) << 24)
                        | ((s[o + 2] as u32) << 16)
                        | ((s[o + 1] as u32) << 8)
                        | (s[o] as u32);
                }
            }
        }
        _ => return JPDFIUM_ERR_INVALID,
    }
    JPDFIUM_OK
}

/// Optimise a standalone PNG byte stream using oxipng (lossless).
///
/// Useful for PNG images extracted from a PDF (e.g. via jpdfium_render_page or
/// image-object extraction) before re-embedding. oxipng removes superfluous
/// metadata, optimises filter selection, and re-deflates with zopfli for the
/// smallest possible lossless PNG.
///
/// Returns 0 and fills out_ptr/out_len only if the optimised output is
/// strictly smaller than the input.  Returns -1 if no improvement was found or
/// if the input is not a valid PNG.
///
/// # Safety
/// `input` must point to `input_len` valid bytes.
/// `out_ptr` and `out_len` must be valid non-null pointers.
#[no_mangle]
pub unsafe extern "C" fn jpdfium_rust_compress_png(
    input: *const u8,
    input_len: i64,
    out_ptr: *mut *mut u8,
    out_len: *mut i64,
    level: i32,
) -> i32 {
    *out_ptr = std::ptr::null_mut();
    *out_len = 0;

    if input.is_null() || input_len <= 0 {
        return JPDFIUM_ERR_GENERIC;
    }

    let input_slice = slice::from_raw_parts(input, input_len as usize);
    let preset = level.clamp(0, 6) as u8;

    let options = oxipng::Options::from_preset(preset);
    match oxipng::optimize_from_memory(input_slice, &options) {
        Ok(optimized) if optimized.len() < input_slice.len() => {
            let ptr = malloc_copy(&optimized);
            if ptr.is_null() {
                return JPDFIUM_ERR_GENERIC;
            }
            *out_ptr = ptr;
            *out_len = optimized.len() as i64;
            JPDFIUM_OK
        }
        Ok(_) => JPDFIUM_ERR_GENERIC,  // already optimal
        Err(_) => JPDFIUM_ERR_GENERIC, // not a valid PNG
    }
}

/// Whether this library was built with the Rust-powered features (always 1
/// here; the C++ stub bridge answers 0 so callers can probe capability).
#[no_mangle]
pub extern "C" fn jpdfium_has_rust() -> i32 {
    1
}

/// Rasterize an SVG document to straight (unpremultiplied) RGBA using resvg.
///
/// `width`/`height` <= 0 keep the SVG's natural size; otherwise the document is
/// scaled, preserving aspect ratio, to fit the requested box. The output is
/// allocated with libc::malloc and must be freed with jpdfium_rust_free.
///
/// # Safety
/// `svg` must point to `svg_len` readable bytes; the out pointers must be valid.
#[no_mangle]
pub unsafe extern "C" fn jpdfium_rust_svg_to_rgba(
    svg: *const u8,
    svg_len: usize,
    width: i32,
    height: i32,
    out_ptr: *mut *mut u8,
    out_len: *mut i64,
    out_w: *mut i32,
    out_h: *mut i32,
) -> i32 {
    if svg.is_null() || svg_len == 0 || out_ptr.is_null() || out_len.is_null() {
        return JPDFIUM_ERR_GENERIC;
    }
    let data = slice::from_raw_parts(svg, svg_len);
    // Untrusted SVG: allow only inline data: images. The default string
    // resolver reads image files from the local filesystem, which would let a
    // crafted document embed host files into the raster.
    let mut options = usvg::Options::default();
    // usvg's default font database is empty, so <text> in an SVG would parse
    // but rasterize with no fonts at all. Load the system fonts once (shared,
    // read-only) instead of per conversion.
    options.fontdb = system_fontdb();
    options.image_href_resolver = usvg::ImageHrefResolver {
        resolve_data: usvg::ImageHrefResolver::default_data_resolver(),
        resolve_string: Box::new(|_, _| None),
    };
    let tree = match usvg::Tree::from_data(data, &options) {
        Ok(tree) => tree,
        Err(_) => return JPDFIUM_ERR_GENERIC,
    };
    let size = tree.size();
    let (natural_w, natural_h) = (size.width(), size.height());
    if !(natural_w.is_finite() && natural_h.is_finite()) || natural_w <= 0.0 || natural_h <= 0.0 {
        return JPDFIUM_ERR_GENERIC;
    }
    let (target_w, target_h, scale) = if width > 0 && height > 0 {
        let scale = (width as f32 / natural_w).min(height as f32 / natural_h);
        (
            (natural_w * scale).round().max(1.0) as u32,
            (natural_h * scale).round().max(1.0) as u32,
            scale,
        )
    } else {
        (
            natural_w.round().max(1.0) as u32,
            natural_h.round().max(1.0) as u32,
            1.0,
        )
    };

    // Untrusted input sets the output size: cap the dimensions and the total
    // pixels before any allocation so a crafted SVG cannot OOM the JVM.
    // 16384^2 = 268M pixels, the same 1 GiB RGBA bound the bridge renders use.
    const MAX_DIM: u32 = 16384;
    const MAX_PIXELS: u64 = 16384 * 16384;
    if target_w == 0 || target_h == 0 || target_w > MAX_DIM || target_h > MAX_DIM {
        return JPDFIUM_ERR_GENERIC;
    }
    if (target_w as u64) * (target_h as u64) > MAX_PIXELS {
        return JPDFIUM_ERR_GENERIC;
    }

    // Render straight into a malloc'd buffer: PixmapMut borrows the exact
    // memory the caller will free with jpdfium_rust_free, so there is no
    // intermediate Vec and no second copy. Zero it first (PixmapMut over
    // uninitialised memory would be UB).
    let byte_len = (target_w as usize) * (target_h as usize) * 4;
    let ptr = libc::malloc(byte_len) as *mut u8;
    if ptr.is_null() {
        return JPDFIUM_ERR_GENERIC;
    }
    std::ptr::write_bytes(ptr, 0, byte_len);
    let buf = slice::from_raw_parts_mut(ptr, byte_len);
    let mut pixmap = match tiny_skia::PixmapMut::from_bytes(buf, target_w, target_h) {
        Some(pixmap) => pixmap,
        None => {
            libc::free(ptr as *mut libc::c_void);
            return JPDFIUM_ERR_GENERIC;
        }
    };
    resvg::render(
        &tree,
        tiny_skia::Transform::from_scale(scale, scale),
        &mut pixmap,
    );
    // End the mutable borrow of `buf` before touching it again.
    drop(pixmap);

    // tiny-skia stores premultiplied RGBA; the JVM and libvips want straight.
    for px in buf.chunks_exact_mut(4) {
        let a = px[3] as u32;
        if a != 0 && a != 255 {
            px[0] = ((px[0] as u32 * 255 + a / 2) / a) as u8;
            px[1] = ((px[1] as u32 * 255 + a / 2) / a) as u8;
            px[2] = ((px[2] as u32 * 255 + a / 2) / a) as u8;
        }
    }

    *out_ptr = ptr;
    *out_len = byte_len as i64;
    if !out_w.is_null() {
        *out_w = target_w as i32;
    }
    if !out_h.is_null() {
        *out_h = target_h as i32;
    }
    JPDFIUM_OK
}

/// Render an SVG document to a standalone PDF (krilla + krilla-svg), parsed with
/// the same hardened options as the raster path. Free with jpdfium_rust_free.
#[no_mangle]
pub unsafe extern "C" fn jpdfium_rust_svg_to_pdf(
    svg: *const u8,
    svg_len: usize,
    width: i32,
    height: i32,
    embed_text: i32,
    out_ptr: *mut *mut u8,
    out_len: *mut i64,
) -> i32 {
    if out_ptr.is_null() || out_len.is_null() {
        return JPDFIUM_ERR_GENERIC;
    }
    *out_ptr = std::ptr::null_mut();
    *out_len = 0;
    if svg.is_null() || svg_len == 0 {
        return JPDFIUM_ERR_GENERIC;
    }
    let data = slice::from_raw_parts(svg, svg_len);

    // Untrusted SVG: allow only inline data: images. krilla-svg 0.8 still
    // consumes usvg 0.47, so this path has its own hardened parse and fontdb.
    let mut options = usvg_v47::Options::default();
    options.fontdb = system_fontdb_v47();
    options.image_href_resolver = usvg_v47::ImageHrefResolver {
        resolve_data: usvg_v47::ImageHrefResolver::default_data_resolver(),
        resolve_string: Box::new(|_, _| None),
    };
    let tree = match usvg_v47::Tree::from_data(data, &options) {
        Ok(tree) => tree,
        Err(_) => return JPDFIUM_ERR_GENERIC,
    };

    let size = tree.size();
    let (natural_w, natural_h) = (size.width(), size.height());
    if !(natural_w.is_finite() && natural_h.is_finite()) || natural_w <= 0.0 || natural_h <= 0.0 {
        return JPDFIUM_ERR_GENERIC;
    }
    let scale = if width > 0 && height > 0 {
        (width as f32 / natural_w).min(height as f32 / natural_h)
    } else {
        1.0
    };
    let page_w = natural_w * scale;
    let page_h = natural_h * scale;
    // Untrusted input sets the page size: cap it so a crafted SVG cannot ask
    // for a preposterous MediaBox.
    const MAX_POINTS: f32 = 16384.0;
    if !(page_w.is_finite() && page_h.is_finite())
        || page_w <= 0.0
        || page_h <= 0.0
        || page_w > MAX_POINTS
        || page_h > MAX_POINTS
    {
        return JPDFIUM_ERR_GENERIC;
    }

    let pdf = match svg_to_pdf_krilla(&tree, page_w, page_h, embed_text != 0) {
        Some(bytes) => bytes,
        None => return JPDFIUM_ERR_GENERIC,
    };
    let ptr = malloc_copy(&pdf);
    if ptr.is_null() {
        return JPDFIUM_ERR_GENERIC;
    }
    *out_ptr = ptr;
    *out_len = pdf.len() as i64;
    JPDFIUM_OK
}

/// Draw a parsed SVG onto a fresh krilla page and serialize it to PDF bytes.
fn svg_to_pdf_krilla(
    tree: &usvg_v47::Tree,
    page_w: f32,
    page_h: f32,
    embed_text: bool,
) -> Option<Vec<u8>> {
    use krilla_svg::SurfaceExt;

    let mut document = krilla::Document::new();
    let page_settings = krilla::page::PageSettings::from_wh(page_w, page_h)?;
    let mut page = document.start_page_with(page_settings);
    let mut surface = page.surface();
    let target = krilla::geom::Size::from_wh(page_w, page_h)?;
    let svg_settings = krilla_svg::SvgSettings {
        embed_text,
        ..Default::default()
    };
    surface.draw_svg(tree, target, svg_settings)?;
    surface.finish();
    page.finish();
    document.finish().ok()
}

/// Extract a page's shading patterns as gradient definitions for the PDF -> SVG
/// exporter. Free with jpdfium_rust_free.
#[no_mangle]
pub unsafe extern "C" fn jpdfium_page_gradients(
    input: *const u8,
    input_len: usize,
    page_index: i32,
    out_ptr: *mut *mut u8,
    out_len: *mut i64,
) -> i32 {
    if out_ptr.is_null() || out_len.is_null() {
        return JPDFIUM_ERR_GENERIC;
    }
    *out_ptr = std::ptr::null_mut();
    *out_len = 0;
    if input.is_null() || input_len == 0 || page_index < 0 {
        return JPDFIUM_ERR_GENERIC;
    }
    let data = slice::from_raw_parts(input, input_len);
    let payload = match gradients::page_gradients(data, page_index) {
        Some(payload) => payload,
        // No shadings on this page is a normal, expected outcome.
        None => return JPDFIUM_OK,
    };
    let ptr = malloc_copy(&payload);
    if ptr.is_null() {
        return JPDFIUM_ERR_GENERIC;
    }
    *out_ptr = ptr;
    *out_len = payload.len() as i64;
    JPDFIUM_OK
}

/// Extract the shadings of several pages from a PDF file on disk, parsing the
/// document once. `pages` is a zero-based index array; null or a count of zero
/// selects every page. The payload carries one `#PAGE <index>` section per page
/// that has shadings, and is freed with jpdfium_rust_free.
///
/// # Safety
/// `path` must be a valid NUL-terminated string. `pages` must point to
/// `page_count` i32 values, or be null.
#[no_mangle]
pub unsafe extern "C" fn jpdfium_page_gradients_file(
    path: *const c_char,
    pages: *const i32,
    page_count: i32,
    out_ptr: *mut *mut u8,
    out_len: *mut i64,
) -> i32 {
    if out_ptr.is_null() || out_len.is_null() {
        return JPDFIUM_ERR_GENERIC;
    }
    *out_ptr = std::ptr::null_mut();
    *out_len = 0;
    if path.is_null() {
        return JPDFIUM_ERR_INVALID;
    }
    let Ok(path) = CStr::from_ptr(path).to_str() else {
        return JPDFIUM_ERR_INVALID;
    };
    let selection = if pages.is_null() || page_count <= 0 {
        None
    } else {
        Some(slice::from_raw_parts(pages, page_count as usize))
    };
    let Ok(file) = std::fs::File::open(path) else {
        return JPDFIUM_ERR_GENERIC;
    };
    // A document without shadings is a normal, expected outcome.
    let payload = match gradients::page_gradients_reader(std::io::BufReader::new(file), selection) {
        Some(payload) => payload,
        None => return JPDFIUM_OK,
    };
    let ptr = malloc_copy(&payload);
    if ptr.is_null() {
        return JPDFIUM_ERR_GENERIC;
    }
    *out_ptr = ptr;
    *out_len = payload.len() as i64;
    JPDFIUM_OK
}

/// Free a buffer previously allocated by any jpdfium_rust_* function.
///
/// Safe to call with a null pointer (no-op).
///
/// # Safety
/// `ptr` must be null or a pointer returned by jpdfium_rust_compress_pdf,
/// jpdfium_rust_repair_lopdf, jpdfium_rust_resize_pixels, or
/// jpdfium_rust_compress_png.
#[no_mangle]
pub unsafe extern "C" fn jpdfium_rust_free(ptr: *mut u8) {
    if !ptr.is_null() {
        libc::free(ptr as *mut libc::c_void);
    }
}
