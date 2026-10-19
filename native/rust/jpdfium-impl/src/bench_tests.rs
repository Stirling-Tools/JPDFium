//! Timing and allocation benchmarks for the SVG/PDF entry points.
//! Run with: cargo test --release --lib bench_all -- --nocapture --test-threads=1

use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Instant;

// Counts allocation traffic so the ABI copies and internal buffers are visible.
struct Counting;

static ALLOC_CALLS: AtomicUsize = AtomicUsize::new(0);
static ALLOC_BYTES: AtomicUsize = AtomicUsize::new(0);

unsafe impl GlobalAlloc for Counting {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        ALLOC_CALLS.fetch_add(1, Ordering::Relaxed);
        ALLOC_BYTES.fetch_add(layout.size(), Ordering::Relaxed);
        unsafe { System.alloc(layout) }
    }

    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        unsafe { System.dealloc(ptr, layout) }
    }

    unsafe fn alloc_zeroed(&self, layout: Layout) -> *mut u8 {
        ALLOC_CALLS.fetch_add(1, Ordering::Relaxed);
        ALLOC_BYTES.fetch_add(layout.size(), Ordering::Relaxed);
        unsafe { System.alloc_zeroed(layout) }
    }

    unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, new_size: usize) -> *mut u8 {
        ALLOC_CALLS.fetch_add(1, Ordering::Relaxed);
        ALLOC_BYTES.fetch_add(new_size, Ordering::Relaxed);
        unsafe { System.realloc(ptr, layout, new_size) }
    }
}

#[global_allocator]
static GLOBAL: Counting = Counting;

fn counters() -> (usize, usize) {
    (ALLOC_CALLS.load(Ordering::Relaxed), ALLOC_BYTES.load(Ordering::Relaxed))
}

struct Stats {
    min: f64,
    median: f64,
    p95: f64,
    max: f64,
}

/// Warm up, then time `iters` runs, resetting allocation counters per run.
fn measure<F: FnMut() -> usize>(mut f: F, warmup: usize, iters: usize) -> (Stats, usize, usize) {
    for _ in 0..warmup {
        std::hint::black_box(f());
    }
    let mut times = Vec::with_capacity(iters);
    let mut alloc_calls = 0usize;
    let mut alloc_bytes = 0usize;
    for _ in 0..iters {
        let (c0, b0) = counters();
        let start = Instant::now();
        let out = f();
        times.push(start.elapsed().as_secs_f64() * 1e6);
        let (c1, b1) = counters();
        alloc_calls += c1 - c0;
        alloc_bytes += b1 - b0;
        std::hint::black_box(out);
    }
    times.sort_by(|a, b| a.partial_cmp(b).unwrap());
    let n = times.len();
    let stats = Stats {
        min: times[0],
        median: times[n / 2],
        p95: times[(n * 95 / 100).min(n - 1)],
        max: times[n - 1],
    };
    (stats, alloc_calls / n, alloc_bytes / n)
}

fn report(name: &str, stats: &Stats, calls: usize, bytes: usize) {
    println!(
        "{:<26} min {:>9.1} med {:>9.1} p95 {:>9.1} max {:>9.1} us | {:>5} allocs {:>9} B",
        name, stats.min, stats.median, stats.p95, stats.max, calls, bytes
    );
}

fn usvg_options() -> usvg::Options<'static> {
    let mut options = usvg::Options::default();
    options.fontdb = super::system_fontdb();
    options.image_href_resolver = usvg::ImageHrefResolver {
        resolve_data: usvg::ImageHrefResolver::default_data_resolver(),
        resolve_string: Box::new(|_, _| None),
    };
    options
}

fn usvg_options_v47() -> usvg_v47::Options<'static> {
    let mut options = usvg_v47::Options::default();
    options.fontdb = super::system_fontdb_v47();
    options.image_href_resolver = usvg_v47::ImageHrefResolver {
        resolve_data: usvg_v47::ImageHrefResolver::default_data_resolver(),
        resolve_string: Box::new(|_, _| None),
    };
    options
}

fn small_svg() -> Vec<u8> {
    concat!(
        r##"<svg xmlns="http://www.w3.org/2000/svg" width="200" height="120" viewBox="0 0 200 120">"##,
        r##"<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="0">"##,
        r##"<stop offset="0" stop-color="#3366ff"/><stop offset="1" stop-color="#ffcc00"/>"##,
        r##"</linearGradient></defs><rect x="10" y="10" width="80" height="50" fill="url(#g)"/>"##,
        r##"<circle cx="150" cy="60" r="40" fill="#22aa55"/>"##,
        r##"<text x="20" y="100" font-family="Helvetica" font-size="18">Vector text</text></svg>"##
    )
    .as_bytes()
    .to_vec()
}

/// Synthetic vector-heavy page: bezier paths with a gradient fill plus text.
fn heavy_svg(shapes: usize) -> Vec<u8> {
    let mut svg = String::with_capacity(shapes * 160 + 400);
    svg.push_str(
        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"800\" height=\"600\" viewBox=\"0 0 800 600\">",
    );
    svg.push_str("<defs><linearGradient id=\"g\"><stop offset=\"0\" stop-color=\"#f00\"/>");
    svg.push_str("<stop offset=\"1\" stop-color=\"#00f\"/></linearGradient></defs>");
    for i in 0..shapes {
        let x = (i * 37) % 700;
        let y = (i * 53) % 520;
        svg.push_str(&format!(
            "<path d=\"M{} {} C {} {} {} {} {} {}\" fill=\"url(#g)\" stroke=\"#123\" stroke-width=\"1.5\"/>",
            x,
            y,
            x + 40,
            y + 10,
            x + 80,
            y + 25,
            x + 120,
            y + 5
        ));
    }
    svg.push_str("<text x=\"20\" y=\"590\" font-family=\"Helvetica\" font-size=\"14\">bench</text>");
    svg.push_str("</svg>");
    svg.into_bytes()
}

fn pdf_fixture(relative: &str) -> Option<Vec<u8>> {
    let path = format!(
        "{}/../../../jpdfium/src/test/resources/pdfs/{}",
        env!("CARGO_MANIFEST_DIR"),
        relative
    );
    std::fs::read(path).ok()
}

fn svg_to_pdf_ffi(svg: &[u8]) -> usize {
    let mut ptr: *mut u8 = std::ptr::null_mut();
    let mut len: i64 = 0;
    let rc = unsafe {
        super::jpdfium_rust_svg_to_pdf(svg.as_ptr(), svg.len(), 0, 0, 1, &mut ptr, &mut len)
    };
    assert_eq!(rc, 0, "svg_to_pdf failed");
    let n = len as usize;
    unsafe { super::jpdfium_rust_free(ptr) };
    n
}

fn svg_to_rgba_ffi(svg: &[u8], width: i32, height: i32) -> usize {
    let mut ptr: *mut u8 = std::ptr::null_mut();
    let mut len: i64 = 0;
    let mut w: i32 = 0;
    let mut h: i32 = 0;
    let rc = unsafe {
        super::jpdfium_rust_svg_to_rgba(
            svg.as_ptr(),
            svg.len(),
            width,
            height,
            &mut ptr,
            &mut len,
            &mut w,
            &mut h,
        )
    };
    assert_eq!(rc, 0, "svg_to_rgba failed");
    let n = len as usize;
    unsafe { super::jpdfium_rust_free(ptr) };
    n
}

#[test]
fn bench_all() {
    let iters = 30;
    let small = small_svg();
    let heavy = heavy_svg(300);
    println!("\n--- fixtures: small {} B, heavy {} B ---", small.len(), heavy.len());

    // Parse-only vs render-only separates usvg from krilla.
    let (stats, calls, bytes) = measure(
        || {
            let options = usvg_options();
            let tree = usvg::Tree::from_data(&heavy, &options).unwrap();
            tree.size().width() as usize
        },
        3,
        iters,
    );
    report("usvg parse (heavy)", &stats, calls, bytes);

    let (stats, calls, bytes) = measure(
        || {
            let options = usvg_options();
            let tree = usvg::Tree::from_data(&small, &options).unwrap();
            tree.size().width() as usize
        },
        3,
        iters,
    );
    report("usvg parse (small)", &stats, calls, bytes);

    let heavy_tree = usvg_v47::Tree::from_data(&heavy, &usvg_options_v47()).unwrap();
    let (stats, calls, bytes) = measure(
        || super::svg_to_pdf_krilla(&heavy_tree, 800.0, 600.0, true).map_or(0, |v| v.len()),
        3,
        iters,
    );
    report("krilla render (heavy)", &stats, calls, bytes);

    let (stats, calls, bytes) = measure(|| svg_to_pdf_ffi(&heavy), 3, iters);
    report("svg_to_pdf FFI (heavy)", &stats, calls, bytes);

    let (stats, calls, bytes) = measure(|| svg_to_pdf_ffi(&small), 5, iters * 3);
    report("svg_to_pdf FFI (small)", &stats, calls, bytes);

    let (stats, calls, bytes) = measure(|| svg_to_rgba_ffi(&heavy, 800, 600), 3, iters);
    report("svg_to_rgba 800x600 (heavy)", &stats, calls, bytes);

    let (stats, calls, bytes) = measure(|| svg_to_rgba_ffi(&small, 0, 0), 5, iters * 3);
    report("svg_to_rgba natural (small)", &stats, calls, bytes);

    if let Some(pdf) = pdf_fixture("repair/pdfjs-issue6961.pdf") {
        let (stats, calls, bytes) = measure(
            || {
                let mut ptr: *mut u8 = std::ptr::null_mut();
                let mut len: i64 = 0;
                let rc = unsafe {
                    super::jpdfium_page_gradients(
                        pdf.as_ptr(),
                        pdf.len(),
                        0,
                        &mut ptr,
                        &mut len,
                    )
                };
                assert_eq!(rc, 0);
                let n = len as usize;
                unsafe { super::jpdfium_rust_free(ptr) };
                n
            },
            1,
            10,
        );
        report("page_gradients (6961 p0)", &stats, calls, bytes);
    } else {
        println!("page_gradients: fixture missing, skipped");
    }
    println!();
}

#[test]
fn bench_breakdown() {
    let Some(pdf) = pdf_fixture("repair/pdfjs-issue6961.pdf") else {
        println!("breakdown: fixture missing, skipped");
        return;
    };
    println!("\n== page_gradients breakdown ({} bytes) ==", pdf.len());
    let (stats, calls, bytes) = measure(
        || {
            let doc = lopdf::Document::load_from(std::io::Cursor::new(&pdf)).unwrap();
            doc.objects.len()
        },
        2,
        10,
    );
    report("lopdf load only", &stats, calls, bytes);

    let (stats, calls, bytes) = measure(
        || {
            let payload = |p: &[u8]| {
                let mut ptr: *mut u8 = std::ptr::null_mut();
                let mut len: i64 = 0;
                let rc = unsafe {
                    super::jpdfium_page_gradients(p.as_ptr(), p.len(), 0, &mut ptr, &mut len)
                };
                assert_eq!(rc, 0);
                let n = len as usize;
                unsafe { super::jpdfium_rust_free(ptr) };
                n
            };
            payload(&pdf)
        },
        1,
        10,
    );
    report("page_gradients total", &stats, calls, bytes);
    println!();
}

fn unpack_ffi(src: &[u8], w: i32, h: i32, stride: i64, format: i32) -> usize {
    let pixels = (w as usize) * (h as usize);
    let mut dst = vec![0u32; pixels];
    let rc = unsafe {
        super::jpdfium_rust_unpack_pixels(
            src.as_ptr(),
            w,
            h,
            stride,
            format,
            dst.as_mut_ptr(),
            pixels as i64,
        )
    };
    assert_eq!(rc, 0, "unpack_pixels failed");
    dst[pixels - 1] as usize
}

/// Our own per-pixel unpack loop: the pure-SIMD candidate, plus the resize delegate.
#[test]
fn bench_images() {
    let (w, h) = (2000i32, 2000i32);
    let n = (w as usize) * (h as usize);
    let gray = vec![128u8; n];
    let rgb: Vec<u8> = (0..n * 3).map(|i| (i % 251) as u8).collect();
    let cmyk: Vec<u8> = (0..n * 4).map(|i| (i % 253) as u8).collect();
    println!("\n== unpack_pixels {}x{} ==", w, h);
    let (stats, calls, bytes) = measure(|| unpack_ffi(&gray, w, h, w as i64, 1), 2, 10);
    report("unpack gray8", &stats, calls, bytes);
    let (stats, calls, bytes) = measure(|| unpack_ffi(&rgb, w, h, (w * 3) as i64, 2), 2, 10);
    report("unpack rgb24", &stats, calls, bytes);
    let (stats, calls, bytes) = measure(|| unpack_ffi(&cmyk, w, h, (w * 4) as i64, 3), 2, 10);
    report("unpack cmyk32", &stats, calls, bytes);

    println!("\n== resize_pixels 2000x2000 -> 800x800 ==");
    let (stats, calls, bytes) = measure(
        || {
            let mut ptr: *mut u8 = std::ptr::null_mut();
            let mut len: i64 = 0;
            let rc = unsafe {
                super::jpdfium_rust_resize_pixels(
                    rgb.as_ptr(),
                    rgb.len() as i64,
                    w,
                    h,
                    3,
                    800,
                    800,
                    &mut ptr,
                    &mut len,
                )
            };
            assert_eq!(rc, 0, "resize_pixels failed");
            let n = len as usize;
            unsafe { super::jpdfium_rust_free(ptr) };
            n
        },
        2,
        5,
    );
    report("resize lanczos3 rgb", &stats, calls, bytes);
    println!();
}

/// One document parse per export versus one parse per page, on a 100-page file.
#[test]
fn bench_file_gradients() {
    let Some(pdf) = pdf_fixture("redact/redact-test-100pages.pdf") else {
        println!("file gradients: fixture missing, skipped");
        return;
    };
    let dir = std::env::temp_dir().join(format!("jpdfium-bench-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("100p.pdf");
    std::fs::write(&path, &pdf).unwrap();
    let c_path = std::ffi::CString::new(path.to_str().unwrap()).unwrap();
    let pages: Vec<i32> = (0..100).collect();
    println!("\n== gradients, {} pages, {} B ==", pages.len(), pdf.len());

    let (stats, calls, bytes) = measure(
        || {
            let mut total = 0usize;
            for p in &pages {
                let mut ptr: *mut u8 = std::ptr::null_mut();
                let mut len: i64 = 0;
                let rc = unsafe {
                    super::jpdfium_page_gradients(pdf.as_ptr(), pdf.len(), *p, &mut ptr, &mut len)
                };
                assert_eq!(rc, 0);
                total += len as usize;
                unsafe { super::jpdfium_rust_free(ptr) };
            }
            total
        },
        1,
        5,
    );
    report("byte route, 100 calls", &stats, calls, bytes);

    let (stats, calls, bytes) = measure(
        || {
            let mut ptr: *mut u8 = std::ptr::null_mut();
            let mut len: i64 = 0;
            let rc = unsafe {
                super::jpdfium_page_gradients_file(
                    c_path.as_ptr(),
                    pages.as_ptr(),
                    pages.len() as i32,
                    &mut ptr,
                    &mut len,
                )
            };
            assert_eq!(rc, 0);
            let n = len as usize;
            unsafe { super::jpdfium_rust_free(ptr) };
            n
        },
        1,
        5,
    );
    report("file route, 1 call", &stats, calls, bytes);
    println!();
    let _ = std::fs::remove_dir_all(&dir);
}

/// Long-running loop so an external sampler (macOS `sample`) can profile it.
/// Set JPDFIUM_PROFILE=svg_pdf|svg_rgba|gradients|load and JPDFIUM_PROFILE_SECS.
#[test]
fn profile_loop() {
    let Ok(which) = std::env::var("JPDFIUM_PROFILE") else {
        return;
    };
    let seconds: f64 = std::env::var("JPDFIUM_PROFILE_SECS")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(10.0);
    let heavy = heavy_svg(300);
    let pdf = pdf_fixture("repair/pdfjs-issue6961.pdf").unwrap_or_default();
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs_f64(seconds);
    let mut iterations: u64 = 0;
    let mut checksum: usize = 0;
    while std::time::Instant::now() < deadline {
        checksum ^= match which.as_str() {
            "svg_pdf" => svg_to_pdf_ffi(&heavy),
            "svg_rgba" => svg_to_rgba_ffi(&heavy, 1600, 1200),
            "load" => lopdf::Document::load_from(std::io::Cursor::new(&pdf))
                .map(|d| d.objects.len())
                .unwrap_or(0),
            "gradients" => {
                if pdf.is_empty() {
                    0
                } else {
                    let mut ptr: *mut u8 = std::ptr::null_mut();
                    let mut len: i64 = 0;
                    let rc = unsafe {
                        super::jpdfium_page_gradients(
                            pdf.as_ptr(),
                            pdf.len(),
                            0,
                            &mut ptr,
                            &mut len,
                        )
                    };
                    assert_eq!(rc, 0);
                    let n = len as usize;
                    unsafe { super::jpdfium_rust_free(ptr) };
                    n
                }
            }
            _ => panic!("unknown JPDFIUM_PROFILE={which}"),
        };
        iterations += 1;
    }
    println!("profile_loop {which}: {iterations} iterations, checksum {checksum}");
}
