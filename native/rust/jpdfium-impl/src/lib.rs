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
//   jpdfium_rust_zlib_compress  - streaming zlib for PDF /FlateDecode streams
//   jpdfium_rust_decode_png     - PNG decoding for jpdf_image_load_file
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


/// Unpack raw samples into opaque ARGB u32s. Formats: 1=gray8, 2=rgb24, 3=cmyk32, 4=bgr24, 5=bgrx/bgra.
///
/// # Safety
///
/// `src` must be readable for `src_stride * (height - 1) + width * bpp` bytes,
/// where `bpp` is the per-format bytes-per-pixel; `dst` must be writable for
/// `width * height` u32s.
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
                let s = slice::from_raw_parts(src.add(y * stride), w * 3);
                let d = slice::from_raw_parts_mut(dst.add(y * w), w);
                for (x, di) in d.iter_mut().enumerate() {
                    let o = x * 3;
                    *di = 0xFF00_0000
                        | ((s[o] as u32) << 16)
                        | ((s[o + 1] as u32) << 8)
                        | (s[o + 2] as u32);
                }
            }
        }
        3 => {
            for y in 0..h {
                let s = slice::from_raw_parts(src.add(y * stride), w * 4);
                let d = slice::from_raw_parts_mut(dst.add(y * w), w);
                for (x, di) in d.iter_mut().enumerate() {
                    let o = x * 4;
                    let k = s[o + 3] as u32;
                    let c = 255 - (s[o] as u32 + k).min(255);
                    let m = 255 - (s[o + 1] as u32 + k).min(255);
                    let yy = 255 - (s[o + 2] as u32 + k).min(255);
                    *di = 0xFF00_0000 | (c << 16) | (m << 8) | yy;
                }
            }
        }
        4 => {
            for y in 0..h {
                let s = slice::from_raw_parts(src.add(y * stride), w * 3);
                let d = slice::from_raw_parts_mut(dst.add(y * w), w);
                for (x, di) in d.iter_mut().enumerate() {
                    let o = x * 3;
                    *di = 0xFF00_0000
                        | ((s[o + 2] as u32) << 16)
                        | ((s[o + 1] as u32) << 8)
                        | (s[o] as u32);
                }
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

/// Sink for streamed output: returns 0 to continue, anything else aborts the encode.
pub type JpdfiumWriteSink =
    unsafe extern "C" fn(ctx: *mut libc::c_void, data: *const u8, len: usize) -> i32;

const PNG_OK: i32 = 0;
const PNG_ERR_INVALID: i32 = -1;
const PNG_ERR_SINK: i32 = -2;
const PNG_ERR_INTERNAL: i32 = -3;
// IDAT payload per chunk; bounds the encoder's buffering independently of image size.
const PNG_IDAT_CHUNK: usize = 256 * 1024;

struct SinkWriter {
    sink: JpdfiumWriteSink,
    ctx: *mut libc::c_void,
    aborted: bool,
}

impl SinkWriter {
    fn emit(&mut self, data: &[u8]) -> std::io::Result<()> {
        if self.aborted || unsafe { (self.sink)(self.ctx, data.as_ptr(), data.len()) } != 0 {
            self.aborted = true;
            return Err(std::io::Error::other("sink aborted"));
        }
        Ok(())
    }

    fn chunk(&mut self, kind: &[u8; 4], payload: &[u8]) -> std::io::Result<()> {
        let mut crc = flate2::Crc::new();
        crc.update(kind);
        crc.update(payload);
        self.emit(&(payload.len() as u32).to_be_bytes())?;
        self.emit(kind)?;
        self.emit(payload)?;
        self.emit(&crc.sum().to_be_bytes())
    }
}

/// Buffers compressed bytes and flushes them to the sink as bounded IDAT chunks.
struct IdatWriter<'a> {
    out: &'a mut SinkWriter,
    buf: Vec<u8>,
}

impl std::io::Write for IdatWriter<'_> {
    fn write(&mut self, data: &[u8]) -> std::io::Result<usize> {
        self.buf.extend_from_slice(data);
        while self.buf.len() >= PNG_IDAT_CHUNK {
            // Emit the chunk from the existing buffer and drop the prefix in place, so the
            // large capacity is retained instead of being reallocated on every chunk.
            self.out.chunk(b"IDAT", &self.buf[..PNG_IDAT_CHUNK])?;
            self.buf.drain(..PNG_IDAT_CHUNK);
        }
        Ok(data.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        if !self.buf.is_empty() {
            self.out.chunk(b"IDAT", &self.buf)?;
            self.buf.clear();
        }
        Ok(())
    }
}

fn paeth(a: u8, b: u8, c: u8) -> u8 {
    let p = a as i16 + b as i16 - c as i16;
    let (pa, pb, pc) = ((p - a as i16).abs(), (p - b as i16).abs(), (p - c as i16).abs());
    if pa <= pb && pa <= pc {
        a
    } else if pb <= pc {
        b
    } else {
        c
    }
}

/// Filters one row with each PNG filter and keeps the smallest absolute sum (libpng's heuristic).
fn filter_row(row: &[u8], prev: &[u8], bpp: usize, out: &mut Vec<u8>, scratch: &mut [Vec<u8>; 5]) {
    for (kind, dst) in scratch.iter_mut().enumerate() {
        dst.clear();
        for i in 0..row.len() {
            let a = if i >= bpp { row[i - bpp] } else { 0 };
            let b = prev[i];
            let c = if i >= bpp { prev[i - bpp] } else { 0 };
            let predicted = match kind {
                0 => 0,
                1 => a,
                2 => b,
                3 => ((a as u16 + b as u16) / 2) as u8,
                _ => paeth(a, b, c),
            };
            dst.push(row[i].wrapping_sub(predicted));
        }
    }
    let cost = |v: &Vec<u8>| v.iter().map(|&x| (x as i8).unsigned_abs() as u64).sum::<u64>();
    let best = (0..5).min_by_key(|&k| cost(&scratch[k])).unwrap_or(0);
    out.push(best as u8);
    out.extend_from_slice(&scratch[best]);
}

fn encode_png(
    pixels: &[u8],
    width: usize,
    height: usize,
    stride: usize,
    channels: usize,
    ppm: (u32, u32),
    level: u32,
    out: &mut SinkWriter,
) -> std::io::Result<()> {
    out.emit(b"\x89PNG\r\n\x1a\n")?;
    // channels 0 is 1-bit grayscale, packed most significant bit first, 1 = white.
    let color_type: u8 = match channels {
        0 | 1 => 0,
        3 => 2,
        _ => 6,
    };
    let bit_depth: u8 = if channels == 0 { 1 } else { 8 };
    let mut ihdr = Vec::with_capacity(13);
    ihdr.extend_from_slice(&(width as u32).to_be_bytes());
    ihdr.extend_from_slice(&(height as u32).to_be_bytes());
    ihdr.extend_from_slice(&[bit_depth, color_type, 0, 0, 0]);
    out.chunk(b"IHDR", &ihdr)?;
    if ppm.0 > 0 && ppm.1 > 0 {
        let mut phys = Vec::with_capacity(9);
        phys.extend_from_slice(&ppm.0.to_be_bytes());
        phys.extend_from_slice(&ppm.1.to_be_bytes());
        phys.push(1);
        out.chunk(b"pHYs", &phys)?;
    }
    let row_len = packed_row_len(width, channels);
    let zero_row = vec![0u8; row_len];
    let mut filtered = Vec::with_capacity(row_len + 1);
    let mut scratch: [Vec<u8>; 5] = Default::default();
    let mut encoder = flate2::write::ZlibEncoder::new(
        IdatWriter { out: &mut *out, buf: Vec::with_capacity(PNG_IDAT_CHUNK) },
        flate2::Compression::new(level),
    );
    for y in 0..height {
        let row = &pixels[y * stride..y * stride + row_len];
        let prev = if y == 0 { &zero_row[..] } else { &pixels[(y - 1) * stride..(y - 1) * stride + row_len] };
        filtered.clear();
        filter_row(row, prev, channels.max(1), &mut filtered, &mut scratch);
        std::io::Write::write_all(&mut encoder, &filtered)?;
    }
    let mut idat = encoder.finish()?;
    std::io::Write::flush(&mut idat)?;
    out.chunk(b"IEND", &[])
}

fn packed_row_len(width: usize, channels: usize) -> usize {
    if channels == 0 {
        width.div_ceil(8)
    } else {
        width * channels
    }
}

/// Streams 1-bit gray (0), 8-bit gray (1), RGB (3) or straight RGBA (4) pixels as PNG.
///
/// `ppm_x`/`ppm_y` > 0 add a pHYs chunk. Returns 0, -1 invalid args, -2 sink abort, -3 internal.
///
/// # Safety
/// `pixels` must hold `stride * height` readable bytes; `sink` must be a valid callback.
#[no_mangle]
pub unsafe extern "C" fn jpdfium_rust_write_png(
    pixels: *const u8,
    width: i32,
    height: i32,
    stride: i32,
    channels: i32,
    ppm_x: u32,
    ppm_y: u32,
    compression: i32,
    sink: Option<JpdfiumWriteSink>,
    ctx: *mut libc::c_void,
) -> i32 {
    let Some(sink) = sink else { return PNG_ERR_INVALID };
    if pixels.is_null() || width <= 0 || height <= 0 || !matches!(channels, 0 | 1 | 3 | 4) {
        return PNG_ERR_INVALID;
    }
    let (w, h, s, ch) = (width as usize, height as usize, stride as usize, channels as usize);
    if w.checked_mul(ch.max(1)).is_none() {
        return PNG_ERR_INVALID;
    }
    let row_len = packed_row_len(w, ch);
    if stride <= 0 || s < row_len {
        return PNG_ERR_INVALID;
    }
    let Some(total) = s.checked_mul(h) else { return PNG_ERR_INVALID };
    let level = if (0..=9).contains(&compression) { compression as u32 } else { 6 };
    let data = slice::from_raw_parts(pixels, total);
    let mut out = SinkWriter { sink, ctx, aborted: false };
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        encode_png(data, w, h, s, ch, (ppm_x, ppm_y), level, &mut out)
    }));
    match result {
        Ok(Ok(())) => PNG_OK,
        Ok(Err(_)) if out.aborted => PNG_ERR_SINK,
        _ => PNG_ERR_INTERNAL,
    }
}

impl std::io::Write for SinkWriter {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        self.emit(buf)?;
        Ok(buf.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

/// Streams `data` as a zlib (PDF /FlateDecode) stream through `sink`. `level` 0-9, else 6.
/// Returns 0, -1 invalid args, -2 sink abort, -3 internal.
///
/// # Safety
/// `data` must hold `len` readable bytes (it may be null when `len` is 0); `sink` must be valid.
#[no_mangle]
pub unsafe extern "C" fn jpdfium_rust_zlib_compress(
    data: *const u8,
    len: usize,
    level: i32,
    sink: Option<JpdfiumWriteSink>,
    ctx: *mut libc::c_void,
) -> i32 {
    use std::io::Write;
    let Some(sink) = sink else { return PNG_ERR_INVALID };
    if data.is_null() && len != 0 {
        return PNG_ERR_INVALID;
    }
    let input: &[u8] = if len == 0 { &[] } else { slice::from_raw_parts(data, len) };
    let level = if (0..=9).contains(&level) { level as u32 } else { 6 };
    let mut out = SinkWriter { sink, ctx, aborted: false };
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut encoder = flate2::write::ZlibEncoder::new(&mut out, flate2::Compression::new(level));
        encoder.write_all(input)?;
        encoder.finish().map(|_| ())
    }));
    match result {
        Ok(Ok(())) => PNG_OK,
        Ok(Err(_)) if out.aborted => PNG_ERR_SINK,
        _ => PNG_ERR_INTERNAL,
    }
}

/// Allocates `size` bytes for decoded pixels; null refuses the allocation.
pub type JpdfiumAllocFn = unsafe extern "C" fn(ctx: *mut libc::c_void, size: usize) -> *mut u8;

/// A decoded image: tightly packed rows of `channels` (1 gray, 3 RGB) bytes per pixel.
#[repr(C)]
pub struct JpdfiumDecodedImage {
    pub width: u32,
    pub height: u32,
    pub channels: u32,
    pub ppm_x: u32,
    pub ppm_y: u32,
    pub pixels: *mut u8,
}

const DECODE_OK: i32 = 0;
const DECODE_ERR_FORMAT: i32 = -1;
const DECODE_ERR_ALLOC: i32 = -2;
const DECODE_ERR_INTERNAL: i32 = -3;
const DECODE_ERR_LIMIT: i32 = -4;

fn decode_png(
    data: &[u8],
    max_pixels: u64,
    alloc: JpdfiumAllocFn,
    ctx: *mut libc::c_void,
    out: &mut JpdfiumDecodedImage,
) -> i32 {
    let mut decoder = png::Decoder::new(Cursor::new(data));
    // Bound the decoder's cumulative allocation to the accepted frame budget plus headroom for
    // chunk metadata instead of disabling the crate's default limit: read_info() parses
    // attacker-controlled chunks (e.g. iCCP/zTXt) before max_pixels is enforced.
    let cap = max_pixels.saturating_mul(8).saturating_add(64 << 20).min(usize::MAX as u64) as usize;
    decoder.set_limits(png::Limits { bytes: cap });
    // Expand palettes and low bit depths and keep the high byte of 16-bit samples, as OpenCV's
    // IMREAD_COLOR does through libpng.
    decoder.set_transformations(png::Transformations::EXPAND | png::Transformations::STRIP_16);
    let Ok(mut reader) = decoder.read_info() else { return DECODE_ERR_FORMAT };
    let (width, height) = (reader.info().width, reader.info().height);
    if width == 0 || height == 0 {
        return DECODE_ERR_FORMAT;
    }
    if (width as u64) * (height as u64) > max_pixels {
        return DECODE_ERR_LIMIT;
    }
    let (ppm_x, ppm_y) = match reader.info().pixel_dims {
        Some(d) if d.unit == png::Unit::Meter => (d.xppu, d.yppu),
        _ => (0, 0),
    };
    let (color, depth) = reader.output_color_type();
    if depth != png::BitDepth::Eight {
        return DECODE_ERR_FORMAT;
    }
    let (in_channels, out_channels) = match color {
        png::ColorType::Grayscale => (1usize, 1usize),
        png::ColorType::GrayscaleAlpha => (2, 1),
        png::ColorType::Rgb => (3, 3),
        png::ColorType::Rgba => (4, 3),
        _ => return DECODE_ERR_FORMAT,
    };
    let (w, h) = (width as usize, height as usize);
    let Some(out_size) = w.checked_mul(h).and_then(|n| n.checked_mul(out_channels)) else {
        return DECODE_ERR_LIMIT;
    };
    let Some(frame_size) = reader.output_buffer_size() else { return DECODE_ERR_LIMIT };
    // When the channel count does not change, a de-interlaced output row is exactly
    // `w * out_channels` bytes, so the whole image is `out_size` and the decoder can write it
    // straight into the destination - no intermediate frame and no second pass. Interlaced
    // images whose channels are reduced still need one full output frame, because their rows
    // arrive in Adam7 pass order rather than row by row.
    let interlaced = reader.info().interlaced;
    let direct = in_channels == out_channels && frame_size == out_size;
    let mut frame = if direct { Vec::new() } else { vec![0u8; frame_size] };
    let pixels = unsafe { alloc(ctx, out_size) };
    if pixels.is_null() {
        return DECODE_ERR_ALLOC;
    }
    let dst = unsafe { slice::from_raw_parts_mut(pixels, out_size) };
    // Alpha is dropped without compositing, as IMREAD_COLOR does.
    let decoded = if direct {
        reader.next_frame(dst).is_ok()
    } else if in_channels == out_channels || interlaced {
        match reader.next_frame(&mut frame) {
            Ok(info) => {
                for y in 0..h {
                    let src = &frame[y * info.line_size..y * info.line_size + w * in_channels];
                    let row = &mut dst[y * w * out_channels..(y + 1) * w * out_channels];
                    convert_row(src, row, in_channels, out_channels);
                }
                true
            }
            Err(_) => false,
        }
    } else {
        // Non-interlaced channel reduction: consume one full-width output row at a time and
        // convert it straight into the destination, skipping the full-frame allocation.
        let mut y = 0usize;
        while y < h {
            match reader.next_row() {
                Ok(Some(row)) => {
                    let out = &mut dst[y * w * out_channels..(y + 1) * w * out_channels];
                    convert_row(&row.data()[..w * in_channels], out, in_channels, out_channels);
                    y += 1;
                }
                Ok(None) => break,
                Err(_) => return DECODE_ERR_FORMAT,
            }
        }
        y == h
    };
    if !decoded {
        return DECODE_ERR_FORMAT;
    }
    *out = JpdfiumDecodedImage {
        width,
        height,
        channels: out_channels as u32,
        ppm_x,
        ppm_y,
        pixels,
    };
    DECODE_OK
}

// Copies one decoded output row into the destination, dropping the alpha channel when present.
// The per-layout branches let the compiler emit wide loads/stores; the generic `chunks_exact`
// iterator used before does not vectorize here and costs several milliseconds over a full image.
#[inline]
fn convert_row(src: &[u8], row: &mut [u8], in_channels: usize, out_channels: usize) {
    match (in_channels, out_channels) {
        (1, 1) | (3, 3) => row.copy_from_slice(&src[..row.len()]),
        (4, 3) => {
            let n = row.len() / 3;
            for i in 0..n {
                let v = u32::from_le_bytes([
                    src[i * 4],
                    src[i * 4 + 1],
                    src[i * 4 + 2],
                    src[i * 4 + 3],
                ]);
                let b = v.to_le_bytes();
                row[i * 3] = b[0];
                row[i * 3 + 1] = b[1];
                row[i * 3 + 2] = b[2];
            }
        }
        (2, 1) => {
            for (i, o) in row.iter_mut().enumerate() {
                *o = src[i * 2];
            }
        }
        _ => {
            for (o, i) in row.chunks_exact_mut(out_channels).zip(src.chunks_exact(in_channels)) {
                o.copy_from_slice(&i[..out_channels]);
            }
        }
    }
}

/// Decodes a PNG held in memory into pixels obtained from `alloc`. Returns 0, -1 not a supported
/// PNG, -2 allocation refused, -3 internal, -4 over `max_pixels`.
///
/// # Safety
/// `data` must hold `len` readable bytes; `alloc` must return null or `size` writable bytes;
/// `out` must be valid for writes.
#[no_mangle]
pub unsafe extern "C" fn jpdfium_rust_decode_png(
    data: *const u8,
    len: usize,
    max_pixels: u64,
    alloc: Option<JpdfiumAllocFn>,
    ctx: *mut libc::c_void,
    out: *mut JpdfiumDecodedImage,
) -> i32 {
    let Some(alloc) = alloc else { return DECODE_ERR_FORMAT };
    if data.is_null() || len == 0 || out.is_null() {
        return DECODE_ERR_FORMAT;
    }
    let input = slice::from_raw_parts(data, len);
    let out = &mut *out;
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        decode_png(input, max_pixels, alloc, ctx, out)
    })) {
        Ok(code) => code,
        Err(_) => DECODE_ERR_INTERNAL,
    }
}
