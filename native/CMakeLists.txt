cmake_minimum_required(VERSION 3.20)
project(jpdfium-native CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
# NOTE: CMAKE_CXX_EXTENSIONS is left ON (default, -std=gnu++23) so the real
# (JPDFIUM_USE_PDFIUM) build keeps access to POSIX helpers like strdup that
# the bridge sources use liberally. Strict -std=c++23 hides those on glibc.
set(CMAKE_CXX_SCAN_FOR_MODULES OFF)  # bridge has no C++20 modules; skip the scan
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)
set(CMAKE_COMPILE_WARNING_AS_ERROR ON)

option(ENABLE_CLANG_TIDY "Run clang-tidy during build" OFF)
if(ENABLE_CLANG_TIDY)
  find_program(CLANG_TIDY_EXE NAMES clang-tidy REQUIRED)
  set(CMAKE_CXX_CLANG_TIDY "${CLANG_TIDY_EXE}")
endif()

# Sanitizer support (dev/CI builds). Usage:
#   cmake -DJPDFIUM_SANITIZE=address,undefined
# Applies -fsanitize=<san> + -fno-omit-frame-pointer to all native targets
# (stub and real bridge). thread is also supported for TSan runs.
option(JPDFIUM_SANITIZE "Comma-separated sanitizers (address,undefined,thread)" "")
if(JPDFIUM_SANITIZE AND NOT MSVC)
  string(REPLACE "," ";" JPDFIUM_SANITIZERS "${JPDFIUM_SANITIZE}")
  set(_jpdfium_san_flags "")
  foreach(_san IN LISTS JPDFIUM_SANITIZERS)
    if(NOT _san STREQUAL "")
      list(APPEND _jpdfium_san_flags "-fsanitize=${_san}")
    endif()
  endforeach()
  if(_jpdfium_san_flags)
    add_compile_options(${_jpdfium_san_flags} -fno-omit-frame-pointer)
    add_link_options(${_jpdfium_san_flags})
    message(STATUS "Sanitizers enabled: ${JPDFIUM_SANITIZE}")
  endif()
endif()

# Native smoke tests (CTest). Exercises the bridge end-to-end without the
# JVM: doc open -> page count -> page open -> render -> free -> close.
option(JPDFIUM_BUILD_TESTS "Build native smoke tests (CTest)" ON)

# libFuzzer harness for the bridge's untrusted-input boundary
# (doc_open_bytes / render / text / save). Requires Clang.
option(JPDFIUM_BUILD_FUZZERS "Build libFuzzer fuzz targets" OFF)

# Platform detection
if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    set(JPDFIUM_OS "linux")
elseif(CMAKE_SYSTEM_NAME STREQUAL "Darwin")
    set(JPDFIUM_OS "darwin")
elseif(CMAKE_SYSTEM_NAME STREQUAL "Windows")
    set(JPDFIUM_OS "windows")
else()
    message(WARNING "Unknown OS: ${CMAKE_SYSTEM_NAME}, defaulting to linux")
    set(JPDFIUM_OS "linux")
endif()

if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64|ARM64")
    set(JPDFIUM_ARCH "arm64")
elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64|AMD64|amd64")
    set(JPDFIUM_ARCH "x64")
else()
    message(WARNING "Unknown arch: ${CMAKE_SYSTEM_PROCESSOR}, defaulting to x64")
    set(JPDFIUM_ARCH "x64")
endif()

if(DEFINED JPDFIUM_TARGET_PLATFORM AND NOT JPDFIUM_TARGET_PLATFORM STREQUAL "")
    set(JPDFIUM_PLATFORM "${JPDFIUM_TARGET_PLATFORM}")
else()
    set(JPDFIUM_PLATFORM "${JPDFIUM_OS}-${JPDFIUM_ARCH}")
endif()
message(STATUS "JPDFium target platform: ${JPDFIUM_PLATFORM}")

set(NATIVES_RESOURCES
    ${CMAKE_SOURCE_DIR}/../jpdfium-natives/jpdfium-natives-${JPDFIUM_PLATFORM}/src/main/resources/natives/${JPDFIUM_PLATFORM})

# Library output naming
if(JPDFIUM_OS STREQUAL "windows")
    set(JPDFIUM_LIB_NAME "jpdfium")
    set(JPDFIUM_LIB_EXTENSION ".dll")
elseif(JPDFIUM_OS STREQUAL "darwin")
    set(JPDFIUM_LIB_NAME "libjpdfium")
    set(JPDFIUM_LIB_EXTENSION ".dylib")
else()
    set(JPDFIUM_LIB_NAME "libjpdfium")
    set(JPDFIUM_LIB_EXTENSION ".so")
endif()

# Off by default - stub is used for Java-layer unit tests.
# To use real PDFium:
#   cmake -DJPDFIUM_USE_PDFIUM=ON -DPDFIUM_DIR=/path/to/pdfium-linux-x64 ..
option(JPDFIUM_USE_PDFIUM "Build against real PDFium (requires PDFIUM_DIR)" OFF)

# Stub library (always built for testing).
# jpdfium_stub.cpp contains inline stubs for every jpdfium_rust_* function
# (they return JPDFIUM_ERR_NATIVE so unit tests run without PDFium/Rust).
add_library(jpdfium_stub SHARED bridge/src/jpdfium_stub.cpp)
target_include_directories(jpdfium_stub PUBLIC bridge/include)
if(MSVC)
    target_compile_options(jpdfium_stub PRIVATE /W4 /WX)
else()
    target_compile_options(jpdfium_stub PRIVATE -Wall -Wextra -Wpedantic -Werror)
endif()
set_target_properties(jpdfium_stub PROPERTIES
    OUTPUT_NAME "jpdfium_stub"
    POSITION_INDEPENDENT_CODE ON)


if(JPDFIUM_USE_PDFIUM)
    if(NOT DEFINED PDFIUM_DIR)
        message(FATAL_ERROR "PDFIUM_DIR must point to a pdfium-binaries extraction, e.g. /opt/pdfium")
    endif()

    # Typical bblanchon/pdfium-binaries layout:
    #   $PDFIUM_DIR/include/   - public headers
    #   $PDFIUM_DIR/lib/       - libpdfium.a (or libpdfium.so)
    set(PDFIUM_INCLUDE_DIR "${PDFIUM_DIR}/include")
    set(PDFIUM_LIB_DIR     "${PDFIUM_DIR}/lib")

    # Detect which prebuild variant we're consuming. setup-pdfium.sh's two
    # modes drop different artifacts here:
    #   component mode: libpdfium.{so,dylib} + sibling component .sos
    #                   (Linux/macOS) / pdfium.dll + sibling .dlls (Windows)
    #   static mode:    libpdfium.a (POSIX) / pdfium.lib (Windows) - a
    #                   single archive with PDFium + every bundled dep.
    #
    # We probe for the static archive first; if found, force whole-archive
    # linking so all the FPDF/EPDF symbols (and the bundled-dep code the
    # bridge transitively pulls) survive into the final libjpdfium.so.
    set(PDFIUM_STATIC_LIB "")
    if(WIN32)
        if(EXISTS "${PDFIUM_LIB_DIR}/pdfium.lib")
            set(PDFIUM_STATIC_LIB "${PDFIUM_LIB_DIR}/pdfium.lib")
        endif()
    else()
        if(EXISTS "${PDFIUM_LIB_DIR}/libpdfium.a")
            set(PDFIUM_STATIC_LIB "${PDFIUM_LIB_DIR}/libpdfium.a")
        endif()
    endif()

    if(PDFIUM_STATIC_LIB)
        set(PDFIUM_LINK_MODE "static")
        set(PDFIUM_LIB "${PDFIUM_STATIC_LIB}")
        message(STATUS "PDFium link mode: static (${PDFIUM_LIB})")
    else()
        find_library(PDFIUM_LIB
            NAMES pdfium
            PATHS "${PDFIUM_LIB_DIR}"
            NO_DEFAULT_PATH)
        if(NOT PDFIUM_LIB)
            message(FATAL_ERROR "Could not find libpdfium in ${PDFIUM_LIB_DIR}")
        endif()
        set(PDFIUM_LINK_MODE "component")
        message(STATUS "PDFium link mode: component (${PDFIUM_LIB})")
    endif()

    add_library(jpdfium SHARED
        bridge/src/jpdfium_document.cpp
        bridge/src/jpdfium_render.cpp
        bridge/src/jpdfium_text.cpp
        bridge/src/jpdfium_redact.cpp
        bridge/src/jpdfium_advanced.cpp
        bridge/src/jpdfium_image.cpp
        bridge/src/jpdfium_repair.cpp
        bridge/src/jpdfium_brotli.cpp
        bridge/src/jpdfium_pdfio.cpp
        bridge/src/jpdfium_lcms.cpp
        bridge/src/jpdfium_openjpeg.cpp)
    target_include_directories(jpdfium
        PUBLIC  bridge/include
        PRIVATE "${PDFIUM_INCLUDE_DIR}")
    # Strict warnings; -Werror deferred until the PDFium-dependent bridge can
    # be compiled (needs setup-pdfium.sh + system libs not present locally).
    if(MSVC)
        target_compile_options(jpdfium PRIVATE /W4)
    else()
        target_compile_options(jpdfium PRIVATE -Wall -Wextra -Wpedantic -Wno-ignored-qualifiers)
    endif()

    if(PDFIUM_LINK_MODE STREQUAL "static")
        # Whole-archive: pull every symbol from libpdfium.a (FPDF/EPDF exports
        # plus the bundled deps' code paths used transitively) into the final
        # libjpdfium.so. Without --whole-archive the linker would dead-strip
        # any pdfium-internal symbol not directly referenced by the bridge,
        # including FPDF_* exports the JVM resolves via dlsym at runtime.
        if(APPLE)
            target_link_options(jpdfium PRIVATE "-Wl,-force_load,${PDFIUM_LIB}")
        elseif(MSVC)
            target_link_options(jpdfium PRIVATE "/WHOLEARCHIVE:${PDFIUM_LIB}")
            target_link_libraries(jpdfium PRIVATE "${PDFIUM_LIB}")
        else()
            # On Linux, force the LLVM linker (lld) when consuming a PDFium
            # static archive built by Chromium's bundled clang. GNU binutils
            # ld 2.42 rejects the bundled-clang .eh_frame sections with
            # "error in libpdfium.a(*.o)(.eh_frame); no .eh_frame_hdr table"
            # plus "unknown architecture of input file is incompatible with
            # i386:x86-64 output" on strerror.o / errorcode.o / safe_strerror.o
            # / pngerror.o / jerror.o. lld parses the chromium-flavored object
            # format correctly. apt-install lld on the runner side.
            target_link_options(jpdfium PRIVATE "-fuse-ld=lld")
            target_link_options(jpdfium PRIVATE
                "-Wl,--whole-archive" "${PDFIUM_LIB}" "-Wl,--no-whole-archive")
        endif()
        # PDFium static archives reference platform libs that we'd otherwise
        # get transitively via libpdfium.so's NEEDED entries.
        if(UNIX AND NOT APPLE)
            target_link_libraries(jpdfium PRIVATE pthread dl m)
        endif()
        if(APPLE)
            target_link_libraries(jpdfium PRIVATE "-framework CoreFoundation"
                                                  "-framework CoreGraphics"
                                                  "-framework CoreText"
                                                  "-framework AppKit")
        endif()
    else()
        target_link_libraries(jpdfium PRIVATE "${PDFIUM_LIB}")
    endif()

    find_program(CARGO_CMD cargo)
    if(NOT CARGO_CMD)
        message(FATAL_ERROR
            "cargo not found. Rust is required to build JPDFium.\n"
            "Install Rust: curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh\n"
            "or run: bash native/build-rust.sh")
    endif()

    # Build the Rust static library. If the staticlib already exists, skip
    # the build (build-real.sh runs cargo build --release before invoking
    # cmake). Cargo's output name differs by toolchain: GNU-style targets
    # produce libjpdfium_rust.a, MSVC produces jpdfium_rust.lib.
    if(MSVC)
        set(RUST_LIB "${CMAKE_SOURCE_DIR}/rust/target/release/jpdfium_rust.lib")
    else()
        set(RUST_LIB "${CMAKE_SOURCE_DIR}/rust/target/release/libjpdfium_rust.a")
    endif()
    if(NOT EXISTS "${RUST_LIB}")
        message(STATUS "Building Rust static library...")
        execute_process(
            COMMAND ${CARGO_CMD} build --release
            WORKING_DIRECTORY "${CMAKE_SOURCE_DIR}/rust"
            RESULT_VARIABLE RUST_BUILD_RESULT)
        if(NOT RUST_BUILD_RESULT EQUAL 0)
            message(FATAL_ERROR
                "Rust build failed (exit code ${RUST_BUILD_RESULT}).\n"
                "Run manually: bash native/build-rust.sh")
        endif()
    endif()

    # Link the static library with whole-archive semantics so Rust #[no_mangle]
    # symbols are preserved (they are called from Java/FFM, not from C++ code).
    # GNU ld and lld use --whole-archive/--no-whole-archive bracketing; Apple's
    # ld64 uses -force_load <path> (no bracketing - applies per-archive); MSVC
    # link.exe uses /WHOLEARCHIVE:<file>.
    if(APPLE)
        target_link_libraries(jpdfium PRIVATE
            -Wl,-force_load,"${RUST_LIB}")
    elseif(MSVC)
        # target_link_libraries treats /WHOLEARCHIVE: as a path and CMake
        # mangles it into a file lookup. target_link_options passes raw
        # flags to link.exe - use that instead and link the .lib normally.
        # /FORCE:MULTIPLE silences LNK2005 for duplicate import descriptors:
        # Rust's staticlib bundles its own kernel32/bcryptprimitives import
        # stubs, and MSVC's auto-link adds them too. Both copies are
        # functionally identical metadata; the linker just needs permission
        # to keep one. (CRT MSVCRT also collides, which is unavoidable when
        # Rust ships its own runtime alongside the MSVC runtime.)
        target_link_libraries(jpdfium PRIVATE "${RUST_LIB}")
        target_link_options(jpdfium PRIVATE
            "/WHOLEARCHIVE:${RUST_LIB}"
            "/FORCE:MULTIPLE"
            "/IGNORE:4006"
            "/IGNORE:4088")
        # Export the Rust-provided symbols from jpdfium.dll. Rust's
        # #[no_mangle] extern "C" definitions don't carry __declspec(dllexport),
        # so without a .def the import library would omit them and neither the
        # CTest native_smoke test nor the Java FFM bindings could resolve them.
        target_sources(jpdfium PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/bridge/jpdfium.def")
    else()
        target_link_libraries(jpdfium PRIVATE
            -Wl,--whole-archive "${RUST_LIB}" -Wl,--no-whole-archive)
    endif()
    # Rust staticlibs don't carry their own platform system-lib dependencies -
    # the consuming target has to supply them.
    if(JPDFIUM_OS STREQUAL "linux")
        target_link_libraries(jpdfium PRIVATE dl pthread m)
    elseif(APPLE)
        # CoreFoundation: iana-time-zone calls CFRelease / CFCopyTimeZone etc.
        # Security + SystemConfiguration: pulled by common TLS/network crates.
        find_library(COREFOUNDATION_FRAMEWORK CoreFoundation)
        find_library(SECURITY_FRAMEWORK Security)
        find_library(SYSTEMCONFIGURATION_FRAMEWORK SystemConfiguration)
        target_link_libraries(jpdfium PRIVATE
            ${COREFOUNDATION_FRAMEWORK}
            ${SECURITY_FRAMEWORK}
            ${SYSTEMCONFIGURATION_FRAMEWORK})
    elseif(WIN32)
        # Rust crates link these via #[link] / #pragma comment, but staticlibs
        # need them at the consumer level. ws2_32 (sockets), userenv (env vars),
        # bcrypt + ncrypt (crypto), advapi32 (registry), ntdll (low-level).
        target_link_libraries(jpdfium PRIVATE
            ws2_32 userenv bcrypt ncrypt advapi32 ntdll)
    endif()
    target_compile_definitions(jpdfium PRIVATE JPDFIUM_HAS_RUST)
    message(STATUS "Rust integration: lopdf + zopfli + oxipng + fast_image_resize")
    message(STATUS "  Rust lib: ${RUST_LIB}")

    # Native library dependencies (all required for full functionality)
    find_package(PkgConfig REQUIRED)

    # PCRE2 - JIT-compiled regex for pattern matching
    pkg_check_modules(PCRE2 REQUIRED IMPORTED_TARGET libpcre2-8)
    target_link_libraries(jpdfium PRIVATE PkgConfig::PCRE2)
    target_compile_definitions(jpdfium PRIVATE JPDFIUM_HAS_PCRE2)

    # FreeType - font extraction, classification, width repair
    find_package(Freetype REQUIRED)
    target_link_libraries(jpdfium PRIVATE Freetype::Freetype)
    target_compile_definitions(jpdfium PRIVATE JPDFIUM_HAS_FREETYPE)

    # HarfBuzz - text shaping + hb-subset for font subsetting
    pkg_check_modules(HARFBUZZ REQUIRED IMPORTED_TARGET harfbuzz harfbuzz-subset)
    target_link_libraries(jpdfium PRIVATE PkgConfig::HARFBUZZ)
    target_compile_definitions(jpdfium PRIVATE JPDFIUM_HAS_HARFBUZZ)

    # ICU4C - Unicode normalization, BiDi, sentence segmentation
    pkg_check_modules(ICU REQUIRED IMPORTED_TARGET icu-uc icu-i18n)
    target_link_libraries(jpdfium PRIVATE PkgConfig::ICU)
    target_compile_definitions(jpdfium PRIVATE JPDFIUM_HAS_ICU)

    # qpdf - PDF structure manipulation, stream replacement
    pkg_check_modules(QPDF REQUIRED libqpdf)
    # qpdf's pkg-config Cflags inject -std=c++20, which appears AFTER
    # -std=gnu++23 on the compile line and silently downgrades the whole
    # target to C++20 (last -std= wins). Strip it and keep gnu++23.
    string(REGEX REPLACE "-std=[^ ;]+" "" QPDF_CFLAGS_OTHER "${QPDF_CFLAGS_OTHER}")
    target_include_directories(jpdfium PRIVATE ${QPDF_INCLUDE_DIRS})
    target_link_directories(jpdfium PRIVATE ${QPDF_LIBRARY_DIRS})
    target_link_libraries(jpdfium PRIVATE ${QPDF_LIBRARIES})
    target_compile_options(jpdfium PRIVATE ${QPDF_CFLAGS_OTHER})
    target_compile_definitions(jpdfium PRIVATE JPDFIUM_HAS_QPDF)
    # Silence qpdf's PointerHolder transition #warning (treated as error by
    # CMAKE_COMPILE_WARNING_AS_ERROR). The bridge uses the modern
    # std::shared_ptr qpdf API (e.g. QPDFWriter::getBufferSharedPointer) and
    # never references PointerHolder directly.
    target_compile_definitions(jpdfium PRIVATE POINTERHOLDER_TRANSITION)

    # pugixml - XMP metadata XML parsing
    pkg_check_modules(PUGIXML REQUIRED IMPORTED_TARGET pugixml)
    target_link_libraries(jpdfium PRIVATE PkgConfig::PUGIXML)
    target_compile_definitions(jpdfium PRIVATE JPDFIUM_HAS_PUGIXML)

    # libunibreak - grapheme cluster boundaries. vcpkg's port ships a CMake
    # config but not a .pc file, so on Windows the pkg_check_modules lookup
    # fails. Fall back to find_library / find_path when pkg-config can't
    # locate it.
    pkg_check_modules(UNIBREAK IMPORTED_TARGET libunibreak)
    if(UNIBREAK_FOUND)
        target_link_libraries(jpdfium PRIVATE PkgConfig::UNIBREAK)
    else()
        find_library(UNIBREAK_LIB NAMES unibreak libunibreak REQUIRED)
        find_path(UNIBREAK_INCLUDE_DIR NAMES unibreakdef.h linebreak.h REQUIRED)
        target_link_libraries(jpdfium PRIVATE "${UNIBREAK_LIB}")
        target_include_directories(jpdfium PRIVATE "${UNIBREAK_INCLUDE_DIR}")
        message(STATUS "  libunibreak (find_library fallback): ${UNIBREAK_LIB}")
    endif()
    target_compile_definitions(jpdfium PRIVATE JPDFIUM_HAS_UNIBREAK)

    message(STATUS "JPDFium native libraries: PCRE2, FreeType, HarfBuzz, ICU4C, qpdf, pugixml, libunibreak")

    if(WIN32)
        # windows.h defines min and max as macros, which clobber std::min /
        # std::max in jpdfium_redact.cpp (overlapRatio) and probably other
        # call sites. NOMINMAX suppresses those macros so the std:: versions
        # work as expected. WIN32_LEAN_AND_MEAN also trims unneeded headers.
        target_compile_definitions(jpdfium PRIVATE NOMINMAX WIN32_LEAN_AND_MEAN)
        # The bridge uses POSIX helpers (strdup, fopen, etc.) directly. On MSVC
        # these trigger C4996 deprecation warnings which are promoted to
        # errors by CMAKE_COMPILE_WARNING_AS_ERROR. _CRT_SECURE_NO_WARNINGS
        # silences deprecation for the secure-CRT transition, and
        # _CRT_NONSTDC_NO_WARNINGS silences deprecation for POSIX names.
        target_compile_definitions(jpdfium PRIVATE _CRT_SECURE_NO_WARNINGS _CRT_NONSTDC_NO_WARNINGS)
    endif()

    # Hermetic natives jar: the publish workflows bundle each third-party
    # dynamic dependency (pcre2, freetype, harfbuzz, icu, qpdf, pugixml,
    # libunibreak - and their transitive deps) next to libjpdfium.* in the
    # platform's natives jar so downstream users don't need apt/brew installs.
    # The bridge needs to look in its own directory FIRST for those deps:
    if(APPLE)
        # macOS dyld: @loader_path resolves to the dir of the loading binary.
        set_target_properties(jpdfium PROPERTIES
            INSTALL_RPATH "@loader_path"
            BUILD_RPATH "@loader_path")
    elseif(UNIX)
        # Linux ld.so: $ORIGIN is the dir of the loading binary. -z origin
        # enables it; the literal $ORIGIN must reach the linker unexpanded.
        set_target_properties(jpdfium PROPERTIES
            INSTALL_RPATH "\$ORIGIN"
            BUILD_RPATH "\$ORIGIN")
        target_link_options(jpdfium PRIVATE "-Wl,-rpath,\$ORIGIN" "-Wl,-z,origin")
    endif()
    # Windows has no RUNPATH equivalent - the NativeLoader pre-loads each
    # dep by absolute path on Windows specifically (see Java side).

    set_target_properties(jpdfium PROPERTIES
        OUTPUT_NAME "jpdfium"
        POSITION_INDEPENDENT_CODE ON)

    # Platform-specific linker flags
    # Under a sanitizer build the bridge references __asan_*/__ubsan_* runtime
    # symbols that are only resolved at the final EXECUTABLE, so the shared lib
    # must not demand them at its own link time (--no-undefined / -undefined,error
    # would fail). The sanitizer build is a test/fuzz configuration only; the
    # strict checks stay on for normal release builds.
    if(JPDFIUM_OS STREQUAL "linux")
        if(NOT JPDFIUM_SANITIZE)
            target_link_options(jpdfium PRIVATE -Wl,--no-undefined)
        endif()
    elseif(JPDFIUM_OS STREQUAL "darwin")
        if(NOT JPDFIUM_SANITIZE)
            target_link_options(jpdfium PRIVATE -Wl,-undefined,error)
        endif()
    endif()

    add_custom_command(TARGET jpdfium POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E make_directory ${NATIVES_RESOURCES}
        COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:jpdfium> ${NATIVES_RESOURCES}/$<TARGET_FILE_NAME:jpdfium>
        COMMENT "Copied lib${JPDFIUM_LIB_NAME}${JPDFIUM_LIB_EXTENSION} → jpdfium-natives-${JPDFIUM_PLATFORM} resources")

    # Also copy libpdfium shared lib(s) if they exist (needed at runtime).
    # With EmbedPDF component build, there may be multiple .so files.
    file(GLOB PDFIUM_SHARED_LIBS "${PDFIUM_LIB_DIR}/lib*${CMAKE_SHARED_LIBRARY_SUFFIX}")
    if(PDFIUM_SHARED_LIBS)
        add_custom_command(TARGET jpdfium POST_BUILD
            COMMAND ${CMAKE_COMMAND} -E copy ${PDFIUM_SHARED_LIBS} ${NATIVES_RESOURCES}/
            COMMENT "Copied libpdfium → natives resources")
    endif()

    # Generate native-libs.txt manifest for Java NativeLoader
    add_custom_command(TARGET jpdfium POST_BUILD
        COMMAND ${CMAKE_COMMAND}
            -DNATIVES_DIR=${NATIVES_RESOURCES}
            -DLIB_SUFFIX=${CMAKE_SHARED_LIBRARY_SUFFIX}
            -P ${CMAKE_SOURCE_DIR}/cmake/generate-native-libs-txt.cmake
        COMMENT "Generated native-libs.txt")

else()
    # Stub target is the default "jpdfium" for Java tests
    add_library(jpdfium ALIAS jpdfium_stub)

    add_custom_command(TARGET jpdfium_stub POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E make_directory ${NATIVES_RESOURCES}
        COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:jpdfium_stub> ${NATIVES_RESOURCES}/${JPDFIUM_LIB_NAME}${JPDFIUM_LIB_EXTENSION}
        COMMENT "Copied stub ${JPDFIUM_LIB_NAME}${JPDFIUM_LIB_EXTENSION} → jpdfium-natives-${JPDFIUM_PLATFORM} resources")

    add_custom_command(TARGET jpdfium_stub POST_BUILD
        COMMAND ${CMAKE_COMMAND}
            -DNATIVES_DIR=${NATIVES_RESOURCES}
            -DLIB_SUFFIX=${JPDFIUM_LIB_EXTENSION}
            -P ${CMAKE_SOURCE_DIR}/cmake/generate-native-libs-txt.cmake
        COMMENT "Generated native-libs.txt for stub")
endif()

if(JPDFIUM_USE_PDFIUM)
    include(CheckSymbolExists)
    set(CMAKE_REQUIRED_INCLUDES "${PDFIUM_INCLUDE_DIR}")
    check_symbol_exists(FPDF_RenderPageSkia "fpdfview.h" JPDFIUM_HAS_SKIA)
    if(NOT JPDFIUM_HAS_SKIA)
        message(STATUS "Skia check: FPDF_RenderPageSkia not found (using standard AGG renderer backend)")
    else()
        message(STATUS "Skia check: FPDF_RenderPageSkia found, Skia backend confirmed")
        # The bridge sources gate Skia paths on #ifdef JPDFIUM_HAS_SKIA.
        target_compile_definitions(jpdfium PRIVATE JPDFIUM_HAS_SKIA=1)
    endif()
endif()

if(JPDFIUM_BUILD_TESTS)
    enable_testing()
    add_executable(native_smoke bridge/tests/smoke.cpp)
    target_include_directories(native_smoke PRIVATE bridge/include)
    if(MSVC)
        target_compile_options(native_smoke PRIVATE /W4 /WX)
    else()
        target_compile_options(native_smoke PRIVATE -Wall -Wextra -Wpedantic -Werror)
    endif()
    if(JPDFIUM_USE_PDFIUM)
        target_link_libraries(native_smoke PRIVATE jpdfium)
    else()
        target_link_libraries(native_smoke PRIVATE jpdfium_stub)
    endif()
    add_test(NAME native_smoke COMMAND native_smoke)
    # Component PDFium builds: the sibling libpdfium.* lives in PDFIUM_LIB_DIR
    # and is only resolvable at test time via the library search path.
    if(JPDFIUM_USE_PDFIUM AND PDFIUM_LINK_MODE STREQUAL "component")
        if(JPDFIUM_OS STREQUAL "darwin")
            set_tests_properties(native_smoke PROPERTIES ENVIRONMENT
                "DYLD_LIBRARY_PATH=${PDFIUM_LIB_DIR}:$ENV{DYLD_LIBRARY_PATH}")
        elseif(JPDFIUM_OS STREQUAL "linux")
            set_tests_properties(native_smoke PROPERTIES ENVIRONMENT
                "LD_LIBRARY_PATH=${PDFIUM_LIB_DIR}:$ENV{LD_LIBRARY_PATH}")
        endif()
    endif()
endif()

if(JPDFIUM_BUILD_FUZZERS)
    if(NOT CMAKE_CXX_COMPILER_ID MATCHES "Clang")
        message(FATAL_ERROR "JPDFIUM_BUILD_FUZZERS requires Clang (-fsanitize=fuzzer)")
    endif()
    add_executable(jpdfium_fuzz bridge/fuzz/jpdfium_fuzz.cpp)
    target_include_directories(jpdfium_fuzz PRIVATE bridge/include)
    target_compile_options(jpdfium_fuzz PRIVATE -fsanitize=fuzzer,address -fno-omit-frame-pointer -g)
    target_link_options(jpdfium_fuzz PRIVATE -fsanitize=fuzzer,address)
    if(JPDFIUM_USE_PDFIUM)
        target_link_libraries(jpdfium_fuzz PRIVATE jpdfium)
        if(PDFIUM_LINK_MODE STREQUAL "component")
            target_link_options(jpdfium_fuzz PRIVATE "-Wl,-rpath,${PDFIUM_LIB_DIR}")
        endif()
    else()
        target_link_libraries(jpdfium_fuzz PRIVATE jpdfium_stub)
    endif()
endif()

# Install target
if(JPDFIUM_USE_PDFIUM)
    install(TARGETS jpdfium
        LIBRARY DESTINATION lib
        RUNTIME DESTINATION bin)
endif()
install(DIRECTORY bridge/include/ DESTINATION include)
