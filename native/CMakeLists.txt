cmake_minimum_required(VERSION 3.20)
project(jpdfium-native CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

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

set(JPDFIUM_PLATFORM "${JPDFIUM_OS}-${JPDFIUM_ARCH}")
message(STATUS "JPDFium target platform: ${JPDFIUM_PLATFORM}")

set(NATIVES_RESOURCES
    ${CMAKE_SOURCE_DIR}/../jpdfium-natives/jpdfium-natives-${JPDFIUM_PLATFORM}/src/main/resources/natives/${JPDFIUM_PLATFORM})

# Library output naming
if(JPDFIUM_OS STREQUAL "windows")
    set(JPDFIUM_LIB_NAME "jpdfium")
    set(JPDFIUM_LIB_EXTENSION ".dll")
else()
    set(JPDFIUM_LIB_NAME "jpdfium")
    set(JPDFIUM_LIB_EXTENSION "${CMAKE_SHARED_LIBRARY_SUFFIX}")
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

    # Try static first, then shared
    find_library(PDFIUM_LIB
        NAMES pdfium
        PATHS "${PDFIUM_LIB_DIR}"
        NO_DEFAULT_PATH)
    if(NOT PDFIUM_LIB)
        message(FATAL_ERROR "Could not find libpdfium in ${PDFIUM_LIB_DIR}")
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
    target_link_libraries(jpdfium PRIVATE "${PDFIUM_LIB}")

    # ── Rust integration (required for real PDFium build) ────────────────────
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
    # ld64 uses -force_load <path> (no bracketing — applies per-archive); MSVC
    # link.exe uses /WHOLEARCHIVE:<file>.
    if(APPLE)
        target_link_libraries(jpdfium PRIVATE
            -Wl,-force_load,"${RUST_LIB}")
    elseif(MSVC)
        # target_link_libraries treats /WHOLEARCHIVE: as a path and CMake
        # mangles it into a file lookup. target_link_options passes raw
        # flags to link.exe — use that instead and link the .lib normally.
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
    else()
        target_link_libraries(jpdfium PRIVATE
            -Wl,--whole-archive "${RUST_LIB}" -Wl,--no-whole-archive)
    endif()
    # Rust staticlibs don't carry their own platform system-lib dependencies —
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
    pkg_check_modules(QPDF REQUIRED IMPORTED_TARGET libqpdf)
    target_link_libraries(jpdfium PRIVATE PkgConfig::QPDF)
    target_compile_definitions(jpdfium PRIVATE JPDFIUM_HAS_QPDF)

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
    endif()

    # Hermetic natives jar: the publish workflows bundle each third-party
    # dynamic dependency (pcre2, freetype, harfbuzz, icu, qpdf, pugixml,
    # libunibreak — and their transitive deps) next to libjpdfium.* in the
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
    # Windows has no RUNPATH equivalent — the NativeLoader pre-loads each
    # dep by absolute path on Windows specifically (see Java side).

    set_target_properties(jpdfium PROPERTIES
        OUTPUT_NAME "jpdfium"
        POSITION_INDEPENDENT_CODE ON)

    # Platform-specific linker flags
    if(JPDFIUM_OS STREQUAL "linux")
        target_link_options(jpdfium PRIVATE -Wl,--no-undefined)
    elseif(JPDFIUM_OS STREQUAL "darwin")
        target_link_options(jpdfium PRIVATE -Wl,-undefined,error)
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
        COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:jpdfium_stub> ${NATIVES_RESOURCES}/libjpdfium.so
        COMMENT "Copied stub libjpdfium.so → jpdfium-natives-${JPDFIUM_PLATFORM} resources")
endif()

# Install target
if(JPDFIUM_USE_PDFIUM)
    install(TARGETS jpdfium
        LIBRARY DESTINATION lib
        RUNTIME DESTINATION bin)
endif()
install(DIRECTORY bridge/include/ DESTINATION include)
