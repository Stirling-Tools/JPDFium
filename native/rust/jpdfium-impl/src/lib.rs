// jpdfium-impl - Rust-powered PDF processing functions for JPDFium.
//
// Provides four C-ABI functions compiled into libjpdfium_rust.a and linked
// directly into libjpdfium.so as first-class, always-available features:
//
//   jpdfium_rust_compress_pdf   - lopdf + zopfli superior DEFLATE, flate2 fallback decompression
//   jpdfium_rust_repair_lopdf   - lopdf tolerant XRef rebuild / repair
//   jpdfium_rust_resize_pixels  - fast_image_resize SIMD pixel scaling (Lanczos3)
//   jpdfium_rust_compress_png   - oxipng lossless PNG optimisation
//   jpdfium_rust_free           - free buffers allocated by any of the above
//
// All functions use libc::malloc for output allocation so the Java side can
// free them with jpdfium_rust_free regardless of Rust's internal allocator.

use std::io::{Cursor, Read};
use std::slice;

use fast_image_resize as fir;
use fir::images::Image as FirImage;
use flate2::read::ZlibDecoder;
use lopdf::Document;
use zopfli::{Format, Options, compress as zopfli_compress};

// Return codes matching jpdfium.h / jpdfium_rust.h
const JPDFIUM_OK: i32 = 0;
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

fn compress_pdf_impl(
    input: &[u8],
    iterations: u32,
) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
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
                stream.dict.set(
                    "Filter",
                    lopdf::Object::Name(b"FlateDecode".to_vec()),
                );
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
    let src_image =
        FirImage::from_slice_u8(src_w, src_h, &mut src_buf, pixel_type)?;
    let mut dst_image = FirImage::new(dst_w, dst_h, pixel_type);

    let mut resizer = fir::Resizer::new();
    let options = fir::ResizeOptions::new()
        .resize_alg(fir::ResizeAlg::Convolution(fir::FilterType::Lanczos3));
    resizer.resize(&src_image, &mut dst_image, Some(&options))?;

    Ok(dst_image.into_vec())
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
        Ok(_) => JPDFIUM_ERR_GENERIC, // already optimal
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
    let tree = match usvg::Tree::from_data(data, &usvg::Options::default()) {
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

    // Render straight into a malloc'd buffer: PixmapMut borrows the exact
    // memory the caller will free with jpdfium_rust_free, so there is no
    // intermediate Vec and no second copy. Zero it first (PixmapMut over
    // uninitialised memory would be UB).
    let byte_len = target_w as usize * target_h as usize * 4;
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
