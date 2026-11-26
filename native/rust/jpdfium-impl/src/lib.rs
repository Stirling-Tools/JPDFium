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

use std::io::{Cursor, Read};
use std::slice;

use std::cell::RefCell;

thread_local! {
    /// Reused encoding buffer. JPEG output is copied into a libc allocation
    /// for the C ABI, but the scratch Vec keeps its capacity between images.
    static CMYK_JPEG_BUFFER: RefCell<Vec<u8>> = const { RefCell::new(Vec::new()) };
}

use fast_image_resize as fir;
use fir::images::Image as FirImage;
use flate2::read::{DeflateDecoder, ZlibDecoder};
use lopdf::Document;
use zopfli::{compress as zopfli_compress, Format, Options};

// Return codes matching jpdfium.h / jpdfium_rust.h
const JPDFIUM_OK: i32 = 0;
const JPDFIUM_ERR_INVALID: i32 = -1;
const JPDFIUM_ERR_GENERIC: i32 = -1;
/// Native output allocation failed (matches jpdfium.h JPDFIUM_ERR_ALLOC).
const JPDFIUM_ERR_ALLOC: i32 = -12;
const JPDFIUM_REPAIR_FIXED: i32 = 1;
const JPDFIUM_REPAIR_FAILED: i32 = -1;
const MAX_DECOMPRESSED_STREAM_BYTES: usize = 64 * 1024 * 1024;
const MAX_TOTAL_DECOMPRESSED_BYTES: usize = 256 * 1024 * 1024;
const MAX_IMAGE_DIM: u32 = 16384;
const MAX_IMAGE_PIXELS: u64 = (MAX_IMAGE_DIM as u64) * (MAX_IMAGE_DIM as u64);
const MAX_RESIZE_BYTES: u64 = MAX_IMAGE_PIXELS * 4;
const MAX_PNG_INPUT_BYTES: u64 = 128 * 1024 * 1024;

struct MallocGuard(*mut u8);

impl Drop for MallocGuard {
    fn drop(&mut self) {
        if !self.0.is_null() {
            unsafe { libc::free(self.0 as *mut libc::c_void) };
        }
    }
}

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

fn read_bounded<R: Read>(mut reader: R, max_bytes: usize) -> Option<Vec<u8>> {
    let mut output = Vec::new();
    let limit = (max_bytes as u64).saturating_add(1);
    if reader
        .by_ref()
        .take(limit)
        .read_to_end(&mut output)
        .is_err()
    {
        return None;
    }
    if output.len() > max_bytes {
        return None;
    }
    Some(output)
}

/// Decompress ZLib (FlateDecode) bytes using flate2, falling back to raw
/// DEFLATE when the zlib wrapper or Adler-32 trailer is corrupt or absent.
fn flate2_decompress_zlib(data: &[u8]) -> Option<Vec<u8>> {
    if let Some(output) = read_bounded(ZlibDecoder::new(data), MAX_DECOMPRESSED_STREAM_BYTES) {
        return Some(output);
    }
    if let Some(output) = read_bounded(DeflateDecoder::new(data), MAX_DECOMPRESSED_STREAM_BYTES) {
        if !output.is_empty() {
            return Some(output);
        }
    }
    if data.len() >= 6 && (data[0] & 0x0F) == 8 {
        let header = ((data[0] as u16) << 8) | (data[1] as u16);
        if header.is_multiple_of(31) {
            if let Some(output) = read_bounded(
                DeflateDecoder::new(&data[2..]),
                MAX_DECOMPRESSED_STREAM_BYTES,
            ) {
                if !output.is_empty() {
                    return Some(output);
                }
            }
        }
    }
    None
}

fn check_decompressed_size_budget(doc: &Document) -> Result<(), Box<dyn std::error::Error>> {
    let mut total_bytes: usize = 0;
    for obj in doc.objects.values() {
        let lopdf::Object::Stream(stream) = obj else {
            continue;
        };
        if stream.content.is_empty() || !is_plain_flate_filter(&stream.dict) {
            continue;
        }
        let mut decoder = ZlibDecoder::new(stream.content.as_slice());
        let mut buf = [0u8; 8192];
        let mut stream_bytes: usize = 0;
        loop {
            match decoder.read(&mut buf) {
                Ok(0) => break,
                Ok(n) => {
                    stream_bytes = stream_bytes.saturating_add(n);
                    if stream_bytes > MAX_DECOMPRESSED_STREAM_BYTES {
                        return Err("decompressed stream exceeds per-stream limit".into());
                    }
                    total_bytes = total_bytes.saturating_add(n);
                    if total_bytes > MAX_TOTAL_DECOMPRESSED_BYTES {
                        return Err("decompressed document exceeds total byte limit".into());
                    }
                }
                Err(_) => break,
            }
        }
    }
    Ok(())
}

fn is_plain_flate_filter(dict: &lopdf::Dictionary) -> bool {
    match dict.get(b"Filter") {
        Ok(lopdf::Object::Name(name)) => name == b"FlateDecode",
        Ok(lopdf::Object::Array(arr)) if arr.len() == 1 => {
            matches!(arr.first(), Some(lopdf::Object::Name(name)) if name == b"FlateDecode")
        }
        _ => false,
    }
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
    if out_ptr.is_null() || out_len.is_null() {
        return JPDFIUM_ERR_GENERIC;
    }
    *out_ptr = std::ptr::null_mut();
    *out_len = 0;

    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if input.is_null() || input_len <= 0 || (input_len as u64) > (isize::MAX as u64) {
            return JPDFIUM_ERR_GENERIC;
        }

        let input_slice = slice::from_raw_parts(input, input_len as usize);
        let iterations = zopfli_iters.max(1) as u32;

        match compress_pdf_impl(input_slice, iterations) {
            Ok(bytes) => {
                let ptr = malloc_copy(&bytes);
                if ptr.is_null() {
                    return JPDFIUM_ERR_ALLOC;
                }
                *out_ptr = ptr;
                *out_len = bytes.len() as i64;
                JPDFIUM_OK
            }
            Err(_) => JPDFIUM_ERR_GENERIC,
        }
    }))
    .unwrap_or(JPDFIUM_ERR_GENERIC)
}

fn compress_pdf_impl(input: &[u8], iterations: u32) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
    let mut doc = Document::load_from(Cursor::new(input))?;

    // Reject Flate bombs before lopdf allocates unbounded buffers inside decompress().
    check_decompressed_size_budget(&doc)?;

    // lopdf's decompress() uses flate2 internally for all FlateDecode streams.
    doc.decompress();

    for (_, obj) in doc.objects.iter_mut() {
        if let lopdf::Object::Stream(ref mut stream) = obj {
            if stream.content.is_empty() || !stream.allows_compression {
                continue;
            }
            if matches!(
                stream.dict.get(b"Type"),
                Ok(lopdf::Object::Name(ref name)) if name == b"Metadata"
            ) {
                continue;
            }

            // After doc.decompress(), streams that were successfully decoded
            // no longer have a Filter entry. Streams that lopdf could NOT
            // decode still have Filter set.
            let still_filtered = stream.dict.get(b"Filter").is_ok();

            // Obtain raw (uncompressed) content as an owned Vec to avoid borrow
            // conflicts when we later mutate stream.content.
            let raw: Vec<u8> = if still_filtered {
                if !is_plain_flate_filter(&stream.dict) {
                    continue;
                }
                // lopdf left this Flate stream compressed - try tolerant flate2/raw-deflate fallback.
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
                // Keep the original compressed bytes intact rather than writing
                // uncompressed bytes under an active /FlateDecode filter.
                continue;
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
    if out_ptr.is_null() || out_len.is_null() {
        return JPDFIUM_REPAIR_FAILED;
    }
    *out_ptr = std::ptr::null_mut();
    *out_len = 0;

    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if input.is_null() || input_len <= 0 || (input_len as u64) > (isize::MAX as u64) {
            return JPDFIUM_REPAIR_FAILED;
        }

        let input_slice = slice::from_raw_parts(input, input_len as usize);

        // Rewriting a signed document always breaks its /ByteRange digest. The
        // Java repair pipeline refuses marker inputs before reaching this
        // fallback, and the qpdf routes verify parsed signature dictionaries;
        // this raw scan keeps direct native callers of the lopdf fallback from
        // silently invalidating signatures.
        const BYTE_RANGE: &[u8] = b"/ByteRange";
        if input_slice
            .windows(BYTE_RANGE.len())
            .any(|w| w == BYTE_RANGE)
        {
            return JPDFIUM_REPAIR_FAILED;
        }

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
    }))
    .unwrap_or(JPDFIUM_REPAIR_FAILED)
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
    if out_ptr.is_null() || out_len.is_null() {
        return JPDFIUM_ERR_GENERIC;
    }
    *out_ptr = std::ptr::null_mut();
    *out_len = 0;

    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if src_pixels.is_null()
            || src_len <= 0
            || (src_len as u64) > (isize::MAX as u64)
            || src_width <= 0
            || src_height <= 0
            || dst_width <= 0
            || dst_height <= 0
            || (src_width as u32) > MAX_IMAGE_DIM
            || (src_height as u32) > MAX_IMAGE_DIM
            || (dst_width as u32) > MAX_IMAGE_DIM
            || (dst_height as u32) > MAX_IMAGE_DIM
            || !(1..=4).contains(&components)
        {
            return JPDFIUM_ERR_INVALID;
        }

        let Some(src_pixels_count) = (src_width as u64).checked_mul(src_height as u64) else {
            return JPDFIUM_ERR_INVALID;
        };
        let Some(expected_len) = src_pixels_count.checked_mul(components as u64) else {
            return JPDFIUM_ERR_INVALID;
        };
        if src_pixels_count > MAX_IMAGE_PIXELS
            || expected_len > MAX_RESIZE_BYTES
            || (src_len as u64) < expected_len
            || expected_len > (isize::MAX as u64)
        {
            return JPDFIUM_ERR_INVALID;
        }

        let Some(dst_pixels_count) = (dst_width as u64).checked_mul(dst_height as u64) else {
            return JPDFIUM_ERR_INVALID;
        };
        let Some(dst_bytes) = dst_pixels_count.checked_mul(components as u64) else {
            return JPDFIUM_ERR_INVALID;
        };
        if dst_pixels_count > MAX_IMAGE_PIXELS
            || dst_bytes > MAX_RESIZE_BYTES
            || dst_bytes > (isize::MAX as u64)
        {
            return JPDFIUM_ERR_INVALID;
        }

        let src_slice = slice::from_raw_parts(src_pixels, expected_len as usize);

        let pixel_type = match components {
            1 => fir::PixelType::U8,
            3 => fir::PixelType::U8x3,
            4 => fir::PixelType::U8x4,
            _ => return JPDFIUM_ERR_INVALID,
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
                    return JPDFIUM_ERR_ALLOC;
                }
                *out_ptr = ptr;
                *out_len = bytes.len() as i64;
                JPDFIUM_OK
            }
            Err(_) => JPDFIUM_ERR_INVALID,
        }
    }))
    .unwrap_or(JPDFIUM_ERR_INVALID)
}

fn resize_pixels_impl(
    src: &[u8],
    src_w: u32,
    src_h: u32,
    dst_w: u32,
    dst_h: u32,
    pixel_type: fir::PixelType,
) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
    if src_w == 0
        || src_h == 0
        || dst_w == 0
        || dst_h == 0
        || src_w > MAX_IMAGE_DIM
        || src_h > MAX_IMAGE_DIM
        || dst_w > MAX_IMAGE_DIM
        || dst_h > MAX_IMAGE_DIM
    {
        return Err("dimensions out of bounds".into());
    }
    let components = match pixel_type {
        fir::PixelType::U8 => 1u64,
        fir::PixelType::U8x3 => 3u64,
        fir::PixelType::U8x4 => 4u64,
        _ => return Err("unsupported pixel type".into()),
    };
    let src_pixels = (src_w as u64)
        .checked_mul(src_h as u64)
        .ok_or("source pixel count overflow")?;
    let src_bytes = src_pixels
        .checked_mul(components)
        .ok_or("source byte size overflow")?;
    let dst_pixels = (dst_w as u64)
        .checked_mul(dst_h as u64)
        .ok_or("destination pixel count overflow")?;
    let dst_bytes = dst_pixels
        .checked_mul(components)
        .ok_or("destination byte size overflow")?;
    if src_pixels > MAX_IMAGE_PIXELS
        || src_bytes > MAX_RESIZE_BYTES
        || dst_pixels > MAX_IMAGE_PIXELS
        || dst_bytes > MAX_RESIZE_BYTES
        || (src.len() as u64) < src_bytes
    {
        return Err("image buffer exceeds resize limits".into());
    }

    // from_slice_u8 requires &mut [u8]; clone only the exact source image bytes.
    let mut src_buf = src[..src_bytes as usize].to_vec();
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
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        unpack_pixels_impl(src, width, height, src_stride, format, dst, dst_len)
    }))
    .unwrap_or(JPDFIUM_ERR_GENERIC)
}

unsafe fn unpack_pixels_impl(
    src: *const u8,
    width: i32,
    height: i32,
    src_stride: i64,
    format: i32,
    dst: *mut u32,
    dst_len: i64,
) -> i32 {
    if src.is_null()
        || dst.is_null()
        || width <= 0
        || height <= 0
        || (width as u32) > MAX_IMAGE_DIM
        || (height as u32) > MAX_IMAGE_DIM
    {
        return JPDFIUM_ERR_INVALID;
    }
    let bpp: i64 = match format {
        1 => 1,
        2 | 4 => 3,
        3 | 5 => 4,
        _ => return JPDFIUM_ERR_INVALID,
    };
    let Some(pixels) = (width as i64).checked_mul(height as i64) else {
        return JPDFIUM_ERR_INVALID;
    };
    let Some(dst_bytes) = (pixels as u64).checked_mul(4) else {
        return JPDFIUM_ERR_INVALID;
    };
    if (pixels as u64) > MAX_IMAGE_PIXELS
        || dst_bytes > (isize::MAX as u64)
        || dst_len < pixels
        || src_stride < (width as i64) * bpp
    {
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
    if out_ptr.is_null() || out_len.is_null() {
        return JPDFIUM_ERR_GENERIC;
    }
    *out_ptr = std::ptr::null_mut();
    *out_len = 0;

    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if input.is_null()
            || input_len <= 0
            || (input_len as u64) > MAX_PNG_INPUT_BYTES
            || (input_len as u64) > (isize::MAX as u64)
        {
            return JPDFIUM_ERR_INVALID;
        }

        let input_slice = slice::from_raw_parts(input, input_len as usize);
        const PNG_SIG: &[u8; 8] = b"\x89PNG\r\n\x1a\n";
        if input_slice.len() < 24 || &input_slice[..8] != PNG_SIG || &input_slice[12..16] != b"IHDR"
        {
            return JPDFIUM_ERR_INVALID;
        }
        let png_w = u32::from_be_bytes([
            input_slice[16],
            input_slice[17],
            input_slice[18],
            input_slice[19],
        ]);
        let png_h = u32::from_be_bytes([
            input_slice[20],
            input_slice[21],
            input_slice[22],
            input_slice[23],
        ]);
        if png_w == 0
            || png_h == 0
            || png_w > MAX_IMAGE_DIM
            || png_h > MAX_IMAGE_DIM
            || (png_w as u64) * (png_h as u64) > MAX_IMAGE_PIXELS
        {
            return JPDFIUM_ERR_INVALID;
        }

        let preset = level.clamp(0, 6) as u8;

        let options = oxipng::Options::from_preset(preset);
        match oxipng::optimize_from_memory(input_slice, &options) {
            Ok(optimized) if optimized.len() < input_slice.len() => {
                let ptr = malloc_copy(&optimized);
                if ptr.is_null() {
                    return JPDFIUM_ERR_ALLOC;
                }
                *out_ptr = ptr;
                *out_len = optimized.len() as i64;
                JPDFIUM_OK
            }
            Ok(_) => JPDFIUM_ERR_GENERIC,  // already optimal
            Err(_) => JPDFIUM_ERR_GENERIC, // not a valid PNG
        }
    }))
    .unwrap_or(JPDFIUM_ERR_GENERIC)
}

/// Encode 4-component DeviceCMYK sample data as a CMYK JPEG byte stream.
///
/// `cmyk` must point to `cmyk_len` bytes holding `width * height * 4` samples
/// in standard DeviceCMYK order, one byte per channel, where 0 means no ink.
/// The encoder writes an Adobe APP14 marker with transform=0 and stores the
/// samples inverted (255 - value), matching the `/Decode [1 0 1 0 1 0 1 0]`
/// entry PDFium attaches when it re-embeds a 4-component JPEG - so the values
/// round-trip through PDFium's image pipeline back to the intended ink levels.
///
/// Returns 0 and fills out_ptr/out_len on success. The output is allocated
/// with libc::malloc and must be freed with jpdfium_rust_free.
///
/// # Safety
/// `cmyk` must point to `cmyk_len` readable bytes; the out pointers must be valid.
#[no_mangle]
pub unsafe extern "C" fn jpdfium_rust_encode_cmyk_jpeg(
    cmyk: *const u8,
    cmyk_len: i64,
    width: i32,
    height: i32,
    quality: i32,
    out_ptr: *mut *mut u8,
    out_len: *mut i64,
) -> i32 {
    if out_ptr.is_null() || out_len.is_null() {
        return JPDFIUM_ERR_INVALID;
    }
    *out_ptr = std::ptr::null_mut();
    *out_len = 0;

    // Contain a panic so it cannot unwind across the C ABI boundary.
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        encode_cmyk_jpeg(cmyk, cmyk_len, width, height, quality, out_ptr, out_len)
    }))
    .unwrap_or(JPDFIUM_ERR_GENERIC)
}

/// Encoding body, separated so the exported wrapper can contain panics.
///
/// # Safety
/// `cmyk` must point to `cmyk_len` readable bytes; the out pointers must be valid.
unsafe fn encode_cmyk_jpeg(
    cmyk: *const u8,
    cmyk_len: i64,
    width: i32,
    height: i32,
    quality: i32,
    out_ptr: *mut *mut u8,
    out_len: *mut i64,
) -> i32 {
    if cmyk.is_null() || width <= 0 || height <= 0 || width > 65535 || height > 65535 {
        return JPDFIUM_ERR_INVALID;
    }

    let needed = width as usize * height as usize * 4;
    if cmyk_len < 0 || needed > cmyk_len as usize {
        return JPDFIUM_ERR_INVALID;
    }

    let data = slice::from_raw_parts(cmyk, needed);
    let quality = quality.clamp(1, 100) as u8;

    let mut buffer = CMYK_JPEG_BUFFER.with(|cell| std::mem::take(&mut *cell.borrow_mut()));
    buffer.clear();
    let encoder = jpeg_encoder::Encoder::new(&mut buffer, quality);
    let encoded = encoder
        .encode(
            data,
            width as u16,
            height as u16,
            jpeg_encoder::ColorType::Cmyk,
        )
        .is_ok();
    if !encoded || buffer.is_empty() {
        CMYK_JPEG_BUFFER.with(|cell| *cell.borrow_mut() = buffer);
        return JPDFIUM_ERR_GENERIC;
    }

    let ptr = malloc_copy(&buffer);
    let len = buffer.len();
    let capacity = buffer.capacity();
    if capacity > 4 * 1024 * 1024 {
        // A single huge JPEG must not pin its capacity to a long-lived worker.
        CMYK_JPEG_BUFFER.with(|cell| *cell.borrow_mut() = Vec::new());
    } else {
        CMYK_JPEG_BUFFER.with(|cell| *cell.borrow_mut() = buffer);
    }
    if ptr.is_null() {
        return JPDFIUM_ERR_GENERIC;
    }
    *out_ptr = ptr;
    *out_len = len as i64;
    JPDFIUM_OK
}

/// Encode interleaved 8-bit gray (1 byte/px) or RGB (3 bytes/px) pixels as a
/// baseline JPEG with optimized Huffman tables. RGB uses 4:2:0 chroma.
///
/// The encoder uses optimized Huffman tables, trellis quantization and
/// deringing; its output can differ from ImageIO at the same quality setting.
///
/// # Safety
/// `pixels` must point to `pixels_len` readable bytes; the out pointers must be valid.
#[no_mangle]
pub unsafe extern "C" fn jpdfium_rust_encode_jpeg(
    pixels: *const u8,
    pixels_len: i64,
    width: i32,
    height: i32,
    components: i32,
    quality: i32,
    out_ptr: *mut *mut u8,
    out_len: *mut i64,
) -> i32 {
    jpdfium_rust_encode_jpeg_ex(
        pixels, pixels_len, width, height, components, quality, 0, out_ptr, out_len,
    )
}

/// As [`jpdfium_rust_encode_jpeg`], with a progressive-scan flag.
///
/// # Safety
/// `pixels` must point to `pixels_len` readable bytes; the out pointers must be valid.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn jpdfium_rust_encode_jpeg_ex(
    pixels: *const u8,
    pixels_len: i64,
    width: i32,
    height: i32,
    components: i32,
    quality: i32,
    progressive: i32,
    out_ptr: *mut *mut u8,
    out_len: *mut i64,
) -> i32 {
    if out_ptr.is_null() || out_len.is_null() {
        return JPDFIUM_ERR_INVALID;
    }
    *out_ptr = std::ptr::null_mut();
    *out_len = 0;

    // Contain a panic so it cannot unwind across the C ABI boundary.
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        encode_jpeg(
            pixels,
            pixels_len,
            width,
            height,
            components,
            quality,
            progressive != 0,
            out_ptr,
            out_len,
        )
    }))
    .unwrap_or(JPDFIUM_ERR_GENERIC)
}

/// Encoding body, separated so the exported wrapper can contain panics.
///
/// # Safety
/// `pixels` must point to `pixels_len` readable bytes; the out pointers must be valid.
#[allow(clippy::too_many_arguments)]
unsafe fn encode_jpeg(
    pixels: *const u8,
    pixels_len: i64,
    width: i32,
    height: i32,
    components: i32,
    quality: i32,
    progressive: bool,
    out_ptr: *mut *mut u8,
    out_len: *mut i64,
) -> i32 {
    let color = match components {
        1 => mozjpeg_rs::Subsampling::Gray,
        3 => mozjpeg_rs::Subsampling::S420,
        _ => return JPDFIUM_ERR_INVALID,
    };
    if pixels.is_null() || width <= 0 || height <= 0 || width > 65535 || height > 65535 {
        return JPDFIUM_ERR_INVALID;
    }

    let needed = width as usize * height as usize * components as usize;
    if pixels_len < 0 || needed > pixels_len as usize {
        return JPDFIUM_ERR_INVALID;
    }

    let data = slice::from_raw_parts(pixels, needed);
    let quality = quality.clamp(1, 100) as u8;

    // Baseline is the default for maximum reader compatibility; progressive
    // saves 4-5% more on scans. Both presets enable trellis quantization,
    // deringing and optimized Huffman tables.
    let preset = if progressive {
        mozjpeg_rs::Preset::ProgressiveBalanced
    } else {
        mozjpeg_rs::Preset::BaselineBalanced
    };
    let encoder = mozjpeg_rs::Encoder::new(preset)
        .quality(quality)
        .subsampling(color)
        .trellis(mozjpeg_rs::TrellisConfig::default())
        .optimize_huffman(true)
        .overshoot_deringing(true);
    let encoded = if components == 1 {
        encoder.encode_gray(data, width as u32, height as u32)
    } else {
        encoder.encode_rgb(data, width as u32, height as u32)
    };
    let buffer = match encoded {
        Ok(bytes) if !bytes.is_empty() => bytes,
        _ => return JPDFIUM_ERR_GENERIC,
    };

    let ptr = malloc_copy(&buffer);
    if ptr.is_null() {
        return JPDFIUM_ERR_ALLOC;
    }
    *out_ptr = ptr;
    *out_len = buffer.len() as i64;
    JPDFIUM_OK
}
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
    if out_ptr.is_null() || out_len.is_null() {
        return JPDFIUM_ERR_GENERIC;
    }
    *out_ptr = std::ptr::null_mut();
    *out_len = 0;
    if !out_w.is_null() {
        *out_w = 0;
    }
    if !out_h.is_null() {
        *out_h = 0;
    }
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        svg_to_rgba_impl(svg, svg_len, width, height, out_ptr, out_len, out_w, out_h)
    }))
    .unwrap_or(JPDFIUM_ERR_GENERIC)
}

#[allow(clippy::too_many_arguments)]
unsafe fn svg_to_rgba_impl(
    svg: *const u8,
    svg_len: usize,
    width: i32,
    height: i32,
    out_ptr: *mut *mut u8,
    out_len: *mut i64,
    out_w: *mut i32,
    out_h: *mut i32,
) -> i32 {
    if svg.is_null() || svg_len == 0 || svg_len > (isize::MAX as usize) {
        return JPDFIUM_ERR_GENERIC;
    }
    let data = slice::from_raw_parts(svg, svg_len);
    // Untrusted SVG: allow only inline data: images. The default string
    // resolver reads image files from the local filesystem, which would let a
    // crafted document embed host files into the raster.
    // usvg's default font database is empty, so <text> in an SVG would parse
    // but rasterize with no fonts at all. Load the system fonts once (shared,
    // read-only) instead of per conversion.
    let options = usvg::Options {
        fontdb: system_fontdb(),
        image_href_resolver: usvg::ImageHrefResolver {
            resolve_data: usvg::ImageHrefResolver::default_data_resolver(),
            resolve_string: Box::new(|_, _| None),
        },
        ..usvg::Options::default()
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
        return JPDFIUM_ERR_ALLOC;
    }
    let mut ptr_guard = MallocGuard(ptr);
    std::ptr::write_bytes(ptr, 0, byte_len);
    let buf = slice::from_raw_parts_mut(ptr, byte_len);
    let mut pixmap = match tiny_skia::PixmapMut::from_bytes(buf, target_w, target_h) {
        Some(pixmap) => pixmap,
        None => return JPDFIUM_ERR_GENERIC,
    };
    resvg::render(
        &tree,
        tiny_skia::Transform::from_scale(scale, scale),
        &mut pixmap,
    );
    // End the mutable borrow of `buf` before touching it again.
    let _ = pixmap;

    // tiny-skia stores premultiplied RGBA; the JVM and libvips want straight.
    for px in buf.chunks_exact_mut(4) {
        let a = px[3] as u32;
        if a != 0 && a != 255 {
            px[0] = ((px[0] as u32 * 255 + a / 2) / a) as u8;
            px[1] = ((px[1] as u32 * 255 + a / 2) / a) as u8;
            px[2] = ((px[2] as u32 * 255 + a / 2) / a) as u8;
        }
    }

    ptr_guard.0 = std::ptr::null_mut();
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

/// Encode a packed 1-bit image as CCITT Group 4 (T.6) for PDF
/// `/CCITTFaxDecode` with `/K -1`.
///
/// `pixels` is row-major, MSB-first, one bit per pixel. A set bit is white by
/// default (DeviceGray convention); `invert` flips that interpretation. The
/// caller supplies the final `/BlackIs1` value to match.
///
/// # Safety
/// `pixels` must point to `pixels_len` readable bytes; the out pointers must be valid.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn jpdfium_rust_encode_ccitt_g4(
    pixels: *const u8,
    pixels_len: i64,
    width: i32,
    height: i32,
    invert: i32,
    out_ptr: *mut *mut u8,
    out_len: *mut i64,
) -> i32 {
    if out_ptr.is_null() || out_len.is_null() {
        return JPDFIUM_ERR_INVALID;
    }
    *out_ptr = std::ptr::null_mut();
    *out_len = 0;
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        encode_ccitt_g4(
            pixels,
            pixels_len,
            width,
            height,
            invert != 0,
            out_ptr,
            out_len,
        )
    }))
    .unwrap_or(JPDFIUM_ERR_GENERIC)
}

/// Encoding body for [`jpdfium_rust_encode_ccitt_g4`].
///
/// # Safety
/// `pixels` must point to `pixels_len` readable bytes; the out pointers must be valid.
unsafe fn encode_ccitt_g4(
    pixels: *const u8,
    pixels_len: i64,
    width: i32,
    height: i32,
    invert: bool,
    out_ptr: *mut *mut u8,
    out_len: *mut i64,
) -> i32 {
    if pixels.is_null()
        || width <= 0
        || height <= 0
        || width > 64 * 1024
        || height > 64 * 1024
        || (width as i64) * (height as i64) > 64 * 1024 * 1024
    {
        return JPDFIUM_ERR_INVALID;
    }
    let row_bytes = (width as usize).div_ceil(8);
    let needed = row_bytes * height as usize;
    if pixels_len < 0 || needed > pixels_len as usize {
        return JPDFIUM_ERR_INVALID;
    }
    let data = slice::from_raw_parts(pixels, needed);
    let writer = fax::VecWriter::with_capacity(needed / 8 + 64);
    let mut encoder = fax::encoder::Encoder::new(writer);
    for y in 0..height as usize {
        let row = &data[y * row_bytes..(y + 1) * row_bytes];
        let line = (0..width as usize).map(|x| {
            let bit = (row[x / 8] >> (7 - (x % 8))) & 1;
            let white = if invert { bit == 0 } else { bit == 1 };
            if white {
                fax::Color::White
            } else {
                fax::Color::Black
            }
        });
        if encoder.encode_line(line, width as u32).is_err() {
            return JPDFIUM_ERR_GENERIC;
        }
    }
    let writer = match encoder.finish() {
        Ok(writer) => writer,
        Err(_) => return JPDFIUM_ERR_GENERIC,
    };
    let buffer = writer.finish();
    let ptr = malloc_copy(&buffer);
    if ptr.is_null() {
        return JPDFIUM_ERR_ALLOC;
    }
    *out_ptr = ptr;
    *out_len = buffer.len() as i64;
    JPDFIUM_OK
}

/// Encode a packed 1-bit image as a PDF JBIG2 fragment pair.
///
/// `pixels` is row-major, MSB-first, one bit per pixel; a set bit means white
/// (DeviceGray). The returned `page` is the image stream; `globals`, when
/// non-empty, is the `/JBIG2Globals` stream.
///
/// # Safety
/// `pixels` must point to `pixels_len` readable bytes; all out pointers must be valid.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn jpdfium_rust_encode_jbig2(
    pixels: *const u8,
    pixels_len: i64,
    width: i32,
    height: i32,
    invert: i32,
    page_out: *mut *mut u8,
    page_len: *mut i64,
    globals_out: *mut *mut u8,
    globals_len: *mut i64,
) -> i32 {
    if page_out.is_null() || page_len.is_null() || globals_out.is_null() || globals_len.is_null() {
        return JPDFIUM_ERR_INVALID;
    }
    *page_out = std::ptr::null_mut();
    *page_len = 0;
    *globals_out = std::ptr::null_mut();
    *globals_len = 0;
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        encode_jbig2(
            pixels,
            pixels_len,
            width,
            height,
            invert != 0,
            page_out,
            page_len,
            globals_out,
            globals_len,
        )
    }))
    .unwrap_or(JPDFIUM_ERR_GENERIC)
}

/// Encoding body for [`jpdfium_rust_encode_jbig2`].
///
/// # Safety
/// `pixels` must point to `pixels_len` readable bytes; all out pointers must be valid.
#[allow(clippy::too_many_arguments)]
unsafe fn encode_jbig2(
    pixels: *const u8,
    pixels_len: i64,
    width: i32,
    height: i32,
    invert: bool,
    page_out: *mut *mut u8,
    page_len: *mut i64,
    globals_out: *mut *mut u8,
    globals_len: *mut i64,
) -> i32 {
    if pixels.is_null()
        || width <= 0
        || height <= 0
        || width > 64 * 1024
        || height > 64 * 1024
        || (width as i64) * (height as i64) > 64 * 1024 * 1024
    {
        return JPDFIUM_ERR_INVALID;
    }
    let row_bytes = (width as usize).div_ceil(8);
    let needed = row_bytes * height as usize;
    if pixels_len < 0 || needed > pixels_len as usize {
        return JPDFIUM_ERR_INVALID;
    }
    let packed = slice::from_raw_parts(pixels, needed);
    let mut raster = Vec::with_capacity(width as usize * height as usize);
    for y in 0..height as usize {
        let row = &packed[y * row_bytes..(y + 1) * row_bytes];
        for x in 0..width as usize {
            let bit = (row[x / 8] >> (7 - (x % 8))) & 1;
            let white = if invert { bit == 0 } else { bit == 1 };
            raster.push(if white { 0 } else { 1 });
        }
    }
    let context =
        jbig2enc_rust::Jbig2Context::with_config(jbig2enc_rust::Jbig2Config::text_lossless(), true);
    let encoded = match jbig2enc_rust::encode_single_image_with_config(
        &raster,
        width as u32,
        height as u32,
        context,
    ) {
        Ok(encoded) => encoded,
        Err(_) => return JPDFIUM_ERR_GENERIC,
    };
    let page_ptr = malloc_copy(&encoded.page_data);
    if page_ptr.is_null() {
        return JPDFIUM_ERR_ALLOC;
    }
    *page_out = page_ptr;
    *page_len = encoded.page_data.len() as i64;
    if let Some(globals) = encoded.global_data {
        let globals_ptr = malloc_copy(&globals);
        if globals_ptr.is_null() {
            libc::free(page_ptr as *mut libc::c_void);
            *page_out = std::ptr::null_mut();
            *page_len = 0;
            return JPDFIUM_ERR_ALLOC;
        }
        *globals_out = globals_ptr;
        *globals_len = globals.len() as i64;
    }
    JPDFIUM_OK
}

#[cfg(test)]
mod jbig2_roundtrip_tests {
    use super::*;

    /// Glyph-like 1-bit tile: 8x10 with one 1-pixel notch/gap difference from
    /// its neighbours, so near-identical symbols ('c'/'e', '0'/'O', '1'/'l')
    /// exercise the symbol dictionary's exact-match path.
    fn glyph(kind: usize) -> [[bool; 8]; 10] {
        let mut g = [[false; 8]; 10];
        // Hollow box body shared by every glyph.
        for row in g.iter_mut().take(9).skip(1) {
            row[1] = true;
            row[6] = true;
        }
        g[1][1..7].fill(true);
        g[8][1..7].fill(true);
        match kind {
            0 => g[5][2] = true, // 'e' crossbar
            1 => {
                g[5][2] = true;
                g[4][3] = true;
            } // differs by one pixel
            2 => g[2][4] = true, // '0' dot
            3 => {
                g[2][4] = true;
                g[6][4] = true; // 'O' second dot
            }
            4 => g[3][2] = true, // 'l' stem
            _ => {}
        }
        g
    }

    #[test]
    fn near_identical_glyphs_round_trip_bit_exact() {
        const W: usize = 60;
        const H: usize = 40;
        let row_bytes = W.div_ceil(8);
        let mut packed = vec![0u8; row_bytes * H];

        // Two rows of five glyphs each, all near-identical but distinct.
        for i in 0..10 {
            let g = glyph(i % 6);
            let ox = 2 + (i % 5) * 11;
            let oy = 3 + (i / 5) * 16;
            for (y, row) in g.iter().enumerate() {
                for (x, &black) in row.iter().enumerate() {
                    if black {
                        let px = ox + x;
                        let py = oy + y;
                        // A set bit means white in the PDF stream, so black is 0.
                        packed[py * row_bytes + px / 8] &= !(0x80 >> (px % 8));
                    } else {
                        let px = ox + x;
                        let py = oy + y;
                        packed[py * row_bytes + px / 8] |= 0x80 >> (px % 8);
                    }
                }
            }
        }

        let mut page_ptr: *mut u8 = std::ptr::null_mut();
        let mut page_len: i64 = 0;
        let mut globals_ptr: *mut u8 = std::ptr::null_mut();
        let mut globals_len: i64 = 0;
        let rc = unsafe {
            encode_jbig2(
                packed.as_ptr(),
                packed.len() as i64,
                W as i32,
                H as i32,
                false,
                &mut page_ptr,
                &mut page_len,
                &mut globals_ptr,
                &mut globals_len,
            )
        };
        assert_eq!(rc, JPDFIUM_OK, "encode_jbig2 must succeed");
        assert!(!page_ptr.is_null() && page_len > 0);

        let page = unsafe { slice::from_raw_parts(page_ptr, page_len as usize) }.to_vec();
        let globals = if globals_ptr.is_null() {
            None
        } else {
            Some(unsafe { slice::from_raw_parts(globals_ptr, globals_len as usize) }.to_vec())
        };
        unsafe {
            libc::free(page_ptr as *mut libc::c_void);
            if !globals_ptr.is_null() {
                libc::free(globals_ptr as *mut libc::c_void);
            }
        }

        let decoded = jbig2enc_rust::decode::decode_embedded(
            globals.as_deref(),
            &page,
            &jbig2enc_rust::decode::DecodeOptions::default(),
        )
        .expect("encoded JBIG2 must decode");
        assert_eq!(decoded.width() as usize, W);
        assert_eq!(decoded.height() as usize, H);

        let decoded_bytes = decoded.as_msb_bytes().to_vec();
        assert_eq!(decoded_bytes.len(), packed.len());
        for y in 0..H {
            for x in 0..W {
                // The PDF stream stores 1 = white; the decoder's bitmap stores
                // 1 = black, so the two are exact complements of each other.
                let a = (packed[y * row_bytes + x / 8] >> (7 - (x % 8))) & 1;
                let b = (decoded_bytes[y * row_bytes + x / 8] >> (7 - (x % 8))) & 1;
                assert_eq!(1 - a, b, "pixel mismatch at ({x},{y})");
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use flate2::write::{DeflateEncoder, ZlibEncoder};
    use flate2::Compression;
    use lopdf::{dictionary, Document, Object, Stream};
    use std::io::Write;

    #[test]
    fn flate2_decompress_zlib_handles_standard_raw_and_bad_adler32() {
        let payload = b"BT /F1 12 Tf 72 700 Td (Repairable Flate Stream) Tj ET";

        let mut zenc = ZlibEncoder::new(Vec::new(), Compression::default());
        zenc.write_all(payload).unwrap();
        let valid_zlib = zenc.finish().unwrap();
        assert_eq!(
            flate2_decompress_zlib(&valid_zlib).as_deref(),
            Some(payload.as_slice())
        );

        // Raw Deflate without the 2-byte zlib header or 4-byte Adler-32 trailer.
        let mut denc = DeflateEncoder::new(Vec::new(), Compression::default());
        denc.write_all(payload).unwrap();
        let raw_deflate = denc.finish().unwrap();
        assert_eq!(
            flate2_decompress_zlib(&raw_deflate).as_deref(),
            Some(payload.as_slice())
        );

        // Valid CMF/FLG zlib header with corrupted Adler-32 trailer.
        let mut bad_adler = valid_zlib.clone();
        let last = bad_adler.len() - 1;
        bad_adler[last] ^= 0x5A;
        assert_eq!(
            flate2_decompress_zlib(&bad_adler).as_deref(),
            Some(payload.as_slice())
        );

        assert_eq!(flate2_decompress_zlib(b"\xFF\x00\xAB"), None);
    }

    #[test]
    fn compress_pdf_impl_preserves_dct_and_flate_filter_integrity() {
        let mut doc = Document::with_version("1.5");
        let pages_id = doc.new_object_id();

        let tiny_raw = b"q Q";
        let mut zenc = ZlibEncoder::new(Vec::new(), Compression::best());
        zenc.write_all(tiny_raw).unwrap();
        let bad_flate = {
            let mut z = zenc.finish().unwrap();
            let last = z.len() - 1;
            z[last] ^= 0xFF;
            z
        };
        let flate_id = doc.add_object(Stream::new(
            dictionary! { "Filter" => "FlateDecode" },
            bad_flate,
        ));

        let fake_jpeg = vec![
            0xFFu8, 0xD8, 0xFF, 0xE0, 0x00, 0x10, b'J', b'F', b'I', b'F', 0xFF, 0xD9,
        ];
        let dct_id = doc.add_object(Stream::new(
            dictionary! {
                "Type" => "XObject",
                "Subtype" => "Image",
                "Width" => 1,
                "Height" => 1,
                "Filter" => "DCTDecode",
            },
            fake_jpeg.clone(),
        ));

        let resources_id = doc.add_object(dictionary! {
            "XObject" => dictionary! { "Im0" => dct_id },
        });
        let page_id = doc.add_object(dictionary! {
            "Type" => "Page",
            "Parent" => pages_id,
            "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
            "Resources" => resources_id,
            "Contents" => flate_id,
        });
        doc.objects.insert(
            pages_id,
            Object::Dictionary(dictionary! {
                "Type" => "Pages",
                "Kids" => vec![page_id.into()],
                "Count" => 1,
            }),
        );
        let catalog_id = doc.add_object(dictionary! {
            "Type" => "Catalog",
            "Pages" => pages_id,
        });
        doc.trailer.set("Root", catalog_id);

        let mut input = Vec::new();
        doc.save_to(&mut Cursor::new(&mut input)).unwrap();

        let out_bytes = compress_pdf_impl(&input, 1).unwrap();
        let reloaded = Document::load_mem(&out_bytes).unwrap();

        let reloaded_dct = reloaded.get_object(dct_id).unwrap().as_stream().unwrap();
        assert_eq!(reloaded_dct.content, fake_jpeg);

        let reloaded_flate = reloaded.get_object(flate_id).unwrap().as_stream().unwrap();
        if is_plain_flate_filter(&reloaded_flate.dict) {
            assert_eq!(
                flate2_decompress_zlib(&reloaded_flate.content).as_deref(),
                Some(tiny_raw.as_slice()),
                "FlateDecode stream must never store uncompressed bytes without stripping /Filter"
            );
        } else {
            assert_eq!(reloaded_flate.content, tiny_raw);
        }
    }

    #[test]
    fn compress_pdf_impl_keeps_metadata_stream_uncompressed() {
        let mut doc = Document::with_version("1.4");
        let pages_id = doc.new_object_id();
        let xmp = b"<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\
            <x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\
            <rdf:Description rdf:about=\"\" xmlns:pdfaid=\"http://www.aiim.org/pdfa/ns/id/\" pdfaid:part=\"1\" pdfaid:conformance=\"B\"/>\
            </rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>".to_vec();
        let meta_id = doc.add_object(Stream::new(
            dictionary! {
                "Type" => "Metadata",
                "Subtype" => "XML",
            },
            xmp.clone(),
        ));
        let page_id = doc.add_object(dictionary! {
            "Type" => "Page",
            "Parent" => pages_id,
            "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
        });
        doc.objects.insert(
            pages_id,
            Object::Dictionary(dictionary! {
                "Type" => "Pages",
                "Kids" => vec![page_id.into()],
                "Count" => 1,
            }),
        );
        let catalog_id = doc.add_object(dictionary! {
            "Type" => "Catalog",
            "Pages" => pages_id,
            "Metadata" => meta_id,
        });
        doc.trailer.set("Root", catalog_id);

        let mut input = Vec::new();
        doc.save_to(&mut Cursor::new(&mut input)).unwrap();

        let out_bytes = compress_pdf_impl(&input, 1).unwrap();
        let reloaded = Document::load_mem(&out_bytes).unwrap();
        let reloaded_meta = reloaded.get_object(meta_id).unwrap().as_stream().unwrap();
        assert!(
            reloaded_meta.dict.get(b"Filter").is_err(),
            "Metadata stream must remain uncompressed for PDF/A-1 compliance"
        );
        assert_eq!(reloaded_meta.content, xmp);
    }

    #[test]
    fn resize_and_png_caps_reject_oversized_dimensions() {
        let mut out_ptr: *mut u8 = std::ptr::dangling_mut();
        let mut out_len: i64 = 999;
        let rgba = [255u8; 16];
        unsafe {
            assert!(
                jpdfium_rust_resize_pixels(
                    rgba.as_ptr(),
                    16,
                    2,
                    2,
                    4,
                    1 << 20,
                    1 << 20,
                    &mut out_ptr,
                    &mut out_len,
                ) < 0
            );
            assert!(out_ptr.is_null());
            assert_eq!(out_len, 0);

            assert!(
                jpdfium_rust_resize_pixels(
                    rgba.as_ptr(),
                    16,
                    2,
                    2,
                    4,
                    (MAX_IMAGE_DIM as i32) + 1,
                    1,
                    &mut out_ptr,
                    &mut out_len,
                ) < 0
            );
            assert!(out_ptr.is_null());
            assert_eq!(out_len, 0);

            let mut dst_px = [0u32; 4];
            assert!(
                jpdfium_rust_unpack_pixels(
                    rgba.as_ptr(),
                    1 << 20,
                    1 << 20,
                    4,
                    5,
                    dst_px.as_mut_ptr(),
                    4,
                ) < 0
            );

            let mut huge_png = Vec::new();
            huge_png.extend_from_slice(b"\x89PNG\r\n\x1a\n");
            huge_png.extend_from_slice(&13u32.to_be_bytes());
            huge_png.extend_from_slice(b"IHDR");
            huge_png.extend_from_slice(&(1u32 << 20).to_be_bytes());
            huge_png.extend_from_slice(&(1u32 << 20).to_be_bytes());
            huge_png.extend_from_slice(&[8, 6, 0, 0, 0]);
            assert!(
                jpdfium_rust_compress_png(
                    huge_png.as_ptr(),
                    huge_png.len() as i64,
                    &mut out_ptr,
                    &mut out_len,
                    2,
                ) < 0
            );
            assert!(out_ptr.is_null());
            assert_eq!(out_len, 0);
        }
    }

    #[test]
    fn compress_pdf_impl_rejects_flate_bomb_within_budget() {
        let mut zenc = ZlibEncoder::new(Vec::new(), Compression::fast());
        let chunk = vec![0u8; 1024 * 1024];
        for _ in 0..65 {
            zenc.write_all(&chunk).unwrap();
        }
        let bomb_bytes = zenc.finish().unwrap();

        let mut doc = Document::with_version("1.5");
        let bomb_id = doc.add_object(Stream::new(
            dictionary! { "Filter" => "FlateDecode" },
            bomb_bytes,
        ));
        let pages_id = doc.new_object_id();
        let page_id = doc.add_object(dictionary! {
            "Type" => "Page",
            "Parent" => pages_id,
            "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
            "Contents" => bomb_id,
        });
        doc.objects.insert(
            pages_id,
            Object::Dictionary(dictionary! {
                "Type" => "Pages",
                "Kids" => vec![page_id.into()],
                "Count" => 1,
            }),
        );
        let catalog_id = doc.add_object(dictionary! {
            "Type" => "Catalog",
            "Pages" => pages_id,
        });
        doc.trailer.set("Root", catalog_id);

        let mut input = Vec::new();
        doc.save_to(&mut Cursor::new(&mut input)).unwrap();

        assert!(compress_pdf_impl(&input, 1).is_err());
    }
}
